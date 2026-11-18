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
              private val captureMaster: (Boolean) -> SecretKey,
              private val syncDir: (File) -> Unit, private val maxChunks: Int = QUEUE_CHUNKS,
              private val maxBytes: Long = QUEUE_BYTES, private val guardJournalRead: () -> Unit = {}) {
    private var active: Writer? = null
    private val pending = mutableMapOf<Pair<UUID, Long>, Queued>()
    private val sealed = mutableSetOf<Pair<UUID, Long>>()
    private var queueLoaded = false
    @Volatile var awaitingUnlock = 0; private set
    @Volatile private var backlogSize = Backlog()
    var initialized = false; private set
    private fun files(dir: File): List<File> = dir.listFiles()?.toList() ?: throw IOException("Store unavailable")
    private fun dirs() = files(root).filter { it.isDirectory && '.' !in it.name }
    private fun revisions(dir: File) = files(dir).filter { it.extension == "rev" }
        .map { it.nameWithoutExtension.toLong() }.sorted()
    private fun pendingBox(dir: File, id: UUID, create: Boolean = false): SealedRecords {
        val wrapped = File(dir, "capture-key")
        if (create && !wrapped.exists()) {
            require(files(dir).none { it.extension in setOf("chunk", "sealed") || it.name == "capture.state" })
            val raw = ByteArray(32).also { SecureRandom().nextBytes(it) }
            try { seal(wrapped, id, captureMaster(dirs().none { File(it, "capture-key").exists() }), raw); syncDir(dir) }
            finally { raw.fill(0) }
        }
        return SealedRecords(dir, id, unseal(wrapped, id, captureMaster(false)))
    }
    private fun box(id: UUID): SealedRecords {
        guardJournalRead()
        val dir = File(root, id.toString())
        if (!dir.isDirectory) throw LocalProblem("Source forgotten or unavailable.")
        return SealedRecords(dir, id, unseal(File(dir, "key"), id, master(false)))
    }
    private fun snapshot(b: SealedRecords): Source {
        val (created, kind) = b.read("source") { readLong() to Origin.valueOf(readUTF()) }
        val revs = revisions(b.dir)
        val segments = linkedMapOf<UUID, Segment>()
        revs.forEachIndexed { i, rev ->
            guardJournalRead()
            if (rev != i + 1L) throw IOException("Missing revision")
            for (s in b.segments(rev)) {
                segments[s.id] = if (s.pick != null && rev != revs.last()) s.copy(pick = null) else s
            }
        }
        guardJournalRead()
        return Source(b.id, created, kind, revs.lastOrNull() ?: 0, segments.values.sortedBy { it.order },
            if (kind == Origin.LISTENING) pendingBox(b.dir, b.id).use { it.state() } else null)
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
                source.coverage?.let { c -> pendingBox(temp, id, create = true).use { it.saveState(c) } }
            }
            Files.move(temp.toPath(), File(root, id.toString()).toPath(), StandardCopyOption.ATOMIC_MOVE)
            syncDir(root)
        } finally { key.fill(0); remove(temp) }
    }
    private fun remove(f: File) {
        if (f.exists() && !f.deleteRecursively()) throw IOException("Cleanup incomplete")
    }

    /** M2 encrypted both domains with one key. Only an unlocked open may transcode old records. */
    private fun migrateCapture(b: SealedRecords) {
        val oldState = File(b.dir, "coverage")
        if (!oldState.isFile) return
        pendingBox(b.dir, b.id, create = true).use { q ->
            if (!File(b.dir, "capture.state").exists()) q.saveState(b.state("coverage"))
            for (old in files(b.dir).filter { it.extension == "audio" }) {
                val target = "${old.nameWithoutExtension}.chunk"
                b.read(old.name) {
                    val bytes = readBytes()
                    try {
                        if (!File(b.dir, target).exists()) q.writeChunk(target) { write(bytes) }
                        q.readChunk(target) {
                            val copied = readBytes()
                            try { check(bytes.contentEquals(copied)) } finally { copied.fill(0) }
                        }
                    } finally { bytes.fill(0) }
                }
                syncDir(b.dir); Files.delete(old.toPath())
            }
            syncDir(b.dir)
            Files.delete(oldState.toPath()); syncDir(b.dir)
        }
    }

    /** Cold charging worker: inspect only the AFU queue. No source, revision content or private recipient key. */
    @Synchronized fun loadPending() {
        if (queueLoaded) return
        pending.clear(); sealed.clear()
        if (root.isDirectory) for (dir in dirs()) {
            val id = UUID.fromString(dir.name)
            val entries = files(dir)
            entries.filter { it.extension == "sealed" }.forEach { sealed.add(id to it.nameWithoutExtension.toLong()) }
            if (entries.none { it.extension == "chunk" }) continue
            pendingBox(dir, id).use { q ->
                for (file in entries.filter { it.extension == "chunk" }) {
                    val seq = file.nameWithoutExtension.toLong()
                    if (id to seq !in sealed) pending[id to seq] = q.readChunk(file.name) {
                        Queued(id, seq, readLong(), readLong(), readInt(), readLong(), file.length())
                    }
                }
            }
        }
        recalculate(); queueLoaded = true
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
        for (dir in dirs()) {
            val id = UUID.fromString(dir.name)
            files(dir).filter { it.extension == "part" }.forEach(::remove)
            box(id).use { b ->
                migrateCapture(b)
                if (File(dir, "capture-key").exists()) {
                    val done = snapshot(b).segments.mapTo(hashSetOf()) { it.order }
                    files(dir).filter { it.extension == "chunk" && it.nameWithoutExtension.toLong() in done }.forEach(::remove)
                }
            }
        }
        queueLoaded = false; loadPending()
        val result = dirs().map { dir ->
            val id = UUID.fromString(dir.name)
            val src = source(id)
            val old = src.coverage
            if (old != null) {
                pendingBox(dir, id).use { q ->
                    var c = repaired(q, old)
                    if (c.phase == CapturePhase.ACTIVE && active?.id != id) {
                        c = ended(c, EndReason.PROCESS, c.lastAt, c.sample)
                    }
                    if (c != old) q.saveState(c)
                    src.copy(coverage = c)
                }
            } else src
        }.sortedByDescending { it.createdAt }
        recalculate()
        initialized = true
        return result
    }
    private fun repaired(q: SealedRecords, c: Coverage): Coverage {
        val missing = pending.values.filter { it.source == q.id && it.sequence > c.sequence }
        val tail = missing.maxByOrNull { it.sequence } ?: return c
        return c.copy(sequence = tail.sequence, sample = tail.end, lastAt = tail.at,
            speechSamples = c.speechSamples + missing.sumOf { it.samples.toLong() })
    }
    @Synchronized fun source(id: UUID) = box(id).use(::snapshot)
    @Synchronized fun currentRevision(id: UUID): Long? =
        if (!File(root, id.toString()).isDirectory) null else box(id).use { revisions(it.dir).lastOrNull() ?: 0L }
    @Synchronized fun sourceIfPresent(id: UUID) = if (File(root, id.toString()).isDirectory) source(id) else null
    @Synchronized fun add(origin: Origin, value: String): Source =
        Source(UUID.randomUUID(), System.currentTimeMillis(), origin, cleanText(value)).also(::make)

    @Synchronized fun forget(id: UUID) {
        if (active?.id == id) throw LocalProblem("Stop listening before forgetting this session.")
        val trash = File(root, "$id.trash")
        Files.move(File(root, id.toString()).toPath(), trash.toPath(), StandardCopyOption.ATOMIC_MOVE)
        syncDir(root)
        pending.keys.removeAll { it.first == id }
        sealed.removeAll { it.first == id }
        recalculate()
        remove(trash)
    }
    fun backlog() = backlogSize
    private fun recalculate() {
        awaitingUnlock = sealed.size
        backlogSize = Backlog(pending.size, pending.values.sumOf { it.bytes }, pending.values.sumOf { it.samples.toLong() })
    }
    private fun reserve() {
        if (!backlog().canReserve(maxChunks - sealed.size, maxBytes)) throw CaptureHalt(EndReason.BACKLOG)
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
        box(id).close() // Only an unlocked, live source may acquire a writer.
        val b = pendingBox(File(root, id.toString()), id)
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
            reserve()
            val seq = c.sequence + 1
            b.writeChunk("$seq.chunk") {
                writeLong(start); writeLong(end); writeInt(samples); writeLong(now); write(encoded)
            }
            val item = Queued(id, seq, start, end, samples, now, File(b.dir, "$seq.chunk").length())
            pending[id to seq] = item
            recalculate()
            c = c.copy(sequence = seq, lastAt = now, sample = maxOf(c.sample, end), speechSamples = c.speechSamples + samples)
            b.saveState(c)
            syncDir(b.dir)
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
    @Synchronized fun finishPaused(id: UUID, now: Long) = box(id).use { source -> pendingBox(source.dir, id).use { b ->
        val c = b.state()
        if (c.phase != CapturePhase.PAUSED) throw LocalProblem("Not a paused source.")
        val gaps = c.boundaries.toMutableList()
        gaps[gaps.lastIndex] = gaps.last().copy(resumedAt = now)
        b.saveState(ended(c.copy(boundaries = gaps), EndReason.STOP, now, c.sample))
    } }
    @Synchronized fun queue(): List<Queued> = pending.values.toList()
    @Synchronized fun hasSealed(id: UUID, seq: Long) = File(File(root, id.toString()), "$seq.sealed").isFile
    @Synchronized fun input(id: UUID, seq: Long): AudioInput? {
        val dir = File(root, id.toString())
        val file = File(dir, "$seq.chunk")
        if (!file.isFile || File(dir, "$seq.sealed").isFile) return null
        return pendingBox(dir, id).use { b ->
            val rev = revisions(b.dir).lastOrNull() ?: 0L
            b.readChunk(file.name) {
                val item = Queued(id, seq, readLong(), readLong(), readInt(), readLong(), file.length())
                // A cold locked worker opens only its assigned chunk; no vault/queue-wide scan.
                pending[id to seq] = item
                AudioInput(item, rev, readBytes())
            }
        }
    }
    @Synchronized fun publish(input: AudioInput, value: String, producer: String): Segment =
        box(input.item.source).use { b ->
            val rev = revisions(b.dir).lastOrNull() ?: 0L
            val item = input.item
            val chunk = File(b.dir, "${item.sequence}.chunk")
            if (pending[b.id to item.sequence] != item || !chunk.isFile || rev != input.revision)
                throw LocalProblem("Stale transcription; nothing published.")
            val seg = input.passage(value, producer)
            b.writeSegment(rev + 1, seg)
            syncDir(b.dir)
            pending.remove(b.id to item.sequence)
            recalculate()
            Files.delete(chunk.toPath())
            seg
        }
    @Synchronized fun sealResult(input: AudioInput, segment: Segment, envelope: OvernightEnvelope) {
        val item = input.item
        val ref = item.source to item.sequence
        val dir = File(root, item.source.toString())
        val chunk = File(dir, "${item.sequence}.chunk")
        if (pending[ref] != item || !chunk.isFile) throw LocalProblem("Input forgotten or retired. No result published.")
        if (input.revision != (revisions(dir).lastOrNull() ?: 0L)) throw RevisionAdvanced()
        require(segment.id == asrId(item.source, item.sequence) && segment.order == item.sequence)
        pendingBox(dir, item.source).use { q ->
            val c = q.state()
            val next = repaired(q, c)
            if (c != next) q.saveState(next)
            val bytes = NightPassage(input.revision, segment).encode()
            try {
                val opaque = envelope.seal(item.source, item.sequence, bytes)
                // Authenticate the expected job with the queue key. A public-key ciphertext alone
                // does not prove that the local worker made it. The queue key cannot open opaque.
                q.write("${item.sequence}.sealed") {
                    writeLong(input.revision); writeLong(item.sequence); write(opaque)
                }
            } finally { bytes.fill(0) }
        }
        // fsync the rename, not only file data, before unlinking the sole audio copy.
        syncDir(dir)
        pending.remove(ref); sealed.add(ref); recalculate()
        Files.delete(chunk.toPath()); syncDir(dir)
    }

    @Synchronized fun mergeSealed(envelope: OvernightEnvelope, checkUnlocked: () -> Unit): MergeResult {
        var merged = 0; var stale = 0
        val changed = mutableSetOf<UUID>()
        for ((id, entries) in sealed.groupBy { it.first }) {
            checkUnlocked()
            box(id).use { b ->
                val src = snapshot(b)
                val done = src.segments.mapTo(hashSetOf()) { it.order }
                val publish = mutableListOf<NightPassage>()
                for ((_, seq) in entries.sortedBy { it.second }) {
                    checkUnlocked()
                    val result = pendingBox(b.dir, id).use { q -> q.read("$seq.sealed") {
                        val base = readLong(); check(readLong() == seq)
                        val bytes = envelope.open(id, seq, readBytes())
                        try { NightPassage.decode(id, bytes).also { check(it.base == base) } } finally { bytes.fill(0) }
                    } }
                    require(result.segment.order == seq)
                    when {
                        seq in done -> Unit // committed batch, crash before cleanup
                        result.base != src.revision -> stale++
                        else -> publish.add(result)
                    }
                }
                checkUnlocked()
                if (publish.isNotEmpty()) {
                    b.writeBatch(src.revision + 1, publish); syncDir(b.dir)
                    merged += publish.size; changed.add(id)
                }
                // Never let a crash leave only the retired input (especially for rejected jobs).
                for ((_, seq) in entries) Files.deleteIfExists(File(b.dir, "$seq.chunk").toPath())
                syncDir(b.dir)
                for (entry in entries) {
                    val seq = entry.second
                    Files.delete(File(b.dir, "$seq.sealed").toPath())
                    sealed.remove(entry)
                }
                syncDir(b.dir); recalculate()
            }
        }
        return MergeResult(merged, stale, changed)
    }
    @Synchronized fun review(id: UUID, expected: Long, segment: UUID, text: String): Source = box(id).use { b ->
        val src = snapshot(b)
        if (src.revision != expected) throw LocalProblem("Source changed; reopen the passage.")
        val old = src.segments.single { it.id == segment }
        val updated = cleanText(text)
        b.writeSegment(expected + 1, old.copy(text = updated, pick = null, provenance =
            if (updated == old.text) Provenance.USER_REVIEWED else Provenance.USER_CORRECTED))
        snapshot(b)
    }
}
