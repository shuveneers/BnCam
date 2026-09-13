@file:Suppress("SpellCheckingInspection", "UNUSED_PARAMETER", "unused")

package com.bncam.core.runners

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import android.view.Surface
import androidx.exifinterface.media.ExifInterface
import com.bncam.core.buffer.FrameRingBuffer
import com.bncam.core.buffer.ZslFramePair
import com.bncam.core.buffer.ZslFrameTimingSnapshot
import com.bncam.core.debug.DiagnosticPayload
import com.bncam.core.debug.CapturePerformanceTracker
import com.bncam.core.debug.BenchmarkWriter
import com.bncam.core.capture.FrameGenerationId
import com.bncam.core.capture.CaptureStageListener
import com.bncam.core.capture.ZslCandidateAuditor
import com.bncam.core.capture.RawColorPipelineAuditor
import com.bncam.core.capture.CaptureRequestPlan
import com.bncam.core.capture.CaptureRoute
import com.bncam.core.capture.OutputPolicy
import com.bncam.core.capture.OutputRotationResolver
import com.bncam.core.capture.ShutterCandidateCollector
import com.bncam.core.capture.SelectedFrameProvenanceValidator
import com.bncam.core.capture.MeteringExactTruth
import com.bncam.core.capture.ShutterCandidateFreshnessPolicy
import com.bncam.core.capture.WarmBufferReadinessPolicy
import com.bncam.core.capture.HardwareQualificationRunner
import com.bncam.core.capture.HardwareQualificationReport
import com.bncam.core.debug.FrameAnalysisDebugEntry
import com.bncam.core.debug.ShotLogger
import com.bncam.core.engine.ImageUtils
import com.bncam.core.engine.LensInfo
import com.bncam.core.engine.WatermarkConfig
import com.bncam.core.engine.WatermarkEngine
import com.bncam.core.isp.raw10.DngWriter
import com.bncam.core.isp.raw.Raw16RenderInput
import com.bncam.core.isp.raw.DemosaicAfHints
import com.bncam.core.isp.raw.SingleRaw16FrameBuilder
import com.bncam.core.quality.LibpatcherProfileResolver
import com.bncam.core.quality.NoiseModelPublicationState
import com.bncam.core.quality.NoiseModelTrace
import com.bncam.core.quality.NoiseModelTraceFrame
import com.bncam.core.quality.RenderQualityConfig
import com.bncam.core.output.CaptureProcessingQueue
import com.bncam.core.output.CaptureSaveQueue
import com.bncam.core.utils.LocationUtils
import com.bncam.data.profile.CameraProfile
import com.bncam.data.settings.SettingsRepository
import com.bncam.vendor.VendorInjectionEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

private data class OisFrameTruth(
    val logicalMode: Int?,
    val physicalMode: Int?,
    val resolvedMode: Int?,
    val sampleSource: String,
    val sampleCount: Int,
    val shiftRmsPx: Double?,
    val shiftPeakPx: Double?
)

private fun resolveOisFrameTruth(
    metadata: TotalCaptureResult?,
    physicalCameraId: String?
): OisFrameTruth {
    if (metadata == null) {
        return OisFrameTruth(null, null, null, "NONE", 0, null, null)
    }
    val logicalMode = metadata.get(CaptureResult.LENS_OPTICAL_STABILIZATION_MODE)
    val physicalResult = if (
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && !physicalCameraId.isNullOrBlank()
    ) {
        metadata.physicalCameraResults[physicalCameraId]
    } else {
        null
    }
    val physicalMode = physicalResult?.get(CaptureResult.LENS_OPTICAL_STABILIZATION_MODE)
    val logicalSamples = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        metadata.get(CaptureResult.STATISTICS_OIS_SAMPLES)
    } else {
        null
    }
    val physicalSamples = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        physicalResult?.get(CaptureResult.STATISTICS_OIS_SAMPLES)
    } else {
        null
    }
    val samples = when {
        !physicalSamples.isNullOrEmpty() -> physicalSamples
        !logicalSamples.isNullOrEmpty() -> logicalSamples
        else -> null
    }
    val source = when {
        !physicalSamples.isNullOrEmpty() -> "PHYSICAL"
        !logicalSamples.isNullOrEmpty() -> "LOGICAL"
        else -> "NONE"
    }
    var sumSquares = 0.0
    var peak = 0.0
    samples?.forEach { sample ->
        val magnitude = hypot(sample.xshift.toDouble(), sample.yshift.toDouble())
        sumSquares += magnitude * magnitude
        if (magnitude > peak) peak = magnitude
    }
    val sampleCount = samples?.size ?: 0
    val rms = if (sampleCount > 0) kotlin.math.sqrt(sumSquares / sampleCount.toDouble()) else null
    val resolvedMode = when {
        physicalMode == CaptureResult.LENS_OPTICAL_STABILIZATION_MODE_ON -> physicalMode
        logicalMode == CaptureResult.LENS_OPTICAL_STABILIZATION_MODE_ON -> logicalMode
        physicalMode != null -> physicalMode
        else -> logicalMode
    }
    return OisFrameTruth(
        logicalMode = logicalMode,
        physicalMode = physicalMode,
        resolvedMode = resolvedMode,
        sampleSource = source,
        sampleCount = sampleCount,
        shiftRmsPx = rms,
        shiftPeakPx = if (sampleCount > 0) peak else null
    )
}

private fun oisModeLabel(mode: Int?): String = when (mode) {
    CaptureResult.LENS_OPTICAL_STABILIZATION_MODE_ON -> "ON"
    CaptureResult.LENS_OPTICAL_STABILIZATION_MODE_OFF -> "OFF"
    null -> "UNREPORTED"
    else -> "UNKNOWN($mode)"
}

private data class AnalysisWrapper(
    val frame: ZslFramePair,
    val sharpness: Double,
    val ev: Double,
    val alignability: Double,
    val clippingScore: Double,
    val lowClippedFraction: Double,
    val highClippedFraction: Double,
    val source: String,
    val analysisTimeMs: Double
)

private data class CandidateScoreInfo(
    val log: String,
    val align: Double,
    val delta: Double,
    val sharpnessScore: Double,
    val motionScore: Double,
    val evScore: Double,
    val clippingScore: Double,
    val analysisSource: String,
    val lowClippedFraction: Double,
    val highClippedFraction: Double,
    val syncScore: Double,
    val candidateAgeAbsMs: Double,
    val freshEnoughForSelection: Boolean,
    val rejectedForStaleSelection: Boolean,
    val temporalBiasContribution: Double,
    val overallScore: Double
)

internal data class HeuristicCandidate(
    val frame: ZslFramePair,
    val index: Int,
    val timestampNs: Long,
    val deltaMs: Double,
    val sharpnessScore: Double,
    val motionScore: Double,
    val motionProxy: Double = 0.5,
    val motionScoreDetails: String = "caller_supplied",
    val evScore: Double,
    val alignabilityScore: Double,
    val clippingScore: Double = 0.5,
    val isStable: Boolean,
    val metadataComplete: Boolean = false,
    val aeState: Int = -1,
    val awbState: Int = -1,
    val focusState: Int = -1
)

internal data class CandidateSelectionScore(
    val candidate: HeuristicCandidate,
    val candidateAgeAbsMs: Double,
    val freshEnoughForSelection: Boolean,
    val rejectedForStaleSelection: Boolean,
    val freshnessScore: Double,
    val aeScore: Double,
    val awbScore: Double,
    val focusScore: Double,
    val trackedSubjectEvidence: Boolean = false,
    val temporalBiasContribution: Double,
    val overallScore: Double
)

internal data class FrameSelectionResult(
    val selected: CandidateSelectionScore,
    val ranked: List<CandidateSelectionScore>,
    val freshnessWindowMs: Double,
    val freshCandidateCount: Int,
    val staleCandidateCount: Int,
    val degradedFallbackUsed: Boolean,
    val effectiveSelectionBias: String,
    val weights: SingleFrameSelectionWeights,
    val anchorSelectionReason: String
)

data class SingleRawProcessingFeedback(
    val nativeStats: String,
    val exposureTimeNs: Long,
    val sensitivityIso: Int,
    val succeeded: Boolean
)

internal object FrameSelectionEngine {
    private fun aeScore(state: Int): Double = when (state) {
        CaptureResult.CONTROL_AE_STATE_CONVERGED,
        CaptureResult.CONTROL_AE_STATE_LOCKED -> 1.0
        CaptureResult.CONTROL_AE_STATE_FLASH_REQUIRED -> 0.8
        CaptureResult.CONTROL_AE_STATE_PRECAPTURE -> 0.4
        CaptureResult.CONTROL_AE_STATE_SEARCHING -> 0.2
        CaptureResult.CONTROL_AE_STATE_INACTIVE -> 0.05
        else -> 0.25
    }

    private fun awbScore(state: Int): Double = when (state) {
        CaptureResult.CONTROL_AWB_STATE_CONVERGED,
        CaptureResult.CONTROL_AWB_STATE_LOCKED -> 1.0
        CaptureResult.CONTROL_AWB_STATE_SEARCHING -> 0.25
        CaptureResult.CONTROL_AWB_STATE_INACTIVE -> 0.05
        else -> 0.25
    }

    private fun focusScore(state: Int): Double = when (state) {
        CaptureResult.CONTROL_AF_STATE_PASSIVE_FOCUSED,
        CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED -> 1.0
        CaptureResult.CONTROL_AF_STATE_ACTIVE_SCAN,
        CaptureResult.CONTROL_AF_STATE_PASSIVE_SCAN -> -2.0 // Heavy penalty for scanning
        CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED,
        CaptureResult.CONTROL_AF_STATE_PASSIVE_UNFOCUSED -> -1.0 // Penalty for unfocused
        CaptureResult.CONTROL_AF_STATE_INACTIVE -> 0.5
        else -> 0.4
    }

    private fun applyNearZslFocusPreference(
        base: SingleFrameSelectionWeights,
        enabled: Boolean
    ): SingleFrameSelectionWeights {
        if (enabled) return base
        val retainedSharpness = base.sharpness * 0.25
        val retainedFocus = base.focus * 0.25
        val releasedToRecency =
            (base.sharpness - retainedSharpness) + (base.focus - retainedFocus)
        return base.copy(
            freshness = base.freshness + releasedToRecency,
            sharpness = retainedSharpness,
            focus = retainedFocus
        )
    }

    fun selectBestCandidate(
        candidates: List<HeuristicCandidate>,
        freshnessWindowMs: Double,
        isTele: Boolean = false,
        nearZslFocusSelectionEnabled: Boolean = true,
        focusCaptureContext: com.bncam.core.engine.FocusCaptureContext = com.bncam.core.engine.FocusCaptureContext(),
        settings: SingleFrameSelectionSettings =
            SingleFrameSelectionSettingsResolver.resolve(
                baseBias = "Overall Best Score",
                frameBias = "Auto",
                acceptAll = false,
                alignableOnly = false,
                preferRecent = false
            )
    ): FrameSelectionResult {
        require(candidates.isNotEmpty()) { "Candidates list is empty" }

        val currentGen = FrameGenerationId.get()
        val isColdStart = FrameGenerationId.msSinceLastIncrement() < 2500L
        val trackedSubjectMode = nearZslFocusSelectionEnabled && focusCaptureContext.reliableTrackedSubject
        val weights = TrackedSubjectSelectionPolicy.adjustWeights(
            applyNearZslFocusPreference(
                SingleFrameSelectionWeightPolicy.resolve(
                    bias = settings.effectiveBias,
                    coldStart = isColdStart
                ),
                enabled = nearZslFocusSelectionEnabled
            ),
            reliableTrackedSubject = trackedSubjectMode
        )
        val balancedWeights = TrackedSubjectSelectionPolicy.adjustWeights(
            applyNearZslFocusPreference(
                SingleFrameSelectionWeightPolicy.resolve(
                    bias = SingleFrameSelectionBias.BALANCED,
                    coldStart = isColdStart
                ),
                enabled = nearZslFocusSelectionEnabled
            ),
            reliableTrackedSubject = trackedSubjectMode
        )

        val scored = candidates.map { candidate ->
            val ageAbsMs = abs(candidate.deltaMs)
            val generationMatches =
                candidate.frame.generationId == currentGen ||
                        candidate.frame.generationId == -1
            val freshEnough = generationMatches && candidate.metadataComplete && ageAbsMs <= freshnessWindowMs
            val freshness = (1.0 - ageAbsMs / freshnessWindowMs).coerceIn(0.0, 1.0)
            val ae = aeScore(candidate.aeState)
            val awb = awbScore(candidate.awbState)
            val focusStateVal = focusScore(candidate.focusState)
            val realFocusConfidence = candidate.frame.focusConfidence.toDouble()
            val candidateFocusOwner = candidate.frame.requestProvenance?.snapshot?.focusOwner ?: "UNKNOWN"
            val trackedSubjectEvidence = candidateFocusOwner.startsWith("TRACK_") &&
                candidate.frame.focusEvaluated && candidate.frame.afRegion != null
            val focus = TrackedSubjectSelectionPolicy.focusScore(
                afStateScore = focusStateVal,
                roiFocusConfidence = realFocusConfidence,
                trackedRoiEvidence = trackedSubjectEvidence,
                reliableTrackedSubject = trackedSubjectMode
            )

            val overall = if (!generationMatches) 0.0 else (
                    freshness * weights.freshness +
                            candidate.sharpnessScore * weights.sharpness +
                            candidate.motionScore * weights.motion +
                            candidate.evScore * weights.exposure +
                            candidate.clippingScore * weights.clipping +
                            ae * weights.ae +
                            awb * weights.awb +
                            focus * weights.focus +
                            candidate.alignabilityScore * weights.alignability
                    ).coerceIn(0.0, 1.0)

            CandidateSelectionScore(
                candidate = candidate,
                candidateAgeAbsMs = ageAbsMs,
                freshEnoughForSelection = freshEnough,
                rejectedForStaleSelection = false,
                freshnessScore = freshness,
                aeScore = ae,
                awbScore = awb,
                focusScore = focus,
                trackedSubjectEvidence = trackedSubjectEvidence,
                temporalBiasContribution =
                    freshness * (weights.freshness - balancedWeights.freshness),
                overallScore = overall
            )
        }

        val freshComplete = scored.filter { it.freshEnoughForSelection }
        val completeCurrentGeneration = scored.filter { score ->
            val frameGeneration = score.candidate.frame.generationId
            (frameGeneration == currentGen || frameGeneration == -1) &&
                    score.candidate.metadataComplete
        }
        if (completeCurrentGeneration.isEmpty()) {
            throw IllegalStateException(
                "No current-generation candidate has complete metadata."
            )
        }
        val degradedFallbackUsed = freshComplete.isEmpty()
        val selectionPool: List<CandidateSelectionScore> =
            if (degradedFallbackUsed) completeCurrentGeneration else freshComplete

        val mostRecentScore = selectionPool.minByOrNull { it.candidateAgeAbsMs }
        val sharpnessDominanceWinner = if (
            nearZslFocusSelectionEnabled &&
            !trackedSubjectMode &&
            !degradedFallbackUsed &&
            settings.effectiveBias != SingleFrameSelectionBias.RECENCY &&
            mostRecentScore != null &&
            (isColdStart || isTele)
        ) {
            val betterSharpnessThreshold = mostRecentScore.candidate.sharpnessScore * 1.12
            val sharperOlderCandidates = selectionPool.filter {
                it.candidate.sharpnessScore > betterSharpnessThreshold && it.focusScore >= 0.5
            }
            if (sharperOlderCandidates.isNotEmpty()) {
                sharperOlderCandidates.maxByOrNull { it.candidate.sharpnessScore }
            } else {
                null
            }
        } else {
            null
        }

        val selectedCandidate = when {
            degradedFallbackUsed ->
                selectionPool.minByOrNull { it.candidateAgeAbsMs }
            sharpnessDominanceWinner != null -> sharpnessDominanceWinner
            else -> selectionPool.maxWithOrNull(
                compareBy<CandidateSelectionScore> { it.overallScore }
                    .thenBy { -it.candidateAgeAbsMs }
            )
        } ?: error("Selection pool unexpectedly empty")
        val selectionReason = when {
            degradedFallbackUsed ->
                "degraded_no_genuine_near_zsl_candidate_closest_complete_frame"
            sharpnessDominanceWinner != null ->
                "genuine_near_zsl_sharpness_dominance_cold_or_tele"
            trackedSubjectMode ->
                "genuine_near_zsl_tracked_subject_roi_focus_weighted"
            !nearZslFocusSelectionEnabled ->
                "genuine_near_zsl_recency_weighted_focus_selection_disabled"
            else ->
                "genuine_near_zsl_highest_weighted_score_bias_${settings.effectiveBias.name.lowercase()}"
        }

        val ranked = scored.map { score ->
            score.copy(
                rejectedForStaleSelection = freshComplete.isNotEmpty() && !score.freshEnoughForSelection
            )
        }.sortedWith(
            compareBy<CandidateSelectionScore> { it.rejectedForStaleSelection }
                .thenByDescending { it.overallScore }
                .thenBy { it.candidateAgeAbsMs }
        )
        val selected = ranked.first { it.candidate === selectedCandidate.candidate }

        // Clear ZslCandidateAuditor logs and record current audit
        ZslCandidateAuditor.clear()
        ranked.forEach { score ->
            val cand = score.candidate
            val isSelected = cand === selectedCandidate.candidate
            val rejectReason = when {
                isSelected -> "SELECTED_BEST_CANDIDATE"
                cand.frame.generationId != currentGen && cand.frame.generationId != -1 -> "wrong_generation_${cand.frame.generationId}_vs_${currentGen}"
                !score.freshEnoughForSelection -> "stale_or_incomplete_metadata"
                score.focusScore < 0.0 -> "focus_scanning_or_lens_moving_state"
                else -> "lower_heuristic_score_overall=${String.format(java.util.Locale.US, "%.4f", score.overallScore)}"
            }
            ZslCandidateAuditor.recordDecision(
                frame = cand.frame,
                sharpnessScore = cand.sharpnessScore,
                motionScore = cand.motionScore,
                aeState = cand.aeState,
                awbState = cand.awbState,
                afState = cand.focusState,
                accepted = isSelected,
                selectedBase = isSelected,
                rejectReason = rejectReason
            )
        }

        return FrameSelectionResult(
            selected = selected,
            ranked = ranked,
            freshnessWindowMs = freshnessWindowMs,
            freshCandidateCount = freshComplete.size,
            staleCandidateCount = scored.count { it.candidateAgeAbsMs > freshnessWindowMs },
            degradedFallbackUsed = degradedFallbackUsed,
            effectiveSelectionBias = settings.effectiveBiasLabel,
            weights = weights,
            anchorSelectionReason = selectionReason
        )
    }
}

