package dev.phonememory

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaDataSource
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.SystemClock
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/** Native FLAC streams, not a serialization of device-specific MediaCodec buffers. */
object FlacCodec : AudioCodec {
    private const val MAX_SAMPLES = 60 * SAMPLE_RATE
    private const val MAX_BYTES = 2_100_000
    private const val PCM_BATCH = 2048
    private const val POLL_US = 10_000L
    private const val STALL_MS = 5_000L
    private const val DEADLINE_MS = 30_000L

    private const val MAGIC = 0x664c6143 // fLaC
    private const val STREAMINFO_SIZE = 34
    private const val HEADER_SIZE = 8 + STREAMINFO_SIZE
    private const val FORMAT_OFFSET = 18
    private const val MD5_OFFSET = 26
    private const val COUNT_MASK = (1L shl 36) - 1
    private const val CSD = "csd-0"

    override fun encode(pcm: ShortArray): ByteArray {
        require(pcm.size in 1..MAX_SAMPLES) { "Invalid PCM sample count" }
        val format = flacFormat().apply { setInteger(MediaFormat.KEY_FLAC_COMPRESSION_LEVEL, 5) }
        var output = ByteBuffer.allocate(minOf(MAX_BYTES, HEADER_SIZE + pcm.size * 2 + 4096))
        output.position(HEADER_SIZE)
        val md5 = MessageDigest.getInstance("MD5") // FLAC's PCM checksum, not authentication.
        var haveHeader = false
        var fed = 0

        fun takeHeader(src: ByteBuffer) {
            val end = metadataEnd(src, pcm.size)
            src.get(output.array(), 0, HEADER_SIZE)
            // Drop optional tags; one final STREAMINFO block suffices for a native stream.
            output.put(4, 0x80.toByte())
            src.position(end)
            haveHeader = true
        }

        try {
            runCodec(format, encode = true, feed = { codec, index, input ->
                // Leave headroom for frame overhead when Codec2 finishes a full,
                // incompressible final block. Send EOS with real PCM, not an empty work item.
                val batch = if (fed == 0) PCM_BATCH / 2 else PCM_BATCH
                val n = minOf(pcm.size - fed, input.remaining() / 2, batch)
                check(n > 0) { "FLAC encoder input too small" }
                input.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(pcm, fed, n)
                input.limit(2 * n)
                md5.update(input)
                val timeUs = sampleTime(fed)
                fed += n
                val eos = fed == pcm.size
                codec.queueInputBuffer(index, 0, 2 * n, timeUs, if (eos) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0)
                eos
            }, onFormat = { fmt ->
                fmt.getByteBuffer(CSD)?.slice()?.order(ByteOrder.BIG_ENDIAN)?.let {
                    takeHeader(it)
                    require(!it.hasRemaining()) { "Unexpected audio in FLAC format metadata" }
                }
            }, consume = { buf, config ->
                // CSD can be reported in both the output format and a config buffer.
                // Also accept an in-band native header without writing it twice.
                if (config || (output.position() == HEADER_SIZE && hasMarker(buf))) takeHeader(buf)
                if (buf.remaining() > output.remaining()) {
                    require(buf.remaining() <= MAX_BYTES - output.position()) { "FLAC output too large" }
                    val grown = ByteBuffer.allocate(MAX_BYTES)
                    output.flip()
                    grown.put(output)
                    output.array().fill(0)
                    output = grown
                }
                output.put(buf)
            })
            check(haveHeader && hasFrame(output, HEADER_SIZE, output.position())) { "Missing FLAC header or audio" }
            // A streaming encoder cannot seek back to fill these standard STREAMINFO fields.
            output.putLong(FORMAT_OFFSET, (output.getLong(FORMAT_OFFSET) and COUNT_MASK.inv()) or fed.toLong())
            md5.digest(output.array(), MD5_OFFSET, 16)
            return output.array().copyOf(output.position())
        } finally {
            output.array().fill(0, 0, output.position())
        }
    }

