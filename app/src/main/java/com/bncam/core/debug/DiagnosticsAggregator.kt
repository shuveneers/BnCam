package com.bncam.core.debug

import android.content.Context
import com.bncam.core.tracing.ArchitectureCaptureTrace
import com.bncam.core.tracing.CaptureTraceSection
import com.bncam.core.vulkan.VulkanDiagnosticExport
import java.io.File
import java.util.ArrayDeque
import java.util.Locale

/**
 * Central in-memory diagnostics bus.
 *
 * Subsystems continue to publish telemetry through one sink, but persistent export is intentionally
 * shot-scoped and owned by [ShotLogger]. This prevents session/viewfinder telemetry from recreating
 * the old root-level debug-file jungle while still keeping a bounded recent context available to a
 * running capture.
 */
object DiagnosticsAggregator {
    enum class Stream {
        SUMMARY,
        CAMERA,
        CAPTURE,
        ISP,
        PERFORMANCE,
        PROFILE
    }

    data class Record(
        val stream: Stream,
        val scope: String,
        val section: String,
        val content: String,
        val timestampMs: Long
    )

    private const val MAX_RECENT_RECORDS = 256
    private val lock = Any()
    private val recentRecords = ArrayDeque<Record>(MAX_RECENT_RECORDS)

    @Volatile
    private var initialized = false

    @Volatile
    private var testDirectory: File? = null

    fun initialize(context: Context) {
        PublicShotDiagnosticsStorage.initialize(context.applicationContext)
        testDirectory = null
        initialized = true
    }

    internal fun initializeDirectory(directory: File) {
        directory.mkdirs()
        testDirectory = directory
        initialized = true
    }

    fun directoryPath(): String? = if (!initialized) null else
        testDirectory?.absolutePath ?: PublicShotDiagnosticsStorage.displayRoot()

    /**
     * Compatibility/debug display path only. There are no root-level stream files anymore.
     */
    fun streamPath(stream: Stream): String? {
        val root = directoryPath() ?: return null
        return "$root/<per-shot>/${suggestedPerShotFile(stream)}"
    }

    fun record(
        stream: Stream,
        scope: String,
        section: String,
        content: String,
        timestampMs: Long = System.currentTimeMillis()
    ) {
        val cleaned = cleanContent(content)
        if (cleaned.isBlank()) return
        synchronized(lock) {
            val fingerprint = "$stream|$scope|$section|$cleaned"
            val duplicate = recentRecords.lastOrNull()?.let {
                "${it.stream}|${it.scope}|${it.section}|${it.content}" == fingerprint
            } ?: false
            if (duplicate) return
            if (recentRecords.size >= MAX_RECENT_RECORDS) recentRecords.removeFirst()
            recentRecords.addLast(Record(stream, scope, section, cleaned, timestampMs))
        }
    }

    fun recordKeyValues(
        stream: Stream,
        scope: String,
        section: String,
        values: Iterable<Pair<String, Any?>>
    ) {
        record(
            stream = stream,
            scope = scope,
            section = section,
            content = values.joinToString("\n") { (key, value) -> "$key=${value ?: "none"}" }
        )
    }

    fun recordCaptureTrace(trace: ArchitectureCaptureTrace) {
        val scope = "CAPTURE #${trace.captureId}"
        trace.sections.forEach { (section, records) ->
            if (records.isEmpty()) return@forEach
            record(
                stream = streamForTraceSection(section),
                scope = scope,
                section = section.name.replace('_', ' '),
                content = records.joinToString("\n") { renderRecord(it.key, it.value, it.decision) }
            )
        }
    }

    fun recordVulkanRuntime(captureId: String, export: VulkanDiagnosticExport) {
        val scope = "CAPTURE #$captureId"
        record(Stream.ISP, scope, "VULKAN RUNTIME", export.runtimeText)
        record(Stream.ISP, scope, "VULKAN CAPABILITIES", export.capabilitiesText)
        record(Stream.PERFORMANCE, scope, "VULKAN VALIDATION", export.validationText)
    }

