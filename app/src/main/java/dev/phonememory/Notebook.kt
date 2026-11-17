package dev.phonememory

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

enum class Work(val label: String) {
    OPEN("Opening notebook"), SAVE("Saving"), FORGET("Forgetting"), IMPORT("Reading selected text"),
    MODEL("Updating local model"), RECORD("Recording"), SPEECH("Transcribing on this phone"),
    INSIGHT("Finding a highlight on this phone")
}

class Notebook(private val app: Application) : AndroidViewModel(app) {
    private val vault = Vault(File(app.noBackupFilesDir, "notebook"), VaultKey()::get)
    private val inference = LocalInference(app)
    private val prefs = app.getSharedPreferences("work-status", Context.MODE_PRIVATE)
    private val fence = ReadFence()
    private var job: Job? = null
    private var mic: ForegroundMic? = null
    var ready by mutableStateOf(false); private set
    var sources by mutableStateOf(emptyList<Source>()); private set
    var installed by mutableStateOf(emptySet<Model>()); private set
    var work by mutableStateOf<Work?>(null); private set
    var seconds by mutableIntStateOf(0); private set
    var speechHint by mutableStateOf(SpeechHint.AUTO); private set
    var message by mutableStateOf(""); private set
    var draft by mutableStateOf(""); private set
    var origin by mutableStateOf(Origin.NOTE); private set
    var selected by mutableStateOf<UUID?>(null); private set
    var highlight by mutableStateOf<Excerpt?>(null); private set

    init { open() }

    fun open() = launch(Work.OPEN, "Could not open local storage. Nothing was overwritten.") {
        sources = withContext(Dispatchers.IO) { vault.load() }
        installed = modelLock.withLock {
            withContext(Dispatchers.IO) {
                inference.models.values.forEach { it.clearInterruptedInstall() }
                installedModels()
            }
        }
        ready = true
        if (prefs.getBoolean("local-work", false)) {
            notice("Previous local processing was interrupted. Unsaved audio and results were discarded.")
            markProcessing(false)
        }
    }

    fun notice(text: String) { message = text }
    fun hint(value: SpeechHint) { if (work == null) speechHint = value }
    fun edit(value: String) {
        if (value.length <= MAX_TEXT_BYTES) draft = value else notice("Draft limit reached (16 KiB when saved).")
    }
    fun discardDraft() { draft = ""; origin = Origin.NOTE }
    fun view(id: UUID?) {
        if (work == Work.INSIGHT) cancelLocal()
        selected = id
        highlight = null
        message = ""
    }

    fun save() = launch(Work.SAVE, "Could not encrypt or save this source. Your draft is still here.") {
        val text = draft
        val kind = origin
        val source = withContext(Dispatchers.IO) { vault.add(kind, text) }
        sources = listOf(source) + sources
        discardDraft()
        view(source.id)
        notice("Saved on this phone.")
    }

    fun forget(id: UUID) {
        if (work == Work.INSIGHT) cancelLocal()
        launch(Work.FORGET, "Could not delete the source. It has not been marked forgotten.") {
            withContext(Dispatchers.IO) { vault.forget(id) }
            sources = sources.filterNot { it.id == id }
            view(null)
            notice("Source forgotten. Its search matches and highlight are gone.")
        }
    }

    fun importText(uri: Uri) = launch(Work.IMPORT, "Could not read the selected text file.") {
        requireEmptyDraft()
        val text = withContext(Dispatchers.IO) { stream(uri).use(::readTextImport) }
        draft = text
        origin = Origin.IMPORT
        notice("Review the imported text, then save. The original file is untouched.")
    }

    fun install(model: Model, uris: List<Uri>) = launch(Work.MODEL, "Model install failed. Check free space and reselect local files.") {
        installed = modelLock.withLock {
            withContext(Dispatchers.IO) {
                val ctx = currentCoroutineContext()
                inference.models.getValue(model).install(uris.map { uri -> { stream(uri) } }) { ctx.ensureActive() }
                installedModels()
            }
        }
        notice("Verified and installed ${model.title}.")
    }

