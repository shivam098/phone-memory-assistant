package dev.phonememory

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID

internal class PowerDeferred : Exception()

internal suspend fun processChunk(app: MemoryApp, engine: LocalInference.ChunkDecoder,
                                  item: Queued, chargingOnly: Boolean): Boolean {
    val power = PowerGuard(app)
    suspend fun guard() {
        currentCoroutineContext().ensureActive()
        if (!power.canProcess(chargingOnly)) throw PowerDeferred()
    }
    guard()
    val input = app.journal.input(item.source, item.sequence) ?: return false
    input.use {
        val pcm = FlacCodec.decode(it.encoded, item.samples)
        try {
            val texts = ArrayList<String>()
            var start = 0
            while (start < pcm.size) {
                guard()
                // The current candidate truncates at 30 s. Split only inside this chunk.
                val n = minOf(SAMPLE_RATE * 20, pcm.size - start)
                val slice = FloatArray(n) { i -> pcm[start + i] / 32768f }
                try { texts.add(engine.decode(slice)) } finally { slice.fill(0f) }
                start += n
            }
            guard()
            app.journal.publish(it, texts.joinToString(" "), engine.producer)
            app.changed(item.source)
            return true
        } finally { pcm.fill(0) }
    }
}

class ChargingTranscription(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val app = applicationContext as MemoryApp
        val id = inputData.getString("source")?.let(UUID::fromString) ?: return@withContext Result.failure()
        val sequence = inputData.getLong("sequence", -1)
        if (!PowerGuard(app).canProcess(true)) return@withContext Result.retry()
        try {
            app.modelLock.withLock {
                if (!PowerGuard(app).canProcess(true)) return@withContext Result.retry()
                app.journal.recoverIfNeeded(app.legacy)
                val item = app.journal.queue().find { it.source == id && it.sequence == sequence }
                    ?: return@withContext Result.success()
                if (!app.inference.models.getValue(Model.SPEECH).installed()) return@withContext Result.success()
                app.inference.openSpeech(SpeechHint.AUTO).use { engine -> processChunk(app, engine, item, true) }
            }
            Result.success()
        } catch (e: CancellationException) { throw e }
        catch (_: PowerDeferred) { Result.retry() }
        catch (_: Exception) { app.processingFailure(); Result.failure() }
        catch (_: LinkageError) { app.processingFailure(); Result.failure() }
    }
    companion object {
        fun enqueue(ctx: Context, item: Queued) {
            val req = OneTimeWorkRequestBuilder<ChargingTranscription>()
                .setConstraints(Constraints.Builder().setRequiresCharging(true).setRequiresBatteryNotLow(true).build())
                .setInputData(workDataOf("source" to item.source.toString(), "sequence" to item.sequence))
                .addTag("asr:${item.source}").build()
            WorkManager.getInstance(ctx).enqueueUniqueWork("asr:${item.source}:${item.sequence}", ExistingWorkPolicy.KEEP, req)
        }
    }
}
