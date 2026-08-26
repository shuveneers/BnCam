package com.bncam.ui.screens.capture

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.SystemClock
import android.util.Log
import com.bncam.core.debug.DiagnosticsAggregator
import java.util.Locale
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.sqrt

/**
 * Debug-build cadence probe keyed by SENSOR_TIMESTAMP. It deliberately reports only unique source
 * timestamps, so offering or drawing the same RAW buffer twice can never inflate the FPS number.
 * Per-frame events stay in memory; bounded snapshots are coalesced off-thread into central diagnostics.
 */
object RawPreviewCadenceDiagnostics {
    private data class FrameRecord(
        val sensorTimestampNs: Long,
        var sourceArrivalNs: Long = 0L,
        var sensorFrameDurationNs: Long = 0L,
        var exposureTimeNs: Long = 0L,
        var requestedFpsLower: Int = 0,
        var requestedFpsUpper: Int = 0,
        var offerCount: Int = 0,
        var processStartNs: Long = 0L,
        var processCompleteNs: Long = 0L,
        var inputPackingMs: Float = 0f,
        var gpuKernelMs: Float = 0f,
        var gpuSyncOverheadMs: Float = 0f,
        var gpuHostReadbackMs: Float = 0f,
        var viewAcceptedNs: Long = 0L,
        var glUploadStartNs: Long = 0L,
        var glUploadCompleteNs: Long = 0L,
        var rgbaHandoffBytes: Long = 0L,
        var drawSubmittedNs: Long = 0L,
        var eglFrameId: Long = 0L,
        var displayPresentNs: Long = 0L
    )

    private data class Snapshot(
        val source: String,
        val generation: Int,
        val advertisedMinFrameDurationNs: Long,
        val duplicateOffersRejected: Long,
        val frames: List<FrameRecord>
    )

    private data class Distribution(
        val count: Int,
        val mean: Double,
        val standardDeviation: Double,
        val minimum: Double,
        val p10: Double,
        val p50: Double,
        val p90: Double,
        val p95: Double,
        val p99: Double,
        val maximum: Double
    )

    private val lock = Any()
    private val records = LinkedHashMap<Long, FrameRecord>()
    private val writerQueued = AtomicBoolean(false)
    private val writer = ScheduledThreadPoolExecutor(1) { runnable ->
        Thread(runnable, "BnCamRawCadenceWriter").apply { priority = Thread.MIN_PRIORITY }
    }.apply { removeOnCancelPolicy = true }

    @Volatile private var initialized = false
    @Volatile private var enabled = false
    private var activeSource = "YUV"
    private var activeGeneration = -1
    private var advertisedMinFrameDurationNs = 0L
    private var duplicateOffersRejected = 0L
    @Volatile private var latestRenderedReport: String? = null