    override fun decode(data: ByteArray, samples: Int): ShortArray {
        require(samples in 1..MAX_SAMPLES && data.size in HEADER_SIZE..MAX_BYTES) { "Invalid FLAC size" }
        val stream = ByteBuffer.wrap(data)
        val frames = metadataEnd(stream, samples)
        require(hasFrame(stream, frames, data.size)) { "Missing FLAC audio" }
        val maxBlock = stream.getShort(10).toInt() and 0xffff
        val deadline = SystemClock.elapsedRealtime() + DEADLINE_MS
        val expectedMd5 = data.copyOfRange(MD5_OFFSET, HEADER_SIZE)
        val md5 = if (expectedMd5.any { it != 0.toByte() }) MessageDigest.getInstance("MD5") else null
        val result = ShortArray(samples)
        var written = 0

        fun pcm(buf: ByteBuffer) {
            val bytes = buf.remaining()
            require(bytes % 2 == 0 && bytes / 2 <= samples - written) { "Invalid decoded PCM length" }
            buf.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(result, written, bytes / 2)
            written += bytes / 2
            md5?.update(buf)
        }

        var complete = false
        try {
            ArraySource(data, deadline).use { source ->
                val extractor = MediaExtractor()
                try {
                    extractor.setDataSource(source)
                    require(extractor.trackCount == 1) { "Expected one FLAC track" }
                    extractor.selectTrack(0)
                    val track = extractor.getTrackFormat(0)
                    when (track.getString(MediaFormat.KEY_MIME)) {
                        MediaFormat.MIMETYPE_AUDIO_RAW -> {
                            checkPcmFormat(track)
                            readPcmTrack(extractor, maxBlock, deadline, ::pcm)
                        }
                        MediaFormat.MIMETYPE_AUDIO_FLAC -> {
                            decodeFlacTrack(extractor, data, frames, samples, deadline, ::pcm)
                        }
                        else -> throw IllegalArgumentException("Expected FLAC or decoded PCM")
                    }
                } finally {
                    extractor.release()
                }
            }
            require(written == samples) { "Incomplete decoded PCM" }
            require(md5 == null || md5.digest().contentEquals(expectedMd5)) { "FLAC PCM checksum mismatch" }
            complete = true
            return result
        } finally {
            if (!complete) result.fill(0)
        }
    }

    // AOSP FLACExtractor itself decodes each FLAC frame to PCM.
    private fun readPcmTrack(extractor: MediaExtractor, maxBlock: Int, deadline: Long, pcm: (ByteBuffer) -> Unit) {
        val scratch = ByteArray(maxBlock * 2)
        val buf = ByteBuffer.wrap(scratch)
        try {
            do {
                check(SystemClock.elapsedRealtime() < deadline) { "FLAC read timed out" }
                if (readSample(extractor, buf) < 0) break
                pcm(buf)
            } while (extractor.advance())
        } finally {
            scratch.fill(0)
        }
    }