    fun recordNamedPayload(captureId: String, fileName: String, content: String) {
        val normalized = fileName.lowercase(Locale.US)
        val stream = when {
            "summary" in normalized -> Stream.SUMMARY
            "profile" in normalized || "recipe" in normalized -> Stream.PROFILE
            "noise" in normalized || "isp" in normalized || "vulkan" in normalized -> Stream.ISP
            "performance" in normalized || "benchmark" in normalized || "cadence" in normalized -> Stream.PERFORMANCE
            "warning" in normalized || "error" in normalized -> Stream.CAPTURE
            else -> Stream.CAPTURE
        }
        val section = fileName.substringBeforeLast('.').replace('_', ' ').uppercase(Locale.US)
        record(stream, "CAPTURE #$captureId", section, content)
    }

    fun snapshotForCapture(captureId: String): List<Record> {
        val exact = "CAPTURE #$captureId"
        return synchronized(lock) {
            recentRecords.filter { it.scope == exact || it.scope == captureId }
        }
    }

    fun recentSessionWarnings(limit: Int = 24): List<Record> = synchronized(lock) {
        recentRecords.toList().asReversed().asSequence()
            .filter { record ->
                val text = "${record.section} ${record.content}".lowercase(Locale.US)
                "warn" in text || "error" in text || "fail" in text || "fallback" in text
            }
            .take(limit.coerceIn(0, 64))
            .toList()
            .asReversed()
    }

    /**
     * Carries a short, bounded history of shutter-admission failures into the next successful
     * per-shot debug folder. A rejected shutter can occur before a runner creates its ShotLogger
     * folder, so without this bridge the exact reason disappears from the user-exported debug ZIP.
     */
    fun recentCaptureAdmissionFailures(
        limit: Int = 12,
        maxAgeMs: Long = 120_000L,
        nowMs: Long = System.currentTimeMillis()
    ): List<Record> = synchronized(lock) {
        recentRecords.toList().asReversed().asSequence()
            .filter { it.stream == Stream.CAPTURE && nowMs - it.timestampMs <= maxAgeMs }
            .filter { record ->
                val text = "${record.section} ${record.content}".lowercase(Locale.US)
                "capture_reject" in text ||
                    "capture_rejected" in text ||
                    "ready=false" in text ||
                    "capture_pipeline_not_ready" in text ||
                    "capture_orphan_finalize" in text ||
                    "capture_exception" in text ||
                    "previous_capture_running" in text
            }
            .take(limit.coerceIn(0, 32))
            .toList()
            .asReversed()
    }

    private fun cleanContent(content: String): String {
        val lines = content.lineSequence()
            .map { it.trimEnd() }
            .filterNot { line ->
                val trimmed = line.trimStart()
                trimmed.startsWith("runtimeJson=") ||
                    trimmed.startsWith("capabilitiesJson=") ||
                    trimmed.startsWith("diagnosticsJson=") ||
                    trimmed.startsWith("validationJson=")
            }
            .toList()
        return lines.joinToString("\n").trim()
    }

    private fun streamForTraceSection(section: CaptureTraceSection): Stream = when (section) {
        CaptureTraceSection.ISP_EXECUTION,
        CaptureTraceSection.ALIGNMENT,
        CaptureTraceSection.FUSION -> Stream.ISP
        CaptureTraceSection.PERFORMANCE_AND_THERMAL -> Stream.PERFORMANCE
        CaptureTraceSection.HARDWARE_AND_CAPABILITIES,
        CaptureTraceSection.BUFFER_LIFECYCLE -> Stream.CAMERA
        else -> Stream.CAPTURE
    }

    private fun suggestedPerShotFile(stream: Stream): String = when (stream) {
        Stream.SUMMARY -> "01_SUMMARY.txt"
        Stream.CAMERA, Stream.CAPTURE, Stream.PERFORMANCE -> "02_CAPTURE.txt"
        Stream.PROFILE -> "03_PROFILE_SETTINGS.txt"
        Stream.ISP -> "04_ISP.txt"
    }

    private fun renderRecord(
        key: String,
        value: String?,
        decision: com.bncam.core.tracing.CaptureTraceDecision?
    ): String {
        if (decision == null) return "$key=${value ?: "none"}"
        return "$key=requested=${decision.requested ?: "none"}; " +
            "supported=${decision.supported ?: "unknown"}; " +
            "resolved=${decision.resolved ?: "none"}; " +
            "executed=${decision.executed ?: "not_recorded"}; " +
            "result=${decision.result ?: "unknown"}; fallback=${decision.fallback}; " +
            "reason=${decision.reason ?: "none"}"
    }
}
