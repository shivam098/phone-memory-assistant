package dev.phonememory

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.text.Normalizer
import java.util.Locale
import java.util.UUID

enum class Origin(val label: String) {
    NOTE("Note"), IMPORT("Imported text"), TRANSCRIPT("Reviewed transcript"), LISTENING("Listening session")
}

enum class Provenance { TYPED, IMPORTED, LEGACY_REVIEWED, LOCAL_ASR, USER_REVIEWED, USER_CORRECTED }
data class Segment(
    val id: UUID, val order: Long, val text: String, val provenance: Provenance,
    val producer: String = "", val startMs: Long? = null, val endMs: Long? = null, val pick: PassagePick? = null
)
data class Source(
    val id: UUID, val createdAt: Long, val origin: Origin, val revision: Long, val segments: List<Segment>,
    val coverage: Coverage? = null
) {
    constructor(id: UUID, createdAt: Long, origin: Origin, text: String) : this(
        id, createdAt, origin, 1, listOf(Segment(id, 0, text, when (origin) {
            Origin.NOTE -> Provenance.TYPED
            Origin.IMPORT -> Provenance.IMPORTED
            Origin.TRANSCRIPT -> Provenance.LEGACY_REVIEWED
            Origin.LISTENING -> error("Microphone sources start without text")
        }))
    )
    val reference get() = "${origin.label} ${id.toString().take(8)}"
}

data class EvidenceSpan(val source: UUID, val revision: Long, val segment: UUID, val from: Int, val until: Int) {
    fun resolve(current: Source): String? {
        if (current.id != source || current.revision != revision) return null
        val s = current.segments.firstOrNull { it.id == segment }?.text ?: return null
        if (from < 0 || until < from || until > s.codePointCount(0, s.length)) return null
        return s.substring(s.offsetByCodePoints(0, from), s.offsetByCodePoints(0, until))
    }
}
data class Excerpt(val span: EvidenceSpan, val line: Int, val text: String) {
    val source get() = span.source
}
class LocalProblem(val explanation: String) : Exception()

const val MAX_TEXT_BYTES = 16 * 1024

fun cleanText(value: String): String {
    val text = value.removePrefix("\uFEFF").replace("\r\n", "\n").replace('\r', '\n').trim()
    if (text.isBlank()) throw LocalProblem("There is no text to save.")
    if (text.toByteArray().size > MAX_TEXT_BYTES) throw LocalProblem("Text must fit in 16 KiB.")
    if (text.any { it.isISOControl() && it != '\n' && it != '\t' }) {
        throw LocalProblem("Choose plain UTF-8 text, not a binary document.")
    }
    return text
}

fun readTextImport(input: InputStream): String {
    val out = ByteArrayOutputStream()
    val buf = ByteArray(4096)
    while (true) {
        val n = input.read(buf)
        if (n == -1) break
        if (out.size() + n > MAX_TEXT_BYTES) throw LocalProblem("Import is larger than 16 KiB.")
        out.write(buf, 0, n)
    }
    val decoded = try {
        Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(out.toByteArray())).toString()
    } catch (_: java.nio.charset.CharacterCodingException) {
        throw LocalProblem("The selected file is not valid UTF-8 text.")
    }
    return cleanText(decoded)
}

private fun normalized(s: String) = Normalizer.normalize(s, Normalizer.Form.NFC).lowercase(Locale.ROOT)
private val spaces = Regex("\\s+")

private data class Passage(val segment: Segment, val line: Int, val offset: Int, val text: String)
private fun passages(source: Source) = source.segments.asSequence().flatMap { seg ->
    var offset = 0
    seg.text.lineSequence().mapIndexed { i, line ->
        Passage(seg, i + 1, offset, line).also { offset += line.length + 1 }
    }
}
private fun Passage.excerpt(source: Source, start: Int = 0, end: Int = text.length): Excerpt {
    val s = segment.text
    val from = s.codePointCount(0, offset + start)
    return Excerpt(EvidenceSpan(source.id, source.revision, segment.id, from,
        from + text.codePointCount(start, end)), line, text.substring(start, end))
}

fun recall(sources: List<Source>, query: String): List<Excerpt> {
    val words = normalized(query.trim()).split(spaces).filter { it.isNotEmpty() }
    if (words.isEmpty()) return emptyList()
    return sources.mapNotNull { source ->
        passages(source).firstOrNull { passage ->
            val haystack = normalized(passage.text)
            words.all { it in haystack }
        }?.excerpt(source)
    }
}

private val sentence = Regex("\\S.*?(?:[.!?।](?=\\s)|$)")

fun candidates(source: Source): List<Excerpt> =
    passages(source).flatMap { p ->
        sentence.findAll(p.text).map {
            val str = it.value.trimEnd()
            val end = str.offsetByCodePoints(0, minOf(str.codePointCount(0, str.length), 180))
            p.excerpt(source, it.range.first, it.range.first + end)
        }
    }.take(6).toList()

/** The model can select evidence or abstain. Its prose is never shown as a fact or an action. */
fun groundedChoice(output: String, options: List<Excerpt>): Excerpt? {
    val token = output.trim()
    if (token.length != 1 || token[0] !in '1'..'6') return null
    return options.getOrNull(token[0] - '1')
}

/** Main-thread publication barrier. Native work can finish after the source/screen is gone. */
class ReadFence {
    private var revision = 0L
    fun advance() = ++revision
    fun current(ticket: Long) = ticket == revision
    fun permits(ticket: Long, id: UUID, selected: UUID?, sources: List<Source>) =
        current(ticket) && selected == id && sources.any { it.id == id }
}
