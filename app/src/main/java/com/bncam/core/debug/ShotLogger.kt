@file:Suppress("SpellCheckingInspection")

package com.bncam.core.debug

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Arrays
import java.util.Date
import java.util.Locale
import android.hardware.camera2.TotalCaptureResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import com.bncam.ui.screens.capture.RawPreviewCadenceDiagnostics
import com.bncam.vendor.VendorInjectionAttempt
import com.bncam.vendor.VendorInjectionStatus
import org.json.JSONArray
import org.json.JSONObject

data class DiagnosticPayload(
    val shotId: String, val startedAtStr: String, val completedAtStr: String, val publicFilename: String, val savedOutputPath: String,
    val profileName: String, val cameraId: String, val targetRotation: Int, val frameWidth: Int, val frameHeight: Int, val bufferFormat: String,
    val totalShotTimeMs: Long, val cameraCaptureTimeMs: Long, val mergeTimeMs: Long,
    val framesBuffered: Int, val framesRequested: Int, val framesEligible: Int, val prunedInvalid: Int, val prunedStale: Int, val prunedDupes: Int, val prunedFirstFrame: Int, val framesAccepted: Int, val framesMerged: Int, val framesRejected: Int,
    val captureSucceeded: Boolean,
    val requestedMode: String, val resolvedRoute: String, val actualRoute: String, val routeRunner: String, val basePosition: String, val candidatesAnalyzed: Int, val includeInMerge: Boolean, val primaryBias: String, val temporalBias: Float, val frameBias: String, val acceptAllFrames: Boolean, val rejectDupes: Boolean, val alignableOnly: Boolean, val discardFirstFrame: Boolean, val preferRecent: Boolean, val ignoreStaleFrames: Boolean,
    val preMergeTriggered: Boolean, val alignmentTriggered: Boolean, val mergeTriggered: Boolean, val postMergeTriggered: Boolean, val advancedTriggered: Boolean, val fallbackUsed: Boolean, val fallbackTarget: String, val fallbackReason: String, val hardFailure: Boolean, val processingFallback: Boolean,
    val routeAnalysis: String,
    val lensName: String = "unknown", val lensFacing: String = "unknown", val physicalCameraId: String = "not reported", val sensorOrientation: Int = -1, val outputWidth: Int = 0, val outputHeight: Int = 0, val jpegCreated: Boolean = false, val jpegBytes: Int = 0, val saveLocation: String = "unknown", val gpsAdded: Boolean = false, val dngCreated: Boolean = false, val rawSensorDisabled: Boolean = true, val raw16Disabled: Boolean = true, val openGlUsed: Boolean = false, val openCvUsed: Boolean = false, val bufferCapacity: Int = 0, val bufferWarmEnough: Boolean = false, val selectedAnchorIndex: Int = -1, val selectedAnchorTimestampNs: Long = 0L, val selectedAnchorDeltaMs: Double = 0.0, val selectedAnchorTiming: String = "unknown", val selectedAnchorReason: String = "not recorded", val analysisSource: String = "unknown", val renderTimeMs: Long = 0L, val jpegCompressionTimeMs: Long = 0L, val saveTimeMs: Long = 0L, val debugWriteTimeMs: Long = 0L, val failureReason: String = "none",
    val profileId: String = "unknown", val requestedModeLabel: String = "unknown", val preferredFrameSetting: String = "unknown", val debugScope: String = "shot_folder_only",
    val mergeFrameCountSetting: Int = 0, val mergeSubPixelSetting: Boolean = false, val mergeLinearInterpolationSetting: Boolean = false, val mergeStrictnessSetting: Float = 0f, val mergeMaxShiftSetting: Int = 0,
    val framesCopiedToRam: Int = 0, val supportFramesForMerge: Int = 0, val mergeInputBytes: Long = 0L, val mergeOutputCreated: Boolean = false, val mergeOutputBytes: Int = 0, val mergedOutputPath: String = "not created",
    val anchorQuickJpegCreated: Boolean = false, val anchorQuickJpegBytes: Int = 0, val hqJpegCreated: Boolean = false, val hqJpegBytes: Int = 0, val hqSavedOutputPath: String = "not created",
    val dngBytes: Int = 0, val dngSavedOutputPath: String = "not created",
    val meteringStyle: String = "unknown",
    val evOffset: Float = 0f,
    val aeRegionsRequested: String = "none",
    val aeRegionsResult: String = "none",
    val evCompRequested: Float = 0f,
    val evCompResult: Float = 0f,
    val aeStateBeforeCapture: String = "unknown",
    val aeStateAtCapture: String = "unknown",
    val sensorExposureTime: Long = 0L,
    val sensorSensitivity: Int = 0,
    val meteringAppliedToRepeatingRequest: Boolean = false,
    val meteringAppliedToStillRequest: Boolean = false,
    val meteringRequested: Boolean = false,
    val meteringAppliedExact: Boolean = false,
    val meteringAppliedPartial: Boolean = false,
    val meteringAppliedToSelectedFrame: Boolean = false,
    val meteringAppliedStatus: String = "not_requested",
    val selectedFrameSourceRequestType: String = "unknown",
    val meteringAppliedToRepeatingRequestStatus: String = "",
    val meteringAppliedToStillRequestStatus: String = ""
)

data class FrameAnalysisDebugEntry(
    val index: Int, val timestampNs: Long, val deltaToShutterMs: Double, val shutterRelation: String, val exposureTimeNs: Long, val iso: Int, val format: String, val width: Int, val height: Int, val metadataValid: Boolean, val stale: Boolean, val duplicate: Boolean, val accepted: Boolean, val rejectionReason: String, val sharpnessScore: Double, val motionScore: Double, val evScore: Double, val alignabilityScore: Double, val syncScore: Double, val temporalBiasContribution: Double, val overallScore: Double, val finalRank: Int, val selectedAnchor: Boolean, val decisionReason: String, val analysisSource: String,
    val candidateTimestampNs: Long = timestampNs,
    val candidateDeltaMs: Double = deltaToShutterMs,
    val candidateIso: Int = iso,
    val candidateExposureNs: Long = exposureTimeNs,
    val candidateAeState: Int = -1,
    val candidateAwbState: Int = -1,
    val candidateFocusState: Int = -1,
    val candidateMetadataComplete: Boolean = false,
    val candidateAgeAbsMs: Double = kotlin.math.abs(candidateDeltaMs),
    val candidateFreshEnoughForSelection: Boolean = false,
    val candidateRejectedForStaleSelection: Boolean = false,
    val scoreComponentsUsed: String = "not_recorded",
    val pipelineGeneration: Int = -1,
    val controlRequestEpoch: Long = 0L,
    val requestProvenanceStatus: String = "UNPROVEN",
    val sensorAuthorityId: String = "UNAVAILABLE",
    val cameraDeviceId: String = "UNAVAILABLE",
    val physicalCameraId: String = "UNAVAILABLE",
    val rawSourceId: String = "UNAVAILABLE",
    val captureResultSourceId: String = "UNAVAILABLE",
    val characteristicsSourceId: String = "UNAVAILABLE",
    val calibrationSourceId: String = "UNAVAILABLE",
    val sensorAuthorityFrameNumber: Long = -1L,
    val captureSequenceId: Int = -1,
    val sensorMetadataTimestampNs: Long = 0L,
    val rawMetadataTimestampMatch: Boolean = false,
    val sensorAuthorityFallbackUsed: Boolean = false,
    val rawProcessingSafe: Boolean = false,
    val sensorAuthorityStatus: String = "UNAVAILABLE",
    val sensorMetadataAuditLines: List<String> = emptyList(),
    val imageArrivalElapsedNs: Long = 0L,
    val metadataArrivalElapsedNs: Long = 0L,
    val pairCompleteElapsedNs: Long = 0L,
    val imageDeliveryLagMs: Double? = null,
    val metadataDeliveryLagMs: Double? = null,
    val pairCompletionLagMs: Double? = null,
    val timestampSource: String = "not_recorded",
    val sensorTimestampComparableToElapsedRealtime: Boolean = false,
    val shutterDeltaClockDomainsComparable: Boolean = false,
    val preScorePruneResult: String = "not_recorded",
    val preScorePruneReason: String = "not_recorded",
    val oisLogicalMode: String = "UNREPORTED",
    val oisPhysicalMode: String = "UNREPORTED",
    val oisSampleSource: String = "NONE",
    val oisSampleCount: Int = 0,
    val oisShiftRmsPx: Double? = null,
    val oisShiftPeakPx: Double? = null
)

data class WarningDebugEntry(val group: String, val message: String, val severity: String = "WARN")
data class PipelineDebugEntry(val group: String, val key: String, val value: String)

enum class CaptureStatusState { STARTED, COMPLETED, FAILED, CANCELLED }

object DebugFailureTestTrigger {
    @Volatile var forceFailureForNextCapture: Boolean = false

    fun shouldFailAndClear(): Boolean {
        if (!com.bncam.BuildConfig.DEBUG) return false
        if (forceFailureForNextCapture) {
            forceFailureForNextCapture = false
            return true
        }
        return false
    }
}

