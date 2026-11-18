package dev.phonememory

const val SAMPLE_RATE = 16_000
const val CHUNK_SECONDS = 60
const val MAX_ENCODED = 2_100_000
const val QUEUE_CHUNKS = 256
const val QUEUE_BYTES = 64L * 1024 * 1024

enum class CapturePhase { ACTIVE, PAUSED, STOPPED }
enum class EndReason(val label: String) {
    PAUSE("Paused by you"), STOP("Stopped by you"), CALL("Call or communication audio active"),
    SILENCED("Microphone silenced or in use"), PERMISSION("Microphone permission lost"),
    MUTED("Microphone muted or privacy switch on"), ROUTE("Input route changed or lost"),
    NO_INPUT("No usable microphone data"), CONTROLS("Notification controls unavailable"),
    BACKLOG("Pending audio limit reached"), STORAGE("Storage unavailable or too low"),
    PROCESS("Process ended; exact cause and time unknown"), SERVICE("Service interrupted"),
    MIC("Microphone unavailable"), LOW_BATTERY("Battery threshold reached"), SAVER("Battery saver enabled"),
    GATE("Speech gate unavailable"), CODEC("Lossless audio encoding unavailable")
}
data class Boundary(val at: Long, val sample: Long, val reason: EndReason, val resumedAt: Long? = null)
data class Coverage(val phase: CapturePhase, val sample: Long, val lastAt: Long, val sequence: Long,
                    val boundaries: List<Boundary> = emptyList(), val speechSamples: Long = 0,
                    val startBattery: Int = -1, val lastBattery: Int = -1,
                    val startCharge: Long = -1, val lastCharge: Long = -1, val baseline: Boolean = false)
data class Backlog(val count: Int = 0, val bytes: Long = 0, val samples: Long = 0) {
    val nearlyFull get() = count >= QUEUE_CHUNKS * 4 / 5 || bytes >= QUEUE_BYTES * 4 / 5
    fun canReserve(maxCount: Int, maxBytes: Long) =
        count < maxCount && bytes + MAX_ENCODED + 256 <= maxBytes
}
class CaptureHalt(val reason: EndReason) : Exception()

interface AudioCodec {
    fun encode(pcm: ShortArray): ByteArray
    fun decode(data: ByteArray, samples: Int): ShortArray
}
interface SpeechGate : AutoCloseable {
    fun speech(frame: ShortArray, size: Int): Boolean
    fun quiet()
}

/** Loudness rejects cheap negatives first. Only the second-stage classifier can admit speech. */
class VoiceGate(startSample: Long, private val detector: SpeechGate, private val emit: (Long, ShortArray) -> Unit) {
    private val frame = ShortArray(512)
    private val pre = ShortArray(frame.size * 5)
    private val chunk = ShortArray(frame.size * 64)
    private var frameN = 0
    private var preN = 0
    private var preAt = 0
    private var chunkN = 0
    private var quiet = 0
    private var chunkStart = startSample
    var position = startSample; private set
    val durablePosition get() = if (chunkN > 0) chunkStart else position - preN - frameN

    fun feed(data: ShortArray, count: Int) {
        require(count in 0..data.size)
        var off = 0
        while (off < count) {
            val n = minOf(frame.size - frameN, count - off)
            data.copyInto(frame, frameN, off, off + n)
            frameN += n; off += n; position += n
            if (frameN == frame.size) { frameN = 0; consume(frame.size) }
        }
    }
    private fun consume(n: Int) {
        var energy = 0L
        for (i in 0 until n) energy += frame[i].toLong() * frame[i]
        val voiced = if (energy >= 120L * 120 * n) detector.speech(frame, n) else { detector.quiet(); false }
        if (!voiced && chunkN == 0) {
            for (i in 0 until n) {
                pre[preAt] = frame[i]
                preAt = (preAt + 1) % pre.size
                preN = minOf(preN + 1, pre.size)
            }
            return
        }
        if (chunkN == 0) {
            chunkStart = position - n - preN
            for (i in 0 until preN) chunk[chunkN++] = pre[(preAt - preN + i + pre.size) % pre.size]
            preN = 0
        }
        frame.copyInto(chunk, chunkN, 0, n)
        chunkN += n
        quiet = if (voiced) 0 else quiet + n
        if (quiet >= SAMPLE_RATE * 3 / 10 || chunkN == chunk.size) flush()
    }
    private fun flush() {
        if (chunkN == 0) return
        val accepted = chunk.copyOf(chunkN)
        chunkN = 0; quiet = 0
        try { emit(chunkStart, accepted) } finally { accepted.fill(0) }
    }
    fun finish() {
        try {
            if (frameN > 0) { val n = frameN; frameN = 0; consume(n) }
            flush()
        } finally { frame.fill(0); pre.fill(0); chunk.fill(0); detector.close() }
    }
}

data class SpeechBlock(val start: Long, val end: Long, val pcm: ShortArray)
/** Pack retained samples only. Chunk timestamps are coarse capture bounds, never word timings. */
class MinutePack(private val emit: (SpeechBlock) -> Unit) {
    private val samples = ShortArray(CHUNK_SECONDS * SAMPLE_RATE)
    private var size = 0
    private var start = 0L
    private var end = 0L
    val pendingStart: Long? get() = start.takeIf { size > 0 }
    fun add(at: Long, pcm: ShortArray) {
        if (size > 0 && (at + pcm.size - start > samples.size || size + pcm.size > samples.size)) flush()
        if (size == 0) start = at
        pcm.copyInto(samples, size); size += pcm.size; end = at + pcm.size
    }
    fun tick(at: Long) { if (size > 0 && at - start >= samples.size) flush() }
    fun flush() {
        if (size == 0) return
        val block = SpeechBlock(start, end, samples.copyOf(size))
        samples.fill(0, 0, size); size = 0
        try { emit(block) } finally { block.pcm.fill(0) }
    }
}
