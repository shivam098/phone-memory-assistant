package dev.phonememory

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import java.io.IOException
import java.security.GeneralSecurityException
import java.util.UUID

class ListeningService : Service() {
    private val app get() = application as MemoryApp
    private val manager get() = getSystemService(NotificationManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private var run: UUID? = null
    private var request = MicInterrupt()

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val token = intent?.data?.schemeSpecificPart?.substringAfter("run/", "")
            ?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        when {
            token != null && run == token && intent.action in setOf(PAUSE, STOP, DISMISS) -> {
                app.stopping(token)
                request.signal(when (intent.action) {
                    STOP -> EndReason.STOP; PAUSE -> EndReason.PAUSE; else -> EndReason.CONTROLS
                })
            }
            token != null && intent.action == START && run == null && app.consume(token) -> {
                val resume = app.listening.value.source
                if (!controlsAvailable(this)) {
                    app.ended(token, EndReason.CONTROLS); stopSelf()
                } else {
                    try {
                        if (Build.VERSION.SDK_INT >= 30) {
                            startForeground(LIVE, notification(token), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
                        } else startForeground(LIVE, notification(token))
                        manager.cancel(STOPPED)
                        run = token
                        request = MicInterrupt()
                        val halt = request
                        Thread({ listen(token, resume, halt) }, "memory-capture").start()
                    } catch (_: SecurityException) {
                        app.ended(token, EndReason.PERMISSION); stopSelf()
                    } catch (_: IllegalStateException) {
                        app.ended(token, EndReason.SERVICE); stopSelf()
                    }
                }
            }
            run == null -> stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun listen(token: UUID, resume: UUID?, halt: MicInterrupt) {
        var reason = EndReason.SERVICE
        var writer: Journal.Writer? = null
        var gate: VoiceGate? = null
        var pack: MinutePack? = null
        var position = 0L
        var observedAt = System.currentTimeMillis()
        val power = PowerGuard(this)
        val powerChange = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                power.captureReason()?.let(halt::signal)
            }
        }
        var watchingPower = false
        try {
            power.captureReason()?.let { throw CaptureHalt(it) }
            registerReceiver(powerChange, IntentFilter(Intent.ACTION_BATTERY_CHANGED).apply {
                addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
            })
            watchingPower = true
            val baseline = app.listening.value.baseline
            val w = app.journal.begin(resume, System.currentTimeMillis(), power.percent, power.charge, baseline)
            writer = w
            position = w.firstSample
            app.progress(token, w.id, app.journal.backlog())
            var firstFrameAt: Long? = null
            var warned = false
            fun wall(sample: Long) = (firstFrameAt ?: System.currentTimeMillis()) + (sample - w.firstSample) * 1000 / SAMPLE_RATE
            val p = if (baseline) null else MinutePack { block ->
                val encoded = try { FlacCodec.encode(block.pcm) }
                catch (_: Exception) { throw CaptureHalt(EndReason.CODEC) }
                val item = try { w.append(block.start, block.end, block.pcm.size, encoded, wall(block.end)) }
                finally { encoded.fill(0) }
                ChargingTranscription.enqueue(this, item)
                val backlog = app.journal.backlog()
                app.progress(token, w.id, backlog, block.end)
                if (!warned && backlog.nearlyFull) {
                    manager.notify(LIVE, notification(token, started = true, nearlyFull = true))
                    warned = true
                }
                w.reserveNext()
            }
            pack = p
            val g = p?.let {
                val detector = try { SileroGate(this) } catch (_: RuntimeException) { throw CaptureHalt(EndReason.GATE) }
                VoiceGate(w.firstSample, detector, it::add)
            }
            gate = g
            val readyAt = android.os.SystemClock.elapsedRealtime()
            var nextBeat = w.firstSample
            reason = captureMic(this, { data, n ->
                if (firstFrameAt == null) {
                    firstFrameAt = System.currentTimeMillis() - n * 1000L / SAMPLE_RATE
                    manager.notify(LIVE, notification(token, started = true))
                }
                g?.feed(data, n)
                position += n
                observedAt = wall(position)
            }, halt, {
                controlsAvailable(this) && (android.os.SystemClock.elapsedRealtime() - readyAt < 1_000 ||
                    manager.activeNotifications.any { it.id == LIVE })
            }) {
                p?.tick(g?.durablePosition ?: position)
                if (position >= nextBeat) {
                    val safe = minOf(g?.durablePosition ?: position, p?.pendingStart ?: Long.MAX_VALUE)
                    w.checkpoint(safe, wall(safe))
                    app.progress(token, w.id, app.journal.backlog(), position)
                    nextBeat = position + 30L * SAMPLE_RATE
                }
            }
        } catch (e: CaptureHalt) { reason = e.reason }
        catch (_: SecurityException) { reason = EndReason.PERMISSION }
        catch (_: IOException) { reason = EndReason.STORAGE }
        catch (_: GeneralSecurityException) { reason = EndReason.STORAGE }
        catch (_: LocalProblem) { reason = EndReason.SERVICE }
        catch (_: IllegalArgumentException) { reason = EndReason.MIC }
        catch (_: IllegalStateException) { reason = EndReason.MIC }
        catch (_: LinkageError) { reason = EndReason.GATE }
        finally {
            if (watchingPower) unregisterReceiver(powerChange)
            try { gate?.finish(); pack?.flush() }
            catch (e: CaptureHalt) { if (reason != EndReason.STOP && reason != EndReason.PAUSE) reason = e.reason }
            catch (_: Exception) { reason = EndReason.STORAGE }
            try {
                writer?.end(reason, observedAt, position, power.percent, power.charge)
            } catch (_: Exception) { reason = EndReason.STORAGE }
            val finalReason = reason
            main.post {
                // A destroyed instance may complete after a new service object exists.
                if (app.listening.value.token == token) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    run = null
                    if (finalReason != EndReason.STOP && controlsAvailable(this)) {
                        manager.notify(STOPPED, base().setContentTitle("Listening stopped")
                            .setContentText("Open Phone Memory to review or resume.")
                            .setWhen(System.currentTimeMillis()).setShowWhen(true).setAutoCancel(true).build())
                    }
                    app.ended(token, finalReason)
                }
                stopSelf()
            }
        }
    }
    override fun onDestroy() { request.signal(EndReason.SERVICE); super.onDestroy() }

