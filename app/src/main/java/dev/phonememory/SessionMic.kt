package dev.phonememory

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioRecordingConfiguration
import android.media.AudioRouting
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.util.concurrent.Executor
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

internal class MicInterrupt {
    private val cause = AtomicReference<EndReason?>(null)
    private var mic: AudioRecord? = null
    fun get() = cause.get()
    @Synchronized fun attach(record: AudioRecord?) { mic = record; if (get() != null) stopInput() }
    @Synchronized fun signal(reason: EndReason) { cause.compareAndSet(null, reason); stopInput() }
    private fun stopInput() {
        try { mic?.takeIf { it.recordingState == AudioRecord.RECORDSTATE_RECORDING }?.stop() }
        catch (_: IllegalStateException) { /* Already released; first observed reason wins. */ }
    }
}

@SuppressLint("MissingPermission")
internal fun captureMic(context: Context, feed: (ShortArray, Int) -> Unit, halt: MicInterrupt,
                        controls: () -> Boolean, frameRead: () -> Unit): EndReason {
    val audio = context.getSystemService(AudioManager::class.java)
    fun policy(): EndReason? = when {
        halt.get() != null -> halt.get()
        context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED -> EndReason.PERMISSION
        !controls() -> EndReason.CONTROLS
        audio.mode in setOf(AudioManager.MODE_IN_CALL, AudioManager.MODE_IN_COMMUNICATION, AudioManager.MODE_RINGTONE) -> EndReason.CALL
        audio.isMicrophoneMute -> EndReason.MUTED
        else -> null
    }
    policy()?.let { return it }
    val channel = AudioFormat.CHANNEL_IN_MONO
    val encoding = AudioFormat.ENCODING_PCM_16BIT
    val min = AudioRecord.getMinBufferSize(SAMPLE_RATE, channel, encoding)
    if (min <= 0) return EndReason.MIC
    val mic = AudioRecord.Builder().setAudioSource(MediaRecorder.AudioSource.MIC)
        .setAudioFormat(AudioFormat.Builder().setSampleRate(SAMPLE_RATE).setChannelMask(channel).setEncoding(encoding).build())
        .setBufferSizeInBytes(maxOf(min, SAMPLE_RATE * 2)).build()
    val cfg = object : AudioManager.AudioRecordingCallback() {
        override fun onRecordingConfigChanged(configs: List<AudioRecordingConfiguration>) {
            if (configs.any { it.clientAudioSessionId == mic.audioSessionId && it.isClientSilenced }) {
                halt.signal(EndReason.SILENCED)
            }
        }
    }
    val initialRoute = AtomicReference<Int?>(null)
    fun checkRoute() {
        val route = mic.routedDevice?.id
        if (route != null) initialRoute.compareAndSet(null, route)
        if (initialRoute.get() != route) halt.signal(EndReason.ROUTE)
    }
    val routeListener = AudioRouting.OnRoutingChangedListener { checkRoute() }
    val free = ArrayBlockingQueue<ShortArray>(12).apply { repeat(12) { add(ShortArray(4096)) } }
    val ready = ArrayBlockingQueue<Pair<ShortArray, Int>>(12)
    var reader: Thread? = null
    val running = java.util.concurrent.atomic.AtomicBoolean(true)
    var configRegistered = false
    var routeRegistered = false
    try {
        if (mic.state != AudioRecord.STATE_INITIALIZED) return EndReason.MIC
        mic.registerAudioRecordingCallback(Executor { it.run() }, cfg); configRegistered = true
        mic.addOnRoutingChangedListener(routeListener, Handler(Looper.getMainLooper())); routeRegistered = true
        mic.startRecording()
        halt.attach(mic)
        if (mic.recordingState != AudioRecord.RECORDSTATE_RECORDING) return EndReason.MIC
        val started = SystemClock.elapsedRealtime()
        var nextPolicy = 0L
        var zeros = 0
        reader = Thread({
            try {
                var previous = SystemClock.elapsedRealtime()
                while (running.get() && halt.get() == null) {
                    val buf = free.poll() ?: break
                    val n = mic.read(buf, 0, buf.size, AudioRecord.READ_BLOCKING)
                    val readAt = SystemClock.elapsedRealtime()
                    if (n <= 0 || readAt - previous > 2_000) break
                    previous = readAt
                    ready.put(buf to n)
                }
            } catch (_: SecurityException) { halt.signal(EndReason.PERMISSION) }
            catch (_: IllegalStateException) { halt.signal(EndReason.MIC) }
            finally { if (running.get()) halt.signal(EndReason.NO_INPUT) }
        }, "memory-mic").apply { start() }
        while (true) {
            halt.get()?.let { return it }
            val now = SystemClock.elapsedRealtime()
            if (now >= nextPolicy) {
                policy()?.let { return it }
                checkRoute()
                if (initialRoute.get() == null && now - started > 2_000) return EndReason.ROUTE
                if (mic.activeRecordingConfiguration?.isClientSilenced == true) return EndReason.SILENCED
                nextPolicy = now + 1_000
            }
            val (buf, n) = ready.poll(2, TimeUnit.SECONDS) ?: return halt.get() ?: EndReason.NO_INPUT
            halt.get()?.let { return it }
            for (i in 0 until n) zeros = if (buf[i].toInt() == 0) zeros + 1 else 0
            // Some privacy toggles send zero PCM instead of reporting a silenced client.
            if (zeros >= SAMPLE_RATE * 2) return EndReason.NO_INPUT
            feed(buf, n)
            free.add(buf)
            frameRead()
        }
    } finally {
        running.set(false)
        halt.attach(null)
        try {
            if (routeRegistered) mic.removeOnRoutingChangedListener(routeListener)
            if (configRegistered) mic.unregisterAudioRecordingCallback(cfg)
        } finally {
            try { if (mic.recordingState == AudioRecord.RECORDSTATE_RECORDING) mic.stop() }
            finally { mic.release(); reader?.join(500); free.forEach { it.fill(0) }; ready.forEach { it.first.fill(0) } }
        }
    }
}
