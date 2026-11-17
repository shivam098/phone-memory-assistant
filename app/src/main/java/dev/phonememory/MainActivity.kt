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
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.lifecycle.ViewModelProvider

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
        book.notice(if (granted) "Microphone allowed. Tap Start recording when everyone is ready."
            else "Microphone not allowed. Notes still work; you can change mic access in app settings.")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        window.decorView.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        if (Build.VERSION.SDK_INT >= 30) {
            window.decorView.importantForContentCapture = View.IMPORTANT_FOR_CONTENT_CAPTURE_NO_EXCLUDE_DESCENDANTS
        }
        setContent {
            // Drafts, queries, selections and excerpts must not enter Android's saved-state Bundle.
            CompositionLocalProvider(LocalSaveableStateRegistry provides null) {
                NotebookScreen(book, { note.launch(Unit) }, ::startMic) { model ->
                    if (model == Model.SPEECH) speech.launch(Unit) else text.launch(Unit)
                }
            }
        }
    }

    private fun startMic() {
        if (!hasWindowFocus() || getSystemService(KeyguardManager::class.java).isKeyguardLocked ||
            !getSystemService(PowerManager::class.java).isInteractive) {
            book.notice("Unlock the phone and keep this screen open to record.")
        } else if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            permission.launch(Manifest.permission.RECORD_AUDIO)
        } else book.record()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus && book.work == Work.RECORD) book.cancelLocal()
    }

    override fun onPause() { book.cancelLocal(); super.onPause() }
}
