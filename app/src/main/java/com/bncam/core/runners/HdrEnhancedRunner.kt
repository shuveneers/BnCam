package com.bncam.core.runners

import android.content.Context
import android.hardware.camera2.CameraManager
import android.util.Log
import com.bncam.core.buffer.FrameRingBuffer
import com.bncam.core.capture.CaptureRecipe
import com.bncam.core.capture.CaptureRequestPlan
import com.bncam.core.capture.CaptureStageListener
import com.bncam.core.capture.HdrEnhancedCaptureContext
import com.bncam.core.capture.PortraitCaptureContext
import com.bncam.core.debug.ShotLogger
import com.bncam.core.engine.FocusCaptureContext as EngineFocusCaptureContext
import com.bncam.core.engine.LensInfo
import com.bncam.core.output.CaptureSubmissionResult
import com.bncam.core.quality.StableWhiteBalanceSnapshot
import com.bncam.data.profile.CameraProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.sqrt

/**
 * Capture authority for Computational HDR.
 *
 * The Camera2 transaction itself is supplied by BnCameraManager because it owns the active
 * session/ImageReader lifecycle. This runner remains the production authority: it requests one
 * deliberate post-shutter burst, validates that acquisition, and hands the exact leased frames to
 * the shared Vulkan multi-frame backend. It never substitutes warm-buffer frames for HDR Enhanced.
 */
