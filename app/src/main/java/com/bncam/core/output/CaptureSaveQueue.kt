package com.bncam.core.output

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.util.Locale

/** Bounded, single-owner output queue. A full queue is an explicit capture failure, never a drop. */
object CaptureSaveQueue {
    private const val CAPACITY = 3
    private val inFlight = java.util.concurrent.atomic.AtomicInteger(0)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
    private val jobs = Channel<SaveJob>(capacity = CAPACITY)

    init {
        scope.launch {
            for (job in jobs) {
                val startedNs = android.os.SystemClock.elapsedRealtimeNanos()
                val waitMs = (startedNs - job.enqueuedNs) / 1_000_000.0
                try {
                    job.block()
                    val completedNs = android.os.SystemClock.elapsedRealtimeNanos()
                    Log.i(
                        "BnCamCaptureTiming",
                        "route=${job.route} save_queue_wait=${fmt(waitMs)} " +
                            "mediastore_write_time=${fmt((completedNs - startedNs) / 1_000_000.0)} " +
                            "total_until_file_saved=${fmt((completedNs - job.captureStartedNs) / 1_000_000.0)}"
                    )
                    job.completion?.complete(Result.success(Unit))
                } catch (failure: Throwable) {
                    Log.e("BnCamSaveQueue", "Output save failed for ${job.route}: ${failure.message}", failure)
                    job.onFailure(failure)
                    Handler(Looper.getMainLooper()).post {
                        Toast.makeText(
                            job.context.applicationContext,
                            "BnCam save failed: ${failure.message ?: failure.javaClass.simpleName}",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                    job.completion?.complete(Result.failure(failure))
                } finally {
                    inFlight.decrementAndGet()
                }
            }
        }
    }

    fun enqueue(
        context: Context,
        route: String,
        captureStartedNs: Long,
        onFailure: (Throwable) -> Unit,
        block: suspend () -> Unit
    ): Boolean {
        return offer(
            context = context,
            route = route,
            captureStartedNs = captureStartedNs,
            onFailure = onFailure,
            completion = null,
            block = block
        )
    }

    suspend fun enqueueAndAwait(
        context: Context,
        route: String,
        captureStartedNs: Long,
        onFailure: (Throwable) -> Unit,
        block: suspend () -> Unit
    ): Boolean {
        val completion = CompletableDeferred<Result<Unit>>()
        if (!offer(context, route, captureStartedNs, onFailure, completion, block)) return false
        completion.await().getOrThrow()
        return true
    }

    private fun offer(
        context: Context,
        route: String,
        captureStartedNs: Long,
        onFailure: (Throwable) -> Unit,
        completion: CompletableDeferred<Result<Unit>>?,
        block: suspend () -> Unit
    ): Boolean {
        val result = jobs.trySend(
            SaveJob(
                context = context,
                route = route,
                captureStartedNs = captureStartedNs,
                enqueuedNs = android.os.SystemClock.elapsedRealtimeNanos(),
                onFailure = onFailure,
                completion = completion,
                block = block
            )
        )
        if (result.isSuccess) {
            inFlight.incrementAndGet()
            return true
        }
        val reason = "Save queue is full ($CAPACITY pending outputs); capture was not accepted."
        Log.e("BnCamSaveQueue", "$route: $reason")
        return false
    }

    fun inFlightCount(): Int = inFlight.get()

    private fun fmt(value: Double): String = String.format(Locale.US, "%.3f", value)

    private data class SaveJob(
        val context: Context,
        val route: String,
        val captureStartedNs: Long,
        val enqueuedNs: Long,
        val onFailure: (Throwable) -> Unit,
        val completion: CompletableDeferred<Result<Unit>>?,
        val block: suspend () -> Unit
    )
}
