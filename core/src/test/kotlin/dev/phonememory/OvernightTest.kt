package dev.phonememory

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.KeyPairGenerator
import java.util.UUID
import javax.crypto.KeyGenerator

class OvernightTest {
    @get:Rule val temp = TemporaryFolder()
    private val root by lazy { temp.newFolder() }
    private val vault = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val queue = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private var locked = false
    private var firstUnlock = true
    private var privateReads = 0
    private val pair by lazy { KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair() }
    private val envelope = OvernightEnvelope({ pair.public }) {
        privateReads++; check(!locked && firstUnlock); pair.private
    }
    private fun store(sync: (File) -> Unit = {}) = Journal(root, { check(!locked && firstUnlock); vault },
        { check(firstUnlock); queue }, sync)
    private fun session(j: Journal, n: Int): UUID {
        val w = j.begin(null, 1000)
        repeat(n) { i -> w.append(i * 1600L, (i + 1) * 1600L, 1600, byteArrayOf(77, 12, 45), 1100L + i * 100) }
        w.end(EndReason.STOP, 1100L + n * 100, n * 1600L)
        return w.id
    }
    private fun stage(j: Journal, id: UUID, seq: Long) {
        j.input(id, seq)!!.use { input ->
            assertArrayEquals(byteArrayOf(77, 12, 45), input.encoded)
            val s = input.passage("आज 🟢 passage $seq", "fixture-asr/1")
            j.sealResult(input, s.copy(pick = PassagePick(0, 2, 1, "fixture-highlight/1")), envelope)
        }
    }
    private fun merge(j: Journal) = j.mergeSealed(envelope) { check(!locked && firstUnlock) }

    @Test fun coldLockedWorkerUsesOnlyAssignedAudioAndPublicKey_thenUnlockPublishesSiblingsOnce() {
        val j = store().apply { open() }
        val id = session(j, 2)
        val dir = File(root, id.toString())
        val historical = j.add(Origin.NOTE, "existing note")
        val worker = store()
        locked = true; firstUnlock = false
        assertThrows(IllegalStateException::class.java) { worker.input(id, 1) }
        firstUnlock = true // relock after first unlock; process has never opened the journal
        assertThrows(IllegalStateException::class.java) { worker.source(historical.id) }
        stage(worker, id, 1); stage(worker, id, 2)
        assertEquals(0, privateReads)
        assertFalse(File(dir, "1.chunk").exists())
        assertFalse(File(dir, "2.chunk").exists())
        assertTrue(dir.listFiles()!!.none { it.extension == "rev" })
        val firstSealed = File(dir, "1.sealed").readBytes()
        assertFalse(firstSealed.toString(Charsets.UTF_8).contains("passage"))
        assertThrows(IllegalStateException::class.java) { merge(worker) }

        locked = false
        val next = store()
        assertTrue(recall(next.open(), "passage").isEmpty())
        assertEquals(2, next.awaitingUnlock)
        assertEquals(2, merge(next).merged)
        val src = next.source(id)
        assertEquals(1L, src.revision)
        assertEquals(listOf(1L, 2L), src.segments.map { it.order })
        assertTrue(src.segments.all { it.provenance == Provenance.LOCAL_ASR && it.producer == "fixture-asr/1" })
        assertEquals("आज", src.segments.first().let { it.pick!!.resolve(src, it)!!.text })
        assertEquals("आज 🟢 passage 2", recall(listOf(src), "2").single().span.resolve(src))
        File(dir, "1.sealed").writeBytes(firstSealed) // simulate committed batch before unlink
        val replay = store().apply { open() }
        assertEquals(0, merge(replay).merged)
        assertEquals(src, replay.source(id))
        assertFalse(File(dir, "1.sealed").exists())
    }

