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

private suspend fun PowerGuard.checkPower(chargingOnly: Boolean) {
    currentCoroutineContext().ensureActive()
    if (!canProcess(chargingOnly)) throw PowerDeferred()
}

private suspend fun decode(input: AudioInput, engine: LocalInference.ChunkDecoder,
                           power: PowerGuard, chargingOnly: Boolean): Segment {
        val pcm = FlacCodec.decode(input.encoded, input.item.samples)
        try {
            val texts = ArrayList<String>()
            var start = 0
            while (start < pcm.size) {
                power.checkPower(chargingOnly)
                // The current candidate truncates at 30 s. Split only inside this chunk.
                val n = minOf(SAMPLE_RATE * 20, pcm.size - start)
                val slice = FloatArray(n) { i -> pcm[start + i] / 32768f }
                try { texts.add(engine.decode(slice)) } finally { slice.fill(0f) }
                start += n
            }
            return input.passage(texts.joinToString(" "), engine.producer)
        } finally { pcm.fill(0) }
}

internal suspend fun processChunk(app: MemoryApp, engine: LocalInference.ChunkDecoder, item: Queued): Boolean {
    val power = PowerGuard(app)
    power.checkPower(false)
    val input = app.journal.input(item.source, item.sequence) ?: return false
    input.use {
        val seg = decode(it, engine, power, false)
        power.checkPower(false)
        app.journal.publish(it, seg.text, seg.producer)
        app.changed(item.source)
        return true
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
                if (!app.inference.models.getValue(Model.SPEECH).installed()) return@withContext Result.success()
                val input = app.journal.input(id, sequence)
                if (input == null) {
                    if (app.journal.hasSealed(id, sequence)) OvernightMerge.afterSeal(app, id, sequence, false)
                    return@withContext Result.success()
                }
                val power = PowerGuard(app)
                var sealedLocked: Boolean
                input.use {
                    val seg = app.inference.openSpeech(SpeechHint.AUTO).use { engine -> decode(it, engine, power, true) }
                    power.checkPower(true)
                    // Use only *this newly decoded chunk*, never an existing source or previous result.
                    val fresh = Source(id, it.item.at, Origin.LISTENING, it.revision, listOf(seg))
                    val pick = if (app.inference.models.getValue(Model.TEXT).installed()) {
                        try {
                            app.inference.highlight(fresh)?.let { h -> PassagePick(h.span.from, h.span.until, h.line, app.inference.highlightProducer) }
                        } catch (e: CancellationException) { throw e }
                        catch (_: Exception) { app.overnightNotice("no-highlight"); null }
                        catch (_: LinkageError) { app.overnightNotice("no-highlight"); null }
                    } else null
                    power.checkPower(true)
                    sealedLocked = app.isLocked
                    app.journal.sealResult(it, seg.copy(pick = pick), app.nightKeys.envelope)
                }
                OvernightMerge.afterSeal(app, id, sequence, sealedLocked)
            }
            Result.success()
        } catch (e: CancellationException) { throw e }
        catch (_: PowerDeferred) { Result.retry() }
        catch (_: RevisionAdvanced) { Result.retry() } // The old answer was not sealed; read the input anew.
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
