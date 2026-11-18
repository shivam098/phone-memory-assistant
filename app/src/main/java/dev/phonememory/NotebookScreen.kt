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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.text.DateFormat
import java.util.Date
import java.util.UUID

@Composable
fun NotebookScreen(book: Notebook, pickText: () -> Unit, start: (UUID?, Boolean) -> Unit, install: (Model) -> Unit) {
    var models by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    val live by book.listening.collectAsStateWithLifecycle()
    val changes by book.changes.collectAsStateWithLifecycle()
    LaunchedEffect(live.source, live.version, changes) { book.refresh() }
    var confirmCapture by remember { mutableStateOf(false) }
    var resumeId by remember { mutableStateOf<UUID?>(null) }
    var baseline by remember { mutableStateOf(false) }
    var diagnostics by remember { mutableStateOf(false) }
    val request: (UUID?, Boolean) -> Unit = { id, measure -> resumeId = id; baseline = measure; confirmCapture = true }
    val backlog = book.backlogForDisplay()
    val source = book.sources.find { it.id == book.selected }
    val idle = book.work == null && book.ready
    val leave: () -> Unit = { models = false; book.view(null) }
    BackHandler(enabled = models || source != null) { leave() }
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
        Surface(Modifier.fillMaxSize()) {
            if (diagnostics) AlertDialog(onDismissRequest = { diagnostics = false },
                title = { Text("Local capture diagnostics") },
                text = {
                    val sessions = book.sources.mapNotNull { it.coverage }
                    val c = sessions.firstOrNull()
                    Text(buildString {
                        appendLine("Last refreshed session totals (including read/discard baselines):")
                        appendLine("Listening: %.3f h".format(sessions.sumOf { it.sample } / 57600000.0))
                        appendLine("Speech + padding: %.2f min".format(sessions.sumOf { it.speechSamples } / 960000.0))
                        appendLine("Pending: ${backlog.samples / SAMPLE_RATE} s / ${backlog.bytes} bytes")
                        if (c != null && c.startBattery >= 0 && c.lastBattery >= 0)
                            appendLine("Latest interval: ${c.startBattery}% → ${c.lastBattery}%")
                        append(if (c != null && c.startCharge >= 0 && c.lastCharge >= 0)
                            "Charge change: ${c.lastCharge - c.startCharge} µAh" else "Charge counter unavailable.")
                    })
                }, confirmButton = { TextButton(onClick = { diagnostics = false }) { Text("Close") } })
            if (confirmCapture) AlertDialog(onDismissRequest = { confirmCapture = false },
                title = { Text("Listen while the phone is locked?") },
                text = { Text(if (baseline) "Mic-only battery test: confirm everyone's consent. Audio is read and discarded, never saved. Controls remain in the notification."
                    else "Confirm everyone present agrees to be recorded. Speech is kept encrypted until local transcription. Recording continues in the background. Use the notification to pause or stop. No call audio.") },
                confirmButton = { TextButton(onClick = { confirmCapture = false; start(resumeId, baseline) }) { Text("Everyone agrees • start") } },
                dismissButton = { TextButton(onClick = { confirmCapture = false }) { Text("Cancel") } })
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
                item {
                    if (live.token != null) {
                        Text(when {
                            live.stopping -> "Stopping and sealing audio…"
                            live.sample == null -> "Starting microphone…"
                            else -> "Listening • ${(live.sample ?: 0) / SAMPLE_RATE} s captured"
                        })
                        Row {
                            Button(enabled = !live.stopping, onClick = { book.control(false) }) { Text("Pause") }
                            TextButton(enabled = !live.stopping, onClick = { book.control(true) }) { Text("Stop") }
                        }
                    } else live.reason?.let { Text("Microphone off • ${it.label}") }
                    Text("Pending transcription: ${backlog.count} chunks • ${backlog.samples / SAMPLE_RATE} s • ${backlog.bytes / 1024} KiB")
                    if (backlog.nearlyFull) {
                        Text("Audio queue nearly full. Process pending audio or forget a session before capture pauses.")
                    }
                }
                if (book.work != null) item {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(book.work?.label.orEmpty())
                    if (book.work == Work.INSIGHT || book.work == Work.SPEECH) {
                        TextButton(onClick = book::cancelLocal) { Text("Cancel processing") }
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
                        source.coverage?.let { c -> item {
                            Text("${c.phase} • last confirmed ${DateFormat.getDateTimeInstance().format(Date(c.lastAt))}")
                            for (gap in c.boundaries.takeLast(4)) {
                                Text("${gap.reason.label} at ${DateFormat.getTimeInstance().format(Date(gap.at))}" +
                                    (gap.resumedAt?.let { " → ${DateFormat.getTimeInstance().format(Date(it))}" } ?: ""))
                            }
                            if (c.phase == CapturePhase.PAUSED && live.token == null) Row {
                                OutlinedButton(enabled = idle, onClick = { request(source.id, c.baseline) }) { Text("Resume") }
                                TextButton(enabled = idle, onClick = { book.finishPaused(source.id) }) { Text("End session") }
                            }
                            Text("Session: %.2f h • retained speech: %.2f min".format(c.sample / 57600000.0, c.speechSamples / 960000.0))
                            if (c.baseline) Text("Diagnostics: microphone-only baseline. Audio was discarded.")
                            if (c.startBattery >= 0 && c.lastBattery >= 0)
                                Text("Last capture interval battery: ${c.startBattery}% → ${c.lastBattery}%")
                            if (c.startCharge >= 0 && c.lastCharge >= 0)
                                Text("Charge-counter change: ${c.lastCharge - c.startCharge} µAh")
                            if (Model.SPEECH !in book.installed) {
                                Text("Transcription unavailable until the local speech model is installed.")
                            } else {
                                var minutes by remember { mutableIntStateOf(15) }
                                Text("Process now • only pending chunks in the last:")
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    for (m in listOf(5,15,60)) FilterChip(selected = minutes == m, enabled = idle,
                                        onClick = { minutes = m }, label = { Text("$m min") })
                                }
                                OutlinedButton(enabled = idle, onClick = { book.transcribe(source.id, minutes) }) { Text("Process now") }
                            }
                        } }
                        items(source.segments, key = { it.id.toString() }) { seg ->
                            var editing by remember { mutableStateOf(false) }
                            Text("${seg.provenance.name.replace('_', ' ')} • r${source.revision} • passage ${seg.id.toString().take(8)}" +
                                (seg.startMs?.let { " • ${it / 1000}–${(seg.endMs ?: it) / 1000} s" } ?: ""))
                            Text(seg.text)
                            if (seg.producer.isNotEmpty()) {
                                Text(seg.producer, style = MaterialTheme.typography.labelSmall)
                                TextButton(enabled = idle, onClick = { editing = true }) { Text("Review passage") }
                            }
                            if (editing) {
                                var value by remember { mutableStateOf(seg.text) }
                                AlertDialog(onDismissRequest = { editing = false }, title = { Text("Review passage") },
                                    text = { PrivateInput(value, "Correct or confirm the transcript", true, false) { value = it } },
                                    confirmButton = { TextButton(onClick = { editing = false; book.review(source, seg, value) }) { Text("Save revision") } },
                                    dismissButton = { TextButton(onClick = { editing = false }) { Text("Cancel") } })
                            }
                        }
                        item {
                            HorizontalDivider()
                            Text("Local highlight", style = MaterialTheme.typography.titleLarge)
                            Text("The model may select one of the first six passages, or return no evidence. A highlight is a quote, not a new fact.")
                            book.highlight?.takeIf { it.span.resolve(source) == it.text }?.let {
                                Text("“${it.text}”\n${source.reference} · r${it.span.revision} · ${it.span.segment.toString().take(8)} · line ${it.line}")
                            }
                            OutlinedButton(enabled = idle && source.segments.isNotEmpty(), onClick = {
                                if (Model.TEXT in book.installed) book.findHighlight(source) else models = true
                            }) { Text(if (Model.TEXT in book.installed) "Find a highlight" else "Set up text model") }
                        }
                        item {
                            var confirm by remember(source.id) { mutableStateOf(false) }
                            TextButton(enabled = !(live.token != null && live.source == source.id) &&
                                (idle || book.work == Work.INSIGHT || book.work == Work.SPEECH), onClick = { confirm = true }) {
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
                            Text("All-day listening", style = MaterialTheme.typography.titleMedium)
                            Text("Capture without an ASR model. Only speech candidates are kept. Transcription waits for charging and unlock; Process now is available in each session.")
                            if (Model.SPEECH in book.installed) {
                                Text("Language hint • try हिन्दी if Auto misses Hindi or Hinglish.")
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    SpeechHint.entries.forEach {
                                        FilterChip(selected = book.speechHint == it, enabled = idle,
                                            onClick = { book.hint(it) }, label = { Text(it.label) })
                                    }
                                }
                            }
                            OutlinedButton(enabled = idle && live.token == null, onClick = { request(null, false) }) { Text("Start listening") }
                            TextButton(enabled = idle && live.token == null, onClick = { request(null, true) }) { Text("Diagnostics: mic-only baseline") }
                            TextButton(onClick = { diagnostics = true }) { Text("View local diagnostics") }
                            Text("Pause at ${book.lowLimit}% battery or when Battery Saver turns on.")
                            Row {
                                TextButton(enabled = book.lowLimit > 5 && live.token == null, onClick = { book.setLimit(book.lowLimit - 5) }) { Text("−5%") }
                                TextButton(enabled = book.lowLimit < 50 && live.token == null, onClick = { book.setLimit(book.lowLimit + 5) }) { Text("+5%") }
                            }
                            TextButton(enabled = idle, onClick = { models = true }) { Text("Manage local models") }
                        }
                        item {
                            HorizontalDivider()
                            Text("Recall", style = MaterialTheme.typography.titleLarge)
                            PrivateInput(query, "Find words on the same line", idle, singleLine = true) {
                                if (it.length <= 160) query = it
                            }
                        }
                        val hits = if (query.isBlank()) book.sources.map { it to null }
                            else recall(book.sources, query).map { h -> book.sources.first { it.id == h.source } to h }
                        if (hits.isEmpty() && book.ready) item {
                            Text(if (query.isBlank()) "No memories yet." else "No matching transcribed source. Pending audio is not searched.")
                        }
                        items(hits, key = { it.first.id.toString() }) { (src, hit) ->
                            OutlinedCard(onClick = { book.view(src.id) }, enabled = idle, modifier = Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(12.dp)) {
                                    Text(src.reference + (hit?.let { " · r${it.span.revision} · ${it.span.segment.toString().take(8)} · line ${it.line}" } ?: ""), style = MaterialTheme.typography.labelLarge)
                                    Text(hit?.text ?: src.segments.firstOrNull()?.text ?: "No transcript yet.",
                                        maxLines = 3, overflow = TextOverflow.Ellipsis)
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