    fun initialize(context: Context) {
        if (initialized) return
        val app = context.applicationContext
        enabled = (app.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        DiagnosticsAggregator.initialize(app)
        initialized = true
    }

    fun route(source: ViewfinderEffectiveSource, generation: Int, minFrameDurationNs: Long = 0L) {
        if (!enabled) return
        synchronized(lock) {
            if (activeSource != source.name || activeGeneration != generation) {
                activeSource = source.name
                activeGeneration = generation
                advertisedMinFrameDurationNs = minFrameDurationNs
                duplicateOffersRejected = 0L
                records.clear()
                latestRenderedReport = null
            } else if (minFrameDurationNs > 0L) {
                advertisedMinFrameDurationNs = minFrameDurationNs
            }
        }
        queueWrite()
    }

    fun sourceArrived(
        source: ViewfinderEffectiveSource,
        generation: Int,
        sensorTimestampNs: Long,
        arrivalElapsedNs: Long = SystemClock.elapsedRealtimeNanos()
    ) = update(source, generation, sensorTimestampNs) { record ->
        if (record.sourceArrivalNs == 0L) record.sourceArrivalNs = arrivalElapsedNs
    }

    fun metadataArrived(
        source: ViewfinderEffectiveSource,
        generation: Int,
        sensorTimestampNs: Long,
        sensorFrameDurationNs: Long,
        exposureTimeNs: Long,
        requestedFpsLower: Int,
        requestedFpsUpper: Int
    ) = update(source, generation, sensorTimestampNs) { record ->
        record.sensorFrameDurationNs = sensorFrameDurationNs
        record.exposureTimeNs = exposureTimeNs
        record.requestedFpsLower = requestedFpsLower
        record.requestedFpsUpper = requestedFpsUpper
    }

    fun offered(source: ViewfinderEffectiveSource, generation: Int, sensorTimestampNs: Long) =
        update(source, generation, sensorTimestampNs) { it.offerCount++ }

    fun duplicateOfferRejected(
        source: ViewfinderEffectiveSource,
        generation: Int,
        sensorTimestampNs: Long
    ) {
        synchronized(lock) { duplicateOffersRejected++ }
        update(source, generation, sensorTimestampNs) { it.offerCount++ }
    }

    fun processingStarted(source: ViewfinderEffectiveSource, generation: Int, sensorTimestampNs: Long) =
        update(source, generation, sensorTimestampNs) {
            if (it.processStartNs == 0L) it.processStartNs = SystemClock.elapsedRealtimeNanos()
        }

    fun processingCompleted(
        source: ViewfinderEffectiveSource,
        generation: Int,
        sensorTimestampNs: Long,
        inputPackingMs: Float = 0f,
        gpuKernelMs: Float = 0f,
        gpuSyncOverheadMs: Float = 0f,
        gpuHostReadbackMs: Float = 0f
    ) = update(source, generation, sensorTimestampNs) {
        if (it.processCompleteNs == 0L) it.processCompleteNs = SystemClock.elapsedRealtimeNanos()
        if (inputPackingMs.isFinite() && inputPackingMs >= 0f) it.inputPackingMs = inputPackingMs
        if (gpuKernelMs.isFinite() && gpuKernelMs >= 0f) it.gpuKernelMs = gpuKernelMs
        if (gpuSyncOverheadMs.isFinite() && gpuSyncOverheadMs >= 0f) it.gpuSyncOverheadMs = gpuSyncOverheadMs
        if (gpuHostReadbackMs.isFinite() && gpuHostReadbackMs >= 0f) it.gpuHostReadbackMs = gpuHostReadbackMs
    }

    fun viewAccepted(
        source: ViewfinderEffectiveSource,
        generation: Int,
        sensorTimestampNs: Long,
        rgbaHandoffBytes: Long = 0L
    ) = update(source, generation, sensorTimestampNs) {
        if (it.viewAcceptedNs == 0L) it.viewAcceptedNs = SystemClock.elapsedRealtimeNanos()
        if (rgbaHandoffBytes > 0L) it.rgbaHandoffBytes = rgbaHandoffBytes
    }

    fun glUploadStarted(source: ViewfinderEffectiveSource, generation: Int, sensorTimestampNs: Long) =
        update(source, generation, sensorTimestampNs) {
            it.glUploadStartNs = SystemClock.elapsedRealtimeNanos()
        }

    fun glUploadCompleted(source: ViewfinderEffectiveSource, generation: Int, sensorTimestampNs: Long) =
        update(source, generation, sensorTimestampNs) {
            it.glUploadCompleteNs = SystemClock.elapsedRealtimeNanos()
        }

    fun drawSubmitted(
        source: ViewfinderEffectiveSource,
        generation: Int,
        sensorTimestampNs: Long,
        eglFrameId: Long
    ) = update(source, generation, sensorTimestampNs) {
        it.drawSubmittedNs = SystemClock.elapsedRealtimeNanos()
        it.eglFrameId = eglFrameId
    }

    fun displayPresented(sensorTimestampNs: Long, eglFrameId: Long, displayPresentNs: Long) {
        if (!enabled || sensorTimestampNs <= 0L || displayPresentNs <= 0L) return
        // EGL_ANDROID_get_frame_timestamps uses CLOCK_MONOTONIC while Camera2 REALTIME sensor
        // timestamps use CLOCK_BOOTTIME/elapsedRealtimeNanos. Convert before computing frame age.
        val boottimePresentNs = displayPresentNs +
            (SystemClock.elapsedRealtimeNanos() - System.nanoTime())
        synchronized(lock) {
            records[sensorTimestampNs]?.let {
                if (it.eglFrameId == 0L || it.eglFrameId == eglFrameId) {
                    it.eglFrameId = eglFrameId
                    it.displayPresentNs = boottimePresentNs
                }
            }
        }
        queueWrite()
    }

    private inline fun update(
        source: ViewfinderEffectiveSource,
        generation: Int,
        sensorTimestampNs: Long,
        crossinline change: (FrameRecord) -> Unit
    ) {
        if (!enabled || sensorTimestampNs <= 0L) return
        synchronized(lock) {
            if (activeSource != source.name || activeGeneration != generation) return
            val record = records.getOrPut(sensorTimestampNs) { FrameRecord(sensorTimestampNs) }
            change(record)
            while (records.size > MAX_RECORDS) {
                records.entries.iterator().run { if (hasNext()) { next(); remove() } }
            }
        }
        queueWrite()
    }

    private fun queueWrite() {
        if (!enabled || !writerQueued.compareAndSet(false, true)) return
        writer.schedule({
            try {
                writeSnapshot()
            } finally {
                writerQueued.set(false)
            }
        }, WRITE_INTERVAL_MS, TimeUnit.MILLISECONDS)
    }

    private fun writeSnapshot() {
        if (!initialized) return
        val snapshot = synchronized(lock) {
            Snapshot(
                source = activeSource,
                generation = activeGeneration,
                advertisedMinFrameDurationNs = advertisedMinFrameDurationNs,
                duplicateOffersRejected = duplicateOffersRejected,
                frames = records.values.map { it.copy() }
            )
        }
        if (snapshot.frames.isEmpty()) return
        val report = renderReport(snapshot)
        latestRenderedReport = report
        DiagnosticsAggregator.record(
            stream = DiagnosticsAggregator.Stream.PERFORMANCE,
            scope = "VIEWFINDER source=${snapshot.source} generation=${snapshot.generation}",
            section = "RAW PREVIEW CADENCE",
            content = report
        )
        DiagnosticsAggregator.record(
            stream = DiagnosticsAggregator.Stream.PERFORMANCE,
            scope = "VIEWFINDER source=${snapshot.source} generation=${snapshot.generation}",
            section = "RAW PREVIEW FRAME CADENCE CSV",
            content = renderCsv(snapshot)
        )
        Log.i(TAG, report.lineSequence().take(4).joinToString(" "))
    }

    fun latestReport(): String? = latestRenderedReport

    private fun renderReport(snapshot: Snapshot): String {
        val sourceFrames = snapshot.frames.filter { it.sourceArrivalNs > 0L }.sortedBy { it.sensorTimestampNs }
        val processedFrames = snapshot.frames.filter { it.processCompleteNs > 0L }.sortedBy { it.processCompleteNs }
        val drawnFrames = snapshot.frames.filter { it.drawSubmittedNs > 0L }.sortedBy { it.drawSubmittedNs }
        val presentedFrames = snapshot.frames.filter { it.displayPresentNs > 0L }.sortedBy { it.displayPresentNs }
        val sourceIntervals = sourceFrames.zipWithNext { a, b -> (b.sensorTimestampNs - a.sensorTimestampNs) / 1e6 }
        val sourceArrivalIntervals = sourceFrames.sortedBy { it.sourceArrivalNs }
            .zipWithNext { a, b -> (b.sourceArrivalNs - a.sourceArrivalNs) / 1e6 }
        val frameDurations = sourceFrames.mapNotNull { it.sensorFrameDurationNs.takeIf { value -> value > 0L }?.div(1e6) }
        val exposureTimes = sourceFrames.mapNotNull { it.exposureTimeNs.takeIf { value -> value > 0L }?.div(1e6) }
        val processIntervals = processedFrames.zipWithNext { a, b -> (b.processCompleteNs - a.processCompleteNs) / 1e6 }
        val drawIntervals = drawnFrames.zipWithNext { a, b -> (b.drawSubmittedNs - a.drawSubmittedNs) / 1e6 }
        val presentIntervals = presentedFrames.zipWithNext { a, b -> (b.displayPresentNs - a.displayPresentNs) / 1e6 }
        val processTimes = processedFrames.mapNotNull { frame ->
            frame.processStartNs.takeIf { it > 0L }?.let { (frame.processCompleteNs - it) / 1e6 }
        }
        val uploadTimes = drawnFrames.mapNotNull { frame ->
            frame.glUploadStartNs.takeIf { it > 0L && frame.glUploadCompleteNs >= it }
                ?.let { (frame.glUploadCompleteNs - it) / 1e6 }
        }
        val inputPackingTimes = processedFrames.map { it.inputPackingMs.toDouble() }.filter { it >= 0.0 }
        val gpuKernelTimes = processedFrames.map { it.gpuKernelMs.toDouble() }.filter { it >= 0.0 }
        val gpuSyncOverheadTimes = processedFrames.map { it.gpuSyncOverheadMs.toDouble() }.filter { it >= 0.0 }
        val gpuHostReadbackTimes = processedFrames.map { it.gpuHostReadbackMs.toDouble() }.filter { it >= 0.0 }
        val sourceArrivalAges = sourceFrames.map { (it.sourceArrivalNs - it.sensorTimestampNs) / 1e6 }
            .filter { it >= 0.0 }
        val processCompleteAges = processedFrames.map { (it.processCompleteNs - it.sensorTimestampNs) / 1e6 }
            .filter { it >= 0.0 }
        val viewAcceptedAges = snapshot.frames.mapNotNull { frame ->
            frame.viewAcceptedNs.takeIf { it > 0L }?.let { (it - frame.sensorTimestampNs) / 1e6 }
        }.filter { it >= 0.0 }
        val drawAges = drawnFrames.map { (it.drawSubmittedNs - it.sensorTimestampNs) / 1e6 }
            .filter { it >= 0.0 }
        val presentAges = presentedFrames.map { (it.displayPresentNs - it.sensorTimestampNs) / 1e6 }
            .filter { it >= 0.0 }
        val fpsRanges = sourceFrames.map { "${it.requestedFpsLower}:${it.requestedFpsUpper}" }
            .filterNot { it == "0:0" }.distinct().joinToString()
        val presentationTimes = if (presentedFrames.size >= 2) {
            presentedFrames.map { it.displayPresentNs }
        } else {
            drawnFrames.map { it.drawSubmittedNs }
        }
        val presentationMode = if (presentedFrames.size >= 2) "EGL_DISPLAY_PRESENT_TIME_ANDROID" else "GL_DRAW_SUBMIT"
        val presentDist = distribution(if (presentIntervals.isNotEmpty()) presentIntervals else drawIntervals)
        val presentLatencyDist = distribution(presentAges)
        val presentationFrameIntervalMeanMs = presentDist?.mean ?: 0.0
        val presentationFrameIntervalP95Ms = presentDist?.p95 ?: 0.0
        val presentationFrameIntervalP99Ms = presentDist?.p99 ?: 0.0
        val sensorToPresentLatencyP50Ms = presentLatencyDist?.p50 ?: 0.0
        val sensorToPresentLatencyP95Ms = presentLatencyDist?.p95 ?: 0.0

        val droppedSource = (sourceFrames.size - processedFrames.size).coerceAtLeast(0)
        val droppedGpuBusy = snapshot.duplicateOffersRejected
        val cpuVisibleRgbaHandoffBytes = snapshot.frames.sumOf { it.rgbaHandoffBytes }
        val fullFrameGlUploadFrames = snapshot.frames.count {
            it.rgbaHandoffBytes > 0L && it.glUploadCompleteNs > 0L
        }
        val fullFrameGlUploadBytes = snapshot.frames.sumOf {
            if (it.glUploadCompleteNs > 0L) it.rgbaHandoffBytes else 0L
        }

        return buildString {
            appendLine("RAW_CADENCE source=${snapshot.source} generation=${snapshot.generation}")
            appendLine("uniqueSourceFrames=${sourceFrames.size} uniqueProcessedFrames=${processedFrames.size} uniqueDrawnFrames=${drawnFrames.size} actualPresentedFrames=${presentedFrames.size}")
            appendLine("uniqueSourceFps=${fpsFromSensorTimestamps(sourceFrames)} processingFps=${fpsFromTimes(processedFrames.map { it.processCompleteNs })} presentationFps=${fpsFromTimes(presentationTimes)} presentationClock=$presentationMode")
            appendLine("advertisedMinFrameDurationMs=${snapshot.advertisedMinFrameDurationNs / 1e6} requestedFpsRanges=$fpsRanges duplicateOffers=${snapshot.frames.sumOf { (it.offerCount - 1).coerceAtLeast(0) }} duplicateOffersRejected=${snapshot.duplicateOffersRejected}")
            appendLine("presentationFrameIntervalMeanMs=$presentationFrameIntervalMeanMs presentationFrameIntervalP95Ms=$presentationFrameIntervalP95Ms presentationFrameIntervalP99Ms=$presentationFrameIntervalP99Ms")
            appendLine("sensorToPresentLatencyP50Ms=$sensorToPresentLatencyP50Ms sensorToPresentLatencyP95Ms=$sensorToPresentLatencyP95Ms")
            appendLine("droppedSourceFrames=$droppedSource droppedGpuBusyFrames=$droppedGpuBusy repeatedPresentedFrames=0 framesInFlight=3 maxQueueDepth=1")
            appendLine(
                "cpuVisibleRgbaHandoffBytes=$cpuVisibleRgbaHandoffBytes " +
                    "glFullFrameUploadBytes=$fullFrameGlUploadBytes " +
                    "glTexImageOrSubImageFullFrameUploads=$fullFrameGlUploadFrames"
            )
            appendLine("sourceSensorIntervalsMs=${format(distribution(sourceIntervals))}")
            appendLine("sourceArrivalIntervalsMs=${format(distribution(sourceArrivalIntervals))}")
            appendLine("reportedSensorFrameDurationMs=${format(distribution(frameDurations))}")
            appendLine("exposureTimeMs=${format(distribution(exposureTimes))}")
            appendLine("processingCompletionIntervalsMs=${format(distribution(processIntervals))}")
            appendLine("presentationIntervalsMs=${format(presentDist)}")
            appendLine("rawProcessDurationMs=${format(distribution(processTimes))}")
            appendLine("rawInputPackingMs=${format(distribution(inputPackingTimes))}")
            appendLine("rawGpuKernelMs=${format(distribution(gpuKernelTimes))}")
            appendLine("rawGpuSyncOverheadMs=${format(distribution(gpuSyncOverheadTimes))}")
            appendLine("rawGpuHostReadbackMs=${format(distribution(gpuHostReadbackTimes))}")
            appendLine("glUploadDurationMs=${format(distribution(uploadTimes))}")
            appendLine("sensorToImageArrivalMs=${format(distribution(sourceArrivalAges))}")
            appendLine("sensorToVulkanCompleteMs=${format(distribution(processCompleteAges))}")
            appendLine("sensorToViewAcceptedMs=${format(distribution(viewAcceptedAges))}")
            appendLine("sensorToDrawSubmitMs=${format(distribution(drawAges))}")
            appendLine("sensorToDisplayPresentMs=${format(presentLatencyDist)}")
        }
    }

    private fun renderCsv(snapshot: Snapshot): String = buildString {
        appendLine("sensorTimestampNs,sourceArrivalNs,sensorFrameDurationNs,exposureTimeNs,fpsLower,fpsUpper,offerCount,processStartNs,processCompleteNs,inputPackingMs,gpuKernelMs,gpuSyncOverheadMs,gpuHostReadbackMs,viewAcceptedNs,glUploadStartNs,glUploadCompleteNs,rgbaHandoffBytes,drawSubmittedNs,eglFrameId,displayPresentNs")
        snapshot.frames.sortedBy { it.sensorTimestampNs }.forEach { f ->
            appendLine(listOf(
                f.sensorTimestampNs, f.sourceArrivalNs, f.sensorFrameDurationNs, f.exposureTimeNs,
                f.requestedFpsLower, f.requestedFpsUpper, f.offerCount, f.processStartNs,
                f.processCompleteNs, f.inputPackingMs, f.gpuKernelMs, f.gpuSyncOverheadMs,
                f.gpuHostReadbackMs, f.viewAcceptedNs, f.glUploadStartNs, f.glUploadCompleteNs,
                f.rgbaHandoffBytes, f.drawSubmittedNs, f.eglFrameId, f.displayPresentNs
            ).joinToString(","))
        }
    }

    private fun fpsFromSensorTimestamps(frames: List<FrameRecord>): Double =
        fpsFromTimes(frames.map { it.sensorTimestampNs })

    private fun fpsFromTimes(times: List<Long>): Double {
        if (times.size < 2) return 0.0
        val sorted = times.sorted()
        val span = sorted.last() - sorted.first()
        return if (span > 0L) (sorted.size - 1) * 1e9 / span else 0.0
    }

    private fun distribution(values: List<Double>): Distribution? {
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        val mean = sorted.average()
        val variance = sorted.sumOf { value -> (value - mean) * (value - mean) } / sorted.size
        fun percentile(fraction: Double): Double {
            val position = fraction * (sorted.size - 1)
            val lower = position.toInt()
            val upper = (lower + 1).coerceAtMost(sorted.lastIndex)
            val blend = position - lower
            return sorted[lower] * (1.0 - blend) + sorted[upper] * blend
        }
        return Distribution(
            count = sorted.size,
            mean = mean,
            standardDeviation = sqrt(variance),
            minimum = sorted.first(),
            p10 = percentile(0.10),
            p50 = percentile(0.50),
            p90 = percentile(0.90),
            p95 = percentile(0.95),
            p99 = percentile(0.99),
            maximum = sorted.last()
        )
    }

    private fun format(value: Distribution?): String {
        value ?: return "count=0"
        fun d(number: Double) = String.format(Locale.US, "%.3f", number)
        return "count=${value.count},mean=${d(value.mean)},sd=${d(value.standardDeviation)}," +
            "min=${d(value.minimum)},p10=${d(value.p10)},p50=${d(value.p50)}," +
            "p90=${d(value.p90)},p95=${d(value.p95)},p99=${d(value.p99)},max=${d(value.maximum)}"
    }

    private const val TAG = "BnCamRawCadence"
    private const val MAX_RECORDS = 360
    private const val WRITE_INTERVAL_MS = 2_000L
}
