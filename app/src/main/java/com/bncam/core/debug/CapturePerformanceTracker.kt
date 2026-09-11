package com.bncam.core.debug

import android.content.Context
import android.os.Debug
import android.os.SystemClock
import android.util.Log
import org.json.JSONObject
import java.util.LinkedHashMap
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

data class CaptureMemorySnapshot(
    val stage: String,
    val elapsedMs: Double,
    val javaHeapBytes: Long,
    val nativeHeapBytes: Long
) {
    val combinedBytes: Long get() = javaHeapBytes + nativeHeapBytes
}

/** Immutable, overlap-safe timing view for capture debug exports. */
data class CapturePerformanceTraceSnapshot(
    val captureId: Long,
    val route: String,
    val elapsedMs: Double,
    val sequentialStageDurationsMs: Map<String, Double>,
    val explicitNestedDurationsMs: Map<String, Double>,
    val sequentialAccountedMs: Double,
    val sequentialUnattributedMs: Double,
    val counters: Map<String, Int>,
    val metrics: Map<String, String>
) {
    fun toTraceMap(): Map<String, Any> = linkedMapOf(
        "captureId" to captureId,
        "route" to route,
        "elapsedMs" to elapsedMs,
        "sequentialStageDurationsMs" to sequentialStageDurationsMs,
        "explicitNestedDurationsMs" to explicitNestedDurationsMs,
        "sequentialAccountedMs" to sequentialAccountedMs,
        "sequentialUnattributedMs" to sequentialUnattributedMs,
        "counters" to counters,
        "metrics" to metrics,
        "reconciliationContract" to "SEQUENTIAL_STAGES_ONLY; EXPLICIT_DURATIONS_MAY_OVERLAP"
    )
}

/**
 * Capture-scoped monotonic telemetry used by the Phase 0 performance work.
 *
 * The tracker deliberately never forces GC and never depends on logcat. In addition to the existing
 * log/debug pairs it can persist one JSON-lines record per capture into app-private storage at:
 *
 *     files/phase0/capture_performance.jsonl
 *
 * This makes physical-device verification possible on devices where logcat is unavailable or
 * encrypted. The report is bounded by a simple rotation policy.
 */
class CapturePerformanceTracker(private val route: String) {
    val captureId: Long = NEXT_CAPTURE_ID.incrementAndGet()

    private val startedNs = SystemClock.elapsedRealtimeNanos()
    private val runtime = Runtime.getRuntime()
    private val snapshots = ArrayList<CaptureMemorySnapshot>(12)
    private val stageDurationsMs = LinkedHashMap<String, Double>()
    private val sequentialStageDurationsMs = LinkedHashMap<String, Double>()
    private val explicitNestedDurationsMs = LinkedHashMap<String, Double>()
    private val counters = LinkedHashMap<String, Int>()
    private val metrics = LinkedHashMap<String, String>()
    private var previousMarkNs = startedNs
    private val terminalReportPersisted = AtomicBoolean(false)

    init {
        sample("start")
    }

    /** Records a sequential stage boundary and returns time since the preceding boundary. */
    @Synchronized
    fun mark(stage: String): Double {
        val nowNs = SystemClock.elapsedRealtimeNanos()
        val durationMs = (nowNs - previousMarkNs) / 1_000_000.0
        previousMarkNs = nowNs
        stageDurationsMs[stage] = durationMs
        sequentialStageDurationsMs[stage] =
            (sequentialStageDurationsMs[stage] ?: 0.0) + durationMs
        sample(stage, nowNs)
        return durationMs
    }

    /** Records an explicitly measured duration without changing the sequential mark clock. */
    @Synchronized
    fun recordDuration(stage: String, durationMs: Double) {
        val safeDurationMs = durationMs.coerceAtLeast(0.0)
        val accumulatedDurationMs = (explicitNestedDurationsMs[stage] ?: 0.0) + safeDurationMs
        stageDurationsMs[stage] = accumulatedDurationMs
        explicitNestedDurationsMs[stage] = accumulatedDurationMs
        sample(stage)
    }

    @Synchronized
    fun incrementCounter(name: String, amount: Int = 1): Int {
        require(amount >= 0) { "Counter increments must be non-negative." }
        val updated = (counters[name] ?: 0) + amount
        counters[name] = updated
        return updated
    }

    @Synchronized
    fun counter(name: String): Int = counters[name] ?: 0

    @Synchronized
    fun setMetric(name: String, value: Any?) {
        metrics[name] = value?.toString() ?: "null"
    }

