package com.bncam.core.debug

import android.content.Context
import android.os.Process
import android.os.SystemClock
import org.json.JSONObject
import java.io.File
import java.util.LinkedHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * Phase-0-only monotonic observability for startup, lens handover and shutter-thumbnail latency.
 *
 * Recording is memory-only on critical paths. Completed traces are routed to DiagnosticsAggregator
 * from a single background executor so measurement never adds filesystem I/O to startup, Camera2
 * transition, GL draw or shutter admission paths.
 */
object Phase0PerformanceTrace {
    private const val PREFS_NAME = "phase0_performance_trace"
    private const val PREF_INSTALL_SEEN = "install_seen"
    private const val EVIDENCE_DIR = "phase0"
    private const val RUNTIME_EVIDENCE_FILE = "runtime_performance.jsonl"
    private const val CAPTURE_EVIDENCE_FILE = "capture_performance.jsonl"
    private const val MAX_EVIDENCE_BYTES = 2L * 1024L * 1024L

    private val lock = Any()
    private val writer = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "BnCamPhase0TraceWriter").apply { isDaemon = true }
    }
    private val nextLensSwitchId = AtomicLong(0L)
    private val nextPreviewToken = AtomicLong(0L)

    @Volatile private var appContext: Context? = null
    private val startupEvents = LinkedHashMap<String, Long>()
    private val startupDetails = LinkedHashMap<String, String>()
    private var startupReported = false
    private var startupVisible = false
    private var sawPauseAfterStartup = false
    private var warmResume: TimedTrace? = null
    private var lensTrace: LensTrace? = null
    private val thumbnailTraces = LinkedHashMap<Long, ThumbnailTrace>()
    private val previewTokens = LinkedHashMap<Long, PreviewTokenTrace>()
    private val previewTokenByPath = LinkedHashMap<String, Long>()

    private data class TimedTrace(
        val name: String,
        val events: LinkedHashMap<String, Long> = LinkedHashMap(),
        val details: LinkedHashMap<String, String> = LinkedHashMap()
    )

    private data class LensTrace(
        val id: Long,
        val fromLensId: String,
        val targetLensId: String,
        val events: LinkedHashMap<String, Long> = LinkedHashMap(),
        val details: LinkedHashMap<String, String> = LinkedHashMap(),
        var reported: Boolean = false
    )

    private data class ThumbnailTrace(
        val workId: Long,
        val route: String,
        val captureStartedNs: Long,
        val events: LinkedHashMap<String, Long> = linkedMapOf("shutter" to captureStartedNs),
        val details: LinkedHashMap<String, String> = LinkedHashMap(),
        var reported: Boolean = false
    )

    private data class PreviewTokenTrace(
        val token: Long,
        var frameFrozenNs: Long? = null,
        var persistStartedNs: Long? = null,
        var persistDoneNs: Long? = null,
        var path: String? = null
    )

    fun applicationOnCreateStarted(context: Context, onCreateStartNs: Long) {
        appContext = context.applicationContext
        synchronized(lock) {
            startupEvents.putIfAbsent(
                "process_start",
                Process.getStartElapsedRealtime().coerceAtLeast(0L) * 1_000_000L
            )
            startupEvents.putIfAbsent("application_onCreate_start", onCreateStartNs)
            startupDetails["clock"] = "SystemClock.elapsedRealtimeNanos"
        }
    }

    fun markStartup(event: String, timestampNs: Long = SystemClock.elapsedRealtimeNanos(), detail: String? = null) {
        synchronized(lock) {
            if (startupReported) return
            startupEvents.putIfAbsent(event, timestampNs)
            if (detail != null) startupDetails[event] = detail
        }
    }

    fun activityOnCreate(timestampNs: Long) = markStartup("activity_onCreate", timestampNs)

    fun activityOnResume(timestampNs: Long = SystemClock.elapsedRealtimeNanos()) {
        synchronized(lock) {
            if (startupVisible && sawPauseAfterStartup && warmResume == null) {
                warmResume = TimedTrace("WARM_RESUME").apply {
                    events["activity_resume"] = timestampNs
                }
                sawPauseAfterStartup = false
            }
        }
    }

    fun activityOnPause(timestampNs: Long = SystemClock.elapsedRealtimeNanos()) {
        synchronized(lock) {
            if (startupVisible) sawPauseAfterStartup = true
            warmResume?.events?.putIfAbsent("activity_pause", timestampNs)
        }
    }

    fun cameraScreenComposed(timestampNs: Long = SystemClock.elapsedRealtimeNanos()) {
        markStartup("camera_screen_composed", timestampNs)
    }

    fun beginLensSwitch(
        fromLensId: String,
        targetLensId: String,
        timestampNs: Long = SystemClock.elapsedRealtimeNanos()
    ): Long {
        val id = nextLensSwitchId.incrementAndGet()
        synchronized(lock) {
            lensTrace = LensTrace(id, fromLensId, targetLensId).apply {
                events["lens_ui_request"] = timestampNs
                details["transitionType"] = "RESOLVE_FROM_CAMERA2_DIAGNOSTICS"
            }
        }
        return id
    }

    fun lensRouteResolutionDone(
        targetLensId: String,
        timestampNs: Long = SystemClock.elapsedRealtimeNanos(),
        detail: String? = null
    ) {
        synchronized(lock) {
            lensTrace?.takeIf { it.targetLensId == targetLensId }?.let { trace ->
                trace.events.putIfAbsent("route_resolution_done", timestampNs)
                if (detail != null) trace.details["routeResolution"] = detail
            }
        }
    }

    fun lensTransitionStarted(targetLensId: String, timestampNs: Long = SystemClock.elapsedRealtimeNanos()) {
        synchronized(lock) {
            lensTrace?.takeIf { it.targetLensId == targetLensId }
                ?.events?.putIfAbsent("transition_started", timestampNs)
        }
    }

    fun lensTransitionMilestone(
        targetLensId: String,
        event: String,
        timestampNs: Long = SystemClock.elapsedRealtimeNanos(),
        detail: String? = null
    ) {
        synchronized(lock) {
            lensTrace?.takeIf { it.targetLensId == targetLensId }?.let { trace ->
                trace.events.putIfAbsent(event, timestampNs)
                if (detail != null) trace.details.putIfAbsent(event, detail)
            }
        }
    }

    fun lensSwitchCancelled(targetLensId: String, reason: String) {
        val report = synchronized(lock) {
            val trace = lensTrace?.takeIf { it.targetLensId == targetLensId } ?: return
            trace.details["cancelled"] = reason
            lensTrace = null
            traceToJson("LENS_SWITCH", trace.id, trace.events, trace.details + mapOf(
                "fromLensId" to trace.fromLensId,
                "targetLensId" to trace.targetLensId
            ))
        }
        persist("PHASE 0 LENS SWITCH TIMELINE", report)
    }

    /** First camera frame that reached the renderer for the currently displayed producer. */
    fun cameraFrameReceived(
        lensId: String,
        source: String,
        generation: Int,
        sensorTimestampNs: Long,
        timestampNs: Long = SystemClock.elapsedRealtimeNanos()
    ) {
        synchronized(lock) {
            if (!startupReported) {
                startupEvents.putIfAbsent("first_camera_frame_received", timestampNs)
                startupDetails.putIfAbsent("firstCameraFrameSource", "$source/$lensId/generation=$generation/sensorTs=$sensorTimestampNs")
            }
            warmResume?.events?.putIfAbsent("first_camera_frame_received", timestampNs)
        }
    }

    /**
     * Records a target-sensor frame only after existing viewfinder authority logic accepted that
     * source/generation for display. This prevents a retained pre-switch frame from satisfying the
     * lens-switch trace merely because the UI lens id already changed.
     */
    fun targetSensorFrameReceived(
        lensId: String,
        source: String,
        generation: Int,
        sensorTimestampNs: Long,
        timestampNs: Long = SystemClock.elapsedRealtimeNanos()
    ) {
        synchronized(lock) {
            lensTrace?.takeIf { it.targetLensId == lensId }?.let { trace ->
                trace.events.putIfAbsent("first_target_sensor_frame_received", timestampNs)
                trace.details.putIfAbsent(
                    "firstTargetSource",
                    "$source/generation=$generation/sensorTs=$sensorTimestampNs"
                )
            }
        }
    }

    fun viewfinderFrameCommitted(
        lensId: String,
        source: String,
        generation: Int,
        sensorTimestampNs: Long,
        targetAuthorityAccepted: Boolean = false,
        timestampNs: Long = SystemClock.elapsedRealtimeNanos()
    ) {
        synchronized(lock) {
            if (!startupReported) {
                startupEvents.putIfAbsent("first_viewfinder_frame_committed", timestampNs)
                startupDetails.putIfAbsent("firstCommittedFrame", "$source/$lensId/generation=$generation/sensorTs=$sensorTimestampNs")
            }
            if (targetAuthorityAccepted) {
                lensTrace?.takeIf { it.targetLensId == lensId }?.events
                    ?.putIfAbsent("first_target_viewfinder_frame_committed", timestampNs)
            }
            warmResume?.events?.putIfAbsent("first_viewfinder_frame_committed", timestampNs)
        }
    }

    fun viewfinderFramePresented(
        lensId: String,
        source: String,
        generation: Int,
        sensorTimestampNs: Long,
        presentationTimestampNs: Long,
        presentationSignal: String,
        targetAuthorityAccepted: Boolean = false
    ) {
        var startupNeedsReport = false
        var lensReport: String? = null
        var warmReport: String? = null
        synchronized(lock) {
            if (!startupReported) {
                startupEvents.putIfAbsent("first_viewfinder_frame_presented", presentationTimestampNs)
                startupDetails["presentationSignal"] = presentationSignal
                startupDetails["firstPresentedFrame"] = "$source/$lensId/generation=$generation/sensorTs=$sensorTimestampNs"
                startupReported = true
                startupVisible = true
                startupNeedsReport = true
            }

            if (targetAuthorityAccepted) {
                lensTrace?.takeIf { it.targetLensId == lensId && !it.reported }?.let { trace ->
                    trace.events.putIfAbsent("first_target_viewfinder_frame_presented", presentationTimestampNs)
                    trace.details["presentationSignal"] = presentationSignal
                    trace.reported = true
                    lensReport = traceToJson(
                        type = "LENS_SWITCH",
                        id = trace.id,
                        events = trace.events,
                        details = trace.details + mapOf(
                            "fromLensId" to trace.fromLensId,
                            "targetLensId" to trace.targetLensId
                        )
                    )
                    lensTrace = null
                }
            }

            warmResume?.let { trace ->
                trace.events.putIfAbsent("first_viewfinder_frame_presented", presentationTimestampNs)
                trace.details["presentationSignal"] = presentationSignal
                warmReport = traceToJson("WARM_RESUME", 0L, trace.events, trace.details)
                warmResume = null
            }
        }
        if (startupNeedsReport) scheduleStartupReport()
        lensReport?.let { persist("PHASE 0 LENS SWITCH TIMELINE", it) }
        warmReport?.let { persist("PHASE 0 WARM RESUME TIMELINE", it) }
    }

    fun thumbnailShutter(workId: Long, route: String, captureStartedNs: Long) {
        if (captureStartedNs <= 0L) return
        synchronized(lock) {
            thumbnailTraces.putIfAbsent(workId, ThumbnailTrace(workId, route, captureStartedNs))
        }
    }

    fun beginPreviewSnapshot(): Long {
        val token = nextPreviewToken.incrementAndGet()
        synchronized(lock) {
            previewTokens[token] = PreviewTokenTrace(token)
            while (previewTokens.size > 16) {
                val stale = previewTokens.entries.firstOrNull() ?: break
                stale.value.path?.let(previewTokenByPath::remove)
                previewTokens.remove(stale.key)
            }
        }
        return token
    }

    fun previewFrameFrozen(token: Long, timestampNs: Long = SystemClock.elapsedRealtimeNanos()) {
        synchronized(lock) { previewTokens[token]?.frameFrozenNs = timestampNs }
    }

    fun previewPersistStarted(token: Long, path: String, timestampNs: Long = SystemClock.elapsedRealtimeNanos()) {
        synchronized(lock) {
            previewTokens[token]?.let {
                it.persistStartedNs = timestampNs
                it.path = path
                previewTokenByPath[path] = token
            }
        }
    }

    fun previewPersistDone(token: Long, timestampNs: Long = SystemClock.elapsedRealtimeNanos()) {
        synchronized(lock) { previewTokens[token]?.persistDoneNs = timestampNs }
    }

    fun bindTemporaryPreview(workId: Long, path: String) {
        synchronized(lock) {
            val trace = thumbnailTraces[workId] ?: return
            val token = previewTokenByPath[path] ?: return
            val preview = previewTokens[token] ?: return
            preview.frameFrozenNs?.let { trace.events.putIfAbsent("viewfinder_frame_frozen", it) }
            preview.persistStartedNs?.let { trace.events.putIfAbsent("thumbnail_optional_persist_started", it) }
            preview.persistDoneNs?.let { trace.events.putIfAbsent("thumbnail_optional_persist_done", it) }
            trace.details["legacyPreviewPathUsed"] = "true"
            previewTokens.remove(token)
            previewTokenByPath.remove(path)
        }
    }

    fun thumbnailUiPresented(
        workId: Long,
        modelKind: String,
        timestampNs: Long = SystemClock.elapsedRealtimeNanos()
    ) {
        val report = synchronized(lock) {
            val trace = thumbnailTraces[workId] ?: return
            if (trace.reported) return
            trace.events.putIfAbsent("thumbnail_ui_presented", timestampNs)
            trace.details["modelKind"] = modelKind
            trace.reported = true
            val json = traceToJson("THUMBNAIL", workId, trace.events, trace.details + mapOf("route" to trace.route))
            thumbnailTraces.remove(workId)
            json
        }
        persist("PHASE 0 THUMBNAIL TIMELINE", report)
    }

    private fun scheduleStartupReport() {
        val (eventsCopy, detailsCopy, context) = synchronized(lock) {
            Triple(LinkedHashMap(startupEvents), LinkedHashMap(startupDetails), appContext)
        }
        writer.execute {
            val firstInstall = context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                ?.getBoolean(PREF_INSTALL_SEEN, false) != true
            if (firstInstall && context != null) {
                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit().putBoolean(PREF_INSTALL_SEEN, true).apply()
            }
            detailsCopy["startupClass"] = if (firstInstall) "FIRST_INSTALL_COLD" else "PROCESS_COLD"
            persistDirect("PHASE 0 STARTUP TIMELINE", traceToJson("STARTUP", 0L, eventsCopy, detailsCopy))
        }
    }

    private fun traceToJson(
        type: String,
        id: Long,
        events: Map<String, Long>,
        details: Map<String, String>
    ): String {
        val ordered = events.toList().sortedBy { it.second }
        val first = ordered.firstOrNull()?.second
        val last = ordered.lastOrNull()?.second
        return JSONObject().apply {
            put("schemaVersion", 1)
            put("traceType", type)
            put("traceId", id)
            put("clock", "SystemClock.elapsedRealtimeNanos")
            put("events", JSONObject().apply { ordered.forEach { (name, ts) -> put(name, ts) } })
            put("durationsMsFromFirst", JSONObject().apply {
                if (first != null) ordered.forEach { (name, ts) -> put(name, (ts - first) / 1_000_000.0) }
            })
            put("totalMs", if (first != null && last != null) (last - first) / 1_000_000.0 else JSONObject.NULL)
            put("details", JSONObject().apply { details.forEach { (key, value) -> put(key, value) } })
        }.toString()
    }

    private fun persist(section: String, report: String) {
        if (report.isBlank()) return
        writer.execute { persistDirect(section, report) }
    }

    /**
     * Capture reports are enqueued from processing/publication workers and persisted only by the
     * dedicated Phase-0 writer. The caller never performs filesystem I/O.
     */
    fun enqueueCaptureReport(context: Context, report: String) {
        if (report.isBlank()) return
        if (appContext == null) appContext = context.applicationContext
        writer.execute { appendEvidenceLine(CAPTURE_EVIDENCE_FILE, report) }
    }

    private fun persistDirect(section: String, report: String) {
        val context = appContext ?: return
        runCatching {
            DiagnosticsAggregator.initialize(context)
            DiagnosticsAggregator.record(
                stream = DiagnosticsAggregator.Stream.PERFORMANCE,
                scope = "PHASE 0",
                section = section,
                content = report
            )
        }
        appendEvidenceLine(RUNTIME_EVIDENCE_FILE, report)
    }

    /** Called exclusively on [writer]. */
    private fun appendEvidenceLine(fileName: String, report: String) {
        val context = appContext ?: return
        runCatching {
            val directory = File(context.filesDir, EVIDENCE_DIR)
            if (!directory.exists() && !directory.mkdirs() && !directory.isDirectory) return@runCatching
            val file = File(directory, fileName)
            if (file.exists() && file.length() >= MAX_EVIDENCE_BYTES) {
                val previous = File(directory, "$fileName.1")
                if (previous.exists()) previous.delete()
                file.renameTo(previous)
            }
            file.appendText(report + "\n", Charsets.UTF_8)
        }
    }
}
