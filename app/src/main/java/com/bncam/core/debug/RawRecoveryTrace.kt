package com.bncam.core.debug

import android.content.Context
import android.os.SystemClock
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Recovery trace for camera/RAW lifecycle diagnostics.
 *
 * Camera producer callbacks never perform filesystem I/O. Events are kept behind a bounded queue,
 * coalesced into batches and then handed to [DiagnosticsAggregator], which is the only export sink.
 */
object RawRecoveryTrace {
    private const val MAX_PENDING_LINES = 1_024
    private const val MAX_DRAIN_BATCH_LINES = 256
    private const val MAX_RECENT_LINES = 1_024L

    private val pendingLines = ConcurrentLinkedQueue<String>()
    private val pendingPermits = Semaphore(MAX_PENDING_LINES)
    private val droppedLineCount = AtomicLong(0L)
    private val drainScheduled = AtomicBoolean(false)
    private val initialized = AtomicBoolean(false)
    private val recentLines = ConcurrentLinkedQueue<String>()
    private val recentLineCount = AtomicLong(0L)
    private val ioExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "RawRecoveryTraceIO").apply { isDaemon = true }
    }

    fun init(context: Context) {
        DiagnosticsAggregator.initialize(context.applicationContext)
        if (initialized.compareAndSet(false, true)) {
            log("TRACE_INITIALIZED", "centralStream=CAMERA.txt")
        }
    }

    fun log(event: String, details: String = "") {
        if (!initialized.get()) return
        val ts = SystemClock.elapsedRealtimeNanos()
        val thread = Thread.currentThread().name
        val line = "$ts | $thread | $event | $details\n"
        rememberRecent(line)
        if (!pendingPermits.tryAcquire()) {
            droppedLineCount.incrementAndGet()
            return
        }
        pendingLines.offer(line)
        scheduleDrain()
    }

    private fun rememberRecent(line: String) {
        recentLines.offer(line)
        val count = recentLineCount.incrementAndGet()
        if (count <= MAX_RECENT_LINES) return
        while (recentLineCount.get() > MAX_RECENT_LINES) {
            if (recentLines.poll() != null) recentLineCount.decrementAndGet() else break
        }
    }

    private fun scheduleDrain() {
        if (!drainScheduled.compareAndSet(false, true)) return
        ioExecutor.execute {
            try {
                val batch = StringBuilder()
                val dropped = droppedLineCount.getAndSet(0L)
                if (dropped > 0L) {
                    batch.append(
                        "${SystemClock.elapsedRealtimeNanos()} | RawRecoveryTraceIO | " +
                            "TRACE_LINES_DROPPED | count=$dropped reason=bounded_queue\n"
                    )
                }
                var drained = 0
                while (drained < MAX_DRAIN_BATCH_LINES) {
                    val line = pendingLines.poll() ?: break
                    pendingPermits.release()
                    batch.append(line)
                    drained++
                }
                if (batch.isNotEmpty()) {
                    DiagnosticsAggregator.record(
                        stream = DiagnosticsAggregator.Stream.CAMERA,
                        scope = "SESSION",
                        section = "RAW RECOVERY TRACE",
                        content = batch.toString()
                    )
                }
            } finally {
                drainScheduled.set(false)
                if (pendingLines.isNotEmpty()) scheduleDrain()
            }
        }
    }

    fun clearTrace() {
        ioExecutor.execute {
            while (true) {
                pendingLines.poll() ?: break
                pendingPermits.release()
            }
            droppedLineCount.set(0L)
            recentLines.clear()
            recentLineCount.set(0L)
            DiagnosticsAggregator.record(
                stream = DiagnosticsAggregator.Stream.CAMERA,
                scope = "SESSION",
                section = "RAW RECOVERY TRACE",
                content = "traceMemoryReset=true"
            )
        }
    }

    fun getTraceText(): String = recentLines.joinToString(separator = "")
}
