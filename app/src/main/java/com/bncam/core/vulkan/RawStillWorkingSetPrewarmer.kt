package com.bncam.core.vulkan

import android.util.Log
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Moves full-resolution RAW still resource allocation away from the first shutter press.
 *
 * The producer calls request() from the first accepted RAW warm-buffer image, so dimensions are
 * authoritative for the active Camera2 stream. Preparation is allocation-only and fail-open.
 */
object RawStillWorkingSetPrewarmer {
    private const val TAG = "RawStillPrewarm"

    private data class Request(
        val width: Int,
        val height: Int,
        val generation: Int
    ) {
        val key: String = "$generation:${width}x$height"
    }

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "BnCamRawStillWarmup").apply {
            priority = (Thread.NORM_PRIORITY - 1).coerceAtLeast(Thread.MIN_PRIORITY)
            isDaemon = true
        }
    }
    private val pending = AtomicReference<Request?>(null)
    private val drainScheduled = AtomicBoolean(false)
    private val preparedKey = AtomicReference<String?>(null)

    fun request(width: Int, height: Int, generation: Int) {
        if (width <= 0 || height <= 0 || generation < 0) return
        val request = Request(width, height, generation)
        if (preparedKey.get() == request.key) return
        pending.set(request)
        scheduleDrain()
    }

    private fun scheduleDrain() {
        if (!drainScheduled.compareAndSet(false, true)) return
        val accepted = runCatching {
            executor.execute {
                try {
                    while (true) {
                        val request = pending.getAndSet(null) ?: break
                        if (preparedKey.get() == request.key) continue

                        val startedNs = System.nanoTime()
                        val prepared = VulkanRuntimeOwner.prepareRawSingleFrameWorkingSet(
                            request.width,
                            request.height
                        )
                        val elapsedMs = (System.nanoTime() - startedNs) / 1_000_000.0
                        if (prepared) preparedKey.set(request.key)
                        Log.i(
                            TAG,
                            "RAW_STILL_WORKING_SET_PREPARE generation=${request.generation} " +
                                "size=${request.width}x${request.height} prepared=$prepared " +
                                "elapsedMs=${"%.3f".format(elapsedMs)}"
                        )
                    }
                } finally {
                    drainScheduled.set(false)
                    if (pending.get() != null) scheduleDrain()
                }
            }
        }.isSuccess
        if (!accepted) drainScheduled.set(false)
    }
}
