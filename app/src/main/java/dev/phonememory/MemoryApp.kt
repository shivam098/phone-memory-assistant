package dev.phonememory

import android.app.Application
import android.content.Intent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.UUID

data class ListeningStatus(val token: UUID? = null, val source: UUID? = null, val stopping: Boolean = false,
                           val backlog: Backlog = Backlog(), val sample: Long? = null, val reason: EndReason? = null,
                           val version: Long = 0, val baseline: Boolean = false)

class MemoryApp : Application() {
    val journal by lazy {
        val wrapper = VaultKey("phone-memory-v2", true)
        Journal(File(noBackupFilesDir, "journal-v2"), { create ->
            if (getSystemService(android.app.KeyguardManager::class.java).isDeviceLocked)
                throw LocalProblem("Unlock the phone to open the notebook.")
            wrapper.get(create)
        })
    }
    val legacy by lazy {
        val legacyKey = VaultKey()
        Vault(File(noBackupFilesDir, "notebook")) { create ->
            if (getSystemService(android.app.KeyguardManager::class.java).isDeviceLocked)
                throw LocalProblem("Unlock the phone before migration.")
            legacyKey.get(create)
        }
    }
    val inference by lazy { LocalInference(this) }
    val modelLock = kotlinx.coroutines.sync.Mutex()
    val changed = MutableStateFlow(0L)
    private val dirty = mutableSetOf<UUID>()
    @Synchronized fun changed(id: UUID) { dirty.add(id); changed.value++ }
    @Synchronized fun takeChanges() = dirty.toSet().also { dirty.clear() }
    fun processingFailure() {
        getSharedPreferences("work-status", MODE_PRIVATE).edit().putBoolean("charging-failed", true).apply()
        synchronized(this) { changed.value++ }
    }
    private val state = MutableStateFlow(ListeningStatus())
    val listening = state.asStateFlow()
    private var permit: UUID? = null

    /** Only reached from a visible, unlocked activity's explicit start/resume button. */
    @Synchronized fun startFromTap(resume: UUID?, baseline: Boolean) {
        if (state.value.token != null) throw LocalProblem("A listening session is already starting or active.")
        val token = UUID.randomUUID()
        permit = token
        state.value = state.value.copy(token = token, source = resume, stopping = false, reason = null, sample = null, baseline = baseline)
        try {
            startForegroundService(command(ListeningService.START, token))
        } catch (e: RuntimeException) {
            ended(token, EndReason.SERVICE)
            throw e
        }
    }
    @Synchronized fun consume(token: UUID): Boolean {
        if (permit != token) return false
        permit = null
        return true
    }
    fun command(action: String, token: UUID) = Intent(this, ListeningService::class.java)
        .setAction(action).setData(android.net.Uri.parse("phonememory:run/$token"))

    @Synchronized fun stopping(token: UUID) {
        if (state.value.token == token) state.value = state.value.copy(stopping = true)
    }
    @Synchronized fun progress(token: UUID, id: UUID, backlog: Backlog, sample: Long? = null) {
        if (state.value.token == token) state.value = state.value.copy(source = id, backlog = backlog, sample = sample)
    }
    @Synchronized fun ended(token: UUID, reason: EndReason) {
        if (state.value.token != token) return
        permit = null
        state.value = state.value.copy(token = null, stopping = false, reason = reason, version = state.value.version + 1)
    }
}
