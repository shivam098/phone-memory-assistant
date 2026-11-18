package dev.phonememory

import android.Manifest
import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.view.View
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.lifecycle.ViewModelProvider
import java.util.UUID

private class LocalDocuments(private val mime: String, private val multiple: Boolean = false) :
    ActivityResultContract<Unit, List<Uri>>() {
    override fun createIntent(context: Context, input: Unit) = Intent(Intent.ACTION_OPEN_DOCUMENT)
        .addCategory(Intent.CATEGORY_OPENABLE).setType(mime)
        .putExtra(Intent.EXTRA_LOCAL_ONLY, true).putExtra(Intent.EXTRA_ALLOW_MULTIPLE, multiple)

    override fun parseResult(resultCode: Int, intent: Intent?): List<Uri> {
        if (resultCode != Activity.RESULT_OK || intent == null) return emptyList()
        val clip = intent.clipData
        val uris = if (clip == null) listOfNotNull(intent.data) else {
            if (clip.itemCount > 3) return emptyList()
            List(clip.itemCount) { clip.getItemAt(it).uri }
        }
        return uris.filterNotNull().takeIf { docs -> docs.all { it.scheme == "content" } }.orEmpty()
    }
}

class MainActivity : ComponentActivity() {
    private var showNotebook by mutableStateOf(false)
    private val screenOff = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            showNotebook = false
            book.hideLockedNotebook()
        }
    }
    private val book by lazy { ViewModelProvider(this)[Notebook::class.java] }
    private val note = registerForActivityResult(LocalDocuments("text/plain")) { uris ->
        uris.singleOrNull()?.let(book::importText) ?: book.notice("No text file selected.")
    }
    private val speech = registerForActivityResult(LocalDocuments("*/*", multiple = true)) {
        if (it.isNotEmpty()) book.install(Model.SPEECH, it) else book.notice("No speech files selected.")
    }
    private val text = registerForActivityResult(LocalDocuments("*/*")) {
        if (it.isNotEmpty()) book.install(Model.TEXT, it) else book.notice("No text model selected.")
    }
    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        book.notice(if (granted) "Microphone allowed. Tap Start listening when everyone is ready."
            else "Microphone not allowed. Notes still work; you can change mic access in app settings.")
    }
    private val notifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        book.notice(if (granted) "Controls enabled. Tap Start or Resume when ready."
            else "Listening is off. Enable notifications in Android settings for visible Pause and Stop controls.")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        androidx.core.content.ContextCompat.registerReceiver(this, screenOff, android.content.IntentFilter(Intent.ACTION_SCREEN_OFF),
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
        ListeningService.createChannel(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        window.decorView.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        if (Build.VERSION.SDK_INT >= 30) {
            window.decorView.importantForContentCapture = View.IMPORTANT_FOR_CONTENT_CAPTURE_NO_EXCLUDE_DESCENDANTS
        }
        setContent {
            // Drafts, queries, selections and excerpts must not enter Android's saved-state Bundle.
            CompositionLocalProvider(LocalSaveableStateRegistry provides null) {
                if (showNotebook) NotebookScreen(book, { note.launch(Unit) }, ::startMic) { model ->
                    if (model == Model.SPEECH) speech.launch(Unit) else text.launch(Unit)
                }
            }
        }
    }

    private fun startMic(resume: UUID?, baseline: Boolean) {
        if (!hasWindowFocus() || getSystemService(KeyguardManager::class.java).isKeyguardLocked ||
            !getSystemService(PowerManager::class.java).isInteractive) {
            book.notice("Unlock and tap Start or Resume from this screen.")
        } else if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            permission.launch(Manifest.permission.RECORD_AUDIO)
        } else if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else if (!ListeningService.controlsAvailable(this)) {
            book.notice("Enable the Listening controls notification channel before starting.")
        } else {
            val reason = PowerGuard(this).captureReason()
            if (reason != null) { book.notice(reason.label); return }
            try { (application as MemoryApp).startFromTap(resume, baseline) }
            catch (_: LocalProblem) { book.notice("Listening is already starting or active.") }
            catch (_: RuntimeException) { book.notice("Android did not allow the microphone service to start.") }
        }
    }
    override fun onResume() {
        super.onResume()
        showNotebook = !getSystemService(KeyguardManager::class.java).isDeviceLocked
        book.activate()
    }
    override fun onDestroy() { unregisterReceiver(screenOff); super.onDestroy() }
    override fun onPause() { showNotebook = false; book.background(); super.onPause() }
    override fun onStop() {
        if (getSystemService(KeyguardManager::class.java).isDeviceLocked ||
            !getSystemService(PowerManager::class.java).isInteractive) book.hideLockedNotebook()
        super.onStop()
    }
}
