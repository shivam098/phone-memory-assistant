package dev.phonememory

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.UUID
import javax.crypto.KeyGenerator

class JournalTest {
    @get:Rule val tmp = TemporaryFolder()
    private val secret = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val pendingKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private fun store(root: File) = Journal(root, { secret }, { pendingKey }, {})
    private fun Journal.Writer.frame(start: Long = 0, now: Long = 1200) =
        append(start, start + 3200, 3200, "test codec bytes (not real audio)".toByteArray(), now)

    @Test fun migratedNotesSurviveInterruptionWithStableIdsAndOriginalText() {
        val legacyDir = tmp.newFolder()
        val legacy = Vault(legacyDir) { secret }
        val v1 = legacy.add(Origin.IMPORT, "🟦 पहला\nThen next")
        val old = legacyDir.listFiles()!!.single().readBytes()
        val root = tmp.newFolder()
        val j = store(root)
        assertEquals(v1, j.open(legacy).single())
        assertTrue(legacy.load().isEmpty())
        File(legacyDir, "${v1.id}.memory").writeBytes(old) // death just before deleting v1
        assertEquals(v1, store(root).open(legacy).single())
        assertTrue(legacy.load().isEmpty())
    }

    @Test fun abruptLossUsesLastDurablePointAndRequiresExplicitResume() {
        val dir = tmp.newFolder()
        val first = store(dir).apply { open() }
        val lease = first.begin(null, 1000)
        lease.frame()
        val recovered = store(dir)
        val source = recovered.open().single()
        val c = source.coverage!!
        assertEquals(CapturePhase.PAUSED, c.phase)
        assertEquals(Boundary(1200, 3200, EndReason.PROCESS), c.boundaries.single())
        assertEquals(3200L, recovered.backlog().samples)
        assertEquals(source, recovered.open().single()) // no repeated gap or automatic start
        val continued = recovered.begin(source.id, 2500)
        assertEquals(3200L, continued.firstSample)
        continued.end(EndReason.STOP, 2800, 4000)
        assertEquals(2500L, recovered.source(source.id).coverage!!.boundaries.first().resumedAt)
        assertThrows(LocalProblem::class.java) { recovered.begin(source.id, 3000) }
    }

    @Test fun lockBlocksNewReadsButWriterSealsUntilQuotaWithoutReopeningMaster() {
        var locked = false
        val j = Journal(tmp.newFolder(), { if (locked) throw IllegalStateException("locked"); secret }, { pendingKey }, {}, maxChunks = 1)
        j.open()
        val writer = j.begin(null, 1000)
        locked = true
        writer.frame()
        assertThrows(IllegalStateException::class.java) { j.source(writer.id) }
        assertEquals(EndReason.BACKLOG, assertThrows(CaptureHalt::class.java) { writer.reserveNext() }.reason)
        writer.end(EndReason.BACKLOG, 1200, 3200)
        assertThrows(IllegalStateException::class.java) { writer.frame() }
        locked = false
        assertEquals(1, j.backlog().count)
        assertEquals(EndReason.BACKLOG, j.source(writer.id).coverage!!.boundaries.last().reason)
    }

    @Test fun chunkCommittedBeforeCoverageRecoversItsExactTailWithoutStartingCapture() {
        val dir = tmp.newFolder()
        val j = store(dir).apply { open() }
        val w = j.begin(null, 1000)
        val header = File(dir, "${w.id}/capture.state")
        val prior = header.readBytes()
        w.frame()
        header.writeBytes(prior) // process lost after chunk rename, before coverage rename
        val recovered = store(dir)
        val c = recovered.open().single().coverage!!
        assertEquals(1L, c.sequence)
        assertEquals(3200L, c.sample)
        assertEquals(3200L, c.speechSamples)
        assertEquals(EndReason.PROCESS, c.boundaries.single().reason)
        assertEquals(1, recovered.queue().size)
    }

