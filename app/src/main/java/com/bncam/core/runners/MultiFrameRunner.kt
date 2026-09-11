package com.bncam.core.runners

import android.content.ContentValues
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureResult
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import android.view.Surface
import com.bncam.core.buffer.FrameRingBuffer
import com.bncam.core.buffer.ZslFramePair
import com.bncam.core.debug.DiagnosticPayload
import com.bncam.core.debug.CapturePerformanceTracker
import com.bncam.core.debug.FrameAnalysisDebugEntry
import com.bncam.core.debug.ShotLogger
import com.bncam.core.capture.CaptureStageListener
import com.bncam.core.capture.HdrBracketCaptureContext
import com.bncam.core.capture.HdrEnhancedCaptureContext
import com.bncam.core.capture.CaptureRequestPlan
import com.bncam.core.capture.CaptureMode
import com.bncam.core.capture.OutputPolicy
import com.bncam.core.capture.OutputRotationResolver
import com.bncam.core.capture.SelectedFrameProvenanceValidator
import com.bncam.core.capture.MeteringExactTruth
import com.bncam.core.capture.WarmBufferReadinessPolicy
import com.bncam.core.engine.ImageUtils
import com.bncam.core.engine.LensInfo
import com.bncam.core.engine.WatermarkConfig
import com.bncam.core.engine.WatermarkEngine
import com.bncam.core.isp.raw10.DngWriter
import com.bncam.core.isp.raw.DemosaicAfHints
import com.bncam.core.isp.raw.MasterRawFrame
import com.bncam.core.isp.raw.RawMasterBuilder
import com.bncam.core.quality.LibpatcherProfileResolver
import com.bncam.core.quality.NoiseModelPublicationState
import com.bncam.core.quality.NoiseModelTrace
import com.bncam.core.quality.NoiseModelTraceFrame
import com.bncam.core.quality.RenderQualityConfig
import com.bncam.core.output.CaptureSaveQueue
import com.bncam.data.profile.CameraProfile
import com.bncam.data.settings.SettingsRepository
import com.bncam.core.utils.LocationUtils
import com.bncam.vendor.VendorInjectionEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

data class MultiRawProcessingFeedback(
    val nativeStats: String,
    val exposureTimeNs: Long,
    val sensitivityIso: Int,
    val succeeded: Boolean
)

