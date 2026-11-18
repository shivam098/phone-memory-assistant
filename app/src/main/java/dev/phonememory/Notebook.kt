package dev.phonememory

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
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
import java.util.UUID

enum class Work(val label: String) {
    OPEN("Opening notebook"), SAVE("Saving"), FORGET("Forgetting"), IMPORT("Reading selected text"),
    MODEL("Updating local model"), SPEECH("Transcribing pending audio"), REVIEW("Updating source"),
    REFRESH("Reading source status"), INSIGHT("Finding a highlight on this phone")
}

class Notebook(private val app: Application) : AndroidViewModel(app) {
    private val host = app as MemoryApp
    private val journal get() = host.journal
    private val inference = host.inference
    private val modelLock = host.modelLock
    val changes = host.changed
    private val power = PowerGuard(app)
    var lowLimit by mutableStateOf(power.threshold); private set
    fun setLimit(value: Int) { power.threshold = value; lowLimit = value }
    private val prefs = app.getSharedPreferences("work-status", Context.MODE_PRIVATE)
    private val fence = ReadFence()
    private var job: Job? = null
    private var visible = false
    private var refreshPending = false
    private var lastProcessed: UUID? = null
    fun backlogForDisplay() = journal.backlog()
    val awaitingUnlock get() = journal.awaitingUnlock
    val listening = host.listening
    var ready by mutableStateOf(false); private set
    var sources by mutableStateOf(emptyList<Source>()); private set
    var installed by mutableStateOf(emptySet<Model>()); private set
    var work by mutableStateOf<Work?>(null); private set
    var pending by mutableStateOf(Backlog()); private set
    var speechHint by mutableStateOf(SpeechHint.AUTO); private set
    var message by mutableStateOf(""); private set
    var draft by mutableStateOf(""); private set
    var origin by mutableStateOf(Origin.NOTE); private set
    var selected by mutableStateOf<UUID?>(null); private set
    var highlight by mutableStateOf<Excerpt?>(null); private set

    fun open() = launch(Work.OPEN, "Could not unlock or migrate local storage. Nothing was reset.") {
        sources = withContext(Dispatchers.IO) {
            val initial = journal.open(host.legacy)
            try { host.nightKeys.prepare(allowNewKey = journal.awaitingUnlock == 0) }
            catch (_: java.security.GeneralSecurityException) { host.overnightNotice("key-failed", false) }
            catch (_: java.security.ProviderException) { host.overnightNotice("key-failed", false) }
            catch (_: LocalProblem) { host.overnightNotice("key-failed", false) }
            if (journal.awaitingUnlock > 0) { mergeForDisplay(); journal.open() } else initial
        }
        pending = withContext(Dispatchers.IO) { journal.backlog() }
        installed = modelLock.withLock {
            withContext(Dispatchers.IO) {
                inference.models.values.forEach { it.clearInterruptedInstall() }
                installedModels()
            }
        }
        ready = true
        if (Model.SPEECH in installed) schedulePending()
        if (prefs.getBoolean("local-work", false)) {
            notice("Previous local processing was interrupted. Check the source and pending audio.")
            markProcessing(false)
        }
        readNightNotices()
    }

    fun activate() {
        if (host.isLocked) { hideLockedNotebook(); return }
        visible = true
        if (!ready) open() else refresh()
    }

    fun background() {
        visible = false
        cancelLocal()
    }

    fun hideLockedNotebook() {
        visible = false
        fence.advance(); job?.cancel(); work = null
        sources = emptyList(); highlight = null; selected = null; ready = false
        discardDraft()
    }
    private fun mergeForDisplay(): MergeResult = try { OvernightMerge.mergeReady(host) }
    catch (_: java.security.GeneralSecurityException) { host.overnightNotice("merge-failed", false); MergeResult() }
    catch (_: java.io.IOException) { host.overnightNotice("merge-failed", false); MergeResult() }
    catch (_: LocalProblem) { host.overnightNotice("merge-failed", false); MergeResult() }

    private fun readNightNotices() {
        val notices = mapOf("merged" to "Sealed passages have been verified and are now available.", "stale" to "Discarded a sealed result because its source changed.",
            "no-highlight" to "The optional overnight highlight was unavailable.",
            "key-failed" to "Overnight keys unavailable. Unlock with a secure credential; no keys were replaced.",
            "merge-failed" to "Sealed passages could not be opened. Existing memories were not reset.")
        val flags = notices.keys.filter { prefs.getBoolean("night-$it", false) }
        if (flags.isNotEmpty()) {
            notice(flags.joinToString(" ") { notices.getValue(it) })
            prefs.edit().apply { flags.forEach { remove("night-$it") } }.apply()
        }
    }

    fun refresh() {
        if (!ready || !visible) return
        if (work != null) { refreshPending = true; return }
        launch(Work.REFRESH, "Could not refresh the source or pending audio.") {
            val merged = withContext(Dispatchers.IO) { mergeForDisplay() }
            for (id in host.takeChanges() + merged.sources + setOfNotNull(listening.value.source, lastProcessed)) {
                withContext(Dispatchers.IO) { journal.sourceIfPresent(id) }?.let(::replace)
            }
            pending = withContext(Dispatchers.IO) { journal.backlog() }
            lastProcessed = null
            if (prefs.getBoolean("charging-failed", false)) {
                notice("Charging transcription failed or returned no text. Pending audio can be retried or forgotten.")
                prefs.edit().remove("charging-failed").apply()
            }
            readNightNotices()
        }
    }
    private fun replace(src: Source) {
        sources = (sources.filterNot { it.id == src.id } + src).sortedByDescending { it.createdAt }
        if (highlight?.source == src.id && highlight?.span?.revision != src.revision) highlight = null
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
        val source = withContext(Dispatchers.IO) { journal.add(kind, text) }
        sources = listOf(source) + sources
        discardDraft()
        view(source.id)
        notice("Saved on this phone.")
    }