class ShotLogger(
    context: Context?,
    private val baseDir: File
) {
    @Suppress("unused")
    private val context: Context? = context?.applicationContext

    constructor(context: Context) : this(context as Context?, resolveDiagnosticsBaseDir(context))
    constructor(overrideBaseDir: File) : this(null, overrideBaseDir)
    private val tag = "ShotLogger"
    private var currentShotDir: File? = null
    private val warnings = mutableListOf<WarningDebugEntry>()
    private val pipelineEvents = mutableListOf<PipelineDebugEntry>()
    private val frameEntries = mutableListOf<FrameAnalysisDebugEntry>()
    private var currentStateDir: File? = null
    @Volatile private var currentPublicFolderName: String = "UNSCOPED"
    private var captureRecipeJson: String? = null
    private var noiseModelTraceJson: String? = null
    private var vendorInjectionText: String? = null
    private var lastDiagnosticPayload: DiagnosticPayload? = null
    private var lastCaptureTrace: com.bncam.core.tracing.ArchitectureCaptureTrace? = null
    private var lastVulkanExport: com.bncam.core.vulkan.VulkanDiagnosticExport? = null
    private var lastLogSummary: Boolean = true
    private var lastLogActiveMode: Boolean = true
    private var lastLogProfileSettings: Boolean = true
    private var lastLogFrameAnalysis: Boolean = true
    private var lastLogWarnings: Boolean = true
    private var lastLogPipelineDebug: Boolean = true
    private var lastLogVendorInjection: Boolean = true
    @Volatile private var activeAttemptId: String? = null
    @Volatile private var activeAttemptState: CaptureStatusState? = null
    @Volatile private var currentCaptureLabel: String = "UNSCOPED"

    init {
        if (!baseDir.exists()) baseDir.mkdirs()
        if (context != null) {
            DiagnosticsAggregator.initialize(context)
            PublicShotDiagnosticsStorage.initialize(context)
        } else {
            DiagnosticsAggregator.initializeDirectory(baseDir)
        }
        runCatching { File(baseDir, "pipeline_lifecycle_debug.txt").delete() }
    }

    fun getShotDir(): File? = currentShotDir

    /**
     * Freezes the current per-shot destination and accumulated diagnostics for deferred work.
     * A later shutter may safely call [startNewShot] on the UI-owned logger without redirecting
     * an older capture's processing/save diagnostics into the newer shot folder.
     */
    @Synchronized
    fun forkForDeferredWork(): ShotLogger {
        val fork = context?.let { ShotLogger(it) } ?: ShotLogger(baseDir)
        fork.currentShotDir = currentShotDir
        fork.currentStateDir = currentStateDir
        fork.currentPublicFolderName = currentPublicFolderName
        fork.warnings.addAll(warnings)
        fork.pipelineEvents.addAll(pipelineEvents)
        fork.frameEntries.addAll(frameEntries)
        fork.captureRecipeJson = captureRecipeJson
        fork.noiseModelTraceJson = noiseModelTraceJson
        fork.vendorInjectionText = vendorInjectionText
        fork.lastDiagnosticPayload = lastDiagnosticPayload
        fork.lastCaptureTrace = lastCaptureTrace
        fork.lastVulkanExport = lastVulkanExport
        fork.lastLogSummary = lastLogSummary
        fork.lastLogActiveMode = lastLogActiveMode
        fork.lastLogProfileSettings = lastLogProfileSettings
        fork.lastLogFrameAnalysis = lastLogFrameAnalysis
        fork.lastLogWarnings = lastLogWarnings
        fork.lastLogPipelineDebug = lastLogPipelineDebug
        fork.lastLogVendorInjection = lastLogVendorInjection
        fork.activeAttemptId = activeAttemptId
        fork.activeAttemptState = activeAttemptState
        fork.currentCaptureLabel = currentCaptureLabel

        // Deferred processing owns terminal finalization, so it must also own the
        // heartbeat for a live attempt. Otherwise the UI logger can keep a
        // GlobalScope heartbeat alive after its capture has been handed off.
        if (fork.activeAttemptState == CaptureStatusState.STARTED) {
            stopHeartbeatPolling()
            fork.currentStateDir?.let { fork.startHeartbeatPolling(it) }
        }
        return fork
    }

    fun startNewShot(
        sensorName: String,
        modeName: String,
        featureName: String = "Photo",
        logSummary: Boolean = true,
        logCapture: Boolean = true,
        logProfileSettings: Boolean = true,
        logIsp: Boolean = true,
        logWarnings: Boolean = true,
        logFrameAnalysis: Boolean = true,
        logVendorInjection: Boolean = true
    ) {
        cleanupTerminalStateDirectories()
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
        val safeSensorName = sensorName
            .ifBlank { "unknown" }
            .trim()
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
            .replace(Regex("_+"), "_")
            .trim('_')
            .ifBlank { "unknown" }
        val modeToken = when {
            modeName.contains("multi", ignoreCase = true) -> "MultiFrameRunner"
            modeName.contains("single", ignoreCase = true) -> "SingleFrameRunner"
            else -> modeName.replace(Regex("[^A-Za-z0-9._-]"), "_")
        }
        val safeFeature = featureName
            .ifBlank { "Photo" }
            .trim()
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
            .replace(Regex("_+"), "_")
            .trim('_')
        val folderName = "${safeSensorName}_${modeToken}_${safeFeature}_$timeStamp"

        warnings.clear()
        pipelineEvents.clear()
        frameEntries.clear()
        captureRecipeJson = null
        noiseModelTraceJson = null
        vendorInjectionText = null
        lastDiagnosticPayload = null
        lastCaptureTrace = null
        lastVulkanExport = null
        activeAttemptId = null
        activeAttemptState = null
        currentCaptureLabel = folderName
        currentPublicFolderName = folderName
        lastLogSummary = logSummary
        lastLogActiveMode = logCapture
        lastLogProfileSettings = logProfileSettings
        lastLogPipelineDebug = logIsp
        lastLogWarnings = logWarnings
        lastLogFrameAnalysis = logFrameAnalysis
        lastLogVendorInjection = logVendorInjection

        // Identity path used by capture tracing/runners only; persistent export is MediaStore-backed.
        currentShotDir = File(baseDir, folderName)
        currentStateDir = File(baseDir, "$TRANSIENT_STATE_PREFIX$folderName").apply { mkdirs() }

        publishInitialFileSet()
    }

    private var heartbeatJob: kotlinx.coroutines.Job? = null

    private fun startHeartbeatPolling(shotDir: File) {
        stopHeartbeatPolling()
        heartbeatJob = kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            while (activeAttemptState == CaptureStatusState.STARTED) {
                try {
                    val hbJson = com.bncam.core.engine.ImageUtils.getNativeStageHeartbeatJsonNative()
                    if (!hbJson.isNullOrBlank()) {
                        File(shotDir, "native_stage_heartbeat.json").writeText(hbJson.trimEnd() + "\n")
                    }
                } catch (_: Throwable) {}
                kotlinx.coroutines.delay(100L)
            }
        }
    }

    private fun stopHeartbeatPolling() {
        heartbeatJob?.cancel()
        heartbeatJob = null
    }

    @Synchronized
    fun writeInitialStatus(attemptId: String, stage: String = "SHUTTER_DISPATCH") {
        activeAttemptId = attemptId
        activeAttemptState = CaptureStatusState.STARTED
        val json = buildStatusJson(attemptId = attemptId, state = CaptureStatusState.STARTED, stage = stage)
        writeTextFileAtomic("status.json", json)
        currentStateDir?.let { startHeartbeatPolling(it) }
    }

    @Synchronized
    fun finalizeAttemptOnce(
        attemptId: String,
        terminalState: CaptureStatusState,
        stage: String,
        exception: Throwable? = null,
        jpegPublished: Boolean = false,
        dngPublished: Boolean = false,
        noiseModelStarted: Boolean = false,
        noiseModelCompleted: Boolean = false
    ): Boolean {
        if (activeAttemptState != CaptureStatusState.STARTED && activeAttemptState != null) {
            safeLogW(tag, "Attempt $attemptId already in terminal state $activeAttemptState; ignoring transition to $terminalState")
            return false
        }
        stopHeartbeatPolling()
        activeAttemptState = terminalState
        activeAttemptId = attemptId
        runCatching {
            val json = buildStatusJson(
                attemptId = attemptId,
                state = terminalState,
                stage = stage,
                exception = exception,
                jpegPublished = jpegPublished,
                dngPublished = dngPublished,
                noiseModelStarted = noiseModelStarted,
                noiseModelCompleted = noiseModelCompleted
            )
            writeTextFileAtomic("status.json", json)
            currentStateDir?.let { dir -> runCatching { File(dir, "native_stage_heartbeat.json").delete() } }
        }.onFailure { safeLogE(tag, "Failed to write status.json for attempt $attemptId", it) }
        return true
    }

    @Synchronized
    fun fallbackFinalizeIfStarted(attemptId: String, reason: String = "terminal_state_missing") {
        if (activeAttemptState == CaptureStatusState.STARTED) {
            stopHeartbeatPolling()
            finalizeAttemptOnce(
                attemptId = attemptId,
                terminalState = CaptureStatusState.FAILED,
                stage = reason,
                exception = IllegalStateException("Capture attempt ended without explicit terminal status ($reason)")
            )
        }
    }

    @Synchronized
    fun finalizeFailureWithPublicDiagnostics(
        attemptId: String,
        stage: String,
        exception: Throwable
    ): Boolean {
        // Never redirect a late async failure into a newer shot folder. Deferred RAW work uses
        // forkForDeferredWork(), so the attempt id is the immutable authority for this logger.
        if (activeAttemptId != attemptId || activeAttemptState != CaptureStatusState.STARTED) {
            safeLogW(
                tag,
                "Ignoring failure diagnostics for attempt $attemptId; activeAttemptId=$activeAttemptId state=$activeAttemptState"
            )
            return false
        }

        val message = exception.message?.takeIf { it.isNotBlank() } ?: "no_message"
        recordWarning(
            group = "Capture Terminal Failure",
            message = "$stage: ${exception.javaClass.simpleName}: $message",
            severity = "ERROR"
        )
        val finalized = finalizeAttemptOnce(
            attemptId = attemptId,
            terminalState = CaptureStatusState.FAILED,
            stage = stage,
            exception = exception
        )
        if (!finalized) return false

        // A failure can happen before DiagnosticPayload exists (for example during candidate
        // admission, RAW materialization, async ISP execution or save publication). Previously
        // that left all public files permanently at "Waiting for capture diagnostics", erasing
        // the only actionable failure stage. Publish a bounded terminal record instead.
        if (lastDiagnosticPayload == null) {
            fun terminalFile(title: String, includePipelineEvents: Boolean = false): String = buildString {
                header(title)
                section("Terminal Capture Failure")
                kv("Folder", currentPublicFolderName)
                kv("Attempt ID", attemptId)
                kv("Capture succeeded", "no")
                kv("Terminal state", CaptureStatusState.FAILED.name)
                kv("Failure stage", stage)
                kv("Exception class", exception.javaClass.name)
                kv("Exception message", message)
                kv("Diagnostic payload", "UNAVAILABLE_BEFORE_TERMINAL_FAILURE")

                if (includePipelineEvents) {
                    section("Last Recorded Pipeline Events")
                    val recent = pipelineEvents.takeLast(32)
                    if (recent.isEmpty()) {
                        kv("Events", "none recorded before failure")
                    } else {
                        recent.forEachIndexed { index, event ->
                            kv("Event ${index + 1}", "${event.group} / ${event.key} = ${event.value}")
                        }
                    }
                }

                if (warnings.isNotEmpty()) {
                    section("Warnings / Errors Recorded Before Failure")
                    warnings.takeLast(16).forEachIndexed { index, warning ->
                        kv(
                            "Finding ${index + 1}",
                            "${warning.severity}: [${warning.group}] ${warning.message}"
                        )
                    }
                }
            }

            publishPublicFile(SUMMARY_FILE, terminalFile("BNCAM SHOT DEBUG"))
            publishPublicFile(CAPTURE_FILE, terminalFile("BNCAM CAPTURE", includePipelineEvents = true))
            publishPublicFile(ISP_FILE, terminalFile("BNCAM ISP", includePipelineEvents = true))
            publishPublicFile(WARNINGS_FILE, terminalFile("BNCAM WARNINGS & ERRORS", includePipelineEvents = true))
            publishPublicFile(FRAME_FILE, terminalFile("BNCAM FRAME ANALYSIS"))
        } else {
            // If a rich payload already exists, retain it and only refresh the warning-bearing
            // public views. Do not replace valid capture/ISP/frame evidence with a smaller record.
            publishPublicFile(SUMMARY_FILE, buildSummaryFile(lastDiagnosticPayload))
            publishPublicFile(WARNINGS_FILE, buildWarningsFile(lastDiagnosticPayload))
        }
        return true
    }

    fun recoverStaleStartedAttempts() {
        if (!baseDir.exists()) return
        baseDir.listFiles()?.filter { it.isDirectory }?.forEach { dir ->
            val statusFile = File(dir, "status.json")
            if (statusFile.exists()) {
                runCatching {
                    val text = statusFile.readText()
                    val json = org.json.JSONObject(text)
                    if (json.optString("state") == "STARTED") {
                        json.put("state", "FAILED")
                        val heartbeatFile = File(dir, "native_stage_heartbeat.json")
                        var lastStage = "STALE_PROCESS_TERMINATED_RECOVERY"
                        if (heartbeatFile.exists()) {
                            runCatching {
                                val hb = org.json.JSONObject(heartbeatFile.readText())
                                val nativeStage = hb.optString("currentNativeStage")
                                if (nativeStage.isNotBlank() && nativeStage != "IDLE") {
                                    lastStage = "STALE_PROCESS_TERMINATED_AT_$nativeStage"
                                    json.put("lastNativeStage", nativeStage)
                                    json.put("nativeStageHeartbeat", hb)
                                }
                            }
                        }
                        json.put("stageReached", lastStage)
                        json.put("exceptionClass", "java.lang.IllegalStateException")
                        json.put("exceptionMessage", "Capture process force-stopped before completion; recovered on app launch")
                        val recovered = json.toString(2)
                        writeTextFileAtomicInDir(dir, "status.json", recovered)
                        DiagnosticsAggregator.record(
                            stream = DiagnosticsAggregator.Stream.CAPTURE,
                            scope = "CAPTURE #${json.optString("attemptId", dir.name)}",
                            section = "STALE CAPTURE RECOVERY",
                            content = recovered
                        )
                        safeLogW(tag, "Recovered stale STARTED status file in ${dir.name} (stageReached=$lastStage)")
                    }
                }
            }
        }
        cleanupTerminalStateDirectories()
    }

    private fun cleanupTerminalStateDirectories() {
        if (!baseDir.exists()) return
        baseDir.listFiles()?.asSequence()
            ?.filter { it.isDirectory && it.name.startsWith(TRANSIENT_STATE_PREFIX) }
            ?.forEach { dir ->
                val statusFile = File(dir, "status.json")
                val terminal = runCatching {
                    if (!statusFile.exists()) return@runCatching false
                    val state = org.json.JSONObject(statusFile.readText()).optString("state")
                    state.isNotBlank() && state != CaptureStatusState.STARTED.name
                }.getOrDefault(false)
                if (terminal) runCatching { dir.deleteRecursively() }
            }
    }

    private fun writeTextFileAtomicInDir(dir: File, fileName: String, content: String) {
        try {
            val targetFile = File(dir, fileName)
            targetFile.writeText(content.trimEnd() + "\n")
        } catch (e: Exception) {
            safeLogE(tag, "Write failed for $fileName in ${dir.name}", e)
        }
    }

    private fun safeLogE(tag: String, msg: String, t: Throwable? = null) {
        try {
            if (t != null) Log.e(tag, msg, t) else Log.e(tag, msg)
        } catch (_: Throwable) {
            System.err.println("[$tag] $msg: ${t?.message}")
        }
    }

    private fun safeLogW(tag: String, msg: String) {
        try {
            Log.w(tag, msg)
        } catch (_: Throwable) {
            System.err.println("[$tag] $msg")
        }
    }

    private fun writeTextFileAtomic(fileName: String, content: String) {
        val dir = currentStateDir ?: return
        try {
            val tempFile = File(dir, "$fileName.tmp")
            val targetFile = File(dir, fileName)
            tempFile.writeText(content.trimEnd() + "\n")
            if (targetFile.exists()) targetFile.delete()
            tempFile.renameTo(targetFile)
        } catch (e: Exception) {
            safeLogE(tag, "Atomic write failed for $fileName", e)
            writeTextFileAtomicInDir(dir, fileName, content)
        }
    }

    private fun buildStatusJson(
        attemptId: String,
        state: CaptureStatusState,
        stage: String,
        exception: Throwable? = null,
        jpegPublished: Boolean = false,
        dngPublished: Boolean = false,
        noiseModelStarted: Boolean = false,
        noiseModelCompleted: Boolean = false
    ): String = buildString {
        append("{\n")
        append("  \"attemptId\": \"").append(attemptId).append("\",\n")
        append("  \"state\": \"").append(state.name).append("\",\n")
        append("  \"stageReached\": \"").append(stage).append("\",\n")
        append("  \"exceptionClass\": ").append(exception?.let { "\"${it.javaClass.name}\"" } ?: "null").append(",\n")
        append("  \"exceptionMessage\": ").append(exception?.let { "\"${it.message?.replace("\"", "\\\"") ?: ""}\"" } ?: "null").append(",\n")
        append("  \"jpegPublished\": ").append(jpegPublished).append(",\n")
        append("  \"dngPublished\": ").append(dngPublished).append(",\n")
        append("  \"noiseModelStarted\": ").append(noiseModelStarted).append(",\n")
        append("  \"noiseModelCompleted\": ").append(noiseModelCompleted).append(",\n")
        append("  \"timestampMs\": ").append(System.currentTimeMillis()).append("\n")
        append("}")
    }

    fun writeTextFile(fileName: String, content: String) {
        try {
            when (fileName.lowercase(Locale.US)) {
                "capture_recipe.json" -> {
                    captureRecipeJson = content
                    publishPublicFile(PROFILE_FILE, buildProfileSettingsFile(lastDiagnosticPayload))
                }
                "noise_model_trace.json" -> {
                    noiseModelTraceJson = content
                    publishPublicFile(ISP_FILE, buildIspFile(lastDiagnosticPayload))
                }
                else -> {
                    // Legacy named payloads remain available in-memory through the central bus,
                    // but persistent output is restricted to the seven stable per-shot files.
                    DiagnosticsAggregator.recordNamedPayload(currentCaptureLabel, fileName, content)
                }
            }
        } catch (e: Exception) {
            safeLogE(tag, "Failed to route per-shot diagnostic payload: $fileName", e)
        }
    }

    fun writeCaptureTrace(trace: com.bncam.core.tracing.ArchitectureCaptureTrace) {
        lastCaptureTrace = trace
        lastVulkanExport = com.bncam.core.vulkan.VulkanRuntimeOwner.diagnosticExport()
        DiagnosticsAggregator.recordCaptureTrace(trace)
        lastVulkanExport?.let { DiagnosticsAggregator.recordVulkanRuntime(trace.captureId, it) }
        publishPublicFile(SUMMARY_FILE, buildSummaryFile(lastDiagnosticPayload))
        publishPublicFile(CAPTURE_FILE, buildCaptureFile(lastDiagnosticPayload))
        publishPublicFile(ISP_FILE, buildIspFile(lastDiagnosticPayload))
        publishPublicFile(WARNINGS_FILE, buildWarningsFile(lastDiagnosticPayload))
    }

    fun recordWarning(group: String, message: String, severity: String = "WARN") {
        warnings.add(WarningDebugEntry(group = group, message = message, severity = severity))
    }

    fun recordPipelineEvent(group: String, key: String, value: String) {
        pipelineEvents.add(PipelineDebugEntry(group = group, key = key, value = value))
    }

    fun recordFrameAnalysisEntries(entries: List<FrameAnalysisDebugEntry>) {
        frameEntries.addAll(entries)
    }

    fun writeShotDebug(
        payload: DiagnosticPayload,
        logSummary: Boolean = true,
        logActiveMode: Boolean = true,
        logProfileSettings: Boolean = true,
        logFrameAnalysis: Boolean = true,
        logWarnings: Boolean = true,
        logPipelineDebug: Boolean = true,
        logVendorInjection: Boolean = true
    ) {
        val debugStart = System.currentTimeMillis()
        lastDiagnosticPayload = payload
        lastLogSummary = logSummary
        lastLogActiveMode = logActiveMode
        lastLogProfileSettings = logProfileSettings
        lastLogFrameAnalysis = logFrameAnalysis
        lastLogWarnings = logWarnings
        lastLogPipelineDebug = logPipelineDebug
        lastLogVendorInjection = logVendorInjection
        try {
            recordPipelineEvent("Timing Breakdown", "Debug render time", "${System.currentTimeMillis() - debugStart} ms")
            publishPublicFile(SUMMARY_FILE, buildSummaryFile(payload))
            publishPublicFile(CAPTURE_FILE, buildCaptureFile(payload))
            publishPublicFile(PROFILE_FILE, buildProfileSettingsFile(payload))
            publishPublicFile(ISP_FILE, buildIspFile(payload))
            publishPublicFile(WARNINGS_FILE, buildWarningsFile(payload))
            publishPublicFile(FRAME_FILE, buildFrameAnalysisFile(payload))
            publishPublicFile(VENDOR_FILE, buildVendorFile())
        } catch (e: Exception) {
            safeLogE(tag, "Failed to flush per-shot diagnostics", e)
        }
    }

    @Suppress("unused")
    fun writeVendorInjectionDebug(
        lensId: String,
        attempts: List<VendorInjectionAttempt>,
        captureResult: TotalCaptureResult? = null
    ) {
        val debugStart = System.currentTimeMillis()
        try {
            vendorInjectionText = buildVendorInjectionDebug(
                lensId = lensId,
                attempts = attempts,
                captureResult = captureResult
            )
            publishPublicFile(VENDOR_FILE, buildVendorFile())
        } catch (e: Exception) {
            safeLogE(tag, "Failed to build vendor tag injection diagnostics", e)
        } finally {
            recordPipelineEvent(
                "Timing Breakdown",
                "Vendor tag injection debug time",
                "${System.currentTimeMillis() - debugStart} ms"
            )
        }
    }

    private fun buildVendorInjectionDebug(
        lensId: String,
        attempts: List<VendorInjectionAttempt>,
        captureResult: TotalCaptureResult?
    ): String = buildString {
        header("INJECTION OF TAGS")

        section("IDENTITY")
        kv("Lens ID", lensId)
        kv("Timestamp", SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date()))
        kv("Attempts Count", attempts.size)
        kv("Capture Result Available", captureResult != null)

        section("SUMMARY")
        val requestedCount = attempts.size
        val appliedCount = attempts.count { it.appliedToBuilder }
        val skippedCount = attempts.count { !it.attempted }
        val failedCount = attempts.count {
            it.finalStatus == VendorInjectionStatus.PARSE_FAILED ||
                    it.finalStatus == VendorInjectionStatus.APPLY_FAILED
        }

        kv("Requested Tags", requestedCount)
        kv("Accepted By Builder", appliedCount)
        kv("Skipped By Engine", skippedCount)
        kv("Failed", failedCount)

        val resultKeys = captureResult?.keys.orEmpty()
        val echoedAttempts = if (captureResult != null) {
            attempts.filter { attempt ->
                resultKeys.any { resultKey -> resultKey.name == attempt.keyName }
            }
        } else {
            emptyList()
        }

        kv("Echoed By Hardware", echoedAttempts.size)
        kv("Not Echoed", if (captureResult != null) attempts.size - echoedAttempts.size else "not checked")

        val inferredVendorSessionTypeAttempt = attempts.firstOrNull { attempt ->
            attempt.keyName.contains("ReprocessableSessionModeTag", ignoreCase = true) ||
                    attempt.keyName.contains("sessionoperationmode", ignoreCase = true) ||
                    attempt.keyName.contains("operationmode", ignoreCase = true)
        }
        val inferredVendorSessionType = inferredVendorSessionTypeAttempt
            ?.requestedValue
            ?.split(",", ";", " ", "[", "]")
            ?.firstNotNullOfOrNull { token ->
                val clean = token.trim()
                when {
                    clean.isBlank() -> null
                    clean.startsWith("0x", ignoreCase = true) -> clean.removePrefix("0x").removePrefix("0X").toIntOrNull(16)
                    else -> clean.toIntOrNull()
                }
            }

        section("SESSION TYPE DIAGNOSTICS")
        if (inferredVendorSessionType != null && inferredVendorSessionType >= 32768) {
            kv("Requested Vendor Session Type", "$inferredVendorSessionType / 0x${inferredVendorSessionType.toString(16).uppercase()}")
            kv("Source Tag", inferredVendorSessionTypeAttempt?.keyName ?: "unknown")
            line("  Note: this proves the recipe requested a vendor operation mode. Root-level pipeline lifecycle logging is disabled; shot debug stays inside the active shot folder.")
        } else {
            kv("Requested Vendor Session Type", "regular / not requested")
        }

        section("REQUESTED TAGS")
        if (attempts.isEmpty()) {
            line("  No vendor tags were requested.")
        } else {
            attempts.forEachIndexed { index, attempt ->
                appendLine("  #${index + 1}")
                kv("Key", attempt.keyName)
                kv("Requested Value", attempt.requestedValue)
                kv("Parsed Preview", attempt.parsedValuePreview.ifBlank { "not parsed" })
                kv("Value Type", attempt.valueType)
                kv("Target", attempt.target)
                kv("Builder Stage", attempt.builderStage)
                kv("Source", attempt.source.ifBlank { "unknown" })
                kv("Attempted", attempt.attempted)
                kv("Accepted By Builder", attempt.appliedToBuilder)
                kv("Final Status", attempt.finalStatus)

                if (attempt.parseError.isNotBlank()) {
                    kv("Parse Error", attempt.parseError)
                }

                if (attempt.applyError.isNotBlank()) {
                    kv("Apply / Skip Reason", attempt.applyError)
                }

                if (attempt.notes.isNotBlank()) {
                    kv("Notes", attempt.notes)
                }

                appendLine()
            }
        }

        section("ACCEPTED BY BUILDER")
        val acceptedByBuilder = attempts.filter { it.appliedToBuilder }

        if (acceptedByBuilder.isEmpty()) {
            line("  No tags were accepted by the CaptureRequest builder.")
        } else {
            acceptedByBuilder.forEach { attempt ->
                line("  [BUILDER ACCEPTED] ${attempt.keyName}")
                line("      value=${attempt.parsedValuePreview.ifBlank { attempt.requestedValue }}")
                line("      target=${attempt.target} stage=${attempt.builderStage}")
            }
        }

        section("REJECTED / SKIPPED BY ENGINE")
        val rejectedByEngine = attempts.filter { !it.appliedToBuilder }

        if (rejectedByEngine.isEmpty()) {
            line("  No tags were rejected or skipped by the engine.")
        } else {
            rejectedByEngine.forEach { attempt ->
                line("  [${attempt.finalStatus}] ${attempt.keyName}")
                if (attempt.parseError.isNotBlank()) {
                    line("      parseError=${attempt.parseError}")
                }
                if (attempt.applyError.isNotBlank()) {
                    line("      reason=${attempt.applyError}")
                }
                line("      target=${attempt.target} stage=${attempt.builderStage}")
            }
        }

        section("ACCEPTED BY HARDWARE / RESULT ECHO")
        if (captureResult == null) {
            line("  No TotalCaptureResult supplied. Hardware echo could not be checked.")
        } else if (attempts.isEmpty()) {
            line("  No requested tags to check.")
        } else {
            attempts.forEach { attempt ->
                val resultKey = resultKeys.find { it.name == attempt.keyName }

                if (resultKey != null) {
                    val hardwareValue = captureResult.get(resultKey).contentToStringSafe()
                    line("  [ECHOED] ${attempt.keyName}")
                    line("      requested=${attempt.requestedValue}")
                    line("      parsed=${attempt.parsedValuePreview.ifBlank { "not parsed" }}")
                    line("      hardware=$hardwareValue")
                } else {
                    line("  [NOT ECHOED] ${attempt.keyName}")
                    line("      status=${attempt.finalStatus}")
                    line("      note=Not returned in TotalCaptureResult. This can be normal for session-only tags, unsupported tags, or HAL-internal controls.")
                }
            }
        }

        section("FULL RETURNED VENDOR METADATA")
        if (captureResult == null) {
            line("  No TotalCaptureResult supplied.")
        } else {
            val vendorKeys = resultKeys
                .filter { it.name.contains(".") && !it.name.startsWith("android.") }
                .sortedBy { it.name }

            if (vendorKeys.isEmpty()) {
                line("  No vendor-specific metadata returned.")
            } else {
                vendorKeys.forEach { key ->
                    val value = captureResult.get(key).contentToStringSafe()
                    line("  ${key.name}")
                    line("      = $value")
                }
            }
        }

        val fullSuccess = attempts.isNotEmpty() &&
                attempts.all { it.appliedToBuilder } &&
                (captureResult == null || echoedAttempts.size == attempts.size)

        val rejectionCount = attempts.count { !it.appliedToBuilder } +
                if (captureResult != null) attempts.size - echoedAttempts.size else 0

        aiAnalysis("vendor_echo", fullSuccess, "N/A", rejectionCount)
    }

    // =========================================================================
    // STRING BUILDERS (CENTRALIZED, READABLE BNCAM DEBUG LAYOUT)
    // =========================================================================

    private fun publishInitialFileSet() {
        val placeholderSuffix = buildString {
            section("Shot Identity")
            kv("Folder", currentPublicFolderName)
            kv("Storage", PublicShotDiagnosticsStorage.displayRoot())
            kv("State", "Waiting for capture diagnostics")
        }
        listOf(
            SUMMARY_FILE to "SUMMARY",
            CAPTURE_FILE to "CAPTURE",
            PROFILE_FILE to "PROFILE SETTINGS",
            ISP_FILE to "ISP",
            WARNINGS_FILE to "WARNINGS & ERRORS",
            FRAME_FILE to "FRAME ANALYSIS",
            VENDOR_FILE to "VENDOR TAG INJECTION"
        ).forEach { (fileName, title) ->
            publishPublicFile(fileName, buildString {
                header(title)
                append(placeholderSuffix)
            })
        }
    }

    private fun isPublicDiagnosticFileEnabled(fileName: String): Boolean = when (fileName) {
        SUMMARY_FILE -> lastLogSummary
        CAPTURE_FILE -> lastLogActiveMode
        PROFILE_FILE -> lastLogProfileSettings
        ISP_FILE -> lastLogPipelineDebug
        WARNINGS_FILE -> lastLogWarnings
        FRAME_FILE -> lastLogFrameAnalysis
        VENDOR_FILE -> lastLogVendorInjection
        else -> false
    }

    private fun publishPublicFile(fileName: String, content: String) {
        if (!isPublicDiagnosticFileEnabled(fileName)) return
        val folder = currentPublicFolderName.takeIf { it.isNotBlank() && it != "UNSCOPED" } ?: return
        if (context != null) {
            PublicShotDiagnosticsStorage.publish(folder, fileName, content)
        } else {
            val dir = File(baseDir, folder).apply { mkdirs() }
            runCatching { File(dir, fileName).writeText(content.trimEnd() + "\n") }
                .onFailure { safeLogE(tag, "Unable to publish test diagnostics $folder/$fileName", it) }
        }
    }

    private fun buildSummaryFile(payload: DiagnosticPayload?): String {
        if (payload == null) return pendingFile("SUMMARY")
        if (!lastLogSummary) return disabledFile("SUMMARY", "Summary detail disabled in diagnostics settings")
        return buildString {
            append(buildSummary(payload))
            section("Automated Interpretation")
            val findings = automatedFindings(payload)
            kv("Overall", if (findings.any { it.first == "CRITICAL" || it.first == "ERROR" }) "ATTENTION REQUIRED" else if (findings.any { it.first == "WARN" }) "REVIEW" else "HEALTHY")
            findings.take(6).forEachIndexed { index, finding ->
                kv("Finding ${index + 1}", "${finding.first}: ${finding.second}")
            }
            kv("Interpretation source", "Deterministic on-device diagnostic rules; no cloud model")
        }
    }

    private fun buildCaptureFile(payload: DiagnosticPayload?): String = buildString {
        header("BNCAM CAPTURE")
        if (payload == null) {
            section("Shot Identity")
            kv("Folder", currentPublicFolderName)
            kv("State", "Waiting for capture result")
            return@buildString
        }

        section("Capture Identity")
        kv("Folder", currentPublicFolderName)
        kv("Lens", payload.lensName)
        kv("Camera ID", payload.cameraId)
        kv("Mode", if (isMultiFrame(payload)) "multi" else "single")
        kv("Runner", payload.routeRunner)
        kv("Frame source", payload.bufferFormat)
        kv("Succeeded", yesNo(payload.captureSucceeded))

        section("Request & Exposure")
        kv("Metering", payload.meteringStyle)
        kv("EV offset", String.format(Locale.US, "%+.2f EV", payload.evOffset))
        kv("Sensor ISO", payload.sensorSensitivity.takeIf { it > 0 } ?: "auto / not reported")
        kv("Exposure", payload.sensorExposureTime.takeIf { it > 0L }?.let { formatExposure(it) } ?: "auto / not reported")
        kv("AE before", payload.aeStateBeforeCapture)
        kv("AE at capture", payload.aeStateAtCapture)
        writeLatestEvents("Camera2 Control Policy")
        writeLatestEvents("Capture Request")
        writeLatestEvents("Metering Validation")

        section("Route & Buffer")
        kv("Requested mode", payload.requestedMode)
        kv("Resolved route", payload.resolvedRoute)
        kv("Actual route", payload.actualRoute)
        kv("Fallback", yesNo(payload.fallbackUsed))
        if (payload.fallbackUsed || payload.fallbackReason != "none") kv("Fallback reason", payload.fallbackReason)
        kv("Capture size", formatSize(payload.frameWidth, payload.frameHeight))
        kv("Output size", formatSize(payload.outputWidth, payload.outputHeight))
        kv("Buffer capacity", payload.bufferCapacity)
        kv("Frames buffered", payload.framesBuffered)
        kv("Frames requested", payload.framesRequested)
        kv("Frames accepted", payload.framesAccepted)
        kv("Frames merged", payload.framesMerged)
        writeLatestEvents("ImageReader / Buffer")
        writeLatestEvents("Buffer Health Freshness")

        section("Output & Publication")
        kv("JPEG created", yesNo(payload.jpegCreated))
        kv("JPEG bytes", payload.jpegBytes)
        kv("DNG created", yesNo(payload.dngCreated))
        kv("Public filename", payload.publicFilename)
        kv("Saved path", payload.savedOutputPath)
        kv("Save location", payload.saveLocation)
        appendTraceDecisions(com.bncam.core.tracing.CaptureTraceSection.OUTPUT_AND_PUBLICATION)

        section("Timing")
        kv("Total shot", ms(payload.totalShotTimeMs))
        kv("Camera capture", ms(payload.cameraCaptureTimeMs))
        if (isMultiFrame(payload)) kv("Merge", ms(payload.mergeTimeMs))
        kv("Render / ISP", ms(payload.renderTimeMs))
        kv("JPEG encode", ms(payload.jpegCompressionTimeMs))
        kv("Save", ms(payload.saveTimeMs))
        writeLatestEvents("Timing Breakdown")
        writeLatestEvents("Performance")

        RawPreviewFirstActivationTrace.latestReport()?.let { report ->
            section("RAW First Activation Trace")
            report.lineSequence().forEach { traceLine -> line(traceLine) }
        }
        RawPreviewCadenceDiagnostics.latestReport()?.let { report ->
            section("RAW Preview Cadence")
            report.lineSequence().forEach { cadenceLine -> line(cadenceLine) }
        }

        section("Automated Interpretation")
        automatedFindings(payload).filter { it.second.contains("capture", true) || it.second.contains("buffer", true) || it.second.contains("tim", true) }
            .take(5).forEachIndexed { index, finding -> kv("Finding ${index + 1}", "${finding.first}: ${finding.second}") }
    }

    private fun buildProfileSettingsFile(payload: DiagnosticPayload?): String {
        if (!lastLogProfileSettings) return disabledFile("PROFILE SETTINGS", "Profile-settings file disabled in diagnostics settings")
        return buildString {
        header("BNCAM PROFILE SETTINGS")
        section("Profile Identity")
        kv("Folder", currentPublicFolderName)
        kv("Profile name", payload?.profileName ?: "waiting for capture result")
        kv("Profile ID", payload?.profileId ?: "waiting for capture result")
        lastCaptureTrace?.let { kv("Profile hash", it.recipeProfileHash) }

        val recipeText = captureRecipeJson
        if (recipeText.isNullOrBlank()) {
            section("Captured Configuration")
            kv("State", "Capture recipe not available yet")
        } else {
            val recipe = runCatching { JSONObject(recipeText) }.getOrNull()
            if (recipe == null) {
                section("Captured Configuration")
                kv("State", "Capture recipe could not be parsed")
            } else {
                section("Profile Routing")
                listOf(
                    "activeProfileIdentifier",
                    "profileVersionHash",
                    "frameSource",
                    "captureMode",
                    "frameSelectionMethod",
                    "anchorSelectionMethod",
                    "alignmentMethod",
                    "fusionMethod",
                    "demosaicMethod"
                ).forEach { key -> if (recipe.has(key)) kv(humanizeKey(key), jsonScalar(recipe.opt(key))) }
                appendJsonBranch(recipe, "ispSettings", "ISP Profile Settings")
                appendJsonBranch(recipe, "selectionSettings", "Frame Selection Settings")
                appendJsonBranch(recipe, "mergeSettings", "Merge Settings")
            }
        }

        section("Portability Boundary")
        kv("Physical sensor calibration", "Excluded from profile settings")
        kv("Target black / white levels", "Resolved from active target sensor")
        kv("Hardware properties", "Resolved from active target camera")
        kv("Interpretation", "This file describes captured profile intent, not device calibration")
        }
    }

    private fun buildIspFile(payload: DiagnosticPayload?): String = buildString {
        header("BNCAM ISP")
        if (!lastLogPipelineDebug) {
            section("Diagnostics Setting")
            kv("State", "ISP detail disabled in diagnostics settings")
            kv("Folder", currentPublicFolderName)
            return@buildString
        }
        section("ISP Identity")
        kv("Folder", currentPublicFolderName)
        kv("Frame source", payload?.bufferFormat ?: "pending")
        kv("Capture size", payload?.let { formatSize(it.frameWidth, it.frameHeight) } ?: "pending")

        listOf(
            "Renderer Pipeline",
            "Sensor Calibration",
            "Sensor Calibration Detail",
            "Native Calibration",
            "RAW10 Native Merge",
            "RAW_SENSOR Native Merge",
            "Single RAW16 Frame",
            "Single RAW16 ISP Render",
            "Master RAW16 ISP Render",
            "YUV Native Render",
            "Resolved ISP Settings",
            "Quality Config",
            "Common Post-Render",
            "Output Encode",
            "HDR"
        ).forEach { group ->
            if (eventsLatestFor(group).isNotEmpty()) {
                section(group)
                eventsLatestFor(group).forEach { event -> kv(event.key, event.value) }
            }
        }

        appendNoiseModelIspSummary()
        appendSingleFrameObjectiveTruth(payload)
        appendVulkanSummary()

        if (payload != null) {
            section("Automated Interpretation")
            automatedIspFindings().ifEmpty { listOf("INFO" to "No high-confidence ISP anomaly was detected by the on-device rules.") }
                .take(8).forEachIndexed { index, finding -> kv("Finding ${index + 1}", "${finding.first}: ${finding.second}") }
            kv("Interpretation source", "Deterministic on-device diagnostic rules; no cloud model")
        }
    }

    private fun buildWarningsFile(payload: DiagnosticPayload?): String = buildString {
        header("BNCAM WARNINGS & ERRORS")
        if (payload == null) {
            section("Health")
            kv("State", "Waiting for capture result")
            return@buildString
        }
        val merged = mutableListOf<WarningDebugEntry>()
        merged += buildWarningList(payload)
        lastCaptureTrace?.warnings?.forEach { warning ->
            merged += WarningDebugEntry("Capture Trace", "${warning.message}; reason=${warning.reason}", warning.severity.name)
        }
        lastCaptureTrace?.exceptions?.forEach { exception ->
            merged += WarningDebugEntry(exception.stage, "${exception.type}: ${exception.message} [${exception.fingerprint}]", "ERROR")
        }
        automatedIspFindings().filter { it.first == "CRITICAL" || it.first == "ERROR" || it.first == "WARN" }.forEach { finding ->
            merged += WarningDebugEntry("Automated ISP Check", finding.second, finding.first)
        }
        DiagnosticsAggregator.recentCaptureAdmissionFailures().forEach { record ->
            merged += WarningDebugEntry(
                group = "Prior Shutter Admission",
                message = "${record.section}: ${record.content}",
                severity = "WARN"
            )
        }
        val filtered = if (lastLogWarnings) merged else merged.filter {
            val severity = it.severity.uppercase(Locale.US)
            severity == "ERROR" || severity == "CRITICAL"
        }
        val deduped = filtered.groupingBy { Triple(it.severity.uppercase(Locale.US), it.group, it.message) }.eachCount()

        section("Health Summary")
        kv("Capture succeeded", yesNo(payload.captureSucceeded))
        kv("Unique findings", deduped.size)
        kv("Errors", deduped.keys.count { it.first == "ERROR" || it.first == "CRITICAL" })
        kv("Warnings", deduped.keys.count { it.first == "WARN" })

        section("Findings")
        if (deduped.isEmpty()) {
            kv("Status", "No warnings or errors recorded")
        } else {
            deduped.entries.sortedWith(compareBy({ severityRank(it.key.first) }, { it.key.second }, { it.key.third }))
                .forEach { (key, count) ->
                    val suffix = if (count > 1) " (repeated $count x; duplicates collapsed)" else ""
                    line("${key.first}: [${key.second}] ${key.third}$suffix")
                }
        }
    }

    private fun buildFrameAnalysisFile(payload: DiagnosticPayload?): String {
        if (payload == null) return pendingFile("FRAME ANALYSIS")
        if (!lastLogFrameAnalysis) return disabledFile("FRAME ANALYSIS", "Frame-analysis detail disabled in diagnostics settings")
        return buildString {
            append(buildFrameAnalysis(payload, includeFullDetail = false))
            section("Automated Interpretation")
            val anchor = frameEntries.firstOrNull { it.selectedAnchor }
            when {
                frameEntries.isEmpty() -> kv("Finding", "INFO: No per-frame candidates were recorded")
                anchor == null -> kv("Finding", "WARN: No selected anchor is present in frame diagnostics")
                anchor.stale -> kv("Finding", "WARN: Selected anchor was marked stale")
                anchor.requestProvenanceStatus.contains("UNPROVEN", true) -> kv("Finding", "WARN: Anchor request provenance was not proven")
                else -> kv("Finding", "INFO: Anchor selection is present and no obvious freshness/provenance issue was detected")
            }
        }
    }

    private fun buildVendorFile(): String {
        if (!lastLogVendorInjection) return disabledFile("VENDOR TAG INJECTION", "Vendor-tag file disabled in diagnostics settings")
        return vendorInjectionText ?: pendingFile("VENDOR TAG INJECTION", "No vendor-tag injection data recorded for this shot yet.")
    }

    private fun pendingFile(title: String, state: String = "Waiting for capture diagnostics"): String = buildString {
        header(title)
        section("Shot Identity")
        kv("Folder", currentPublicFolderName)
        kv("State", state)
    }

    private fun disabledFile(title: String, state: String): String = buildString {
        header(title)
        section("Diagnostics Setting")
        kv("Folder", currentPublicFolderName)
        kv("State", state)
    }

    private fun StringBuilder.writeLatestEvents(group: String) {
        val events = eventsLatestFor(group)
        if (events.isEmpty()) return
        events.forEach { event -> kv(event.key, event.value) }
    }

    private fun eventsLatestFor(group: String): List<PipelineDebugEntry> {
        val latest = linkedMapOf<String, PipelineDebugEntry>()
        pipelineEvents.asSequence().filter { it.group == group }.forEach { event -> latest[event.key] = event }
        return latest.values.toList()
    }

    private fun StringBuilder.appendTraceDecisions(section: com.bncam.core.tracing.CaptureTraceSection) {
        val records = lastCaptureTrace?.sections?.get(section).orEmpty()
        records.filter { it.decision != null }.forEach { record ->
            val decision = record.decision ?: return@forEach
            kv(
                humanizeKey(record.key),
                "requested=${decision.requested ?: "none"}; resolved=${decision.resolved ?: "none"}; executed=${decision.executed ?: "not recorded"}; result=${decision.result ?: "unknown"}; fallback=${decision.fallback}; reason=${decision.reason ?: "none"}"
            )
        }
    }

    private fun StringBuilder.appendJsonBranch(root: JSONObject, key: String, title: String) {
        val value = root.opt(key) ?: return
        section(title)
        appendJsonValue(value, prefix = "", depth = 0)
    }

    private fun StringBuilder.appendJsonValue(value: Any?, prefix: String, depth: Int) {
        if (depth > 7) {
            kv(prefix.ifBlank { "Value" }, "nested value omitted beyond depth limit")
            return
        }
        when (value) {
            null, JSONObject.NULL -> kv(prefix.ifBlank { "Value" }, "none")
            is JSONObject -> {
                val keys = value.keys().asSequence().toList().sorted()
                keys.forEach { key ->
                    val childPrefix = if (prefix.isBlank()) humanizeKey(key) else "$prefix / ${humanizeKey(key)}"
                    appendJsonValue(value.opt(key), childPrefix, depth + 1)
                }
            }
            is JSONArray -> {
                if (value.length() == 0) {
                    kv(prefix.ifBlank { "Value" }, "[]")
                } else {
                    for (index in 0 until value.length()) {
                        val item = value.opt(index)
                        val childPrefix = "${prefix.ifBlank { "Item" }} ${index + 1}"
                        appendJsonValue(item, childPrefix, depth + 1)
                    }
                }
            }
            else -> kv(prefix.ifBlank { "Value" }, jsonScalar(value))
        }
    }

    private fun jsonScalar(value: Any?): String = when (value) {
        null, JSONObject.NULL -> "none"
        is Double -> String.format(Locale.US, "%.6f", value)
        is Float -> String.format(Locale.US, "%.6f", value)
        else -> value.toString()
    }

    private fun humanizeKey(key: String): String = key
        .replace('_', ' ')
        .replace(Regex("([a-z0-9])([A-Z])"), "$1 $2")
        .trim()
        .replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.US) else it.toString() }

    private fun StringBuilder.appendNoiseModelIspSummary() {
        val root = noiseModelTraceJson?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return
        val metrics = root.optJSONObject("timing")?.optJSONObject("runner")?.optJSONObject("metrics")
        if (metrics != null) {
            val keys = listOf(
                "nativeRawIsp.actualIso",
                "nativeRawIsp.exposureMs",
                "nativeRawIsp.effectiveCfaPattern",
                "nativeRawIsp.cfaOriginX",
                "nativeRawIsp.cfaOriginY",
                "nativeRawIsp.demosaicInputWidth",
                "nativeRawIsp.demosaicInputHeight",
                "nativeRawIsp.demosaicModeRequested",
                "nativeRawIsp.demosaicModeResolved",
                "nativeRawIsp.vulkanDemosaicStatus",
                "nativeRawIsp.vulkanAwbCcmStatus",
                "nativeRawIsp.colorStageMeansRawR",
                "nativeRawIsp.colorStageMeansRawG",
                "nativeRawIsp.colorStageMeansRawB",
                "nativeRawIsp.colorStageMeansWbR",
                "nativeRawIsp.colorStageMeansWbG",
                "nativeRawIsp.colorStageMeansWbB",
                "nativeRawIsp.colorStageMeansCcmR",
                "nativeRawIsp.colorStageMeansCcmG",
                "nativeRawIsp.colorStageMeansCcmB",
                "nativeRawIsp.colorStageMeansToneR",
                "nativeRawIsp.colorStageMeansToneG",
                "nativeRawIsp.colorStageMeansToneB",
                "nativeRawIsp.finalRedClippedPct",
                "nativeRawIsp.finalGreenClippedPct",
                "nativeRawIsp.finalBlueClippedPct",
                "nativeRawIsp.blackAnchor",
                "nativeRawIsp.toneSceneMidtoneTarget",
                "nativeRawIsp.toneAutomaticGlobalGainCap",
                "nativeRawIsp.toneSceneRangeStops",
                "nativeRawIsp.toneSceneRangeEvidence",
                "nativeRawIsp.toneDisplayWhiteExpansionStart",
                "nativeRawIsp.toneDisplayWhiteExpansionGamma",
                "nativeRawIsp.toneShoulderStart",
                "nativeRawIsp.toneShoulderStrength",
                "nativeRawIsp.toneContrastStrength",
                "nativeRawIsp.toneDynamicRangePressure",
                "nativeRawIsp.automaticExposureGain",
                "nativeRawIsp.appliedGain",
                "nativeRawIsp.profileToneExposureEv",
                "nativeRawIsp.profileToneHighlights",
                "nativeRawIsp.profileToneShadows",
                "nativeRawIsp.profileToneWhites",
                "nativeRawIsp.profileToneBlacks",
                "nativeRawIsp.profileToneContrast",
                "nativeRawIsp.profileLocalToneBias",
                "nativeRawIsp.localToneStrength",
                "nativeRawIsp.localToneMaxLiftEv",
                "nativeRawIsp.localToneMaxCompressEv",
                "nativeRawIsp.localToneBackend",
                "nativeRawIsp.highlightOccupancyPct",
                "nativeRawIsp.highlightRecoveryMs",
                "nativeRawIsp.vulkanToneStatus",
                "nativeRawIsp.sharpenBackend",
                "nativeRawIsp.sharpenAmount",
                "nativeRawIsp.jpegEncodeMs",
                "nativeRawIsp.totalRawIspCoreMs",
                "nativeRawIsp.rawResidentEntryUsed",
                "nativeRawIsp.rawNormalizeBackend",
                "nativeRawIsp.spectraProductionBackend",
                "nativeRawIsp.sensorNoiseVarianceFormula",
                "nativeRawIsp.sensorNoiseVarianceSamples",
                "nativeRawIsp.meanSensorNoiseVariance",
                "nativeRawIsp.minSensorNoiseVariance",
                "nativeRawIsp.maxSensorNoiseVariance",
                "nativeRawIsp.noiseModelSoReceivedByCpp",
                "nativeRawIsp.noiseModelApplied",
                "nativeRawIsp.absoluteMeanLumaSigma",
                "nativeRawIsp.absoluteMeanChromaSigma",
                "nativeRawIsp.effectiveLumaSigma",
                "nativeRawIsp.effectiveChromaSigma",
                "nativeRawIsp.preDenoiseResidualEstimate",
                "nativeRawIsp.postDenoiseResidualEstimate",
                "nativeRawIsp.postSharpenResidualEstimate",
                "nativeRawIsp.avgAppliedBlend",
                "nativeRawIsp.edgeProtectedPixelFraction",
                "nativeRawIsp.physicalBaselineNrActive",
                "nativeRawIsp.physicalBaselineLumaFraction",
                "nativeRawIsp.physicalBaselineChromaFraction",
                "nativeRawIsp.autoMeanGradient",
                "nativeRawIsp.autoP90Gradient",
                "nativeRawIsp.autoEdgeFraction",
                "nativeRawIsp.autoCoherentEdgeFraction",
                "nativeRawIsp.rawJpegVibranceApplied",
                "nativeRawIsp.rawJpegBaseVibrance",
                "nativeRawIsp.rawJpegEffectiveVibranceMean",
                "nativeRawIsp.colorStageMeanSaturationBefore",
                "nativeRawIsp.colorStageMeanSaturationAfter",
                "nativeRawIsp.highlightNeutralize",
                "nativeRawIsp.localHighlightRecoveryApplied",
                "nativeRawIsp.spectraPropagationAwbGainsRgb",
                "nativeRawIsp.spectraPropagationColourMatrix"
            )
            section("Native ISP High-Signal Metrics")
            keys.forEach { key -> if (metrics.has(key)) kv(humanizeKey(key.removePrefix("nativeRawIsp.")), jsonScalar(metrics.opt(key))) }
        }

        root.optJSONObject("captureNoiseState")?.let { state ->
            section("Noise / Sensor State")
            listOf("captureIso", "exposureTimeNs", "cfaName", "whiteLevel", "blackLevelMosaicOrder", "modelConfidence").forEach { key ->
                if (state.has(key)) kv(humanizeKey(key), jsonScalar(state.opt(key)))
            }
        }
    }

    private fun parseSemicolonStats(raw: String?): Map<String, String> {
        if (raw.isNullOrBlank() || raw == "not recorded") return emptyMap()
        return raw.split(';')
            .mapNotNull { token ->
                val index = token.indexOf('=')
                if (index <= 0) return@mapNotNull null
                val key = token.substring(0, index).trim()
                val value = token.substring(index + 1).trim()
                if (key.isEmpty() || value.isEmpty()) null else key to value
            }
            .toMap()
    }

    private fun JSONObject.metricDouble(key: String): Double? = when (val value = opt(key)) {
        is Number -> value.toDouble().takeIf { it.isFinite() }
        else -> value?.toString()?.toDoubleOrNull()?.takeIf { it.isFinite() }
    }

    private fun Map<String, String>.statDouble(key: String): Double? =
        get(key)?.toDoubleOrNull()?.takeIf { it.isFinite() }

    private fun StringBuilder.appendSingleFrameObjectiveTruth(payload: DiagnosticPayload?) {
        if (payload == null || isMultiFrame(payload)) return
        when (payload.bufferFormat) {
            "RAW10", "RAW_SENSOR" -> appendRawSingleFrameObjectiveTruth()
            "YUV_420_888" -> appendYuvSingleFrameObjectiveTruth()
        }
    }

    private fun StringBuilder.appendRawSingleFrameObjectiveTruth() {
        val metrics = noiseModelMetrics()
        section("Single-Frame Objective Truth — RAW")
        kv("Noise model source", firstEventValue(
            "Sensor Calibration Detail" to "Sensor Noise Profile Source",
            "Sensor Calibration" to "Sensor Noise Profile Source"
        ) ?: "not recorded")
        kv("Noise model formula", firstEventValue(
            "Sensor Calibration Detail" to "Sensor Noise Profile Formula",
            "Sensor Calibration" to "Sensor Noise Profile Formula"
        ) ?: metrics?.optString("nativeRawIsp.sensorNoiseVarianceFormula")?.takeIf { it.isNotBlank() } ?: "not recorded")
        kv("Noise model S/O", metrics?.optString("nativeRawIsp.noiseModelSoReceivedByCpp")?.takeIf { it.isNotBlank() }
            ?: firstEventValue("Sensor Calibration Detail" to "Sensor Noise Profile Values S/O")
            ?: "not recorded")
        kv("WB gains actually propagated", metrics?.optString("nativeRawIsp.spectraPropagationAwbGainsRgb")?.takeIf { it.isNotBlank() } ?: "not recorded")
        kv("CCM actually propagated", metrics?.optString("nativeRawIsp.spectraPropagationColourMatrix")?.takeIf { it.isNotBlank() } ?: "not recorded")
        kv("CCM metadata pre-normalization", eventValue("Sensor Calibration Detail", "Color Matrix Metadata Pre-Normalization Values") ?: "not recorded")
        kv("CCM effective", eventValue("Sensor Calibration Detail", "Color Matrix Values") ?: "not recorded")
        kv("CCM neutral row normalization", eventValue("Sensor Calibration Detail", "Color Matrix Neutral Row Normalization Applied") ?: "not recorded")

        if (metrics == null) {
            kv("Objective native metrics", "not recorded")
            return
        }
        fun metric(key: String): String = metrics.opt(key)?.let { jsonScalar(it) } ?: "not recorded"
        kv("Sensor noise variance samples", metric("nativeRawIsp.sensorNoiseVarianceSamples"))
        kv("Mean sensor noise variance", metric("nativeRawIsp.meanSensorNoiseVariance"))
        kv("Absolute mean luma sigma", metric("nativeRawIsp.absoluteMeanLumaSigma"))
        kv("Absolute mean chroma sigma", metric("nativeRawIsp.absoluteMeanChromaSigma"))
        kv("Effective luma sigma", metric("nativeRawIsp.effectiveLumaSigma"))
        kv("Effective chroma sigma", metric("nativeRawIsp.effectiveChromaSigma"))
        kv("Pre-denoise residual", metric("nativeRawIsp.preDenoiseResidualEstimate"))
        kv("Post-denoise residual", metric("nativeRawIsp.postDenoiseResidualEstimate"))
        kv("Post-sharpen residual", metric("nativeRawIsp.postSharpenResidualEstimate"))
        kv("Average applied denoise blend", metric("nativeRawIsp.avgAppliedBlend"))
        kv("Edge-protected pixel fraction", metric("nativeRawIsp.edgeProtectedPixelFraction"))
        kv("Demosaic analysis mean / p90 gradient",
            "${metric("nativeRawIsp.autoMeanGradient")} / ${metric("nativeRawIsp.autoP90Gradient")}")
        kv("Demosaic edge / coherent-edge fraction",
            "${metric("nativeRawIsp.autoEdgeFraction")} / ${metric("nativeRawIsp.autoCoherentEdgeFraction")}")
        kv("Presentation vibrance applied", metric("nativeRawIsp.rawJpegVibranceApplied"))
        kv("Presentation base vibrance", metric("nativeRawIsp.rawJpegBaseVibrance"))
        kv("Presentation effective vibrance mean", metric("nativeRawIsp.rawJpegEffectiveVibranceMean"))
        kv("Mean saturation before / after presentation",
            "${metric("nativeRawIsp.colorStageMeanSaturationBefore")} / ${metric("nativeRawIsp.colorStageMeanSaturationAfter")}")
        kv("Highlight neutralization applied", metric("nativeRawIsp.highlightNeutralize"))
        kv("Local highlight recovery applied", metric("nativeRawIsp.localHighlightRecoveryApplied"))

        val pre = metrics.metricDouble("nativeRawIsp.preDenoiseResidualEstimate")
        val post = metrics.metricDouble("nativeRawIsp.postDenoiseResidualEstimate")
        val sharpened = metrics.metricDouble("nativeRawIsp.postSharpenResidualEstimate")
        if (pre != null && post != null && pre > 1.0e-12) {
            kv("Measured residual reduction", "${fmt2((1.0 - post / pre) * 100.0)} %")
        } else {
            kv("Measured residual reduction", "not derivable")
        }
        if (post != null && sharpened != null && post > 1.0e-12) {
            kv("Residual gain through sharpening", "${fmt4(sharpened / post)} x")
        } else {
            kv("Residual gain through sharpening", "not derivable")
        }

        listOf("Raw", "Wb", "Ccm", "Tone").forEach { stage ->
            val r = metrics.metricDouble("nativeRawIsp.colorStageMeans${stage}R")
            val g = metrics.metricDouble("nativeRawIsp.colorStageMeans${stage}G")
            val b = metrics.metricDouble("nativeRawIsp.colorStageMeans${stage}B")
            if (r != null && g != null && b != null) {
                kv("$stage RGB means", "R=${fmt4(r)}; G=${fmt4(g)}; B=${fmt4(b)}")
            }
        }
        kv("Final clipped channels", "R=${metric("nativeRawIsp.finalRedClippedPct")}% / G=${metric("nativeRawIsp.finalGreenClippedPct")}% / B=${metric("nativeRawIsp.finalBlueClippedPct")}%")
    }

    private fun StringBuilder.appendYuvSingleFrameObjectiveTruth() {
        val stats = parseSemicolonStats(eventValue("YUV Native Render", "Stats"))
        section("Single-Frame Objective Truth — YUV")
        if (stats.isEmpty()) {
            kv("YUV objective metrics", "not recorded")
            return
        }
        fun stat(key: String): String = stats[key] ?: "not recorded"
        kv("Default Camera2 color contract", stat("yuvDefaultCamera2ColorContract"))
        kv("Current BnCam conversion matrix", stat("yuvCurrentBncamConversionMatrix"))
        kv("Current BnCam conversion range", stat("yuvCurrentBncamConversionRange"))
        kv("Input samples", stat("yuvInputSignalSampleCount"))
        kv("Input Y mean / stddev", "${stat("yuvInputYMean")} / ${stat("yuvInputYStdDev")}")
        kv("Input U centered mean / stddev", "${stat("yuvInputUCenteredMean")} / ${stat("yuvInputUStdDev")}")
        kv("Input V centered mean / stddev", "${stat("yuvInputVCenteredMean")} / ${stat("yuvInputVStdDev")}")
        val yStd = stats.statDouble("yuvInputYStdDev")
        val uStd = stats.statDouble("yuvInputUStdDev")
        val vStd = stats.statDouble("yuvInputVStdDev")
        kv("Sampled Y variance", yStd?.let { fmt4(it * it) } ?: "not derivable")
        kv("Sampled U / V variance", if (uStd != null && vStd != null) {
            "${fmt4(uStd * uStd)} / ${fmt4(vStd * vStd)}"
        } else {
            "not derivable"
        })
        kv("Input chroma RMS from neutral", stat("yuvInputChromaRmsFromNeutral"))
        kv("Luma neighbour delta mean", stat("yuvInputLumaNeighbourDeltaMean"))
        kv("Chroma neighbour delta mean", stat("yuvInputChromaNeighbourDeltaMean"))
        kv("Neighbour delta semantics", stat("yuvInputNeighbourDeltaSemantics"))
        kv("Input Y p0.1 / p50 / p99.9", "${stat("yuvInputNativeYP0_1")} / ${stat("yuvInputNativeYP50")} / ${stat("yuvInputNativeYP99_9")}")
        kv("Input black / white clipped", "${stat("yuvInputBlackClippedFraction")} / ${stat("yuvInputWhiteClippedFraction")}")
        kv("Resolved Vulkan luma NR blend", stat("yuvResolvedGpuLumaNrBlend"))
        kv("Resolved Vulkan chroma NR blend", stat("yuvResolvedGpuChromaNrBlend"))
        kv("Resolved Vulkan luma/chroma protection", "${stat("yuvResolvedGpuLumaNrProtection")} / ${stat("yuvResolvedGpuChromaNrProtection")}")
        kv("YUV denoise applied", stat("yuvDenoiseApplied"))
        kv("YUV ISP backend", stat("yuvSingleFrameIspBackend"))
        kv("YUV GPU execution wall", "${stat("yuvSingleFrameGpuExecutionWallMs")} ms")
        kv("YUV GPU synchronization", "${stat("yuvSingleFrameGpuSyncMs")} ms")
        kv("YUV publication readback", "${stat("yuvSingleFramePublicationReadbackMs")} ms")
        kv("YUV JPEG encode", "${stat("yuvJpegEncodeMs")} ms")
        kv("Total native YUV", "${stat("totalNativeYuvMs")} ms")
    }

    private fun StringBuilder.appendVulkanSummary() {
        val export = lastVulkanExport ?: return
        section("Vulkan Runtime")
        val usefulPrefixes = listOf(
            "State:", "Selected device:", "Runtime initialized:", "Loader available:",
            "Instance creation count:", "Device creation count:", "In-flight submissions:",
            "Active production stages:", "Pipeline cache:", "VMA"
        )
        export.runtimeText.lineSequence().map { it.trim() }
            .filter { line -> usefulPrefixes.any { prefix -> line.startsWith(prefix) } }
            .distinct()
            .take(20)
            .forEach { line ->
                val split = line.split(':', limit = 2)
                if (split.size == 2) kv(split[0], split[1].trim()) else line(line)
            }
        val validationSummary = export.validationText.lineSequence().map { it.trim() }
            .filter { it.startsWith("Total messages:") || it.startsWith("Unique messages:") || it.startsWith("Dropped messages:") }
            .toList()
        if (validationSummary.isNotEmpty()) {
            section("Vulkan Validation")
            validationSummary.forEach { entry ->
                val split = entry.split(':', limit = 2)
                if (split.size == 2) kv(split[0], split[1].trim())
            }
        }
    }

    private fun automatedFindings(payload: DiagnosticPayload): List<Pair<String, String>> = buildList {
        if (!payload.captureSucceeded) add("ERROR" to "The capture did not complete successfully.")
        if (payload.fallbackUsed) add("WARN" to "A capture fallback was used: ${payload.fallbackReason}.")
        if (payload.totalShotTimeMs > 2_000L) add("WARN" to "Total capture time exceeded 2.0 s (${payload.totalShotTimeMs} ms).")
        if (payload.bufferCapacity > 0 && payload.framesBuffered <= 0) add("WARN" to "The capture buffer reported no buffered frames.")
        if (payload.jpegCreated && payload.jpegBytes <= 0) add("ERROR" to "JPEG publication was reported but the JPEG byte count is zero.")
        addAll(automatedIspFindings())
        if (isEmpty()) add("INFO" to "Capture completed without a high-confidence anomaly detected by the on-device rules.")
    }

    private fun automatedIspFindings(): List<Pair<String, String>> = buildList {
        val crop = eventValue("Renderer Pipeline", "Runtime RAW visible crop")
            ?: eventValue("ImageReader / Buffer", "Runtime RAW visible crop")
        if (!crop.isNullOrBlank()) {
            val match = Regex("L[:=]?(\\d+)\\s+T[:=]?(\\d+)\\s+W[:=]?(\\d+)\\s+H[:=]?(\\d+)", RegexOption.IGNORE_CASE).find(crop)
            if (match != null) {
                val left = match.groupValues[1].toIntOrNull() ?: 0
                val top = match.groupValues[2].toIntOrNull() ?: 0
                val width = match.groupValues[3].toIntOrNull() ?: 0
                val height = match.groupValues[4].toIntOrNull() ?: 0
                if ((left and 1) != 0 || (top and 1) != 0 || (width and 1) != 0 || (height and 1) != 0) {
                    add("CRITICAL" to "RAW visible geometry is not Bayer-cell aligned ($crop); CFA phase/channel interpretation may be corrupted.")
                }
            }
        }

        val yuvStats = parseSemicolonStats(eventValue("YUV Native Render", "Stats"))
        if (yuvStats["yuvDefaultCamera2ColorContract"] == "JFIF_REC601_FULL_RANGE" &&
            yuvStats["yuvCurrentBncamConversionRange"] == "LIMITED_16_235_240") {
            add("WARN" to "YUV conversion contract audit: BnCam currently decodes limited-range while the recorded default Camera2 contract is full-range; Phase 0 identified this as a correction candidate.")
        }

        val metrics = noiseModelMetrics() ?: return@buildList
        val width = metrics.optString("nativeRawIsp.demosaicInputWidth").toIntOrNull()
        val height = metrics.optString("nativeRawIsp.demosaicInputHeight").toIntOrNull()
        if (width != null && height != null && ((width and 1) != 0 || (height and 1) != 0)) {
            add("CRITICAL" to "Demosaic received an odd Bayer dimension ${width}x$height; a 2x2 CFA cell cannot remain phase-stable across that geometry.")
        }
        val ccmR = metrics.optString("nativeRawIsp.colorStageMeansCcmR").toDoubleOrNull()
        val ccmG = metrics.optString("nativeRawIsp.colorStageMeansCcmG").toDoubleOrNull()
        val ccmB = metrics.optString("nativeRawIsp.colorStageMeansCcmB").toDoubleOrNull()
        if (ccmR != null && ccmG != null && ccmB != null) {
            val rbReference = (ccmR + ccmB) * 0.5
            if (rbReference > 1e-6 && ccmG / rbReference < 0.35) {
                add("CRITICAL" to "Green collapses after CCM (R=${fmt4(ccmR)}, G=${fmt4(ccmG)}, B=${fmt4(ccmB)}); inspect CFA phase/crop before tuning WB or CCM.")
            }
        }
        val redClip = metrics.optString("nativeRawIsp.finalRedClippedPct").toDoubleOrNull() ?: 0.0
        val greenClip = metrics.optString("nativeRawIsp.finalGreenClippedPct").toDoubleOrNull() ?: 0.0
        val blueClip = metrics.optString("nativeRawIsp.finalBlueClippedPct").toDoubleOrNull() ?: 0.0
        if (maxOf(redClip, greenClip, blueClip) > 5.0) {
            add("WARN" to "Final channel clipping exceeds 5% (R=${fmt2(redClip)}%, G=${fmt2(greenClip)}%, B=${fmt2(blueClip)}%).")
        }
        val preResidual = metrics.metricDouble("nativeRawIsp.preDenoiseResidualEstimate")
        val postResidual = metrics.metricDouble("nativeRawIsp.postDenoiseResidualEstimate")
        val sharpenResidual = metrics.metricDouble("nativeRawIsp.postSharpenResidualEstimate")
        if (preResidual != null && postResidual != null && preResidual > 1.0e-12 && postResidual > preResidual * 1.05) {
            add("WARN" to "Measured RAW residual increased through denoise (${fmt4(preResidual)} -> ${fmt4(postResidual)}).")
        }
        if (postResidual != null && sharpenResidual != null && postResidual > 1.0e-12 && sharpenResidual > postResidual * 1.25) {
            add("WARN" to "Sharpening amplified measured residual by more than 25% (${fmt4(postResidual)} -> ${fmt4(sharpenResidual)}).")
        }

    }

    private fun noiseModelMetrics(): JSONObject? = noiseModelTraceJson
        ?.let { runCatching { JSONObject(it) }.getOrNull() }
        ?.optJSONObject("timing")
        ?.optJSONObject("runner")
        ?.optJSONObject("metrics")

    private fun severityRank(severity: String): Int = when (severity.uppercase(Locale.US)) {
        "CRITICAL", "ERROR" -> 0
        "WARN", "WARNING" -> 1
        else -> 2
    }

    private fun fmt2(value: Double): String = String.format(Locale.US, "%.2f", value)

    private fun fmt4(value: Double): String = String.format(Locale.US, "%.4f", value)

    private fun buildSummary(payload: DiagnosticPayload): String = buildString {
        header("BNCAM SHOT DEBUG")

        numberedSection(1, "Shot Overview")
        kv("Shot ID", payload.shotId)
        kv("Started", payload.startedAtStr)
        kv("Completed", payload.completedAtStr)
        kv("Capture succeeded", yesNo(payload.captureSucceeded))
        kv("Selected mode", payload.requestedModeLabel.ifBlank { payload.requestedMode })
        kv("Actual runner", payload.routeRunner)
        kv("Frame source", payload.bufferFormat)
        kv("Fallback used", yesNo(payload.fallbackUsed))
        if (payload.fallbackUsed || payload.fallbackReason != "none") kv("Fallback reason", payload.fallbackReason)
        if (payload.hardFailure || payload.failureReason != "none") kv("Failure reason", payload.failureReason)

        numberedSection(2, "Camera / Lens Truth")
        kv("Camera ID", payload.cameraId)
        kv("Lens name", payload.lensName)
        kv("Lens facing", payload.lensFacing)
        kv("Physical camera ID", payload.physicalCameraId)
        kv("Sensor orientation", if (payload.sensorOrientation >= 0) "${payload.sensorOrientation}°" else "unknown")
        kv("Output rotation", "${payload.targetRotation}°")
        frameEntries.firstOrNull { it.selectedAnchor }?.let { anchor ->
            kv("OIS logical result", anchor.oisLogicalMode)
            kv("OIS physical result", anchor.oisPhysicalMode)
            kv("OIS sample source", anchor.oisSampleSource)
            kv("OIS sample count", anchor.oisSampleCount)
            kv("OIS shift RMS", anchor.oisShiftRmsPx?.let { String.format(Locale.US, "%.4f px", it) } ?: "unavailable")
            kv("OIS shift peak", anchor.oisShiftPeakPx?.let { String.format(Locale.US, "%.4f px", it) } ?: "unavailable")
        }

        numberedSection(3, "Route Truth")
        kv("Profile", payload.profileName)
        kv("Profile ID", payload.profileId)
        kv("Requested mode", payload.requestedMode)
        kv("Requested mode label", payload.requestedModeLabel)
        kv("Preferred frame setting", payload.preferredFrameSetting)
        kv("Resolved route", payload.resolvedRoute)
        kv("Actual route", payload.actualRoute)
        kv("Runner", payload.routeRunner)
        writeEventsCompact("Metering Validation")

        numberedSection(4, "Buffer / Format Truth")
        kv("Buffer format", payload.bufferFormat)
        kv("Capture size", formatSize(payload.frameWidth, payload.frameHeight))
        kv("Output size", if (payload.outputWidth > 0 && payload.outputHeight > 0) formatSize(payload.outputWidth, payload.outputHeight) else "renderer defined")
        if (isMultiFrame(payload)) {
            kv("Buffer capacity", payload.bufferCapacity)
            kv("Frames buffered", payload.framesBuffered)
            kv("Frames requested", payload.framesRequested)
            kv("Frames eligible", payload.framesEligible)
            kv("Frames accepted", payload.framesAccepted)
            kv("Frames merged", payload.framesMerged)
            kv("Frames rejected", payload.framesRejected)
        } else {
            kv("Selected capture frame", if (payload.selectedAnchorIndex >= 0) "Frame ${payload.selectedAnchorIndex + 1}" else "not recorded")
        }
        writeEventsCompact("ImageReader / Buffer")

        numberedSection(5, "Output / Save Result")
        kv("Public filename", payload.publicFilename)
        kv("JPEG created", yesNo(payload.jpegCreated))
        kv("JPEG bytes", payload.jpegBytes)
        kv("Saved output path", payload.savedOutputPath)
        if (payload.hqJpegCreated || payload.hqSavedOutputPath != "not created") {
            kv("HQ merge JPEG", "${yesNo(payload.hqJpegCreated)} / ${payload.hqJpegBytes} bytes")
            kv("HQ merge path", payload.hqSavedOutputPath)
        }
        if (payload.dngCreated || payload.dngSavedOutputPath != "not created") {
            kv("DNG created", yesNo(payload.dngCreated))
            kv("DNG bytes", payload.dngBytes)
            kv("DNG path", payload.dngSavedOutputPath)
        }
        kv("Save location setting", payload.saveLocation)
        kv("EXIF/GPS added", yesNo(payload.gpsAdded))

        numberedSection(6, "Timing")
        kv("Total shot time", ms(payload.totalShotTimeMs))
        kv("Camera capture time", ms(payload.cameraCaptureTimeMs))
        if (isMultiFrame(payload)) kv("Merge time", ms(payload.mergeTimeMs))
        kv("Render / ISP time", ms(payload.renderTimeMs))
        kv("JPEG encode time", ms(payload.jpegCompressionTimeMs))
        kv("Save time", ms(payload.saveTimeMs))

        numberedSection(7, "Warnings / Errors")
        val uniqueWarningCount = buildWarningList(payload)
            .distinctBy { Triple(it.severity.uppercase(Locale.US), it.group, it.message) }
            .size
        kv("Unique findings", uniqueWarningCount)
        kv("Details", WARNINGS_FILE)
    }

    private fun buildActiveMode(payload: DiagnosticPayload): String = buildString {
        header("BNCAM ACTIVE MODE DEBUG")

        numberedSection(1, "Mode Identity")
        kv("Selected mode", payload.requestedModeLabel.ifBlank { payload.requestedMode })
        kv("Mode family", if (isMultiFrame(payload)) "Multi Frame" else "Single Frame")
        kv("Runner", payload.routeRunner)
        kv("Frame source", payload.bufferFormat)
        kv("Capture succeeded", yesNo(payload.captureSucceeded))
        kv("Debug scope", payload.debugScope)

        numberedSection(2, "Route Decision")
        kv("Requested mode", payload.requestedMode)
        kv("Resolved route", payload.resolvedRoute)
        kv("Actual route", payload.actualRoute)
        kv("Fallback used", yesNo(payload.fallbackUsed))
        if (payload.fallbackUsed || payload.fallbackReason != "none") {
            kv("Fallback target", payload.fallbackTarget)
            kv("Fallback reason", payload.fallbackReason)
        }
        kv("Route analysis", payload.routeAnalysis)

        numberedSection(3, "Frame Selection")
        kv("Base position", payload.basePosition)
        kv("Candidates analyzed", payload.candidatesAnalyzed)
        kv("Primary bias", payload.primaryBias)
        kv("Temporal bias", String.format(Locale.US, "%+.2f", payload.temporalBias))
        kv("Selected anchor", if (payload.selectedAnchorIndex >= 0) "Frame ${payload.selectedAnchorIndex + 1}" else "not recorded")
        kv("Anchor delta to shutter", String.format(Locale.US, "%+.3f ms", payload.selectedAnchorDeltaMs))
        kv("Anchor timing", payload.selectedAnchorTiming)
        kv("Anchor reason", payload.selectedAnchorReason)
        append(buildStreamlinedFrameTable())

        numberedSection(4, "Selection Rules")
        kv("Frame bias", payload.frameBias)
        kv("Accept all frames", yesNo(payload.acceptAllFrames))
        kv("Reject duplicates", yesNo(payload.rejectDupes))
        kv("Alignable only", yesNo(payload.alignableOnly))
        kv("Discard first frame", yesNo(payload.discardFirstFrame))
        kv("Prefer recent", yesNo(payload.preferRecent))
        kv("Ignore stale frames", yesNo(payload.ignoreStaleFrames))

        if (isMultiFrame(payload)) {
            numberedSection(5, "Merge Runtime")
            kv("Merge frame count setting", payload.mergeFrameCountSetting)
            kv("Sub-pixel alignment", yesNo(payload.mergeSubPixelSetting))
            kv("Linear interpolation", yesNo(payload.mergeLinearInterpolationSetting))
            kv("Alignment strictness", String.format(Locale.US, "%.3f", payload.mergeStrictnessSetting))
            kv("Max shift pixels", payload.mergeMaxShiftSetting)
            kv("Frames copied to RAM", payload.framesCopiedToRam)
            kv("Support frames for merge", payload.supportFramesForMerge)
            kv("Merge input bytes", payload.mergeInputBytes)
            kv("Merge output created", yesNo(payload.mergeOutputCreated))
            kv("Merge output bytes", payload.mergeOutputBytes)
            kv("Merged output path", payload.mergedOutputPath)
        }

        numberedSection(if (isMultiFrame(payload)) 6 else 5, "Effective Settings")
        writeEventsOrEmpty("Lens Hardware Settings", "No lens hardware settings were recorded.")
        writeEventsOrEmpty("Resolved ISP Settings", "No resolved ISP settings were recorded.")
        writeEventsOrEmpty("Quality Config", "No quality config was recorded.")
        writeEventsOrEmpty("Common Post-Render", "No common post-render runtime mapping was recorded.")
        writeEventsOrEmpty("Output Encode", "No output encode runtime mapping was recorded.")

        numberedSection(if (isMultiFrame(payload)) 7 else 6, "Processing Path")
        when (payload.bufferFormat) {
            "RAW10" -> {
                kv("Route", if (isMultiFrame(payload)) "RAW10 Bayer burst -> native merge/ISP -> JPEG" else "RAW10 Bayer anchor -> native ISP -> JPEG")
                kv("Merge domain", if (isMultiFrame(payload)) "Bayer before demosaic" else "single RAW master")
                kv("OpenCV available", yesNo(payload.openCvUsed))
            }
            "RAW_SENSOR" -> {
                kv("Route", if (isMultiFrame(payload)) "RAW_SENSOR/RAW16 burst -> native merge/ISP -> JPEG" else "RAW_SENSOR/RAW16 anchor -> native ISP -> JPEG")
                kv("Merge domain", if (isMultiFrame(payload)) "Bayer before demosaic" else "single RAW master")
                kv("OpenCV available", yesNo(payload.openCvUsed))
            }
            "YUV_420_888" -> {
                kv("Route", if (isMultiFrame(payload)) "YUV HardwareBuffer burst -> native merge/JPEG" else "YUV HardwareBuffer anchor -> native JPEG")
                kv("Processing domain", "YUV/native bridge")
                kv("OpenCV available", yesNo(payload.openCvUsed))
            }
            else -> kv("Route", "Unknown frame source; inspect Pipeline debug.txt")
        }
        kv("Analysis source", payload.analysisSource)
    }

    private fun buildFrameAnalysis(payload: DiagnosticPayload, includeFullDetail: Boolean): String = buildString {
        header("BNCAM FRAME ANALYSIS")

        numberedSection(1, "Frame Selection Summary")
        kv("Frame source", payload.bufferFormat)
        kv("Entries written", frameEntries.size)
        kv("Frames accepted", payload.framesAccepted)
        kv("Frames rejected", payload.framesRejected)
        kv("Selected anchor", if (payload.selectedAnchorIndex >= 0) "Frame ${payload.selectedAnchorIndex + 1}" else "not recorded")
        kv("Analysis source", payload.analysisSource)
        kv("Score type", scoreTypeLabel(payload.analysisSource, payload.bufferFormat))
        line("  Delta: negative = before shutter, positive = after shutter. Scores: 0.0000 to 1.0000, higher is better.")

        numberedSection(2, "Pruning Summary")
        kv("Initial available", payload.framesEligible)
        kv("Rejected invalid", payload.prunedInvalid)
        kv("Rejected first frame", payload.prunedFirstFrame)
        kv("Rejected stale", payload.prunedStale)
        kv("Rejected duplicates", payload.prunedDupes)

        numberedSection(3, "Frame Scores")
        val entriesToWrite = frameEntries
        if (entriesToWrite.isEmpty()) {
            line("  No frame analysis entries were recorded. The runner did not provide per-frame scoring for this shot.")
        } else {
            entriesToWrite.forEach { entry ->
                line("  Frame ${entry.index + 1}: ${if (entry.accepted) "ACCEPTED" else "REJECTED"}${if (entry.selectedAnchor) " / ANCHOR" else ""}")
                kv("Delta to shutter", String.format(Locale.US, "%+.3f ms", entry.deltaToShutterMs))
                kv("Overall score", formatScore(entry.overallScore))
                kv("Sharpness", formatScore(entry.sharpnessScore))
                kv("Motion", formatScore(entry.motionScore))
                kv("EV", formatScore(entry.evScore))
                kv("Alignability", formatScore(entry.alignabilityScore))
                kv("Sync", formatScore(entry.syncScore))
                kv("Decision", entry.decisionReason)
                if (!entry.accepted) kv("Rejection reason", entry.rejectionReason)
                if (entry.selectedAnchor) {
                    line("")
                    line("  Sensor Authority / Provenance")
                    kv("Sensor Authority ID", entry.sensorAuthorityId)
                    kv("Camera Device ID", entry.cameraDeviceId)
                    kv("Physical Camera ID", entry.physicalCameraId)
                    kv("Authority Type", sensorAuthorityTypeLabel(entry))
                    kv("RAW Source ID", entry.rawSourceId)
                    kv("CaptureResult Source ID", entry.captureResultSourceId)
                    kv("Characteristics Source ID", entry.characteristicsSourceId)
                    kv("Calibration Source ID", entry.calibrationSourceId)
                    kv("Sensor Authority Frame Number", entry.sensorAuthorityFrameNumber)
                    kv("Capture Sequence ID", entry.captureSequenceId)
                    kv("Sensor Metadata Timestamp Ns", entry.sensorMetadataTimestampNs)
                    kv("RAW/Metadata Timestamp Match", entry.rawMetadataTimestampMatch)
                    kv("Logical Metadata Fallback", logicalMetadataFallbackLabel(entry))
                    kv("Foreign Sensor Metadata Used", foreignSensorMetadataLabel(entry))
                    kv("Fallback Used", entry.sensorAuthorityFallbackUsed)
                    kv("Raw Processing Safe", entry.rawProcessingSafe)
                    kv("Sensor Authority Status", entry.sensorAuthorityStatus)
                    line("")
                    line("  Uniform Sensor Metadata")
                    if (entry.sensorMetadataAuditLines.isEmpty()) {
                        line("  UNAVAILABLE | value=UNAVAILABLE; source=UNAVAILABLE; validity=UNAVAILABLE; reason=SENSOR_METADATA_AUDIT_NOT_RECORDED")
                    } else {
                        entry.sensorMetadataAuditLines.forEach { auditLine -> line("  $auditLine") }
                    }
                }
                if (includeFullDetail) {
                    kv("Timestamp ns", entry.timestampNs)
                    kv("Shutter relation", entry.shutterRelation)
                    kv("Exposure", formatExposure(entry.exposureTimeNs))
                    kv("ISO", entry.iso)
                    kv("candidateTimestampNs", entry.candidateTimestampNs)
                    kv("candidateDeltaMs", String.format(Locale.US, "%+.3f", entry.candidateDeltaMs))
                    kv("candidateIso", entry.candidateIso)
                    kv("candidateExposureNs", entry.candidateExposureNs)
                    kv("candidateAeState", entry.candidateAeState)
                    kv("candidateAwbState", entry.candidateAwbState)
                    kv("candidateFocusState", entry.candidateFocusState)
                    kv("candidateMetadataComplete", entry.candidateMetadataComplete)
                    kv("candidateAgeAbsMs", String.format(Locale.US, "%.3f", entry.candidateAgeAbsMs))
                    kv("candidateFreshEnoughForSelection", entry.candidateFreshEnoughForSelection)
                    kv("candidateRejectedForStaleSelection", entry.candidateRejectedForStaleSelection)
                    kv("scoreComponentsUsed", entry.scoreComponentsUsed)
                    kv("pipelineGeneration", entry.pipelineGeneration)
                    kv("controlRequestEpoch", entry.controlRequestEpoch)
                    kv(
                        "requestProvenanceStatus",
                        entry.requestProvenanceStatus
                    )
                    if (!entry.selectedAnchor) {
                        kv("Sensor Authority ID", entry.sensorAuthorityId)
                        kv("Camera Device ID", entry.cameraDeviceId)
                        kv("Physical Camera ID", entry.physicalCameraId)
                        kv("Authority Type", sensorAuthorityTypeLabel(entry))
                        kv("RAW Source ID", entry.rawSourceId)
                        kv("CaptureResult Source ID", entry.captureResultSourceId)
                        kv("Characteristics Source ID", entry.characteristicsSourceId)
                        kv("Calibration Source ID", entry.calibrationSourceId)
                        kv("Sensor Authority Frame Number", entry.sensorAuthorityFrameNumber)
                        kv("Capture Sequence ID", entry.captureSequenceId)
                        kv("Sensor Metadata Timestamp Ns", entry.sensorMetadataTimestampNs)
                        kv("RAW/Metadata Timestamp Match", entry.rawMetadataTimestampMatch)
                        kv("Logical Metadata Fallback", logicalMetadataFallbackLabel(entry))
                        kv("Foreign Sensor Metadata Used", foreignSensorMetadataLabel(entry))
                        kv("Fallback Used", entry.sensorAuthorityFallbackUsed)
                        kv("Raw Processing Safe", entry.rawProcessingSafe)
                        kv("Sensor Authority Status", entry.sensorAuthorityStatus)
                    }
                    kv("imageArrivalElapsedNs", entry.imageArrivalElapsedNs)
                    kv("metadataArrivalElapsedNs", entry.metadataArrivalElapsedNs)
                    kv("pairCompleteElapsedNs", entry.pairCompleteElapsedNs)
                    kv(
                        "imageDeliveryLagMs",
                        entry.imageDeliveryLagMs?.let {
                            String.format(Locale.US, "%.3f", it)
                        } ?: "unavailable_clock_domain"
                    )
                    kv(
                        "metadataDeliveryLagMs",
                        entry.metadataDeliveryLagMs?.let {
                            String.format(Locale.US, "%.3f", it)
                        } ?: "unavailable_clock_domain"
                    )
                    kv(
                        "pairCompletionLagMs",
                        entry.pairCompletionLagMs?.let {
                            String.format(Locale.US, "%.3f", it)
                        } ?: "unavailable_clock_domain"
                    )
                    kv("timestampSource", entry.timestampSource)
                    kv(
                        "sensorTimestampComparableToElapsedRealtime",
                        entry.sensorTimestampComparableToElapsedRealtime
                    )
                    kv(
                        "shutterDeltaClockDomainsComparable",
                        entry.shutterDeltaClockDomainsComparable
                    )
                    kv("preScorePruneResult", entry.preScorePruneResult)
                    kv("preScorePruneReason", entry.preScorePruneReason)
                    kv("oisLogicalMode", entry.oisLogicalMode)
                    kv("oisPhysicalMode", entry.oisPhysicalMode)
                    kv("oisSampleSource", entry.oisSampleSource)
                    kv("oisSampleCount", entry.oisSampleCount)
                    kv("oisShiftRmsPx", entry.oisShiftRmsPx?.let { String.format(Locale.US, "%.4f", it) } ?: "unavailable")
                    kv("oisShiftPeakPx", entry.oisShiftPeakPx?.let { String.format(Locale.US, "%.4f", it) } ?: "unavailable")
                    kv("Format", "${entry.format} / ${entry.width} x ${entry.height}")
                    kv("Metadata valid", yesNo(entry.metadataValid))
                    kv("Stale", yesNo(entry.stale))
                    kv("Duplicate", yesNo(entry.duplicate))
                    kv("Analysis source", entry.analysisSource)
                }
                appendLine()
            }
        }
    }

    private fun buildWarnings(payload: DiagnosticPayload): String = buildString {
        header("BNCAM WARNINGS & ERRORS")

        val allWarnings = buildWarningList(payload)
        numberedSection(1, "Health Summary")
        kv("Capture succeeded", yesNo(payload.captureSucceeded))
        kv("Hard failure", yesNo(payload.hardFailure))
        kv("Fallback used", yesNo(payload.fallbackUsed))
        kv("Warning/error count", allWarnings.size)

        numberedSection(2, "Warnings By Source")
        if (allWarnings.isEmpty()) {
            line("  No warnings or errors were recorded.")
        } else {
            allWarnings.groupBy { it.group }.toSortedMap().forEach { (group, entries) ->
                appendLine()
                line("  $group")
                entries.forEach { entry -> line("    ${entry.severity}: ${entry.message}") }
            }
        }
    }

    private fun buildPipelineDebug(payload: DiagnosticPayload): String = buildString {
        header("BNCAM PIPELINE DEBUG")

        numberedSection(1, "Shot Overview")
        kv("Runner", payload.routeRunner)
        kv("Selected mode", payload.requestedModeLabel.ifBlank { payload.requestedMode })
        kv("Frame source", payload.bufferFormat)
        kv("Capture succeeded", yesNo(payload.captureSucceeded))
        kv("Hard failure", yesNo(payload.hardFailure))
        if (payload.failureReason != "none") kv("Failure reason", payload.failureReason)

        numberedSection(2, "Route Truth")
        kv("Requested mode", payload.requestedMode)
        kv("Requested mode label", payload.requestedModeLabel)
        kv("Resolved route", payload.resolvedRoute)
        kv("Actual route", payload.actualRoute)
        kv("Profile ID", payload.profileId)
        kv("Preferred frame setting", payload.preferredFrameSetting)
        kv("Debug scope", payload.debugScope)

        numberedSection(3, "Camera / Lens Truth")
        kv("Camera ID", payload.cameraId)
        kv("Lens", payload.lensName)
        kv("Physical camera ID", payload.physicalCameraId)
        kv("Lens facing", payload.lensFacing)
        kv("Sensor orientation", if (payload.sensorOrientation >= 0) "${payload.sensorOrientation}°" else "unknown")
        kv("Output rotation", "${payload.targetRotation}°")
        kv("Metering Style", payload.meteringStyle)
        kv("EV Offset", payload.evOffset)
        kv("AE Regions Requested", payload.aeRegionsRequested)
        kv("AE Regions Result", payload.aeRegionsResult)
        kv("EV Comp Requested", payload.evCompRequested)
        kv("EV Comp Result", payload.evCompResult)
        kv("AE State Before Capture", payload.aeStateBeforeCapture)
        kv("AE State At Capture", payload.aeStateAtCapture)
        kv("Sensor Exposure Time", payload.sensorExposureTime)
        kv("Sensor Sensitivity", payload.sensorSensitivity)
        kv("Metering Applied to Repeating Request", payload.meteringAppliedToRepeatingRequestStatus.ifBlank { yesNo(payload.meteringAppliedToRepeatingRequest) })
        kv("Metering Applied to Still Request", payload.meteringAppliedToStillRequestStatus.ifBlank { yesNo(payload.meteringAppliedToStillRequest) })
        writeEventsCompact("Lens Hardware Settings")

        numberedSection(4, "Buffer / Format Truth")
        kv("Buffer format", payload.bufferFormat)
        kv("Capture size", formatSize(payload.frameWidth, payload.frameHeight))
        kv("Output size", if (payload.outputWidth > 0 && payload.outputHeight > 0) formatSize(payload.outputWidth, payload.outputHeight) else "renderer defined")
        kv("Buffer capacity", payload.bufferCapacity)
        if (payload.bufferFormat == "RAW10" || payload.bufferFormat == "RAW_SENSOR") {
            val rawRuntime = com.bncam.core.runtime.RawPipelineRuntimeOwner.getProfile()
            if (rawRuntime != null) {
                val geometry = rawRuntime.geometry
                kv("Runtime RAW buffer geometry", "${geometry.bufferWidth}x${geometry.bufferHeight}")
                kv(
                    "Runtime RAW visible crop",
                    "L:${geometry.cropLeft} T:${geometry.cropTop} W:${geometry.cropWidth} H:${geometry.cropHeight}"
                )
                kv("Runtime RAW row stride", geometry.rowStride)
                kv("Runtime RAW pixel stride", geometry.pixelStride)
                kv("Runtime RAW visible aspect", String.format(Locale.US, "%.6f", geometry.visibleAspectRatio))
            }
        }
        kv("Frames buffered", payload.framesBuffered)
        kv("Frames requested", payload.framesRequested)
        kv("Frames eligible", payload.framesEligible)
        kv("Frames copied to RAM", payload.framesCopiedToRam)
        if (isMultiFrame(payload)) kv("Support frames for merge", payload.supportFramesForMerge)
        append(buildStreamlinedFrameTable())

        numberedSection(5, "Pipeline Contract")
        writeCalibrationContract(payload)

        numberedSection(6, "Effective Settings")
        writeEventsOrEmpty("Resolved ISP Settings", "No resolved ISP settings were recorded.")
        writeEventsOrEmpty("Quality Config", "No quality config was recorded.")
        writeEventsOrEmpty("Common Post-Render", "No common post-render runtime mapping was recorded.")
        writeEventsOrEmpty("Output Encode", "No output encode runtime mapping was recorded.")

        if (isMultiFrame(payload)) {
            numberedSection(7, "Merge / Alignment")
            kv("Merge triggered", yesNo(payload.mergeTriggered))
            kv("Alignment triggered", yesNo(payload.alignmentTriggered))
            kv("Merge frame count", payload.mergeFrameCountSetting)
            kv("Sub-pixel alignment", yesNo(payload.mergeSubPixelSetting))
            kv("Linear interpolation", yesNo(payload.mergeLinearInterpolationSetting))
            kv("Alignment strictness", String.format(Locale.US, "%.3f", payload.mergeStrictnessSetting))
            kv("Max shift pixels", payload.mergeMaxShiftSetting)
            kv("Merge output created", yesNo(payload.mergeOutputCreated))
            kv("Merge output bytes", payload.mergeOutputBytes)
            kv("Merge input bytes", payload.mergeInputBytes)
            writeEventsCompact("Merge Config")
            writeEventsCompact("YUV Native Render")
            writeEventsCompact("RAW10 Native Merge")
            writeEventsCompact("RAW_SENSOR Native Merge")
        }

        numberedSection(if (isMultiFrame(payload)) 8 else 7, "Renderer / Native Events")
        writeEventsOrEmpty("Renderer Pipeline", "No renderer-pipeline events were recorded.")
        writeEventsCompact("Master RAW Frame")
        writeEventsCompact("Master RAW16 ISP Render")
        writeEventsCompact("DNG Merge / RAW16 Master")
        writeEventsCompact("DNG Export")
        writeEventsCompact("YUV Native Render")
        writeEventsCompact("RAW10 Native Merge")
        writeEventsCompact("RAW_SENSOR Native Merge")

        numberedSection(if (isMultiFrame(payload)) 9 else 8, "Timing")
        kv("Camera capture time", ms(payload.cameraCaptureTimeMs))
        if (isMultiFrame(payload)) kv("Merge time", ms(payload.mergeTimeMs))
        kv("Render / ISP time", ms(payload.renderTimeMs))
        kv("JPEG encode time", ms(payload.jpegCompressionTimeMs))
        kv("Save time", ms(payload.saveTimeMs))
        kv("Debug write time", eventValue("Timing Breakdown", "Debug write time") ?: ms(payload.debugWriteTimeMs))
        kv("Total processing", ms(payload.totalShotTimeMs))
        writeEventsCompact("Timing Breakdown")

        numberedSection(if (isMultiFrame(payload)) 10 else 9, "Output / Recovery")
        kv("JPEG created", yesNo(payload.jpegCreated))
        kv("JPEG bytes", payload.jpegBytes)
        kv("Saved output path", payload.savedOutputPath)
        kv("DNG created", yesNo(payload.dngCreated))
        if (payload.dngCreated || payload.dngSavedOutputPath != "not created") kv("DNG path", payload.dngSavedOutputPath)
        kv("Fallback used", yesNo(payload.fallbackUsed))
        if (payload.fallbackUsed || payload.fallbackReason != "none") {
            kv("Fallback target", payload.fallbackTarget)
            kv("Fallback reason", payload.fallbackReason)
        }
        if (payload.hardFailure || payload.failureReason != "none") kv("Failure reason", payload.failureReason)
    }

    private fun StringBuilder.writeCalibrationContract(payload: DiagnosticPayload) {
        val isYuv = payload.bufferFormat == "YUV_420_888"

        if (isYuv) {
            kv("Pipeline name", "YUV Native Render Route")
            kv("Buffer route", payload.bufferFormat)
            kv("RAW Calibration Applicable", "no")
            kv("Not applicable reason", "YUV is display-ready. RAW corrections (Black/White level, CFA, RAW Noise) are already applied by the hardware ISP.")
            writeEventsCompact("YUV Native Render")
            return
        }

        kv("Pipeline name", "RAW Native ISP Route")
        kv("Buffer route", payload.bufferFormat)

        val blackSource: String = firstEventValue("Sensor Calibration" to "Black Level Source", "Sensor Calibration Detail" to "Black Level Source", "Master RAW Frame" to "Black Level Source") ?: "not recorded"
        val blackApplied: String = firstEventValue("Sensor Calibration" to "Black Subtraction Applied", "Sensor Calibration Detail" to "Black Subtraction Applied", "Master RAW Frame" to "Black Level Applied") ?: "not recorded"
        val blackDomain: String = firstEventValue("Sensor Calibration" to "Applied Black Level Domain", "Sensor Calibration Detail" to "Applied Black Level Domain", "Master RAW Frame" to "Black Level Domain") ?: "not recorded"

        val whiteSource: String = firstEventValue("Sensor Calibration" to "White Level Source", "Sensor Calibration Detail" to "White Level Source", "Master RAW Frame" to "White Level Source") ?: "not recorded"
        val whiteApplied: String = firstEventValue("Sensor Calibration" to "White Level Applied", "Sensor Calibration Detail" to "White Level Applied", "Master RAW Frame" to "White Level Applied") ?: "not recorded"
        val whiteDomain: String = firstEventValue("Sensor Calibration" to "Applied White Level Domain", "Sensor Calibration Detail" to "Applied White Level Domain", "Master RAW Frame" to "White Level Domain") ?: "not recorded"

        val colorSource: String = firstEventValue("Sensor Calibration" to "Color Matrix Source", "Sensor Calibration Detail" to "Color Matrix Source", "Master RAW Frame" to "Color Matrix Source") ?: "not recorded"
        val colorApplied: String = firstEventValue("Sensor Calibration" to "Color Matrix Applied", "Sensor Calibration Detail" to "Color Matrix Applied", "Master RAW Frame" to "Color Matrix Applied") ?: "not recorded"

        val wbSource: String = firstEventValue("Sensor Calibration" to "WB Source", "Sensor Calibration Detail" to "WB Source", "Master RAW Frame" to "WB Source") ?: "not recorded"
        val wbApplied: String = firstEventValue("Sensor Calibration" to "WB Applied", "Sensor Calibration Detail" to "WB Applied", "Master RAW Frame" to "WB Applied") ?: "not recorded"

        val noiseSource: String = firstEventValue("Sensor Calibration" to "Sensor Noise Profile Source", "Sensor Calibration Detail" to "Sensor Noise Profile Source", "Master RAW Frame" to "Noise Profile Source") ?: "not recorded"
        val noisePresent: String = firstEventValue("Sensor Calibration" to "Sensor Noise Profile Present", "Sensor Calibration Detail" to "Sensor Noise Profile Present", "Master RAW Frame" to "Noise Profile Present") ?: "not recorded"
        val noiseApplied: String = firstEventValue("Sensor Calibration" to "Sensor Noise Profile Applied", "Sensor Calibration Detail" to "Sensor Noise Profile Applied", "Master RAW Frame" to "Noise Profile Applied") ?: "not recorded"

        val rawBlackLevels: String = firstEventValue("Sensor Calibration" to "Applied Black Levels", "Sensor Calibration Detail" to "Applied Black Levels", "Master RAW Frame" to "Applied Black Levels") ?: "not recorded"
        val rawWhiteLevel: String = firstEventValue("Sensor Calibration" to "Applied White Level", "Sensor Calibration Detail" to "Applied White Level", "Master RAW Frame" to "Applied White Level") ?: "not recorded"
        val rawColorMatrix: String = firstEventValue("Sensor Calibration" to "Color Matrix Values", "Sensor Calibration Detail" to "Color Matrix Values", "Master RAW Frame" to "Color Matrix Values") ?: "not recorded"

        kv("Black level source", blackSource)
        kv("Black level applied yes/no", blackApplied)
        kv("Black level applied domain", blackDomain)

        kv("White level source", whiteSource)
        kv("White level applied yes/no", whiteApplied)
        kv("White level applied domain", whiteDomain)

        kv("Color matrix source", colorSource)
        kv("Color matrix applied yes/no", colorApplied)

        kv("WB source", wbSource)
        kv("WB applied yes/no", wbApplied)

        kv("Noise profile source", noiseSource)
        kv("Noise profile present yes/no", noisePresent)
        kv("Noise profile applied yes/no", noiseApplied)

        kv("Applied Black Levels", rawBlackLevels)
        kv("Applied White Level", rawWhiteLevel)
        kv("Color Matrix Values", rawColorMatrix)

        // Native Summary (Data directly from JNI return payload)
        appendLine()
        line("  Native Calibration Summary (from C++):")
        val nHasBlack: String = firstEventValue("Native Calibration" to "hasBlackLevel") ?: "not recorded"
        val nHasWhite: String = firstEventValue("Native Calibration" to "hasWhiteLevel") ?: "not recorded"
        val nHasColor: String = firstEventValue("Native Calibration" to "hasColorMatrix") ?: "not recorded"
        val nHasWb: String = firstEventValue("Native Calibration" to "hasWbGains") ?: "not recorded"
        val nHasNoise: String = firstEventValue("Native Calibration" to "hasNoiseProfile") ?: "not recorded"
        val nCalApplied: String = firstEventValue("Native Calibration" to "calibrationApplied") ?: "not recorded"
        val nNoiseApplied: String = firstEventValue("Native Calibration" to "noiseProfileApplied") ?: "not recorded"
        val nWarnings: String = firstEventValue("Native Calibration" to "calibrationWarnings") ?: "none"

        kv("  hasBlackLevel", nHasBlack)
        kv("  hasWhiteLevel", nHasWhite)
        kv("  hasColorMatrix", nHasColor)
        kv("  hasWbGains", nHasWb)
        kv("  hasNoiseProfile", nHasNoise)
        kv("  calibrationApplied", nCalApplied)
        kv("  noiseProfileApplied", nNoiseApplied)
        kv("  calibrationWarnings", nWarnings)
    }

    private fun buildWarningList(payload: DiagnosticPayload): List<WarningDebugEntry> {
        val allWarnings = warnings.toMutableList()
        if (payload.jpegBytes <= 0) allWarnings.add(WarningDebugEntry("Capture", "JPEG bytes are zero.", "WARN"))
        if (!payload.captureSucceeded) allWarnings.add(WarningDebugEntry("Capture", "Capture failed: ${payload.failureReason}", "ERROR"))
        if (payload.fallbackUsed && payload.fallbackReason != "none") allWarnings.add(WarningDebugEntry("Fallbacks", payload.fallbackReason, "INFO"))
        return allWarnings
    }

    private fun eventsFor(group: String): List<PipelineDebugEntry> = eventsLatestFor(group)

    private fun eventValue(group: String, key: String): String? =
        pipelineEvents.lastOrNull { it.group == group && it.key == key }?.value?.takeIf { it.isNotBlank() }

    private fun firstEventValue(vararg keys: Pair<String, String>): String? {
        keys.forEach { (group, key) ->
            val value = eventValue(group, key)
            if (value != null) return value
        }
        return null
    }

    private fun StringBuilder.writeEventsCompact(group: String) {
        val events = eventsFor(group)
        if (events.isEmpty()) return
        appendLine()
        line("  $group")
        events.forEach { event -> kv(event.key, event.value) }
    }

    private fun StringBuilder.writeEventsOrEmpty(group: String, emptyMessage: String) {
        val events = eventsFor(group)
        appendLine()
        line("  $group")
        if (events.isEmpty()) {
            line("    $emptyMessage")
        } else {
            events.forEach { event -> kv(event.key, event.value) }
        }
    }

    // =========================================================================
    // LAYOUT HELPER FUNCTIONS (READABLE DEBUG FILES)
    // =========================================================================

    private fun StringBuilder.header(title: String) {
        appendLine(phoneSectionTitle(title))
        appendLine()
    }

    private fun StringBuilder.section(title: String) {
        appendLine()
        appendLine(phoneSectionTitle(title))
    }

    private fun StringBuilder.numberedSection(number: Int, title: String) {
        section("$number. $title")
    }

    private fun formatTitle(title: String): String = phoneSectionTitle(title)

    private fun phoneSectionTitle(title: String): String {
        val clean = title.trim().uppercase(Locale.US)
        val innerWidth = (PHONE_LINE_WIDTH - 2).coerceAtLeast(clean.length + 2)
        val availableDots = (innerWidth - clean.length - 2).coerceAtLeast(2)
        val leftDots = availableDots / 2
        val rightDots = availableDots - leftDots
        return "[${".".repeat(leftDots)} $clean ${".".repeat(rightDots)}]"
    }

    private fun sensorAuthorityTypeLabel(entry: FrameAnalysisDebugEntry): String = when (entry.physicalCameraId) {
        "STANDALONE" -> "STANDALONE"
        "UNAVAILABLE", "" -> "UNAVAILABLE"
        else -> "PHYSICAL_CHILD"
    }

    private fun logicalMetadataFallbackLabel(entry: FrameAnalysisDebugEntry): String = when {
        !entry.sensorAuthorityFallbackUsed -> "false"
        entry.sensorAuthorityStatus == "LOGICAL_METADATA_FALLBACK_FORBIDDEN" -> "true"
        else -> "unknown"
    }

    private fun foreignSensorMetadataLabel(entry: FrameAnalysisDebugEntry): String = when {
        !entry.sensorAuthorityFallbackUsed -> "false"
        entry.sensorAuthorityStatus == "FOREIGN_SENSOR_METADATA_FORBIDDEN" -> "true"
        else -> "unknown"
    }

    private fun StringBuilder.kv(key: String, value: Any?) {
        val label = key.trim().removeSuffix(":")
        val rendered = value?.toString()?.ifBlank { "none" } ?: "unknown"
        val valueWidth = (PHONE_LINE_WIDTH - PHONE_VALUE_COLUMN).coerceAtLeast(18)
        val chunks = rendered.chunked(valueWidth).ifEmpty { listOf("none") }
        if (label.length <= PHONE_LABEL_WIDTH) {
            val dots = ".".repeat((PHONE_VALUE_COLUMN - label.length).coerceAtLeast(2))
            appendLine("$label$dots${chunks.first()}")
        } else {
            appendLine(label)
            appendLine("${".".repeat(PHONE_VALUE_COLUMN)}${chunks.first()}")
        }
        chunks.drop(1).forEach { chunk ->
            appendLine("${".".repeat(PHONE_VALUE_COLUMN)}$chunk")
        }
    }

    private fun StringBuilder.line(value: String) {
        appendLine(value)
    }

    private fun yesNo(value: Boolean): String = if (value) "yes" else "no"
    private fun ms(value: Long): String = "$value ms"
    private fun formatSize(width: Int, height: Int): String = if (width > 0 && height > 0) "$width x $height" else "unknown"
    private fun isMultiFrame(payload: DiagnosticPayload): Boolean = payload.routeRunner.contains("MultiFrame", ignoreCase = true) || payload.requestedMode.contains("MULTI", ignoreCase = true) || payload.requestedModeLabel.contains("Multi", ignoreCase = true)
    private fun isRaw(payload: DiagnosticPayload): Boolean = payload.bufferFormat == "RAW10" || payload.bufferFormat == "RAW_SENSOR"

    private fun formatScore(value: Double): String = String.format(Locale.US, "%.4f", value.coerceIn(0.0, 1.0))

    private fun formatExposure(exposureTimeNs: Long): String {
        if (exposureTimeNs <= 0L) return "unknown"
        val exposureMs = exposureTimeNs / 1_000_000.0
        return String.format(Locale.US, "%.3f ms", exposureMs)
    }

    private fun scoreTypeLabel(analysisSource: String, format: String): String {
        return when {
            analysisSource.contains("PROXY", ignoreCase = true) -> "Metadata/proxy scoring. Useful for frame ordering; not a full pixel-quality metric."
            format == "YUV_420_888" -> "Native YUV route scoring. Pixel scoring may be delegated or simplified."
            format == "RAW10" -> "RAW10 native/proxy scoring before full per-frame Bayer metrics are exposed."
            format == "RAW_SENSOR" -> "RAW_SENSOR native/proxy scoring before full per-frame Bayer metrics are exposed."
            else -> "Runner-provided scoring."
        }
    }

    private fun StringBuilder.aiAnalysis(topic: String, success: Boolean, format: String, warningsCount: Int) {
        appendLine()
        appendLine(formatTitle("Debug Takeaway"))
        val line = when (topic) {
            "vendor_echo" -> if (success) "Vendor tag request/echo looked healthy." else "Vendor tag request/echo had missing or rejected entries."
            "summary" -> if (success && warningsCount == 0) "Capture completed without recorded warnings." else "Capture completed with warnings or failed; inspect Warnings.txt first."
            "mode" -> "Active processing path: $format."
            "frames" -> "Frame analysis is runner-provided and stays in this text file."
            else -> "Debug data recorded."
        }
        appendLine("  $line")
        appendLine()
    }

    private fun buildStreamlinedFrameTable(): String = buildString {
        appendLine()
        line("  Streamlined Frame Table:")
        line("  Frame | Sharpness | Motion  | EV      | Align   | Delta (ms) | Decision Summary")
        line("  " + "-".repeat(78))
        if (frameEntries.isEmpty()) {
            line("  No frame analysis entries recorded.")
        } else {
            frameEntries.forEach { entry ->
                val marker = if (entry.selectedAnchor) "*" else " "
                val indexStr = String.format(Locale.US, "%-4d%s", entry.index + 1, marker)
                val sharpnessStr = String.format(Locale.US, "%.4f", entry.sharpnessScore)
                val motionStr = String.format(Locale.US, "%.4f", entry.motionScore)
                val evStr = String.format(Locale.US, "%.4f", entry.evScore)
                val alignStr = String.format(Locale.US, "%.4f", entry.alignabilityScore)
                val deltaStr = String.format(Locale.US, "%+10.3f", entry.deltaToShutterMs)
                val decisionStr = if (entry.selectedAnchor) {
                    "ACCEPTED_ANCHOR / ${entry.decisionReason}"
                } else {
                    "REJECTED / ${entry.rejectionReason}"
                }
                line(String.format(Locale.US, "  %-5s | %-9s | %-7s | %-7s | %-7s | %-10s | %s",
                    indexStr, sharpnessStr, motionStr, evStr, alignStr, deltaStr, decisionStr))
            }
        }
        appendLine()
    }

    // Helper om primitieve arrays netjes te printen
    private fun Any?.contentToStringSafe(): String {
        if (this == null) return "null"
        return when (this) {
            is IntArray -> this.contentToString()
            is FloatArray -> this.contentToString()
            is ByteArray -> this.contentToString()
            is Array<*> -> this.contentToString()
            else -> this.toString()
        }
    }


    companion object {
        private const val TRANSIENT_STATE_PREFIX = ".capture_state_"
        private const val PHONE_LINE_WIDTH = 54
        private const val PHONE_LABEL_WIDTH = 22
        private const val PHONE_VALUE_COLUMN = 26

        private const val SUMMARY_FILE = "01_SUMMARY.txt"
        private const val CAPTURE_FILE = "02_CAPTURE.txt"
        private const val PROFILE_FILE = "03_PROFILE_SETTINGS.txt"
        private const val ISP_FILE = "04_ISP.txt"
        private const val WARNINGS_FILE = "05_WARNINGS_ERRORS.txt"
        private const val FRAME_FILE = "06_FRAME_ANALYSIS.txt"
        private const val VENDOR_FILE = "07_VENDOR_TAG_INJECTION.txt"

        private fun resolveDiagnosticsBaseDir(context: Context): File {
            DiagnosticsAggregator.initialize(context.applicationContext)
            return File(context.filesDir, "BnCamDebugState").apply { mkdirs() }
        }
    }

}