    @Test fun transcriptSegmentsExceedM1TotalLimitAndEvidenceNeverAliasesNewRevision() {
        val j = store(tmp.newFolder()).apply { open() }
        val w = j.begin(null, 1000)
        w.frame(); w.frame(3200, 1400); w.end(EndReason.STOP, 1400, 6400)
        val text = "🟢 Hello. बैठक 🟡। " + "x".repeat(9000)
        val a = j.input(w.id, 1)!!
        j.publish(a, text, "synthetic-producer/v1")
        val old = j.source(w.id)
        val hit = candidates(old)[1]
        assertEquals("बैठक 🟡।", hit.span.resolve(old))
        assertEquals(9, hit.span.from) // scalar values; the green circle is one
        assertEquals(Provenance.LOCAL_ASR, old.segments.single().provenance)
        val b = j.input(w.id, 2)!!
        j.publish(b, text, "synthetic-producer/v1")
        val current = j.source(w.id)
        assertEquals(2, current.segments.size)
        assertTrue(current.segments.sumOf { it.text.length } > MAX_TEXT_BYTES)
        assertNull(hit.span.resolve(current))
        val reviewed = j.review(w.id, 2, current.segments[0].id, "Corrected")
        assertEquals(Provenance.USER_CORRECTED, reviewed.segments.first().provenance)
        assertEquals("synthetic-producer/v1", reviewed.segments.first().producer)
        assertEquals(0, j.backlog().count)
        assertThrows(LocalProblem::class.java) { j.publish(b, "stale", "test") }
    }

    @Test fun committedTranscriptBeforeAudioUnlinkIsRecoveredOnceAndForgottenWorkCannotReturn() {
        val dir = tmp.newFolder()
        val j = store(dir).apply { open() }
        val w = j.begin(null, 1000)
        w.frame(); w.frame(3200, 1400); w.end(EndReason.ROUTE, 1400, 6400)
        val first = j.input(w.id, 1)!!
        val ciphertext = File(dir, "${w.id}/1.chunk").readBytes()
        assertFalse(ciphertext.toString(Charsets.UTF_8).contains("test codec"))
        j.publish(first, "one", "test")
        File(dir, "${w.id}/1.chunk").writeBytes(ciphertext)
        val next = store(dir)
        assertEquals(1, next.open().single().segments.size)
        assertFalse(File(dir, "${w.id}/1.chunk").exists())
        val stale = next.input(w.id, 2)!!
        next.forget(w.id)
        assertThrows(LocalProblem::class.java) { next.publish(stale, "late", "test") }
        assertTrue(store(dir).open().isEmpty())
    }

    @Test fun urgentWindowExcludesOtherSourcesAndChunksCrossingTheCutoff() {
        val id = UUID.randomUUID()
        val item = Queued(id, 1, 0, 30L * SAMPLE_RATE, 3000, 350_000, 900)
        val outside = item.copy(sequence = 2, at = 310_000)
        assertEquals(listOf(item), recentWindow(listOf(item, outside, item.copy(source = UUID.randomUUID())),
            id, 5, 600_000))
    }

    @Test fun loudnessAloneCannotAdmitAudioAndSilenceNeverCallsTheSecondStage() {
        var calls = 0
        var allow = false
        var emitted = 0
        val classifier = object : SpeechGate {
            override fun speech(frame: ShortArray, size: Int): Boolean { calls++; return allow }
            override fun quiet() {}
            override fun close() {}
        }
        val pack = MinutePack { emitted += it.pcm.size }
        val gate = VoiceGate(0, classifier, pack::add)
        val quiet = ShortArray(512)
        val loud = ShortArray(512) { 2000 }
        repeat(200) { gate.feed(quiet, quiet.size) }
        assertEquals(0, calls)
        repeat(10) { gate.feed(loud, loud.size) }
        assertEquals(0, emitted)
        allow = true
        repeat(1900) { gate.feed(loud, loud.size); pack.tick(gate.durablePosition) }
        gate.finish(); pack.flush()
        assertTrue(emitted in (1900 * 512)..(1905 * 512))
    }
}