    private fun base(): Notification.Builder = Notification.Builder(this, CHANNEL)
        .setSmallIcon(R.drawable.ic_notebook).setOnlyAlertOnce(true)
        .setVisibility(Notification.VISIBILITY_PUBLIC)
        .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
    private fun notification(token: UUID, started: Boolean = false, nearlyFull: Boolean = false): Notification {
        fun action(cmd: String) = PendingIntent.getService(this, 0, app.command(cmd, token), PendingIntent.FLAG_IMMUTABLE)
        val b = base().setContentTitle(if (started) "Phone Memory • microphone on" else "Phone Memory • starting microphone")
            .setContentText(if (nearlyFull) "Audio queue nearly full. Open the app to manage it." else "Use Pause or Stop to release the microphone.").setOngoing(true)
            .setDeleteIntent(action(DISMISS))
            .addAction(Notification.Action.Builder(null, "Pause", action(PAUSE)).build())
            .addAction(Notification.Action.Builder(null, "Stop", action(STOP)).build())
        if (Build.VERSION.SDK_INT >= 31) b.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        return b.build()
    }
    companion object {
        const val START = "dev.phonememory.START"
        const val PAUSE = "dev.phonememory.PAUSE"
        const val STOP = "dev.phonememory.STOP"
        private const val DISMISS = "dev.phonememory.DISMISS"
        private const val CHANNEL = "listening-controls"
        private const val LIVE = 41
        const val STOPPED = 42
        fun createChannel(context: Context) {
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, "Listening controls", NotificationManager.IMPORTANCE_LOW))
        }
        fun controlsAvailable(context: Context): Boolean {
            val m = context.getSystemService(NotificationManager::class.java)
            val channel = m.getNotificationChannel(CHANNEL) ?: return false
            if (context.getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked) {
                // @Readable AOSP settings. Unknown OEM policy fails closed.
                val allowed = try {
                    android.provider.Settings.Secure.getInt(context.contentResolver, "lock_screen_show_notifications", 0) == 1 &&
                        (channel.importance > NotificationManager.IMPORTANCE_LOW ||
                            android.provider.Settings.Secure.getInt(context.contentResolver, "lock_screen_show_silent_notifications", 1) == 1)
                } catch (_: SecurityException) { false }
                if (!allowed || channel.lockscreenVisibility == Notification.VISIBILITY_SECRET) return false
            }
            return (Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED) && m.areNotificationsEnabled() &&
                channel.importance >= NotificationManager.IMPORTANCE_LOW
        }
    }
}
