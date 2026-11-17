package dev.phonememory

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioRecordingConfiguration
import android.media.MediaRecorder
import android.os.SystemClock
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import java.util.concurrent.Executor
import kotlin.math.abs

const val SAMPLE_RATE = 16_000
const val CAPTURE_SECONDS = 25

/** Single use. A focus/lock interruption always wins over a pending Finish tap. */
class ForegroundMic {
    @Volatile private var finished = false
    @Volatile private var discarded = false
    @Volatile private var silenced = false
    private var recorder: AudioRecord? = null

    @Synchronized
    fun stop(discard: Boolean) {
        discarded = discarded || discard
        finished = true
        recorder?.let {
            if (it.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                try { it.stop() } catch (_: IllegalStateException) { discarded = true }
            }
        }
    }

    @SuppressLint("MissingPermission") // Activity checks permission on every user-initiated capture.
    suspend fun collect(onSecond: (Int) -> Unit): FloatArray {
        val channel = AudioFormat.CHANNEL_IN_MONO
        val encoding = AudioFormat.ENCODING_PCM_16BIT
        val minimum = AudioRecord.getMinBufferSize(SAMPLE_RATE, channel, encoding)
        if (minimum <= 0) throw LocalProblem("This microphone does not offer 16 kHz mono capture.")
        val mic = AudioRecord.Builder().setAudioSource(MediaRecorder.AudioSource.MIC)
            .setAudioFormat(AudioFormat.Builder().setSampleRate(SAMPLE_RATE).setChannelMask(channel)
                .setEncoding(encoding).build())
            .setBufferSizeInBytes(maxOf(minimum, SAMPLE_RATE / 2)).build()
        val callback = object : AudioManager.AudioRecordingCallback() {
            override fun onRecordingConfigChanged(configs: List<AudioRecordingConfiguration>) {
                if (configs.any { it.clientAudioSessionId == mic.audioSessionId && it.isClientSilenced }) {
                    silenced = true
                }
            }
        }
        val samples = ShortArray(SAMPLE_RATE * CAPTURE_SECONDS)
        var count = 0
        var peak = 0
        var registered = false
        try {
            synchronized(this) {
                if (finished) throw LocalProblem("Recording interrupted; audio discarded.")
                if (mic.state != AudioRecord.STATE_INITIALIZED) throw LocalProblem("Microphone is unavailable.")
                recorder = mic
                mic.registerAudioRecordingCallback(Executor { it.run() }, callback)
                registered = true
                mic.startRecording()
                if (mic.recordingState != AudioRecord.RECORDSTATE_RECORDING) throw LocalProblem("Microphone did not start.")
            }
            val start = SystemClock.elapsedRealtime()
            var lastData = start
            var lastSecond = -1
            while (!finished && count < samples.size) {
                currentCoroutineContext().ensureActive()
                if (silenced || mic.activeRecordingConfiguration?.isClientSilenced == true) {
                    throw LocalProblem("Microphone silenced or in use. Recording discarded.")
                }
                val now = SystemClock.elapsedRealtime()
                if (now - lastData > 2_000) throw LocalProblem("Microphone stopped supplying audio. Recording discarded.")
                if (now - start >= CAPTURE_SECONDS * 1000L) break
                val n = mic.read(samples, count, minOf(1600, samples.size - count), AudioRecord.READ_NON_BLOCKING)
                if (n < 0 && !finished) throw LocalProblem("Microphone interrupted. Recording discarded.")
                if (n > 0) {
                    for (i in count until count + n) peak = maxOf(peak, abs(samples[i].toInt()))
                    count += n
                    lastData = now
                } else if (!finished) delay(12)
                val second = count / SAMPLE_RATE
                if (second != lastSecond) { lastSecond = second; onSecond(second) }
            }
            if (discarded || silenced) throw LocalProblem("Recording interrupted; audio discarded.")
            if (count < SAMPLE_RATE / 2 || peak < 32) throw LocalProblem("Not enough audible input to transcribe.")
            return FloatArray(count) { samples[it] / 32768f }
        } finally {
            synchronized(this) {
                recorder = null
                try {
                    if (registered) mic.unregisterAudioRecordingCallback(callback)
                } finally { mic.release() }
            }
            samples.fill(0)
        }
    }
}
