package dev.phonememory

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.text.Normalizer
import java.util.Locale
import java.util.UUID

enum class Origin(val label: String) {
    NOTE("Note"), IMPORT("Imported text"), TRANSCRIPT("Reviewed transcript")
}

data class Source(val id: UUID, val createdAt: Long, val origin: Origin, val text: String) {
    val reference get() = "${origin.label} ${id.toString().take(8)}"
}

data class Excerpt(val source: UUID, val line: Int, val text: String)
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

/** Literal matching on the same line; references always point at the original, unnormalized text. */
fun recall(sources: List<Source>, query: String): List<Excerpt> {
    val words = normalized(query.trim()).split(spaces).filter { it.isNotEmpty() }
    if (words.isEmpty()) return emptyList()
    return sources.mapNotNull { source ->
        source.text.lineSequence().withIndex().firstOrNull { (_, line) ->
            val haystack = normalized(line)
            words.all { it in haystack }
        }?.let { Excerpt(source.id, it.index + 1, it.value) }
    }
}

private val sentenceBreak = Regex("(?<=[.!?।])\\s+")

fun candidates(source: Source): List<Excerpt> =
    source.text.lineSequence().withIndex().flatMap { (i, line) ->
        line.splitToSequence(sentenceBreak).map { it.trim() }.filter { it.isNotEmpty() }.map {
            val end = it.offsetByCodePoints(0, minOf(it.codePointCount(0, it.length), 180))
            Excerpt(source.id, i + 1, it.substring(0, end))
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
