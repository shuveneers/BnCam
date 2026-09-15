package com.bncam.core.vulkan

import android.util.Log
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

/**
 * Moves full-resolution RAW still resource allocation away from the first shutter press.
 *
 * DELTA 0229 starts request() as soon as the Camera2 RAW pipeline identity is known. The first
 * accepted RAW frame still requests the same working set as a fallback/retry, but the prepared
 * identity is intentionally dimension-based rather than pipeline-generation-based: these Vulkan
 * buffers are process-resident capacity resources and do not contain sensor/frame-generation data.
 */
object RawStillWorkingSetPrewarmer {
    private const val TAG = "RawStillPrewarm"

    private data class Request(
        val width: Int,
        val height: Int,
        val generation: Int
    ) {
        // Working-set allocation depends on dimensions only. A generation change with the same
        // RAW extent must not repeat multi-second full-resolution allocations or race a shutter.
        val key: String = "${width}x$height"
    }

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "BnCamRawStillWarmup").apply {
            priority = (Thread.NORM_PRIORITY - 1).coerceAtLeast(Thread.MIN_PRIORITY)
            isDaemon = true
        }
    }
    private val pending = AtomicReference<Request?>(null)
    private val drainScheduled = AtomicBoolean(false)
    private val preparedKeys = ConcurrentHashMap.newKeySet<String>()

    fun request(width: Int, height: Int, generation: Int) {
        if (width <= 0 || height <= 0 || generation < 0) return
        val request = Request(width, height, generation)
        if (preparedKeys.contains(request.key)) return
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
                        if (preparedKeys.contains(request.key)) continue

                        val startedNs = System.nanoTime()
                        val prepared = VulkanRuntimeOwner.prepareRawSingleFrameWorkingSet(
                            request.width,
                            request.height
                        )
                        val elapsedMs = (System.nanoTime() - startedNs) / 1_000_000.0
                        if (prepared) preparedKeys.add(request.key)
                        Log.i(
                            TAG,
                            "RAW_STILL_WORKING_SET_PREPARE generation=${request.generation} " +
                                "size=${request.width}x${request.height} key=${request.key} " +
                                "prepared=$prepared elapsedMs=${"%.3f".format(elapsedMs)}"
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
