package dev.phonememory

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.SecretKey

data class Queued(val source: UUID, val sequence: Long, val start: Long, val end: Long,
                  val samples: Int, val at: Long, val bytes: Long)
fun recentWindow(queue: List<Queued>, source: UUID, minutes: Int, endWall: Long): List<Queued> =
    queue.filter { it.source == source && it.at <= endWall &&
        it.at - (it.end - it.start) * 1000 / SAMPLE_RATE >= endWall - minutes * 60_000L }.sortedBy { it.sequence }
data class AudioInput(val item: Queued, val revision: Long, val encoded: ByteArray) : AutoCloseable {
    override fun close() { encoded.fill(0) }
}

/** One instance per process. Native inference runs outside this transaction boundary. */
class Journal(private val root: File, private val master: (Boolean) -> SecretKey,
              private val maxChunks: Int = QUEUE_CHUNKS, private val maxBytes: Long = QUEUE_BYTES) {
    private var active: Writer? = null
    private val pending = mutableMapOf<Pair<UUID, Long>, Queued>()
    @Volatile private var backlogSize = Backlog()
    var initialized = false; private set
    private fun files(dir: File): List<File> = dir.listFiles()?.toList() ?: throw IOException("Store unavailable")
    private fun dirs() = files(root).filter { it.isDirectory && '.' !in it.name }
    private fun revisions(b: SealedRecords) = files(b.dir).filter { it.extension == "rev" }
        .map { it.nameWithoutExtension.toLong() }.sorted()
    private fun audio(dir: File) = files(dir).filter { it.extension == "audio" }.sortedBy { it.nameWithoutExtension.toLong() }
    private fun box(id: UUID): SealedRecords {
        val dir = File(root, id.toString())
        if (!dir.isDirectory) throw LocalProblem("Source forgotten or unavailable.")
        return SealedRecords(dir, id, unseal(File(dir, "key"), id, master(false)))
    }
    private fun snapshot(b: SealedRecords): Source {
        val (created, kind) = b.read("source") { readLong() to Origin.valueOf(readUTF()) }
        val revs = revisions(b)
        val segments = linkedMapOf<UUID, Segment>()
        revs.forEachIndexed { i, rev ->
            if (rev != i + 1L) throw IOException("Missing revision")
            val s = b.segment(rev)
            segments[s.id] = s
        }
        return Source(b.id, created, kind, revs.lastOrNull() ?: 0, segments.values.sortedBy { it.order },
            if (kind == Origin.LISTENING) b.state() else null)
    }
    private fun make(source: Source) {
        if (dirs().size >= 100) throw LocalProblem("Notebook full (100 sources). Forget a source first.")
        val id = source.id
        val temp = File(root, "$id.creating")
        if (!temp.mkdir()) throw IOException("Cannot stage source")
        val key = ByteArray(32).also { SecureRandom().nextBytes(it) }
        try {
            seal(File(temp, "key"), id, master(dirs().isEmpty()), key)
            SealedRecords(temp, id, key).use { b ->
                b.write("source") { writeLong(source.createdAt); writeUTF(source.origin.name) }
                source.segments.forEachIndexed { i, s -> b.writeSegment(i + 1L, s) }
                source.coverage?.let(b::saveState)
            }
            Files.move(temp.toPath(), File(root, id.toString()).toPath(), StandardCopyOption.ATOMIC_MOVE)
        } finally { key.fill(0); remove(temp) }
    }
    private fun remove(f: File) {
        if (f.exists() && !f.deleteRecursively()) throw IOException("Cleanup incomplete")
    }

    @Synchronized fun open(legacy: Vault? = null): List<Source> {
        if (initialized) return dirs().map { source(UUID.fromString(it.name)) }.sortedByDescending { it.createdAt }
        if (!root.isDirectory && !root.mkdirs()) throw IOException("Cannot open notebook")
        files(root).filter { it.extension in setOf("creating", "trash") }.forEach(::remove)
        legacy?.load()?.forEach { old ->
            if (!File(root, old.id.toString()).exists()) make(old)
            // Both copies may survive a kill immediately after publishing the directory.
            if (source(old.id) != old) throw IOException("Migration conflict")
            legacy.forget(old.id)
        }
        pending.clear()
        val result = dirs().map { dir ->
            val id = UUID.fromString(dir.name)
            files(dir).filter { it.extension == "part" }.forEach(::remove)
            box(id).use { b ->
                var src = snapshot(b)
                val old = src.coverage
                if (old != null) {
                    // A chunk rename can commit before the corresponding coverage checkpoint.
                    val done = src.segments.mapTo(hashSetOf()) { it.order }
                    for (f in audio(dir)) {
                        val seq = f.nameWithoutExtension.toLong()
                        if (seq in done) { remove(f); continue }
                        pending[id to seq] = b.read(f.name) {
                            Queued(id, seq, readLong(), readLong(), readInt(), readLong(), f.length())
                        }
                    }
                    val tail = pending.values.lastOrNull { it.source == id }
                    var c = old
                    if (tail != null && tail.sequence > old.sequence) {
                        c = old.copy(sequence = tail.sequence, sample = tail.end, lastAt = tail.at,
                            speechSamples = old.speechSamples + tail.samples)
                    }
                    if (c.phase == CapturePhase.ACTIVE && active?.id != id) {
                        c = ended(c, EndReason.PROCESS, c.lastAt, c.sample)
                    }
                    if (c != old) b.saveState(c)
                    src = src.copy(coverage = c)
                }
                src
            }
        }.sortedByDescending { it.createdAt }
        recalculate()
        initialized = true
        return result
    }
    @Synchronized fun source(id: UUID) = box(id).use(::snapshot)
    @Synchronized fun recoverIfNeeded(legacy: Vault) { if (!initialized) open(legacy) }
    @Synchronized fun currentRevision(id: UUID): Long? =
        if (!File(root, id.toString()).isDirectory) null else box(id).use { revisions(it).lastOrNull() ?: 0L }
    @Synchronized fun sourceIfPresent(id: UUID) = if (File(root, id.toString()).isDirectory) source(id) else null
    @Synchronized fun add(origin: Origin, value: String): Source =
        Source(UUID.randomUUID(), System.currentTimeMillis(), origin, cleanText(value)).also(::make)

    @Synchronized fun forget(id: UUID) {
        if (active?.id == id) throw LocalProblem("Stop listening before forgetting this session.")
        val trash = File(root, "$id.trash")
        Files.move(File(root, id.toString()).toPath(), trash.toPath(), StandardCopyOption.ATOMIC_MOVE)
        pending.keys.removeAll { it.first == id }
        recalculate()
        remove(trash)
    }
    fun backlog() = backlogSize
    private fun recalculate() {
        backlogSize = Backlog(pending.size, pending.values.sumOf { it.bytes }, pending.values.sumOf { it.samples.toLong() })
    }
    private fun reserve() {
        if (!backlog().canReserve(maxChunks, maxBytes)) throw CaptureHalt(EndReason.BACKLOG)
        if (root.usableSpace < 8 * 1024 * 1024L) throw CaptureHalt(EndReason.STORAGE)
    }
    @Synchronized fun begin(resume: UUID?, now: Long, battery: Int = -1, charge: Long = -1, baseline: Boolean = false): Writer {
        check(initialized)
        check(active == null)
        reserve()
        val id = resume ?: UUID.randomUUID()
        if (resume == null) make(Source(id, now, Origin.LISTENING, 0, emptyList(),
            Coverage(CapturePhase.ACTIVE, 0, now, 0, startBattery = battery, lastBattery = battery,
                startCharge = charge, lastCharge = charge, baseline = baseline)))
        val b = box(id)
        try {
            var c = b.state()
            if (resume != null) {
                if (c.phase != CapturePhase.PAUSED || c.baseline != baseline) throw LocalProblem("This source cannot resume in this mode.")
                val gaps = c.boundaries.toMutableList()
                gaps[gaps.lastIndex] = gaps.last().copy(resumedAt = now)
                c = c.copy(phase = CapturePhase.ACTIVE, lastAt = now, boundaries = gaps,
                    startBattery = battery, lastBattery = battery, startCharge = charge, lastCharge = charge)
                b.saveState(c)
            }
            return Writer(b, c).also { active = it }
        } catch (e: Exception) { b.close(); throw e }
    }
    private fun ended(c: Coverage, reason: EndReason, now: Long, sample: Long) = c.copy(
        phase = if (reason == EndReason.STOP) CapturePhase.STOPPED else CapturePhase.PAUSED,
        lastAt = now, sample = sample, boundaries = c.boundaries + Boundary(now, sample, reason)
    )
    inner class Writer internal constructor(private val b: SealedRecords, private var c: Coverage) {
        val id get() = b.id
        val firstSample = c.sample
        fun append(start: Long, end: Long, samples: Int, encoded: ByteArray, now: Long): Queued = synchronized(this@Journal) {
            check(active === this)
            require(samples in 1..(CHUNK_SECONDS * SAMPLE_RATE) && end - start >= samples && encoded.size in 1..MAX_ENCODED)
            if (!backlog().canReserve(maxChunks, maxBytes)) throw CaptureHalt(EndReason.BACKLOG)
            val seq = c.sequence + 1
            b.write("$seq.audio") {
                writeLong(start); writeLong(end); writeInt(samples); writeLong(now); write(encoded)
            }
            val item = Queued(id, seq, start, end, samples, now, File(b.dir, "$seq.audio").length())
            pending[id to seq] = item
            recalculate()
            c = c.copy(sequence = seq, lastAt = now, sample = maxOf(c.sample, end), speechSamples = c.speechSamples + samples)
            b.saveState(c)
            item
        }
        fun reserveNext() = synchronized(this@Journal) { check(active === this); reserve() }
        fun checkpoint(position: Long, now: Long) = synchronized(this@Journal) {
            check(active === this)
            c = c.copy(sample = maxOf(c.sample, position), lastAt = now)
            b.saveState(c)
        }
        fun end(reason: EndReason, now: Long, position: Long, battery: Int = -1, charge: Long = -1) = synchronized(this@Journal) {
            check(active === this)
            val failedWrite = reason == EndReason.STORAGE || reason == EndReason.CODEC || reason == EndReason.BACKLOG
            try { b.saveState(ended(c.copy(lastBattery = battery, lastCharge = charge), reason,
                if (failedWrite) c.lastAt else now, if (failedWrite) c.sample else maxOf(position, c.sample))) }
            finally { active = null; b.close() }
        }
    }
    @Synchronized fun finishPaused(id: UUID, now: Long) = box(id).use { b ->
        val c = b.state()
        if (c.phase != CapturePhase.PAUSED) throw LocalProblem("Not a paused source.")
        val gaps = c.boundaries.toMutableList()
        gaps[gaps.lastIndex] = gaps.last().copy(resumedAt = now)
        b.saveState(ended(c.copy(boundaries = gaps), EndReason.STOP, now, c.sample))
    }
    @Synchronized fun queue(): List<Queued> = pending.values.toList()
    @Synchronized fun input(id: UUID, seq: Long): AudioInput? {
        val item = pending[id to seq] ?: return null
        return box(id).use { b ->
            val rev = revisions(b).lastOrNull() ?: 0L
            b.read("$seq.audio") {
                check(readLong() == item.start && readLong() == item.end && readInt() == item.samples && readLong() == item.at)
                AudioInput(item, rev, readBytes())
            }
        }
    }
    @Synchronized fun publish(input: AudioInput, value: String, producer: String): Segment =
        box(input.item.source).use { b ->
            val rev = revisions(b).lastOrNull() ?: 0L
            val item = input.item
            val chunk = File(b.dir, "${item.sequence}.audio")
            if (pending[b.id to item.sequence] != item || !chunk.isFile || rev != input.revision)
                throw LocalProblem("Stale transcription; nothing published.")
            val seg = Segment(UUID.nameUUIDFromBytes("${b.id}:${item.sequence}".toByteArray()), item.sequence,
                cleanText(value), Provenance.LOCAL_ASR, producer, item.start * 1000 / SAMPLE_RATE,
                item.end * 1000 / SAMPLE_RATE)
            b.writeSegment(rev + 1, seg)
            pending.remove(b.id to item.sequence)
            recalculate()
            Files.delete(chunk.toPath())
            seg
        }
    @Synchronized fun review(id: UUID, expected: Long, segment: UUID, text: String): Source = box(id).use { b ->
        val src = snapshot(b)
        if (src.revision != expected) throw LocalProblem("Source changed; reopen the passage.")
        val old = src.segments.single { it.id == segment }
        val updated = cleanText(text)
        b.writeSegment(expected + 1, old.copy(text = updated, provenance =
            if (updated == old.text) Provenance.USER_REVIEWED else Provenance.USER_CORRECTED))
        snapshot(b)
    }
}
