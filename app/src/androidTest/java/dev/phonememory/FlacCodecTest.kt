package dev.phonememory

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FlacCodecTest {
    @Test fun signedPcmRoundTripsBitExactly() {
        var state = 17
        val signal = ShortArray(60 * 16_000) { i ->
            state = state * 1664525 + 1013904223
            when (i % 8191) {
                0 -> Short.MIN_VALUE
                1 -> Short.MAX_VALUE
                in 2..127 -> 0
                in 128..511 -> (i * 73).toShort()
                else -> (state ushr 16).toShort()
            }
        }
        // A single sample, an incompressible full block, and a multi-buffer clip with a partial tail.
        for (pcm in listOf(shortArrayOf(-257), signal.copyOfRange(512, 512 + 4096), signal)) {
            val stream = FlacCodec.encode(pcm)
            assertTrue(stream.size <= 2_100_000)
            assertArrayEquals("fLaC".toByteArray(Charsets.US_ASCII), stream.copyOf(4))
            assertArrayEquals(pcm, FlacCodec.decode(stream, pcm.size))
        }
    }

    @Test fun rejectsMalformedMetadataAndWrongSampleCounts() {
        val pcm = ShortArray(8193) { (it * 127).toShort() }
        val stream = FlacCodec.encode(pcm)
        val badLength = stream.clone().apply { this[7] = 0x7f }
        assertThrows(IllegalArgumentException::class.java) { FlacCodec.decode(badLength, pcm.size) }
        assertThrows(IllegalArgumentException::class.java) { FlacCodec.decode(stream, pcm.size + 1) }
        val badChecksum = stream.clone().apply { this[26] = (this[26].toInt() xor 1).toByte() }
        assertThrows(IllegalArgumentException::class.java) { FlacCodec.decode(badChecksum, pcm.size) }
        // A platform decoder may reject the cut frame before our count/checksum check.
        assertThrows(Exception::class.java) { FlacCodec.decode(stream.copyOf(stream.size - 2), pcm.size) }

        // Zero STREAMINFO total means "unknown", not permission to truncate or zero-pad.
        val unknownCount = stream.clone().apply {
            this[21] = (this[21].toInt() and 0xf0).toByte()
            fill(0, 22, 26)
        }
        assertArrayEquals(pcm, FlacCodec.decode(unknownCount, pcm.size))
        assertThrows(IllegalArgumentException::class.java) { FlacCodec.decode(unknownCount, pcm.size - 1) }
        assertThrows(IllegalArgumentException::class.java) { FlacCodec.decode(unknownCount, pcm.size + 1) }
        assertThrows(IllegalArgumentException::class.java) { FlacCodec.decode(stream, Int.MAX_VALUE) }
        assertThrows(IllegalArgumentException::class.java) { FlacCodec.decode(stream.copyOf(2_100_001), pcm.size) }
        assertThrows(IllegalArgumentException::class.java) { FlacCodec.encode(ShortArray(960_001)) }
    }
}
