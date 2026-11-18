package dev.phonememory

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.await
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

class OvernightMerge(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val app = applicationContext as MemoryApp
        if (app.isLocked) return@withContext Result.retry()
        try {
            mergeReady(app)
            Result.success()
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) {
            if (app.isLocked) Result.retry() else {
                app.overnightNotice("merge-failed"); Result.failure()
            }
        }
    }

    companion object {
        const val STATUS = "Transcribed overnight — available after unlock"
        private const val CHANNEL = "overnight-results"
        private const val NOTICE = 43

        fun enqueue(app: MemoryApp, name: String) =
            WorkManager.getInstance(app).enqueueUniqueWork("overnight-merge:$name", ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<OvernightMerge>().build())
        suspend fun afterSeal(app: MemoryApp, id: UUID, seq: Long, whileLocked: Boolean) {
            enqueue(app, "$id:$seq").await()
            app.changed(id)
            if (!whileLocked || !app.isLocked || app.journal.awaitingUnlock == 0) return
            val m = app.getSystemService(NotificationManager::class.java)
            if (!m.areNotificationsEnabled() || (Build.VERSION.SDK_INT >= 33 &&
                    app.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)) return
            m.createNotificationChannel(NotificationChannel(CHANNEL, "Overnight processing", NotificationManager.IMPORTANCE_LOW))
            val open = PendingIntent.getActivity(app, NOTICE, Intent(app, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
            try {
                m.notify(NOTICE, Notification.Builder(app, CHANNEL).setSmallIcon(R.drawable.ic_notebook)
                    .setContentTitle("Phone Memory").setContentText(STATUS).setContentIntent(open)
                    .setAutoCancel(true).setOnlyAlertOnce(true).setVisibility(Notification.VISIBILITY_PUBLIC).build())
            } catch (_: SecurityException) { /* Notification access revoked; the sealed result is still safe. */ }
        }

        internal fun mergeReady(app: MemoryApp): MergeResult {
            app.journal.loadPending()
            if (app.journal.awaitingUnlock == 0) {
                app.getSystemService(NotificationManager::class.java).cancel(NOTICE)
                return MergeResult()
            }
            val result = app.journal.mergeSealed(app.nightKeys.envelope) {
                if (app.isLocked) throw LocalProblem("Unlock to merge sealed passages.")
            }
            if (result.merged > 0) app.overnightNotice("merged")
            if (result.stale > 0) app.overnightNotice("stale")
            result.sources.forEach(app::changed)
            if (app.journal.awaitingUnlock == 0) app.getSystemService(NotificationManager::class.java).cancel(NOTICE)
            return result
        }
    }
}
