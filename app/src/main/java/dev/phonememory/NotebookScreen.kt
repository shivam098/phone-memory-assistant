package dev.phonememory

import android.text.InputType
import android.text.InputFilter
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.widget.doAfterTextChanged
import java.text.DateFormat
import java.util.Date

@Composable
fun NotebookScreen(book: Notebook, pickText: () -> Unit, start: () -> Unit, install: (Model) -> Unit) {
    var models by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    val source = book.sources.find { it.id == book.selected }
    val idle = book.work == null && book.ready
    val leave: () -> Unit = { models = false; book.view(null) }
    BackHandler(enabled = models || source != null) { leave() }
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
        Surface(Modifier.fillMaxSize()) {
            if (book.work == Work.RECORD || book.work == Work.SPEECH) {
                Column(Modifier.safeDrawingPadding().padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    if (book.work == Work.RECORD) {
                        Text("Microphone on", style = MaterialTheme.typography.headlineMedium)
                        Text("${book.seconds} / $CAPTURE_SECONDS seconds • staying on this screen records audio")
                        Button(onClick = book::finishRecording) { Text("Stop & transcribe") }
                        OutlinedButton(onClick = book::cancelLocal) { Text("Discard recording") }
                    } else {
                        Text("Microphone off", style = MaterialTheme.typography.headlineMedium)
                        Text("Transcribing on this phone. No source has been saved.")
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        OutlinedButton(onClick = book::cancelLocal) { Text("Discard result") }
                    }
                }
                return@Surface
            }
            LazyColumn(
                Modifier.safeDrawingPadding().imePadding(), contentPadding = PaddingValues(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    Text("ON THIS PHONE", style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary)
                    Text(if (models) "Local models" else if (source != null) "Source" else "Phone Memory",
                        style = MaterialTheme.typography.headlineMedium)
                }
                if (book.work != null) item {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(book.work?.label.orEmpty())
                    if (book.work == Work.INSIGHT) {
                        TextButton(onClick = book::cancelLocal) { Text("Discard result") }
                    }
                }
                if (book.message.isNotEmpty()) item { Text(book.message, color = MaterialTheme.colorScheme.primary) }
                if (!book.ready && book.work == null) item { Button(onClick = book::open) { Text("Retry opening") } }
                when {
                    models -> {
                        item {
                            Text("No downloads in this app. Copy the pinned model files to your phone, then choose them from Files. Models run on the CPU; device quality and speed are unverified.")
                        }
                        items(Model.entries) { m ->
                            val present = m in book.installed
                            Text(m.title, style = MaterialTheme.typography.titleMedium)
                            Text(if (present) "Installed" else if (m == Model.SPEECH)
                                "Not installed • 153 MiB • select encoder, decoder and tokens together"
                                else "Not installed • 521 MiB • select the Q8 .task file")
                            OutlinedButton(enabled = idle, onClick = { if (present) book.remove(m) else install(m) }) {
                                Text(if (present) "Remove model" else "Select model files")
                            }
                        }
                        item { TextButton(onClick = leave) { Text("Back to notebook") } }
                    }
                    source != null -> {
                        item {
                            Text(source.reference, style = MaterialTheme.typography.titleMedium)
                            Text(DateFormat.getDateTimeInstance().format(Date(source.createdAt)))
                            if (source.origin == Origin.TRANSCRIPT) Text("From speech recognition; review is not proof of accuracy.")
                        }
                        item { Text(source.text) }
                        item {
                            HorizontalDivider()
                            Text("Local highlight", style = MaterialTheme.typography.titleLarge)
                            Text("The model may select one of the first six passages, or return no evidence. A highlight is a quote, not a new fact.")
                            book.highlight?.takeIf { it.source == source.id }?.let {
                                Text("“${it.text}”\n${source.reference} · line ${it.line}")
                            }
                            OutlinedButton(enabled = idle, onClick = {
                                if (Model.TEXT in book.installed) book.findHighlight(source) else models = true
                            }) { Text(if (Model.TEXT in book.installed) "Find a highlight" else "Set up text model") }
                        }
                        item {
                            var confirm by remember(source.id) { mutableStateOf(false) }
                            TextButton(enabled = idle || book.work == Work.INSIGHT, onClick = { confirm = true }) {
                                Text("Forget source", color = MaterialTheme.colorScheme.error)
                            }
                            if (confirm) AlertDialog(onDismissRequest = { confirm = false },
                                title = { Text("Forget this source?") },
                                text = { Text("Delete this phone's copy, search matches and highlight. Any original imported file stays where it was.") },
                                confirmButton = { TextButton(onClick = { confirm = false; book.forget(source.id) }) { Text("Forget") } },
                                dismissButton = { TextButton(onClick = { confirm = false }) { Text("Keep") } })
                            TextButton(onClick = leave) { Text("Back to notebook") }
                        }
                    }
                    else -> {
                        item {
                            Text(if (book.origin == Origin.TRANSCRIPT) "Review transcript" else "New memory",
                                style = MaterialTheme.typography.titleLarge)
                            PrivateInput(book.draft, "Write or review a note", idle, singleLine = false, book::edit)
                            Row {
                                Button(enabled = idle && book.draft.isNotBlank(), onClick = book::save) { Text("Save locally") }
                                TextButton(enabled = idle && book.draft.isNotEmpty(), onClick = book::discardDraft) { Text("Discard draft") }
                            }
                            OutlinedButton(enabled = idle && book.draft.isEmpty(), onClick = pickText) { Text("Import UTF-8 text") }
                        }
                        item {
                            Text("Conversation clip", style = MaterialTheme.typography.titleMedium)
                            Text("Get everyone's permission. Up to $CAPTURE_SECONDS seconds; keep this screen open. Leaving or locking discards the clip. Only a transcript you review and save is kept.")
                            if (Model.SPEECH in book.installed) {
                                Text("Language hint • try हिन्दी if Auto misses Hindi or Hinglish.")
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    SpeechHint.entries.forEach {
                                        FilterChip(selected = book.speechHint == it, enabled = idle,
                                            onClick = { book.hint(it) }, label = { Text(it.label) })
                                    }
                                }
                            }
                            OutlinedButton(enabled = idle && book.draft.isEmpty(), onClick = {
                                if (Model.SPEECH in book.installed) start() else models = true
                            }) { Text(if (Model.SPEECH in book.installed) "Start recording" else "Set up speech model") }
                            TextButton(enabled = idle, onClick = { models = true }) { Text("Manage local models") }
                        }
                        item {
                            HorizontalDivider()
                            Text("Recall", style = MaterialTheme.typography.titleLarge)
                            PrivateInput(query, "Find words on the same line", idle, singleLine = true) {
                                if (it.length <= 160) query = it
                            }
                        }
                        val hits = if (query.isBlank()) book.sources.map { Excerpt(it.id, 1, it.text.lineSequence().first()) }
                            else recall(book.sources, query)
                        if (hits.isEmpty() && book.ready) item {
                            Text(if (query.isBlank()) "No memories yet." else "No matching source. Try different words.")
                        }
                        items(hits, key = { it.source.toString() }) { hit ->
                            val ref = book.sources.first { it.id == hit.source }.reference
                            OutlinedCard(onClick = { book.view(hit.source) }, enabled = idle, modifier = Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(12.dp)) {
                                    Text("$ref · line ${hit.line}", style = MaterialTheme.typography.labelLarge)
                                    Text(hit.text, maxLines = 3, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Native field so that IMEs receive Android's no-learning request; the user's keyboard is trusted. */
@Composable
private fun PrivateInput(value: String, hint: String, enabled: Boolean, singleLine: Boolean, change: (String) -> Unit) {
    val listener by rememberUpdatedState(change)
    val color = MaterialTheme.colorScheme.onSurface.toArgb()
    val hintColor = MaterialTheme.colorScheme.onSurfaceVariant.toArgb()
    AndroidView(modifier = Modifier.fillMaxWidth().semantics { contentDescription = hint },
        factory = { context -> EditText(context).apply {
            this.hint = hint
            setSingleLine(singleLine)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or
                (if (singleLine) 0 else InputType.TYPE_TEXT_FLAG_MULTI_LINE)
            imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            isSaveEnabled = false
            filters = arrayOf(InputFilter.LengthFilter(if (singleLine) 160 else MAX_TEXT_BYTES))
            setMaxLines(if (singleLine) 1 else 6)
            doAfterTextChanged { listener(it.toString()) }
        } },
        update = { field ->
            field.isEnabled = enabled
            field.setTextColor(color)
            field.setHintTextColor(hintColor)
            if (field.text.toString() != value) { field.setText(value); field.setSelection(value.length) }
        })
}