    fun remove(model: Model) = launch(Work.MODEL, "Could not remove all model files.") {
        installed = modelLock.withLock {
            withContext(Dispatchers.IO) { inference.models.getValue(model).uninstall(); installedModels() }
        }
        notice("Removed ${model.title}. Your sources were not changed.")
    }

    fun record() = launch(Work.RECORD, "Local capture or transcription failed. No source was saved.") { token ->
        requireEmptyDraft()
        if (Model.SPEECH !in installed) throw LocalProblem("Install the multilingual speech model first.")
        markProcessing(true)
        seconds = 0
        val device = ForegroundMic()
        mic = device
        val audio = withContext(Dispatchers.IO) {
            device.collect { n -> viewModelScope.launch { if (fence.current(token)) seconds = n } }
        }
        try {
            mic = null
            work = Work.SPEECH
            val hint = speechHint
            val transcript = modelLock.withLock { withContext(Dispatchers.Default) { inference.transcribe(audio, hint) } }
            if (fence.current(token)) {
                draft = cleanText(transcript)
                origin = Origin.TRANSCRIPT
                notice("Unverified transcript. Check names, numbers and language switches before saving.")
            }
        } finally { audio.fill(0f) }
    }

    fun finishRecording() { mic?.stop(discard = false) }

    fun findHighlight(source: Source) = launch(Work.INSIGHT, "Local text inference failed. No highlight was saved.") { token ->
        highlight = null
        markProcessing(true)
        val result = modelLock.withLock { withContext(Dispatchers.Default) { inference.highlight(source) } }
        if (fence.permits(token, source.id, selected, sources)) {
            highlight = result
            notice(if (result == null) "No grounded highlight returned." else "Model-selected excerpt, not a verified fact.")
        }
    }

    fun cancelLocal() {
        if (work != Work.RECORD && work != Work.SPEECH && work != Work.INSIGHT) return
        fence.advance()
        mic?.stop(discard = true)
        mic = null
        job?.cancel()
        work = null
        markProcessing(false)
        notice("Local operation interrupted. Audio and unpublished results discarded.")
    }

    override fun onCleared() { cancelLocal(); super.onCleared() }

    private fun launch(kind: Work, failure: String, run: suspend (Long) -> Unit) {
        if (work != null || (kind != Work.OPEN && !ready)) return
        val token = fence.advance()
        work = kind
        message = ""
        job = viewModelScope.launch {
            try { run(token) }
            catch (e: CancellationException) { throw e }
            catch (e: LocalProblem) { if (fence.current(token)) notice(e.explanation) }
            // Provider/engine exceptions can contain personal input. Never log or render their messages.
            catch (_: Exception) { if (fence.current(token)) notice(failure) }
            catch (_: LinkageError) { if (fence.current(token)) notice("Local runtime unavailable on this device.") }
            finally {
                if (fence.current(token)) {
                    if (kind == Work.INSIGHT || kind == Work.RECORD) markProcessing(false)
                    mic = null
                    work = null
                }
            }
        }
    }

    private fun markProcessing(active: Boolean) { prefs.edit().putBoolean("local-work", active).apply() }
    private fun installedModels() = inference.models.filterValues { it.installed() }.keys.toSet()
    private fun stream(uri: Uri) = app.contentResolver.openInputStream(uri)
        ?: throw LocalProblem("The selected document is unavailable.")
    private fun requireEmptyDraft() {
        if (draft.isNotEmpty()) throw LocalProblem("Save or discard the current draft first.")
    }

    private companion object {
        // Synchronous native calls may finish after cancellation. Never unload their files or run a
        // second engine until the call actually returns, including across activity recreation.
        val modelLock = Mutex()
    }
}