class SingleFrameRunner(
    private val context: Context,
    private val cameraManager: CameraManager
) {
    companion object {
        private val ispExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "BnCam-Native-ISP-Worker").apply {
                priority = Thread.MAX_PRIORITY
            }
        }
        val ispDispatcher = ispExecutor.asCoroutineDispatcher()

        private val rawMaterializationExecutor =
            java.util.concurrent.Executors.newSingleThreadExecutor { runnable ->
                Thread(runnable, "BnCam-RAW16-Materializer").apply {
                    priority = Thread.NORM_PRIORITY
                }
            }
        private val rawMaterializationDispatcher =
            rawMaterializationExecutor.asCoroutineDispatcher()
    }

    private val tag = "SingleFrameRunner"
    private val nearZslTimingTag = "NearZslTiming"

    private fun formatLabel(format: Int): String = when (format) {
        ImageFormat.RAW_SENSOR -> "RAW_SENSOR"
        ImageFormat.RAW10 -> "RAW10"
        ImageFormat.YUV_420_888 -> "YUV_420_888"
        else -> "UNKNOWN($format)"
    }

    suspend fun execute(
        plan: CaptureRequestPlan,
        recipe: com.bncam.core.capture.CaptureRecipe,
        ringBuffer: FrameRingBuffer,
        shutterTimestampNs: Long,
        shutterTimestampDomain: String,
        requestedBaseCandidateCount: Int,
        activeProfile: CameraProfile,
        activeLens: LensInfo,
        shotLogger: ShotLogger,
        deviceRotation: Int,
        activeZslFormat: Int,
        meteringStyle: String,
        evOffset: Float,
        currentSubmittedControlRequestEpochAtShutter: Long,
        aeStateBeforeCapture: Int?,
        meteringPolicySummary: String,
        exposurePolicySummary: String,
        postShutterStillCaptureUsed: Boolean,
        userShutterTimestampNs: Long,
        stableAutoWhiteBalance: com.bncam.core.quality.StableWhiteBalanceSnapshot? = null,
        captureStageListener: CaptureStageListener = CaptureStageListener.NONE,
        onRawProcessingFeedback: (SingleRawProcessingFeedback) -> Unit = {},
        temporaryPreviewPath: String? = null,
        focusCaptureContext: com.bncam.core.engine.FocusCaptureContext = com.bncam.core.engine.FocusCaptureContext(),
        portraitCaptureContext: com.bncam.core.capture.PortraitCaptureContext = com.bncam.core.capture.PortraitCaptureContext()
    ): com.bncam.core.output.CaptureSubmissionResult = withContext(Dispatchers.IO) {
        require(userShutterTimestampNs > 0L) {
            "SingleFrameRunner requires a valid non-zero userShutterTimestampNs from actual shutter press."
        }

        val performanceTracker = CapturePerformanceTracker("SINGLE_FRAME_${formatLabel(activeZslFormat)}")
        performanceTracker.setMetric("thermalStatusAtShutter", recipe.thermalState)
        performanceTracker.sampleSystemState(context, "processing_start")
        performanceTracker.setMetric("captureMode", "SINGLE")
        performanceTracker.setMetric("bufferFormat", formatLabel(activeZslFormat))
        performanceTracker.setMetric("outputPolicy", plan.outputPolicy.name)
        performanceTracker.setMetric("requestedFrameCount", 1)
        performanceTracker.setMetric("portraitRequested", portraitCaptureContext.requested)
        performanceTracker.setMetric("portraitMaskAvailableAtShutter", portraitCaptureContext.available)
        performanceTracker.setMetric("portraitMaskStatus", portraitCaptureContext.status)
        val captureDispatchLatencyMs =
            ((android.os.SystemClock.elapsedRealtimeNanos() - shutterTimestampNs).coerceAtLeast(0L) / 1_000_000.0)
        val expectedCollectionGeneration = ringBuffer.currentGeneration()
        val candidateCollectionResult = ShutterCandidateCollector(ringBuffer).collect(
            shutterTimestampNs = shutterTimestampNs,
            shutterTimestampDomain = shutterTimestampDomain,
            expectedGeneration = expectedCollectionGeneration,
            expectedFormat = activeZslFormat,
            requestedInitialCandidateCount = requestedBaseCandidateCount
        )
        // Candidate references remain ring-owned. The selected frame gets an explicit FrameLease
        // below; Single YUV transfers that exact lease to CaptureProcessingQueue because the native
        // renderer consumes its HardwareBuffer after execute() has returned Submitted.
        val settingsRepo = SettingsRepository(context)
        val nearZslFocusSelectionEnabled = true
        val capturedSettings = recipe.executionSettings
        val activeBufferFormatLabel = formatLabel(activeZslFormat)

        val startTimeMs = System.currentTimeMillis()
        val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
        val startedAtStr = isoFormat.format(Date(startTimeMs))

        val chars = cameraManager.getCameraCharacteristics(activeLens.id)
        val cameraTimestampSource =
            chars.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE)
                ?: CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_UNKNOWN
        val cameraTimestampSourceLabel = when (cameraTimestampSource) {
            CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME -> "REALTIME"
            CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_UNKNOWN -> "UNKNOWN"
            else -> "UNRECOGNIZED($cameraTimestampSource)"
        }
        val sensorTimestampComparableToElapsedRealtime =
            cameraTimestampSource ==
                    CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME
        val shutterTimestampComparableToSensorTimestamp =
            shutterTimestampDomain == "SENSOR_TIMESTAMP" ||
                    (shutterTimestampDomain.startsWith("ELAPSED_REALTIME") &&
                            sensorTimestampComparableToElapsedRealtime)
        val lensHardwareSettings = capturedSettings.lensHardwareSettings
        val nativeLensHardwarePushed = ImageUtils.updateHardwareConfigNative(lensHardwareSettings)
        val finalJpegRotation = getJpegOrientation(chars, deviceRotation)
        val isFrontCamera = chars.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT

        val mirrorFront = capturedSettings.output.mirrorFrontPreview
        val photoPrefix = capturedSettings.output.photoPrefix
        val saveLocation = capturedSettings.output.saveLocation
        val phoneAssistanceSensorsEnabled = recipe.phoneAssistanceSensorsEnabled
        val colorSensorHelper = com.bncam.core.sensors.ColorSensorHelper(context)
        require(plan.captureMode == com.bncam.core.capture.CaptureMode.SINGLE) {
            "SingleFrameRunner received non-single route ${plan.route.id}."
        }
        val saveLocationData = capturedSettings.debug.saveLocationData
        val enableShotLogger = capturedSettings.debug.shotLoggingEnabled
        val logSummary = capturedSettings.debug.logSummary
        val logActiveMode = capturedSettings.debug.logActiveMode
        val logProfileSettings = capturedSettings.debug.logProfileSettings
        val logFrameAnalysis = capturedSettings.debug.logFrameAnalysis
        val logWarnings = capturedSettings.debug.logWarnings
        val logPipelineDebug = capturedSettings.debug.logPipelineDebug
        val logVendorInjection = capturedSettings.debug.logVendorInjection

        val wmEnabled = capturedSettings.output.watermarkEnabled
        val wmStyle = capturedSettings.output.watermarkStyle
        val wmSignature = capturedSettings.output.watermarkSignature
        val wmAddAuthorTopRight = capturedSettings.output.watermarkAuthor

        val exifSaveSignature = capturedSettings.output.exifSaveSignature
        val exifExtraData = capturedSettings.output.exifExtraData

        val attemptId = "${activeLens.id}-$shutterTimestampNs"
        val singleFeatureName = when {
            portraitCaptureContext.requested -> "Portrait"
            recipe.computationalHdrRequested -> "CompHDR"
            else -> "Photo"
        }
        performanceTracker.setMetric("actualRunner", "SingleFrameRunner")
        performanceTracker.setMetric("computationalHdrResolutionReason", recipe.computationalHdrResolutionReason)
        val shotId = if (enableShotLogger) {
            shotLogger.startNewShot(
                sensorName = activeLens.name.substringBefore(" "),
                modeName = "SingleFrame",
                featureName = singleFeatureName,
                logSummary = logSummary,
                logCapture = logActiveMode,
                logProfileSettings = logProfileSettings,
                logIsp = logPipelineDebug,
                logWarnings = logWarnings,
                logFrameAnalysis = logFrameAnalysis,
                logVendorInjection = logVendorInjection
            )
            shotLogger.writeTextFile("capture_recipe.json", recipe.toJson())
            shotLogger.writeInitialStatus(attemptId, "SHUTTER_DISPATCH")
            if (com.bncam.core.debug.DebugFailureTestTrigger.shouldFailAndClear()) {
                val testException = IllegalStateException("Debug-only forced processing failure triggered for test")
                shotLogger.finalizeAttemptOnce(
                    attemptId = attemptId,
                    terminalState = com.bncam.core.debug.CaptureStatusState.FAILED,
                    stage = "DEBUG_FORCED_FAILURE",
                    exception = testException
                )
                throw testException
            }
            shotLogger.getShotDir()?.name ?: "Unknown_Shot_ID"
        } else {
            "Unknown_Shot_ID"
        }
        val captureTrace =
            com.bncam.core.tracing.CaptureTraceRecorder(recipe = recipe, captureId = shotId)

        if (enableShotLogger) {
            shotLogger.recordPipelineEvent("Camera2 Control Policy", "meteringPlan", meteringPolicySummary)
            shotLogger.recordPipelineEvent("Camera2 Control Policy", "exposurePlan", exposurePolicySummary)
            val initialNewestSensorTimestampNs =
                candidateCollectionResult.initialRingSnapshot.completeFrames
                    .maxOfOrNull { it.sensorTimestampNs } ?: 0L
            val initialNewestDeltaMs =
                if (
                    shutterTimestampComparableToSensorTimestamp &&
                    initialNewestSensorTimestampNs > 0L
                ) {
                    (initialNewestSensorTimestampNs - shutterTimestampNs) /
                            1_000_000.0
                } else {
                    null
                }
            fun collectorMs(value: Double?): String =
                value?.let { String.format(Locale.US, "%.3f", it) }
                    ?: "unavailable_clock_domain"
            shotLogger.recordPipelineEvent(
                "Near-ZSL Collector",
                "start",
                "shutterTimestampNs=$shutterTimestampNs;" +
                        "pipelineGeneration=$expectedCollectionGeneration;" +
                        "initialCompletePairCount=${
                            candidateCollectionResult.initialRingSnapshot.completePairCount
                        };initialNewestSensorTimestampNs=$initialNewestSensorTimestampNs;" +
                        "initialNewestAnchorDeltaMs=${collectorMs(initialNewestDeltaMs)};" +
                        "hardDeadlineMs=${candidateCollectionResult.hardDeadlineMs};" +
                        "frameDurationMedianMs=${
                            collectorMs(
                                candidateCollectionResult.timingEstimateAtStart
                                    .frameDurationMedianMs
                            )
                        };imageDeliveryLagMedianMs=${
                            collectorMs(
                                candidateCollectionResult.timingEstimateAtStart
                                    .imageDeliveryLagMedianMs
                            )
                        };metadataDeliveryLagMedianMs=${
                            collectorMs(
                                candidateCollectionResult.timingEstimateAtStart
                                    .metadataDeliveryLagMedianMs
                            )
                        };pairCompletionLagMedianMs=${
                            collectorMs(
                                candidateCollectionResult.timingEstimateAtStart
                                    .pairCompletionLagMedianMs
                            )
                        }"
            )
            candidateCollectionResult.considerations.forEachIndexed { index, event ->
                shotLogger.recordPipelineEvent(
                    "Near-ZSL Collector Candidate",
                    "candidate[$index]",
                    "origin=${event.origin};sensorTimestampNs=${event.sensorTimestampNs};" +
                            "candidateSensorDeltaMs=${collectorMs(event.candidateSensorDeltaMs)};" +
                            "pairCompleteElapsedNs=${event.pairCompleteElapsedNs};" +
                            "candidatePairCompletedRelativeToShutterMs=${
                                collectorMs(
                                    event.candidatePairCompletedRelativeToShutterMs
                                )
                            };pipelineGeneration=${event.pipelineGeneration};" +
                            "controlRequestEpoch=${event.controlRequestEpoch};" +
                            "requestProvenanceStatus=${event.requestProvenanceStatus};" +
                            "format=${event.format};acceptedIntoCollector=${event.accepted};" +
                            "rejectionReason=${event.rejectionReason}"
                )
            }
            shotLogger.recordPipelineEvent(
                "Near-ZSL Collector",
                "close",
                "closeReason=${candidateCollectionResult.closeReason.name};" +
                        "collectionDurationMs=${
                            collectorMs(candidateCollectionResult.collectionDurationMs)
                        };hardDeadlineMs=${candidateCollectionResult.hardDeadlineMs};" +
                        "candidateCount=${candidateCollectionResult.candidateCount};" +
                        "newestCandidateDeltaMs=${
                            collectorMs(candidateCollectionResult.newestCandidateDeltaMs)
                        };closestCandidateAbsoluteDeltaMs=${
                            collectorMs(
                                candidateCollectionResult.closestCandidateAbsoluteDeltaMs
                            )
                        };postShutterCompletedPairCount=${
                            candidateCollectionResult.postShutterCompletedPairCount
                        };frameDurationMedianMs=${
                            collectorMs(
                                candidateCollectionResult.timingEstimateAtClose
                                    .frameDurationMedianMs
                            )
                        };imageDeliveryLagMedianMs=${
                            collectorMs(
                                candidateCollectionResult.timingEstimateAtClose
                                    .imageDeliveryLagMedianMs
                            )
                        };metadataDeliveryLagMedianMs=${
                            collectorMs(
                                candidateCollectionResult.timingEstimateAtClose
                                    .metadataDeliveryLagMedianMs
                            )
                        };pairCompletionLagMedianMs=${
                            collectorMs(
                                candidateCollectionResult.timingEstimateAtClose
                                    .pairCompletionLagMedianMs
                            )
                        }"
            )
            shotLogger.recordPipelineEvent(
                "Near-ZSL Collector",
                "discardFirstPolicy",
                "applied=false;" +
                        "reason=shutter_candidate_order_is_not_stream_startup_order"
            )
        }

        val analysisSource = "$activeBufferFormatLabel native_pixel_plus_capture_metadata_scoring"
        if (enableShotLogger) {
            shotLogger.recordWarning(
                group = "Frame Source Warnings",
                message = "Analysis delegated to C++ native layer (HardwareBuffer).",
                severity = "INFO"
            )
        }

        val profileId = activeProfile.id
        val preferredFrameSetting = recipe.frameSource.name
        val selection = capturedSettings.selection
        val basePosition = selection.basePosition
        val baseInclude = selection.baseIncludeInMerge
        val baseBias = selection.baseBias
        val baseTempBias = selection.baseTemporalBias

        val frameBias = selection.frameBias
        val acceptAll = selection.acceptAll
        val rejectDupes = selection.rejectDuplicates
        val useAlignableOnly = selection.useAlignableOnly
        val discardFirstConfigured = selection.discardFirst
        if (enableShotLogger) {
            shotLogger.recordPipelineEvent(
                "Near-ZSL Collector",
                "legacyDiscardFirstSetting",
                "configured=$discardFirstConfigured;applied=false;" +
                        "startupSafety=handled_by_generation_and_pipeline_warmup_gates"
            )
        }
        val preferRecent = selection.preferRecent
        val ignoreStale = selection.ignoreStale
        val selectionSettings = SingleFrameSelectionSettingsResolver.resolve(
            baseBias = baseBias,
            frameBias = frameBias,
            acceptAll = acceptAll,
            alignableOnly = useAlignableOnly,
            preferRecent = preferRecent
        )
        if (enableShotLogger) {
            shotLogger.recordPipelineEvent(
                "Frame Selection Settings",
                "near_zsl_focus_selection",
                "configured=$nearZslFocusSelectionEnabled;" +
                        "policy=${if (nearZslFocusSelectionEnabled) "quality_weighted" else "recency_weighted_soft_focus"}"
            )
        }
        if (enableShotLogger) {
            shotLogger.recordPipelineEvent(
                "Frame Selection Settings",
                "effectivePolicy",
                "effectiveBias=${selectionSettings.effectiveBiasLabel};" +
                        "effectiveBiasSource=${selectionSettings.effectiveBiasSource};" +
                        "configuredBaseBias=$baseBias;" +
                        "baseBiasApplied=${selectionSettings.baseBiasApplied};" +
                        "configuredFrameBias=$frameBias;" +
                        "frameBiasApplied=${selectionSettings.frameBiasApplied};" +
                        "configuredPreferRecent=$preferRecent;" +
                        "preferRecentApplied=${
                            selectionSettings.effectiveBias ==
                                    SingleFrameSelectionBias.RECENCY
                        }"
            )
            shotLogger.recordPipelineEvent(
                "Frame Selection Settings",
                "selection_accept_all",
                "configured=$acceptAll;effective=false;" +
                        "status=${selectionSettings.acceptAllStatus}"
            )
            shotLogger.recordPipelineEvent(
                "Frame Selection Settings",
                "selection_alignable_only",
                "configured=$useAlignableOnly;effective=false;" +
                        "status=${selectionSettings.alignableOnlyStatus}"
            )
            shotLogger.recordPipelineEvent(
                "Frame Selection Settings",
                "base_temporal_bias",
                "configured=$baseTempBias;effective=0.0;" +
                        "status=DEPRECATED_SINGLE_FRAME_USE_SELECTION_PREFER_RECENT"
            )
        }

        val ringSnapshotAtShutter =
            candidateCollectionResult.initialRingSnapshot
        val imageReaderPressureAtShutter = ringBuffer.imageReaderPressureDiagnostics()
        val framesInBufferCount = ringSnapshotAtShutter.completePairCount
        val flashMode = capturedSettings.flashMode
        val requirePostShutter = postShutterStillCaptureUsed

        val maxImagesVal = ringSnapshotAtShutter.actualImageReaderMaxImages
        val configuredBufferCapacityVal = ringBuffer.currentCapacity()
        val ringBufferCapacityVal = ringBuffer.currentCapacity()
        val framesReceivedVal = ringSnapshotAtShutter.acquiredImageCount
        val completePairCountVal = ringSnapshotAtShutter.completePairCount
        val completedPairCountTotalVal =
            ringSnapshotAtShutter.completedPairCountTotal
        val ringRetainedCountVal = ringSnapshotAtShutter.ringRetainedCount
        val ringOverwriteCountVal = ringSnapshotAtShutter.ringOverwriteCount
        val framesRejectedStaleVal = ringBuffer.rejectedStale
        val framesRejectedGenerationVal = ringBuffer.rejectedGeneration
        val framesDroppedByRingBufferVal = ringBuffer.framesDroppedByRingBuffer
        val imageReaderAcquireFailureCountVal = ringBuffer.imageReaderAcquireFailureCount
        val bufferBackpressureDetectedVal = imageReaderPressureAtShutter.backpressureDetected
        val warmBufferRequirement = WarmBufferReadinessPolicy.captureRoute(
            format = activeZslFormat,
            captureMode = com.bncam.core.capture.CaptureMode.SINGLE,
            requestedFrameCount = requestedBaseCandidateCount,
            bufferCapacity = ringBuffer.currentCapacity()
        )
        val requiredWarmPairCountVal =
            warmBufferRequirement.requiredCompleteFrames
        val streamHealthFreshPairCountAtShutter =
            if (shutterTimestampComparableToSensorTimestamp) {
                ringSnapshotAtShutter.completeFrames.count { frame ->
                    kotlin.math.abs(
                        frame.sensorTimestampNs - shutterTimestampNs
                    ) / 1_000_000.0 <=
                            warmBufferRequirement.streamHealthFreshnessWindowMs
                }
            } else {
                -1
            }

        val timestampDomainSummary =
            "cameraId=${activeLens.id};cameraTimestampSource=$cameraTimestampSourceLabel($cameraTimestampSource);" +
                    "ringTimestampSource=${ringSnapshotAtShutter.timestampSourceLabel}(${ringSnapshotAtShutter.timestampSource});" +
                    "ringTimestampSourceCameraId=${ringSnapshotAtShutter.timestampSourceCameraId};" +
                    "sensorTimestampComparableToElapsedRealtime=$sensorTimestampComparableToElapsedRealtime;" +
                    "shutterTimestampDomain=$shutterTimestampDomain;" +
                    "shutterTimestampComparableToSensorTimestamp=$shutterTimestampComparableToSensorTimestamp"
        Log.i(
            nearZslTimingTag,
            "event=SHUTTER_RING_SNAPSHOT shutterTimestampNs=$shutterTimestampNs " +
                    "timestampDomain={$timestampDomainSummary} " +
                    "pipelineGeneration=${ringSnapshotAtShutter.pipelineGeneration} " +
                    "actualImageReaderMaxImages=$maxImagesVal " +
                    "acquiredImageCount=$framesReceivedVal " +
                    "completePairCount=$completePairCountVal " +
                    "completedPairCountTotal=$completedPairCountTotalVal " +
                    "ringRetainedCount=$ringRetainedCountVal " +
                    "ringOverwriteCount=$ringOverwriteCountVal " +
                    "ringCapacity=$ringBufferCapacityVal " +
                    "ringResidentImageSlots=${imageReaderPressureAtShutter.ringResidentImageSlots} " +
                    "producerHeadroom=${imageReaderPressureAtShutter.producerHeadroom} " +
                    "leasedFrames=${imageReaderPressureAtShutter.leasedFrames} " +
                    "drainBatchHighWatermark=${imageReaderPressureAtShutter.drainBatchHighWatermark} " +
                    "drainServiceMedianMs=${imageReaderPressureAtShutter.drainServiceMedianMs} " +
                    "imageArrivalCadenceMedianMs=${imageReaderPressureAtShutter.imageArrivalCadenceMedianMs} " +
                    "pairSkewMedianMs=${imageReaderPressureAtShutter.imageMetadataPairSkewMedianMs} " +
                    "estimatedRingResidentImageBytes=${imageReaderPressureAtShutter.estimatedRingResidentImageBytes}"
        )
        if (enableShotLogger) {
            shotLogger.recordPipelineEvent(
                "Near-ZSL Timing",
                "timestampDomain",
                timestampDomainSummary
            )
            shotLogger.recordPipelineEvent(
                "Near-ZSL Timing",
                "shutterRingSnapshot",
                "shutterTimestampNs=$shutterTimestampNs;" +
                        "pipelineGeneration=${ringSnapshotAtShutter.pipelineGeneration};" +
                        "actualImageReaderMaxImages=$maxImagesVal;" +
                        "acquiredImageCount=$framesReceivedVal;" +
                        "completePairCount=$completePairCountVal;" +
                        "completedPairCountTotal=$completedPairCountTotalVal;" +
                        "ringRetainedCount=$ringRetainedCountVal;" +
                        "ringOverwriteCount=$ringOverwriteCountVal;" +
                        "ringCapacity=$ringBufferCapacityVal"
            )
        }

        // The event-driven collector has already transferred ownership of a bounded,
        // shutter-centered set. Existing pruning and scoring continue below.
        val candidateFrames = candidateCollectionResult.frames
        val generationAfterCollection = ringBuffer.currentGeneration()
        if (generationAfterCollection != expectedCollectionGeneration) {
            Log.w(
                "NearZslCollector",
                "event=COLLECTOR_POST_CLOSE_ABORT " +
                        "closeReason=PIPELINE_GENERATION_CHANGED " +
                        "expectedGeneration=$expectedCollectionGeneration " +
                        "actualGeneration=$generationAfterCollection"
            )
            if (enableShotLogger) {
                shotLogger.recordPipelineEvent(
                    "Near-ZSL Collector",
                    "postCloseAbort",
                    "closeReason=PIPELINE_GENERATION_CHANGED;" +
                            "expectedGeneration=$expectedCollectionGeneration;" +
                            "actualGeneration=$generationAfterCollection"
                )
            }
            throw IllegalStateException(
                "Pipeline generation changed after shutter candidate collection."
            )
        }

        val oldestFrame = candidateFrames.minByOrNull { it.timestamp }
        val newestFrame = candidateFrames.maxByOrNull { it.timestamp }
        fun clockSafeFrameAgeMs(frame: ZslFramePair?): Double {
            if (frame == null) return 0.0
            if (shutterTimestampComparableToSensorTimestamp && frame.timestamp > 0L) {
                return (shutterTimestampNs - frame.timestamp) / 1_000_000.0
            }
            if (shutterTimestampDomain.startsWith("ELAPSED_REALTIME")) {
                val completionNs = frame.pairCompleteElapsedNs.takeIf { it > 0L }
                    ?: maxOf(frame.imageArrivalElapsedNs, frame.metadataArrivalElapsedNs)
                if (completionNs > 0L) return (shutterTimestampNs - completionNs) / 1_000_000.0
            }
            return 0.0
        }
        val oldestFrameAgeMs = clockSafeFrameAgeMs(oldestFrame)
        val newestFrameAgeMs = clockSafeFrameAgeMs(newestFrame)

        if (candidateFrames.isEmpty()) {
            throw IllegalStateException("Single-frame capture has no complete warm-buffer frame.")
        }

        // We gaan verder met alle candidate frames voor de scoring engine!
        val takenFrames = candidateFrames.toMutableList()
        var takenFramesClosed = false
        var takenHardwareReleased = false
        var rawWorkReservationForCleanup: CaptureProcessingQueue.Reservation? = null
        var acquiredRawInputForCleanup: Raw16RenderInput? = null
        var rawWorkHandedOff = false
        var yuvWorkHandedOff = false
        var anchorLease: com.bncam.core.buffer.FrameLease? = null

        fun releaseTakenHardwareBuffers() {}
        fun closeTakenFrames() {}

        fun timingKey(timestampNs: Long, generation: Int): String =
            "$generation:$timestampNs"

        fun timingKey(frame: ZslFramePair): String =
            timingKey(frame.timestamp, frame.generationId)

        val pruneReasonsByTimingKey =
            mutableMapOf<String, MutableList<String>>()

        fun recordPruneReason(frame: ZslFramePair, reason: String) {
            pruneReasonsByTimingKey
                .getOrPut(timingKey(frame)) { mutableListOf() }
                .add(reason)
        }

        fun formatTimingMs(value: Double?): String =
            value?.let { String.format(Locale.US, "%.3f", it) }
                ?: "unavailable_clock_domain"

        fun emitCandidateTimingTelemetry(
            finalCandidates: List<ZslFramePair>,
            terminalStage: String
        ) {
            val takenKeys = takenFrames.map(::timingKey).toSet()
            val finalKeys = finalCandidates.map(::timingKey).toSet()
            fun timingSnapshot(frame: ZslFramePair): ZslFrameTimingSnapshot {
                fun lagMs(arrivalElapsedNs: Long): Double? =
                    if (
                        frame.sensorTimestampComparableToElapsedRealtime &&
                        frame.timestamp > 0L &&
                        arrivalElapsedNs > 0L
                    ) {
                        (arrivalElapsedNs - frame.timestamp) / 1_000_000.0
                    } else {
                        null
                    }
                val timestampSourceLabel = when (frame.timestampSource) {
                    CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME ->
                        "REALTIME"
                    CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_UNKNOWN ->
                        "UNKNOWN"
                    else -> "UNRECOGNIZED(${frame.timestampSource})"
                }
                return ZslFrameTimingSnapshot(
                    sensorTimestampNs = frame.timestamp,
                    exposureTimeNs = frame.exposureTimeNs,
                    imageArrivalElapsedNs = frame.imageArrivalElapsedNs,
                    metadataArrivalElapsedNs = frame.metadataArrivalElapsedNs,
                    pairCompleteElapsedNs = frame.pairCompleteElapsedNs,
                    pipelineGeneration = frame.generationId,
                    controlRequestEpoch = frame.controlRequestEpoch,
                    requestProvenanceStatus =
                        frame.requestProvenance?.associationStatus ?: "UNPROVEN",
                    timestampSource = frame.timestampSource,
                    timestampSourceLabel = timestampSourceLabel,
                    sensorTimestampComparableToElapsedRealtime =
                        frame.sensorTimestampComparableToElapsedRealtime,
                    imageDeliveryLagMs = lagMs(frame.imageArrivalElapsedNs),
                    metadataDeliveryLagMs = lagMs(frame.metadataArrivalElapsedNs),
                    pairCompletionLagMs = lagMs(frame.pairCompleteElapsedNs)
                )
            }
            val initialTimingKeys = ringSnapshotAtShutter.completeFrames
                .map {
                    timingKey(
                        it.sensorTimestampNs,
                        it.pipelineGeneration
                    )
                }
                .toSet()
            val allTimingFrames =
                (ringSnapshotAtShutter.completeFrames +
                        takenFrames.map(::timingSnapshot))
                    .distinctBy {
                        timingKey(
                            it.sensorTimestampNs,
                            it.pipelineGeneration
                        )
                    }
                    .sortedBy { it.sensorTimestampNs }
            val collectorReasonsByKey =
                candidateCollectionResult.considerations
                    .groupBy {
                        timingKey(
                            it.sensorTimestampNs,
                            it.pipelineGeneration
                        )
                    }
                    .mapValues { (_, events) ->
                        events.joinToString("|") { it.rejectionReason }
                    }
            allTimingFrames.forEachIndexed { index, frame ->
                val key = timingKey(
                    frame.sensorTimestampNs,
                    frame.pipelineGeneration
                )
                val collected = key in takenKeys
                val keptForScoring = key in finalKeys
                val rawShutterDifferenceMs =
                    (frame.sensorTimestampNs - shutterTimestampNs) / 1_000_000.0
                val shutterDeltaMs =
                    if (shutterTimestampComparableToSensorTimestamp) {
                        formatTimingMs(rawShutterDifferenceMs)
                    } else {
                        "unavailable_clock_domain"
                    }
                val pairAssemblySpanMs =
                    if (
                        frame.imageArrivalElapsedNs > 0L &&
                        frame.metadataArrivalElapsedNs > 0L
                    ) {
                        kotlin.math.abs(
                            frame.imageArrivalElapsedNs -
                                    frame.metadataArrivalElapsedNs
                        ) / 1_000_000.0
                    } else {
                        null
                    }
                val pruneResult = when {
                    !collected -> "NOT_COLLECTED"
                    keptForScoring -> "KEPT_FOR_SCORING"
                    else -> "PRUNED"
                }
                val pruneReason = when {
                    !collected -> collectorReasonsByKey[key]
                        ?: "NOT_SELECTED_BY_INITIAL_SHUTTER_CENTERED_LIMIT_${
                            candidateCollectionResult.initialCandidateLimit
                        }"
                    keptForScoring -> "NONE"
                    else -> pruneReasonsByTimingKey[key]
                        ?.joinToString("|")
                        ?: "PRUNED_REASON_NOT_RECORDED"
                }
                val telemetry =
                    "index=$index;sensorTimestampNs=${frame.sensorTimestampNs};" +
                            "shutterTimestampNs=$shutterTimestampNs;" +
                            "shutterTimestampDomain=$shutterTimestampDomain;" +
                            "shutterDeltaMs=$shutterDeltaMs;" +
                            "rawTimestampDifferenceMs=${
                                String.format(
                                    Locale.US,
                                    "%.3f",
                                    rawShutterDifferenceMs
                                )
                            };" +
                            "shutterDeltaClockDomainsComparable=$shutterTimestampComparableToSensorTimestamp;" +
                            "exposureTimeNs=${frame.exposureTimeNs};" +
                            "imageArrivalElapsedNs=${frame.imageArrivalElapsedNs};" +
                            "metadataArrivalElapsedNs=${frame.metadataArrivalElapsedNs};" +
                            "pairCompleteElapsedNs=${frame.pairCompleteElapsedNs};" +
                            "imageDeliveryLagMs=${formatTimingMs(frame.imageDeliveryLagMs)};" +
                            "metadataDeliveryLagMs=${formatTimingMs(frame.metadataDeliveryLagMs)};" +
                            "pairCompletionLagMs=${formatTimingMs(frame.pairCompletionLagMs)};" +
                            "pairAssemblySpanMs=${formatTimingMs(pairAssemblySpanMs)};" +
                            "pipelineGeneration=${frame.pipelineGeneration};" +
                            "controlRequestEpoch=${frame.controlRequestEpoch};" +
                            "requestProvenanceStatus=${frame.requestProvenanceStatus};" +
                            "timestampSource=${frame.timestampSourceLabel}(${frame.timestampSource});" +
                            "sensorTimestampComparableToElapsedRealtime=${frame.sensorTimestampComparableToElapsedRealtime};" +
                            "collectorOrigin=${
                                if (key in initialTimingKeys) {
                                    "PRESENT_AT_COLLECTOR_START"
                                } else {
                                    "COMPLETED_DURING_COLLECTION"
                                }
                            };" +
                            "candidateCollected=$collected;pruneResult=$pruneResult;" +
                            "pruneReason=$pruneReason;terminalStage=$terminalStage"
                Log.i(
                    nearZslTimingTag,
                    "event=CANDIDATE_AT_SHUTTER $telemetry"
                )
                if (enableShotLogger) {
                    shotLogger.recordPipelineEvent(
                        "Near-ZSL Candidate Timing",
                        "candidate[$index]",
                        telemetry
                    )
                }
            }
        }

        try {
            com.bncam.core.debug.RawRecoveryTrace.log(
                "SINGLE_RUNNER_START",
                "userShutterTs=$userShutterTimestampNs, format=$activeZslFormat, takenFramesCount=${takenFrames.size}"
            )

        fun isFullyPreShutter(frame: ZslFramePair): Boolean {
            return com.bncam.core.buffer.NearZslEligibilityPolicy.isFullyPreShutter(frame, userShutterTimestampNs)
        }

        // Hard eligibility is limited to timestamp/shutter-window correctness. Lens/OIS motion is
        // intentionally a soft quality signal: FrameMotionScorer already penalizes LENS_STATE_MOVING
        // and AF scanning later. Making motion a hard gate can empty an otherwise healthy startup
        // buffer while AF is settling, which is worse than selecting the best available frame.
        var availableFrames = takenFrames.filter { frame: ZslFramePair ->
            val ts = frame.metadata?.get(CaptureResult.SENSOR_TIMESTAMP)
            if (ts == null) {
                recordPruneReason(frame, "ELIGIBILITY_MISSING_SENSOR_TIMESTAMP")
                return@filter false
            }
            if (!ImageUtils.isOisStabilized(frame.metadata)) {
                Log.i(tag, "Frame timestamp=$ts retained with moving-lens penalty; selection scorer will down-rank it.")
            }
            if (!requirePostShutter) {
                val fullyPreShutter = isFullyPreShutter(frame)
                if (!fullyPreShutter) {
                    val fullExposureEndNs = com.bncam.core.buffer.NearZslEligibilityPolicy.calculateFullExposureEndNs(frame)
                    recordPruneReason(
                        frame,
                        "HARD_ELIGIBILITY_GATE_NOT_FULLY_PRE_SHUTTER:fullExposureEndNs=$fullExposureEndNs>userShutterTimestampNs=$userShutterTimestampNs"
                    )
                }
                fullyPreShutter
            } else {
                val insidePostShutterContract = ts >= (shutterTimestampNs - 50_000_000L)
                if (!insidePostShutterContract) {
                    recordPruneReason(frame, "POST_SHUTTER_CONTRACT_OLDER_THAN_50MS")
                }
                insidePostShutterContract
            }
        }.toMutableList()

        if (requirePostShutter) {
            // Hardware-flash capture is not a generic post-shutter window. It is one exact Camera2
            // STILL_CAPTURE request. Never let a neighboring repeating warm-buffer frame win on
            // sharpness/recency, because that frame may not contain the main flash at all.
            val exactStillFrames = availableFrames.filter { frame ->
                val epochMatches =
                    frame.controlRequestEpoch == currentSubmittedControlRequestEpochAtShutter
                val timestampMatches =
                    shutterTimestampDomain != "SENSOR_TIMESTAMP" ||
                        frame.timestamp == shutterTimestampNs
                epochMatches && timestampMatches
            }
            if (exactStillFrames.isEmpty()) {
                throw IllegalStateException(
                    "Post-shutter still contract violated: exact Camera2 still frame is missing. " +
                        "expectedEpoch=$currentSubmittedControlRequestEpochAtShutter " +
                        "shutterTimestampNs=$shutterTimestampNs domain=$shutterTimestampDomain"
                )
            }
            availableFrames = exactStillFrames.toMutableList()
            if (enableShotLogger) {
                shotLogger.recordPipelineEvent(
                    "Flash Capture",
                    "exactStillFrame",
                    "required=true;epoch=$currentSubmittedControlRequestEpochAtShutter;" +
                        "timestampNs=${availableFrames[0].timestamp};candidateCount=${availableFrames.size}"
                )
            }
        }

        if (availableFrames.isEmpty() && !requirePostShutter) {
            val collectedTimestamps = takenFrames.map { it.timestamp }.toSet()
            val extraWarmFrames = ringBuffer.queryCandidates(userShutterTimestampNs = userShutterTimestampNs, maxCount = ringBuffer.currentCapacity())
            val additionalValid = mutableListOf<ZslFramePair>()
            for (frame in extraWarmFrames) {
                if (frame.timestamp in collectedTimestamps) {
                    continue
                }
                val ts = frame.metadata?.get(CaptureResult.SENSOR_TIMESTAMP) ?: frame.timestamp
                if (ts > 0L && isFullyPreShutter(frame)) {
                    if (!ImageUtils.isOisStabilized(frame.metadata)) {
                        Log.i(tag, "Search-back frame timestamp=$ts retained with moving-lens score penalty.")
                    }
                    additionalValid.add(frame)
                    takenFrames.add(frame)
                } else {
                    recordPruneReason(frame, "WARM_BUFFER_SEARCH_BACK_REJECTED")
                }
            }
            availableFrames = additionalValid
        }

        if (availableFrames.isEmpty()) {
            emitCandidateTimingTelemetry(
                finalCandidates = emptyList(),
                terminalStage = "SHUTTER_WINDOW_ELIGIBILITY_FILTER"
            )
            val diagReason = "NO_VALID_FULLY_PRE_SHUTTER_FRAME:userShutterTimestampNs=$userShutterTimestampNs,completeFrames=${ringBuffer.completeFrameCount()}"
            ringBuffer.recordSelectionFailureReason(diagReason)
            throw IllegalStateException("No warm-buffer frame satisfies the selected shutter-window contract.")
        }

        val initialAvailableCount = availableFrames.size
        var rejectedByInvalid = 0
        val rejectedByFirstFrame = 0
        var rejectedByStale = 0
        var rejectedByDupes = 0

        var prunedList = availableFrames.filter { f: ZslFramePair ->
            val ts = f.metadata?.get(CaptureResult.SENSOR_TIMESTAMP)
            if (f.metadata == null || ts == null || ts <= 0L) {
                rejectedByInvalid++
                recordPruneReason(
                    f,
                    "INVALID_METADATA_OR_SENSOR_TIMESTAMP"
                )
                false
            } else true
        }

        if (ignoreStale && prunedList.isNotEmpty()) {
            val currentTimeNs = android.os.SystemClock.elapsedRealtimeNanos()
            val preStaleSize = prunedList.size
            prunedList = prunedList.filter { f ->
                val ts = f.metadata?.get(CaptureResult.SENSOR_TIMESTAMP) ?: 0L
                val retainedByCurrentStalePredicate =
                    ((currentTimeNs - ts) / 1_000_000.0) < 2000.0
                if (!retainedByCurrentStalePredicate) {
                    recordPruneReason(
                        f,
                        "CURRENT_STALE_PREDICATE_ELAPSED_MINUS_SENSOR_GTE_2000MS"
                    )
                }
                retainedByCurrentStalePredicate
            }
            rejectedByStale = preStaleSize - prunedList.size
        }

        if (rejectDupes && prunedList.size > 1) {
            val preDupeSize = prunedList.size
            var lastTs = 0L
            prunedList = prunedList.filter { f ->
                val ts = f.metadata?.get(CaptureResult.SENSOR_TIMESTAMP) ?: 0L
                if (abs(ts - lastTs) > 10_000_000L) {
                    lastTs = ts; true
                } else {
                    recordPruneReason(
                        f,
                        "DUPLICATE_WITHIN_10MS_OF_PREVIOUS_ACCEPTED_TIMESTAMP"
                    )
                    false
                }
            }
            rejectedByDupes = preDupeSize - prunedList.size
        }

        emitCandidateTimingTelemetry(
            finalCandidates = prunedList,
            terminalStage = "ALL_PRE_SCORE_PRUNING_COMPLETE"
        )

        if (prunedList.isEmpty()) {
            throw IllegalStateException("No frame remains after the active single-frame selection policy.")
        }

        fun nativeSampleDomain(metadata: TotalCaptureResult?): Pair<Int, IntArray> {
            if (activeZslFormat == ImageFormat.YUV_420_888) return 255 to intArrayOf(0, 0, 0, 0)
            val dynamicWhite = metadata?.get(CaptureResult.SENSOR_DYNAMIC_WHITE_LEVEL)
            val staticWhite = chars.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL)
            val payloadWhite = (dynamicWhite ?: staticWhite ?: if (activeZslFormat == ImageFormat.RAW10) 1023 else 65535)
                .coerceIn(1, 65535)
            val dynamicBlack = metadata?.get(CaptureResult.SENSOR_DYNAMIC_BLACK_LEVEL)?.takeIf { it.size >= 4 }
            val staticBlack = chars.get(CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN)
            val payloadBlack = FloatArray(4) { index ->
                dynamicBlack?.get(index) ?: staticBlack?.getOffsetForIndex(index and 1, index shr 1)?.toFloat() ?: 0f
            }
            if (activeZslFormat != ImageFormat.RAW10) {
                return payloadWhite to IntArray(4) { payloadBlack[it].toInt().coerceIn(0, payloadWhite - 1) }
            }
            val nativeWhite = 1023
            val explicitPayloadToNativeScale = nativeWhite.toFloat() / payloadWhite.toFloat()
            return nativeWhite to IntArray(4) {
                (payloadBlack[it] * explicitPayloadToNativeScale).toInt().coerceIn(0, nativeWhite - 1)
            }
        }

        fun clockSafeCandidateDeltaMs(frame: ZslFramePair, sensorTimestampNs: Long): Double {
            if (shutterTimestampComparableToSensorTimestamp && sensorTimestampNs > 0L) {
                return (sensorTimestampNs - shutterTimestampNs) / 1_000_000.0
            }
            if (shutterTimestampDomain.startsWith("ELAPSED_REALTIME")) {
                val completionElapsedNs = frame.pairCompleteElapsedNs.takeIf { it > 0L }
                    ?: maxOf(frame.imageArrivalElapsedNs, frame.metadataArrivalElapsedNs)
                if (completionElapsedNs > 0L) {
                    return (completionElapsedNs - shutterTimestampNs) / 1_000_000.0
                }
            }
            // Unknown/non-comparable timing should not manufacture a huge stale penalty.
            return 0.0
        }

        // DELTA 0220: the three Near-ZSL candidates are independent, already-complete
        // HardwareBuffers. Analyze them concurrently instead of paying three serial CPU-read
        // sampling passes after shutter. awaitAll() preserves prunedList order, so scoring,
        // freshness, selection weights and tie-breaking remain byte-for-byte policy-equivalent.
        val analyzedCandidates = coroutineScope {
            prunedList.map { candidateFrame: ZslFramePair ->
                async(Dispatchers.Default) {
                    val metadata = candidateFrame.metadata
                    val tsNs = metadata?.get(CaptureResult.SENSOR_TIMESTAMP) ?: 0L
                    val expTimeNs = metadata?.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: 0L
                    val iso = metadata?.get(CaptureResult.SENSOR_SENSITIVITY) ?: 0
                    val deltaMs = clockSafeCandidateDeltaMs(candidateFrame, tsNs)
                    val syncProxy = max(
                        0.0,
                        1.0 - (
                                abs(deltaMs) /
                                        ShutterCandidateFreshnessPolicy
                                            .GENUINE_NEAR_ZSL_WINDOW_MS
                                )
                    ).coerceIn(0.0, 1.0)
                    val exposureMs = expTimeNs / 1_000_000.0
                    val brightnessProxy = if (expTimeNs > 0L && iso > 0) exposureMs * (iso / 100.0) else 20.0
                    val evProxy = when {
                        brightnessProxy < 2.0 -> 0.35
                        brightnessProxy > 120.0 -> 0.55
                        else -> 0.80
                    }
                    val sharpnessProxy = 0.50
                    val alignabilityProxy = (0.35 + (syncProxy * 0.65)).coerceIn(0.0, 1.0)
                    val buffer = candidateFrame.hardwareBuffer
                    val domain = nativeSampleDomain(metadata)
                    val pixelMetrics = if (buffer != null) {
                        ImageUtils.analyzeFrameCandidateSafe(
                            buffer, activeZslFormat, domain.first, domain.second
                        )
                    } else {
                        com.bncam.core.engine.FrameCandidatePixelMetrics(
                            false, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, "hardware_buffer_missing"
                        )
                    }

                    AnalysisWrapper(
                        frame = candidateFrame,
                        sharpness = if (pixelMetrics.valid) pixelMetrics.sharpnessScore else sharpnessProxy,
                        ev = if (pixelMetrics.valid) pixelMetrics.exposureScore else evProxy,
                        alignability = alignabilityProxy,
                        clippingScore = if (pixelMetrics.valid) pixelMetrics.clippingScore else 0.5,
                        lowClippedFraction = pixelMetrics.lowClippedFraction,
                        highClippedFraction = pixelMetrics.highClippedFraction,
                        source = pixelMetrics.source,
                        analysisTimeMs = pixelMetrics.analysisTimeMs
                    )
                }
            }.awaitAll()
        }

        if (analyzedCandidates.isEmpty()) {
            closeTakenFrames()
            return@withContext com.bncam.core.output.CaptureSubmissionResult.Rejected("candidates_empty")
        }

        // Apply dynamic heuristic selection logic:
        val evaluated = analyzedCandidates.mapIndexed { index, analysis: AnalysisWrapper ->
            val frame = analysis.frame
            val metadata = frame.metadata
            val tsNs = metadata?.get(CaptureResult.SENSOR_TIMESTAMP) ?: 0L
            val expTimeNs = metadata?.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: 0L
            val deltaMs = clockSafeCandidateDeltaMs(frame, tsNs)
            val oisTruth = resolveOisFrameTruth(metadata, activeLens.id)
            val oisMode = oisTruth.resolvedMode
            val lensState = metadata?.get(CaptureResult.LENS_STATE)
            val focusState = metadata?.get(CaptureResult.CONTROL_AF_STATE) ?: -1
            val motion = FrameMotionScorer.score(
                exposureTimeNs = expTimeNs,
                oisMode = oisMode,
                lensState = lensState,
                afState = focusState
            )

            val sharpnessScore = analysis.sharpness
            val motionScore = motion.score

            val evScore = analysis.ev
            val iso = metadata?.get(CaptureResult.SENSOR_SENSITIVITY) ?: 0
            val aeState = metadata?.get(CaptureResult.CONTROL_AE_STATE) ?: -1
            val awbState = metadata?.get(CaptureResult.CONTROL_AWB_STATE) ?: -1
            // AE/AWB/AF state keys are quality evidence, not frame-completeness evidence.
            // Camera2 HALs may legitimately omit one or more of them (fixed-focus lenses are a
            // common example). Keep the frame eligible when timestamp/exposure/ISO are valid;
            // the score functions already assign bounded neutral/low values to missing 3A states.
            val metadataComplete = metadata != null &&
                tsNs > 0L && expTimeNs > 0L && iso > 0
            val isStable = motionScore >= 0.6 && sharpnessScore >= 0.4 && evScore >= 0.4

            HeuristicCandidate(
                frame = frame,
                index = index,
                timestampNs = tsNs,
                deltaMs = deltaMs,
                sharpnessScore = sharpnessScore,
                motionScore = motionScore,
                motionProxy = motion.exposureDurationScore,
                motionScoreDetails = motion.diagnosticSummary(),
                evScore = evScore,
                alignabilityScore = analysis.alignability,
                clippingScore = analysis.clippingScore,
                isStable = isStable,
                metadataComplete = metadataComplete,
                aeState = aeState,
                awbState = awbState,
                focusState = focusState
            )
        }

        val selectionFreshnessWindowMs =
            ShutterCandidateFreshnessPolicy.GENUINE_NEAR_ZSL_WINDOW_MS
        val isTele = activeLens.name.contains("tele", ignoreCase = true) || activeLens.name.contains("3.4x", ignoreCase = true)
        val selectionResult = FrameSelectionEngine.selectBestCandidate(
            candidates = evaluated,
            freshnessWindowMs = selectionFreshnessWindowMs,
            isTele = isTele,
            nearZslFocusSelectionEnabled = nearZslFocusSelectionEnabled,
            focusCaptureContext = focusCaptureContext,
            settings = selectionSettings
        )
        val winningCandidate = selectionResult.selected.candidate

        val anchorFrame = winningCandidate.frame
        anchorLease = ringBuffer.leaseFrame(anchorFrame)
            ?: throw IllegalStateException(
                "Selected Near-ZSL frame could not be leased before render: " +
                    "generation=${anchorFrame.generationId} timestamp=${anchorFrame.timestamp}"
            )
        val frameSelectTimeMs = performanceTracker.mark("frame_select_time")
        performanceTracker.setMetric("selectedFrameCount", 1)
        performanceTracker.recordDuration("frame_selection", frameSelectTimeMs)
        val selectedSensorStartNs = anchorFrame.metadata?.get(CaptureResult.SENSOR_TIMESTAMP) ?: anchorFrame.timestamp
        val selectedExposureTimeNs = anchorFrame.metadata?.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: anchorFrame.exposureTimeNs
        val selectedRollingShutterSkewNs = anchorFrame.metadata?.get(CaptureResult.SENSOR_ROLLING_SHUTTER_SKEW) ?: 0L
        val selectedFullExposureEndNs = selectedSensorStartNs + selectedExposureTimeNs + selectedRollingShutterSkewNs
        val selectionReferenceTimestampNs = userShutterTimestampNs
        val selectedStartDeltaToUserShutterMs = if (sensorTimestampComparableToElapsedRealtime) {
            (selectedSensorStartNs - userShutterTimestampNs) / 1_000_000.0
        } else null
        val selectedEndDeltaToUserShutterMs = if (sensorTimestampComparableToElapsedRealtime) {
            (selectedFullExposureEndNs - userShutterTimestampNs) / 1_000_000.0
        } else null
        val selectedFullyPreShutter = com.bncam.core.buffer.NearZslEligibilityPolicy.isFullyPreShutter(
            pair = anchorFrame,
            userShutterTimestampNs = userShutterTimestampNs,
            shutterTimestampDomain = "ELAPSED_REALTIME"
        )

        Log.i(
            nearZslTimingTag,
            "event=NEAR_ZSL_SHUTTER_CONTRACT_PROOF " +
                    "userShutterTimestampNs=$userShutterTimestampNs " +
                    "selectionReferenceTimestampNs=$selectionReferenceTimestampNs " +
                    "selectedSensorStartNs=$selectedSensorStartNs " +
                    "selectedExposureTimeNs=$selectedExposureTimeNs " +
                    "selectedRollingShutterSkewNs=$selectedRollingShutterSkewNs " +
                    "selectedFullExposureEndNs=$selectedFullExposureEndNs " +
                    "selectedStartDeltaToUserShutterMs=${selectedStartDeltaToUserShutterMs?.let { String.format(Locale.US, "%.3f", it) } ?: "CLOCK_DOMAIN_UNAVAILABLE"} " +
                    "selectedEndDeltaToUserShutterMs=${selectedEndDeltaToUserShutterMs?.let { String.format(Locale.US, "%.3f", it) } ?: "CLOCK_DOMAIN_UNAVAILABLE"} " +
                    "selectedFullyPreShutter=$selectedFullyPreShutter"
        )

        val bufferHealth = ringBuffer.healthDiagnostics(userShutterTimestampNs)
        Log.i(
            nearZslTimingTag,
            "event=NEAR_ZSL_BUFFER_HEALTH_DIAGNOSTICS " +
                    "targetCapacity=${bufferHealth.targetCapacity} " +
                    "completeFrames=${bufferHealth.completeFrames} " +
                    "pendingPairs=${bufferHealth.pendingPairs} " +
                    "leasedFrames=${bufferHealth.leasedFrames} " +
                    "actualProducerHeadroom=${bufferHealth.actualProducerHeadroom} " +
                    "bufferSpanMs=${String.format(Locale.US, "%.3f", bufferHealth.bufferSpanMs)} " +
                    "oldestSensorAgeMs=${bufferHealth.oldestSensorAgeMs?.let { String.format(Locale.US, "%.3f", it) } ?: "null"} " +
                    "newestSensorAgeMs=${bufferHealth.newestSensorAgeMs?.let { String.format(Locale.US, "%.3f", it) } ?: "null"} " +
                    "evictions=${bufferHealth.evictions} " +
                    "pairingFailures=${bufferHealth.pairingFailures} " +
                    "fullyPreShutterCandidateCount=${bufferHealth.fullyPreShutterCandidateCount} " +
                    "bufferRefillState=${bufferHealth.bufferRefillState}"
        )

        if (enableShotLogger) {
            shotLogger.recordPipelineEvent(
                "Near-ZSL Buffer Diagnostics",
                "healthInfo",
                "targetCapacity=${bufferHealth.targetCapacity};" +
                        "completeFrames=${bufferHealth.completeFrames};" +
                        "pendingPairs=${bufferHealth.pendingPairs};" +
                        "leasedFrames=${bufferHealth.leasedFrames};" +
                        "actualProducerHeadroom=${bufferHealth.actualProducerHeadroom};" +
                        "bufferSpanMs=${String.format(Locale.US, "%.3f", bufferHealth.bufferSpanMs)};" +
                        "oldestSensorAgeMs=${bufferHealth.oldestSensorAgeMs?.let { String.format(Locale.US, "%.3f", it) } ?: "null"};" +
                        "newestSensorAgeMs=${bufferHealth.newestSensorAgeMs?.let { String.format(Locale.US, "%.3f", it) } ?: "null"};" +
                        "evictions=${bufferHealth.evictions};" +
                        "pairingFailures=${bufferHealth.pairingFailures};" +
                        "fullyPreShutterCandidateCount=${bufferHealth.fullyPreShutterCandidateCount};" +
                        "bufferRefillState=${bufferHealth.bufferRefillState}"
            )
        }

        captureTrace.record(
            com.bncam.core.tracing.CaptureTraceSection.FRAME_SELECTION,
            "shutterContractProof",
            "userShutterTimestampNs=$userShutterTimestampNs;" +
                    "selectionReferenceTimestampNs=$selectionReferenceTimestampNs;" +
                    "selectedSensorStartNs=$selectedSensorStartNs;" +
                    "selectedExposureTimeNs=$selectedExposureTimeNs;" +
                    "selectedRollingShutterSkewNs=$selectedRollingShutterSkewNs;" +
                    "selectedFullExposureEndNs=$selectedFullExposureEndNs;" +
                    "selectedStartDeltaToUserShutterMs=${selectedStartDeltaToUserShutterMs?.let { String.format(Locale.US, "%.3f", it) } ?: "CLOCK_DOMAIN_UNAVAILABLE"};" +
                    "selectedEndDeltaToUserShutterMs=${selectedEndDeltaToUserShutterMs?.let { String.format(Locale.US, "%.3f", it) } ?: "CLOCK_DOMAIN_UNAVAILABLE"};" +
                    "selectedFullyPreShutter=$selectedFullyPreShutter"
        )

        if (!requirePostShutter) {
            require(selectedFullyPreShutter) {
                "Near-ZSL Single Frame contract violated: selectedFullExposureEndNs ($selectedFullExposureEndNs) > userShutterTimestampNs ($userShutterTimestampNs)"
            }
        }

        val selectedFrameTimestampNs = anchorFrame.timestamp
        val selectedFrameGeneration = anchorFrame.generationId
        val selectedDeltaMs = if (sensorTimestampComparableToElapsedRealtime) {
            (anchorFrame.timestamp - userShutterTimestampNs) / 1_000_000.0
        } else {
            val completionElapsedNs = anchorFrame.pairCompleteElapsedNs.takeIf { it > 0L }
                ?: maxOf(anchorFrame.imageArrivalElapsedNs, anchorFrame.metadataArrivalElapsedNs)
            if (completionElapsedNs > 0L) {
                (completionElapsedNs - userShutterTimestampNs) / 1_000_000.0
            } else 0.0
        }
        val selectedImageTimestampNs = try {
            anchorFrame.image?.timestamp
        } catch (_: IllegalStateException) {
            null
        }
        val selectedMetadataTimestampNs =
            anchorFrame.metadata?.get(CaptureResult.SENSOR_TIMESTAMP)
        if (
            selectedImageTimestampNs == null ||
            selectedMetadataTimestampNs == null ||
            selectedImageTimestampNs != selectedFrameTimestampNs ||
            selectedMetadataTimestampNs != selectedFrameTimestampNs
        ) {
            closeTakenFrames()
            throw IllegalStateException(
                "Selected Near-ZSL frame binding changed before render: " +
                        "pairTimestampNs=$selectedFrameTimestampNs " +
                        "imageTimestampNs=$selectedImageTimestampNs " +
                        "metadataTimestampNs=$selectedMetadataTimestampNs"
            )
        }
        val anchorBuffer = anchorFrame.hardwareBuffer ?: run {
            closeTakenFrames()
            return@withContext com.bncam.core.output.CaptureSubmissionResult.Rejected("anchor_buffer_null")
        }
        captureStageListener.sourceImageAcquired()
        val captureMetadata: TotalCaptureResult? = anchorFrame.metadata
        val isRawFrameSource =
            activeZslFormat == ImageFormat.RAW10 ||
                activeZslFormat == ImageFormat.RAW_SENSOR
        val exactRawAuthorityInput = if (isRawFrameSource) {
            com.bncam.core.quality.PhysicalSensorProfileRegistry.resolveCurrentCalibrationInput(
                fallbackCharacteristics = chars,
                captureResult = captureMetadata,
                sensorMetadata = anchorFrame.sensorMetadataSnapshot
            ).also { authority ->
                val expectedSource = anchorFrame.sensorMetadataSnapshot?.sensorIdentity?.sourceId
                if (expectedSource == null || authority.sensorIdentity?.sourceId != expectedSource) {
                    throw com.bncam.core.quality.SensorAuthorityUnavailableException(
                        "UNSAFE_TO_PROCESS:RAW_MATERIALIZATION_AUTHORITY_MISMATCH"
                    )
                }
            }
        } else null
        val rawAuthorityCharacteristics = exactRawAuthorityInput?.characteristics ?: chars
        val rawAuthorityCaptureResult = exactRawAuthorityInput?.captureResult ?: captureMetadata
        val colorSensorReading = if (phoneAssistanceSensorsEnabled) {
            colorSensorHelper.getBestReading(captureMetadata)
        } else {
            com.bncam.core.model.ColorSensorReading()
        }
        val colorSensorContributionWeight =
            if (phoneAssistanceSensorsEnabled && colorSensorReading.isValid) 0.12f else 0.0f
        if (enableShotLogger) {
            shotLogger.recordPipelineEvent("Phone Assistance Sensors", "Enabled", phoneAssistanceSensorsEnabled.toString())
            shotLogger.recordPipelineEvent("Phone Assistance Sensors", "Android sensor", colorSensorHelper.auxiliaryManager.selectedSensorName ?: "none")
            shotLogger.recordPipelineEvent("Phone Assistance Sensors", "Capability", colorSensorHelper.auxiliaryManager.selectedCapability.name)
            shotLogger.recordPipelineEvent("Phone Assistance Sensors", "Listener active", colorSensorHelper.auxiliaryManager.isListening.toString())
            shotLogger.recordPipelineEvent("Phone Assistance Sensors", "Reading source", colorSensorReading.sourceId)
            shotLogger.recordPipelineEvent("Phone Assistance Sensors", "Reading capability", colorSensorReading.capability)
            shotLogger.recordPipelineEvent("Phone Assistance Sensors", "Reading valid", colorSensorReading.isValid.toString())
            shotLogger.recordPipelineEvent("Phone Assistance Sensors", "CCT", if (colorSensorReading.isValid) "${colorSensorReading.cctKelvin} K" else "unavailable")
            shotLogger.recordPipelineEvent("Phone Assistance Sensors", "ISP contribution", colorSensorContributionWeight.toString())
        }
        val selectedFrameProvenanceProof = SelectedFrameProvenanceValidator.verify(
            framePipelineGeneration = anchorFrame.generationId,
            frameControlRequestEpoch = anchorFrame.controlRequestEpoch,
            frameTimestampNs = anchorFrame.timestamp,
            metadataTimestampNs = selectedMetadataTimestampNs,
            provenance = anchorFrame.requestProvenance
        )
        val selectedRequestSnapshot = selectedFrameProvenanceProof.snapshot

        val scoredCandidates = selectionResult.ranked.map { selectionScore ->
            val cand = selectionScore.candidate
            val pixelAnalysis = analyzedCandidates.first { it.frame === cand.frame }

            Triple(
                cand.frame,
                selectionScore.overallScore,
                CandidateScoreInfo(
                    log = selectionResult.anchorSelectionReason,
                    align = cand.alignabilityScore,
                    delta = cand.deltaMs,
                    sharpnessScore = cand.sharpnessScore,
                    motionScore = cand.motionScore,
                    evScore = cand.evScore,
                    clippingScore = cand.clippingScore,
                    analysisSource = pixelAnalysis.source,
                    lowClippedFraction = pixelAnalysis.lowClippedFraction,
                    highClippedFraction = pixelAnalysis.highClippedFraction,
                    syncScore = selectionScore.freshnessScore,
                    candidateAgeAbsMs = selectionScore.candidateAgeAbsMs,
                    freshEnoughForSelection = selectionScore.freshEnoughForSelection,
                    rejectedForStaleSelection = selectionScore.rejectedForStaleSelection,
                    temporalBiasContribution =
                        selectionScore.temporalBiasContribution,
                    overallScore = selectionScore.overallScore
                )
            )
        }

        val selectedAnchorRank = scoredCandidates.indexOfFirst { it.first === anchorFrame }
        val totalAlign = scoredCandidates.sumOf { it.third.align }

        val frameWidth = anchorBuffer.width
        val frameHeight = anchorBuffer.height

        val raw10CfaForDebug = chars.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT)?.toString() ?: "not reported"

        val renderQualityConfig = RenderQualityConfig.load(
            repo = settingsRepo,
            profileId = profileId,
            frameSourceFormat = activeZslFormat,
            captureMode = activeProfile.captureStrategy,
            characteristics = chars,
            captureResult = captureMetadata,
            sensorMetadata = anchorFrame.sensorMetadataSnapshot,
            lensHardwareSettings = lensHardwareSettings,
            preferenceSnapshot = capturedSettings.renderPreferences,
            stableAutoWhiteBalance = stableAutoWhiteBalance
        )
        if (enableShotLogger) {
            // SingleFrameRunner previously logged only the narrow libpatcher resolver snapshot.
            // The actual profile-scoped RAW controls are resolved by RenderQualityConfig, so omitting
            // this block made working tone/color/detail/noise settings look dead in 03_PROFILE_SETTINGS.
            renderQualityConfig.debugPairs().forEach { (key, value) ->
                shotLogger.recordPipelineEvent("Quality Config", key, value)
            }
            // Phase 1 single-frame truth instrumentation: keep the physical calibration in its
            // own stable diagnostics group as well. RenderQualityConfig also exposes these values,
            // but a dedicated group prevents the central ISP report from inventing defaults when
            // a field is genuinely unavailable and makes RAW10/RAW_SENSOR captures comparable.
            renderQualityConfig.finalCalibration?.debugPairs()?.forEach { (key, value) ->
                shotLogger.recordPipelineEvent("Sensor Calibration Detail", key, value)
            }
            renderQualityConfig.commonPostRender?.debugPairs()?.forEach { (key, value) ->
                shotLogger.recordPipelineEvent("Common Post-Render", key, value)
            }
            renderQualityConfig.outputEncode?.debugPairs()?.forEach { (key, value) ->
                shotLogger.recordPipelineEvent("Output Encode", key, value)
            }
        }
        val rawJpegContractFailure = when {
            !plan.outputPolicy.producesJpeg || !renderQualityConfig.isRawPipeline -> null
            !renderQualityConfig.cfaSupportedForRawJpeg ->
                "RAW JPEG requires a supported Bayer CFA; lens reports ${renderQualityConfig.cfaName}."
            renderQualityConfig.finalCalibration?.effectiveWbApplied != true ->
                "RAW JPEG requires valid per-frame or calibrated white-balance metadata."
            renderQualityConfig.finalCalibration?.effectiveColorMatrixApplied != true ->
                "RAW JPEG requires a validated Camera2 color transform."
            else -> null
        }

        val frameDebugEntries = if (enableShotLogger) {
            scoredCandidates.mapIndexed { index, scoredData: Triple<ZslFramePair, Double, CandidateScoreInfo> ->
                val frame = scoredData.first
                val metadata = frame.metadata
                val hwBuffer = frame.hardwareBuffer
                val metadataTimestampNs = metadata?.get(CaptureResult.SENSOR_TIMESTAMP) ?: 0L
                val timestampNs = if (metadataTimestampNs > 0L) metadataTimestampNs else frame.timestamp
                val exposureTimeNs = metadata?.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: 0L
                val frameIso = metadata?.get(CaptureResult.SENSOR_SENSITIVITY) ?: 0
                val candidateAeState = metadata?.get(CaptureResult.CONTROL_AE_STATE) ?: -1
                val candidateAwbState = metadata?.get(CaptureResult.CONTROL_AWB_STATE) ?: -1
                val candidateFocusState = metadata?.get(CaptureResult.CONTROL_AF_STATE) ?: -1
                val metadataComplete = metadata != null &&
                        metadataTimestampNs > 0L &&
                        exposureTimeNs > 0L &&
                        frameIso > 0
                val scoredCandidate = selectionResult.ranked.first {
                    it.candidate.frame === frame
                }.candidate
                val weights = selectionResult.weights
                val scoreComponentsUsed = buildList {
                    add(if (metadataTimestampNs > 0L) "freshness_${weights.freshness}_sensor_timestamp" else if (frame.timestamp > 0L) "freshness_${weights.freshness}_frame_timestamp" else "freshness_unavailable")
                    add("motion_${weights.motion}_${scoredCandidate.motionScoreDetails}")
                    add("exposure_${weights.exposure}_pixel_mean_and_clipping")
                    add("sharpness_${weights.sharpness}_sampled_pixels")
                    add("clipping_${weights.clipping}_sampled_pixels")
                    add("alignability_${weights.alignability}_proxy")
                    if (candidateAeState >= 0) add("ae_${weights.ae}_state") else add("ae_${weights.ae}_unavailable")
                    if (candidateAwbState >= 0) add("awb_${weights.awb}_state") else add("awb_${weights.awb}_unavailable")
                    if (candidateFocusState >= 0) add("focus_${weights.focus}_state") else add("focus_${weights.focus}_unavailable")
                    val trackedEvidence = selectionResult.ranked.first { it.candidate.frame === frame }.trackedSubjectEvidence
                    if (focusCaptureContext.reliableTrackedSubject) {
                        add(if (trackedEvidence) "tracked_subject_roi_focus" else "tracked_subject_roi_missing")
                    }
                }.joinToString(",")
                val isSelected = frame === anchorFrame
                val rejectedForStaleSelection = scoredData.third.rejectedForStaleSelection
                fun deliveryLagMs(arrivalElapsedNs: Long): Double? {
                    return if (
                        frame.sensorTimestampComparableToElapsedRealtime &&
                        timestampNs > 0L &&
                        arrivalElapsedNs > 0L
                    ) {
                        (arrivalElapsedNs - timestampNs) / 1_000_000.0
                    } else {
                        null
                    }
                }
                val oisTruth = resolveOisFrameTruth(metadata, activeLens.id)
                val frameTimestampSourceLabel = when (frame.timestampSource) {
                    CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME ->
                        "REALTIME"
                    CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_UNKNOWN ->
                        "UNKNOWN"
                    else -> "UNRECOGNIZED(${frame.timestampSource})"
                }
                val sensorSnapshot = frame.sensorMetadataSnapshot
                val sensorIdentity = sensorSnapshot?.sensorIdentity
                val frameIdentity = sensorSnapshot?.frameIdentityForRaw(frame.timestamp)

                FrameAnalysisDebugEntry(
                    index = index,
                    timestampNs = timestampNs,
                    deltaToShutterMs = scoredData.third.delta,
                    shutterRelation = if (timestampNs <= 0L) "UNKNOWN_TIMESTAMP" else if (timestampNs < shutterTimestampNs) "PRE_SHUTTER" else "POST_SHUTTER",
                    exposureTimeNs = exposureTimeNs,
                    iso = frameIso,
                    format = activeBufferFormatLabel,
                    width = hwBuffer?.width ?: frameWidth,
                    height = hwBuffer?.height ?: frameHeight,
                    metadataValid = metadata != null && metadataTimestampNs > 0L,
                    stale = timestampNs <= 0L || scoredData.third.candidateAgeAbsMs > selectionFreshnessWindowMs,
                    duplicate = false,
                    accepted = isSelected,
                    rejectionReason = when {
                        isSelected -> "ACCEPTED_ANCHOR"
                        rejectedForStaleSelection -> "Rejected by shutter-relative freshness gate"
                        else -> "Lower weighted selection score"
                    },
                    sharpnessScore = scoredData.third.sharpnessScore,
                    motionScore = scoredData.third.motionScore,
                    evScore = scoredData.third.evScore,
                    alignabilityScore = scoredData.third.align,
                    syncScore = scoredData.third.syncScore,
                    temporalBiasContribution = scoredData.third.temporalBiasContribution,
                    overallScore = scoredData.third.overallScore,
                    finalRank = index + 1,
                    selectedAnchor = isSelected,
                    decisionReason = if (isSelected) selectionResult.anchorSelectionReason else if (rejectedForStaleSelection) "stale_while_fresh_candidate_available" else "lower_weighted_score",
                    analysisSource = scoredData.third.analysisSource,
                    candidateTimestampNs = timestampNs,
                    candidateDeltaMs = if (timestampNs > 0L) (timestampNs - shutterTimestampNs) / 1_000_000.0 else 0.0,
                    candidateIso = frameIso,
                    candidateExposureNs = exposureTimeNs,
                    candidateAeState = candidateAeState,
                    candidateAwbState = candidateAwbState,
                    candidateFocusState = candidateFocusState,
                    candidateMetadataComplete = metadataComplete,
                    candidateAgeAbsMs = scoredData.third.candidateAgeAbsMs,
                    candidateFreshEnoughForSelection = scoredData.third.freshEnoughForSelection,
                    candidateRejectedForStaleSelection = rejectedForStaleSelection,
                    scoreComponentsUsed = scoreComponentsUsed,
                    pipelineGeneration = frame.generationId,
                    controlRequestEpoch = frame.controlRequestEpoch,
                    requestProvenanceStatus =
                        frame.requestProvenance?.associationStatus ?: "UNPROVEN",
                    sensorAuthorityId = sensorIdentity?.sensorAuthorityId ?: "UNAVAILABLE",
                    cameraDeviceId = sensorIdentity?.cameraDeviceId ?: "UNAVAILABLE",
                    physicalCameraId = sensorIdentity?.physicalLabel ?: "UNAVAILABLE",
                    rawSourceId = sensorSnapshot?.rawSourceId ?: "UNAVAILABLE",
                    captureResultSourceId = sensorSnapshot?.captureResultSourceId ?: "UNAVAILABLE",
                    characteristicsSourceId = sensorSnapshot?.characteristicsSourceId ?: "UNAVAILABLE",
                    calibrationSourceId = sensorSnapshot?.calibrationSourceId ?: "UNAVAILABLE",
                    sensorAuthorityFrameNumber = sensorSnapshot?.captureIdentity?.frameNumber ?: -1L,
                    captureSequenceId = sensorSnapshot?.captureIdentity?.captureSequenceId ?: -1,
                    sensorMetadataTimestampNs = sensorSnapshot?.captureIdentity?.sensorTimestampNs ?: 0L,
                    rawMetadataTimestampMatch = frameIdentity?.rawMetadataTimestampMatch ?: false,
                    sensorAuthorityFallbackUsed =
                        sensorSnapshot?.let { it.logicalMetadataFallbackUsed || it.foreignSensorMetadataUsed } ?: false,
                    rawProcessingSafe = frameIdentity?.safeForRawProcessing == true &&
                        sensorSnapshot?.coreRawMetadataValid == true,
                    sensorAuthorityStatus = when {
                        frameIdentity == null -> "UNAVAILABLE"
                        !frameIdentity.safeForRawProcessing -> frameIdentity.rejectionReason()
                        sensorSnapshot?.coreRawMetadataValid != true ->
                            "UNSAFE_TO_PROCESS:${sensorSnapshot?.coreRawMetadataStatus ?: "SENSOR_METADATA_UNAVAILABLE"}"
                        else -> "NONE"
                    },
                    sensorMetadataAuditLines = sensorSnapshot?.debugAuditLines().orEmpty(),
                    imageArrivalElapsedNs = frame.imageArrivalElapsedNs,
                    metadataArrivalElapsedNs = frame.metadataArrivalElapsedNs,
                    pairCompleteElapsedNs = frame.pairCompleteElapsedNs,
                    imageDeliveryLagMs =
                        deliveryLagMs(frame.imageArrivalElapsedNs),
                    metadataDeliveryLagMs =
                        deliveryLagMs(frame.metadataArrivalElapsedNs),
                    pairCompletionLagMs =
                        deliveryLagMs(frame.pairCompleteElapsedNs),
                    timestampSource =
                        "$frameTimestampSourceLabel(${frame.timestampSource})",
                    sensorTimestampComparableToElapsedRealtime =
                        frame.sensorTimestampComparableToElapsedRealtime,
                    shutterDeltaClockDomainsComparable =
                        shutterTimestampComparableToSensorTimestamp,
                    preScorePruneResult = "KEPT_FOR_SCORING",
                    preScorePruneReason = "NONE",
                    oisLogicalMode = oisModeLabel(oisTruth.logicalMode),
                    oisPhysicalMode = oisModeLabel(oisTruth.physicalMode),
                    oisSampleSource = oisTruth.sampleSource,
                    oisSampleCount = oisTruth.sampleCount,
                    oisShiftRmsPx = oisTruth.shiftRmsPx,
                    oisShiftPeakPx = oisTruth.shiftPeakPx
                )
            }
        } else {
            emptyList()
        }

        if (enableShotLogger) {
            shotLogger.recordFrameAnalysisEntries(frameDebugEntries)
        }

        // SPECTRA observes one real compatible warm-buffer support frame even in
        // single-frame mode. It is aligned and measured natively but never fused into
        // the single-frame output. This replaces the invalid render-to-render observer.
        val spectraObserverFrame: ZslFramePair? = if (
            isRawFrameSource && renderQualityConfig.finalCalibration?.noiseSnapshot?.isSpectraActive() == true
        ) {
            val anchorExposure = captureMetadata?.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: 0L
            val anchorIso = captureMetadata?.get(CaptureResult.SENSOR_SENSITIVITY) ?: 0
            scoredCandidates.asSequence()
                .map { it.first }
                .filter { candidate ->
                    if (candidate === anchorFrame || candidate.hardwareBuffer == null) return@filter false
                    if (candidate.generationId != anchorFrame.generationId ||
                        candidate.controlRequestEpoch != anchorFrame.controlRequestEpoch) return@filter false
                    val metadata = candidate.metadata ?: return@filter false
                    val exposure = metadata.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: return@filter false
                    val iso = metadata.get(CaptureResult.SENSOR_SENSITIVITY) ?: return@filter false
                    val exposureCompatible = anchorExposure > 0L &&
                        abs(exposure.toDouble() / anchorExposure.toDouble() - 1.0) <= 0.08
                    val isoCompatible = anchorIso > 0 &&
                        abs(iso.toDouble() / anchorIso.toDouble() - 1.0) <= 0.08
                    val temporallyClose = abs(candidate.timestamp - anchorFrame.timestamp) <= 500_000_000L
                    exposureCompatible && isoCompatible && temporallyClose
                }
                .minByOrNull { candidate -> abs(candidate.timestamp - anchorFrame.timestamp) }
        } else {
            null
        }
        performanceTracker.setMetric("spectraObserverFrameAvailable", spectraObserverFrame != null)

        // Release all unselected candidate leases.
        val selectedControlRequestEpochSnapshot = anchorFrame.controlRequestEpoch
        val selectedRequestProvenanceSnapshot = anchorFrame.requestProvenance
        @Suppress("NAME_SHADOWING")
        val shotLogger = if (isRawFrameSource && enableShotLogger) {
            shotLogger.forkForDeferredWork()
        } else {
            shotLogger
        }
        val rawWorkReservation = if (isRawFrameSource) {
            CaptureProcessingQueue.tryReserve(
                route = plan.route.id,
                captureStartedNs = shutterTimestampNs,
                temporaryPreviewPath = temporaryPreviewPath
            )?.also {
                rawWorkReservationForCleanup = it
                performanceTracker.setMetric("processingWorkId", it.workId)
                performanceTracker.setMetric("shotSequenceId", it.shotSequenceId)
            } ?: run {
                throw IllegalStateException(
                    "RAW processing queue is full; capture was not accepted."
                )
            }
        } else {
            null
        }
        val rawBuildStartedNs =
            if (isRawFrameSource) android.os.SystemClock.elapsedRealtimeNanos() else 0L
        val acquiredRawInput: Raw16RenderInput? = if (isRawFrameSource) {
            try {
                // DELTA 0217: Stage-A RAW column/payload auditing is diagnostics-only here.
                // Its return value was discarded and it does not participate in frame selection,
                // RAW-domain construction, publication integrity, DNG export, or JPEG pixels.
                // Keep the auditor available for explicit diagnostics, but do not scan the full
                // acquired RAW image on every shutter-critical single-frame capture.
                withContext(rawMaterializationDispatcher) {
                    val predictiveEstimate = ringBuffer.predictiveAfTracker
                        ?.predictFocusDistance(anchorFrame.timestamp)
                    val demosaicAfHints = DemosaicAfHints(
                        focusScore = anchorFrame.focusScore,
                        focusConfidence = anchorFrame.focusConfidence,
                        confidenceState = anchorFrame.confidenceState,
                        afState = anchorFrame.afState,
                        lensState = anchorFrame.lensState,
                        lensFocusDistance = anchorFrame.lensFocusDistance,
                        focusVelocityDioptersPerSec = predictiveEstimate?.focusVelocityDioptersPerSec ?: 0f,
                        predictiveConfidence = predictiveEstimate?.confidence ?: 0f
                    )
                    SingleRaw16FrameBuilder.build(
                        lensId = activeLens.id,
                        buffer = anchorBuffer,
                        observerBuffer = spectraObserverFrame?.hardwareBuffer,
                        sourceFormat = activeZslFormat,
                        width = frameWidth,
                        height = frameHeight,
                        characteristics = rawAuthorityCharacteristics,
                        captureResult = rawAuthorityCaptureResult,
                        qualityConfig = renderQualityConfig,
                        orientationDegrees = finalJpegRotation,
                        demosaicAfHints = demosaicAfHints,
                        phoneAssistanceSensorsEnabled = phoneAssistanceSensorsEnabled,
                        colorSensorReading = colorSensorReading,
                        colorSensorContributionWeight = colorSensorContributionWeight
                    )
                }
            } catch (failure: Throwable) {
                rawWorkReservation?.fail(
                    "raw16_materialization_failed:${failure.javaClass.simpleName}"
                )
                throw failure
            } finally {
                // Native RAW16 materialization finished. Leases remain managed by outer finally.
            }
        } else {
            null
        }
        val rawUnpackMs = if (isRawFrameSource) {
            (android.os.SystemClock.elapsedRealtimeNanos() - rawBuildStartedNs) / 1_000_000.0
        } else {
            0.0
        }
        acquiredRawInputForCleanup = acquiredRawInput
        if (isRawFrameSource && acquiredRawInput == null) {
            rawWorkReservation?.fail("raw16_materialization_returned_empty")
            throw IllegalStateException(
                "Single RAW16 materialization failed; no safe processing input exists."
            )
        }
        if (isRawFrameSource) {
            performanceTracker.mark("raw_unpack_time")
            performanceTracker.recordDuration("raw_materialization", rawUnpackMs)
            performanceTracker.setMetric("immutableRaw16ByteCount", acquiredRawInput?.raw16ByteCount ?: 0)
            performanceTracker.setMetric("raw16NativeOwner", "NativeRaw16Buffer")
            performanceTracker.setMetric("raw16OwnershipContract", "NATIVE_DIRECT_BUFFER_V1")
            performanceTracker.setMetric("raw16JavaRoundTripEliminated", true)
            performanceTracker.setMetric("raw16Phase2ImportBoundary", "DIRECT_BYTE_BUFFER")
            performanceTracker.setMetric(
                "raw16NativeOutstandingBuffersAtAcquisition",
                ImageUtils.nativeRaw16OutstandingBufferCountSafe()
            )
            Log.i(
                "BnCamCaptureTiming",
                "route=${plan.route.id} lifecycle=CAPTURE_ACQUISITION_COMPLETE " +
                    "raw_unpack_time=${String.format(Locale.US, "%.3f", rawUnpackMs)} " +
                    "originalHardwareBufferReleased=true immutableRaw16Bytes=" +
                    "${acquiredRawInput?.raw16ByteCount ?: 0} " +
                    "processingWorkId=${rawWorkReservation?.workId}"
            )
        }
        val deferredQualificationFrame = if (isRawFrameSource) {
            ZslFramePair().apply {
                timestamp = selectedFrameTimestampNs
                metadata = captureMetadata
                format = activeZslFormat
                generationId = selectedFrameGeneration
                controlRequestEpoch = selectedControlRequestEpochSnapshot
                requestProvenance = selectedRequestProvenanceSnapshot
            }
        } else {
            anchorFrame
        }

        if (plan.outputPolicy == OutputPolicy.RAW_ONLY) {
            require(
                plan.route == CaptureRoute.SingleRaw10RawOnly ||
                    plan.route == CaptureRoute.SingleRawSensorRawOnly
            ) { "RAW-only was routed to ${plan.route.id}, which is not a single RAW-only path." }
            val baseFilename = "$photoPrefix${SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())}"
            val rawInput = acquiredRawInput
                ?: throw IllegalStateException("Single RAW16 unpack failed; RAW-only output was not created.")
            performanceTracker.mark("raw_unpack_time")
            val dngUri = preInsertImageToMediaStore("$baseFilename.dng", saveLocation, "image/x-adobe-dng")
                ?: run {
                    rawWorkReservation?.fail("raw_only_dng_reservation_failed")
                    throw IllegalStateException("RAW-only DNG MediaStore reservation failed.")
                }
            val metadata = rawAuthorityCaptureResult
                ?: run {
                    rawWorkReservation?.fail("raw_only_metadata_missing")
                    runCatching { context.contentResolver.delete(dngUri, null, null) }
                    throw IllegalStateException("RAW-only DNG requires complete capture metadata.")
                }
            val reservation = requireNotNull(rawWorkReservation)
            val queued = CaptureProcessingQueue.submit(context, reservation) { work ->
                work.markSaving()
                val saveAccepted = CaptureSaveQueue.enqueue(
                    context = context,
                    route = plan.route.id,
                    captureStartedNs = shutterTimestampNs,
                    onFailure = { failure ->
                        if (enableShotLogger) {
                            shotLogger.writeCaptureTrace(
                                captureTrace.completeFailure("raw_only_publication", failure)
                            )
                            shotLogger.finalizeFailureWithPublicDiagnostics(
                                attemptId = attemptId,
                                stage = "ASYNC_RAW_ONLY_PUBLICATION",
                                exception = failure
                            )
                        }
                        runCatching { context.contentResolver.delete(dngUri, null, null) }
                        performanceTracker.persistJsonLine(
                            context = context,
                            status = "FAILED",
                            failureReason = "save_failed:${failure.javaClass.simpleName}:${failure.message}"
                        )
                        work.fail("save_failed:${failure.javaClass.simpleName}")
                    }
                ) {
                    try {
                        val rawExportStartedNs = android.os.SystemClock.elapsedRealtimeNanos()
                        val rawMaterializeStartedNs = android.os.SystemClock.elapsedRealtimeNanos()
                    val raw16ForDng = rawInput.materializeRaw16ForDng()
                    com.bncam.core.isp.raw.RawColumnStatsAuditor.auditRaw16ByteArray("Stage-C (Materialized RAW16)", raw16ForDng, rawInput.width, rawInput.height)
                    performanceTracker.recordDuration(
                        "raw16_dng_materialization",
                        (android.os.SystemClock.elapsedRealtimeNanos() - rawMaterializeStartedNs) / 1_000_000.0
                    )
                    performanceTracker.incrementCounter("raw16ManagedHeapMaterializationCount")
                    performanceTracker.setMetric("raw16ManagedHeapMaterializationBytes", raw16ForDng.size)
                    writeVirtualDngToReservedUri(
                        uri = dngUri,
                        raw16Bytes = raw16ForDng,
                        width = rawInput.width,
                        height = rawInput.height,
                        metadata = metadata,
                        characteristics = rawAuthorityCharacteristics,
                        orientation = getExifOrientation(finalJpegRotation),
                        dngMergeStats = rawInput.dngMergeStats,
                        lensHardwareDescription = lensHardwareSettings.dngDescription(),
                        rawDomainContract = rawInput.rawFrameInfo,
                        calibration = rawInput.finalCalibration
                    )
                    performanceTracker.recordDuration(
                        "dng_export_and_mediastore_write",
                        (android.os.SystemClock.elapsedRealtimeNanos() - rawExportStartedNs) / 1_000_000.0
                    )
                    performanceTracker.incrementCounter("dngMediaStorePublicationCount")
                    performanceTracker.incrementCounter("terminalPublicationCount")
                    performanceTracker.setMetric("genuinelyFusedFrameCount", 0)
                    performanceTracker.setMetric("dngRaw16ByteCount", rawInput.raw16ByteCount)
                    performanceTracker.setMetric(
                        "raw16ManagedHeapMaterializationCount",
                        rawInput.managedMaterializationCount
                    )
                    performanceTracker.setMetric(
                        "raw16ManagedHeapMaterializationBytes",
                        rawInput.managedMaterializationBytes
                    )
                    check(rawInput.managedMaterializationCount == 1) {
                        "Phase 1B invariant failed: RAW-only capture materialized RAW16 " +
                            "${rawInput.managedMaterializationCount} times."
                    }
                    check(performanceTracker.counter("rawIspInvocationCount") == 0) {
                        "Phase 0 invariant failed: RAW-only single capture invoked the JPEG ISP."
                    }
                    check(performanceTracker.counter("rawMergeInvocationCount") == 0) {
                        "Phase 0 invariant failed: single-frame capture invoked RAW merge."
                    }
                    check(performanceTracker.counter("jpegEncodeInvocationCount") == 0) {
                        "RAW-only invariant failed: single capture invoked JPEG encoding."
                    }
                    check(performanceTracker.counter("jpegMediaStorePublicationCount") == 0) {
                        "RAW-only invariant failed: single capture published a hidden JPEG."
                    }
                    performanceTracker.setMetric("rawOnlyRgbIspExecuted", false)
                    performanceTracker.setMetric("rawOnlyJpegEncoded", false)
                    performanceTracker.setMetric("rawOnlyHiddenJpegCreated", false)
                    captureTrace.record(
                        com.bncam.core.tracing.CaptureTraceSection.ISP_EXECUTION,
                        "rawOnlyRgbIspExecuted",
                        false
                    )
                    captureTrace.record(
                        com.bncam.core.tracing.CaptureTraceSection.ISP_EXECUTION,
                        "rawOnlyJpegEncoded",
                        false
                    )
                    captureTrace.record(
                        com.bncam.core.tracing.CaptureTraceSection.OUTPUT_AND_PUBLICATION,
                        "rawOnlyHiddenJpegCreated",
                        false
                    )
                    rawInput.close()
                    performanceTracker.setMetric(
                        "raw16NativeOwnerReleasedBeforePublication",
                        rawInput.nativeRaw16Buffer.isClosed
                    )
                    performanceTracker.setMetric(
                        "raw16NativeOutstandingBuffersAtPublication",
                        ImageUtils.nativeRaw16OutstandingBufferCountSafe()
                    )
                    acquiredRawInputForCleanup = null
                    performanceTracker.mark("publication_complete")
                    val rawOnlyOutputs =
                        com.bncam.core.output.PublicationPolicyResolver.resolveStrings(
                            outputPolicy = OutputPolicy.RAW_ONLY,
                            jpegSucceeded = false,
                            jpegUri = null,
                            dngSucceeded = true,
                            dngUri = dngUri.toString()
                        )
                    captureTrace.decision(
                        section = com.bncam.core.tracing.CaptureTraceSection.FRAME_SELECTION,
                        key = "executedFrameSelection",
                        requested = recipe.frameSelectionMethod.requestedId,
                        supported = recipe.frameSelectionMethod.supported.toString(),
                        resolved = recipe.frameSelectionMethod.resolvedId,
                        executed = "balanced",
                        result = "selected_timestamp_$selectedFrameTimestampNs",
                        fallback = false,
                        reason = "single_frame_weighted_candidate_selection"
                    )
                    captureTrace.decision(
                        section = com.bncam.core.tracing.CaptureTraceSection.ALIGNMENT,
                        key = "executedAlignment",
                        requested = recipe.alignmentMethod.requestedId,
                        supported = recipe.alignmentMethod.supported.toString(),
                        resolved = "not_applicable",
                        executed = "not_executed",
                        result = "single_frame",
                        fallback = false,
                        reason = "raw_only_single_frame_route"
                    )
                    captureTrace.decision(
                        section = com.bncam.core.tracing.CaptureTraceSection.FUSION,
                        key = "executedFusion",
                        requested = recipe.fusionMethod.requestedId,
                        supported = recipe.fusionMethod.supported.toString(),
                        resolved = "not_applicable",
                        executed = "not_executed",
                        result = "one_raw_master",
                        fallback = false,
                        reason = "raw_only_single_frame_route"
                    )
                    captureTrace.decision(
                        section = com.bncam.core.tracing.CaptureTraceSection.ISP_EXECUTION,
                        key = "executedDemosaic",
                        requested = recipe.demosaicMethod.requestedId,
                        supported = recipe.demosaicMethod.supported.toString(),
                        resolved = recipe.demosaicMethod.resolvedId,
                        executed = "not_executed",
                        result = "not_applicable_raw_only",
                        fallback = false,
                        reason = "raw_only_stops_before_demosaic_rgb_isp_and_jpeg"
                    )
                    captureTrace.decision(
                        section =
                            com.bncam.core.tracing.CaptureTraceSection.OUTPUT_AND_PUBLICATION,
                        key = "executedDngSource",
                        requested = recipe.dngSource.name,
                        supported = "true",
                        resolved = recipe.dngSource.name,
                        executed = com.bncam.core.capture.DngSource.ANCHOR_RAW.name,
                        result = "published_dng",
                        fallback = false,
                        reason = "published_dng_contains_selected_anchor_raw"
                    )
                    if (enableShotLogger) {
                        shotLogger.writeCaptureTrace(captureTrace.complete(rawOnlyOutputs))
                    }
                    work.markPublished(rawOnlyOutputs)
                    performanceTracker.persistJsonLine(context, status = "PUBLISHED")
                    Log.i(
                        "BnCamCaptureTiming",
                        "route=${plan.route.id} raw_export_time=${String.format(Locale.US, "%.3f", (android.os.SystemClock.elapsedRealtimeNanos() - rawExportStartedNs) / 1_000_000.0)}"
                    )
                    Log.i(
                        tag,
                        "DNG_SAVE_SUCCEEDED profile=$profileId requested=$preferredFrameSetting actual=$activeBufferFormatLabel " +
                                "generation=$selectedFrameGeneration timestamp=$selectedFrameTimestampNs " +
                                "metadataPresent=true output=$dngUri rawBytes=${rawInput.raw16ByteCount}"
                    )
                    } finally {
                        rawInput.close()
                        acquiredRawInputForCleanup = null
                    }
                }
                if (!saveAccepted) {
                    rawInput.close()
                    acquiredRawInputForCleanup = null
                    work.fail("save_queue_full")
                    runCatching { context.contentResolver.delete(dngUri, null, null) }
                    throw IllegalStateException("Save queue is full.")
                }
            }
            if (!queued) {
                rawInput.close()
                acquiredRawInputForCleanup = null
                runCatching { context.contentResolver.delete(dngUri, null, null) }
                performanceTracker.persistJsonLine(
                    context = context,
                    status = "FAILED",
                    failureReason = "processing_queue_submit_failed_raw_only"
                )
                reservation.fail("processing_queue_submit_failed")
                throw IllegalStateException("Processing queue rejected RAW-only output.")
            }
            rawWorkHandedOff = true
            acquiredRawInputForCleanup = null
            Log.i(
                "BnCamCaptureTiming",
                "route=${plan.route.id} raw_unpack_time=${String.format(Locale.US, "%.3f", rawUnpackMs)} " +
                    "master_build_time=0.000 demosaic_time=0.000 render_profile_time=0.000 " +
                    "denoise_time=0.000 sharpen_time=0.000 jpeg_encode_time=0.000 exif_time=0.000 " +
                    "raw_export_time=QUEUED " +
                    "total_until_preview_ready=${String.format(Locale.US, "%.3f", performanceTracker.elapsedMs())}"
            )
            return@withContext com.bncam.core.output.CaptureSubmissionResult.Submitted(
                attemptId = plan.route.id,
                workId = reservation.workId,
                temporaryPreviewPath = temporaryPreviewPath
            )
        }

        val timeStampForFile = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
        val baseFilename = "$photoPrefix$timeStampForFile"
        val jpegFilename = "$baseFilename.jpg"

        var publishedOutputUri: Uri? = null
        var reservedJpegUri: Uri? = if (isRawFrameSource) {
            preInsertImageToMediaStore(jpegFilename, saveLocation, "image/jpeg")
                ?: run {
                    rawWorkReservation?.fail("jpeg_reservation_failed_before_processing")
                    throw IllegalStateException("JPEG MediaStore reservation failed.")
                }
        } else {
            null
        }
        val processAndQueueOutput:
            suspend (CaptureStageListener, CaptureProcessingQueue.Reservation?) -> Boolean =
            { stageListener, processingWork ->
            withContext(ispDispatcher) {
            var finalProcessingFailure: Throwable? = null
            var finalJpegBytes: ByteArray? = null
            var ultraHdrGainmapArtifact: ImageUtils.UltraHdrGainmapArtifact? = null
            var singleRawFrame: Raw16RenderInput? = acquiredRawInput
            var dngBytesSize = 0
            var dngMergeStats = "not requested"
            var masterIspStats = "not recorded"
            var yuvNativeStats = "not recorded"
            var dngFailureReason = "none"
            var renderTimeMs = 0L
            var dngWriteTimeMs = 0L
            var nativeRawOwnerTransferredToSaveQueue = false
            try {
            val renderStartMs = System.currentTimeMillis()
            stageListener.nativeProcessingStart()
            try {
                if (activeZslFormat == ImageFormat.RAW10 || activeZslFormat == ImageFormat.RAW_SENSOR) {
                    Log.i(tag, "RAW single-frame processing start (SingleRaw16Frame; no Master RAW16 builder)")
                    val rawInput = singleRawFrame
                    val jpegResult = if (rawInput != null && rawJpegContractFailure == null) {
                        performanceTracker.incrementCounter("rawIspInvocationCount")
                        performanceTracker.incrementCounter("jpegEncodeInvocationCount")
                        ImageUtils.renderJpegFromRaw16InputWithUltraHdrSafe(
                        masterFrame = rawInput,
                        qualityConfig = renderQualityConfig,
                        rotationDegrees = finalJpegRotation,
                        portraitCaptureContext = portraitCaptureContext
                    )
                    } else null
                    ultraHdrGainmapArtifact = jpegResult?.ultraHdrGainmap

                    dngMergeStats = singleRawFrame?.dngMergeStats ?: ImageUtils.lastDngMergeStats()
                    masterIspStats = ImageUtils.lastMasterIspStats()
                    parseNativeStats(dngMergeStats).forEach { (key, value) ->
                        performanceTracker.setMetric("nativeRawInput.$key", value)
                    }
                    parseNativeStats(masterIspStats).forEach { (key, value) ->
                        performanceTracker.setMetric("nativeRawIsp.$key", value)
                    }

                    val renderedJpegBytes = jpegResult?.jpegBytes
                    if (renderedJpegBytes != null && renderedJpegBytes.isNotEmpty()) {
                        val colorAudit = RawColorPipelineAuditor.audit(
                            sensorMetadata = anchorFrame.sensorMetadataSnapshot,
                            cfaPattern = renderQualityConfig.finalCalibration?.base?.cfaPattern ?: 0,
                            effectiveWbGains = renderQualityConfig.finalCalibration?.effectiveWbGains ?: floatArrayOf(1.0f, 1.0f, 1.0f, 1.0f),
                            colorMatrix = renderQualityConfig.finalCalibration?.effectiveColorMatrix ?: floatArrayOf(1.0f, 0.0f, 0.0f, 0.0f, 1.0f, 0.0f, 0.0f, 0.0f, 1.0f),
                            lensShadingMap = captureMetadata?.get(CaptureResult.STATISTICS_LENS_SHADING_CORRECTION_MAP),
                            jniStatsString = masterIspStats
                        )

                        val calibration = renderQualityConfig.finalCalibration
                        val calibrationBinding = calibration?.base?.calibrationProfileBinding
                        val frameIdentity = anchorFrame.sensorMetadataSnapshot?.frameIdentityForRaw(anchorFrame.timestamp)
                        val white = calibration?.effectiveWhiteLevel ?: 0
                        val blackLevels = calibration?.effectiveBlackLevels
                        val blackWhiteRangeValid = white > 1 &&
                            blackLevels?.size == 4 &&
                            blackLevels.all { level ->
                                level.isFinite() && level >= 0.0f && level < white.toFloat()
                            }
                        val publicationIntegrity = com.bncam.core.capture.RawPublicationIntegrityGate.evaluate(
                            com.bncam.core.capture.RawPublicationIntegrityGate.Input(
                                provenanceSafe = frameIdentity?.safeForRawProcessing == true,
                                coreMetadataSafe = anchorFrame.sensorMetadataSnapshot?.coreRawMetadataValid == true,
                                calibrationBindingSafe = calibrationBinding?.safeForProfileBinding == true,
                                authorityProfileMatch = calibrationBinding?.provenance?.authorityMatches == true,
                                cfaMatched = colorAudit.cfaPatternMatched,
                                whiteBalanceValid = colorAudit.wbGainsConsistent,
                                colorMatrixValid = colorAudit.ccmDeterminantValid &&
                                    calibration?.effectiveColorMatrixApplied == true,
                                colorMatrixIdentityFallbackUsed =
                                    calibration?.effectiveColorMatrixIdentityFallbackUsed != false,
                                blackWhiteRangeValid = blackWhiteRangeValid
                            )
                        )
                        if (enableShotLogger) {
                            shotLogger.recordPipelineEvent(
                                "RAW Publication Integrity",
                                "Safe",
                                publicationIntegrity.safeForPublication.toString()
                            )
                            shotLogger.recordPipelineEvent(
                                "RAW Publication Integrity",
                                "Reason",
                                publicationIntegrity.reason
                            )
                            shotLogger.recordPipelineEvent(
                                "RAW Publication Integrity",
                                "Frame Identity Reason",
                                frameIdentity?.rejectionReason() ?: "FRAME_IDENTITY_UNAVAILABLE"
                            )
                            shotLogger.recordPipelineEvent(
                                "RAW Publication Integrity",
                                "Frame Timestamp Ns",
                                anchorFrame.timestamp.toString()
                            )
                            shotLogger.recordPipelineEvent(
                                "RAW Publication Integrity",
                                "Metadata Timestamp Ns",
                                frameIdentity?.captureIdentity?.sensorTimestampNs?.toString()
                                    ?: "UNAVAILABLE"
                            )
                            shotLogger.recordPipelineEvent(
                                "RAW Publication Integrity",
                                "Sensor Authority ID",
                                calibrationBinding?.provenance?.sensorAuthorityId ?: "UNAVAILABLE"
                            )
                            shotLogger.recordPipelineEvent(
                                "RAW Publication Integrity",
                                "Calibration Profile ID",
                                calibrationBinding?.calibrationProfileId ?: "UNAVAILABLE"
                            )
                            shotLogger.recordPipelineEvent(
                                "RAW Publication Integrity",
                                "DNG/RAW CaptureResult Source",
                                exactRawAuthorityInput?.sensorIdentity?.sourceId ?: "UNAVAILABLE"
                            )
                        }
                        if (!publicationIntegrity.safeForPublication) {
                            if (enableShotLogger) {
                                shotLogger.recordWarning(
                                    "RAW Publication Integrity",
                                    "RAW_PUBLICATION_INTEGRITY_BLOCKED:${publicationIntegrity.reason}",
                                    "ERROR"
                                )
                            }
                            throw IllegalStateException(
                                "RAW_PUBLICATION_INTEGRITY_BLOCKED:${publicationIntegrity.reason}"
                            )
                        }
                        finalJpegBytes = renderedJpegBytes
                        Log.i(
                            tag,
                            "RAW_PUBLICATION_INTEGRITY_PASS authority=${calibrationBinding?.provenance?.sensorAuthorityId} " +
                                "profile=${calibrationBinding?.calibrationProfileId} reason=${publicationIntegrity.reason}"
                        )
                        Log.i(tag, "Single RAW16 profile render succeeded. JPEG received (${renderedJpegBytes.size} bytes).")

                        val rejectedReasons = ZslCandidateAuditor.getLogs()
                            .associate { it.timestampNs to it.rejectReason }

                        val colorAuditSummary = colorAudit.diagnostics.joinToString("; ")
                        val dngAuditResult = if (plan.outputPolicy.producesRaw) "PASS" else "N/A"
                        val jpegChannelStats = "JPEG Size: ${renderedJpegBytes.size} bytes"

                        if (plan.debugPolicy.qualificationEnabled) HardwareQualificationRunner.runQualificationScenario(
                            cameraId = activeLens.id,
                            physicalCameraId = captureMetadata?.get(CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID) ?: activeLens.id,
                            lensId = activeLens.name,
                            profileId = profileId,
                            format = if (activeZslFormat == ImageFormat.RAW10) "RAW10" else "RAW_SENSOR",
                            bufferType = "ZSL",
                            captureMode = activeProfile.captureStrategy.name,
                            generationId = FrameGenerationId.get(),
                            ringBuffer = ringBuffer,
                            characteristics = chars,
                            latestResult = captureMetadata,
                            selectedFrame = deferredQualificationFrame,
                            rejectedReasons = rejectedReasons,
                            colorAuditSummary = colorAuditSummary,
                            dngAuditResult = dngAuditResult,
                            jpegChannelStats = jpegChannelStats
                        )

                        val extDir = if (plan.debugPolicy.qualificationEnabled) context.getExternalFilesDir(null) else null
                        if (extDir != null) {
                            HardwareQualificationRunner.exportReportsToFile(extDir)
                        }
                    } else {
                        dngFailureReason = "Master RAW Frame creation or JPEG render failed."
                        finalProcessingFailure = IllegalStateException(
                            rawJpegContractFailure
                                ?: "Native C++ ImageProcessor returned null/empty for JPEG from Master RAW Frame."
                        )
                    }

                    if (enableShotLogger && (activeZslFormat == ImageFormat.RAW_SENSOR || activeZslFormat == ImageFormat.RAW10)) {
                        shotLogger.recordPipelineEvent("Single RAW16 Frame", "Frame Count", "1")
                        shotLogger.recordPipelineEvent("Single RAW16 Frame", "Builder", "SingleRaw16FrameBuilder")
                        shotLogger.recordPipelineEvent("Single RAW16 Frame", "Master RAW16 Builder Used", "false")
                        shotLogger.recordPipelineEvent("Single RAW16 Frame", "Stats", dngMergeStats)
                        shotLogger.recordPipelineEvent("Single RAW16 ISP Render", "Stats", masterIspStats)
                        val calibrationProfileId = renderQualityConfig.finalCalibration?.base?.calibrationProfileId ?: "unknown"
                        shotLogger.recordPipelineEvent(
                            "Calibration Profile Repository",
                            "Binding",
                            com.bncam.core.isp.raw10.RawCameraColorProfileRepository.bindingDebugSummary(calibrationProfileId)
                        )
                        val masterIspStatsMap = parseNativeStats(masterIspStats)
                        if (masterIspStatsMap["colorMatrixApplied"] == "false" || masterIspStats.contains("colorMatrixApplied=false")) {
                            shotLogger.recordWarning("RAW ISP", "Color Matrix Applied=false during Master RAW JPEG render. This is a quality warning, not a clean success.", "WARN")
                        }
                        masterIspStatsMap.forEach { (key, value) ->
                            shotLogger.recordPipelineEvent("Master RAW16 ISP Render", prettyStatKey(key), value)
                            if (key in listOf("hasBlackLevel", "hasWhiteLevel", "hasColorMatrix", "hasWbGains", "hasNoiseProfile", "calibrationApplied", "noiseProfileApplied", "calibrationWarnings")) {
                                shotLogger.recordPipelineEvent("Native Calibration", key, value)
                            }
                        }
                    }

                } else {
                    val hwBufferArray = arrayOf(anchorBuffer)
                    Log.w(tag, "YUV Native Processing Start")
                    performanceTracker.incrementCounter("yuvNativeInvocationCount")
                    performanceTracker.incrementCounter("jpegEncodeInvocationCount")
                    val jpegResult = ImageUtils.processNativeYuvSafe(
                        buffers = hwBufferArray,
                        qualityConfig = renderQualityConfig,
                        rotationDegrees = finalJpegRotation,
                        lensId = activeLens.id,
                        captureSensitivityIso = captureMetadata?.get(CaptureResult.SENSOR_SENSITIVITY) ?: 0,
                        portraitCaptureContext = portraitCaptureContext
                    )
                    yuvNativeStats = ImageUtils.lastYuvStats()
                    parseNativeStats(yuvNativeStats).forEach { (key, value) ->
                        performanceTracker.setMetric("nativeYuv.$key", value)
                    }

                    if (jpegResult != null && jpegResult.isNotEmpty()) {
                        Log.i(tag, "Native C++ YUV processing (native rotation) succeeded. JPEG received (${jpegResult.size} bytes).")
                        finalJpegBytes = jpegResult

                        val rejectedReasons = ZslCandidateAuditor.getLogs()
                            .associate { it.timestampNs to it.rejectReason }
                        val jpegChannelStats = "JPEG Size: ${jpegResult.size} bytes"

                        if (plan.debugPolicy.qualificationEnabled) HardwareQualificationRunner.runQualificationScenario(
                            cameraId = activeLens.id,
                            physicalCameraId = captureMetadata?.get(CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID) ?: activeLens.id,
                            lensId = activeLens.name,
                            profileId = profileId,
                            format = "YUV",
                            bufferType = "ZSL",
                            captureMode = activeProfile.captureStrategy.name,
                            generationId = FrameGenerationId.get(),
                            ringBuffer = ringBuffer,
                            characteristics = chars,
                            latestResult = captureMetadata,
                            selectedFrame = anchorFrame,
                            rejectedReasons = rejectedReasons,
                            colorAuditSummary = "YUV - N/A",
                            dngAuditResult = "N/A",
                            jpegChannelStats = jpegChannelStats
                        )

                        val extDir = if (plan.debugPolicy.qualificationEnabled) context.getExternalFilesDir(null) else null
                        if (extDir != null) {
                            HardwareQualificationRunner.exportReportsToFile(extDir)
                        }
                    } else {
                        finalProcessingFailure = IllegalStateException("Native C++ YUV processing returned null/empty.")
                    }
                }

                if (plan.outputPolicy.producesRaw && !(activeZslFormat == ImageFormat.RAW10 || activeZslFormat == ImageFormat.RAW_SENSOR)) {
                    dngFailureReason = "Active frame source is $activeBufferFormatLabel; DNG is only supported for RAW10 and RAW_SENSOR."
                    if (enableShotLogger) shotLogger.recordWarning("DNG Export", dngFailureReason, "INFO")
                }

            } catch (t: Throwable) {
                finalProcessingFailure = t
                Log.e(tag, "Error occurred during final processing on background thread", t)
                if (enableShotLogger) {
                    shotLogger.recordWarning("Renderer Warnings", "Final processing failed: ${t.javaClass.simpleName}: ${t.message}", "ERROR")
                }
            } finally {
                // RAW handed ownership to immutable RAW16 during acquisition. YUV still owns the
                // selected HardwareBuffer until its native render has consumed it.
                renderTimeMs = System.currentTimeMillis() - renderStartMs
                performanceTracker.mark("jpeg_render")
                stageListener.nativeProcessingEnd(finalJpegBytes?.isNotEmpty() == true)
            }
            if (isRawFrameSource) {
                runCatching {
                    onRawProcessingFeedback(
                        SingleRawProcessingFeedback(
                            nativeStats = masterIspStats,
                            exposureTimeNs =
                                rawAuthorityCaptureResult?.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: 0L,
                            sensitivityIso =
                                rawAuthorityCaptureResult?.get(CaptureResult.SENSOR_SENSITIVITY) ?: 0,
                            succeeded = finalJpegBytes?.isNotEmpty() == true
                        )
                    )
                }.onFailure { callbackFailure ->
                    Log.w(
                        tag,
                        "RAW processing feedback callback failed: ${callbackFailure.message}",
                        callbackFailure
                    )
                }
            }

            val jpegBytesAvailable = finalJpegBytes?.isNotEmpty() == true
            val jpegPublicUri = if (jpegBytesAvailable) {
                reservedJpegUri
                    ?: preInsertImageToMediaStore(jpegFilename, saveLocation, "image/jpeg")
            } else {
                null
            }
            reservedJpegUri = jpegPublicUri
            if (jpegPublicUri == null) {
                if (jpegBytesAvailable) {
                    finalProcessingFailure = IllegalStateException("JPEG MediaStore reservation failed.")
                }
                val rawInput = singleRawFrame
                val metadata = rawAuthorityCaptureResult
                if (plan.outputPolicy.producesRaw &&
                    rawInput != null && rawInput.raw16ByteCount > 0 && metadata != null
                ) {
                    val dngUri = preInsertImageToMediaStore(
                        "$baseFilename.dng",
                        saveLocation,
                        "image/x-adobe-dng"
                    ) ?: throw IllegalStateException("DNG MediaStore reservation failed after JPEG output became unavailable.")
                    val jpegFailureEvent = if (jpegBytesAvailable) {
                        "JPEG_RESERVATION_FAILED_CONTINUING_DNG"
                    } else {
                        "JPEG_RENDER_FAILED_CONTINUING_DNG"
                    }
                    Log.e(
                        tag,
                        "$jpegFailureEvent profile=$profileId requested=$preferredFrameSetting " +
                                "actual=$activeBufferFormatLabel timestamp=$selectedFrameTimestampNs " +
                                "metadataPresent=true output=$dngUri failure=${finalProcessingFailure?.message ?: "empty JPEG"}"
                    )
                    processingWork?.markSaving()
                    val dngQueued = CaptureSaveQueue.enqueue(
                        context = context,
                        route = "${plan.route.id}_DNG_AFTER_JPEG_FAILURE",
                        captureStartedNs = shutterTimestampNs,
                        onFailure = { failure ->
                            if (enableShotLogger) {
                                shotLogger.writeCaptureTrace(
                                    captureTrace.completeFailure(
                                        "dng_fallback_publication",
                                        failure
                                    )
                                )
                                shotLogger.finalizeFailureWithPublicDiagnostics(
                                    attemptId = attemptId,
                                    stage = "ASYNC_DNG_FALLBACK_PUBLICATION",
                                    exception = failure
                                )
                            }
                            runCatching { context.contentResolver.delete(dngUri, null, null) }
                            processingWork?.fail(
                                "dng_after_jpeg_failure_save_failed:" +
                                    failure.javaClass.simpleName
                            )
                        }
                    ) {
                        try {
                            val rawMaterializeStartedNs = android.os.SystemClock.elapsedRealtimeNanos()
                            val raw16ForDng = rawInput.materializeRaw16ForDng()
                            performanceTracker.recordDuration(
                                "raw16_dng_materialization",
                                (android.os.SystemClock.elapsedRealtimeNanos() - rawMaterializeStartedNs) / 1_000_000.0
                            )
                            performanceTracker.incrementCounter("raw16ManagedHeapMaterializationCount")
                            performanceTracker.setMetric("raw16ManagedHeapMaterializationBytes", raw16ForDng.size)
                            writeVirtualDngToReservedUri(
                                uri = dngUri,
                                raw16Bytes = raw16ForDng,
                                width = rawInput.width,
                                height = rawInput.height,
                                metadata = metadata,
                                characteristics = rawAuthorityCharacteristics,
                                orientation = getExifOrientation(finalJpegRotation),
                                dngMergeStats = rawInput.dngMergeStats,
                                lensHardwareDescription = lensHardwareSettings.dngDescription(),
                                rawDomainContract = rawInput.rawFrameInfo,
                                calibration = rawInput.finalCalibration
                            )
                            performanceTracker.incrementCounter("dngMediaStorePublicationCount")
                            performanceTracker.incrementCounter("terminalPublicationCount")
                            performanceTracker.setMetric("dngRaw16ByteCount", rawInput.raw16ByteCount)
                            performanceTracker.setMetric(
                                "raw16ManagedHeapMaterializationCount",
                                rawInput.managedMaterializationCount
                            )
                            performanceTracker.setMetric(
                                "raw16ManagedHeapMaterializationBytes",
                                rawInput.managedMaterializationBytes
                            )
                            check(rawInput.managedMaterializationCount == 1) {
                                "Phase 1B invariant failed: DNG fallback materialized RAW16 " +
                                    "${rawInput.managedMaterializationCount} times."
                            }
                            rawInput.close()
                            performanceTracker.setMetric(
                                "raw16NativeOwnerReleasedBeforePublication",
                                rawInput.nativeRaw16Buffer.isClosed
                            )
                            performanceTracker.setMetric(
                                "raw16NativeOutstandingBuffersAtPublication",
                                ImageUtils.nativeRaw16OutstandingBufferCountSafe()
                            )
                            if (singleRawFrame === rawInput) singleRawFrame = null
                            val dngFallbackOutputs = com.bncam.core.output.PublicationPolicyResolver.resolveStrings(
                                outputPolicy = plan.outputPolicy,
                                jpegSucceeded = false,
                                jpegUri = null,
                                dngSucceeded = true,
                                dngUri = dngUri.toString(),
                                jpegFailureReason = finalProcessingFailure?.message ?: "JPEG render or reservation failed",
                                dngFailureReason = null
                            )
                            captureTrace.decision(
                                section =
                                    com.bncam.core.tracing.CaptureTraceSection.ISP_EXECUTION,
                                key = "executedDemosaic",
                                requested = recipe.demosaicMethod.requestedId,
                                supported = recipe.demosaicMethod.supported.toString(),
                                resolved = recipe.demosaicMethod.resolvedId,
                                executed = recipe.demosaicMethod.resolvedId,
                                result = "jpeg_failed_dng_preserved",
                                fallback = true,
                                reason =
                                    finalProcessingFailure?.message
                                        ?: "jpeg_reservation_or_render_failed"
                            )
                            captureTrace.decision(
                                section =
                                    com.bncam.core.tracing.CaptureTraceSection.OUTPUT_AND_PUBLICATION,
                                key = "executedDngSource",
                                requested = recipe.dngSource.name,
                                supported = "true",
                                resolved = recipe.dngSource.name,
                                executed = com.bncam.core.capture.DngSource.ANCHOR_RAW.name,
                                result = "published_dng",
                                fallback = false,
                                reason = "published_dng_contains_selected_anchor_raw"
                            )
                            if (enableShotLogger) {
                                shotLogger.writeCaptureTrace(
                                    captureTrace.complete(dngFallbackOutputs)
                                )
                            }
                            performanceTracker.mark("publication_complete")
                            processingWork?.markPublished(dngFallbackOutputs)
                            performanceTracker.persistJsonLine(context, status = "PUBLISHED")
                        } finally {
                            rawInput.close()
                            if (singleRawFrame === rawInput) singleRawFrame = null
                        }
                    }
                    if (dngQueued) {
                        nativeRawOwnerTransferredToSaveQueue = true
                        publishedOutputUri = null
                        return@withContext true
                    }
                    processingWork?.fail("dng_after_jpeg_failure_save_queue_full")
                    rawInput.close()
                    if (singleRawFrame === rawInput) singleRawFrame = null
                }
                singleRawFrame?.close()
                singleRawFrame = null
                Log.e(
                    tag,
                    "OUTPUT_UNAVAILABLE profile=$profileId requested=$preferredFrameSetting actual=$activeBufferFormatLabel " +
                            "timestamp=$selectedFrameTimestampNs metadataPresent=${captureMetadata != null} " +
                            "jpegRequested=${plan.outputPolicy.producesJpeg} dngRequested=${plan.outputPolicy.producesRaw} " +
                            "failure=${finalProcessingFailure?.message ?: "empty JPEG and no DNG payload"}"
                )
                return@withContext false
            }

            val saveBlock: suspend () -> Unit = {
            try {
            // Exif writing and saving to pre-inserted Uri
            val saveStartMs = System.currentTimeMillis()
            var bytesToSave = finalJpegBytes
            if (isFrontCamera && mirrorFront && bytesToSave != null && bytesToSave.isNotEmpty()) {
                try {
                    val bitmap = BitmapFactory.decodeByteArray(bytesToSave, 0, bytesToSave.size)
                    val matrix = Matrix().apply { postScale(-1f, 1f) }
                    val flippedBitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                    val outputStream = ByteArrayOutputStream()
                    flippedBitmap.compress(Bitmap.CompressFormat.JPEG, 98, outputStream)
                    bytesToSave = outputStream.toByteArray()
                    bitmap.recycle()
                    flippedBitmap.recycle()
                } catch (e: Exception) {
                    throw IllegalStateException("Front-camera mirror contract failed.", e)
                }
            }

            var saveSucceeded = false
            var mediaStorePendingCleared = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
            var gpsAdded = false
            var exifTimeMs = 0.0
            var mediaStoreWriteTimeMs = 0.0
            var jpegSaveFailure: Throwable? = null

            fun writeAndPublishJpeg(bytes: ByteArray): Boolean {
                val output = context.contentResolver.openOutputStream(jpegPublicUri) ?: return false
                output.use { it.write(bytes) }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val contentValues = ContentValues().apply {
                        put(MediaStore.MediaColumns.IS_PENDING, 0)
                    }
                    mediaStorePendingCleared = context.contentResolver.update(
                        jpegPublicUri,
                        contentValues,
                        null,
                        null
                    ) > 0
                }
                return mediaStorePendingCleared
            }

            if (bytesToSave != null && bytesToSave.isNotEmpty()) {
                try {
                    var displayShutter = ""
                    val expTimeNs = captureMetadata?.get(CaptureResult.SENSOR_EXPOSURE_TIME)
                    if (expTimeNs != null && expTimeNs > 0L) {
                        val expTimeSec = expTimeNs / 1_000_000_000.0
                        displayShutter = if (expTimeSec < 1.0) {
                            val denominator = kotlin.math.round(1.0 / expTimeSec).toInt()
                            "1/$denominator s"
                        } else {
                            String.format(Locale.US, "%.2f s", expTimeSec)
                        }
                    }

                    val isoVal = captureMetadata?.get(CaptureResult.SENSOR_SENSITIVITY)
                    val displayIso = if (isoVal != null && isoVal > 0) "ISO $isoVal" else ""

                    val aperture = captureMetadata?.get(CaptureResult.LENS_APERTURE)
                    val displayAperture = if (aperture != null && aperture > 0f) "f/$aperture" else ""

                    val displayMode = activeProfile.captureStrategy.name
                    val displayFrameCount = "1 Frame (ZSL)"

                    var effectiveFovStr = ""
                    val physicalFocal = captureMetadata?.get(CaptureResult.LENS_FOCAL_LENGTH)

                    if (physicalFocal != null && physicalFocal > 0f) {
                        var baseFocal35mm = physicalFocal
                        val sensorSize = chars.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
                        if (sensorSize != null && sensorSize.width > 0f && sensorSize.height > 0f) {
                            val sensorDiag = kotlin.math.sqrt((sensorSize.width * sensorSize.width + sensorSize.height * sensorSize.height).toDouble())
                            val fullFrameDiag = 43.266
                            val cropFactor = (fullFrameDiag / sensorDiag).toFloat()
                            baseFocal35mm = physicalFocal * cropFactor
                        }

                        var zoomRatio = 1.0f
                        val activeArray = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
                        val cropRegion = captureMetadata.get(CaptureResult.SCALER_CROP_REGION)

                        if (activeArray != null && cropRegion != null && cropRegion.width() > 0) {
                            zoomRatio = activeArray.width().toFloat() / cropRegion.width().toFloat()
                        }

                        val finalZoomedFov = (baseFocal35mm * zoomRatio).toInt()
                        effectiveFovStr = "$finalZoomedFov mm"
                    }

                    val displaySensor = activeLens.name

                    val extendedWmConfig = WatermarkConfig(
                        enabled = wmEnabled,
                        style = wmStyle,
                        signature = wmSignature,
                        addAuthorTopRight = wmAddAuthorTopRight,
                        deviceModel = Build.MODEL,
                        sensorName = displaySensor,
                        fov = effectiveFovStr,
                        aperture = displayAperture,
                        shutterSpeed = displayShutter,
                        iso = displayIso,
                        captureMode = displayMode,
                        frameCountInfo = displayFrameCount
                    )

                    val watermarkedBytes = WatermarkEngine.applyWatermark(context, bytesToSave, extendedWmConfig)

                    val exifStartedNs = android.os.SystemClock.elapsedRealtimeNanos()
                    val tempFile = java.io.File(context.cacheDir, "exif_temp.jpg")
                    tempFile.writeBytes(watermarkedBytes)
                    val exif = androidx.exifinterface.media.ExifInterface(tempFile.absolutePath)

                    exif.setAttribute(androidx.exifinterface.media.ExifInterface.TAG_MAKE, Build.MANUFACTURER)
                    exif.setAttribute(androidx.exifinterface.media.ExifInterface.TAG_MODEL, Build.MODEL)
                    exif.setAttribute(androidx.exifinterface.media.ExifInterface.TAG_SOFTWARE, "BnCam Pro")

                    val exifDate = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).format(Date(startTimeMs))
                    exif.setAttribute(androidx.exifinterface.media.ExifInterface.TAG_DATETIME, exifDate)
                    exif.setAttribute(androidx.exifinterface.media.ExifInterface.TAG_DATETIME_ORIGINAL, exifDate)
                    exif.setAttribute(androidx.exifinterface.media.ExifInterface.TAG_DATETIME_DIGITIZED, exifDate)

                    var customInfo = "App Info: BnCam"

                    if (exifExtraData) {
                        customInfo += " | CameraID: ${activeLens.id} | Frame Count: 1 | Mode: $displayMode"
                    }

                    if (exifSaveSignature && wmSignature.isNotBlank()) {
                        customInfo += " | Author: $wmSignature"
                        exif.setAttribute(androidx.exifinterface.media.ExifInterface.TAG_ARTIST, wmSignature)
                        exif.setAttribute(androidx.exifinterface.media.ExifInterface.TAG_COPYRIGHT, wmSignature)
                    }

                    exif.setAttribute(androidx.exifinterface.media.ExifInterface.TAG_IMAGE_DESCRIPTION, customInfo)
                    exif.setAttribute(androidx.exifinterface.media.ExifInterface.TAG_USER_COMMENT, customInfo)

                    captureMetadata?.let { meta ->
                        fun rationalFromDouble(value: Double, denominator: Int = 1000): String {
                            val numerator = (value * denominator).toInt().coerceAtLeast(0)
                            return "$numerator/$denominator"
                        }

                        fun apexShutterSpeedValue(exposureTimeSeconds: Double): String {
                            val apex = -kotlin.math.ln(exposureTimeSeconds) / kotlin.math.ln(2.0)
                            return rationalFromDouble(apex, 1000)
                        }

                        val metaIsoVal = meta.get(CaptureResult.SENSOR_SENSITIVITY)
                        if (metaIsoVal != null && metaIsoVal > 0) {
                            exif.setAttribute(androidx.exifinterface.media.ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, metaIsoVal.toString())
                            @Suppress("DEPRECATION")
                            exif.setAttribute(androidx.exifinterface.media.ExifInterface.TAG_ISO_SPEED_RATINGS, metaIsoVal.toString())
                        }

                        val metaExpTimeNs = meta.get(CaptureResult.SENSOR_EXPOSURE_TIME)
                        if (metaExpTimeNs != null && metaExpTimeNs > 0L) {
                            val expTimeSec = metaExpTimeNs / 1_000_000_000.0
                            val exposureString = if (expTimeSec < 1.0) {
                                val denominator = kotlin.math.round(1.0 / expTimeSec).toInt()
                                "1/$denominator"
                            } else {
                                String.format(Locale.US, "%.2f", expTimeSec)
                            }
                            exif.setAttribute(androidx.exifinterface.media.ExifInterface.TAG_EXPOSURE_TIME, exposureString)
                            exif.setAttribute(androidx.exifinterface.media.ExifInterface.TAG_SHUTTER_SPEED_VALUE, apexShutterSpeedValue(expTimeSec))
                        }

                        val metaFocalLength = meta.get(CaptureResult.LENS_FOCAL_LENGTH)
                        if (metaFocalLength != null && metaFocalLength > 0f) {
                            exif.setAttribute(androidx.exifinterface.media.ExifInterface.TAG_FOCAL_LENGTH, rationalFromDouble(metaFocalLength.toDouble(), 1000))
                            try {
                                val sensorSize = chars.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
                                if (sensorSize != null && sensorSize.width > 0f && sensorSize.height > 0f) {
                                    val sensorDiag = kotlin.math.sqrt((sensorSize.width * sensorSize.width + sensorSize.height * sensorSize.height).toDouble())
                                    val fullFrameDiag = 43.266
                                    val cropFactor = fullFrameDiag / sensorDiag
                                    val focal35mm = (metaFocalLength * cropFactor).toInt().coerceAtLeast(1)
                                    exif.setAttribute(androidx.exifinterface.media.ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM, focal35mm.toString())
                                }
                            } catch (e: Exception) {
                                Log.w(tag, "Could not calculate 35mm equivalent", e)
                            }
                        }

                        val metaAperture = meta.get(CaptureResult.LENS_APERTURE)
                        if (metaAperture != null && metaAperture > 0f) {
                            exif.setAttribute(androidx.exifinterface.media.ExifInterface.TAG_F_NUMBER, metaAperture.toString())
                            exif.setAttribute(androidx.exifinterface.media.ExifInterface.TAG_APERTURE_VALUE, rationalFromDouble(metaAperture.toDouble(), 100))
                        }

                        val flashState = meta.get(CaptureResult.FLASH_STATE)
                        if (flashState != null) {
                            val flashFired = if (flashState == CaptureResult.FLASH_STATE_FIRED) "1" else "0"
                            exif.setAttribute(androidx.exifinterface.media.ExifInterface.TAG_FLASH, flashFired)
                        }
                    }

                    if (saveLocationData) {
                        LocationUtils.getLastKnownLocation(context)?.let { loc ->
                            exif.setGpsInfo(loc)
                            gpsAdded = true
                        }
                    }

                    exif.saveAttributes()
                    val finalBytesWithExif = tempFile.readBytes()
                    tempFile.delete()
                    exifTimeMs = (android.os.SystemClock.elapsedRealtimeNanos() - exifStartedNs) / 1_000_000.0
                    performanceTracker.recordDuration("jpeg_exif_and_watermark", exifTimeMs)

                    val finalPublicationBytes = if (renderQualityConfig.ultraHdrGainmapEnabled && ultraHdrGainmapArtifact != null) {
                        val packageStartedNs = android.os.SystemClock.elapsedRealtimeNanos()
                        val packaged = ImageUtils.packageUltraHdrJpegSafe(finalBytesWithExif, ultraHdrGainmapArtifact!!)
                        performanceTracker.recordDuration(
                            "ultra_hdr_container_package",
                            (android.os.SystemClock.elapsedRealtimeNanos() - packageStartedNs) / 1_000_000.0
                        )
                        if (packaged != null && packaged.isNotEmpty()) {
                            performanceTracker.setMetric("ultraHdrPackaged", true)
                            performanceTracker.setMetric("ultraHdrGainmapMaxBoost", ultraHdrGainmapArtifact!!.maxContentBoost)
                            Log.i(tag, "Ultra HDR packaged from GPU gainmap: ${ultraHdrGainmapArtifact!!.width}x${ultraHdrGainmapArtifact!!.height} maxBoost=${ultraHdrGainmapArtifact!!.maxContentBoost}")
                            packaged
                        } else {
                            performanceTracker.setMetric("ultraHdrPackaged", false)
                            Log.w(tag, "Ultra HDR requested but final container packaging failed; publishing original SDR JPEG")
                            finalBytesWithExif
                        }
                    } else {
                        if (renderQualityConfig.ultraHdrGainmapEnabled && isRawFrameSource) {
                            performanceTracker.setMetric("ultraHdrPackaged", false)
                            Log.i(tag, "Ultra HDR requested but Vulkan reported no meaningful HDR gainmap headroom; publishing SDR JPEG")
                        }
                        finalBytesWithExif
                    }

                    val mediaStoreWriteStartedNs = android.os.SystemClock.elapsedRealtimeNanos()
                    if (!writeAndPublishJpeg(finalPublicationBytes)) {
                        throw IllegalStateException("JPEG bytes were written but MediaStore pending state was not cleared")
                    }
                    mediaStoreWriteTimeMs = (android.os.SystemClock.elapsedRealtimeNanos() - mediaStoreWriteStartedNs) / 1_000_000.0
                    performanceTracker.recordDuration("jpeg_mediastore_write", mediaStoreWriteTimeMs)
                    saveSucceeded = true

                } catch (e: Exception) {
                    try { context.contentResolver.delete(jpegPublicUri, null, null) } catch (cleanupError: Exception) {
                        Log.w(tag, "Failed to remove incomplete JPEG uri=$jpegPublicUri", cleanupError)
                    }
                    jpegSaveFailure = e
                    Log.e(
                        tag,
                        "JPEG_SAVE_FAILED_CONTINUING_DNG profile=$profileId requested=$preferredFrameSetting " +
                                "actual=$activeBufferFormatLabel timestamp=$selectedFrameTimestampNs " +
                                "metadataPresent=${captureMetadata != null} output=$jpegPublicUri",
                        e
                    )
                }
            } else {
                Log.e(tag, "No JPEG bytes to save.")
                try { context.contentResolver.delete(jpegPublicUri, null, null) } catch (cleanupError: Exception) {
                    Log.w(tag, "Failed to remove empty JPEG uri=$jpegPublicUri", cleanupError)
                }
            }
            val saveTimeMs = System.currentTimeMillis() - saveStartMs
            performanceTracker.mark("jpeg_save")

            val raw16ForDngSave = if (plan.outputPolicy.producesRaw) {
                singleRawFrame?.let { rawInput ->
                    val rawMaterializeStartedNs = android.os.SystemClock.elapsedRealtimeNanos()
                    rawInput.materializeRaw16ForDng().also { bytes ->
                        performanceTracker.recordDuration(
                            "raw16_dng_materialization",
                            (android.os.SystemClock.elapsedRealtimeNanos() - rawMaterializeStartedNs) / 1_000_000.0
                        )
                        performanceTracker.incrementCounter("raw16ManagedHeapMaterializationCount")
                        performanceTracker.setMetric("raw16ManagedHeapMaterializationBytes", bytes.size)
                    }
                }
            } else null
            var dngPublicUri: Uri? = null
            if (plan.outputPolicy.producesRaw && raw16ForDngSave != null && raw16ForDngSave.isNotEmpty() && rawAuthorityCaptureResult != null) {
                val dngWriteStartedNs = android.os.SystemClock.elapsedRealtimeNanos()
                val dngSaveResult = saveVirtualDngToMediaStore(
                    raw16Bytes = raw16ForDngSave,
                    filename = "$baseFilename.dng",
                    saveLocation = saveLocation,
                    width = singleRawFrame?.width ?: frameWidth,
                    height = singleRawFrame?.height ?: frameHeight,
                    metadata = rawAuthorityCaptureResult,
                    characteristics = rawAuthorityCharacteristics,
                    orientation = getExifOrientation(finalJpegRotation),
                    dngMergeStats = dngMergeStats,
                    lensHardwareDescription = lensHardwareSettings.dngDescription(),
                    rawDomainContract = singleRawFrame?.rawFrameInfo,
                    calibration = singleRawFrame?.finalCalibration
                )
                dngPublicUri = dngSaveResult.first
                dngBytesSize = dngSaveResult.second.toInt().coerceAtLeast(raw16ForDngSave.size)
                dngWriteTimeMs = (android.os.SystemClock.elapsedRealtimeNanos() - dngWriteStartedNs) / 1_000_000L
                if (dngPublicUri == null) {
                    dngFailureReason = "DngCreator or MediaStore publication failed"
                    Log.e(
                        tag,
                        "DNG_SAVE_FAILED profile=$profileId requested=$preferredFrameSetting actual=$activeBufferFormatLabel " +
                                "generation=$selectedFrameGeneration bufferSize=$framesInBufferCount " +
                                "timestamp=$selectedFrameTimestampNs metadataPresent=true output=$saveLocation"
                    )
                }

                if (enableShotLogger) {
                    shotLogger.recordPipelineEvent("DNG Export", "Route", "SingleRaw16Frame -> DNG export (same immutable RAW16 input as JPEG)")
                    shotLogger.recordPipelineEvent("DNG Export", "Native Stats", dngMergeStats)
                    shotLogger.recordPipelineEvent("DNG Export", "RAW16 Payload Bytes", raw16ForDngSave.size.toString())
                    shotLogger.recordPipelineEvent("DNG Audit", "Report", DngWriter.lastAuditReport())
                    if (dngPublicUri == null) {
                        shotLogger.recordWarning("DNG Export", "DNG RAW16 payload prepared but MediaStore save failed.", "ERROR")
                    }
                }
            } else if (plan.outputPolicy.producesRaw) {
                dngFailureReason = "Native buffer returned null or empty"
                if (enableShotLogger) shotLogger.recordWarning("DNG Export", "DNG export failed: $dngFailureReason", "WARN")
            }
            performanceTracker.mark("dng_write")
            Log.i(
                "BnCamCaptureTiming",
                "route=${plan.route.id} exif_time=${String.format(Locale.US, "%.3f", exifTimeMs)} " +
                    "mediastore_write_time=${String.format(Locale.US, "%.3f", mediaStoreWriteTimeMs)} " +
                    "raw_export_time=$dngWriteTimeMs total_until_file_saved=${String.format(Locale.US, "%.3f", performanceTracker.elapsedMs())}"
            )

            val totalProcessingTimeMs = System.currentTimeMillis() - startTimeMs
            performanceTracker.mark("complete")
            val jpegPublished = saveSucceeded && mediaStorePendingCleared
            val dngPublished = dngPublicUri != null
            val publicationSnapshot = com.bncam.core.output.PublicationPolicyResolver.resolveStrings(
                outputPolicy = plan.outputPolicy,
                jpegSucceeded = jpegPublished,
                jpegUri = if (jpegPublished) jpegPublicUri?.toString() else null,
                dngSucceeded = dngPublished,
                dngUri = dngPublicUri?.toString(),
                jpegFailureReason = jpegSaveFailure?.message,
                dngFailureReason = dngFailureReason
            )

            if (enableShotLogger) {
                val framesRejected = max(0, initialAvailableCount - 1)

                data class MeteringValidation(
                    val aeRequestedRegionCount: Int,
                    val aeActualRegionCount: Int,
                    val aeRegionTruncatedByDriver: Boolean,
                    val aeCoreRegionMatched: Boolean,
                    val aeContextRegionDropped: Boolean,
                    val meteringAppliedExact: Boolean,
                    val meteringAppliedPartial: Boolean
                )

                val selectedRequestedAeRegions = selectedRequestSnapshot?.state?.aeRegions
                    ?.map { region ->
                        android.hardware.camera2.params.MeteringRectangle(
                            android.graphics.Rect(
                                region.rect.left,
                                region.rect.top,
                                region.rect.right,
                                region.rect.bottom
                            ),
                            region.weight
                        )
                    }
                    ?.toTypedArray()
                val selectedRequestedAfRegions = selectedRequestSnapshot?.state?.afRegions
                    ?.map { region ->
                        android.hardware.camera2.params.MeteringRectangle(
                            android.graphics.Rect(
                                region.rect.left,
                                region.rect.top,
                                region.rect.right,
                                region.rect.bottom
                            ),
                            region.weight
                        )
                    }
                    ?.toTypedArray()
                val actualAeRegions = captureMetadata?.get(CaptureResult.CONTROL_AE_REGIONS)
                val actualAfRegions = captureMetadata?.get(CaptureResult.CONTROL_AF_REGIONS)
                val actualEvCompSteps =
                    captureMetadata?.get(CaptureResult.CONTROL_AE_EXPOSURE_COMPENSATION)
                val selectedRequestedEvCompSteps =
                    selectedRequestSnapshot?.state?.aeExposureCompensation
                val actualEvComp = actualEvCompSteps?.toFloat() ?: 0f
                val requestedEvComp = selectedRequestedEvCompSteps?.toFloat() ?: 0f
                val intent = captureMetadata?.get(CaptureResult.CONTROL_CAPTURE_INTENT)

                val hwLevel = chars.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)
                val isLegacy = hwLevel == CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY
                val coordinateSpace = if (isLegacy) "SCALER_CROP_REGION" else "SENSOR_ACTIVE_ARRAY"

                fun validateMeteringRegions(
                    requested: Array<android.hardware.camera2.params.MeteringRectangle>?,
                    actual: Array<android.hardware.camera2.params.MeteringRectangle>?
                ): MeteringValidation {
                    val listA = requested?.filter { it.meteringWeight > 0 } ?: emptyList()
                    val listB = actual?.filter { it.meteringWeight > 0 } ?: emptyList()

                    if (listA.isEmpty()) {
                        val matchesEmpty = listB.isEmpty()
                        return MeteringValidation(
                            aeRequestedRegionCount = 0,
                            aeActualRegionCount = listB.size,
                            aeRegionTruncatedByDriver = false,
                            aeCoreRegionMatched = matchesEmpty,
                            aeContextRegionDropped = false,
                            meteringAppliedExact = matchesEmpty,
                            meteringAppliedPartial = false
                        )
                    }

                    val coreReq = listA.maxByOrNull { it.meteringWeight } ?: listA[0]

                    val activeArray = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
                    val cropRegionVal = captureMetadata?.get(CaptureResult.SCALER_CROP_REGION)

                    val activeArrayWidth = activeArray?.width() ?: 4000
                    val activeArrayHeight = activeArray?.height() ?: 3000
                    val cropLeft = cropRegionVal?.left ?: 0
                    val cropTop = cropRegionVal?.top ?: 0

                    val refWidth = if (isLegacy && cropRegionVal != null) cropRegionVal.width() else activeArrayWidth
                    val refHeight = if (isLegacy && cropRegionVal != null) cropRegionVal.height() else activeArrayHeight

                    val adaptiveToleranceX = kotlin.math.max(100, (refWidth * 0.01f).toInt())
                    val adaptiveToleranceY = kotlin.math.max(100, (refHeight * 0.01f).toInt())

                    val weightTolerance = 50

                    fun toActiveArraySpace(rect: android.graphics.Rect, isRequested: Boolean): android.graphics.Rect {
                        if (isLegacy) {
                            return android.graphics.Rect(rect.left + cropLeft, rect.top + cropTop, rect.right + cropLeft, rect.bottom + cropTop)
                        }
                        if (!isRequested && cropLeft > 100 && cropTop > 100) {
                            val cropWidth = cropRegionVal?.width() ?: activeArrayWidth
                            val cropHeight = cropRegionVal?.height() ?: activeArrayHeight
                            if (rect.right <= cropWidth && rect.bottom <= cropHeight) {
                                return android.graphics.Rect(rect.left + cropLeft, rect.top + cropTop, rect.right + cropLeft, rect.bottom + cropTop)
                            }
                        }
                        return rect
                    }

                    fun matchRegion(req: android.hardware.camera2.params.MeteringRectangle, act: android.hardware.camera2.params.MeteringRectangle): Boolean {
                        val reqRect = toActiveArraySpace(req.rect, isRequested = true)
                        val actRect = toActiveArraySpace(act.rect, isRequested = false)
                        val centerMatch = kotlin.math.abs(reqRect.centerX() - actRect.centerX()) <= adaptiveToleranceX &&
                                          kotlin.math.abs(reqRect.centerY() - actRect.centerY()) <= adaptiveToleranceY
                        val sizeMatch = kotlin.math.abs(reqRect.width() - actRect.width()) <= adaptiveToleranceX &&
                                        kotlin.math.abs(reqRect.height() - actRect.height()) <= adaptiveToleranceY
                        val overlap = android.graphics.Rect.intersects(reqRect, actRect)
                        val weightMatch = kotlin.math.abs(req.meteringWeight - act.meteringWeight) <= weightTolerance
                        return centerMatch && sizeMatch && overlap && weightMatch
                    }

                    val coreMatched = listB.any { matchRegion(coreReq, it) }

                    val matchedIndices = mutableSetOf<Int>()
                    var allMatched = true
                    for (req in listA) {
                        var found = false
                        for (idx in listB.indices) {
                            if (idx !in matchedIndices && matchRegion(req, listB[idx])) {
                                matchedIndices.add(idx)
                                found = true
                                break
                            }
                        }
                        if (!found) {
                            allMatched = false
                        }
                    }

                    val exactMatch = allMatched && listA.size == listB.size

                    val contextDropped = listA.size > 1 && !exactMatch && coreMatched
                    val truncatedByDriver = listB.size < listA.size && coreMatched

                    return MeteringValidation(
                        aeRequestedRegionCount = listA.size,
                        aeActualRegionCount = listB.size,
                        aeRegionTruncatedByDriver = truncatedByDriver,
                        aeCoreRegionMatched = coreMatched,
                        aeContextRegionDropped = contextDropped,
                        meteringAppliedExact = exactMatch,
                        meteringAppliedPartial = !exactMatch && coreMatched
                    )
                }

                val regionValidation =
                    validateMeteringRegions(selectedRequestedAeRegions, actualAeRegions)
                val afRegionValidation =
                    validateMeteringRegions(selectedRequestedAfRegions, actualAfRegions)

                val exactProvenance = selectedFrameProvenanceProof.exact
                val meteringRequested =
                    selectedRequestedAeRegions?.any { it.meteringWeight > 0 } == true
                val afRegionsRequested =
                    selectedRequestedAfRegions?.any { it.meteringWeight > 0 } == true
                val requiredAfEvidenceExact =
                    !afRegionsRequested ||
                            (actualAfRegions != null &&
                                    afRegionValidation.meteringAppliedExact)
                val evCompEvidenceAvailable =
                    selectedRequestedEvCompSteps != null && actualEvCompSteps != null
                val evCompMatched = evCompEvidenceAvailable &&
                        selectedRequestedEvCompSteps == actualEvCompSteps
                val meteringAppliedExact = MeteringExactTruth.canReportAppliedExact(
                    exactFrameRequestResultProvenance = exactProvenance,
                    meteringRequested = meteringRequested,
                    requestRegionsExactlyMatchedByResult =
                        regionValidation.meteringAppliedExact &&
                                requiredAfEvidenceExact,
                    requestedEvCompensation = selectedRequestedEvCompSteps,
                    resultEvCompensation = actualEvCompSteps
                )
                val meteringAppliedPartial = meteringRequested &&
                        exactProvenance &&
                        !meteringAppliedExact &&
                        regionValidation.meteringAppliedPartial &&
                        evCompMatched
                val selectedFrameSourceRequestType = when (intent) {
                    CaptureResult.CONTROL_CAPTURE_INTENT_STILL_CAPTURE -> "still"
                    null -> "unknown"
                    else -> "repeating"
                }
                val meteringAppliedToSelectedFrame = meteringRequested &&
                        (meteringAppliedExact || meteringAppliedPartial)
                val meteringAppliedStatus = when {
                    !exactProvenance ->
                        "unproven_${selectedFrameProvenanceProof.status.lowercase(Locale.US)}"
                    !meteringRequested -> "not_requested_exact_provenance"
                    actualAeRegions == null ||
                            (afRegionsRequested && actualAfRegions == null) ||
                            !evCompEvidenceAvailable ->
                        "unproven_result_metering_evidence_missing"
                    meteringAppliedExact -> "exact"
                    meteringAppliedPartial &&
                            regionValidation.aeRegionTruncatedByDriver &&
                            regionValidation.aeCoreRegionMatched ->
                        "partial_driver_truncated_core_matched"
                    meteringAppliedPartial -> "partial_core_matched"
                    !evCompMatched -> "ev_comp_mismatch"
                    else -> "request_result_region_mismatch"
                }
                val meteringAppliedToRepeatingRequest = selectedFrameSourceRequestType == "repeating" &&
                        meteringAppliedToSelectedFrame
                val meteringAppliedToStillRequest = selectedFrameSourceRequestType == "still" &&
                        meteringAppliedToSelectedFrame
                val meteringAppliedToRepeatingRequestStatus =
                    if (selectedFrameSourceRequestType == "repeating") meteringAppliedStatus
                    else "not_applicable_selected_frame_source_$selectedFrameSourceRequestType"
                val meteringAppliedToStillRequestStatus =
                    if (selectedFrameSourceRequestType == "still") meteringAppliedStatus
                    else "not_applicable_near_zsl_repeating_frame"

                // Recording Metering Validation details
                shotLogger.recordPipelineEvent("Metering Validation", "currentPipelineGeneration", expectedCollectionGeneration.toString())
                shotLogger.recordPipelineEvent("Metering Validation", "currentSubmittedControlRequestEpochAtShutter", currentSubmittedControlRequestEpochAtShutter.toString())
                shotLogger.recordPipelineEvent("Metering Validation", "selectedFramePipelineGeneration", selectedFrameGeneration.toString())
                shotLogger.recordPipelineEvent("Metering Validation", "selectedFrameControlRequestEpoch", selectedControlRequestEpochSnapshot.toString())
                shotLogger.recordPipelineEvent("Metering Validation", "selectedFrameProvenanceExact", exactProvenance.toString())
                shotLogger.recordPipelineEvent("Metering Validation", "selectedFrameProvenanceStatus", selectedFrameProvenanceProof.status)
                shotLogger.recordPipelineEvent("Metering Validation", "selectedRequestSubmissionType", selectedRequestSnapshot?.submissionType?.name ?: "unknown")
                shotLogger.recordPipelineEvent("Metering Validation", "selectedRequestSubmissionReason", selectedRequestSnapshot?.submissionReason ?: "unknown")
                shotLogger.recordPipelineEvent("Metering Validation", "selectedRequestSnapshot", selectedRequestSnapshot?.diagnosticSummary() ?: "unproven")
                shotLogger.recordPipelineEvent("Metering Validation", "meteringRequested", meteringRequested.toString())
                shotLogger.recordPipelineEvent("Metering Validation", "aeRequestedRegionCount", regionValidation.aeRequestedRegionCount.toString())
                shotLogger.recordPipelineEvent("Metering Validation", "aeActualRegionCount", regionValidation.aeActualRegionCount.toString())
                shotLogger.recordPipelineEvent("Metering Validation", "aeRegionTruncatedByDriver", regionValidation.aeRegionTruncatedByDriver.toString())
                shotLogger.recordPipelineEvent("Metering Validation", "aeCoreRegionMatched", regionValidation.aeCoreRegionMatched.toString())
                shotLogger.recordPipelineEvent("Metering Validation", "aeContextRegionDropped", regionValidation.aeContextRegionDropped.toString())
                shotLogger.recordPipelineEvent("Metering Validation", "afRequestedRegionCount", afRegionValidation.aeRequestedRegionCount.toString())
                shotLogger.recordPipelineEvent("Metering Validation", "afActualRegionCount", afRegionValidation.aeActualRegionCount.toString())
                shotLogger.recordPipelineEvent("Metering Validation", "afRegionsRequiredForExact", afRegionsRequested.toString())
                shotLogger.recordPipelineEvent("Metering Validation", "afRegionsMatchedForExact", requiredAfEvidenceExact.toString())
                shotLogger.recordPipelineEvent("Metering Validation", "meteringAppliedExact", meteringAppliedExact.toString())
                shotLogger.recordPipelineEvent("Metering Validation", "meteringAppliedPartial", meteringAppliedPartial.toString())
                shotLogger.recordPipelineEvent("Metering Validation", "meteringAppliedToSelectedFrame", meteringAppliedToSelectedFrame.toString())
                shotLogger.recordPipelineEvent("Metering Validation", "meteringAppliedStatus", meteringAppliedStatus)
                shotLogger.recordPipelineEvent("Metering Validation", "selectedFrameSourceRequestType", selectedFrameSourceRequestType)
                shotLogger.recordPipelineEvent("Metering Validation", "meteringAppliedToRepeatingRequestStatus", meteringAppliedToRepeatingRequestStatus)
                shotLogger.recordPipelineEvent("Metering Validation", "meteringAppliedToStillRequestStatus", meteringAppliedToStillRequestStatus)

                val activeArrayVal = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
                val cropRegionVal = captureMetadata?.get(CaptureResult.SCALER_CROP_REGION)
                shotLogger.recordPipelineEvent("Metering Validation", "aeValidationCoordinateSpace", coordinateSpace)
                shotLogger.recordPipelineEvent("Metering Validation", "aeActiveArraySize", activeArrayVal?.let { "[${it.left},${it.top},${it.right},${it.bottom}]" } ?: "unknown")
                shotLogger.recordPipelineEvent("Metering Validation", "aeMeteringCropRegion", cropRegionVal?.let { "[${it.left},${it.top},${it.right},${it.bottom}]" } ?: "unknown")

                // Recording Buffer Proof details
                shotLogger.recordPipelineEvent("ImageReader / Buffer", "imageReaderMaxImages", maxImagesVal.toString())
                shotLogger.recordPipelineEvent("ImageReader / Buffer", "ringResidentImageSlots", imageReaderPressureAtShutter.ringResidentImageSlots.toString())
                shotLogger.recordPipelineEvent("ImageReader / Buffer", "producerHeadroom", imageReaderPressureAtShutter.producerHeadroom.toString())
                shotLogger.recordPipelineEvent("ImageReader / Buffer", "leasedFrames", imageReaderPressureAtShutter.leasedFrames.toString())
                shotLogger.recordPipelineEvent("ImageReader / Buffer", "imageReaderMaxImagesExhaustionCount", imageReaderPressureAtShutter.imageReaderMaxImagesExhaustionCount.toString())
                shotLogger.recordPipelineEvent("ImageReader / Buffer", "drainCallbackCount", imageReaderPressureAtShutter.drainCallbackCount.toString())
                shotLogger.recordPipelineEvent("ImageReader / Buffer", "drainBatchHighWatermark", imageReaderPressureAtShutter.drainBatchHighWatermark.toString())
                shotLogger.recordPipelineEvent("ImageReader / Buffer", "drainServiceMedianMs", imageReaderPressureAtShutter.drainServiceMedianMs?.let { String.format(Locale.US, "%.3f", it) } ?: "unknown")
                shotLogger.recordPipelineEvent("ImageReader / Buffer", "drainServiceMaxMs", imageReaderPressureAtShutter.drainServiceMaxMs?.let { String.format(Locale.US, "%.3f", it) } ?: "unknown")
                shotLogger.recordPipelineEvent("ImageReader / Buffer", "imageArrivalCadenceMedianMs", imageReaderPressureAtShutter.imageArrivalCadenceMedianMs?.let { String.format(Locale.US, "%.3f", it) } ?: "unknown")
                shotLogger.recordPipelineEvent("ImageReader / Buffer", "imageMetadataPairSkewMedianMs", imageReaderPressureAtShutter.imageMetadataPairSkewMedianMs?.let { String.format(Locale.US, "%.3f", it) } ?: "unknown")
                shotLogger.recordPipelineEvent("ImageReader / Buffer", "estimatedFrameBytes", imageReaderPressureAtShutter.estimatedFrameBytes.toString())
                shotLogger.recordPipelineEvent("ImageReader / Buffer", "estimatedRingResidentImageBytes", imageReaderPressureAtShutter.estimatedRingResidentImageBytes.toString())
                shotLogger.recordPipelineEvent("ImageReader / Buffer", "configuredBufferCapacity", configuredBufferCapacityVal.toString())
                shotLogger.recordPipelineEvent("ImageReader / Buffer", "ringBufferCapacity", ringBufferCapacityVal.toString())
                shotLogger.recordPipelineEvent("ImageReader / Buffer", "framesReceived", framesReceivedVal.toString())
                shotLogger.recordPipelineEvent("ImageReader / Buffer", "acquiredImageCount", framesReceivedVal.toString())
                shotLogger.recordPipelineEvent("ImageReader / Buffer", "completePairCountAtShutter", completePairCountVal.toString())
                shotLogger.recordPipelineEvent("ImageReader / Buffer", "completedPairCountTotal", completedPairCountTotalVal.toString())
                shotLogger.recordPipelineEvent("ImageReader / Buffer", "ringRetainedCountAtShutter", ringRetainedCountVal.toString())
                shotLogger.recordPipelineEvent("ImageReader / Buffer", "framesEligible", initialAvailableCount.toString())
                shotLogger.recordPipelineEvent("ImageReader / Buffer", "framesScored", scoredCandidates.size.toString())
                shotLogger.recordPipelineEvent("ImageReader / Buffer", "framesRejectedStale", framesRejectedStaleVal.toString())
                shotLogger.recordPipelineEvent("ImageReader / Buffer", "framesDroppedByImageReaderMeasured", "false")
                shotLogger.recordPipelineEvent("ImageReader / Buffer", "framesDroppedByImageReader", "unknown")
                shotLogger.recordPipelineEvent("ImageReader / Buffer", "imageReaderAcquireFailureCount", imageReaderAcquireFailureCountVal.toString())
                shotLogger.recordPipelineEvent("ImageReader / Buffer", "imageReaderBackpressureDetected", bufferBackpressureDetectedVal.toString())
                shotLogger.recordPipelineEvent("ImageReader / Buffer", "ringBufferOverwriteCount", ringOverwriteCountVal.toString())
                shotLogger.recordPipelineEvent("ImageReader / Buffer", "ringBufferOverwriteObserved", (ringOverwriteCountVal > 0).toString())
                shotLogger.recordPipelineEvent("ImageReader / Buffer", "ringBufferOverwriteExpected", "not_inferred_from_acquired_image_count")
                shotLogger.recordPipelineEvent("ImageReader / Buffer", "framesDroppedByRingBuffer", framesDroppedByRingBufferVal.toString())
                shotLogger.recordPipelineEvent("ImageReader / Buffer", "completePairsOverwritten", framesDroppedByRingBufferVal.toString())
                shotLogger.recordPipelineEvent("ImageReader / Buffer", "framesRejectedGeneration", framesRejectedGenerationVal.toString())
                shotLogger.recordPipelineEvent("ImageReader / Buffer", "bufferBackpressureDetected", bufferBackpressureDetectedVal.toString())
                shotLogger.recordPipelineEvent("ImageReader / Buffer", "oldestFrameAgeMs", String.format(Locale.US, "%.1f", oldestFrameAgeMs))
                shotLogger.recordPipelineEvent("ImageReader / Buffer", "newestFrameAgeMs", String.format(Locale.US, "%.1f", newestFrameAgeMs))
                shotLogger.recordPipelineEvent("ImageReader / Buffer", "selectedDeltaMs", String.format(Locale.US, "%.1f", selectedDeltaMs))
                shotLogger.recordPipelineEvent("Buffer Health Freshness", "purpose", warmBufferRequirement.purpose)
                shotLogger.recordPipelineEvent("Buffer Health Freshness", "streamHealthFreshnessWindowMs", String.format(Locale.US, "%.1f", warmBufferRequirement.streamHealthFreshnessWindowMs))
                shotLogger.recordPipelineEvent("Buffer Health Freshness", "freshCompletePairsAtShutter", if (streamHealthFreshPairCountAtShutter >= 0) streamHealthFreshPairCountAtShutter.toString() else "unavailable_clock_domain")
                shotLogger.recordPipelineEvent("Buffer Health Freshness", "requiredCompletePairs", requiredWarmPairCountVal.toString())
                shotLogger.recordPipelineEvent("Shutter Candidate Freshness", "genuineNearZslWindowMs", String.format(Locale.US, "%.1f", selectionResult.freshnessWindowMs))
                shotLogger.recordPipelineEvent("Shutter Candidate Freshness", "selectedFrameAgeAbsMs", String.format(Locale.US, "%.3f", selectionResult.selected.candidateAgeAbsMs))
                shotLogger.recordPipelineEvent("Shutter Candidate Freshness", "selectedFrameIsGenuineNearZsl", selectionResult.selected.freshEnoughForSelection.toString())
                shotLogger.recordPipelineEvent("Shutter Candidate Freshness", "genuineNearZslCandidateCount", selectionResult.freshCandidateCount.toString())
                shotLogger.recordPipelineEvent("Shutter Candidate Freshness", "outsideNearZslWindowCandidateCount", selectionResult.staleCandidateCount.toString())
                shotLogger.recordPipelineEvent("Shutter Candidate Freshness", "degradedClosestFrameFallbackUsed", selectionResult.degradedFallbackUsed.toString())
                shotLogger.recordPipelineEvent("Shutter Candidate Freshness", "anchorSelectionReason", selectionResult.anchorSelectionReason)
                shotLogger.recordPipelineEvent("Frame Selection Quality", "effectiveSelectionBias", selectionResult.effectiveSelectionBias)
                val selectedPixelAnalysis = analyzedCandidates.first { it.frame === anchorFrame }
                shotLogger.recordPipelineEvent("Frame Selection Quality", "pixelMetricsValid", (selectedPixelAnalysis.source == "native_sampled_pixels").toString())
                shotLogger.recordPipelineEvent("Frame Selection Quality", "pixelCandidateCount", analyzedCandidates.count { it.source == "native_sampled_pixels" }.toString())
                shotLogger.recordPipelineEvent("Frame Selection Quality", "selectedSharpnessScore", String.format(Locale.US, "%.5f", winningCandidate.sharpnessScore))
                shotLogger.recordPipelineEvent("Frame Selection Quality", "selectedMotionScore", String.format(Locale.US, "%.5f", winningCandidate.motionScore))
                shotLogger.recordPipelineEvent("Frame Selection Quality", "selectedMotionModel", winningCandidate.motionScoreDetails)
                shotLogger.recordPipelineEvent("Frame Selection Quality", "selectedClippingScore", String.format(Locale.US, "%.5f", winningCandidate.clippingScore))
                shotLogger.recordPipelineEvent("Frame Selection Quality", "selectedLowClippedPct", String.format(Locale.US, "%.5f", selectedPixelAnalysis.lowClippedFraction * 100.0))
                shotLogger.recordPipelineEvent("Frame Selection Quality", "selectedHighClippedPct", String.format(Locale.US, "%.5f", selectedPixelAnalysis.highClippedFraction * 100.0))
                shotLogger.recordPipelineEvent("Frame Selection Quality", "analysisTimeMsTotal", String.format(Locale.US, "%.3f", analyzedCandidates.sumOf { it.analysisTimeMs }))
                shotLogger.recordPipelineEvent("Frame Selection Quality", "scoreSource", selectedPixelAnalysis.source)

                shotLogger.recordPipelineEvent("Thumbnail / URI Lifecycle", "thumbnailUriReturnedAfterSave", "false")
                shotLogger.recordPipelineEvent("Thumbnail / URI Lifecycle", "jpegSaveSucceededBeforeUriReturn", "false")
                shotLogger.recordPipelineEvent("Thumbnail / URI Lifecycle", "mediaStorePendingClearedBeforeUriReturn", "false")
                shotLogger.recordPipelineEvent("Thumbnail / URI Lifecycle", "returnedUriWasPending", "true")
                shotLogger.recordPipelineEvent("Thumbnail / URI Lifecycle", "thumbnailUpdateUri", if (saveSucceeded && mediaStorePendingCleared) jpegPublicUri.toString() else "none")
                shotLogger.recordPipelineEvent("Thumbnail / URI Lifecycle", "thumbnailUpdateSource", "asynchronous_publication")
                shotLogger.recordPipelineEvent("Thumbnail / URI Lifecycle", "captureExecuteReturnedAfterRenderMs", totalProcessingTimeMs.toString())
                shotLogger.recordPipelineEvent("Thumbnail / URI Lifecycle", "detachedIspLaunchUsed", isRawFrameSource.toString())

                shotLogger.recordPipelineEvent("ImageReader / Buffer", "Candidate Frames Analyzed", scoredCandidates.size.toString())
                shotLogger.recordPipelineEvent("ImageReader / Buffer", "Selected Anchor Rank", (selectedAnchorRank + 1).toString())
                shotLogger.recordPipelineEvent("ImageReader / Buffer", "Selected Anchor Timestamp", frameDebugEntries.firstOrNull { it.selectedAnchor }?.timestampNs?.toString() ?: "not recorded")
                shotLogger.recordPipelineEvent("Renderer Pipeline", "Render Time", "$renderTimeMs ms")
                if (activeZslFormat == ImageFormat.YUV_420_888) {
                    shotLogger.recordPipelineEvent("YUV Native Render", "Stats", yuvNativeStats)
                    parseNativeStats(yuvNativeStats).forEach { (key, value) ->
                        shotLogger.recordPipelineEvent("YUV Native Render", prettyStatKey(key), value)
                    }
                }
                shotLogger.recordPipelineEvent("Renderer Pipeline", "JPEG Bytes", "${bytesToSave?.size ?: 0}")
                shotLogger.recordPipelineEvent("Timing Breakdown", "Save Time", "$saveTimeMs ms")
                val dngPerformanceStats = parseNativeStats(dngMergeStats)
                val rawIspPerformanceStats = parseNativeStats(masterIspStats)
                val yuvPerformanceStats = parseNativeStats(yuvNativeStats)
                val jpegEncodeMs = rawIspPerformanceStats["jpegEncodeMs"]
                    ?: yuvPerformanceStats["yuvJpegEncodeMs"]
                    ?: "0"
                shotLogger.recordPipelineEvent("Performance", "captureDispatchLatencyMs", String.format(Locale.US, "%.3f", captureDispatchLatencyMs))
                shotLogger.recordPipelineEvent("Performance", "rawUnpackMs", dngPerformanceStats["anchorUnpackMs"] ?: "0")
                shotLogger.recordPipelineEvent("Performance", "mergeMs", dngPerformanceStats["totalNativeDngMergeMs"] ?: "0")
                shotLogger.recordPipelineEvent("Performance", "dngWriteMs", dngWriteTimeMs.toString())
                shotLogger.recordPipelineEvent("Performance", "jpegRenderMs", renderTimeMs.toString())
                shotLogger.recordPipelineEvent("Performance", "jpegEncodeMs", jpegEncodeMs)
                shotLogger.recordPipelineEvent("Performance", "saveMs", saveTimeMs.toString())
                performanceTracker.debugPairs().forEach { (key, value) ->
                    shotLogger.recordPipelineEvent("Performance", key, value)
                }

                shotLogger.recordPipelineEvent("Renderer Pipeline", "Renderer", "NATIVE C++ HW BUFFER ENGINE")
                if (activeZslFormat == ImageFormat.RAW10 || activeZslFormat == ImageFormat.RAW_SENSOR) {
                    shotLogger.recordPipelineEvent("Renderer Pipeline", "CFA Pattern", renderQualityConfig.cfaName.ifBlank { raw10CfaForDebug })
                }

                val avgAlign = if (scoredCandidates.isNotEmpty()) totalAlign / scoredCandidates.size else 0.0
                if (scoredCandidates.size < 3) {
                    val warning = if (completePairCountVal < 3) {
                        "Ring held $completePairCountVal complete pair(s) at shutter; ${scoredCandidates.size} candidate frame(s) were analyzed."
                    } else {
                        "Ring was populated with $completePairCountVal complete pair(s), but only ${scoredCandidates.size} candidate frame(s) remained after collection/pruning. This is not a ring-underfill diagnosis."
                    }
                    shotLogger.recordWarning(
                        "Buffer Warnings",
                        warning,
                        "INFO"
                    )
                }
                if (avgAlign < 0.3 && activeZslFormat != ImageFormat.RAW10) {
                    shotLogger.recordWarning("Frame Source Warnings", "Low YUV alignability detected. Average alignability=${String.format(Locale.US, "%.3f", avgAlign)}", "WARN")
                }

                val sensorOrientation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: -1
                val lensFacing = when (chars.get(CameraCharacteristics.LENS_FACING)) {
                    CameraCharacteristics.LENS_FACING_FRONT -> "FRONT"
                    CameraCharacteristics.LENS_FACING_BACK -> "BACK"
                    CameraCharacteristics.LENS_FACING_EXTERNAL -> "EXTERNAL"
                    else -> "UNKNOWN"
                }
                val selectedAnchor = frameDebugEntries.firstOrNull { it.selectedAnchor }

                // Redundant metering definitions removed.

                fun formatAeRegions(regions: Array<android.hardware.camera2.params.MeteringRectangle>?): String {
                    val list = regions?.filter { it.meteringWeight > 0 } ?: emptyList()
                    if (list.isEmpty()) return "none"
                    return list.joinToString(";") { "[${it.rect.left},${it.rect.top},${it.rect.width()}x${it.rect.height()},w=${it.meteringWeight}]" }
                }

                fun aeStateName(state: Int?): String = when (state) {
                    CaptureResult.CONTROL_AE_STATE_INACTIVE -> "INACTIVE"
                    CaptureResult.CONTROL_AE_STATE_SEARCHING -> "SEARCHING"
                    CaptureResult.CONTROL_AE_STATE_CONVERGED -> "CONVERGED"
                    CaptureResult.CONTROL_AE_STATE_LOCKED -> "LOCKED"
                    CaptureResult.CONTROL_AE_STATE_FLASH_REQUIRED -> "FLASH_REQUIRED"
                    CaptureResult.CONTROL_AE_STATE_PRECAPTURE -> "PRECAPTURE"
                    else -> "UNKNOWN(${state})"
                }

                val payload = DiagnosticPayload(
                    shotId = shotId,
                    startedAtStr = startedAtStr,
                    completedAtStr = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).format(Date(System.currentTimeMillis())),
                    publicFilename = "$baseFilename.jpg",
                    savedOutputPath = jpegPublicUri.toString(),
                    profileName = activeProfile.name,
                    profileId = profileId,
                    requestedModeLabel = activeProfile.captureStrategy.label,
                    preferredFrameSetting = preferredFrameSetting,
                    cameraId = activeLens.id,
                    targetRotation = finalJpegRotation,
                    frameWidth = frameWidth,
                    frameHeight = frameHeight,
                    bufferFormat = activeBufferFormatLabel,
                    totalShotTimeMs = totalProcessingTimeMs,
                    cameraCaptureTimeMs = captureDispatchLatencyMs.toLong(),
                    mergeTimeMs = dngPerformanceStats["totalNativeDngMergeMs"]?.toDoubleOrNull()?.toLong() ?: 0L,
                    framesBuffered = framesInBufferCount,
                    framesRequested = 1,
                    framesEligible = initialAvailableCount,
                    prunedInvalid = rejectedByInvalid,
                    prunedStale = rejectedByStale,
                    prunedDupes = rejectedByDupes,
                    prunedFirstFrame = rejectedByFirstFrame,
                    framesAccepted = 1,
                    framesMerged = 1,
                    framesRejected = framesRejected,
                    captureSucceeded = saveSucceeded,
                    requestedMode = activeProfile.captureStrategy.name,
                    resolvedRoute = when (activeZslFormat) {
                        ImageFormat.RAW_SENSOR -> "CAMERA2_RAW_SENSOR_WARM_BUFFER_SINGLE_FRAME"
                        ImageFormat.RAW10 -> "CAMERA2_RAW10_WARM_BUFFER_SINGLE_FRAME"
                        else -> "CAMERA2_YUV_WARM_BUFFER_SINGLE_FRAME"
                    },
                    actualRoute = when (activeZslFormat) {
                        ImageFormat.RAW_SENSOR -> "CAMERA2_RAW_SENSOR_WARM_BUFFER_SINGLE_FRAME"
                        ImageFormat.RAW10 -> "CAMERA2_RAW10_WARM_BUFFER_SINGLE_FRAME"
                        else -> "CAMERA2_YUV_WARM_BUFFER_SINGLE_FRAME"
                    },
                    routeRunner = "SingleFrameRunner",
                    basePosition = basePosition,
                    candidatesAnalyzed = scoredCandidates.size,
                    includeInMerge = baseInclude,
                    primaryBias =
                        "${selectionSettings.effectiveBiasLabel} via " +
                                selectionSettings.effectiveBiasSource,
                    temporalBias = 0f,
                    frameBias = selectionSettings.effectiveBiasLabel,
                    acceptAllFrames = false,
                    rejectDupes = rejectDupes,
                    alignableOnly = false,
                    discardFirstFrame = false,
                    preferRecent =
                        selectionSettings.effectiveBias ==
                                SingleFrameSelectionBias.RECENCY,
                    ignoreStaleFrames = ignoreStale,
                    preMergeTriggered = true,
                    alignmentTriggered = false,
                    mergeTriggered = false,
                    postMergeTriggered = false,
                    advancedTriggered = false,
                    fallbackUsed = false,
                    fallbackTarget = "none",
                    fallbackReason = "none",
                    hardFailure = finalProcessingFailure != null,
                    processingFallback = false,
                    routeAnalysis = if (activeZslFormat == ImageFormat.RAW10 || activeZslFormat == ImageFormat.RAW_SENSOR) "$activeBufferFormatLabel -> untouched OriginalMasterRaw16 (DNG) + JPEG-only LinearFloatRaw normalization -> demosaic/render" else "$activeBufferFormatLabel single-frame HardwareBuffer pipeline delegated to native C++.",
                    lensName = activeLens.name,
                    lensFacing = lensFacing,
                    sensorOrientation = sensorOrientation,
                    outputWidth = frameWidth,
                    outputHeight = frameHeight,
                    jpegCreated = bytesToSave != null,
                    jpegBytes = bytesToSave?.size ?: 0,
                    saveLocation = saveLocation,
                    gpsAdded = gpsAdded,
                    dngCreated = dngPublicUri != null,
                    dngBytes = if (dngPublicUri != null) dngBytesSize else 0,
                    dngSavedOutputPath = dngPublicUri?.toString() ?: "not created",
                    rawSensorDisabled = activeZslFormat != ImageFormat.RAW_SENSOR,
                    raw16Disabled = activeZslFormat != ImageFormat.RAW_SENSOR,
                    openGlUsed = false,
                    openCvUsed = ImageUtils.nativeEngineAvailable,
                    bufferCapacity = ringBuffer.currentCapacity(),
                    bufferWarmEnough =
                        if (streamHealthFreshPairCountAtShutter >= 0) {
                            streamHealthFreshPairCountAtShutter >=
                                    requiredWarmPairCountVal
                        } else {
                            completePairCountVal >= requiredWarmPairCountVal
                        },
                    selectedAnchorIndex = selectedAnchor?.index ?: selectedAnchorRank,
                    selectedAnchorTimestampNs = selectedAnchor?.timestampNs ?: 0L,
                    selectedAnchorDeltaMs = selectedAnchor?.deltaToShutterMs ?: 0.0,
                    selectedAnchorTiming = selectedAnchor?.shutterRelation ?: "unknown",
                    selectedAnchorReason = selectedAnchor?.decisionReason ?: "not recorded",
                    analysisSource = analysisSource,
                    renderTimeMs = renderTimeMs,
                    jpegCompressionTimeMs = jpegEncodeMs.toDoubleOrNull()?.toLong() ?: 0L,
                    saveTimeMs = saveTimeMs,
                    failureReason = finalProcessingFailure?.let { "${it.javaClass.simpleName}: ${it.message}" } ?: if (!saveSucceeded) "Output save failed or JPEG bytes unavailable" else "none",
                    meteringStyle = meteringStyle,
                    evOffset = evOffset,
                    aeRegionsRequested = formatAeRegions(selectedRequestedAeRegions),
                    aeRegionsResult = formatAeRegions(actualAeRegions),
                    evCompRequested = requestedEvComp,
                    evCompResult = actualEvComp,
                    aeStateBeforeCapture = aeStateName(aeStateBeforeCapture),
                    aeStateAtCapture = aeStateName(captureMetadata?.get(CaptureResult.CONTROL_AE_STATE)),
                    sensorExposureTime = captureMetadata?.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: 0L,
                    sensorSensitivity = captureMetadata?.get(CaptureResult.SENSOR_SENSITIVITY) ?: 0,
                    meteringAppliedToRepeatingRequest = meteringAppliedToRepeatingRequest,
                    meteringAppliedToStillRequest = meteringAppliedToStillRequest,
                    meteringRequested = meteringRequested,
                    meteringAppliedExact = meteringAppliedExact,
                    meteringAppliedPartial = meteringAppliedPartial,
                    meteringAppliedToSelectedFrame = meteringAppliedToSelectedFrame,
                    meteringAppliedStatus = meteringAppliedStatus,
                    selectedFrameSourceRequestType = selectedFrameSourceRequestType,
                    meteringAppliedToRepeatingRequestStatus = meteringAppliedToRepeatingRequestStatus,
                    meteringAppliedToStillRequestStatus = meteringAppliedToStillRequestStatus
                )
                val vendorAttempts = VendorInjectionEngine.consumeAttempts(activeLens.id)
                vendorAttempts.forEach { attempt ->
                    captureTrace.record(
                        com.bncam.core.tracing.CaptureTraceSection.VENDOR_TAGS,
                        attempt.keyName,
                        "stage=${attempt.builderStage};status=${attempt.finalStatus};" +
                            "attempted=${attempt.attempted};applied=${attempt.appliedToBuilder}"
                    )
                }

                if (logVendorInjection) {
                    shotLogger.writeVendorInjectionDebug(
                        lensId = activeLens.id,
                        attempts = vendorAttempts,
                        captureResult = captureMetadata
                    )
                }

                val noiseTraceFrames = renderQualityConfig.finalCalibration?.let { calibration ->
                    val exactNoiseFrameTimestampNs = captureMetadata
                        ?.get(CaptureResult.SENSOR_TIMESTAMP)
                        ?.takeIf { it > 0L }
                        ?: selectedAnchor?.timestampNs?.takeIf { it > 0L }
                        ?: selectedFrameTimestampNs
                    listOf(
                        NoiseModelTraceFrame(
                            captureId = "${activeLens.id}-$exactNoiseFrameTimestampNs",
                            frameTimestampNs = exactNoiseFrameTimestampNs,
                            lensId = activeLens.id,
                            calibration = calibration,
                            captureResult = captureMetadata
                        )
                    )
                }.orEmpty()
                shotLogger.writeTextFile(
                    "noise_model_trace.json",
                    NoiseModelTrace.build(
                        frames = noiseTraceFrames,
                        jniCalibration = renderQualityConfig.finalCalibration,
                        nativeStats = if (isRawFrameSource) masterIspStats else yuvNativeStats,
                        fusionStats = if (isRawFrameSource) dngMergeStats else "",
                        dynamicIsoCoefficient = lensHardwareSettings.dynamicIsoCoeff,
                        captureAttemptId = attemptId,
                        recipe = recipe,
                        runnerPerformance = performanceTracker.traceSnapshot(),
                        publicationState = NoiseModelPublicationState(
                            jpegPublished = jpegPublished,
                            dngPublished = dngPublished,
                            thumbnailPublished = publicationSnapshot.thumbnailUri != null,
                            publicationResult = publicationSnapshot.publicationResult.name,
                            jpegFailureReason = jpegSaveFailure?.message,
                            dngFailureReason = dngFailureReason
                        )
                    )
                )
                shotLogger.writeShotDebug(payload, logSummary, logActiveMode, logProfileSettings, logFrameAnalysis, logWarnings, logPipelineDebug, logVendorInjection)
            }

            if (enableShotLogger) {
                shotLogger.finalizeAttemptOnce(
                    attemptId = attemptId,
                    terminalState = com.bncam.core.debug.CaptureStatusState.COMPLETED,
                    stage = "CAPTURE_COMPLETED",
                    jpegPublished = jpegPublished,
                    dngPublished = dngPublished,
                    noiseModelStarted = renderQualityConfig.finalCalibration?.noiseModelMode != "Off",
                    noiseModelCompleted = (if (isRawFrameSource) masterIspStats else yuvNativeStats).contains("noiseModelApplied=yes")
                )
            }
            val resolvedOutputs = com.bncam.core.output.PublicationPolicyResolver.resolveUris(
                outputPolicy = plan.outputPolicy,
                jpegSucceeded = jpegPublished,
                jpegUri = if (jpegPublished) jpegPublicUri else null,
                dngSucceeded = dngPublished,
                dngUri = dngPublicUri,
                jpegFailureReason = jpegSaveFailure?.message,
                dngFailureReason = dngFailureReason
            )
            publishedOutputUri = resolvedOutputs.thumbnailUri
            if (jpegPublished) {
                Log.i(
                    tag,
                    "JPEG_SAVE_SUCCEEDED profile=$profileId requested=$preferredFrameSetting actual=$activeBufferFormatLabel " +
                            "generation=$selectedFrameGeneration timestamp=$selectedFrameTimestampNs " +
                            "metadataPresent=${captureMetadata != null} output=$jpegPublicUri bytes=${bytesToSave?.size ?: 0}"
                )
            }
            if (dngPublished) {
                Log.i(
                    tag,
                    "DNG_SAVE_SUCCEEDED profile=$profileId requested=$preferredFrameSetting actual=$activeBufferFormatLabel " +
                            "generation=$selectedFrameGeneration timestamp=$selectedFrameTimestampNs " +
                            "metadataPresent=${captureMetadata != null} output=$dngPublicUri bytes=$dngBytesSize"
                )
            }
            if (resolvedOutputs.publicationResult == com.bncam.core.output.CapturePublicationResult.FAILURE) {
                throw IllegalStateException(
                    "Neither JPEG nor DNG output was published. " +
                            "jpegFailure=${jpegSaveFailure?.message ?: "not published"}; dngFailure=$dngFailureReason",
                    jpegSaveFailure
                )
            }
            val stringOutputs = com.bncam.core.output.PublicationPolicyResolver.resolveStrings(
                outputPolicy = plan.outputPolicy,
                jpegSucceeded = jpegPublished,
                jpegUri = if (jpegPublished) jpegPublicUri.toString() else null,
                dngSucceeded = dngPublished,
                dngUri = dngPublicUri?.toString(),
                jpegFailureReason = jpegSaveFailure?.message,
                dngFailureReason = dngFailureReason
            )
            if (jpegPublished) {
                performanceTracker.incrementCounter("jpegMediaStorePublicationCount")
            }
            if (dngPublished) {
                performanceTracker.incrementCounter("dngMediaStorePublicationCount")
            }
            performanceTracker.setMetric("genuinelyFusedFrameCount", 0)
            performanceTracker.setMetric("finalJpegByteCount", bytesToSave?.size ?: 0)
            val rawOwnerAtPublication = singleRawFrame
            performanceTracker.setMetric("dngRaw16ByteCount", rawOwnerAtPublication?.raw16ByteCount ?: 0)
            if (rawOwnerAtPublication != null) {
                performanceTracker.setMetric(
                    "raw16ManagedHeapMaterializationCount",
                    rawOwnerAtPublication.managedMaterializationCount
                )
                performanceTracker.setMetric(
                    "raw16ManagedHeapMaterializationBytes",
                    rawOwnerAtPublication.managedMaterializationBytes
                )
                val expectedMaterializations = if (plan.outputPolicy.producesRaw) 1 else 0
                check(rawOwnerAtPublication.managedMaterializationCount == expectedMaterializations) {
                    "Phase 1B invariant failed: ${plan.outputPolicy} materialized RAW16 " +
                        "${rawOwnerAtPublication.managedMaterializationCount} times; " +
                        "expected $expectedMaterializations."
                }
                rawOwnerAtPublication.close()
                performanceTracker.setMetric(
                    "raw16NativeOwnerReleasedBeforePublication",
                    rawOwnerAtPublication.nativeRaw16Buffer.isClosed
                )
                performanceTracker.setMetric(
                    "raw16NativeOutstandingBuffersAtPublication",
                    ImageUtils.nativeRaw16OutstandingBufferCountSafe()
                )
                if (singleRawFrame === rawOwnerAtPublication) singleRawFrame = null
            }

            val rawIspCount = performanceTracker.counter("rawIspInvocationCount")
            val jpegEncodeCount = performanceTracker.counter("jpegEncodeInvocationCount")
            val jpegPublicationCount = performanceTracker.counter("jpegMediaStorePublicationCount")
            val dngPublicationCount = performanceTracker.counter("dngMediaStorePublicationCount")
            check(performanceTracker.counter("rawMergeInvocationCount") == 0) {
                "Phase 0 invariant failed: single-frame capture invoked RAW merge."
            }
            check(!jpegPublished || !isRawFrameSource || rawIspCount == 1) {
                "Phase 0 invariant failed: single RAW JPEG capture invoked ISP $rawIspCount times."
            }
            check(!jpegPublished || jpegEncodeCount == 1) {
                "Phase 0 invariant failed: single JPEG capture encoded $jpegEncodeCount times."
            }
            check(!jpegPublished || jpegPublicationCount == 1) {
                "Phase 0 invariant failed: JPEG was published $jpegPublicationCount times."
            }
            check(!dngPublished || dngPublicationCount == 1) {
                "Phase 0 invariant failed: DNG was published $dngPublicationCount times."
            }
            captureTrace.record(
                com.bncam.core.tracing.CaptureTraceSection.BUFFER_LIFECYCLE,
                "configuredCapacity",
                ringBuffer.currentCapacity()
            )
            captureTrace.record(
                com.bncam.core.tracing.CaptureTraceSection.FRAME_CANDIDATES,
                "analyzedCandidateCount",
                scoredCandidates.size
            )
            captureTrace.decision(
                section = com.bncam.core.tracing.CaptureTraceSection.FRAME_SELECTION,
                key = "executedFrameSelection",
                requested = recipe.frameSelectionMethod.requestedId,
                supported = recipe.frameSelectionMethod.supported.toString(),
                resolved = recipe.frameSelectionMethod.resolvedId,
                executed = "balanced",
                result = "selected_timestamp_$selectedFrameTimestampNs",
                fallback = selectionResult.degradedFallbackUsed,
                reason = selectionResult.anchorSelectionReason
            )
            captureTrace.decision(
                section = com.bncam.core.tracing.CaptureTraceSection.ALIGNMENT,
                key = "executedAlignment",
                requested = recipe.alignmentMethod.requestedId,
                supported = recipe.alignmentMethod.supported.toString(),
                resolved = "not_applicable",
                executed = "not_executed",
                result = "single_frame",
                fallback = false,
                reason = "single_frame_route"
            )
            captureTrace.decision(
                section = com.bncam.core.tracing.CaptureTraceSection.FUSION,
                key = "executedFusion",
                requested = recipe.fusionMethod.requestedId,
                supported = recipe.fusionMethod.supported.toString(),
                resolved = "not_applicable",
                executed = "not_executed",
                result = "one_frame",
                fallback = false,
                reason = "single_frame_route"
            )
            val traceIspStats =
                if (isRawFrameSource) parseNativeStats(masterIspStats)
                else parseNativeStats(yuvNativeStats)
            captureTrace.decision(
                section = com.bncam.core.tracing.CaptureTraceSection.ISP_EXECUTION,
                key = "executedDemosaic",
                requested = recipe.demosaicMethod.requestedId,
                supported = recipe.demosaicMethod.supported.toString(),
                resolved = recipe.demosaicMethod.resolvedId,
                executed =
                    if (isRawFrameSource) {
                        traceIspStats["resolvedDemosaicAlgorithm"]
                            ?: recipe.demosaicMethod.resolvedId
                    } else {
                        "not_applicable_yuv"
                    },
                result = if (jpegPublished) "jpeg_rendered" else "jpeg_not_published",
                fallback =
                    traceIspStats["fallbackOccurred"].equals("true", ignoreCase = true),
                reason = traceIspStats["fallbackReason"]
            )
            captureTrace.decision(
                section = com.bncam.core.tracing.CaptureTraceSection.OUTPUT_AND_PUBLICATION,
                key = "executedDngSource",
                requested = recipe.dngSource.name,
                supported = plan.outputPolicy.producesRaw.toString(),
                resolved = recipe.dngSource.name,
                executed =
                    if (dngPublished) {
                        com.bncam.core.capture.DngSource.ANCHOR_RAW.name
                    } else {
                        "not_executed"
                    },
                result =
                    if (dngPublished) {
                        "published_dng"
                    } else if (plan.outputPolicy.producesRaw) {
                        "dng_not_published"
                    } else {
                        "not_applicable"
                    },
                fallback = false,
                reason =
                    if (dngPublished) {
                        "published_dng_contains_selected_anchor_raw"
                    } else if (plan.outputPolicy.producesRaw) {
                        "required_dng_publication_failed"
                    } else {
                        "output_policy_does_not_request_dng"
                    }
            )
            captureTrace.record(
                com.bncam.core.tracing.CaptureTraceSection.PERFORMANCE_AND_THERMAL,
                "totalUntilPublicationMs",
                performanceTracker.elapsedMs()
            )
            if (enableShotLogger) {
                shotLogger.writeCaptureTrace(captureTrace.complete(stringOutputs))
            }
            performanceTracker.incrementCounter("terminalPublicationCount")
            performanceTracker.mark("publication_complete")
            processingWork?.markPublished(stringOutputs)
            performanceTracker.persistJsonLine(context, status = "PUBLISHED")
            } finally {
                singleRawFrame?.close()
                singleRawFrame = null
            }
            }
            val saveFailureHandler: (Throwable) -> Unit = {
                if (enableShotLogger) {
                    shotLogger.writeCaptureTrace(
                        captureTrace.completeFailure("output_publication", it)
                    )
                    shotLogger.finalizeFailureWithPublicDiagnostics(
                        attemptId = attemptId,
                        stage = "ASYNC_OUTPUT_PUBLICATION",
                        exception = it
                    )
                }
                runCatching { context.contentResolver.delete(jpegPublicUri, null, null) }
                performanceTracker.persistJsonLine(
                    context = context,
                    status = "FAILED",
                    failureReason = "save_failed:${it.javaClass.simpleName}:${it.message}"
                )
                processingWork?.fail(
                    "save_failed:${it.javaClass.simpleName}:${it.message ?: "no_message"}"
                )
            }
            processingWork?.markSaving()
            val queued = CaptureSaveQueue.enqueue(
                context = context,
                route = plan.route.id,
                captureStartedNs = shutterTimestampNs,
                onFailure = saveFailureHandler,
                block = saveBlock
            )
            val rawTiming = parseNativeStats(dngMergeStats)
            val rawRenderTiming = parseNativeStats(masterIspStats)
            val yuvTiming = parseNativeStats(yuvNativeStats)
            Log.i(
                "BnCamCaptureTiming",
                "route=${plan.route.id} camera_request_time=${String.format(Locale.US, "%.3f", captureDispatchLatencyMs)} " +
                    "frame_acquire_time=0.000 frame_select_time=${String.format(Locale.US, "%.3f", frameSelectTimeMs)} " +
                    "raw_unpack_time=${rawTiming["anchorUnpackMs"] ?: "0"} master_build_time=0 " +
                    "demosaic_time=${rawRenderTiming["demosaicMs"] ?: "0"} " +
                    "yuv_convert_time=${yuvTiming["yuvToBgrMs"] ?: "0"} " +
                    "render_profile_time=${rawRenderTiming["curveMs"] ?: yuvTiming["yuvPostProcessMs"] ?: "0"} " +
                    "denoise_time=${rawRenderTiming["denoiseMs"] ?: "0"} sharpen_time=${rawRenderTiming["sharpenMs"] ?: "0"} " +
                    "jpeg_encode_time=${rawRenderTiming["jpegEncodeMs"] ?: yuvTiming["yuvJpegEncodeMs"] ?: "0"} " +
                    "exif_time=QUEUED mediastore_write_time=QUEUED optional_analysis_time=0 " +
                    "total_until_jpeg_render_complete=${String.format(Locale.US, "%.3f", performanceTracker.elapsedMs())} " +
                    "total_until_preview_ready=${String.format(Locale.US, "%.3f", performanceTracker.elapsedMs())}"
            )
            BenchmarkWriter.record(
                context = context,
                route = plan.route.id,
                format = activeBufferFormatLabel,
                masterIspStats = if (isRawFrameSource) masterIspStats else yuvNativeStats,
                dngMergeStats = dngMergeStats,
                totalElapsedMs = performanceTracker.elapsedMs(),
                frameSelectTimeMs = frameSelectTimeMs,
                rawUnpackTimeMs = rawUnpackMs
            )
            if (!queued) {
                singleRawFrame?.close()
                singleRawFrame = null
                performanceTracker.persistJsonLine(
                    context = context,
                    status = "FAILED",
                    failureReason = "save_queue_rejected"
                )
            } else if (isRawFrameSource) {
                nativeRawOwnerTransferredToSaveQueue = true
            }
            queued
            } finally {
                if (isRawFrameSource && !nativeRawOwnerTransferredToSaveQueue) {
                    singleRawFrame?.close()
                    singleRawFrame = null
                }
            }
        }
        }

        if (isRawFrameSource) {
            val reservation = requireNotNull(rawWorkReservation)
            // DELTA 0222: RAW processing continues to read immutable capture metadata through the
            // selected ZslFramePair after the native HardwareBuffer has been retained/materialized.
            // Keep the ring slot leased until the detached RAW worker has finished its render and
            // publication-integrity decision. Releasing here allowed the ring to recycle/mutate the
            // same ZslFramePair while the worker was still using anchorFrame, creating intermittent
            // RAW_METADATA_TIMESTAMP_MISMATCH / RAW_METADATA_PROVENANCE_UNSAFE failures.
            val rawAnchorLease = requireNotNull(anchorLease) {
                "Single RAW requires the selected Near-ZSL FrameLease before processing handoff."
            }
            val submitted = CaptureProcessingQueue.submit(context, reservation) { work ->
                try {
                    val saveAccepted = processAndQueueOutput(
                        CaptureStageListener.NONE,
                        work
                    )
                    if (!saveAccepted) {
                        work.fail("save_queue_full_or_output_unavailable")
                        reservedJpegUri?.let { uri ->
                            runCatching { context.contentResolver.delete(uri, null, null) }
                        }
                        throw IllegalStateException(
                            "RAW processing completed but output save was not accepted."
                        )
                    }
                } catch (failure: Throwable) {
                    if (enableShotLogger) {
                        shotLogger.finalizeFailureWithPublicDiagnostics(
                            attemptId = attemptId,
                            stage = "ASYNC_RAW_PROCESSING_WORKER",
                            exception = failure
                        )
                    }
                    throw failure
                } finally {
                    rawAnchorLease.release()
                }
            }
            if (!submitted) {
                acquiredRawInput?.close()
                acquiredRawInputForCleanup = null
                reservedJpegUri?.let { uri ->
                    runCatching { context.contentResolver.delete(uri, null, null) }
                }
                performanceTracker.persistJsonLine(
                    context = context,
                    status = "FAILED",
                    failureReason = "processing_queue_submit_failed_raw"
                )
                reservation.fail("processing_queue_submit_failed")
                throw IllegalStateException("RAW processing queue rejected acquired input.")
            }
            rawWorkHandedOff = true
            acquiredRawInputForCleanup = null
            // The detached worker now owns the selected frame lease. Clear the capture-side
            // reference so the outer finally cannot release/recycle the mutable ring slot early.
            anchorLease = null
            performanceTracker.setMetric(
                "sourceFrameOwnership",
                "SELECTED_FRAME_LEASE_TRANSFERRED_TO_RAW_PROCESSING_QUEUE"
            )
            Log.i(
                "BnCamCaptureTiming",
                "route=${plan.route.id} lifecycle=PROCESSING_QUEUED " +
                    "processingWorkId=${reservation.workId} " +
                    "total_until_preview_ready=" +
                    "${String.format(Locale.US, "%.3f", performanceTracker.elapsedMs())}"
            )
            return@withContext com.bncam.core.output.CaptureSubmissionResult.Submitted(
                attemptId = plan.route.id,
                workId = reservation.workId,
                temporaryPreviewPath = temporaryPreviewPath
            )
        }

        // Single YUV previously rendered synchronously, enqueued MediaStore publication, and then
        // immediately required publishedOutputUri to be non-null. CaptureSaveQueue is asynchronous,
        // so this deterministically converted an otherwise successful capture into a terminal
        // failure after processing. Use the same explicit process-owned lifecycle as detached RAW.
        performanceTracker.setMetric("processingOwner", "CaptureProcessingQueue")
        performanceTracker.setMetric("sourceFrameOwnership", "SELECTED_FRAME_LEASE_HELD_BY_CAPTURE")
        performanceTracker.setMetric("singleYuvSynchronousPublicationContract", "REMOVED")
        val yuvReservation = CaptureProcessingQueue.tryReserve(
            route = plan.route.id,
            captureStartedNs = shutterTimestampNs,
            temporaryPreviewPath = temporaryPreviewPath
        ) ?: throw IllegalStateException(
            "Processing queue is full; Single YUV was not accepted."
        )

        val yuvAnchorLease = requireNotNull(anchorLease) {
            "Single YUV requires the selected Near-ZSL FrameLease before processing handoff."
        }
        val yuvSubmitted = CaptureProcessingQueue.submit(context, yuvReservation) { work ->
            try {
                val saveAccepted = processAndQueueOutput(
                    CaptureStageListener.NONE,
                    work
                )
                if (!saveAccepted) {
                    work.fail("save_queue_full_or_output_unavailable")
                    throw IllegalStateException(
                        "Single YUV processing completed but output save was not accepted."
                    )
                }
            } catch (failure: Throwable) {
                if (enableShotLogger) {
                    shotLogger.finalizeFailureWithPublicDiagnostics(
                        attemptId = attemptId,
                        stage = "ASYNC_YUV_PROCESSING_WORKER",
                        exception = failure
                    )
                }
                throw failure
            } finally {
                // The selected Image/HardwareBuffer must stay pinned until native YUV rendering
                // and output queueing have finished. This lease is the actual ownership handoff.
                yuvAnchorLease.release()
            }
        }
        if (!yuvSubmitted) {
            yuvReservation.fail("processing_queue_submit_failed")
            throw IllegalStateException(
                "Single YUV processing queue rejected acquired input."
            )
        }
        // submit() accepted the worker: from this point the worker owns the selected frame lease.
        // Clear the capture-side reference so the outer finally cannot release it prematurely.
        anchorLease = null
        yuvWorkHandedOff = true
        performanceTracker.setMetric(
            "sourceFrameOwnership",
            "SELECTED_FRAME_LEASE_TRANSFERRED_TO_PROCESSING_QUEUE"
        )
        Log.i(
            "BnCamCaptureTiming",
            "route=${plan.route.id} lifecycle=PROCESSING_QUEUED " +
                "processingWorkId=${yuvReservation.workId} sourceFrameOwner=PROCESSING_QUEUE " +
                "total_until_preview_ready=" +
                "${String.format(Locale.US, "%.3f", performanceTracker.elapsedMs())}"
        )
        return@withContext com.bncam.core.output.CaptureSubmissionResult.Submitted(
            attemptId = plan.route.id,
            workId = yuvReservation.workId,
            temporaryPreviewPath = temporaryPreviewPath
        )
        } finally {
            if (!rawWorkHandedOff) {
                acquiredRawInputForCleanup?.close()
                acquiredRawInputForCleanup = null
                rawWorkReservationForCleanup?.fail(
                    "capture_ended_before_processing_handoff"
                )
            }
            if (!yuvWorkHandedOff) {
                closeTakenFrames()
            }
            anchorLease?.release()
        }
    }

    private fun getJpegOrientation(chars: CameraCharacteristics, deviceRotation: Int): Int {
        val sensorOrientation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
        val deviceDegrees = when (deviceRotation) { Surface.ROTATION_0 -> 0; Surface.ROTATION_90 -> 90; Surface.ROTATION_180 -> 180; Surface.ROTATION_270 -> 270; else -> 0 }
        val facing = chars.get(CameraCharacteristics.LENS_FACING)
        return OutputRotationResolver.resolve(
            sensorOrientationDegrees = sensorOrientation,
            displayRotationDegrees = deviceDegrees,
            frontFacing = facing == CameraCharacteristics.LENS_FACING_FRONT
        )
    }

    private fun getExifOrientation(rotationDegrees: Int): Int {
        return when (rotationDegrees) { 0 -> 1; 90 -> 6; 180 -> 3; 270 -> 8; else -> 1 }
    }

    private suspend fun saveVirtualDngToMediaStore(
        raw16Bytes: ByteArray,
        filename: String,
        saveLocation: String,
        width: Int,
        height: Int,
        metadata: CaptureResult,
        characteristics: CameraCharacteristics,
        orientation: Int,
        dngMergeStats: String,
        lensHardwareDescription: String = "",
        rawDomainContract: com.bncam.core.isp.raw.RawDomainContract? = null,
        calibration: com.bncam.core.quality.FinalSensorCalibration? = null
    ): Pair<Uri?, Long> {
        return withContext(Dispatchers.IO) {
            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
                put(MediaStore.MediaColumns.MIME_TYPE, "image/x-adobe-dng")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val safeLocation = if (saveLocation.startsWith("Pictures/") || saveLocation.startsWith("DCIM/")) saveLocation else "Pictures/$saveLocation"
                    put(MediaStore.MediaColumns.RELATIVE_PATH, safeLocation)
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
            }

            val resolver = context.contentResolver
            val uri = try {
                resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
            } catch (t: Throwable) {
                Log.e(tag, "DNG MediaStore reservation failed filename=$filename size=${width}x$height rawBytes=${raw16Bytes.size}", t)
                null
            } ?: run {
                Log.e(tag, "DNG MediaStore reservation returned null filename=$filename size=${width}x$height rawBytes=${raw16Bytes.size}")
                return@withContext null to 0L
            }

            var bytesWritten = 0L
            try {
                resolver.openOutputStream(uri)?.use { outputStream ->
                    bytesWritten = DngWriter.writeDngFromVirtualToStream(
                        width = width,
                        height = height,
                        raw16Bytes = raw16Bytes,
                        metadata = metadata,
                        characteristics = characteristics,
                        orientation = orientation,
                        dngMergeStats = dngMergeStats,
                        lensHardwareDescription = lensHardwareDescription,
                        rawDomainContract = rawDomainContract,
                        outputStream = outputStream,
                        calibration = calibration
                    ) ?: 0L
                }

                if (bytesWritten <= 0L) {
                    Log.e(tag, "DngCreator produced zero bytes uri=$uri size=${width}x$height rawBytes=${raw16Bytes.size}")
                    resolver.delete(uri, null, null)
                    return@withContext null to 0L
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    contentValues.clear()
                    contentValues.put(MediaStore.MediaColumns.IS_PENDING, 0)
                    if (resolver.update(uri, contentValues, null, null) <= 0) {
                        throw IllegalStateException("DNG bytes were written but MediaStore publication failed for $uri")
                    }
                }

                uri to bytesWritten
            } catch (t: Throwable) {
                Log.e(tag, "DNG MediaStore streaming save failed", t)
                runCatching { resolver.delete(uri, null, null) }
                null to 0L
            }
        }
    }

    private suspend fun writeVirtualDngToReservedUri(
        uri: Uri,
        raw16Bytes: ByteArray,
        width: Int,
        height: Int,
        metadata: CaptureResult,
        characteristics: CameraCharacteristics,
        orientation: Int,
        dngMergeStats: String,
        lensHardwareDescription: String,
        rawDomainContract: com.bncam.core.isp.raw.RawDomainContract?,
        calibration: com.bncam.core.quality.FinalSensorCalibration? = null
    ) = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val bytesWritten = resolver.openOutputStream(uri)?.use { outputStream ->
            DngWriter.writeDngFromVirtualToStream(
                width = width,
                height = height,
                raw16Bytes = raw16Bytes,
                metadata = metadata,
                characteristics = characteristics,
                orientation = orientation,
                dngMergeStats = dngMergeStats,
                lensHardwareDescription = lensHardwareDescription,
                rawDomainContract = rawDomainContract,
                outputStream = outputStream,
                calibration = calibration
            )
        } ?: 0L
        if (bytesWritten <= 0L) throw IllegalStateException("DNG writer produced no bytes.")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val published = resolver.update(
                uri,
                ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                null,
                null
            ) > 0
            if (!published) throw IllegalStateException("DNG bytes were written but MediaStore publication failed.")
        }
    }


    private fun parseNativeStats(stats: String): Map<String, String> {
        if (stats.isBlank()) return emptyMap()
        return stats.split(";")
            .mapNotNull { part ->
                val idx = part.indexOf('=')
                if (idx <= 0 || idx >= part.lastIndex) null else part.substring(0, idx).trim() to part.substring(idx + 1).trim()
            }
            .toMap()
    }

    private fun prettyStatKey(key: String): String {
        return key.replace(Regex("([a-z])([A-Z])"), "$1 $2")
            .replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.US) else it.toString() }
    }


    private fun preInsertImageToMediaStore(filename: String, saveLocation: String, mimeType: String): Uri? {
        val resolver = context.contentResolver
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val safeLocation = if (saveLocation.startsWith("Pictures/") || saveLocation.startsWith("DCIM/")) saveLocation else "Pictures/$saveLocation"
                put(MediaStore.MediaColumns.RELATIVE_PATH, safeLocation)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }
        return try {
            resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
        } catch (e: Exception) {
            Log.e(tag, "Failed to pre-insert media entry", e)
            null
        }
    }

}