class HdrEnhancedRunner(
    private val context: Context,
    private val cameraManager: CameraManager
) {
    suspend fun execute(
        plan: CaptureRequestPlan,
        recipe: CaptureRecipe,
        ringBuffer: FrameRingBuffer,
        shutterTimestampNs: Long,
        shutterTimestampDomain: String,
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
        stableAutoWhiteBalance: StableWhiteBalanceSnapshot? = null,
        captureStageListener: CaptureStageListener = CaptureStageListener.NONE,
        onRawProcessingFeedback: (MultiRawProcessingFeedback) -> Unit = {},
        temporaryPreviewPath: String? = null,
        focusCaptureContext: EngineFocusCaptureContext = EngineFocusCaptureContext(),
        portraitCaptureContext: PortraitCaptureContext = PortraitCaptureContext(),
        acquireDeliberateBurst: suspend () -> HdrEnhancedCaptureContext?
    ): CaptureSubmissionResult = withContext(Dispatchers.IO) {
        require(recipe.computationalHdrRouteEnabled) {
            "HdrEnhancedRunner requires an enabled Computational HDR production route."
        }

        val captureContext = acquireDeliberateBurst()
            ?: return@withContext CaptureSubmissionResult.Rejected("hdr_enhanced_burst_acquisition_failed")
        var ownershipTransferred = false
        try {
            val frames = captureContext.mainFrames
            if (frames.size < 4) {
                return@withContext CaptureSubmissionResult.Rejected("hdr_enhanced_insufficient_valid_frames")
            }

            val expMin = frames.minOf { it.actualExposureTimeNs }
            val expMax = frames.maxOf { it.actualExposureTimeNs }
            val isoMin = frames.minOf { it.actualSensitivityIso }
            val isoMax = frames.maxOf { it.actualSensitivityIso }
            val exposureConsistent = expMin > 0L &&
                (expMax - expMin).toDouble() / expMin.toDouble() <= 0.05 &&
                isoMin > 0 && (isoMax - isoMin).toDouble() / isoMin.toDouble() <= 0.05
            if (!exposureConsistent) {
                return@withContext CaptureSubmissionResult.Rejected("hdr_enhanced_main_exposure_inconsistent")
            }

            val selectedBase = captureContext.selectedBaseFrame
            Log.i(
                "HdrEnhancedRunner",
                "HDR_ENHANCED_BURST_ACQUIRED: deliberateBurst=true requested=${captureContext.plan.mainFrameCount} " +
                    "acquired=${frames.size} provenanceValid=${frames.count { it.provenanceValid }} " +
                    "burstSubmitTimestampNs=${captureContext.burstSubmitElapsedRealtimeNs} " +
                    "sensorTimestampSource=${captureContext.sensorTimestampSource}"
            )
            frames.forEach { frame ->
                val sharpness = frame.sharpnessScore?.let { String.format(Locale.US, "%.3f", it) } ?: "UNAVAILABLE"
                val motion = frame.motionScore?.let { String.format(Locale.US, "%.3f", it) } ?: "UNAVAILABLE"
                val focus = frame.focusConfidence?.let { String.format(Locale.US, "%.3f", it) } ?: "UNAVAILABLE"
                val clipping = frame.clippingFraction?.let { String.format(Locale.US, "%.5f", it) } ?: "UNAVAILABLE"
                Log.i(
                    "HdrEnhancedRunner",
                    "HDR_ENHANCED_FRAME: tag=${frame.semanticTag} sequenceId=${frame.captureSequenceId} frameNumber=${frame.captureFrameNumber} " +
                        "requestSubmittedElapsedRealtimeNs=${frame.requestSubmittedElapsedRealtimeNs} sensorTimestampNs=${frame.timestampNs} " +
                        "expNs=${frame.actualExposureTimeNs} iso=${frame.actualSensitivityIso} " +
                        "sharpness=$sharpness motion=$motion focus=$focus clipping=$clipping provenanceValid=${frame.provenanceValid}"
                )
            }
            Log.i(
                "HdrEnhancedRunner",
                "HDR_ENHANCED_BASE_SELECTED: bestBaseSelected=true selectedBaseIndex=${captureContext.selectedBaseIndex} " +
                    "selectedBaseTimestampNs=${selectedBase.timestampNs} measuredEvidence=${captureContext.baseSelectionMeasuredEvidenceAvailable} " +
                    "reason=${captureContext.baseSelectionReason}"
            )
            Log.i(
                "HdrEnhancedRunner",
                "HDR_ENHANCED_PROCESSING_DISPATCH: captureAuthorityRunner=HdrEnhancedRunner " +
                    "processingBackend=MultiFrameRunner/VulkanRawMultiFrameBackend frames=${frames.size} " +
                    "theoreticalSnrGain=${sqrt(frames.size.toDouble())}"
            )

            // MultiFrameRunner either accepts async ownership or closes its leases on a returned
            // rejection. Mark this context transferred only after execute() returns; if setup throws
            // before that boundary, this runner still owns and closes the exact deliberate frames.
            val submissionResult = MultiFrameRunner(context, cameraManager).execute(
                plan = plan,
                recipe = recipe,
                ringBuffer = ringBuffer,
                shutterTimestampNs = shutterTimestampNs,
                shutterTimestampDomain = shutterTimestampDomain,
                activeProfile = activeProfile,
                activeLens = activeLens,
                shotLogger = shotLogger,
                deviceRotation = deviceRotation,
                activeZslFormat = activeZslFormat,
                meteringStyle = meteringStyle,
                evOffset = evOffset,
                currentSubmittedControlRequestEpochAtShutter = currentSubmittedControlRequestEpochAtShutter,
                aeStateBeforeCapture = aeStateBeforeCapture,
                meteringPolicySummary = meteringPolicySummary,
                exposurePolicySummary = exposurePolicySummary,
                postShutterStillCaptureUsed = true,
                stableAutoWhiteBalance = stableAutoWhiteBalance,
                hdrEnhancedCaptureContext = captureContext,
                captureStageListener = captureStageListener,
                onRawProcessingFeedback = onRawProcessingFeedback,
                temporaryPreviewPath = temporaryPreviewPath,
                focusCaptureContext = focusCaptureContext,
                portraitCaptureContext = portraitCaptureContext
            )
            ownershipTransferred = true
            return@withContext submissionResult
        } finally {
            if (!ownershipTransferred) captureContext.close()
        }
    }
}