    private fun decodeFlacTrack(
        extractor: MediaExtractor, data: ByteArray, frames: Int, samples: Int, deadline: Long, pcm: (ByteBuffer) -> Unit
    ) {
        // configure() submits CSD automatically. Each subsequent input is one complete access unit.
        val format = flacFormat().apply {
            val csd = data.copyOf(HEADER_SIZE)
            csd[4] = 0x80.toByte()
            setByteBuffer(CSD, ByteBuffer.wrap(csd))
            val maxInput = extractor.getTrackFormat(0).getInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 0)
            if (maxInput > 0) setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, minOf(maxInput, data.size))
        }
        var bytesLeft = data.size - frames
        runCodec(format, encode = false, deadline = deadline, feed = { codec, index, input ->
            val n = readSample(extractor, input)
            if (n < 0) {
                require(bytesLeft == 0) { "Incomplete FLAC track" }
                codec.queueInputBuffer(index, 0, 0, sampleTime(samples), MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            } else {
                val timeUs = extractor.sampleTime
                require(n <= bytesLeft && timeUs >= 0) { "Invalid FLAC access unit" }
                bytesLeft -= n
                codec.queueInputBuffer(index, 0, n, timeUs, 0)
                extractor.advance()
            }
            n < 0
        }, onFormat = ::checkPcmFormat, consume = { buf, config ->
            if (!config) pcm(buf)
        })
    }

    private fun flacFormat() = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_FLAC, SAMPLE_RATE, 1).apply {
        setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
    }

    private fun checkPcmFormat(fmt: MediaFormat) {
        require(fmt.getString(MediaFormat.KEY_MIME) == MediaFormat.MIMETYPE_AUDIO_RAW &&
            fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE) == SAMPLE_RATE &&
            fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT) == 1 &&
            fmt.getInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT) == AudioFormat.ENCODING_PCM_16BIT
        ) { "Expected 16 kHz mono PCM16" }
    }

    private fun sampleTime(samples: Int) = samples * 1_000_000L / SAMPLE_RATE

    private fun hasMarker(buf: ByteBuffer) = buf.limit() >= 4 && buf.getInt(0) == MAGIC

    private fun hasFrame(buf: ByteBuffer, start: Int, end: Int) =
        end - start >= 2 && (buf.getShort(start).toInt() and 0xfffe) == 0xfff8

    // All offsets are relative to the fLaC marker; FLAC fields are big-endian.
    private fun metadataEnd(buf: ByteBuffer, samples: Int): Int {
        require(buf.limit() in HEADER_SIZE..MAX_BYTES && hasMarker(buf)) { "Invalid FLAC header" }
        var block = buf.getInt(4)
        require((block and Int.MAX_VALUE) == STREAMINFO_SIZE) { "Invalid FLAC STREAMINFO" }
        val minBlock = buf.getShort(8).toInt() and 0xffff
        val maxBlock = buf.getShort(10).toInt() and 0xffff
        require(minBlock in 16..maxBlock) { "Invalid FLAC block size" }
        val minFrame = buf.getInt(12) ushr 8
        val maxFrame = buf.getInt(15) ushr 8
        require(minFrame <= MAX_BYTES && maxFrame <= MAX_BYTES && (maxFrame == 0 || minFrame <= maxFrame)) {
            "Invalid FLAC frame sizes"
        }
        val packed = buf.getLong(FORMAT_OFFSET)
        // 20 bits sample rate, 3 channels-minus-one, 5 bits-per-sample-minus-one, 36 count.
        require((packed ushr 36) == ((SAMPLE_RATE.toLong() shl 8) or 15L)) { "Unsupported FLAC format" }
        val count = packed and COUNT_MASK
        require(count == 0L || count == samples.toLong()) { "FLAC sample count mismatch" }
        var end = HEADER_SIZE
        while (block >= 0) { // Last-metadata-block flag is the high bit.
            require(buf.limit() - end >= 4) { "Truncated FLAC metadata" }
            block = buf.getInt(end)
            val type = (block ushr 24) and 0x7f
            val size = block and 0xffffff
            end += 4
            require(type in 1..126 && size <= buf.limit() - end) { "Invalid FLAC metadata block" }
            end += size
        }
        return end
    }

    private fun readSample(extractor: MediaExtractor, buf: ByteBuffer): Int {
        val size = extractor.sampleSize
        if (size == -1L) return -1
        buf.clear()
        require(size in 1L..buf.remaining().toLong()) { "Invalid FLAC track sample length" }
        val n = extractor.readSampleData(buf, 0)
        require(n.toLong() == size) { "Incomplete FLAC track sample" }
        buf.position(0)
        buf.limit(n)
        return n
    }

    private class ArraySource(private val data: ByteArray, private val deadline: Long) : MediaDataSource() {
        override fun getSize() = data.size.toLong()
        override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
            check(SystemClock.elapsedRealtime() < deadline) { "FLAC read timed out" }
            require(position >= 0 && offset in 0..buffer.size && size in 0..buffer.size - offset)
            if (size == 0) return 0
            if (position >= data.size) return -1
            val start = position.toInt()
            val n = minOf(size, data.size - start)
            data.copyInto(buffer, offset, start, start + n)
            return n
        }
        override fun close() = Unit // Caller owns the array.
    }

    @Suppress("DEPRECATION") // INFO_OUTPUT_BUFFERS_CHANGED is harmless with indexed buffers.
    private fun runCodec(
        format: MediaFormat, encode: Boolean, deadline: Long = SystemClock.elapsedRealtime() + DEADLINE_MS,
        feed: (MediaCodec, Int, ByteBuffer) -> Boolean,
        onFormat: (MediaFormat) -> Unit, consume: (ByteBuffer, Boolean) -> Unit
    ) {
        val mime = MediaFormat.MIMETYPE_AUDIO_FLAC
        val codec = if (encode) MediaCodec.createEncoderByType(mime) else MediaCodec.createDecoderByType(mime)
        try {
            codec.configure(format, null, null, if (encode) MediaCodec.CONFIGURE_FLAG_ENCODE else 0)
            codec.start()
            val info = MediaCodec.BufferInfo()
            var inputEnded = false
            var haveFormat = false
            var lastProgress = SystemClock.elapsedRealtime()
            while (true) {
                val now = SystemClock.elapsedRealtime()
                check(now < deadline && now - lastProgress < STALL_MS) { "FLAC codec timed out" }
                var progressed = false
                if (!inputEnded) {
                    val index = codec.dequeueInputBuffer(0)
                    if (index >= 0) {
                        val input = checkNotNull(codec.getInputBuffer(index))
                        input.clear()
                        inputEnded = feed(codec, index, input)
                        progressed = true
                    }
                }
                val index = codec.dequeueOutputBuffer(info, if (progressed) 0L else POLL_US)
                if (index >= 0) {
                    try {
                        check(info.offset >= 0 && info.size >= 0) { "Invalid FLAC codec output range" }
                        if (info.size > 0) {
                            if (!haveFormat) {
                                onFormat(codec.getOutputFormat(index))
                                haveFormat = true
                            }
                            val buf = checkNotNull(codec.getOutputBuffer(index))
                            check(info.size <= buf.capacity() - info.offset) { "Invalid FLAC codec output range" }
                            buf.limit(info.offset + info.size)
                            buf.position(info.offset)
                            val config = (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                            consume(buf.slice().order(ByteOrder.BIG_ENDIAN), config)
                            progressed = true
                        }
                        if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            check(inputEnded) { "Premature FLAC codec EOS" }
                            return
                        }
                    } finally {
                        codec.releaseOutputBuffer(index, false)
                    }
                } else when (index) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        onFormat(codec.outputFormat)
                        haveFormat = true
                    }
                    MediaCodec.INFO_TRY_AGAIN_LATER, MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> Unit
                    else -> error("Unexpected FLAC codec status")
                }
                if (progressed) lastProgress = SystemClock.elapsedRealtime()
            }
        } finally {
            codec.release()
        }
    }
}