class MultiFrameRunner(
    private val context: Context,
    private val cameraManager: CameraManager
) {
    private val tag = "MultiFrameRunner"

    private fun formatLabel(format: Int): String = when (format) {
        ImageFormat.RAW_SENSOR -> "RAW_SENSOR"
        ImageFormat.RAW10 -> "RAW10"
        ImageFormat.YUV_420_888 -> "YUV_420_888"
        else -> "UNKNOWN($format)"
    }

    private fun maxRequestedFramesFor(format: Int): Int = when (format) {
        ImageFormat.RAW_SENSOR,
        ImageFormat.RAW10,
        ImageFormat.YUV_420_888 ->
            com.bncam.core.capture.FrameCapacityPolicy.maximumProcessingFrames(
                com.bncam.core.capture.FrameCapacityPolicy.frameOrigin(format)
            )
        else -> throw IllegalArgumentException("Unsupported multi-frame format $format")
    }

    suspend fun execute(
        plan: CaptureRequestPlan,
        recipe: com.bncam.core.capture.CaptureRecipe,
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
        stableAutoWhiteBalance: com.bncam.core.quality.StableWhiteBalanceSnapshot? = null,
        hdrBracket: HdrBracketCaptureContext? = null,
        hdrEnhancedCaptureContext: HdrEnhancedCaptureContext? = null,
        preleasedShutterAnchor: FrameRingBuffer.LeasedCandidate? = null,
        captureStageListener: CaptureStageListener = CaptureStageListener.NONE,
        onRawProcessingFeedback: (MultiRawProcessingFeedback) -> Unit = {},
        temporaryPreviewPath: String? = null,
        focusCaptureContext: com.bncam.core.engine.FocusCaptureContext = com.bncam.core.engine.FocusCaptureContext(),
        portraitCaptureContext: com.bncam.core.capture.PortraitCaptureContext = com.bncam.core.capture.PortraitCaptureContext(),
        nightCapturePlan: com.bncam.core.capture.NightCapturePlan? = null
    ): com.bncam.core.output.CaptureSubmissionResult = withContext(Dispatchers.IO) {

        val performanceTracker = CapturePerformanceTracker("MULTI_FRAME_${formatLabel(activeZslFormat)}")
        performanceTracker.setMetric("thermalStatusAtShutter", recipe.thermalState)
        performanceTracker.sampleSystemState(context, "processing_start")
        performanceTracker.setMetric("portraitRequested", portraitCaptureContext.requested)
        performanceTracker.setMetric("portraitMaskAvailableAtShutter", portraitCaptureContext.available)
        performanceTracker.setMetric("portraitMaskStatus", portraitCaptureContext.status)
        val isNightMode = nightCapturePlan != null
        performanceTracker.setMetric("nightRequested", isNightMode)
        performanceTracker.setMetric("nightPlanActive", nightCapturePlan != null)
        if (nightCapturePlan != null) {
            performanceTracker.setMetric("nightSceneClass", nightCapturePlan.sceneClass.name)
            performanceTracker.setMetric("nightFrameBudget", nightCapturePlan.requestedFrames)
            performanceTracker.setMetric("nightAdaptiveMinimum", nightCapturePlan.adaptiveMinimumFrames)
            performanceTracker.setMetric("nightReason", nightCapturePlan.reason)
        }
        val hdrEnhancedActiveAtDispatch = hdrEnhancedCaptureContext != null
        performanceTracker.setMetric("actualRunner", if (hdrEnhancedActiveAtDispatch) "HdrEnhancedRunner" else "MultiFrameRunner")
        performanceTracker.setMetric("captureAuthorityRunner", if (hdrEnhancedActiveAtDispatch) "HdrEnhancedRunner" else "MultiFrameRunner")
        performanceTracker.setMetric("processingBackend", if (hdrEnhancedActiveAtDispatch) "MultiFrameRunner/VulkanRawMultiFrameBackend" else "MultiFrameRunner")
        performanceTracker.setMetric("deliberateBurst", hdrEnhancedActiveAtDispatch)
        performanceTracker.setMetric("computationalHdrResolutionReason", recipe.computationalHdrResolutionReason)
        val captureDispatchLatencyMs =
            ((android.os.SystemClock.elapsedRealtimeNanos() - shutterTimestampNs).coerceAtLeast(0L) / 1_000_000.0)
        val totalStartTimeMs = System.currentTimeMillis()
        val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
        val startedAtStr = isoFormat.format(Date(totalStartTimeMs))

        val settingsRepo = SettingsRepository(context)
        val nearZslFocusSelectionEnabled = true
        val capturedSettings = recipe.executionSettings
        val multiFeatureName = when {
            hdrEnhancedActiveAtDispatch -> "HDREnhanced"
            isNightMode -> "Night"
            portraitCaptureContext.requested -> "Portrait"
            recipe.computationalHdrRequested -> "CompHDR"
            else -> "Photo"
        }
        require(plan.captureMode == CaptureMode.MULTI) {
            "MultiFrameRunner received non-multi route ${plan.route.id}."
        }
        val enableShotLogger = capturedSettings.debug.shotLoggingEnabled
        val saveLocationData = capturedSettings.debug.saveLocationData
        val logSummary = capturedSettings.debug.logSummary
        val logActiveMode = capturedSettings.debug.logActiveMode
        val logProfileSettings = capturedSettings.debug.logProfileSettings
        val logFrameAnalysis = capturedSettings.debug.logFrameAnalysis
        val logWarnings = capturedSettings.debug.logWarnings
        val logPipelineDebug = capturedSettings.debug.logPipelineDebug
        val logVendorInjection = capturedSettings.debug.logVendorInjection
        val watermarkEnabled = capturedSettings.output.watermarkEnabled
        val watermarkStyle = capturedSettings.output.watermarkStyle
        val watermarkSignature = capturedSettings.output.watermarkSignature
        val watermarkAuthor = capturedSettings.output.watermarkAuthor
        val exifSaveSignature = capturedSettings.output.exifSaveSignature
        val exifExtraData = capturedSettings.output.exifExtraData

        val attemptId = "${activeLens.id}-$shutterTimestampNs"
        var shotId = "Unknown_Shot_ID"
        if (enableShotLogger) {
            shotLogger.startNewShot(
                sensorName = activeLens.name.substringBefore(" "),
                modeName = if (hdrEnhancedActiveAtDispatch) "HdrEnhanced" else "MultiFrame",
                featureName = multiFeatureName,
                logSummary = logSummary,
                logCapture = logActiveMode,
                logProfileSettings = logProfileSettings,
                logIsp = logPipelineDebug,
                logWarnings = logWarnings,
                logFrameAnalysis = logFrameAnalysis,
                logVendorInjection = logVendorInjection
            )
            shotId = shotLogger.getShotDir()?.name ?: "Unknown_Shot_ID"
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
            shotLogger.recordPipelineEvent("Camera2 Control Policy", "meteringPlan", meteringPolicySummary)
            shotLogger.recordPipelineEvent("Camera2 Control Policy", "exposurePlan", exposurePolicySummary)
        }
        val captureTrace =
            com.bncam.core.tracing.CaptureTraceRecorder(recipe = recipe, captureId = shotId)

        fun finalizeRejectedBeforeAsyncOwnership(reason: String, stage: String) {
            if (!enableShotLogger) return
            shotLogger.recordWarning(
                "Capture Admission",
                "Multi-frame shutter rejected before async processing ownership: $reason",
                "ERROR"
            )
            shotLogger.finalizeAttemptOnce(
                attemptId = attemptId,
                terminalState = com.bncam.core.debug.CaptureStatusState.FAILED,
                stage = stage,
                exception = IllegalStateException(reason)
            )
        }

        val chars = cameraManager.getCameraCharacteristics(activeLens.id)
        val finalRotation = getJpegOrientation(chars, deviceRotation)
        val exifOrientation = getExifOrientation(finalRotation)

        val profileId = activeProfile.id

        // Capture-request/profile settings: these are also mirrored into Active mode.txt.
        val preferredFrameSetting = recipe.frameSource.name
        val jpegFusionFrameCount = hdrEnhancedCaptureContext?.mainFrames?.size
            ?: recipe.processingFrameResolution.effectiveValue
        val selection = capturedSettings.selection
        val basePosition = selection.basePosition
        val baseCandidates = selection.baseCandidateCount
        val baseInclude = selection.baseIncludeInMerge
        val baseBias = selection.baseBias
        val baseTempBias = selection.baseTemporalBias
        val selectionReqFrames = selection.selectionRequestedFrames
        val frameBias = selection.frameBias
        val acceptAll = selection.acceptAll
        val rejectDupes = selection.rejectDuplicates
        val useAlignableOnly = selection.useAlignableOnly
        val discardFirst = selection.discardFirst
        val preferRecent = selection.preferRecent
        val ignoreStale = selection.ignoreStale

        val requestedMergeFramesRaw = nightCapturePlan?.requestedFrames ?: recipe.requestedFrameCount
        val maxRequestedFrames = maxRequestedFramesFor(activeZslFormat)
        val requestFrames = nightCapturePlan?.requestedFrames?.coerceAtMost(maxRequestedFrames) ?: recipe.effectiveFrameCount
        val warmBufferRequirement = WarmBufferReadinessPolicy.captureRoute(
            format = activeZslFormat,
            captureMode = CaptureMode.MULTI,
            requestedFrameCount = requestedMergeFramesRaw,
            bufferCapacity = ringBuffer.currentCapacity()
        )
        val activeBufferFormatLabel = formatLabel(activeZslFormat)
        val lensHardwareSettings = capturedSettings.lensHardwareSettings
        val nativeLensHardwarePushed = ImageUtils.updateHardwareConfigNative(lensHardwareSettings)
        val useSubPixel = capturedSettings.merge.subPixel
        val useLinearInterp = capturedSettings.merge.linearInterpolation
        val strictness = capturedSettings.merge.strictness
        val maxShift = capturedSettings.merge.maximumShiftPixels

        if (enableShotLogger) {
            shotLogger.recordPipelineEvent(
                "Frame Selection Settings",
                "legacyUnappliedSettings",
                "base_bias=$baseBias;base_temporal_bias=$baseTempBias;" +
                        "selection_frame_bias=$frameBias;" +
                        "selection_accept_all=$acceptAll;" +
                        "selection_alignable_only=$useAlignableOnly;" +
                        "selection_prefer_recent=$preferRecent;" +
                        "merge_subpixel=$useSubPixel;" +
                        "merge_linear_interp=$useLinearInterp;" +
                        "status=UNAVAILABLE_PRESERVED_NOT_EXECUTED;" +
                        "authoritativeFrameCount=profile_multiframe_fusion_frames"
            )
            shotLogger.recordPipelineEvent("Capture Request", "Profile ID", profileId)
            shotLogger.recordPipelineEvent("Capture Request", "Requested Mode", activeProfile.captureStrategy.name)
            shotLogger.recordPipelineEvent("Capture Request", "Preferred Frame", preferredFrameSetting)
            shotLogger.recordPipelineEvent("Capture Request", "Selection Requested Frames", selectionReqFrames.toString())
            shotLogger.recordPipelineEvent("Merge Config", "Requested Merge Frames Raw", requestedMergeFramesRaw.toString())
            shotLogger.recordPipelineEvent("Merge Config", "Requested Merge Frames Effective", requestFrames.toString())
            shotLogger.recordPipelineEvent(
                "DNG Master Config",
                "Requested DNG Master Frames",
                recipe.dngMasterFrameResolution.requestedValue.toString()
            )
            shotLogger.recordPipelineEvent(
                "DNG Master Config",
                "Effective DNG Master Frames",
                recipe.dngMasterFrameResolution.effectiveValue.toString()
            )
            shotLogger.recordPipelineEvent(
                "DNG Master Config",
                "Planned DNG Source",
                recipe.dngSource.name
            )
            shotLogger.recordPipelineEvent("Merge Config", "Warm Buffer Required Complete Frames", warmBufferRequirement.requiredCompleteFrames.toString())
            shotLogger.recordPipelineEvent("Merge Config", "Max Requested Frames For Format", maxRequestedFrames.toString())
            shotLogger.recordPipelineEvent("Merge Config", "Active Buffer Format", activeBufferFormatLabel)
            lensHardwareSettings.debugPairs(activeBufferFormatLabel).forEach { (key, value) ->
                val resolvedValue = if (key == "Native Hardware Config Pushed") nativeLensHardwarePushed.toString() else value
                shotLogger.recordPipelineEvent("Lens Hardware Settings", key, resolvedValue)
            }
            if (!nativeLensHardwarePushed) {
                shotLogger.recordWarning("Lens Hardware Settings", "Native config push failed or native engine unavailable. Kotlin-render metadata/debug remains available, but native C++ may use defaults.", "WARN")
            }
            lensHardwareSettings.warnings.forEach { warning ->
                shotLogger.recordWarning("Lens Hardware Settings", warning, "WARN")
            }
            if (requestedMergeFramesRaw != requestFrames) {
                shotLogger.recordWarning(
                    "Merge Config",
                    "profile fusion frame count=$requestedMergeFramesRaw clamped to $requestFrames for $activeBufferFormatLabel",
                    "INFO"
                )
            }
            shotLogger.recordPipelineEvent(
                "Unavailable Settings",
                "Sub-Pixel / Linear Interpolation",
                "preserved values: subPixel=$useSubPixel linearInterpolation=$useLinearInterp; executed=not_executed"
            )
            shotLogger.recordPipelineEvent("Merge Config", "Strictness", strictness.toString())
            shotLogger.recordPipelineEvent("Merge Config", "Max Shift", maxShift.toString())
        }

        val profileName = capturedSettings.profileName
        val phoneAssistanceSensorsEnabled = recipe.phoneAssistanceSensorsEnabled
        val phoneAssistanceHelper = com.bncam.core.sensors.ColorSensorHelper(context)
        val alignmentMethod = recipe.alignmentMethod.requestedId
        val fusionMethod = recipe.fusionMethod.requestedId
        val exposureStrategy = recipe.exposureStrategy
        val dngMasterFrameCount = recipe.dngMasterFrameResolution.effectiveValue
        val rawExecutionFrameCount =
            if (plan.outputPolicy == OutputPolicy.RAW_ONLY) {
                dngMasterFrameCount
            } else {
                jpegFusionFrameCount
            }

        val immutableConfig = com.bncam.core.capture.MultiFrameCaptureConfig(
            profileId = profileId,
            profileName = profileName,
            sourceFormat = activeZslFormat,
            shootingMode = CaptureMode.MULTI,
            alignmentMethod = alignmentMethod,
            fusionMethod = fusionMethod,
            fusionFrameCount = jpegFusionFrameCount,
            dngMasterFrameCount = dngMasterFrameCount,
            exposureStrategy = exposureStrategy,
            configuredBufferCapacity = ringBuffer.currentCapacity(),
            effectiveBufferCapacity = recipe.bufferCapacity,
            lensId = activeLens.id,
            demosaicMethod = recipe.demosaicMethod.resolvedId,
            outputPolicy = plan.outputPolicy,
            phoneAssistanceSensorsEnabled = phoneAssistanceSensorsEnabled
        )
        // ==========================================
        val imageReaderPressureAtShutter = ringBuffer.imageReaderPressureDiagnostics()
        val maxImagesVal = imageReaderPressureAtShutter.imageReaderMaxImages
        val configuredBufferCapacityVal = ringBuffer.currentCapacity()
        val ringBufferCapacityVal = ringBuffer.currentCapacity()
        val framesReceivedVal = imageReaderPressureAtShutter.cumulativeImagesAcquired
        val framesRejectedStaleVal = ringBuffer.rejectedStale
        val framesDroppedByRingBufferVal = ringBuffer.framesDroppedByRingBuffer
        val imageReaderAcquireFailureCountVal = imageReaderPressureAtShutter.imageReaderAcquireFailureCount
        val bufferBackpressureDetectedVal = imageReaderPressureAtShutter.backpressureDetected

        // 1. HAAL DE BURST UIT DE BUFFER
        // ==========================================
        val framesInBuffer = ringBuffer.completeFrameCount()
        val hdrRequestedForShot = recipe.computationalHdrRouteEnabled
        val hdrEnhancedOrderedFrames = hdrEnhancedCaptureContext?.let { hdrContext ->
            val base = hdrContext.selectedBaseFrame
            hdrContext.mainFrames.filter { it !== base } + base
        }
        val hdrEnhancedActive = hdrEnhancedOrderedFrames != null
        val legacyBracketActive = !hdrEnhancedActive && hdrRequestedForShot && hdrBracket != null
        val computationalHdrActive = hdrEnhancedActive || legacyBracketActive
        val hdrFallbackAnchorOnly = hdrRequestedForShot && !hdrEnhancedActive && hdrBracket == null
        val normalSelectionCount = if (hdrFallbackAnchorOnly) 1 else requestFrames
        // Pin the shutter-time anchor as early as BnCameraManager can resolve the effective route.
        // This lease survives recipe/readiness work, so a producer overwrite cannot evict the exact
        // frame that existed when the user pressed the shutter. If the pipeline generation/format
        // changed before the runner started, discard that lease and fall back to atomic selection.
        val validPreleasedAnchor = preleasedShutterAnchor?.takeIf { candidate ->
            candidate.frame.generationId == ringBuffer.currentGeneration() &&
                candidate.frame.format == activeZslFormat &&
                candidate.frame.hardwareBuffer != null &&
                candidate.frame.metadata != null &&
                com.bncam.core.buffer.NearZslEligibilityPolicy.isFullyPreShutter(
                    candidate.frame,
                    shutterTimestampNs,
                    shutterTimestampDomain
                )
        }
        if (preleasedShutterAnchor != null && validPreleasedAnchor == null) {
            preleasedShutterAnchor.lease.release()
        }

        // Normal Near-ZSL support selection and lease acquisition must be one ring-buffer
        // transaction. The shutter anchor, when available, is already leased and explicitly
        // excluded from support selection to avoid duplicate processing/lease ownership.
        val normalLeasedCandidates =
            if (hdrEnhancedOrderedFrames == null && hdrBracket == null) {
                val supportBudget = (normalSelectionCount - if (validPreleasedAnchor != null) 1 else 0)
                    .coerceAtLeast(0)
                if (supportBudget > 0) {
                    ringBuffer.queryAndLeaseCandidates(
                        userShutterTimestampNs = shutterTimestampNs,
                        maxCount = supportBudget,
                        shutterTimestampDomain = shutterTimestampDomain,
                        excludeFrameVersions = validPreleasedAnchor
                            ?.let { setOf(it.frameVersion) }
                            ?: emptySet(),
                        expectedFormat = activeZslFormat
                    )
                } else {
                    emptyList()
                }
            } else {
                emptyList()
            }
        val normalOwnedCandidates = if (validPreleasedAnchor != null) {
            normalLeasedCandidates + validPreleasedAnchor
        } else {
            normalLeasedCandidates
        }
        var burstFrames = hdrEnhancedOrderedFrames?.map { it.lease.pair }
            ?: hdrBracket?.processingFrames?.map { it.lease.pair }
            ?: normalOwnedCandidates.map { it.frame }
        val burstLeases = hdrEnhancedOrderedFrames?.map { it.lease }
            ?: hdrBracket?.processingFrames?.map { it.lease }
            ?: normalOwnedCandidates.map { it.lease }
        val hdrExposureScales = when {
            hdrEnhancedActive -> FloatArray(burstFrames.size) { 1f }
            hdrBracket != null -> hdrBracket.exposureScalesToAnchor
            else -> FloatArray(burstFrames.size) { 1f }
        }
        if (enableShotLogger && hdrRequestedForShot) {
            if (hdrFallbackAnchorOnly) {
                shotLogger.recordWarning(
                    "HDR",
                    "Computational HDR reached MultiFrameRunner without a deliberate HDR Enhanced burst; processing is restricted to anchor-only fallback.",
                    "WARN"
                )
            }
            shotLogger.recordPipelineEvent("HDR", "routeEnabled", recipe.computationalHdrRouteEnabled.toString())
            shotLogger.recordPipelineEvent("HDR", "hdrEnhancedDeliberateBurst", hdrEnhancedActive.toString())
            shotLogger.recordPipelineEvent("HDR", "legacyBracketAcquired", legacyBracketActive.toString())
            shotLogger.recordPipelineEvent("HDR", "fallbackAnchorOnly", hdrFallbackAnchorOnly.toString())
            shotLogger.recordPipelineEvent(
                "HDR",
                "exposureScalesToAnchor",
                hdrExposureScales.joinToString(prefix = "[", postfix = "]")
            )
        }

        performanceTracker.setMetric("captureMode", "MULTI")
        performanceTracker.setMetric("bufferFormat", activeBufferFormatLabel)
        performanceTracker.setMetric("outputPolicy", plan.outputPolicy.name)
        performanceTracker.setMetric("requestedFrameCount", requestFrames)
        performanceTracker.setMetric("availableCompleteFrameCount", framesInBuffer)
        performanceTracker.setMetric("selectedFrameCount", burstFrames.size)
        performanceTracker.setMetric(
            "normalSelectionAtomicLease",
            hdrEnhancedOrderedFrames == null && hdrBracket == null
        )
        performanceTracker.setMetric("shutterAnchorPreleased", validPreleasedAnchor != null)
        performanceTracker.setMetric(
            "shutterAnchorPreleasedFrameVersion",
            validPreleasedAnchor?.frameVersion ?: 0L
        )
        performanceTracker.setMetric("leasedFrameCount", burstLeases.size)
        val normalNearZslDegradedFrameCount =
            hdrEnhancedOrderedFrames == null && hdrBracket == null &&
                burstFrames.isNotEmpty() && burstFrames.size < normalSelectionCount
        performanceTracker.setMetric("normalNearZslDegradedFrameCount", normalNearZslDegradedFrameCount)
        performanceTracker.setMetric("normalNearZslRequestedFrames", normalSelectionCount)
        performanceTracker.setMetric("normalNearZslEligiblePreShutterFrames", burstFrames.size)
        if (enableShotLogger && normalNearZslDegradedFrameCount) {
            shotLogger.recordWarning(
                "Frame Acquisition",
                "Near-ZSL had ${burstFrames.size} eligible pre-shutter frame(s) for a $normalSelectionCount-frame quality target; capture proceeds with the exact available set instead of dropping the shutter press.",
                "INFO"
            )
        }
        performanceTracker.setMetric("computationalHdrRequested", hdrRequestedForShot)
        performanceTracker.setMetric("computationalHdrActive", computationalHdrActive)
        performanceTracker.setMetric("hdrEnhancedDeliberateBurst", hdrEnhancedActive)
        performanceTracker.setMetric("mainFramesRequested", hdrEnhancedCaptureContext?.plan?.mainFrameCount ?: requestFrames)
        performanceTracker.setMetric("mainFramesAcquired", hdrEnhancedCaptureContext?.mainFrames?.size ?: burstFrames.size)
        performanceTracker.setMetric("mainFramesProvenanceValid", hdrEnhancedCaptureContext?.mainFrames?.count { it.provenanceValid } ?: 0)
        performanceTracker.setMetric("bestBaseSelected", hdrEnhancedCaptureContext?.selectedBaseIndex?.let { it >= 0 } ?: false)
        performanceTracker.setMetric("selectedBaseFrameIndex", hdrEnhancedCaptureContext?.selectedBaseIndex ?: "NOT_APPLICABLE")
        performanceTracker.setMetric("hdrEnhancedBaseSelectionReason", hdrEnhancedCaptureContext?.baseSelectionReason ?: "NOT_APPLICABLE")
        performanceTracker.setMetric(
            "hdrEnhancedBaseSelectionMeasuredEvidence",
            hdrEnhancedCaptureContext?.baseSelectionMeasuredEvidenceAvailable ?: false
        )
        performanceTracker.setMetric("computationalHdrAnchorFallback", hdrFallbackAnchorOnly)
        performanceTracker.setMetric("phoneAssistanceSensorsEnabled", phoneAssistanceSensorsEnabled)
        captureTrace.record(
            com.bncam.core.tracing.CaptureTraceSection.BUFFER_LIFECYCLE,
            "configuredCapacity",
            ringBuffer.currentCapacity()
        )
        captureTrace.record(
            com.bncam.core.tracing.CaptureTraceSection.BUFFER_LIFECYCLE,
            "completeFramesAtLease",
            framesInBuffer
        )
        captureTrace.record(
            com.bncam.core.tracing.CaptureTraceSection.FRAME_CANDIDATES,
            "leasedCandidateCount",
            burstFrames.size
        )

        fun clockSafeFrameAgeMs(frame: ZslFramePair?): Double? {
            if (frame == null) return null
            val sensorComparable = shutterTimestampDomain == "SENSOR_TIMESTAMP" ||
                    (shutterTimestampDomain.startsWith("ELAPSED_REALTIME") &&
                            frame.sensorTimestampComparableToElapsedRealtime)
            return when {
                sensorComparable && frame.timestamp > 0L ->
                    (shutterTimestampNs - frame.timestamp) / 1_000_000.0
                shutterTimestampDomain.startsWith("ELAPSED_REALTIME") -> {
                    val completionElapsedNs = frame.pairCompleteElapsedNs.takeIf { it > 0L }
                        ?: maxOf(frame.imageArrivalElapsedNs, frame.metadataArrivalElapsedNs)
                    completionElapsedNs.takeIf { it > 0L }
                        ?.let { (shutterTimestampNs - it) / 1_000_000.0 }
                }
                else -> null
            }
        }

        val oldestFrame = burstFrames.minByOrNull { it.timestamp }
        val oldestFrameAgeMs = clockSafeFrameAgeMs(oldestFrame)

        val newestFrame = burstFrames.maxByOrNull { it.timestamp }
        val newestFrameAgeMs = clockSafeFrameAgeMs(newestFrame)

        var burstFramesClosed = false
        var burstHardwareReleased = false

        fun releaseBurstHardwareBuffers() {}
        fun closeBurstFrames() { burstLeases.forEach { it.release() } }

        if (burstFrames.isEmpty()) {
            Log.e(tag, "Buffer leeg, geen Multi-Frame mogelijk.")
            val vendorAttempts = VendorInjectionEngine.consumeAttempts(activeLens.id)
            if (enableShotLogger) {
                shotLogger.recordWarning("Capture Warnings", "Buffer is empty. No photo can be generated.", "ERROR")
                if (logVendorInjection) shotLogger.writeVendorInjectionDebug(activeLens.id, vendorAttempts, null)
            }
            finalizeRejectedBeforeAsyncOwnership("ring_buffer_empty", "MULTI_FRAME_RING_BUFFER_EMPTY")
            return@withContext com.bncam.core.output.CaptureSubmissionResult.Rejected("ring_buffer_empty")
        }
        val frameAcquireTimeMs = performanceTracker.mark("frame_acquire_time")

        if (!computationalHdrActive && focusCaptureContext.reliableTrackedSubject && nearZslFocusSelectionEnabled) {
            val trackedAnchorIndex = TrackedSubjectAnchorPolicy.selectIndex(
                candidates = burstFrames.mapIndexed { index, frame ->
                    TrackedSubjectAnchorEvidence(
                        index = index,
                        trackingOwned = frame.requestProvenance?.snapshot?.focusOwner?.startsWith("TRACK_") == true,
                        focusEvaluated = frame.focusEvaluated,
                        hasAfRegion = frame.afRegion != null,
                        focusConfidence = frame.focusConfidence.toDouble(),
                        ageMs = clockSafeFrameAgeMs(frame)?.coerceAtLeast(0.0) ?: Double.POSITIVE_INFINITY,
                        lensMoving = frame.lensState == CaptureResult.LENS_STATE_MOVING
                    )
                },
                enabled = true,
                freshnessWindowMs = 350.0
            )
            if (trackedAnchorIndex != null && trackedAnchorIndex != burstFrames.lastIndex) {
                val trackedAnchor = burstFrames[trackedAnchorIndex]
                burstFrames = burstFrames.filterIndexed { index, _ -> index != trackedAnchorIndex } + trackedAnchor
                if (enableShotLogger) {
                    shotLogger.recordPipelineEvent(
                        "Focus Track",
                        "multiFrameAnchorSelection",
                        "tracked_subject_roi_focus timestamp=${trackedAnchor.timestamp} confidence=${trackedAnchor.focusConfidence}"
                    )
                }
            }
        }

        val anchorPair = burstFrames.last()
        val anchorBuffer = anchorPair.hardwareBuffer
        val anchorMetadata = anchorPair.metadata
        val selectedDeltaMs = clockSafeFrameAgeMs(anchorPair)?.let { -it }
        val predictiveAfEstimate = ringBuffer.predictiveAfTracker
            ?.predictFocusDistance(anchorPair.timestamp)
        val demosaicAfHints = DemosaicAfHints(
            focusScore = anchorPair.focusScore,
            focusConfidence = anchorPair.focusConfidence,
            confidenceState = anchorPair.confidenceState,
            afState = anchorPair.afState,
            lensState = anchorPair.lensState,
            lensFocusDistance = anchorPair.lensFocusDistance,
            focusVelocityDioptersPerSec =
                predictiveAfEstimate?.focusVelocityDioptersPerSec ?: 0f,
            predictiveConfidence = predictiveAfEstimate?.confidence ?: 0f
        )

        if (anchorBuffer == null || anchorMetadata == null) {
            Log.e(tag, "Anchor frame is incomplete.")
            val vendorAttempts = VendorInjectionEngine.consumeAttempts(activeLens.id)
            if (enableShotLogger) {
                shotLogger.recordWarning("Capture Warnings", "Anchor frame is incomplete (null hardwareBuffer or metadata).", "ERROR")
                if (logVendorInjection) shotLogger.writeVendorInjectionDebug(activeLens.id, vendorAttempts, anchorMetadata)
            }
            closeBurstFrames()
            finalizeRejectedBeforeAsyncOwnership("anchor_frame_incomplete", "MULTI_FRAME_ANCHOR_INCOMPLETE")
            return@withContext com.bncam.core.output.CaptureSubmissionResult.Rejected("anchor_frame_incomplete")
        }
        val colorSensorReading = if (phoneAssistanceSensorsEnabled) {
            phoneAssistanceHelper.getBestReading(anchorMetadata)
        } else {
            com.bncam.core.model.ColorSensorReading()
        }
        val colorSensorContributionWeight =
            if (phoneAssistanceSensorsEnabled && colorSensorReading.isValid) 0.12f else 0.0f
        performanceTracker.setMetric("phoneAssistanceReadingValid", colorSensorReading.isValid)
        if (enableShotLogger) {
            shotLogger.recordPipelineEvent("Phone Assistance Sensors", "Enabled", phoneAssistanceSensorsEnabled.toString())
            shotLogger.recordPipelineEvent("Phone Assistance Sensors", "Android sensor", phoneAssistanceHelper.auxiliaryManager.selectedSensorName ?: "none")
            shotLogger.recordPipelineEvent("Phone Assistance Sensors", "Capability", phoneAssistanceHelper.auxiliaryManager.selectedCapability.name)
            shotLogger.recordPipelineEvent("Phone Assistance Sensors", "Listener active", phoneAssistanceHelper.auxiliaryManager.isListening.toString())
            shotLogger.recordPipelineEvent("Phone Assistance Sensors", "Reading source", colorSensorReading.sourceId)
            shotLogger.recordPipelineEvent("Phone Assistance Sensors", "Reading capability", colorSensorReading.capability)
            shotLogger.recordPipelineEvent("Phone Assistance Sensors", "Reading valid", colorSensorReading.isValid.toString())
            shotLogger.recordPipelineEvent("Phone Assistance Sensors", "CCT", if (colorSensorReading.isValid) "${colorSensorReading.cctKelvin} K" else "unavailable")
            shotLogger.recordPipelineEvent("Phone Assistance Sensors", "ISP contribution", colorSensorContributionWeight.toString())
        }
        val selectedFrameProvenanceProof = SelectedFrameProvenanceValidator.verify(
            framePipelineGeneration = anchorPair.generationId,
            frameControlRequestEpoch = anchorPair.controlRequestEpoch,
            frameTimestampNs = anchorPair.timestamp,
            metadataTimestampNs = anchorMetadata.get(CaptureResult.SENSOR_TIMESTAMP),
            provenance = anchorPair.requestProvenance
        )
        val selectedRequestSnapshot = selectedFrameProvenanceProof.snapshot
        captureStageListener.sourceImageAcquired()

        val reservation = com.bncam.core.output.CaptureProcessingQueue.tryReserve(
            route = plan.route.id,
            captureStartedNs = shutterTimestampNs,
            temporaryPreviewPath = temporaryPreviewPath
        ) ?: run {
            closeBurstFrames()
            performanceTracker.persistJsonLine(
                context = context,
                status = "FAILED",
                failureReason = "processing_queue_full"
            )
            finalizeRejectedBeforeAsyncOwnership("processing_queue_full", "MULTI_FRAME_QUEUE_FULL")
            return@withContext com.bncam.core.output.CaptureSubmissionResult.Rejected("processing_queue_full")
        }
        performanceTracker.setMetric("processingWorkId", reservation.workId)
        performanceTracker.setMetric("shotSequenceId", reservation.shotSequenceId)

        // The full native multi-frame pipeline belongs to the bounded processing worker. Older
        // revisions only deferred MediaStore publication and executed alignment/fusion/ISP in the
        // shutter coroutine, which monopolized the capture GPU path and froze the live viewfinder.
        val submitted = com.bncam.core.output.CaptureProcessingQueue.submit(context, reservation) { workReservation ->
        val width = anchorBuffer.width
        val height = anchorBuffer.height
        val rowStride = 0 // RowStride wordt nu onzichtbaar en efficiënt door C++ HardwareBuffer API afgehandeld

        val baseRenderQualityConfig = RenderQualityConfig.load(
            repo = settingsRepo,
            profileId = profileId,
            frameSourceFormat = activeZslFormat,
            captureMode = activeProfile.captureStrategy,
            characteristics = chars,
            captureResult = anchorMetadata,
            lensHardwareSettings = lensHardwareSettings,
            preferenceSnapshot = capturedSettings.renderPreferences,
            stableAutoWhiteBalance = stableAutoWhiteBalance
        )
        val selectedNoiseTraceFrames = burstFrames.mapNotNull { frame ->
            frame.metadata?.let { exactFrameMetadata ->
                val exactFrameTimestampNs = exactFrameMetadata
                    .get(CaptureResult.SENSOR_TIMESTAMP)
                    ?.takeIf { it > 0L }
                    ?: frame.timestamp
                val calibration = com.bncam.core.quality.SensorCalibrationResolver.resolve(
                    lensId = activeLens.id,
                    physicalCameraId = null,
                    frameSourceFormat = activeZslFormat,
                    characteristics = chars,
                    captureResult = exactFrameMetadata,
                    lensSettings = lensHardwareSettings,
                    profileAwbSettings = capturedSettings.renderPreferences.profileAwb
                )
                NoiseModelTraceFrame(
                    captureId = "${activeLens.id}-$shutterTimestampNs",
                    frameTimestampNs = exactFrameTimestampNs,
                    lensId = activeLens.id,
                    calibration = calibration,
                    captureResult = exactFrameMetadata
                )
            }
        }
        val selectedFrameCalibrations = selectedNoiseTraceFrames.map { it.calibration }
        val fusedCalibration = baseRenderQualityConfig.finalCalibration?.let { anchorCalibration ->
            com.bncam.core.quality.SensorCalibrationResolver.combineNoiseForFusion(
                anchor = anchorCalibration,
                selectedFrames = selectedFrameCalibrations
            )
        }
        val renderQualityConfig = if (fusedCalibration != null) {
            baseRenderQualityConfig.copy(
                finalCalibration = fusedCalibration,
                blackLevels = fusedCalibration.effectiveBlackLevels.toList()
            )
        } else baseRenderQualityConfig
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

        val finalCalibration = requireNotNull(renderQualityConfig.finalCalibration)

        if (enableShotLogger) {
            // Raw dump
            finalCalibration.debugPairs().forEach { (key, value) ->
                shotLogger.recordPipelineEvent("Sensor Calibration Detail", key, value)
            }

            // Strikt contract voor ShotLogger mapping (YUV vs RAW netjes gescheiden)
            shotLogger.recordPipelineEvent("Sensor Calibration", "Black Level Source", finalCalibration.effectiveBlackLevelSource)
            shotLogger.recordPipelineEvent("Sensor Calibration", "Black Level Applied", finalCalibration.blackSubtractionApplied.toString())
            shotLogger.recordPipelineEvent("Sensor Calibration", "Black Level Domain", finalCalibration.effectiveBlackLevelAppliedDomain)

            shotLogger.recordPipelineEvent("Sensor Calibration", "White Level Source", finalCalibration.effectiveWhiteLevelSource)
            shotLogger.recordPipelineEvent("Sensor Calibration", "White Level Applied", "true")
            shotLogger.recordPipelineEvent("Sensor Calibration", "White Level Domain", finalCalibration.effectiveWhiteLevelAppliedDomain)

            shotLogger.recordPipelineEvent("Sensor Calibration", "Color Matrix Source", finalCalibration.effectiveColorMatrixSource)
            shotLogger.recordPipelineEvent("Sensor Calibration", "Color Matrix Applied", finalCalibration.effectiveColorMatrixApplied.toString())

            shotLogger.recordPipelineEvent("Sensor Calibration", "WB Source", finalCalibration.effectiveWbSource)
            shotLogger.recordPipelineEvent("Sensor Calibration", "WB Applied", finalCalibration.effectiveWbApplied.toString())

            shotLogger.recordPipelineEvent("Sensor Calibration", "Noise Profile Source", finalCalibration.effectiveNoiseProfileSource)
            shotLogger.recordPipelineEvent("Sensor Calibration", "Noise Profile Present", (finalCalibration.effectiveNoiseProfile != null).toString())
            shotLogger.recordPipelineEvent("Sensor Calibration", "Noise Profile Applied", finalCalibration.effectiveNoiseProfileApplied.toString())

            capturedSettings.renderPreferences.resolvedIspSettings
                .debugPairs().forEach { (key, value) ->
                shotLogger.recordPipelineEvent("Resolved ISP Settings", key, value)
            }
        }

        if (enableShotLogger) {
            renderQualityConfig.debugPairs().forEach { (key, value) ->
                shotLogger.recordPipelineEvent("Quality Config", key, value)
            }
            renderQualityConfig.commonPostRender?.debugPairs()?.forEach { (key, value) ->
                shotLogger.recordPipelineEvent("Common Post-Render", key, value)
            }
            renderQualityConfig.outputEncode?.debugPairs()?.forEach { (key, value) ->
                shotLogger.recordPipelineEvent("Output Encode", key, value)
            }
            if (renderQualityConfig.isRawPipeline && !renderQualityConfig.cfaSupportedForRawJpeg) {
                shotLogger.recordWarning(
                    group = "RAW ISP",
                    message = "Unsupported/non-Bayer CFA ${renderQualityConfig.cfaPattern}/${renderQualityConfig.cfaName}; native renderer will use an explicit RGGB fallback instead of silently pretending the sensor is supported.",
                    severity = "WARN"
                )
            }
            if (renderQualityConfig.isRawPipeline && !renderQualityConfig.whiteBalanceGains.fromMetadata) {
                shotLogger.recordWarning(
                    group = "RAW ISP",
                    message = "CaptureResult.COLOR_CORRECTION_GAINS missing; native renderer will use bounded gray-world fallback for JPEG color only. DNG remains metadata/container based.",
                    severity = "INFO"
                )
            }
            if (renderQualityConfig.isRawPipeline && !renderQualityConfig.colorCorrectionMatrix.fromMetadata) {
                shotLogger.recordWarning(
                    group = "RAW ISP",
                    message = "RAW color matrix is not applied in this shot; native renderer uses controlled identity fallback. ${renderQualityConfig.colorCorrectionMatrix.note}",
                    severity = "WARN"
                )
            }
        }

        val frameDebugEntries = mutableListOf<FrameAnalysisDebugEntry>()
        if (enableShotLogger) {
            shotLogger.recordPipelineEvent("Frame Acquisition", "Frames In Ring Buffer", framesInBuffer.toString())
            shotLogger.recordPipelineEvent("Frame Acquisition", "Burst Frames Pulled", burstFrames.size.toString())
            shotLogger.recordPipelineEvent("Frame Acquisition", "Anchor Index", (burstFrames.size - 1).toString())
            shotLogger.recordPipelineEvent("Frame Acquisition", "Anchor Timestamp", (anchorMetadata.get(CaptureResult.SENSOR_TIMESTAMP) ?: 0L).toString())
        }

// ==========================================
        // 2. NATIVE C++ DELEGATION (HardwareBuffers)
        // ==========================================
        var finalJpegBytes: ByteArray? = null
        var ultraHdrGainmapArtifact: ImageUtils.UltraHdrGainmapArtifact? = null
        var masterRawFrame: MasterRawFrame? = null
        var jpegMasterFrameForCleanup: MasterRawFrame? = null
        var raw16ByteCountSnapshot = 0
        var raw16ManagedMaterializationCountSnapshot = 0
        var raw16ManagedMaterializationBytesSnapshot = 0L
        var raw16NativeOwnerReleasedBeforePublication = false
        var dngCreated = false
        var dngBytesSize = 0
        var dngUri: Uri? = null
        var jpegMergeStats = "not requested"
        var dngMergeStats = "not requested"
        var executedDngSource = com.bncam.core.capture.DngSource.NOT_APPLICABLE
        var masterIspStats = "not recorded"
        var yuvNativeStats = "not recorded"
        var yuvStatsMap: Map<String, String> = emptyMap()
        var yuvFramesUsed = 0
        var yuvAnchorOnly = activeZslFormat == ImageFormat.YUV_420_888
        val hqJpegCreated = false
        val hqJpegBytesSize = 0
        val hqUri: Uri? = null
        var mergeOutputCreated = false
        var mergeOutputBytes = 0
        var mergeOutputPath = "not created"
        var raw10NativeStats = "not recorded"
        var raw10StatsMap: Map<String, String> = emptyMap()
        var raw10FramesMerged = 0
        var raw10SupportRejected = 0
        var raw10AnchorOnly = activeZslFormat == ImageFormat.RAW10
        var rawSensorNativeStats = "not recorded"
        var rawSensorStatsMap: Map<String, String> = emptyMap()
        var rawSensorFramesMerged = 0
        var rawSensorSupportRejected = 0
        var rawSensorAnchorOnly = activeZslFormat == ImageFormat.RAW_SENSOR
        var renderTimeMs = 0L
        var masterBuildTimeMs = 0L
        var jpegRenderOnlyTimeMs = 0L
        var dngWriteTimeMs = 0L
        var failureReason = "none"
        var hdrEnhancedAlignmentCompletedActual = false
        var hdrEnhancedTemporalMergeCompletedActual = false
        var hdrEnhancedIspCompletedActual = false
        var hdrEnhancedJpegCompletedActual = false
        var hdrEnhancedGpuResidentIspEntryUsed = false
        var hdrEnhancedIspCpuFallbackUsed = false

        // Verzamelt alle HardwareBuffers uit de burst
        val hwBuffers = burstFrames.mapNotNull { it.hardwareBuffer }.toTypedArray()
        val pixels = width.toLong() * height.toLong()
        val sourceFrameBytes = when (activeZslFormat) {
            ImageFormat.RAW10 -> (pixels * 5L + 3L) / 4L
            ImageFormat.RAW_SENSOR -> pixels * 2L
            ImageFormat.YUV_420_888 -> (pixels * 3L + 1L) / 2L
            else -> pixels * 2L
        }
        val mergeInputBytes = hwBuffers.size.toLong() * sourceFrameBytes

        val renderStartMs = System.currentTimeMillis()
        val isRawEnabled = activeZslFormat == ImageFormat.RAW10 || activeZslFormat == ImageFormat.RAW_SENSOR
        val dngExportRequested = isRawEnabled && plan.outputPolicy.producesRaw

        captureStageListener.nativeProcessingStart()
        try {
            if (hwBuffers.isNotEmpty()) {
                Log.i(tag, "Start synchronous native processing for $activeBufferFormatLabel with ${hwBuffers.size} frame(s); publication is deferred to CaptureProcessingQueue.")

                coroutineScope {
                    if (activeZslFormat == ImageFormat.RAW10 || activeZslFormat == ImageFormat.RAW_SENSOR) {
                        // JPEG processing and DNG publication have independent frame-count
                        // contracts. When the counts differ, render and release the JPEG master
                        // first, then build the DNG master from the still-owned source buffers.
                        // This keeps only one authoritative native RAW16 owner live at a time.
                        val masterBuildStartedNs = android.os.SystemClock.elapsedRealtimeNanos()
                        val canShareMaster =
                            plan.outputPolicy.producesJpeg &&
                                dngExportRequested &&
                                jpegFusionFrameCount == dngMasterFrameCount &&
                                !hdrRequestedForShot

                        fun releaseJpegProcessingMaster() {
                            jpegMasterFrameForCleanup?.let { processingMaster ->
                                raw16ByteCountSnapshot = processingMaster.raw16ByteCount
                                raw16ManagedMaterializationCountSnapshot =
                                    processingMaster.managedMaterializationCount
                                raw16ManagedMaterializationBytesSnapshot =
                                    processingMaster.managedMaterializationBytes
                                check(processingMaster.managedMaterializationCount == 0) {
                                    "Phase 1B invariant failed: JPEG processing master materialized RAW16."
                                }
                                processingMaster.close()
                                raw16NativeOwnerReleasedBeforePublication =
                                    processingMaster.nativeRaw16Buffer.isClosed
                            }
                            jpegMasterFrameForCleanup = null
                        }

                        fun buildRawMaster(
                            frameCap: Int,
                            forDng: Boolean,
                            debugGroup: String,
                            enableComputationalHdr: Boolean = false
                        ): MasterRawFrame? {
                            performanceTracker.incrementCounter("rawMergeInvocationCount")
                            if (hdrEnhancedActive && enableComputationalHdr) {
                                Log.i(
                                    "HdrEnhancedRunner",
                                    "HDR_ENHANCED_ALIGNMENT_STARTED: processingBackend=VulkanRawMultiFrameBackend frames=${hwBuffers.size}"
                                )
                            }
                            val built = RawMasterBuilder.build(
                                lensId = activeLens.id,
                                buffers = hwBuffers,
                                sourceFormat = activeZslFormat,
                                width = width,
                                height = height,
                                characteristics = chars,
                                captureResult = anchorMetadata,
                                // DngMerger needs the pre-fusion per-frame S/O model. It
                                // derives actual observer/fusion scaling from aligned supports;
                                // passing the already 1/N-scaled display calibration here would
                                // double-count fusion variance.
                                qualityConfig = baseRenderQualityConfig,
                                dngExportRequested = forDng,
                                orientationDegrees = finalRotation,
                                maxFramesCap = frameCap,
                                maxShiftPixels = maxShift,
                                alignmentStrictness = strictness,
                                exposureScaleToAnchor = if (enableComputationalHdr) {
                                    hdrExposureScales
                                } else {
                                    FloatArray(hwBuffers.size) { 1f }
                                },
                                computationalHdr = enableComputationalHdr,
                                demosaicAfHints = demosaicAfHints,
                                phoneAssistanceSensorsEnabled = phoneAssistanceSensorsEnabled,
                                colorSensorReading = colorSensorReading,
                                colorSensorContributionWeight = colorSensorContributionWeight
                            )
                            if (enableShotLogger && built != null) {
                                built.debugPairs().forEach { (key, value) ->
                                    shotLogger.recordPipelineEvent(debugGroup, key, value)
                                }
                                built.warnings().forEach { warning ->
                                    shotLogger.recordWarning(debugGroup, warning, "WARN")
                                }
                            }
                            return built
                        }

                        if (plan.outputPolicy.producesJpeg) {
                            jpegMasterFrameForCleanup = buildRawMaster(
                                frameCap = jpegFusionFrameCount,
                                forDng = canShareMaster,
                                debugGroup = "JPEG Processing Master RAW",
                                enableComputationalHdr = computationalHdrActive
                            )
                            if (computationalHdrActive && jpegMasterFrameForCleanup == null) {
                                if (enableShotLogger) {
                                    shotLogger.recordWarning(
                                        "HDR",
                                        "Vulkan HDR RAW fusion failed; rendering the exact anchor only. No CPU HDR fallback was used.",
                                        "WARN"
                                    )
                                }
                                jpegMasterFrameForCleanup = buildRawMaster(
                                    frameCap = 1,
                                    forDng = false,
                                    debugGroup = "HDR Anchor Fallback RAW",
                                    enableComputationalHdr = false
                                )
                            }
                            jpegMergeStats =
                                jpegMasterFrameForCleanup?.dngMergeStats
                                    ?: "jpeg_master_build_failed"
                            val renderMaster = jpegMasterFrameForCleanup
                            val jpegResult =
                                if (renderMaster != null && rawJpegContractFailure == null) {
                                    val jpegRenderStartedNs =
                                        android.os.SystemClock.elapsedRealtimeNanos()
                                    performanceTracker.incrementCounter("rawIspInvocationCount")
                                    performanceTracker.incrementCounter("jpegEncodeInvocationCount")
                                    if (hdrEnhancedActive) {
                                        Log.i(
                                            "HdrEnhancedRunner",
                                            "HDR_ENHANCED_ISP_STARTED: route=RAW_MASTER_ISP requestedGpuFirst=true actualBackend=PENDING"
                                        )
                                    }
                                    ImageUtils.renderJpegFromMasterFrameWithUltraHdrSafe(
                                        masterFrame = renderMaster,
                                        qualityConfig = renderQualityConfig,
                                        rotationDegrees = finalRotation,
                                        portraitCaptureContext = portraitCaptureContext
                                    ).also {
                                        jpegRenderOnlyTimeMs =
                                            (android.os.SystemClock.elapsedRealtimeNanos() -
                                                jpegRenderStartedNs) / 1_000_000L
                                        performanceTracker.recordDuration(
                                            "raw_isp_and_jpeg_encode",
                                            jpegRenderOnlyTimeMs.toDouble()
                                        )
                                    }
                                } else {
                                    null
                                }
                            ultraHdrGainmapArtifact = jpegResult?.ultraHdrGainmap
                            masterIspStats = ImageUtils.lastMasterIspStats()
                            val renderedJpegBytes = jpegResult?.jpegBytes
                            if (hdrEnhancedActive) {
                                val hdrIspStats = parseNativeStats(masterIspStats)
                                hdrEnhancedGpuResidentIspEntryUsed =
                                    boolStat(hdrIspStats, "rawResidentEntryUsed", false)
                                hdrEnhancedIspCpuFallbackUsed =
                                    boolStat(hdrIspStats, "rawResidentCpuFallbackUsed", false)
                                val rawNormalizeBackend = hdrIspStats["rawNormalizeBackend"] ?: "UNAVAILABLE"
                                val vulkanDemosaicUsedForOutput =
                                    boolStat(hdrIspStats, "vulkanDemosaicUsedForOutput", false)
                                val vulkanAwbCcmSucceeded =
                                    boolStat(hdrIspStats, "vulkanAwbCcmExecutionSucceeded", false)
                                val vulkanToneUsedForOutput =
                                    boolStat(hdrIspStats, "vulkanToneUsedForOutput", false)
                                hdrEnhancedIspCompletedActual =
                                    renderedJpegBytes != null && renderedJpegBytes.isNotEmpty()
                                performanceTracker.setMetric(
                                    "hdrEnhancedIspCompleted",
                                    hdrEnhancedIspCompletedActual
                                )
                                performanceTracker.setMetric(
                                    "hdrEnhancedGpuResidentIspEntryUsed",
                                    hdrEnhancedGpuResidentIspEntryUsed
                                )
                                performanceTracker.setMetric(
                                    "hdrEnhancedIspCpuFallbackUsed",
                                    hdrEnhancedIspCpuFallbackUsed
                                )
                                performanceTracker.setMetric(
                                    "hdrEnhancedRawNormalizeBackend",
                                    rawNormalizeBackend
                                )
                                performanceTracker.setMetric(
                                    "hdrEnhancedVulkanDemosaicUsedForOutput",
                                    vulkanDemosaicUsedForOutput
                                )
                                performanceTracker.setMetric(
                                    "hdrEnhancedVulkanAwbCcmSucceeded",
                                    vulkanAwbCcmSucceeded
                                )
                                performanceTracker.setMetric(
                                    "hdrEnhancedVulkanToneUsedForOutput",
                                    vulkanToneUsedForOutput
                                )
                                if (hdrEnhancedIspCompletedActual) {
                                    Log.i(
                                        "HdrEnhancedRunner",
                                        "HDR_ENHANCED_ISP_COMPLETE: ispComplete=true " +
                                            "rawResidentEntryUsed=$hdrEnhancedGpuResidentIspEntryUsed " +
                                            "rawResidentCpuFallbackUsed=$hdrEnhancedIspCpuFallbackUsed " +
                                            "rawNormalizeBackend=$rawNormalizeBackend " +
                                            "vulkanDemosaicUsedForOutput=$vulkanDemosaicUsedForOutput " +
                                            "vulkanAwbCcmSucceeded=$vulkanAwbCcmSucceeded " +
                                            "vulkanToneUsedForOutput=$vulkanToneUsedForOutput"
                                    )
                                } else {
                                    Log.w(
                                        "HdrEnhancedRunner",
                                        "HDR_ENHANCED_ISP_INCOMPLETE: jpegBytesAvailable=false " +
                                            "rawResidentEntryUsed=$hdrEnhancedGpuResidentIspEntryUsed " +
                                            "rawResidentCpuFallbackUsed=$hdrEnhancedIspCpuFallbackUsed " +
                                            "rawNormalizeBackend=$rawNormalizeBackend"
                                    )
                                }
                            }
                            if (renderedJpegBytes != null && renderedJpegBytes.isNotEmpty()) {
                                finalJpegBytes = renderedJpegBytes
                                if (hdrEnhancedActive) {
                                    hdrEnhancedJpegCompletedActual = true
                                    performanceTracker.setMetric("hdrEnhancedJpegCompleted", true)
                                    Log.i(
                                        "HdrEnhancedRunner",
                                        "HDR_ENHANCED_JPEG_COMPLETE: jpegComplete=true bytes=${renderedJpegBytes.size}"
                                    )
                                }
                                mergeOutputCreated = true
                                mergeOutputBytes = renderedJpegBytes.size
                                mergeOutputPath = "public JPEG output"
                                failureReason = "none"
                            } else {
                                failureReason = rawJpegContractFailure
                                    ?: "Native RAW JPEG processing returned null/empty."
                            }
                        } else {
                            masterIspStats = "not_executed_raw_only"
                            performanceTracker.setMetric("rawOnlyJpegMasterBuilt", false)
                            performanceTracker.setMetric("rawOnlyRgbIspExecuted", false)
                            performanceTracker.setMetric("rawOnlyJpegEncoded", false)
                        }

                        if (dngExportRequested) {
                            if (canShareMaster) {
                                masterRawFrame = jpegMasterFrameForCleanup
                                jpegMasterFrameForCleanup = null
                            } else {
                                releaseJpegProcessingMaster()
                                masterRawFrame = buildRawMaster(
                                    frameCap = dngMasterFrameCount,
                                    forDng = true,
                                    debugGroup = "DNG Publication Master RAW"
                                )
                            }
                            dngMergeStats =
                                masterRawFrame?.dngMergeStats ?: "dng_master_build_failed"
                            val dngStats = parseNativeStats(dngMergeStats)
                            executedDngSource =
                                com.bncam.core.capture.DngSourceResolver.executed(
                                    rawMasterAvailable = masterRawFrame != null,
                                    masterFrameCount = masterRawFrame?.frameCount ?: 0,
                                    nativeAnchorOnly = boolStat(dngStats, "anchorOnly", true)
                                )
                        } else {
                            releaseJpegProcessingMaster()
                        }

                        masterBuildTimeMs =
                            (android.os.SystemClock.elapsedRealtimeNanos() -
                                masterBuildStartedNs) / 1_000_000L
                        performanceTracker.recordDuration(
                            "master_build",
                            masterBuildTimeMs.toDouble()
                        )
                        releaseBurstHardwareBuffers()

                        if (
                            dngExportRequested &&
                            (masterRawFrame == null || masterRawFrame?.raw16ByteCount == 0)
                        ) {
                            failureReason = "DNG master RAW16 creation failed."
                        }

                    } else {
                        // ==========================================
                        // YUV native pipeline with native output rotation and timed copy/render stages
                        // ==========================================
                        val jpegRenderStartedNs = android.os.SystemClock.elapsedRealtimeNanos()
                        performanceTracker.incrementCounter("yuvNativeInvocationCount")
                        performanceTracker.incrementCounter("jpegEncodeInvocationCount")
                        val jpegResult = ImageUtils.processNativeYuvWithUltraHdrSafe(
                            buffers = hwBuffers,
                            qualityConfig = renderQualityConfig,
                            rotationDegrees = finalRotation,
                            lensId = activeLens.id,
                            captureSensitivityIso = anchorMetadata.get(CaptureResult.SENSOR_SENSITIVITY) ?: 0,
                            exposureScaleToAnchor = hdrExposureScales,
                            computationalHdr = computationalHdrActive,
                            portraitCaptureContext = portraitCaptureContext
                        )
                        jpegRenderOnlyTimeMs = (android.os.SystemClock.elapsedRealtimeNanos() - jpegRenderStartedNs) / 1_000_000L
                        ultraHdrGainmapArtifact = jpegResult?.ultraHdrGainmap
                        yuvNativeStats = ImageUtils.lastYuvStats()
                        yuvStatsMap = parseNativeStats(yuvNativeStats)
                        yuvFramesUsed = intStat(yuvStatsMap, "yuvFramesUsed", 0)
                        yuvAnchorOnly = boolStat(yuvStatsMap, "yuvAnchorOnly", true)
                        val renderedJpegBytes = jpegResult?.jpegBytes
                        if (renderedJpegBytes != null && renderedJpegBytes.isNotEmpty()) {
                            Log.i(tag, "Native C++ YUV processing (native rotation) succeeded. JPEG received (${renderedJpegBytes.size} bytes).")
                            finalJpegBytes = renderedJpegBytes
                            mergeOutputCreated = true
                            mergeOutputBytes = renderedJpegBytes.size
                            mergeOutputPath = "public JPEG output"
                            failureReason = "none"
                        } else {
                            failureReason = "Native C++ YUV processing returned null/empty."
                            Log.e(tag, "Processing failed. $failureReason")
                        }
                    }

                    // Haal de stats op voor de debug bestanden. RAW10/RAW_SENSOR lopen nu via
                    // DNG/Master RAW16 -> centrale IspCore JPEG, dus de oude directe native stats
                    // zijn niet langer de waarheid voor deze route.
                    if (isRawEnabled) {
                        val executionMergeStats =
                            if (plan.outputPolicy.producesJpeg) {
                                jpegMergeStats
                            } else {
                                dngMergeStats
                            }
                        val masterIspStatsMap = parseNativeStats(masterIspStats)
                        if (
                            plan.outputPolicy.producesJpeg &&
                            (masterIspStatsMap["colorMatrixApplied"] == "false" ||
                                masterIspStats.contains("colorMatrixApplied=false"))
                        ) {
                            shotLogger.recordWarning("RAW ISP", "Color Matrix Applied=false during Master RAW JPEG render. This is a quality warning, not a clean success.", "WARN")
                        }
                        val executionStatsMap = parseNativeStats(executionMergeStats)
                        dngBytesSize = masterRawFrame?.raw16ByteCount ?: 0
                        raw16ByteCountSnapshot = dngBytesSize
                        performanceTracker.setMetric("raw16NativeOwner", "NativeRaw16Buffer")
                        performanceTracker.setMetric("raw16OwnershipContract", "NATIVE_DIRECT_BUFFER_V1")
                        performanceTracker.setMetric("raw16JavaRoundTripEliminated", true)
                        performanceTracker.setMetric("raw16Phase2ImportBoundary", "DIRECT_BYTE_BUFFER")
                        performanceTracker.setMetric(
                            "raw16NativeOutstandingBuffersAtAcquisition",
                            ImageUtils.nativeRaw16OutstandingBufferCountSafe()
                        )
                        if (activeZslFormat == ImageFormat.RAW_SENSOR) {
                            rawSensorNativeStats = executionMergeStats
                            rawSensorStatsMap = executionStatsMap
                            rawSensorFramesMerged = intStat(executionStatsMap, "framesMerged", 0)
                            rawSensorSupportRejected = intStat(executionStatsMap, "supportRejected", 0)
                            rawSensorAnchorOnly = boolStat(executionStatsMap, "anchorOnly", true)
                        } else if (activeZslFormat == ImageFormat.RAW10) {
                            raw10NativeStats = executionMergeStats
                            raw10StatsMap = executionStatsMap
                            raw10FramesMerged = intStat(executionStatsMap, "framesMerged", 0)
                            raw10SupportRejected = intStat(executionStatsMap, "supportRejected", 0)
                            raw10AnchorOnly = boolStat(executionStatsMap, "anchorOnly", true)
                        }
                        if (hdrEnhancedActive) {
                            val actualFramesMerged = intStat(executionStatsMap, "framesMerged", 0)
                            val supportRejected = intStat(executionStatsMap, "supportRejected", 0)
                            val supportRejectedCanonicalization = intStat(executionStatsMap, "supportRejectedCanonicalization", 0)
                            val supportRejectedAlignment = intStat(executionStatsMap, "supportRejectedAlignment", 0)
                            val supportRejectedForwardBackward = intStat(executionStatsMap, "supportRejectedForwardBackward", 0)
                            val supportRejectedSpectraConsensus = intStat(executionStatsMap, "supportRejectedSpectraConsensus", 0)
                            val supportRejectedZeroWeightedContribution = intStat(executionStatsMap, "supportRejectedZeroWeightedContribution", 0)
                            val supportRejectedOther = intStat(executionStatsMap, "supportRejectedOther", 0)
                            val mergeAttempted = boolStat(executionStatsMap, "mergeAttempted", false)
                            val mergeOutputCreatedActual = boolStat(executionStatsMap, "mergeOutputCreated", false)
                            val anchorOnlyActual = boolStat(executionStatsMap, "anchorOnly", true)
                            val alignmentBackend = executionStatsMap["alignmentBackend"] ?: "UNAVAILABLE"
                            val fusionBackend = executionStatsMap["fusionBackend"] ?: "UNAVAILABLE"
                            val expectedAlignmentBackendMatched =
                                alignmentBackend == "VULKAN_RAW_NCC_EXHAUSTIVE_FINE"
                            val expectedTemporalFusionBackendMatched =
                                fusionBackend == "VULKAN_RAW_HDR_SAME_EXPOSURE_TEMPORAL_CONFIDENCE_FUSION"
                            val alignmentCompleted = mergeAttempted &&
                                actualFramesMerged > 1 &&
                                !anchorOnlyActual &&
                                expectedAlignmentBackendMatched
                            val temporalMergeCompleted = mergeAttempted &&
                                mergeOutputCreatedActual &&
                                actualFramesMerged > 1 &&
                                !anchorOnlyActual &&
                                expectedTemporalFusionBackendMatched
                            performanceTracker.setMetric("mainFramesMerged", actualFramesMerged)
                            performanceTracker.setMetric("hdrEnhancedSupportRejectedTotal", supportRejected)
                            performanceTracker.setMetric("hdrEnhancedSupportRejectedCanonicalization", supportRejectedCanonicalization)
                            performanceTracker.setMetric("hdrEnhancedSupportRejectedAlignment", supportRejectedAlignment)
                            performanceTracker.setMetric("hdrEnhancedSupportRejectedForwardBackward", supportRejectedForwardBackward)
                            performanceTracker.setMetric("hdrEnhancedSupportRejectedSpectraConsensus", supportRejectedSpectraConsensus)
                            performanceTracker.setMetric("hdrEnhancedSupportRejectedZeroWeightedContribution", supportRejectedZeroWeightedContribution)
                            performanceTracker.setMetric("hdrEnhancedSupportRejectedOther", supportRejectedOther)
                            performanceTracker.setMetric("hdrEnhancedAlignmentBackend", alignmentBackend)
                            performanceTracker.setMetric("hdrEnhancedFusionBackend", fusionBackend)
                            performanceTracker.setMetric("hdrEnhancedExpectedAlignmentBackendMatched", expectedAlignmentBackendMatched)
                            performanceTracker.setMetric("hdrEnhancedExpectedFusionBackendMatched", expectedTemporalFusionBackendMatched)
                            hdrEnhancedAlignmentCompletedActual = alignmentCompleted
                            hdrEnhancedTemporalMergeCompletedActual = temporalMergeCompleted
                            performanceTracker.setMetric("hdrEnhancedAlignmentCompleted", alignmentCompleted)
                            performanceTracker.setMetric("hdrEnhancedTemporalMergeCompleted", temporalMergeCompleted)
                            performanceTracker.setMetric("hdrEnhancedAnchorOnly", anchorOnlyActual)
                            Log.i(
                                "HdrEnhancedRunner",
                                "HDR_ENHANCED_SUPPORT_REJECTIONS: total=$supportRejected canonicalization=$supportRejectedCanonicalization alignment=$supportRejectedAlignment forwardBackward=$supportRejectedForwardBackward spectraConsensus=$supportRejectedSpectraConsensus zeroContribution=$supportRejectedZeroWeightedContribution other=$supportRejectedOther"
                            )
                            Log.i(
                                "HdrEnhancedRunner",
                                "HDR_ENHANCED_ALIGNMENT_COMPLETE: completed=$alignmentCompleted accepted=$actualFramesMerged rejected=$supportRejected backend=$alignmentBackend anchorOnly=$anchorOnlyActual"
                            )
                            Log.i(
                                "HdrEnhancedRunner",
                                "HDR_ENHANCED_TEMPORAL_MERGE_COMPLETE: completed=$temporalMergeCompleted merged=$actualFramesMerged rejected=$supportRejected backend=$fusionBackend mergeOutputCreated=$mergeOutputCreatedActual"
                            )
                        }
                    }
                }
            } else {
                failureReason = "No HardwareBuffers available after burst extraction."
                Log.w(tag, failureReason)
            }

        } catch (t: Throwable) {
            failureReason = "${t.javaClass.simpleName}: ${t.message}"
            Log.e(tag, "Fout in Native C++ Render", t)
            if (enableShotLogger) shotLogger.recordWarning("Renderer Warnings", "Final JPEG processing failed: ${t.javaClass.simpleName}: ${t.message}", "ERROR")
        } finally {
            // Native processing is synchronous and its JPEG/RAW16 results own their memory.
            // Release transferred Image/HardwareBuffer handles for YUV as well as RAW.
            jpegMasterFrameForCleanup?.let { processingMaster ->
                check(processingMaster.managedMaterializationCount == 0) {
                    "Phase 1B invariant failed: abandoned JPEG processing master materialized RAW16."
                }
                processingMaster.close()
                raw16NativeOwnerReleasedBeforePublication =
                    processingMaster.nativeRaw16Buffer.isClosed
            }
            jpegMasterFrameForCleanup = null
            releaseBurstHardwareBuffers()
            burstLeases.forEach { it.release() }
            renderTimeMs = System.currentTimeMillis() - renderStartMs
            performanceTracker.mark("native_render")
            captureStageListener.nativeProcessingEnd(
                if (plan.outputPolicy == OutputPolicy.RAW_ONLY) (masterRawFrame?.raw16ByteCount ?: 0) > 0
                else finalJpegBytes?.isNotEmpty() == true
            )
        }

        if (isRawEnabled) {
            runCatching {
                onRawProcessingFeedback(
                    MultiRawProcessingFeedback(
                        nativeStats = masterIspStats,
                        exposureTimeNs =
                            anchorMetadata.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: 0L,
                        sensitivityIso =
                            anchorMetadata.get(CaptureResult.SENSOR_SENSITIVITY) ?: 0,
                        succeeded = if (plan.outputPolicy == OutputPolicy.RAW_ONLY) {
                            (masterRawFrame?.raw16ByteCount ?: 0) > 0
                        } else {
                            finalJpegBytes?.isNotEmpty() == true
                        }
                    )
                )
            }.onFailure { callbackFailure ->
                Log.w(
                    tag,
                    "RAW multi-frame processing feedback callback failed: ${callbackFailure.message}",
                    callbackFailure
                )
            }
        }

        // Phase 0 ownership boundary: native rendering is complete before publication starts.
        // Publication receives immutable snapshots and must never invoke the RAW ISP again.
        val finalJpegBytesForPublication = finalJpegBytes
        val ultraHdrGainmapForPublication = ultraHdrGainmapArtifact
        val masterRawFrameForDng = if (dngExportRequested) masterRawFrame else null
        if (!dngExportRequested) {
            masterRawFrame?.let { nativeMaster ->
                raw16ManagedMaterializationCountSnapshot = nativeMaster.managedMaterializationCount
                raw16ManagedMaterializationBytesSnapshot = nativeMaster.managedMaterializationBytes
                check(nativeMaster.managedMaterializationCount == 0) {
                    "Phase 1B invariant failed: JPEG-only multi-frame capture materialized RAW16 " +
                        "${nativeMaster.managedMaterializationCount} times."
                }
                nativeMaster.close()
                raw16NativeOwnerReleasedBeforePublication = nativeMaster.nativeRaw16Buffer.isClosed
            }
            masterRawFrame = null
        }

            try {
                val timeStampForFile = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
                val photoPrefix = capturedSettings.output.photoPrefix
                val saveLocation = capturedSettings.output.saveLocation
                val baseFilename = "${photoPrefix}${timeStampForFile}"

                var jpegSaved = false
                var dngSaved = false
                var jpegUri: Uri? = null
                var jpegSaveFailure: Throwable? = null
                var dngSaveFailure: Throwable? = null
                val burstFramesSizeSnapshot = burstFrames.size

                workReservation.markSaving()
        val queued = CaptureSaveQueue.enqueueAndAwait(
            context = context,
            route = plan.route.id,
            captureStartedNs = shutterTimestampNs,
            onFailure = {}
        ) {
            coroutineScope {
                val jpegJob = async(Dispatchers.IO) {
                    if (plan.outputPolicy.producesJpeg) {
                        try {
                            // The final JPEG was already produced by the native processing stage.
                            // Re-rendering the Master RAW here previously executed the full RAW ISP and
                            // JPEG encoder a second time for every RAW multi-frame capture.
                            val jpegBytesToUse = finalJpegBytesForPublication

                            if (jpegBytesToUse == null || jpegBytesToUse.isEmpty()) {
                                jpegSaveFailure = IllegalStateException("JPEG bytes to save are null/empty")
                                Log.e(tag, "JPEG_RENDER_FAILED_CONTINUING_DNG profile=$profileId")
                                return@async
                            }

                            val reservedJpegUri = reserveMediaStoreItem("$baseFilename.jpg", saveLocation, "image/jpeg")
                            if (reservedJpegUri == null) {
                                jpegSaveFailure = IllegalStateException("JPEG MediaStore reservation returned null")
                                Log.e(tag, "JPEG_RESERVATION_FAILED_CONTINUING_DNG profile=$profileId")
                                return@async
                            }

                            val jpegPostStartedNs = android.os.SystemClock.elapsedRealtimeNanos()
                            val preparedJpeg = prepareJpegForSave(
                                jpegBytes = jpegBytesToUse,
                                tempName = baseFilename,
                                metadata = anchorMetadata,
                                // Both native multi-frame renderers already rotate the JPEG
                                // pixels. A non-normal EXIF tag would rotate them a second time in
                                // galleries. DNG keeps exifOrientation because its Bayer payload
                                // is not physically rotated.
                                orientation = androidx.exifinterface.media.ExifInterface.ORIENTATION_NORMAL,
                                lensName = activeLens.name,
                                captureMode = activeProfile.captureStrategy.name,
                                frameCount = burstFramesSizeSnapshot,
                                watermarkEnabled = watermarkEnabled,
                                watermarkStyle = watermarkStyle,
                                watermarkSignature = watermarkSignature,
                                watermarkAuthor = watermarkAuthor,
                                exifSaveSignature = exifSaveSignature,
                                exifExtraData = exifExtraData,
                                saveLocationData = saveLocationData
                            )
                            val exifAndWatermarkMs = (android.os.SystemClock.elapsedRealtimeNanos() - jpegPostStartedNs) / 1_000_000.0
                            performanceTracker.recordDuration("jpeg_exif_and_watermark", exifAndWatermarkMs)
                            val finalPublicationJpeg = if (renderQualityConfig.ultraHdrGainmapEnabled && ultraHdrGainmapForPublication != null) {
                                val packageStartedNs = android.os.SystemClock.elapsedRealtimeNanos()
                                val packaged = ImageUtils.packageUltraHdrJpegSafe(preparedJpeg, ultraHdrGainmapForPublication)
                                performanceTracker.recordDuration(
                                    "ultra_hdr_container_package",
                                    (android.os.SystemClock.elapsedRealtimeNanos() - packageStartedNs) / 1_000_000.0
                                )
                                if (packaged != null && packaged.isNotEmpty()) {
                                    performanceTracker.setMetric("ultraHdrPackaged", true)
                                    performanceTracker.setMetric("ultraHdrGainmapMaxBoost", ultraHdrGainmapForPublication.maxContentBoost)
                                    Log.i(tag, "Ultra HDR packaged from GPU gainmap: ${ultraHdrGainmapForPublication.width}x${ultraHdrGainmapForPublication.height} maxBoost=${ultraHdrGainmapForPublication.maxContentBoost}")
                                    packaged
                                } else {
                                    performanceTracker.setMetric("ultraHdrPackaged", false)
                                    Log.w(tag, "Ultra HDR requested but final container packaging failed; publishing original SDR JPEG")
                                    preparedJpeg
                                }
                            } else {
                                if (renderQualityConfig.ultraHdrGainmapEnabled) {
                                    performanceTracker.setMetric("ultraHdrPackaged", false)
                                    Log.i(tag, "Ultra HDR requested but no meaningful GPU HDR gainmap authority was produced; publishing SDR JPEG")
                                }
                                preparedJpeg
                            }
                            val mediaStoreStartedNs = android.os.SystemClock.elapsedRealtimeNanos()
                            writeBytesToReservedUri(reservedJpegUri, finalPublicationJpeg)
                            performanceTracker.recordDuration(
                                "jpeg_mediastore_write",
                                (android.os.SystemClock.elapsedRealtimeNanos() - mediaStoreStartedNs) / 1_000_000.0
                            )
                            jpegUri = reservedJpegUri
                            jpegSaved = true
                            performanceTracker.incrementCounter("jpegMediaStorePublicationCount")
                            Log.i(
                                "BnCamCaptureTiming",
                                "route=${plan.route.id} exif_time=${String.format(Locale.US, "%.3f", exifAndWatermarkMs)} " +
                                    "mediastore_write_time=${String.format(Locale.US, "%.3f", (android.os.SystemClock.elapsedRealtimeNanos() - mediaStoreStartedNs) / 1_000_000.0)}"
                            )
                        } catch (t: Throwable) {
                            jpegSaveFailure = t
                            Log.e(
                                tag,
                                "JPEG_SAVE_FAILED_CONTINUING_DNG profile=$profileId requested=$preferredFrameSetting " +
                                        "actual=$activeBufferFormatLabel generation=${anchorPair.generationId} " +
                                        "bufferSize=$framesInBuffer timestamp=${anchorPair.timestamp} " +
                                        "metadataPresent=true",
                                t
                            )
                        }
                    }
                }

                val dngJob = async(Dispatchers.IO) {
                    if (dngExportRequested) {
                        try {
                            val nativeMaster = masterRawFrameForDng
                            if (nativeMaster == null || nativeMaster.raw16ByteCount <= 0) {
                                dngSaveFailure = IllegalStateException("RAW16 bytes for DNG save are null/empty")
                                Log.e(tag, "DNG_SAVE_FAILED_CONTINUING_JPEG profile=$profileId")
                                return@async
                            }
                            val reservedDngUri = reserveMediaStoreItem("$baseFilename.dng", saveLocation, "image/x-adobe-dng")
                            if (reservedDngUri == null) {
                                dngSaveFailure = IllegalStateException("DNG MediaStore reservation returned null")
                                Log.e(tag, "DNG_RESERVATION_FAILED_CONTINUING_JPEG profile=$profileId")
                                return@async
                            }
                            val rawExportStartedNs = android.os.SystemClock.elapsedRealtimeNanos()
                            val rawMaterializeStartedNs = android.os.SystemClock.elapsedRealtimeNanos()
                            val raw16 = nativeMaster.materializeRaw16ForDng()
                            performanceTracker.recordDuration(
                                "raw16_dng_materialization",
                                (android.os.SystemClock.elapsedRealtimeNanos() - rawMaterializeStartedNs) / 1_000_000.0
                            )
                            performanceTracker.incrementCounter("raw16ManagedHeapMaterializationCount")
                            performanceTracker.setMetric("raw16ManagedHeapMaterializationBytes", raw16.size)
                            writeVirtualDngToReservedUri(
                                uri = reservedDngUri,
                                raw16Bytes = raw16,
                                width = nativeMaster.width,
                                height = nativeMaster.height,
                                metadata = anchorMetadata,
                                characteristics = chars,
                                orientation = exifOrientation,
                                dngMergeStats = dngMergeStats,
                                lensHardwareDescription = lensHardwareSettings.dngDescription(),
                                rawDomainContract = nativeMaster.rawFrameInfo
                            )
                            performanceTracker.recordDuration(
                                "dng_export_and_mediastore_write",
                                (android.os.SystemClock.elapsedRealtimeNanos() - rawExportStartedNs) / 1_000_000.0
                            )
                            dngUri = reservedDngUri
                            dngSaved = true
                            performanceTracker.incrementCounter("dngMediaStorePublicationCount")
                            Log.i(
                                "BnCamCaptureTiming",
                                "route=${plan.route.id} raw_export_time=${String.format(Locale.US, "%.3f", (android.os.SystemClock.elapsedRealtimeNanos() - rawExportStartedNs) / 1_000_000.0)}"
                            )
                        } catch (t: Throwable) {
                            dngSaveFailure = t
                            Log.e(
                                tag,
                                "DNG_SAVE_FAILED_CONTINUING_JPEG profile=$profileId requested=$preferredFrameSetting " +
                                        "actual=$activeBufferFormatLabel generation=${anchorPair.generationId} " +
                                        "bufferSize=$framesInBuffer timestamp=${anchorPair.timestamp} " +
                                        "metadataPresent=true",
                                t
                            )
                        }
                    }
                }
                jpegJob.await()
                dngJob.await()
            }
            if (!jpegSaved && !dngSaved) {
                val failure = IllegalStateException(
                    "Neither JPEG nor DNG output was published. " +
                            "jpegFailure=${jpegSaveFailure?.message ?: "not requested"}; " +
                            "dngFailure=${dngSaveFailure?.message ?: "not requested"}"
                )
                jpegSaveFailure?.let(failure::addSuppressed)
                dngSaveFailure?.let(failure::addSuppressed)
                throw failure
            }
        }
        if (!queued) {
            throw IllegalStateException("Save queue is full; output was not accepted.")
        }
        masterRawFrameForDng?.let { nativeMaster ->
            raw16ManagedMaterializationCountSnapshot = nativeMaster.managedMaterializationCount
            raw16ManagedMaterializationBytesSnapshot = nativeMaster.managedMaterializationBytes
            check(nativeMaster.managedMaterializationCount in 0..1) {
                "Phase 1B invariant failed: multi-frame DNG path materialized RAW16 " +
                    "${nativeMaster.managedMaterializationCount} times; at most one managed copy is allowed."
            }
            check(!dngSaved || nativeMaster.managedMaterializationCount == 1) {
                "Phase 1B invariant failed: a published DNG requires exactly one explicit RAW16 materialization."
            }
            if (!dngSaved && nativeMaster.managedMaterializationCount == 0) {
                performanceTracker.setMetric(
                    "raw16DngMaterializationSkippedReason",
                    dngSaveFailure?.message ?: "dng_not_published_before_materialization"
                )
            }
            nativeMaster.close()
            raw16NativeOwnerReleasedBeforePublication = nativeMaster.nativeRaw16Buffer.isClosed
        }
        val stringOutputs = com.bncam.core.output.PublicationPolicyResolver.resolveStrings(
            outputPolicy = plan.outputPolicy,
            jpegSucceeded = jpegSaved,
            jpegUri = if (jpegSaved) jpegUri?.toString() else null,
            dngSucceeded = dngSaved,
            dngUri = if (dngSaved) dngUri?.toString() else null,
            jpegFailureReason = jpegSaveFailure?.message,
            dngFailureReason = dngSaveFailure?.message
        )
        val resolvedOutputs = com.bncam.core.output.PublicationPolicyResolver.resolveUris(
            outputPolicy = plan.outputPolicy,
            jpegSucceeded = jpegSaved,
            jpegUri = if (jpegSaved) jpegUri else null,
            dngSucceeded = dngSaved,
            dngUri = if (dngSaved) dngUri else null,
            jpegFailureReason = jpegSaveFailure?.message,
            dngFailureReason = dngSaveFailure?.message
        )
        val publicUri = resolvedOutputs.thumbnailUri
        dngCreated = dngSaved
        val hdrEnhancedPublicationQualified = hdrEnhancedActive &&
            jpegSaved &&
            hdrEnhancedAlignmentCompletedActual &&
            hdrEnhancedTemporalMergeCompletedActual &&
            hdrEnhancedIspCompletedActual &&
            hdrEnhancedJpegCompletedActual
        if (hdrEnhancedActive) {
            performanceTracker.setMetric(
                "hdrEnhancedPublicationQualified",
                hdrEnhancedPublicationQualified
            )
            performanceTracker.setMetric(
                "hdrEnhancedPublicationStatus",
                if (hdrEnhancedPublicationQualified) "HDR_ENHANCED" else "FALLBACK_OUTPUT"
            )
        }
        if (jpegSaved) {
            Log.i(
                tag,
                "JPEG_SAVE_SUCCEEDED profile=$profileId requested=$preferredFrameSetting actual=$activeBufferFormatLabel " +
                        "generation=${anchorPair.generationId} bufferSize=$framesInBuffer " +
                        "timestamp=${anchorPair.timestamp} metadataPresent=true output=$jpegUri"
            )
        }
        if (dngSaved) {
            Log.i(
                tag,
                "DNG_SAVE_SUCCEEDED profile=$profileId requested=$preferredFrameSetting actual=$activeBufferFormatLabel " +
                        "generation=${anchorPair.generationId} bufferSize=$framesInBuffer " +
                        "timestamp=${anchorPair.timestamp} metadataPresent=true output=$dngUri bytes=$dngBytesSize"
            )
        }
        val saveTimeMs = 0L
        performanceTracker.mark("save_queued")
        val rawTimingLog = parseNativeStats(dngMergeStats)
        val renderTimingLog = parseNativeStats(masterIspStats)
        val yuvTimingLog = parseNativeStats(yuvNativeStats)
        rawTimingLog.forEach { (key, value) -> performanceTracker.setMetric("nativeMerge.$key", value) }
        renderTimingLog.forEach { (key, value) -> performanceTracker.setMetric("nativeRawIsp.$key", value) }
        yuvTimingLog.forEach { (key, value) -> performanceTracker.setMetric("nativeYuv.$key", value) }
        Log.i(
            "BnCamCaptureTiming",
            "route=${plan.route.id} camera_request_time=${String.format(Locale.US, "%.3f", captureDispatchLatencyMs)} " +
                "frame_acquire_time=${String.format(Locale.US, "%.3f", frameAcquireTimeMs)} frame_select_time=0 " +
                "raw_unpack_time=${rawTimingLog["anchorUnpackMs"] ?: "0"} " +
                "master_build_time=$masterBuildTimeMs demosaic_time=${renderTimingLog["demosaicMs"] ?: "0"} " +
                "yuv_convert_time=${yuvTimingLog["yuvToBgrMs"] ?: "0"} " +
                "render_profile_time=${renderTimingLog["curveMs"] ?: yuvTimingLog["yuvPostProcessMs"] ?: "0"} " +
                "denoise_time=${renderTimingLog["denoiseMs"] ?: "0"} sharpen_time=${renderTimingLog["sharpenMs"] ?: "0"} " +
                "jpeg_encode_time=${renderTimingLog["jpegEncodeMs"] ?: yuvTimingLog["yuvJpegEncodeMs"] ?: "0"} " +
                "exif_time=${if (plan.outputPolicy.producesJpeg) "QUEUED" else "0"} " +
                "mediastore_write_time=QUEUED raw_export_time=${if (plan.outputPolicy.producesRaw) "QUEUED" else "0"} " +
                "optional_analysis_time=0 total_until_jpeg_render_complete=${if (plan.outputPolicy.producesJpeg) jpegRenderOnlyTimeMs else 0} " +
                "total_until_preview_ready=${String.format(Locale.US, "%.3f", performanceTracker.elapsedMs())}"
        )
        // ==========================================
        // 5. DEBUGGING AANROEP
        // ==========================================
        val totalProcessingTimeMs = System.currentTimeMillis() - totalStartTimeMs
        performanceTracker.mark("complete")

        val vendorAttempts = VendorInjectionEngine.consumeAttempts(activeLens.id)
        vendorAttempts.forEach { attempt ->
            captureTrace.record(
                com.bncam.core.tracing.CaptureTraceSection.VENDOR_TAGS,
                attempt.keyName,
                "stage=${attempt.builderStage};status=${attempt.finalStatus};" +
                    "attempted=${attempt.attempted};applied=${attempt.appliedToBuilder}"
            )
        }
        if (enableShotLogger) {
            frameDebugEntries.clear()
            frameDebugEntries.addAll(
                buildNativeFrameAnalysisEntries(
                    burstFrames = burstFrames,
                    shutterTimestampNs = shutterTimestampNs,
                    shutterTimestampDomain = shutterTimestampDomain,
                    activeBufferFormatLabel = activeBufferFormatLabel,
                    selectedAnchorIndex = burstFrames.lastIndex,
                    framesMerged = when (activeZslFormat) {
                        ImageFormat.RAW10 -> raw10FramesMerged
                        ImageFormat.RAW_SENSOR -> rawSensorFramesMerged
                        else -> if (finalJpegBytes != null) yuvFramesUsed.coerceAtLeast(if (yuvAnchorOnly) 1 else 0) else 0
                    },
                    supportRejected = when (activeZslFormat) {
                        ImageFormat.RAW10 -> raw10SupportRejected
                        ImageFormat.RAW_SENSOR -> rawSensorSupportRejected
                        else -> 0
                    },
                    width = width,
                    height = height,
                    analysisSource = "$activeBufferFormatLabel metadata_proxy_scoring"
                )
            )
            shotLogger.recordFrameAnalysisEntries(frameDebugEntries)

            if (logVendorInjection) shotLogger.writeVendorInjectionDebug(activeLens.id, vendorAttempts, anchorMetadata)

            // Recording Metering Validation details
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
            val actualAeRegions = anchorMetadata.get(CaptureResult.CONTROL_AE_REGIONS)
            val actualAfRegions = anchorMetadata.get(CaptureResult.CONTROL_AF_REGIONS)
            val actualEvCompSteps =
                anchorMetadata.get(CaptureResult.CONTROL_AE_EXPOSURE_COMPENSATION)
            val selectedRequestedEvCompSteps =
                selectedRequestSnapshot?.state?.aeExposureCompensation
            val actualEvComp = actualEvCompSteps?.toFloat() ?: 0f
            val requestedEvComp = selectedRequestedEvCompSteps?.toFloat() ?: 0f
            val intent = anchorMetadata.get(CaptureResult.CONTROL_CAPTURE_INTENT)

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
                val cropRegionVal = anchorMetadata?.get(CaptureResult.SCALER_CROP_REGION)

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
            shotLogger.recordPipelineEvent("Metering Validation", "currentPipelineGeneration", ringBuffer.currentGeneration().toString())
            shotLogger.recordPipelineEvent("Metering Validation", "currentSubmittedControlRequestEpochAtShutter", currentSubmittedControlRequestEpochAtShutter.toString())
            shotLogger.recordPipelineEvent("Metering Validation", "selectedFramePipelineGeneration", anchorPair.generationId.toString())
            shotLogger.recordPipelineEvent("Metering Validation", "selectedFrameControlRequestEpoch", anchorPair.controlRequestEpoch.toString())
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
            val cropRegionVal = anchorMetadata?.get(CaptureResult.SCALER_CROP_REGION)
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
            shotLogger.recordPipelineEvent("ImageReader / Buffer", "framesEligible", burstFrames.size.toString())
            shotLogger.recordPipelineEvent("ImageReader / Buffer", "framesScored", burstFrames.size.toString())
            shotLogger.recordPipelineEvent("ImageReader / Buffer", "framesRejectedStale", framesRejectedStaleVal.toString())
            shotLogger.recordPipelineEvent("ImageReader / Buffer", "framesDroppedByImageReaderMeasured", "false")
            shotLogger.recordPipelineEvent("ImageReader / Buffer", "framesDroppedByImageReader", "unknown")
            shotLogger.recordPipelineEvent("ImageReader / Buffer", "imageReaderAcquireFailureCount", imageReaderAcquireFailureCountVal.toString())
            shotLogger.recordPipelineEvent("ImageReader / Buffer", "imageReaderBackpressureDetected", imageReaderPressureAtShutter.backpressureDetected.toString())
            shotLogger.recordPipelineEvent("ImageReader / Buffer", "ringBufferOverwriteCount", imageReaderPressureAtShutter.ringOverwriteCount.toString())
            shotLogger.recordPipelineEvent("ImageReader / Buffer", "ringBufferOverwriteExpected", "not_inferred_from_cumulative_acquisition")
            shotLogger.recordPipelineEvent("ImageReader / Buffer", "framesDroppedByRingBuffer", framesDroppedByRingBufferVal.toString())
            shotLogger.recordPipelineEvent("ImageReader / Buffer", "bufferBackpressureDetected", bufferBackpressureDetectedVal.toString())
            shotLogger.recordPipelineEvent("ImageReader / Buffer", "oldestFrameAgeMs", oldestFrameAgeMs?.let { String.format(Locale.US, "%.1f", it) } ?: "CLOCK_DOMAIN_UNAVAILABLE")
            shotLogger.recordPipelineEvent("ImageReader / Buffer", "newestFrameAgeMs", newestFrameAgeMs?.let { String.format(Locale.US, "%.1f", it) } ?: "CLOCK_DOMAIN_UNAVAILABLE")
            shotLogger.recordPipelineEvent("ImageReader / Buffer", "selectedDeltaMs", selectedDeltaMs?.let { String.format(Locale.US, "%.1f", it) } ?: "CLOCK_DOMAIN_UNAVAILABLE")
            shotLogger.recordPipelineEvent("Thumbnail / URI Lifecycle", "thumbnailUriReturnedAfterSave", (publicUri != null).toString())
            shotLogger.recordPipelineEvent("Thumbnail / URI Lifecycle", "jpegSaveSucceededBeforeUriReturn", (publicUri != null).toString())
            shotLogger.recordPipelineEvent("Thumbnail / URI Lifecycle", "mediaStorePendingClearedBeforeUriReturn", (publicUri != null).toString())
            shotLogger.recordPipelineEvent("Thumbnail / URI Lifecycle", "returnedUriWasPending", "false")
            shotLogger.recordPipelineEvent("Thumbnail / URI Lifecycle", "thumbnailUpdateUri", publicUri?.toString() ?: "none")
            shotLogger.recordPipelineEvent("Thumbnail / URI Lifecycle", "thumbnailUpdateSource", "runner_result")
            shotLogger.recordPipelineEvent("Thumbnail / URI Lifecycle", "captureExecuteReturnedAfterRenderMs", totalProcessingTimeMs.toString())
            shotLogger.recordPipelineEvent("Thumbnail / URI Lifecycle", "detachedIspLaunchUsed", "false")

            if (activeZslFormat == ImageFormat.YUV_420_888) {
                shotLogger.recordPipelineEvent("YUV Native Render", "Stats", yuvNativeStats)
                parseNativeStats(yuvNativeStats).forEach { (key, value) ->
                    shotLogger.recordPipelineEvent("YUV Native Render", prettyStatKey(key), value)
                }
                if (yuvAnchorOnly && burstFrames.size > 1) {
                    shotLogger.recordWarning(
                        "YUV Native Render",
                        "YUV_COMPUTE was requested with ${burstFrames.size} frames, but native processing used anchor-only output. Stats: $yuvNativeStats",
                        "WARN"
                    )
                }
            }
            if (activeZslFormat == ImageFormat.RAW10) {
                shotLogger.recordPipelineEvent("RAW10 Native Merge", "Native Stats", raw10NativeStats)
                raw10StatsMap.forEach { (key, value) ->
                    shotLogger.recordPipelineEvent("RAW10 Native Merge", prettyStatKey(key), value)
                }
                if (raw10AnchorOnly && publicUri != null && rawExecutionFrameCount > 1) {
                    shotLogger.recordWarning(
                        "Merge",
                        "RAW10 multi-frame requested, but native merge used anchor output only. Stats: $raw10NativeStats",
                        "WARN"
                    )
                }
            }
            if (activeZslFormat == ImageFormat.RAW_SENSOR) {
                shotLogger.recordPipelineEvent("RAW_SENSOR Native Merge", "Native Stats", rawSensorNativeStats)
                rawSensorStatsMap.forEach { (key, value) ->
                    shotLogger.recordPipelineEvent("RAW_SENSOR Native Merge", prettyStatKey(key), value)
                }
                if (rawSensorAnchorOnly && publicUri != null && rawExecutionFrameCount > 1) {
                    shotLogger.recordWarning(
                        "Merge",
                        "RAW_SENSOR multi-frame requested, but native merge used anchor output only. Stats: $rawSensorNativeStats",
                        "WARN"
                    )
                }
            }

            if (isRawEnabled) {
                shotLogger.recordPipelineEvent("Master RAW16 ISP Render", "Stats", masterIspStats)
                parseNativeStats(masterIspStats).forEach { (key, value) ->
                    shotLogger.recordPipelineEvent("Master RAW16 ISP Render", prettyStatKey(key), value)
                    // 🔥 NIEUW: Native Calibration Bridge
                    if (key in listOf("hasBlackLevel", "hasWhiteLevel", "hasColorMatrix", "hasWbGains", "hasNoiseProfile", "calibrationApplied", "noiseProfileApplied", "calibrationWarnings")) {
                        shotLogger.recordPipelineEvent("Native Calibration", key, value)
                    }
                }
                shotLogger.recordPipelineEvent("DNG Merge / RAW16 Master", "Stats", dngMergeStats)
            }

            val dngPerformanceStats = when (activeZslFormat) {
                ImageFormat.RAW10 -> raw10StatsMap
                ImageFormat.RAW_SENSOR -> rawSensorStatsMap
                else -> emptyMap()
            }
            val rawIspPerformanceStats = parseNativeStats(masterIspStats)
            val jpegCompressionTimeMs = (
                    rawIspPerformanceStats["jpegEncodeMs"]
                        ?: yuvStatsMap["yuvJpegEncodeMs"]
                        ?: "0"
                    ).toDoubleOrNull()?.toLong() ?: 0L
            val mergeTimeMs = dngPerformanceStats["totalNativeDngMergeMs"]?.toDoubleOrNull()?.toLong() ?: 0L
            shotLogger.recordPipelineEvent("Performance", "captureDispatchLatencyMs", String.format(Locale.US, "%.3f", captureDispatchLatencyMs))
            shotLogger.recordPipelineEvent("Performance", "rawUnpackMs", dngPerformanceStats["anchorUnpackMs"] ?: "0")
            shotLogger.recordPipelineEvent("Performance", "mergeMs", dngPerformanceStats["totalNativeDngMergeMs"] ?: "0")
            shotLogger.recordPipelineEvent("Performance", "masterBuildMs", masterBuildTimeMs.toString())
            shotLogger.recordPipelineEvent("Performance", "dngWriteMs", dngWriteTimeMs.toString())
            shotLogger.recordPipelineEvent("Performance", "jpegRenderMs", jpegRenderOnlyTimeMs.toString())
            shotLogger.recordPipelineEvent("Performance", "jpegEncodeMs", jpegCompressionTimeMs.toString())
            shotLogger.recordPipelineEvent("Performance", "saveMs", saveTimeMs.toString())
            performanceTracker.debugPairs().forEach { (key, value) ->
                shotLogger.recordPipelineEvent("Performance", key, value)
            }

            val anchorOutputCreated = finalJpegBytes != null && (when (activeZslFormat) {
                ImageFormat.RAW10 -> raw10AnchorOnly
                ImageFormat.RAW_SENSOR -> rawSensorAnchorOnly
                ImageFormat.YUV_420_888 -> yuvAnchorOnly
                else -> true
            })

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
                completedAtStr = isoFormat.format(Date()),
                publicFilename = "$baseFilename.jpg",
                savedOutputPath = publicUri?.toString() ?: "FAILED",
                profileName = activeProfile.name,
                profileId = profileId,
                cameraId = activeLens.id,
                targetRotation = finalRotation,
                frameWidth = width,
                frameHeight = height,
                bufferFormat = activeBufferFormatLabel,
                totalShotTimeMs = totalProcessingTimeMs,
                cameraCaptureTimeMs = captureDispatchLatencyMs.toLong(),
                mergeTimeMs = mergeTimeMs,
                framesBuffered = framesInBuffer,
                framesRequested = requestFrames,
                framesEligible = burstFrames.size,
                prunedInvalid = 0,
                prunedStale = 0,
                prunedDupes = 0,
                prunedFirstFrame = 0,
                framesAccepted = when {
                    activeZslFormat == ImageFormat.RAW10 && raw10FramesMerged > 0 -> raw10FramesMerged
                    activeZslFormat == ImageFormat.RAW_SENSOR && rawSensorFramesMerged > 0 -> rawSensorFramesMerged
                    activeZslFormat == ImageFormat.YUV_420_888 && finalJpegBytes != null -> yuvFramesUsed.coerceAtLeast(if (yuvAnchorOnly) 1 else 0)
                    else -> burstFrames.size
                },
                framesMerged = when (activeZslFormat) {
                    ImageFormat.RAW10 -> raw10FramesMerged
                    ImageFormat.RAW_SENSOR -> rawSensorFramesMerged
                    ImageFormat.YUV_420_888 -> if (mergeOutputCreated) yuvFramesUsed.coerceAtLeast(if (yuvAnchorOnly) 1 else 0) else 0
                    else -> if (mergeOutputCreated) burstFrames.size else 0
                },
                framesRejected = when (activeZslFormat) {
                    ImageFormat.RAW10 -> raw10SupportRejected
                    ImageFormat.RAW_SENSOR -> rawSensorSupportRejected
                    ImageFormat.YUV_420_888 -> intStat(yuvStatsMap, "yuvSupportRejected", 0)
                    else -> 0
                },
                captureSucceeded =
                    resolvedOutputs.publicationResult !=
                        com.bncam.core.output.CapturePublicationResult.FAILURE,
                requestedMode = activeProfile.captureStrategy.name,
                requestedModeLabel = activeProfile.captureStrategy.label,
                preferredFrameSetting = preferredFrameSetting,
                resolvedRoute = when (activeZslFormat) {
                    ImageFormat.RAW_SENSOR -> "CAMERA2_RAW_SENSOR_WARM_BUFFER_MULTI_FRAME"
                    ImageFormat.RAW10 -> "CAMERA2_RAW10_WARM_BUFFER_MULTI_FRAME"
                    else -> "CAMERA2_YUV_COMPUTE_WARM_BUFFER"
                },
                actualRoute = when (activeZslFormat) {
                    ImageFormat.RAW_SENSOR -> "CAMERA2_RAW_SENSOR_WARM_BUFFER_MULTI_FRAME"
                    ImageFormat.RAW10 -> "CAMERA2_RAW10_WARM_BUFFER_MULTI_FRAME"
                    ImageFormat.YUV_420_888 -> if (yuvAnchorOnly) "CAMERA2_YUV_FAST_ANCHOR_ONLY" else "CAMERA2_YUV_COMPUTE_MULTI_FRAME_LUMA"
                    else -> "CAMERA2_UNKNOWN"
                },
                routeRunner = "MultiFrameRunner",
                basePosition = basePosition,
                candidatesAnalyzed = baseCandidates,
                includeInMerge = baseInclude,
                primaryBias = "DEPRECATED_UNAPPLIED_MULTI_FRAME",
                temporalBias = 0f,
                frameBias = "DEPRECATED_UNAPPLIED_MULTI_FRAME",
                acceptAllFrames = false,
                rejectDupes = rejectDupes,
                alignableOnly = false,
                discardFirstFrame = discardFirst,
                preferRecent = false,
                ignoreStaleFrames = ignoreStale,
                preMergeTriggered = true,
                alignmentTriggered = true,
                mergeTriggered = true,
                postMergeTriggered = false,
                advancedTriggered = false,
                fallbackUsed = when (activeZslFormat) {
                    ImageFormat.RAW10 -> raw10AnchorOnly
                    ImageFormat.RAW_SENSOR -> rawSensorAnchorOnly && burstFrames.size > 1
                    ImageFormat.YUV_420_888 -> yuvAnchorOnly && burstFrames.size > 1
                    else -> !mergeOutputCreated
                },
                fallbackTarget = when {
                    activeZslFormat == ImageFormat.RAW10 && mergeOutputCreated -> "none"
                    activeZslFormat == ImageFormat.RAW10 -> "raw10_anchor_output"
                    activeZslFormat == ImageFormat.RAW_SENSOR && mergeOutputCreated -> "none"
                    activeZslFormat == ImageFormat.RAW_SENSOR && burstFrames.size > 1 -> "raw_sensor_anchor_output"
                    activeZslFormat == ImageFormat.YUV_420_888 && yuvAnchorOnly && burstFrames.size > 1 -> "yuv_fast_anchor_output"
                    mergeOutputCreated -> "none"
                    else -> "anchor_quick_jpeg"
                },
                fallbackReason = when {
                    activeZslFormat == ImageFormat.RAW10 && mergeOutputCreated -> "none"
                    activeZslFormat == ImageFormat.RAW10 -> "RAW10 support frames were not accepted by native merge; anchor RAW10 output used"
                    activeZslFormat == ImageFormat.RAW_SENSOR && mergeOutputCreated -> "none"
                    activeZslFormat == ImageFormat.RAW_SENSOR && burstFrames.size > 1 -> "RAW_SENSOR support frames were not accepted by native merge; anchor RAW_SENSOR output used"
                    activeZslFormat == ImageFormat.YUV_420_888 && yuvAnchorOnly && burstFrames.size > 1 -> "YUV support frames were not accepted by native luma alignment; anchor YUV output used"
                    mergeOutputCreated -> "none"
                    else -> "merged output not created; anchor quick JPEG remains public output"
                },
                hardFailure =
                    resolvedOutputs.publicationResult ==
                        com.bncam.core.output.CapturePublicationResult.FAILURE,
                processingFallback = false,
                routeAnalysis = when {
                    activeZslFormat == ImageFormat.RAW10 && mergeOutputCreated -> "RAW10 native multi-frame merge executed before demosaic. Stats: $raw10NativeStats"
                    activeZslFormat == ImageFormat.RAW10 -> "RAW10 native burst route executed, but output is anchor-only. Stats: $raw10NativeStats"
                    activeZslFormat == ImageFormat.RAW_SENSOR && mergeOutputCreated -> "RAW_SENSOR native still-burst merge executed before demosaic. Stats: $rawSensorNativeStats"
                    activeZslFormat == ImageFormat.RAW_SENSOR -> "RAW_SENSOR native still-burst route executed, but output is anchor-only. Stats: $rawSensorNativeStats"
                    activeZslFormat == ImageFormat.YUV_420_888 && !yuvAnchorOnly -> "YUV_COMPUTE native luma alignment/average used $yuvFramesUsed frame(s) with anchor UV. Stats: $yuvNativeStats"
                    activeZslFormat == ImageFormat.YUV_420_888 -> "YUV_FAST anchor-only native route used. Stats: $yuvNativeStats"
                    else -> "$activeBufferFormatLabel native burst route executed via HardwareBuffer bridge."
                },
                lensName = activeLens.name,
                lensFacing = chars.get(CameraCharacteristics.LENS_FACING)?.let { if (it == CameraCharacteristics.LENS_FACING_FRONT) "FRONT" else "BACK" } ?: "UNKNOWN",
                sensorOrientation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: -1,
                outputWidth = width,
                outputHeight = height,
                jpegCreated = finalJpegBytes != null,
                anchorQuickJpegCreated = anchorOutputCreated,
                jpegBytes = finalJpegBytes?.size ?: 0,
                anchorQuickJpegBytes = if (anchorOutputCreated) finalJpegBytes?.size ?: 0 else 0,
                hqJpegCreated = hqJpegCreated,
                hqJpegBytes = hqJpegBytesSize,
                hqSavedOutputPath = hqUri?.toString() ?: "not created",
                saveLocation = saveLocation,
                gpsAdded = saveLocationData,
                dngCreated = dngCreated,
                dngBytes = dngBytesSize,
                dngSavedOutputPath = dngUri?.toString() ?: "not created",
                rawSensorDisabled = activeZslFormat != ImageFormat.RAW_SENSOR,
                raw16Disabled = activeZslFormat != ImageFormat.RAW_SENSOR,
                openGlUsed = false,
                openCvUsed = ImageUtils.nativeEngineAvailable,
                bufferCapacity = ringBuffer.currentCapacity(),
                bufferWarmEnough =
                    framesInBuffer >=
                            warmBufferRequirement.requiredCompleteFrames,
                selectedAnchorIndex = frameDebugEntries.find { it.selectedAnchor }?.index ?: -1,
                selectedAnchorTimestampNs = frameDebugEntries.find { it.selectedAnchor }?.timestampNs ?: 0L,
                selectedAnchorDeltaMs = frameDebugEntries.find { it.selectedAnchor }?.deltaToShutterMs ?: 0.0,
                selectedAnchorTiming = frameDebugEntries.find { it.selectedAnchor }?.shutterRelation ?: "unknown",
                selectedAnchorReason = "Most recent complete frame",
                analysisSource = "$activeBufferFormatLabel metadata_proxy_scoring",
                mergeFrameCountSetting = requestFrames,
                mergeSubPixelSetting = useSubPixel,
                mergeLinearInterpolationSetting = useLinearInterp,
                mergeStrictnessSetting = strictness,
                mergeMaxShiftSetting = maxShift,
                framesCopiedToRam = 0,
                supportFramesForMerge = (burstFrames.size - 1).coerceAtLeast(0),
                mergeInputBytes = mergeInputBytes,
                mergeOutputCreated = mergeOutputCreated,
                mergeOutputBytes = mergeOutputBytes,
                mergedOutputPath = mergeOutputPath,
                renderTimeMs = renderTimeMs,
                jpegCompressionTimeMs = jpegCompressionTimeMs,
                saveTimeMs = saveTimeMs,
                debugWriteTimeMs = 0L,
                failureReason = failureReason,
                meteringStyle = meteringStyle,
                evOffset = evOffset,
                aeRegionsRequested = formatAeRegions(selectedRequestedAeRegions),
                aeRegionsResult = formatAeRegions(actualAeRegions),
                evCompRequested = requestedEvComp,
                evCompResult = actualEvComp,
                aeStateBeforeCapture = aeStateName(aeStateBeforeCapture),
                aeStateAtCapture = aeStateName(anchorMetadata?.get(CaptureResult.CONTROL_AE_STATE)),
                sensorExposureTime = anchorMetadata?.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: 0L,
                sensorSensitivity = anchorMetadata?.get(CaptureResult.SENSOR_SENSITIVITY) ?: 0,
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

            shotLogger.writeTextFile(
                "noise_model_trace.json",
                NoiseModelTrace.build(
                    frames = selectedNoiseTraceFrames,
                    jniCalibration = renderQualityConfig.finalCalibration,
                    nativeStats = if (isRawEnabled) masterIspStats else yuvNativeStats,
                    fusionStats = if (isRawEnabled) jpegMergeStats else "",
                    dynamicIsoCoefficient = lensHardwareSettings.dynamicIsoCoeff,
                    captureAttemptId = attemptId,
                    recipe = recipe,
                    runnerPerformance = performanceTracker.traceSnapshot(),
                    publicationState = NoiseModelPublicationState(
                        jpegPublished = jpegSaved,
                        dngPublished = dngSaved,
                        thumbnailPublished = resolvedOutputs.thumbnailUri != null,
                        publicationResult = resolvedOutputs.publicationResult.name,
                        jpegFailureReason = jpegSaveFailure?.message,
                        dngFailureReason = dngSaveFailure?.message
                    )
                )
            )
            shotLogger.writeShotDebug(payload, logSummary, logActiveMode, logProfileSettings, logFrameAnalysis, logWarnings, logPipelineDebug, logVendorInjection)
            val jpegPublished = finalJpegBytesForPublication != null
            val dngPublished = raw16ByteCountSnapshot > 0
            shotLogger.finalizeAttemptOnce(
                attemptId = attemptId,
                terminalState = com.bncam.core.debug.CaptureStatusState.COMPLETED,
                stage = "CAPTURE_COMPLETED",
                jpegPublished = jpegPublished,
                dngPublished = dngPublished,
                noiseModelStarted = renderQualityConfig.finalCalibration?.noiseModelMode != "Off",
                noiseModelCompleted = (if (isRawEnabled) masterIspStats else yuvNativeStats).contains("noiseModelApplied=yes")
            )
        }
            performanceTracker.setMetric("genuinelyFusedFrameCount", when (activeZslFormat) {
                ImageFormat.RAW10 -> if (raw10AnchorOnly) 0 else raw10FramesMerged
                ImageFormat.RAW_SENSOR -> if (rawSensorAnchorOnly) 0 else rawSensorFramesMerged
                else -> if (yuvAnchorOnly) 0 else yuvFramesUsed
            })
            performanceTracker.setMetric("finalJpegByteCount", finalJpegBytesForPublication?.size ?: 0)
            performanceTracker.setMetric("dngRaw16ByteCount", raw16ByteCountSnapshot)
            if (isRawEnabled) {
                performanceTracker.setMetric(
                    "raw16ManagedHeapMaterializationCount",
                    raw16ManagedMaterializationCountSnapshot
                )
                performanceTracker.setMetric(
                    "raw16ManagedHeapMaterializationBytes",
                    raw16ManagedMaterializationBytesSnapshot
                )
                performanceTracker.setMetric(
                    "raw16NativeOwnerReleasedBeforePublication",
                    raw16NativeOwnerReleasedBeforePublication
                )
                performanceTracker.setMetric(
                    "raw16NativeOutstandingBuffersAtPublication",
                    ImageUtils.nativeRaw16OutstandingBufferCountSafe()
                )
                check(raw16ManagedMaterializationCountSnapshot in 0..1) {
                    "Phase 1B invariant failed: ${plan.outputPolicy} materialized RAW16 " +
                        "$raw16ManagedMaterializationCountSnapshot times; at most one managed copy is allowed."
                }
                check(dngExportRequested || raw16ManagedMaterializationCountSnapshot == 0) {
                    "Phase 1B invariant failed: JPEG-only multi-frame capture created a managed RAW16 copy."
                }
                check(!dngSaved || raw16ManagedMaterializationCountSnapshot == 1) {
                    "Phase 1B invariant failed: a published DNG did not use exactly one explicit RAW16 materialization."
                }
                check(raw16NativeOwnerReleasedBeforePublication) {
                    "Phase 1B invariant failed: native RAW16 owner was still live at publication."
                }
            }

            val rawIspCount = performanceTracker.counter("rawIspInvocationCount")
            val jpegEncodeCount = performanceTracker.counter("jpegEncodeInvocationCount")
            val mergeCount = performanceTracker.counter("rawMergeInvocationCount")
            val jpegPublicationCount = performanceTracker.counter("jpegMediaStorePublicationCount")
            val dngPublicationCount = performanceTracker.counter("dngMediaStorePublicationCount")
            check(!jpegSaved || !isRawEnabled || rawIspCount == 1) {
                "Phase 0 invariant failed: RAW JPEG capture invoked ISP $rawIspCount times."
            }
            check(!jpegSaved || jpegEncodeCount == 1) {
                "Phase 0 invariant failed: JPEG capture encoded $jpegEncodeCount times."
            }
            if (plan.outputPolicy == OutputPolicy.RAW_ONLY) {
                check(rawIspCount == 0) {
                    "RAW-only invariant failed: RGB ISP executed $rawIspCount times."
                }
                check(jpegEncodeCount == 0) {
                    "RAW-only invariant failed: JPEG encoder executed $jpegEncodeCount times."
                }
                check(jpegPublicationCount == 0) {
                    "RAW-only invariant failed: hidden JPEG publication count=$jpegPublicationCount."
                }
                check(finalJpegBytesForPublication == null) {
                    "RAW-only invariant failed: unpublished JPEG bytes were created."
                }
            }
            val expectedRawMasterBuilds = when {
                !isRawEnabled -> 0
                plan.outputPolicy == OutputPolicy.JPEG_PLUS_RAW &&
                    jpegFusionFrameCount != dngMasterFrameCount -> 2
                else -> 1
            }
            check(mergeCount == expectedRawMasterBuilds) {
                "RAW master build invariant failed: expected=$expectedRawMasterBuilds actual=$mergeCount."
            }
            check(!jpegSaved || jpegPublicationCount == 1) {
                "Phase 0 invariant failed: JPEG was published $jpegPublicationCount times."
            }
            check(!dngSaved || dngPublicationCount == 1) {
                "Phase 0 invariant failed: DNG was published $dngPublicationCount times."
            }
            val anchorOnly = when (activeZslFormat) {
                ImageFormat.RAW10 -> raw10AnchorOnly
                ImageFormat.RAW_SENSOR -> rawSensorAnchorOnly
                ImageFormat.YUV_420_888 -> yuvAnchorOnly
                else -> true
            }
            val executedFusion = when {
                anchorOnly -> "anchor_only"
                activeZslFormat == ImageFormat.YUV_420_888 -> "weighted_average"
                else -> "robust_mean"
            }
            captureTrace.decision(
                section = com.bncam.core.tracing.CaptureTraceSection.FRAME_SELECTION,
                key = "executedFrameSelection",
                requested = recipe.frameSelectionMethod.requestedId,
                supported = recipe.frameSelectionMethod.supported.toString(),
                resolved = recipe.frameSelectionMethod.resolvedId,
                executed = "latest_complete",
                result = "anchor_index_${burstFrames.lastIndex}",
                fallback = false,
                reason = "multi_frame_runner_leases_latest_complete_frames"
            )
            captureTrace.decision(
                section = com.bncam.core.tracing.CaptureTraceSection.ALIGNMENT,
                key = "executedAlignment",
                requested = recipe.alignmentMethod.requestedId,
                supported = recipe.alignmentMethod.supported.toString(),
                resolved = recipe.alignmentMethod.resolvedId,
                executed = if (anchorOnly) "not_executed" else "phase_correlation_fast",
                result = if (anchorOnly) "anchor_only" else "support_frames_aligned",
                fallback = anchorOnly && rawExecutionFrameCount > 1,
                reason =
                    if (anchorOnly) {
                        if (plan.outputPolicy == OutputPolicy.RAW_ONLY) {
                            "dng_master_is_anchor_raw"
                        } else {
                            "no_support_frame_accepted_by_native_alignment"
                        }
                    } else if (plan.outputPolicy == OutputPolicy.RAW_ONLY) {
                        "native_alignment_determines_published_fused_dng"
                    } else {
                        "native_opencv_phase_correlation_translation_for_jpeg_processing"
                    }
            )
            captureTrace.decision(
                section = com.bncam.core.tracing.CaptureTraceSection.FUSION,
                key = "executedFusion",
                requested = recipe.fusionMethod.requestedId,
                supported = recipe.fusionMethod.supported.toString(),
                resolved = recipe.fusionMethod.resolvedId,
                executed = executedFusion,
                result = "frames_merged_${
                    when (activeZslFormat) {
                        ImageFormat.RAW10 -> raw10FramesMerged
                        ImageFormat.RAW_SENSOR -> rawSensorFramesMerged
                        else -> yuvFramesUsed.coerceAtLeast(if (yuvAnchorOnly && jpegSaved) 1 else 0)
                    }
                }",
                fallback = anchorOnly && rawExecutionFrameCount > 1,
                reason =
                    if (anchorOnly) {
                        "anchor_only_typed_result"
                    } else if (plan.outputPolicy == OutputPolicy.RAW_ONLY) {
                        "raw_fusion_determines_published_fused_dng"
                    } else {
                        "source_specific_native_accumulation_for_jpeg_processing"
                    }
            )
            val executedDemosaic = when {
                plan.outputPolicy == OutputPolicy.RAW_ONLY -> "not_executed"
                activeZslFormat == ImageFormat.YUV_420_888 -> "not_applicable_yuv"
                else ->
                    parseNativeStats(masterIspStats)["resolvedDemosaicAlgorithm"]
                        ?: recipe.demosaicMethod.resolvedId
            }
            captureTrace.decision(
                section = com.bncam.core.tracing.CaptureTraceSection.ISP_EXECUTION,
                key = "executedDemosaic",
                requested = recipe.demosaicMethod.requestedId,
                supported = recipe.demosaicMethod.supported.toString(),
                resolved = recipe.demosaicMethod.resolvedId,
                executed = executedDemosaic,
                result = when {
                    plan.outputPolicy == OutputPolicy.RAW_ONLY -> "not_applicable_raw_only"
                    jpegSaved -> "jpeg_rendered"
                    else -> "jpeg_failed"
                },
                fallback =
                    parseNativeStats(masterIspStats)["fallbackOccurred"]
                        .equals("true", ignoreCase = true),
                reason =
                    if (plan.outputPolicy == OutputPolicy.RAW_ONLY) {
                        "raw_only_stops_before_demosaic_rgb_isp_and_jpeg"
                    } else {
                        parseNativeStats(masterIspStats)["fallbackReason"]
                }
            )
            if (plan.outputPolicy == OutputPolicy.RAW_ONLY) {
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
            }
            captureTrace.decision(
                section = com.bncam.core.tracing.CaptureTraceSection.OUTPUT_AND_PUBLICATION,
                key = "executedDngSource",
                requested = recipe.dngSource.name,
                supported = dngExportRequested.toString(),
                resolved = recipe.dngSource.name,
                executed =
                    if (dngSaved) {
                        executedDngSource.name
                    } else {
                        "not_executed"
                    },
                result =
                    if (dngSaved) {
                        "published_dng"
                    } else if (dngExportRequested) {
                        "dng_not_published"
                    } else {
                        "not_applicable"
                    },
                fallback = dngSaved && executedDngSource != recipe.dngSource,
                reason =
                    if (!dngExportRequested) {
                        "output_policy_does_not_request_dng"
                    } else if (executedDngSource == com.bncam.core.capture.DngSource.ANCHOR_RAW) {
                        "published_dng_contains_selected_anchor_raw"
                    } else {
                        "published_dng_contains_native_fused_raw_master"
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
            workReservation.markPublished(stringOutputs)
            if (hdrEnhancedActive) {
                if (hdrEnhancedPublicationQualified) {
                    Log.i(
                        "HdrEnhancedRunner",
                        "HDR_ENHANCED_PUBLISHED: status=SUCCESS jpegUri=$jpegUri " +
                            "captureAuthorityRunner=HdrEnhancedRunner " +
                            "processingBackend=MultiFrameRunner/VulkanRawMultiFrameBackend " +
                            "alignmentComplete=$hdrEnhancedAlignmentCompletedActual " +
                            "temporalMergeComplete=$hdrEnhancedTemporalMergeCompletedActual " +
                            "ispComplete=$hdrEnhancedIspCompletedActual jpegComplete=$hdrEnhancedJpegCompletedActual"
                    )
                } else {
                    Log.w(
                        "HdrEnhancedRunner",
                        "HDR_ENHANCED_FALLBACK_PUBLISHED: status=FALLBACK_OUTPUT jpegSaved=$jpegSaved dngSaved=$dngSaved " +
                            "alignmentComplete=$hdrEnhancedAlignmentCompletedActual " +
                            "temporalMergeComplete=$hdrEnhancedTemporalMergeCompletedActual " +
                            "ispComplete=$hdrEnhancedIspCompletedActual jpegComplete=$hdrEnhancedJpegCompletedActual"
                    )
                }
            }
            performanceTracker.persistJsonLine(context, status = "PUBLISHED")
            } catch (failure: Throwable) {
                if (enableShotLogger) {
                    shotLogger.writeCaptureTrace(
                        captureTrace.completeFailure("multi_frame_processing", failure)
                    )
                } else {
                    captureTrace.exception("multi_frame_processing", failure)
                }
                performanceTracker.setMetric("terminalFailure", "${failure.javaClass.simpleName}:${failure.message}")
                performanceTracker.persistJsonLine(
                    context = context,
                    status = "FAILED",
                    failureReason = "${failure.javaClass.simpleName}:${failure.message}"
                )
                workReservation.fail("multiframe_failed:${failure.javaClass.simpleName}:${failure.message}")
                throw failure
            } finally {
                masterRawFrameForDng?.close()
            }
        }

        if (!submitted) {
            // No worker accepted ownership, so the capture thread must release the leased source
            // frames immediately. No native master has been created yet because all heavy work now
            // starts inside CaptureProcessingQueue.
            closeBurstFrames()
            performanceTracker.persistJsonLine(
                context = context,
                status = "FAILED",
                failureReason = "queue_submission_rejected"
            )
            finalizeRejectedBeforeAsyncOwnership("queue_submission_rejected", "MULTI_FRAME_QUEUE_SUBMISSION_REJECTED")
            return@withContext com.bncam.core.output.CaptureSubmissionResult.Rejected("queue_submission_rejected")
        }

        return@withContext com.bncam.core.output.CaptureSubmissionResult.Submitted(
            attemptId = plan.route.id,
            workId = reservation.workId,
            temporaryPreviewPath = temporaryPreviewPath
        )
    }

    private fun buildNativeFrameAnalysisEntries(
        burstFrames: List<ZslFramePair>,
        shutterTimestampNs: Long,
        shutterTimestampDomain: String,
        activeBufferFormatLabel: String,
        selectedAnchorIndex: Int,
        framesMerged: Int,
        supportRejected: Int,
        width: Int,
        height: Int,
        analysisSource: String
    ): List<FrameAnalysisDebugEntry> {
        if (burstFrames.isEmpty()) return emptyList()

        val acceptedSupportBudget = (framesMerged - 1).coerceAtLeast(0)
        var acceptedSupportUsed = 0
        val rejectedStartBudget = supportRejected.coerceAtLeast(0)

        return burstFrames.mapIndexed { index, frame ->
            val metadata = frame.metadata
            val metadataTimestampNs = metadata?.get(CaptureResult.SENSOR_TIMESTAMP) ?: 0L
            val timestampNs = if (metadataTimestampNs > 0L) metadataTimestampNs else frame.timestamp
            val exposureTimeNs = metadata?.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: 0L
            val iso = metadata?.get(CaptureResult.SENSOR_SENSITIVITY) ?: 0
            val sensorComparable = shutterTimestampDomain == "SENSOR_TIMESTAMP" ||
                    (shutterTimestampDomain.startsWith("ELAPSED_REALTIME") &&
                            frame.sensorTimestampComparableToElapsedRealtime)
            val completionElapsedNs = frame.pairCompleteElapsedNs.takeIf { it > 0L }
                ?: maxOf(frame.imageArrivalElapsedNs, frame.metadataArrivalElapsedNs)
            val deltaMs = when {
                sensorComparable && timestampNs > 0L ->
                    (timestampNs - shutterTimestampNs) / 1_000_000.0
                shutterTimestampDomain.startsWith("ELAPSED_REALTIME") && completionElapsedNs > 0L ->
                    (completionElapsedNs - shutterTimestampNs) / 1_000_000.0
                else -> 0.0
            }
            val shutterRelation = when {
                sensorComparable && timestampNs > 0L ->
                    if (timestampNs < shutterTimestampNs) "PRE_SHUTTER" else "POST_SHUTTER"
                shutterTimestampDomain.startsWith("ELAPSED_REALTIME") && completionElapsedNs > 0L ->
                    if (completionElapsedNs <= shutterTimestampNs) "PRE_SHUTTER_PAIR_COMPLETION_PROXY" else "POST_SHUTTER_PAIR_COMPLETION_PROXY"
                else -> "CLOCK_DOMAIN_UNAVAILABLE"
            }
            val candidateAeState = metadata?.get(CaptureResult.CONTROL_AE_STATE) ?: -1
            val candidateAwbState = metadata?.get(CaptureResult.CONTROL_AWB_STATE) ?: -1
            val candidateFocusState = metadata?.get(CaptureResult.CONTROL_AF_STATE) ?: -1
            val metadataComplete = metadata != null &&
                    metadataTimestampNs > 0L &&
                    exposureTimeNs > 0L &&
                    iso > 0
            val scoreComponentsUsed = buildList {
                add(if (metadataTimestampNs > 0L) "sync_from_sensor_timestamp" else if (frame.timestamp > 0L) "sync_from_frame_timestamp" else "sync_unavailable")
                add(if (exposureTimeNs > 0L) "motion_from_exposure_time" else "motion_default_no_exposure")
                add(if (exposureTimeNs > 0L && iso > 0) "ev_from_exposure_iso" else "ev_default_metadata_incomplete")
                add("sharpness_proxy")
                add("alignability_proxy")
                if (candidateAeState >= 0) add("ae_state") else add("ae_state_unavailable")
                if (candidateAwbState >= 0) add("awb_state") else add("awb_state_unavailable")
                if (candidateFocusState >= 0) add("focus_state") else add("focus_state_unavailable")
            }.joinToString(",")
            val syncScore = syncScore(deltaMs)
            val motionScore = motionScore(exposureTimeNs)
            val evScore = evProxyScore(exposureTimeNs, iso)
            val sharpnessScore = sharpnessProxyScore(motionScore, syncScore)
            val alignScore = alignabilityProxyScore(syncScore, index == selectedAnchorIndex)
            val overall = ((sharpnessScore * 0.30) + (motionScore * 0.20) + (evScore * 0.15) + (alignScore * 0.25) + (syncScore * 0.10)).coerceIn(0.0, 1.0)

            val isAnchor = index == selectedAnchorIndex
            val accepted = when {
                isAnchor && framesMerged > 0 -> true
                !isAnchor && acceptedSupportUsed < acceptedSupportBudget -> {
                    acceptedSupportUsed++
                    true
                }
                framesMerged <= 0 -> false
                else -> false
            }

            val rejectionReason = when {
                accepted -> "Accepted by native route"
                rejectedStartBudget > 0 -> "Rejected by native alignment/merge gate"
                framesMerged <= 0 -> "No native output was created"
                else -> "Not used by native merge"
            }

            val sensorSnapshot = frame.sensorMetadataSnapshot
            val sensorIdentity = sensorSnapshot?.sensorIdentity
            val frameIdentity = sensorSnapshot?.frameIdentityForRaw(frame.timestamp)

            FrameAnalysisDebugEntry(
                index = index,
                timestampNs = timestampNs,
                deltaToShutterMs = deltaMs,
                shutterRelation = shutterRelation,
                exposureTimeNs = exposureTimeNs,
                iso = iso,
                format = activeBufferFormatLabel,
                width = frame.hardwareBuffer?.width ?: width,
                height = frame.hardwareBuffer?.height ?: height,
                metadataValid = metadata != null && metadataTimestampNs > 0L,
                stale = false,
                duplicate = false,
                accepted = accepted,
                rejectionReason = rejectionReason,
                sharpnessScore = sharpnessScore,
                motionScore = motionScore,
                evScore = evScore,
                alignabilityScore = alignScore,
                syncScore = syncScore,
                temporalBiasContribution = 0.0,
                overallScore = overall,
                finalRank = index + 1,
                selectedAnchor = isAnchor,
                decisionReason = if (accepted) {
                    if (isAnchor) "Selected as anchor / base frame" else "Accepted as support frame"
                } else {
                    rejectionReason
                },
                analysisSource = analysisSource,
                candidateTimestampNs = timestampNs,
                candidateDeltaMs = deltaMs,
                candidateIso = iso,
                candidateExposureNs = exposureTimeNs,
                candidateAeState = candidateAeState,
                candidateAwbState = candidateAwbState,
                candidateFocusState = candidateFocusState,
                candidateMetadataComplete = metadataComplete,
                scoreComponentsUsed = scoreComponentsUsed,
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
                rawProcessingSafe = frameIdentity?.safeForRawProcessing ?: false,
                sensorAuthorityStatus = frameIdentity?.rejectionReason() ?: "UNAVAILABLE"
            )
        }
    }

    private fun syncScore(deltaMs: Double): Double = max(0.0, 1.0 - (abs(deltaMs) / 250.0)).coerceIn(0.0, 1.0)

    private fun motionScore(exposureTimeNs: Long): Double {
        if (exposureTimeNs <= 0L) return 0.50
        val exposureMs = exposureTimeNs / 1_000_000.0
        return (1.0 - (exposureMs / 80.0)).coerceIn(0.05, 1.0)
    }

    private fun evProxyScore(exposureTimeNs: Long, iso: Int): Double {
        if (exposureTimeNs <= 0L || iso <= 0) return 0.50
        val exposureMs = exposureTimeNs / 1_000_000.0
        val brightnessProxy = exposureMs * (iso / 100.0)
        return when {
            brightnessProxy < 2.0 -> 0.35
            brightnessProxy > 120.0 -> 0.55
            else -> 0.80
        }
    }

    private fun sharpnessProxyScore(motionScore: Double, syncScore: Double): Double {
        return (0.45 + (motionScore * 0.35) + (syncScore * 0.20)).coerceIn(0.0, 1.0)
    }

    private fun alignabilityProxyScore(syncScore: Double, isAnchor: Boolean): Double {
        return if (isAnchor) 1.0 else (0.35 + (syncScore * 0.65)).coerceIn(0.0, 1.0)
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

    private fun intStat(stats: Map<String, String>, key: String, defaultValue: Int): Int {
        return stats[key]?.toIntOrNull() ?: defaultValue
    }

    private fun boolStat(stats: Map<String, String>, key: String, defaultValue: Boolean): Boolean {
        return when (stats[key]?.lowercase(Locale.US)) {
            "true" -> true
            "false" -> false
            else -> defaultValue
        }
    }

    private fun prettyStatKey(key: String): String {
        return key.replace(Regex("([a-z])([A-Z])"), "$1 $2")
            .replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.US) else it.toString() }
    }

    private fun bufferToByteArraySafe(buffer: ByteBuffer): ByteArray? {
        return try {
            val duplicate = buffer.duplicate()
            duplicate.position(0)
            val bytes = ByteArray(duplicate.remaining())
            duplicate.get(bytes)
            bytes
        } catch (e: OutOfMemoryError) {
            null
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

    private fun getExifOrientation(rotationDegrees: Int): Int = when (rotationDegrees) { 0 -> 1; 90 -> 6; 180 -> 3; 270 -> 8; else -> 1 }

    private fun prepareJpegForSave(
        jpegBytes: ByteArray,
        tempName: String,
        metadata: CaptureResult,
        orientation: Int,
        lensName: String,
        captureMode: String,
        frameCount: Int,
        watermarkEnabled: Boolean,
        watermarkStyle: String,
        watermarkSignature: String,
        watermarkAuthor: Boolean,
        exifSaveSignature: Boolean,
        exifExtraData: Boolean,
        saveLocationData: Boolean
    ): ByteArray {
        val exposureNs = metadata.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: 0L
        val exposureSeconds = exposureNs / 1_000_000_000.0
        val shutterText = when {
            exposureSeconds <= 0.0 -> ""
            exposureSeconds < 1.0 -> "1/${kotlin.math.round(1.0 / exposureSeconds).toInt()} s"
            else -> String.format(Locale.US, "%.2f s", exposureSeconds)
        }
        val iso = metadata.get(CaptureResult.SENSOR_SENSITIVITY) ?: 0
        val aperture = metadata.get(CaptureResult.LENS_APERTURE) ?: 0f
        val watermarked = WatermarkEngine.applyWatermark(
            context,
            jpegBytes,
            WatermarkConfig(
                enabled = watermarkEnabled,
                style = watermarkStyle,
                signature = watermarkSignature,
                addAuthorTopRight = watermarkAuthor,
                deviceModel = Build.MODEL,
                sensorName = lensName,
                fov = "",
                aperture = if (aperture > 0f) "f/$aperture" else "",
                shutterSpeed = shutterText,
                iso = if (iso > 0) "ISO $iso" else "",
                captureMode = captureMode,
                frameCountInfo = "$frameCount Frames"
            )
        )

        val tempFile = java.io.File(context.cacheDir, "${tempName}_exif.jpg")
        try {
            tempFile.writeBytes(watermarked)
            val exif = androidx.exifinterface.media.ExifInterface(tempFile.absolutePath)
            exif.setAttribute(androidx.exifinterface.media.ExifInterface.TAG_MAKE, Build.MANUFACTURER)
            exif.setAttribute(androidx.exifinterface.media.ExifInterface.TAG_MODEL, Build.MODEL)
            exif.setAttribute(androidx.exifinterface.media.ExifInterface.TAG_SOFTWARE, "BnCam Pro")
            exif.setAttribute(androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION, orientation.toString())
            if (iso > 0) {
                exif.setAttribute(androidx.exifinterface.media.ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, iso.toString())
            }
            if (exposureSeconds > 0.0) {
                exif.setAttribute(androidx.exifinterface.media.ExifInterface.TAG_EXPOSURE_TIME, exposureSeconds.toString())
            }
            metadata.get(CaptureResult.LENS_FOCAL_LENGTH)?.takeIf { it > 0f }?.let {
                exif.setAttribute(androidx.exifinterface.media.ExifInterface.TAG_FOCAL_LENGTH, "${(it * 1000).toInt()}/1000")
            }
            if (aperture > 0f) {
                exif.setAttribute(androidx.exifinterface.media.ExifInterface.TAG_F_NUMBER, aperture.toString())
            }
            if (exifSaveSignature && watermarkSignature.isNotBlank()) {
                exif.setAttribute(androidx.exifinterface.media.ExifInterface.TAG_ARTIST, watermarkSignature)
                exif.setAttribute(androidx.exifinterface.media.ExifInterface.TAG_COPYRIGHT, watermarkSignature)
            }
            val description = if (exifExtraData) {
                "BnCam | Lens: $lensName | Mode: $captureMode | Frames: $frameCount"
            } else {
                "BnCam"
            }
            exif.setAttribute(androidx.exifinterface.media.ExifInterface.TAG_IMAGE_DESCRIPTION, description)
            if (saveLocationData) LocationUtils.getLastKnownLocation(context)?.let(exif::setGpsInfo)
            exif.saveAttributes()
            return tempFile.readBytes()
        } finally {
            tempFile.delete()
        }
    }

    private fun reserveMediaStoreItem(filename: String, saveLocation: String, mimeType: String): Uri? {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val safeLocation = if (saveLocation.startsWith("Pictures/") || saveLocation.startsWith("DCIM/")) saveLocation else "Pictures/$saveLocation"
                put(MediaStore.MediaColumns.RELATIVE_PATH, safeLocation)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }
        return try {
            context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        } catch (t: Throwable) {
            Log.e(tag, "MediaStore reservation failed filename=$filename mimeType=$mimeType location=$saveLocation", t)
            null
        }
    }

    private suspend fun writeBytesToReservedUri(uri: Uri, bytes: ByteArray) = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        resolver.openOutputStream(uri)?.use { it.write(bytes) }
            ?: throw IllegalStateException("Could not open reserved JPEG output stream.")
        publishReservedUri(uri)
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
        rawDomainContract: com.bncam.core.isp.raw.RawDomainContract?
    ) = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val bytesWritten = resolver.openOutputStream(uri)?.use { output ->
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
                outputStream = output
            )
        } ?: 0L
        if (bytesWritten <= 0L) throw IllegalStateException("DNG writer produced no bytes.")
        publishReservedUri(uri)
    }

    private fun publishReservedUri(uri: Uri) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val published = context.contentResolver.update(
            uri,
            ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
            null,
            null
        ) > 0
        if (!published) throw IllegalStateException("MediaStore bytes were written but publication failed.")
    }

}