    @Test fun changedAndForgottenInputsCannotReturnFromSealedOrInFlightJobs() {
        val j = store().apply { open() }
        val first = session(j, 2); val other = session(j, 2)
        val original = j.input(first, 1)!!.use { j.publish(it, "original", "fixture") }
        val late = j.input(other, 2)!!
        locked = true
        stage(j, first, 2); stage(j, other, 1)
        locked = false
        j.review(first, 1, original.id, "corrected")
        j.forget(other)
        assertThrows(LocalProblem::class.java) { j.sealResult(late, late.passage("late", "test"), envelope) }
        late.close()
        assertEquals(1, merge(j).stale)
        assertEquals("corrected", j.source(first).segments.single().text)
        assertNull(j.sourceIfPresent(other))
        assertEquals(0, j.awaitingUnlock)
    }

    @Test fun relockDuringMergePublishesNothing_andTamperedOrCrossJobEnvelopeFailsClosed() {
        val j = store().apply { open() }
        val id = session(j, 2)
        stage(j, id, 1); stage(j, id, 2)
        var gates = 0
        assertThrows(IllegalStateException::class.java) {
            j.mergeSealed(envelope) { check(++gates < 4) } // final check before publishing the batch
        }
        assertTrue(j.source(id).segments.isEmpty())
        val f1 = File(root, "$id/1.sealed")
        f1.writeBytes(File(root, "$id/2.sealed").readBytes())
        assertThrows(GeneralSecurityException::class.java) { merge(j) }
        assertTrue(j.source(id).segments.isEmpty())
        assertEquals(2, j.awaitingUnlock)
        val sealed = envelope.seal(id, 3, "new result".toByteArray())
        assertThrows(GeneralSecurityException::class.java) { envelope.open(id, 4, sealed) }
        locked = true
        assertThrows(IllegalStateException::class.java) { envelope.open(id, 3, sealed) }
    }

    @Test fun unsuccessfulSealOrDirectorySyncKeepsAudioAndWrappedContentKey() {
        var failSync = false
        val j = store { if (failSync) throw IOException("simulated directory fsync failure") }.apply { open() }
        val id = session(j, 1)
        val input = j.input(id, 1)!!
        val seg = input.passage("preserve input", "fixture")
        val broken = OvernightEnvelope({ throw IOException("simulated unavailable public key") }, { error("must not decrypt") })
        assertThrows(IOException::class.java) { j.sealResult(input, seg, broken) }
        assertTrue(File(root, "$id/1.chunk").isFile)
        failSync = true
        assertThrows(IOException::class.java) { j.sealResult(input, seg, envelope) }
        input.close()
        assertTrue(File(root, "$id/1.chunk").isFile)
        val fresh = store().apply { open() }
        assertEquals(1, merge(fresh).merged)
        assertFalse(File(root, "$id/1.chunk").exists())
        assertEquals("preserve input", fresh.source(id).segments.single().text)
    }

    @Test fun m2PendingAudioMigratesIndependentlyWithoutMakingOldJournalReadable() {
        val id = UUID.randomUUID()
        val dir = File(root, id.toString()).apply { mkdir() }
        val raw = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
        seal(File(dir, "key"), id, vault, raw)
        SealedRecords(dir, id, raw).use { m2 ->
            m2.write("source") { writeLong(1000); writeUTF("LISTENING") }
            m2.saveState(Coverage(CapturePhase.STOPPED, 3200, 1200, 2), "coverage")
            m2.writeSegment(1, Segment(asrId(id, 1), 1, "old saved memory", Provenance.LOCAL_ASR))
            m2.write("2.audio") { writeLong(1600); writeLong(3200); writeInt(1600); writeLong(1200); write(byteArrayOf(77, 12, 45)) }
        }
        val migrated = store().apply { open() }
        assertFalse(File(dir, "2.audio").exists())
        val pendingRaw = unseal(File(dir, "capture-key"), id, queue)
        SealedRecords(dir, id, pendingRaw).use {
            assertThrows(GeneralSecurityException::class.java) { it.segments(1) }
        }
        locked = true
        stage(store(), id, 2)
        assertThrows(IllegalStateException::class.java) { migrated.source(id) }
        locked = false
        val next = store().apply { open() }
        assertEquals(1, merge(next).merged)
        assertEquals(listOf("old saved memory", "आज 🟢 passage 2"), next.source(id).segments.map { it.text })
    }
}