    fun forget(id: UUID) {
        if (work == Work.INSIGHT || work == Work.SPEECH) cancelLocal()
        launch(Work.FORGET, "Could not delete the source. It has not been marked forgotten.") {
            withContext(Dispatchers.IO) { journal.forget(id) }
            androidx.work.WorkManager.getInstance(app).cancelAllWorkByTag("asr:$id")
            pending = withContext(Dispatchers.IO) { journal.backlog() }
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
        if (model == Model.SPEECH) schedulePending()
    }

    fun remove(model: Model) = launch(Work.MODEL, "Could not remove all model files.") {
        installed = modelLock.withLock {
            withContext(Dispatchers.IO) { inference.models.getValue(model).uninstall(); installedModels() }
        }
        notice("Removed ${model.title}. Your sources were not changed.")
    }

    private suspend fun schedulePending() = withContext(Dispatchers.IO) {
        journal.queue().forEach { ChargingTranscription.enqueue(app, it) }
    }
    fun transcribe(id: UUID, minutes: Int) = launch(Work.SPEECH, "Local transcription failed. Untranscribed audio remains queued.") {
        require(minutes in setOf(5, 15, 60))
        if (!visible) throw LocalProblem("Open the notebook to process audio.")
        if (Model.SPEECH !in installed) throw LocalProblem("Install the multilingual speech model first.")
        val hint = speechHint
        markProcessing(true)
        lastProcessed = id
        val count = modelLock.withLock {
            withContext(Dispatchers.IO) {
                if (!power.canProcess(false)) throw PowerDeferred()
                val eligible = recentWindow(journal.queue(), id, minutes, System.currentTimeMillis())
                if (eligible.isEmpty()) return@withContext 0
                inference.openSpeech(hint).use { engine ->
                    var completed = 0
                    for (item in eligible) {
                        if (processChunk(host, engine, item)) completed++
                        val backlog = journal.backlog()
                        withContext(Dispatchers.Main) { pending = backlog; notice("$completed passage(s) transcribed locally.") }
                    }
                    completed
                }
            }
        }
        notice(if (count == 0) "No pending chunks wholly inside this time window." else "$count passage(s) saved as unreviewed ASR. Processed audio deleted.")
    }

    fun review(src: Source, segment: Segment, text: String) = launch(Work.REVIEW, "Could not update the passage.") {
        replace(withContext(Dispatchers.IO) { journal.review(src.id, src.revision, segment.id, text) })
        notice("New source revision saved. Old citations are no longer valid.")
    }

    fun finishPaused(id: UUID) = launch(Work.REVIEW, "Could not end the paused session.") {
        replace(withContext(Dispatchers.IO) { journal.finishPaused(id, System.currentTimeMillis()); journal.source(id) })
    }

    fun control(stop: Boolean) {
        val token = listening.value.token ?: return
        try { app.startService(host.command(if (stop) ListeningService.STOP else ListeningService.PAUSE, token)) }
        catch (_: IllegalStateException) { notice("Controls unavailable here. Use the listening notification.") }
    }

    fun findHighlight(source: Source) = launch(Work.INSIGHT, "Local text inference failed. No highlight was saved.") { token ->
        highlight = null
        if (!power.canProcess(false)) throw PowerDeferred()
        markProcessing(true)
        val result = modelLock.withLock { withContext(Dispatchers.Default) { inference.highlight(source) } }
        val live = withContext(Dispatchers.IO) { journal.currentRevision(source.id) }
        if (!power.canProcess(false)) throw PowerDeferred()
        if (fence.permits(token, source.id, selected, sources) && live == source.revision) {
            highlight = result
            notice(if (result == null) "No grounded highlight returned." else "Model-selected excerpt, not a verified fact.")
        }
    }

    fun cancelLocal() {
        if (work != Work.SPEECH && work != Work.INSIGHT) return
        fence.advance()
        job?.cancel()
        work = null
        markProcessing(false)
        notice("Processing interrupted. Completed passages are kept; remaining audio is queued.")
        if (visible && lastProcessed != null) refreshPending = true
    }

    override fun onCleared() { cancelLocal(); super.onCleared() }

    private fun launch(kind: Work, failure: String, run: suspend (Long) -> Unit) {
        if (work != null || (kind != Work.OPEN && !ready)) return
        val token = fence.advance()
        work = kind
        if (kind != Work.REFRESH) message = ""
        job = viewModelScope.launch {
            try { run(token) }
            catch (e: CancellationException) { throw e }
            catch (_: PowerDeferred) { if (fence.current(token)) notice("Processing paused: unlock, cool down, or check battery. Audio is still queued.") }
            catch (e: LocalProblem) { if (fence.current(token)) notice(e.explanation) }
            // Provider/engine exceptions can contain personal input. Never log or render their messages.
            catch (_: Exception) { if (fence.current(token)) notice(failure) }
            catch (_: LinkageError) { if (fence.current(token)) notice("Local runtime unavailable on this device.") }
            finally {
                if (fence.current(token)) {
                    if (kind == Work.INSIGHT || kind == Work.SPEECH) markProcessing(false)
                    work = null
                    if (visible && (refreshPending || kind == Work.SPEECH)) {
                        refreshPending = false
                        refresh()
                    }
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

}