    /**
     * Capture-scoped system envelope telemetry only. These observations never alter resolution,
     * frame count, ISP selection or any other quality/runtime decision.
     */
    fun sampleSystemState(context: Context, stage: String) {
        val safeStage = stage.replace(Regex("[^A-Za-z0-9_]+"), "_").trim('_').ifBlank { "unknown" }
        val thermalStatus = runCatching {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                powerManager.currentThermalStatus.toString()
            } else {
                "API_BELOW_29"
            }
        }.getOrDefault("UNAVAILABLE")
        val thermalHeadroom = runCatching {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                val powerManager = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
                powerManager.getThermalHeadroom(0).takeIf { it.isFinite() }?.toString() ?: "UNAVAILABLE"
            } else {
                "API_BELOW_30"
            }
        }.getOrDefault("UNAVAILABLE")
        val memoryInfo = runCatching {
            val activityManager =
                context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            android.app.ActivityManager.MemoryInfo().also { activityManager.getMemoryInfo(it) }
        }.getOrNull()

        setMetric("systemEnvelope.$safeStage.thermalStatus", thermalStatus)
        setMetric("systemEnvelope.$safeStage.thermalHeadroom", thermalHeadroom)
        setMetric("systemEnvelope.$safeStage.systemAvailableMemoryBytes", memoryInfo?.availMem ?: "UNAVAILABLE")
        setMetric("systemEnvelope.$safeStage.systemLowMemory", memoryInfo?.lowMemory ?: "UNAVAILABLE")
        setMetric("systemEnvelope.$safeStage.javaHeapBytes", runtime.totalMemory() - runtime.freeMemory())
        setMetric("systemEnvelope.$safeStage.nativeHeapBytes", Debug.getNativeHeapAllocatedSize())
        setMetric("systemEnvelope.qualityAdaptationApplied", false)
    }

    @Synchronized
    fun elapsedMs(): Double = (SystemClock.elapsedRealtimeNanos() - startedNs) / 1_000_000.0

    @Synchronized
    fun debugPairs(): List<Pair<String, String>> {
        val baseline = snapshots.first()
        val peak = snapshots.maxByOrNull { it.combinedBytes } ?: baseline
        val latest = snapshots.last()
        return buildList {
            add("captureId" to captureId.toString())
            add("route" to route)
            add("elapsedMs" to format(elapsedMs()))
            add("javaHeapStartBytes" to baseline.javaHeapBytes.toString())
            add("nativeHeapStartBytes" to baseline.nativeHeapBytes.toString())
            add("javaHeapPeakBytes" to (snapshots.maxOfOrNull { it.javaHeapBytes } ?: baseline.javaHeapBytes).toString())
            add("nativeHeapPeakBytes" to (snapshots.maxOfOrNull { it.nativeHeapBytes } ?: baseline.nativeHeapBytes).toString())
            add("combinedHeapPeakBytes" to peak.combinedBytes.toString())
            add("combinedHeapPeakDeltaBytes" to (peak.combinedBytes - baseline.combinedBytes).coerceAtLeast(0L).toString())
            add("combinedHeapEndDeltaBytes" to (latest.combinedBytes - baseline.combinedBytes).toString())
            add("peakStage" to peak.stage)
            add("stageDurationsMs" to stageDurationsMs.entries.joinToString(",") { "${it.key}:${format(it.value)}" })
            add("sequentialStageDurationsMs" to sequentialStageDurationsMs.entries.joinToString(",") { "${it.key}:${format(it.value)}" })
            add("explicitNestedDurationsMs" to explicitNestedDurationsMs.entries.joinToString(",") { "${it.key}:${format(it.value)}" })
            val sequentialAccountedMs = sequentialStageDurationsMs.values.sum()
            add("sequentialAccountedMs" to format(sequentialAccountedMs))
            add("sequentialUnattributedMs" to format((elapsedMs() - sequentialAccountedMs).coerceAtLeast(0.0)))
            add("invocationCounters" to counters.entries.joinToString(",") { "${it.key}:${it.value}" })
            add("metrics" to metrics.entries.joinToString(",") { "${it.key}:${it.value}" })
            add("memorySampleCount" to snapshots.size.toString())
            add("forcedGcUsed" to "false")
        }
    }

    @Synchronized
    fun traceSnapshot(): CapturePerformanceTraceSnapshot {
        val elapsed = elapsedMs()
        val accounted = sequentialStageDurationsMs.values.sum()
        return CapturePerformanceTraceSnapshot(
            captureId = captureId,
            route = route,
            elapsedMs = elapsed,
            sequentialStageDurationsMs = LinkedHashMap(sequentialStageDurationsMs),
            explicitNestedDurationsMs = LinkedHashMap(explicitNestedDurationsMs),
            sequentialAccountedMs = accounted,
            sequentialUnattributedMs = (elapsed - accounted).coerceAtLeast(0.0),
            counters = LinkedHashMap(counters),
            metrics = LinkedHashMap(metrics)
        )
    }

    /**
     * Routes a single terminal report. This method is safe to call from the processing/save worker.
     * Diagnostics remain in-memory here; durable JSONL persistence is queued to the dedicated
     * Phase-0 writer so this method never adds filesystem I/O to the caller.
     */
    fun persistJsonLine(
        context: Context,
        status: String,
        failureReason: String? = null
    ) {
        if (!terminalReportPersisted.compareAndSet(false, true)) return
        sampleSystemState(context, "terminal")
        val report = synchronized(this) {
            val baseline = snapshots.first()
            val peak = snapshots.maxByOrNull { it.combinedBytes } ?: baseline
            val latest = snapshots.last()
            JSONObject().apply {
                put("schemaVersion", 2)
                put("captureId", captureId)
                put("route", route)
                put("status", status)
                put("failureReason", failureReason ?: JSONObject.NULL)
                put("startedElapsedRealtimeNs", startedNs)
                put("completedElapsedRealtimeNs", SystemClock.elapsedRealtimeNanos())
                put("elapsedMs", elapsedMs())
                put("javaHeapStartBytes", baseline.javaHeapBytes)
                put("nativeHeapStartBytes", baseline.nativeHeapBytes)
                put("javaHeapPeakBytes", snapshots.maxOfOrNull { it.javaHeapBytes } ?: baseline.javaHeapBytes)
                put("nativeHeapPeakBytes", snapshots.maxOfOrNull { it.nativeHeapBytes } ?: baseline.nativeHeapBytes)
                put("combinedHeapPeakBytes", peak.combinedBytes)
                put("combinedHeapPeakDeltaBytes", (peak.combinedBytes - baseline.combinedBytes).coerceAtLeast(0L))
                put("combinedHeapEndDeltaBytes", latest.combinedBytes - baseline.combinedBytes)
                put("peakStage", peak.stage)
                put("forcedGcUsed", false)
                put("stageDurationsMs", JSONObject().apply {
                    stageDurationsMs.forEach { (name, value) -> put(name, value) }
                })
                put("sequentialStageDurationsMs", JSONObject().apply {
                    sequentialStageDurationsMs.forEach { (name, value) -> put(name, value) }
                })
                put("explicitNestedDurationsMs", JSONObject().apply {
                    explicitNestedDurationsMs.forEach { (name, value) -> put(name, value) }
                })
                val sequentialAccounted = sequentialStageDurationsMs.values.sum()
                put("sequentialAccountedMs", sequentialAccounted)
                put("sequentialUnattributedMs", (elapsedMs() - sequentialAccounted).coerceAtLeast(0.0))
                put("invocationCounters", JSONObject().apply {
                    counters.forEach { (name, value) -> put(name, value) }
                })
                put("metrics", JSONObject().apply {
                    metrics.forEach { (name, value) -> put(name, value) }
                })
            }.toString()
        }

        try {
            DiagnosticsAggregator.initialize(context.applicationContext)
            DiagnosticsAggregator.record(
                stream = DiagnosticsAggregator.Stream.PERFORMANCE,
                scope = "CAPTURE #$captureId",
                section = "CAPTURE PERFORMANCE",
                content = report
            )
            Phase0PerformanceTrace.enqueueCaptureReport(context.applicationContext, report)
            Log.i(
                "BnCamPerformance",
                "capture_report_routed captureId=$captureId route=$route status=$status"
            )
        } catch (failure: Throwable) {
            Log.e(
                "BnCamPerformance",
                "Unable to route capture report captureId=$captureId route=$route: ${failure.message}",
                failure
            )
        }
    }

    private fun sample(stage: String, nowNs: Long = SystemClock.elapsedRealtimeNanos()) {
        snapshots += CaptureMemorySnapshot(
            stage = stage,
            elapsedMs = (nowNs - startedNs) / 1_000_000.0,
            javaHeapBytes = runtime.totalMemory() - runtime.freeMemory(),
            nativeHeapBytes = Debug.getNativeHeapAllocatedSize()
        )
    }

    private fun format(value: Double): String = String.format(Locale.US, "%.3f", value)

    companion object {
        private val NEXT_CAPTURE_ID = AtomicLong(0L)
    }
}
