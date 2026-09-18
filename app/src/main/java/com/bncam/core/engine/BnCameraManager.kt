package com.bncam.core.engine

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Rect
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.Face
import android.hardware.camera2.params.MeteringRectangle
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.hardware.camera2.params.TonemapCurve
import android.media.ImageReader
import android.media.MediaActionSound
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import android.view.WindowManager
import com.bncam.core.buffer.FrameRingBuffer
import com.bncam.core.buffer.CaptureBufferBudget
import com.bncam.core.buffer.NearZslEligibilityPolicy
import com.bncam.core.capture.CameraExposurePolicy
import com.bncam.core.capture.AfCoordinateMapper
import com.bncam.core.capture.ExposureStatistics
import com.bncam.core.capture.LiveRgbHistogram
import com.bncam.core.capture.CameraMeteringPolicy
import com.bncam.core.capture.ExposureBounds
import com.bncam.core.capture.ExposurePlan
import com.bncam.core.capture.CaptureExposurePreferences
import com.bncam.core.capture.ProfileExposurePriorityPlan
import com.bncam.core.capture.ProfileExposurePriorityPlanner
import com.bncam.core.capture.DefaultRawShutterPriorityPlan
import com.bncam.core.capture.DefaultRawShutterPriorityPolicy
import com.bncam.core.capture.DefaultRawManualFallbackPlan
import com.bncam.core.capture.DefaultRawShutterManualFallbackPolicy
import com.bncam.core.capture.DefaultRawExposureRoute
import com.bncam.core.capture.DefaultRawExposureRealizationTruth
import com.bncam.core.capture.DefaultRawExposureRealizationEvaluator
import com.bncam.core.capture.DefaultRawApi36AuthorityAction
import com.bncam.core.capture.DefaultRawApi36AuthorityTracker
import com.bncam.core.capture.DefaultRawApi36RoutePolicy
import com.bncam.core.capture.DefaultRawPhotometricConvergenceSnapshot
import com.bncam.core.capture.DefaultRawPhotometricConvergenceTracker
import com.bncam.core.capture.DefaultRawMeteringSnapshot
import com.bncam.core.capture.DefaultRawMeteringTracker
import com.bncam.core.capture.DefaultRawAeReferenceGate
import com.bncam.core.capture.DefaultRawTargetContinuity
import com.bncam.core.capture.DefaultRawFinalExposureTarget
import com.bncam.core.capture.DefaultRawExposureTargetModel
import com.bncam.core.capture.DefaultRawExposureAllocation
import com.bncam.core.capture.DefaultRawExposureAllocator
import com.bncam.core.capture.RawMotionMeasurement
import com.bncam.core.capture.RawPreviewMotionMeter
import com.bncam.core.capture.WarmRawMotionSampler
import com.bncam.core.capture.RawShutterSafetyCeilings
import com.bncam.core.capture.RawFlickerConstraint
import com.bncam.core.capture.RawFlickerFrequency
import com.bncam.core.capture.RawFlickerObservation
import com.bncam.core.capture.RawFlickerStabilitySnapshot
import com.bncam.core.capture.RawFlickerStabilityTracker
import com.bncam.core.capture.FlickerFpsRange
import com.bncam.core.capture.RawFlickerCadencePolicy
import com.bncam.core.capture.PreviewFlickerAuthorityPolicy
import com.bncam.core.capture.DynamicSensorProfile
import com.bncam.core.capture.MeteringMode
import com.bncam.core.capture.MeteringPlan
import com.bncam.core.capture.NormalizedMeteringRegion
import com.bncam.core.capture.NormalizedPoint
import com.bncam.core.capture.FrameGenerationId
import com.bncam.core.capture.CaptureReadinessGate
import com.bncam.core.capture.CapturePreviewContinuityTracker
import com.bncam.core.capture.CaptureAttemptContext
import com.bncam.core.capture.CaptureAttemptCoordinator
import com.bncam.core.capture.CaptureAttemptResult
import com.bncam.core.capture.CloseOnce
import com.bncam.core.capture.RepeatedCaptureShotResult
import com.bncam.core.capture.RepeatedCaptureValidator
import com.bncam.core.capture.ReadinessState
import com.bncam.core.capture.WarmBufferReadinessPolicy
import com.bncam.core.capture.WarmBufferReadinessRequirement
import com.bncam.core.capture.NearZslAnchorAdmissionPolicy
import com.bncam.core.capture.ZslCaptureCandidateEvidence
import com.bncam.core.capture.ZslCaptureCandidateRolePolicy
import com.bncam.core.capture.CaptureCapabilities
import com.bncam.core.capture.CaptureMode
import com.bncam.core.capture.NightCapturePlan
import com.bncam.core.capture.NightCapturePolicy
import com.bncam.core.capture.ViewfinderMode
import com.bncam.core.capture.CaptureRequestPlan
import com.bncam.core.capture.CaptureRoutePlanner
import com.bncam.core.capture.CaptureOutputPolicyResolver
import com.bncam.core.capture.OutputRotationResolver
import com.bncam.core.capture.CameraRequestSubmissionType
import com.bncam.core.capture.ControlRequestEpochTracker
import com.bncam.core.capture.ControlRequestState
import com.bncam.core.capture.ImmutableMeteringRegionSnapshot
import com.bncam.core.capture.ImmutableRectSnapshot
import com.bncam.core.capture.PreparedControlRequest
import com.bncam.core.capture.FrameOrigin
import com.bncam.core.capture.PerformanceDebugPolicy
import com.bncam.core.capture.HdrAeCompensationBounds
import com.bncam.core.capture.HdrBracketCaptureContext
import com.bncam.core.capture.HdrCapturedFrame
import com.bncam.core.capture.HdrExposureBracketPlanner
import com.bncam.core.capture.HdrExposureControlMode
import com.bncam.core.capture.HdrManualSensorBounds
import com.bncam.core.debug.ShotLogger
import com.bncam.core.debug.RuntimeDiagnosticDomain
import com.bncam.core.debug.UnifiedRuntimeDiagnosticsSnapshot
import com.bncam.core.quality.RenderQualityConfig
import com.bncam.core.quality.PhysicalSensorProfileRegistry
import com.bncam.core.quality.FrameSensorMetadataSnapshot
import com.bncam.core.quality.RawCalibrationRole
import com.bncam.core.quality.SizeSnapshot
import com.bncam.core.isp.raw.RawBlackDomainBinding
import com.bncam.core.isp.raw.RawDomainContractResolver
import com.bncam.core.isp.raw.RawWhiteDomainBinding
import com.bncam.core.isp.raw10.RawCameraColorProfileRepository
import com.bncam.core.quality.RawColorTransformEngine
import com.bncam.core.quality.ProfileYuvAwbMapper
import com.bncam.core.quality.StableWhiteBalanceSnapshot
import com.bncam.core.quality.WhiteBalanceConvergence
import com.bncam.core.quality.WhiteBalanceStateEngine
import com.bncam.core.runtime.RawPipelineRuntimeOwner
import com.bncam.core.runtime.RawPreviewAnalysisDemand
import com.bncam.core.runtime.RawPreviewFastPathPolicy
import com.bncam.core.runtime.RawPreviewProducerAuthorityTracker
import com.bncam.core.runtime.RawPreviewProducerKind
import com.bncam.data.profile.CameraProfile
import com.bncam.data.settings.SettingsRepository
import com.bncam.data.settings.CaptureSettingKeys
import com.bncam.data.settings.ProfileAwbModels
import com.bncam.data.settings.ProfileAwbModes
import com.bncam.data.settings.ProfileAwbSettings
import com.bncam.data.settings.parseCameraFormatCode
import com.bncam.data.settings.rawPreviewFormatCompatibility
import com.bncam.data.settings.RawPreviewFormatCompatibility
import com.bncam.data.settings.VendorTagTarget
import com.bncam.vendor.DynamicVendorTag
import com.bncam.vendor.VendorInjectionEngine
import com.bncam.vendor.VendorRequestStage
import com.bncam.vendor.VendorScanner
import com.bncam.ui.screens.capture.RawPreviewFrame
import com.bncam.ui.screens.capture.RawPreviewRenderConfig
import com.bncam.ui.screens.capture.RawPreviewRenderer
import com.bncam.ui.screens.capture.RawPreviewCalibrationTransform
import com.bncam.ui.screens.capture.RawPreviewCadenceDiagnostics
import com.bncam.ui.screens.capture.ViewfinderEffectiveSource
import com.bncam.ui.screens.capture.ViewfinderStream
import com.bncam.ui.screens.capture.resolveEffectiveViewfinderSource
import com.bncam.core.quality.DefaultIspProfile
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.IdentityHashMap
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

private const val RAW_PREVIEW_CONFIG_REFRESH_MS = 1_500L
private const val SESSION_TRANSITION_TIMEOUT_MS = 2_500L
private const val CAMERA_HARD_CLOSE_RECOVERY_TIMEOUT_MS = 1_500L
private const val CAMERA_HARD_START_READY_TIMEOUT_MS = 3_500L
private const val DIRECT_CAMERA_PROBE_TIMEOUT_MS = 3_500L

private enum class PipelineTransitionState {
    PREVIEW_ATTACHED,
    STARTING,
    RECONFIGURING,
    LENS_SWITCHING,
    CLOSING,
    CLOSED
}

enum class CameraEngineState {
    CAMERA_STARTING, PREVIEW_STARTING, PREVIEW_STABLE, PROCESSING_STREAM_RECONFIGURING,
    BUFFER_WARMING, BUFFER_READY, CAPTURE_READY, CAPTURING, ERROR
}

private fun physicalCaptureResultOrNull(
    result: TotalCaptureResult,
    physicalCameraId: String?
): CaptureResult? {
    if (physicalCameraId.isNullOrBlank()) return null
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        result.physicalCameraTotalResults[physicalCameraId]
    } else {
        @Suppress("DEPRECATION")
        result.physicalCameraResults[physicalCameraId]
    }
}

data class LiveWhiteBalanceDisplayCompensation(
    val red: Float = 1f,
    val green: Float = 1f,
    val blue: Float = 1f,
    val active: Boolean = false
)

data class FocusPeakingGuidance(
    val focusConfidence: Float = 0f,
    val confidenceState: com.bncam.core.quality.FocusConfidenceState =
        com.bncam.core.quality.FocusConfidenceState.INDETERMINATE,
    val afState: Int = CaptureResult.CONTROL_AF_STATE_INACTIVE,
    val lensState: Int = CaptureResult.LENS_STATE_STATIONARY,
    val afRegion: Rect? = null,
    val coordinateBounds: Rect? = null,
    val subjectRoiUsed: Boolean = false,
    val timestampNs: Long = 0L
)

data class LensInfo(
    val id: String,
    val name: String,
    val facing: Int,
    val isLogicalMultiCamera: Boolean,
    val listTitle: String = name,
    val listDescription: String = "",
    val isInjected: Boolean = false,
    val opticalZoomRatio: Float = 1f,
    val equivalentFocalLength35mm: Float? = null,
    val sensorOrientationDegrees: Int = 0,
    val yuvPreviewOrientationCorrectionDegrees: Int = 0
)

data class PipelineIdentity(
    val selectedLensId: String,
    val requestedProfileId: String,
    val requestedFrameSource: String,
    val effectiveFrameSource: String,
    val bufferFormat: Int,
    val logicalCameraId: String,
    val physicalCameraId: String?,
    val cameraRouteKind: CameraRouteKind,
    val lensRole: String?,
    val backendRoute: String,
    val width: Int,
    val height: Int,
    val maxImages: Int,
    val rawPreviewBindingSignature: String = "none",
    val rawPreviewFormatCode: Int? = null,
    val vendorConfigSignature: String = "none",
    val vendorSessionRebuildRequired: Boolean = false
) {
    /**
     * Compares the canonical near-ZSL producer while deliberately ignoring the optional
     * custom RAW preview output. A custom preview-code change needs a new Camera2 session,
     * but it must not throw away CameraDevice/ImageReader/ring-buffer ownership.
     */
    fun sameWarmProducerAs(other: PipelineIdentity): Boolean {
        return effectiveFrameSource == other.effectiveFrameSource &&
                bufferFormat == other.bufferFormat &&
                logicalCameraId == other.logicalCameraId &&
                physicalCameraId == other.physicalCameraId &&
                cameraRouteKind == other.cameraRouteKind &&
                lensRole == other.lensRole &&
                backendRoute == other.backendRoute &&
                width == other.width &&
                height == other.height &&
                maxImages == other.maxImages &&
                vendorConfigSignature == other.vendorConfigSignature
    }
}

data class PipelineResetDecision(
    val required: Boolean,
    val reasons: List<String>,
    val previous: PipelineIdentity?,
    val requested: PipelineIdentity
)

data class ViewfinderRebuildVisualState(
    val active: Boolean = false,
    val generation: Int = -1,
    val reason: String = "idle"
)

private data class PendingPipelineResetRequest(
    val previewSurface: Surface,
    val newFormat: String,
    val profileId: String,
    val forceSessionRebuild: Boolean,
    val reason: String
)

private data class DeferredSelectionExposureConstraintUpdate(
    val exposureTargetNs: Long?,
    val source: String,
    val isoTarget: Int?
)

private data class NearZslSingleAnchorReservation(
    val candidate: FrameRingBuffer.LeasedCandidate,
    val temporalClass: String,
    val effectiveShutterTimestampNs: Long,
    val effectiveShutterTimestampDomain: String,
    val waitMs: Double,
    val physicalAgeAtUserShutterMs: Double?
)


private data class VendorOperationModeProbeState(
    val lensId: String,
    val featureSignature: String,
    val candidateIndex: Int,
    val sessionType: Int,
    val sessionTypeLabel: String,
    val watchedTags: List<com.bncam.data.settings.VendorTagConfig>,
    var framesObserved: Int = 0,
    var matchedKey: String = ""
)

private data class BufferFrameAnalysisRequest(
    val generation: Int,
    val expectedFormat: Int
)

private data class YuvFrameAnalysisRequest(
    val timestampNs: Long,
    val generation: Int,
    val expectedFormat: Int,
    val deviceRotation: Int
)

class BnCameraManager(private val context: Context) {
    companion object {
        @Volatile var activeInstance: BnCameraManager? = null
    }

    private val managerShutdownRequested = java.util.concurrent.atomic.AtomicBoolean(false)
    private val managerShutdownFinalized = java.util.concurrent.atomic.AtomicBoolean(false)
    private val owningLifecycle = context as? LifecycleOwner
    private val managerLifecycleObserver = object : DefaultLifecycleObserver {
        override fun onDestroy(owner: LifecycleOwner) {
            shutdown("ACTIVITY_DESTROY")
        }
    }

    init {
        activeInstance = this
        owningLifecycle?.lifecycle?.addObserver(managerLifecycleObserver)
        RawPreviewCadenceDiagnostics.initialize(context)
        com.bncam.core.debug.RawPreviewFirstActivationTrace.initialize(context)
        com.bncam.core.debug.DeviceTelemetryLogger.initialize(context)
        com.bncam.core.debug.AfGroundTruthTrace.initialize(context)
        RawCameraColorProfileRepository.beginSession(context.applicationContext)
        com.bncam.core.debug.DiagnosticsAggregator.record(
            stream = com.bncam.core.debug.DiagnosticsAggregator.Stream.CAPTURE,
            scope = "SESSION",
            section = "RAW COLOR PROFILE SESSION",
            content = RawCameraColorProfileRepository.debugSummary()
        )
    }

    private val cameraCharacteristicsCache = java.util.concurrent.ConcurrentHashMap<String, CameraCharacteristics>()
    fun getCachedCameraCharacteristics(cameraId: String): CameraCharacteristics =
        cameraCharacteristicsCache.computeIfAbsent(cameraId) { cameraManager.getCameraCharacteristics(it) }

    private fun traceCaptureRuntime(message: String) {
        if ((context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) == 0) {
            return
        }
        com.bncam.core.debug.DiagnosticsAggregator.record(
            stream = com.bncam.core.debug.DiagnosticsAggregator.Stream.CAPTURE,
            scope = "SESSION",
            section = "CAPTURE RUNTIME",
            content = "${android.os.SystemClock.elapsedRealtimeNanos()} $message"
        )
    }

    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val publicCameraIds: Set<String> by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        cameraManager.cameraIdList.toSet()
    }
    private val sensorProfileRegistry = PhysicalSensorProfileRegistry(cameraManager)

    private fun frameSensorMetadataSnapshot(
        result: TotalCaptureResult,
        expectedGeneration: Int,
        fallbackLogicalCameraId: String
    ): FrameSensorMetadataSnapshot? {
        if (pipelineGeneration != expectedGeneration) return null
        val identity = synchronized(pipelineLock) { activePipelineIdentity }
        val logicalId = identity?.logicalCameraId ?: fallbackLogicalCameraId
        val physicalId = identity?.physicalCameraId
        val rawFrameSize = identity
            ?.takeIf { it.bufferFormat == ImageFormat.RAW10 || it.bufferFormat == ImageFormat.RAW_SENSOR }
            ?.let { SizeSnapshot(it.width, it.height) }
        return runCatching {
            sensorProfileRegistry.snapshotForFrame(
                logicalCameraId = logicalId,
                physicalCameraId = physicalId,
                result = result,
                rawFrameSize = rawFrameSize
            )
        }.onFailure { failure ->
            val reason = (failure as? com.bncam.core.quality.SensorAuthorityUnavailableException)
                ?.authorityReason ?: failure.javaClass.simpleName
            Log.w(
                "SensorProfileRegistry",
                "frame snapshot failed logical=$logicalId physical=${physicalId ?: "none"} " +
                    "generation=$expectedGeneration frame=${result.frameNumber} reason=$reason",
                failure
            )
            com.bncam.core.debug.DiagnosticsAggregator.record(
                stream = com.bncam.core.debug.DiagnosticsAggregator.Stream.CAPTURE,
                scope = "SESSION",
                section = "CAPTURE_REJECT_SENSOR_AUTHORITY",
                content = "reason=$reason;logical=$logicalId;physical=${physicalId ?: "STANDALONE"};" +
                    "generation=$expectedGeneration;frame=${result.frameNumber};sequence=${result.sequenceId}"
            )
        }.getOrNull()
    }

    /**
     * Freezes a compact, read-only cross-owner diagnostic view at shutter time. None of the
     * sources below is allowed to submit a request, recover a renderer, change calibration or
     * mutate capture policy. Missing evidence remains explicit instead of being synthesized.
     */
    private fun buildUnifiedRuntimeDiagnosticsSnapshot(
        capturePlan: CaptureRequestPlan,
        capabilities: CaptureCapabilities,
        sensorMetadata: FrameSensorMetadataSnapshot?
    ): UnifiedRuntimeDiagnosticsSnapshot {
        val rawCapture = capturePlan.frameOrigin != FrameOrigin.YUV

        val aeDomain = if (!rawCapture) {
            RuntimeDiagnosticDomain.of(
                "CAMERA2_HAL_OWNED",
                "owner" to "CAMERA2_HAL",
                "cameraAeState" to (lastAeState ?: "unavailable"),
                "rawControllerActive" to false,
                "policy" to "STANDARD_CAMERA2_AE_NO_BNCAM_RAW_FEEDBACK"
            )
        } else {
            val convergence = latestDefaultRawPhotometricConvergence
                ?.takeIf { it.generation == pipelineGeneration }
            val status = when {
                convergence == null -> "RAW_CONVERGENCE_UNAVAILABLE"
                convergence.photometricConverged -> "PHOTOMETRIC_CONVERGED"
                convergence.allocationReady -> "ALLOCATED_SETTLING"
                else -> "ALLOCATION_NOT_READY"
            }
            RuntimeDiagnosticDomain.of(
                status,
                "owner" to "BNCAM_RAW_EXPOSURE_POLICY",
                "route" to (convergence?.route ?: "unavailable"),
                "allocationReady" to (convergence?.allocationReady ?: defaultRawAllocationReady),
                "photometricConverged" to (convergence?.photometricConverged ?: false),
                "targetLuma" to convergence?.targetLuma,
                "observedLuma" to convergence?.observedLuma,
                "exposureErrorEv" to convergence?.exposureErrorEv,
                "realizationStatus" to convergence?.realizationStatus,
                "aeStable" to convergence?.aeStable,
                "reason" to (convergence?.reason ?: "no_generation_matched_convergence"),
                "framesSinceExposureRequest" to defaultRawFramesSinceExposureRequest
            )
        }

        val rawPreviewDomain = if (!rawCapture) {
            RuntimeDiagnosticDomain.of(
                "NOT_APPLICABLE_YUV",
                "owner" to "CAMERA2_YUV_PREVIEW",
                "captureOrigin" to capturePlan.frameOrigin
            )
        } else {
            val nowNs = android.os.SystemClock.elapsedRealtimeNanos()
            val health = com.bncam.ui.screens.capture.RawPreviewHealthMonitor.snapshot(nowNs)
            RuntimeDiagnosticDomain.of(
                health.stage.name,
                "source" to health.source,
                "healthGeneration" to health.pipelineGeneration,
                "eglGeneration" to health.eglGeneration,
                "expectedIntervalNs" to health.expectedIntervalNs,
                "stallThresholdNs" to health.stallThresholdNs,
                "actualPresentationObserved" to health.actualPresentationObserved,
                "rgbMean" to health.outputRgbMean,
                "lastRecoveryReason" to health.lastRecoveryReason,
                "producerAuthority" to
                    rawPreviewProducerAuthorityTracker.diagnosticSummary(pipelineGeneration),
                "renderer" to rawPreviewRenderer.runtimeDiagnosticsSummary()
            )
        }

        val calibrationDomain = if (!rawCapture) {
            RuntimeDiagnosticDomain.of(
                "NOT_APPLICABLE_YUV",
                "reason" to "RAW_SENSOR_CALIBRATION_NOT_USED_BY_YUV_ROUTE"
            )
        } else if (sensorMetadata == null) {
            RuntimeDiagnosticDomain.unavailable("EXACT_CAPTURE_FRAME_SENSOR_METADATA_NOT_FROZEN_YET")
        } else {
            val ownership = sensorMetadata.calibrationOwnership
            val status = when {
                !ownership.sourceAuthorityCoherent -> "SENSOR_AUTHORITY_REJECTED"
                ownership.mandatoryRawNormalizationReady && ownership.calibratedColorReady ->
                    "RAW_AND_COLOR_CALIBRATED"
                ownership.mandatoryRawNormalizationReady -> "RAW_NORMALIZATION_READY"
                else -> "RAW_NORMALIZATION_REJECTED"
            }
            RuntimeDiagnosticDomain.of(
                status,
                "sensorAuthorityId" to ownership.sensorAuthorityId,
                "sourceAuthorityCoherent" to ownership.sourceAuthorityCoherent,
                "sourceAuthorityReason" to ownership.sourceAuthorityReason,
                "mandatoryRawReady" to ownership.mandatoryRawNormalizationReady,
                "mandatoryRejectionReason" to ownership.mandatoryRejectionReason,
                "calibratedColorReady" to ownership.calibratedColorReady,
                "cfaAuthority" to ownership.role(RawCalibrationRole.CFA).authority.name,
                "blackAuthority" to ownership.role(RawCalibrationRole.BLACK_LEVEL).authority.name,
                "whiteAuthority" to ownership.role(RawCalibrationRole.WHITE_LEVEL).authority.name,
                "shadingAuthority" to ownership.role(RawCalibrationRole.LENS_SHADING).authority.name,
                "wbAuthority" to ownership.role(RawCalibrationRole.WHITE_BALANCE).authority.name,
                "colorAuthority" to ownership.role(RawCalibrationRole.COLOR_TRANSFORM).authority.name,
                "coreRawMetadataStatus" to sensorMetadata.coreRawMetadataStatus,
                "logicalFallbackUsed" to sensorMetadata.logicalMetadataFallbackUsed,
                "foreignMetadataUsed" to sensorMetadata.foreignSensorMetadataUsed
            )
        }

        val noiseDomain = if (!rawCapture) {
            RuntimeDiagnosticDomain.of(
                "NOT_APPLICABLE_YUV",
                "reason" to "RAW_PHYSICAL_NOISE_MODEL_NOT_USED_BY_YUV_ROUTE"
            )
        } else if (sensorMetadata == null) {
            RuntimeDiagnosticDomain.unavailable("EXACT_CAPTURE_FRAME_NOISE_METADATA_NOT_FROZEN_YET")
        } else {
            RuntimeDiagnosticDomain.of(
                if (sensorMetadata.hasPhysicalNoiseModel) "PHYSICAL_SO_AVAILABLE" else "PHYSICAL_SO_UNAVAILABLE",
                "authorityContract" to "PHYSICAL_SO_PRIMARY_ISO_FALLBACK_NO_LENS_ID_STRENGTH_SHORTCUT",
                "physicalSoAvailable" to sensorMetadata.hasPhysicalNoiseModel,
                "soSource" to sensorMetadata.noiseProfileSoField.source,
                "soValidity" to sensorMetadata.noiseProfileSoField.validity.name,
                "iso" to sensorMetadata.sensitivityIso,
                "exposureTimeNs" to sensorMetadata.exposureTimeNs,
                "postRawSensitivityBoost" to sensorMetadata.postRawSensitivityBoost,
                "postRawBoostAffectsRawNoiseEvidence" to false,
                "processingConfidence" to "CONFIRMED_LATER_IN_NOISE_MODEL_TRACE"
            )
        }

        val capabilityDomain = RuntimeDiagnosticDomain.of(
            if (capabilities.supports(capturePlan.frameOrigin)) "ROUTE_SUPPORTED" else "ROUTE_UNSUPPORTED",
            "yuv" to capabilities.yuv,
            "raw10" to capabilities.raw10,
            "rawSensor" to capabilities.rawSensor,
            "camera2RawCapability" to capabilities.camera2RawCapability,
            "selectedOrigin" to capturePlan.frameOrigin,
            "resolvedRoute" to capturePlan.route.id,
            "captureImplementation" to "CaptureRoutePlanner",
            "tuningAuthority" to "NONE_FROM_CAPABILITY_FACTS"
        )

        return UnifiedRuntimeDiagnosticsSnapshot(
            generation = pipelineGeneration,
            captureRoute = capturePlan.route.id,
            ae = aeDomain,
            rawPreview = rawPreviewDomain,
            calibration = calibrationDomain,
            noise = noiseDomain,
            capability = capabilityDomain
        )
    }

    /**
     * Hidden camera IDs are qualified once per manager runtime. A successful direct probe remains
     * direct for the process lifetime; a real direct-open failure remains on the logical/physical
     * fallback. Public IDs never enter this cache because CameraManager already qualifies them.
     */
    private val hiddenCameraRouteQualifications = ConcurrentHashMap<String, CameraRouteKind>()
    @Volatile private var previewOrientationCorrectionDegrees: Int = 0
    private val closingCameraDeviceCount = java.util.concurrent.atomic.AtomicInteger(0)
    private val openingCameraDeviceCount = java.util.concurrent.atomic.AtomicInteger(0)
    private val cameraDeviceLifecycleLock = Any()
    @Volatile private var pendingCameraOpenGeneration: Int = -1
    @Volatile private var pendingCameraOpenSettled: CompletableDeferred<Unit>? = null
    private val cameraDeviceCloseBarriers = IdentityHashMap<CameraDevice, CompletableDeferred<Unit>>()

    suspend fun awaitCameraHardwareClosed(maxWaitMs: Long = 150L): Boolean {
        if (closingCameraDeviceCount.get() <= 0 && openingCameraDeviceCount.get() <= 0) return true
        val deadline = android.os.SystemClock.elapsedRealtime() + maxWaitMs.coerceAtLeast(0L)
        while ((closingCameraDeviceCount.get() > 0 || openingCameraDeviceCount.get() > 0) &&
            android.os.SystemClock.elapsedRealtime() < deadline
        ) {
            delay(5L)
        }
        val settled = closingCameraDeviceCount.get() <= 0 && openingCameraDeviceCount.get() <= 0
        if (!settled) {
            Log.e(
                tag,
                "Camera hardware barrier timed out opening=${openingCameraDeviceCount.get()} " +
                    "closing=${closingCameraDeviceCount.get()}; awaiting real lifecycle callbacks."
            )
        }
        return settled
    }

    private fun beginCameraOpenRequest(generation: Int) {
        synchronized(cameraDeviceLifecycleLock) {
            pendingCameraOpenGeneration = generation
            pendingCameraOpenSettled = CompletableDeferred()
            openingCameraDeviceCount.incrementAndGet()
        }
    }

    private fun settleCameraOpenRequest(generation: Int) {
        synchronized(cameraDeviceLifecycleLock) {
            if (pendingCameraOpenGeneration != generation) return
            pendingCameraOpenGeneration = -1
            openingCameraDeviceCount.updateAndGet { count -> (count - 1).coerceAtLeast(0) }
            pendingCameraOpenSettled?.complete(Unit)
            pendingCameraOpenSettled = null
        }
    }

    private data class CameraDeviceCloseTicket(
        val device: CameraDevice,
        val closeBarrier: CompletableDeferred<Unit>,
        val generation: Int,
        val reason: String
    )

    private fun cameraDeviceCloseBarrierFor(device: CameraDevice): CompletableDeferred<Unit> =
        synchronized(cameraDeviceLifecycleLock) {
            cameraDeviceCloseBarriers.getOrPut(device) { CompletableDeferred() }
        }

    private fun requestCameraDeviceClose(device: CameraDevice, reason: String): CameraDeviceCloseTicket {
        val barrier = cameraDeviceCloseBarrierFor(device)
        val ticket = CameraDeviceCloseTicket(device, barrier, pipelineGeneration, reason)
        lifetimeCameraCloseRequestCount.incrementAndGet()
        closingCameraDeviceCount.incrementAndGet()
        logCameraLifetimeCounters(
            event = "CAMERA_DEVICE_CLOSE_REQUEST",
            extra = "logical=${device.id} reason=$reason generation=${ticket.generation}"
        )
        try {
            device.close()
        } catch (closeFailure: Throwable) {
            synchronized(cameraDeviceLifecycleLock) {
                cameraDeviceCloseBarriers.remove(device)?.complete(Unit)
            }
            closingCameraDeviceCount.updateAndGet { count -> (count - 1).coerceAtLeast(0) }
            Log.w(tag, "CameraDevice.close failed before close acknowledgement reason=$reason", closeFailure)
        }
        return ticket
    }

    private suspend fun awaitCameraDeviceClosed(
        ticket: CameraDeviceCloseTicket,
        timeoutMs: Long = CAMERA_HARD_CLOSE_RECOVERY_TIMEOUT_MS
    ): Boolean {
        val closed = withTimeoutOrNull(timeoutMs.coerceAtLeast(0L)) {
            ticket.closeBarrier.await()
            true
        } ?: false
        if (!closed) {
            Log.e(
                tag,
                "CameraDevice onClosed acknowledgement timed out logical=${ticket.device.id} " +
                    "generation=${ticket.generation} reason=${ticket.reason}"
            )
        }
        return closed
    }

    private val tag = "BnCameraManager"
    private val previewDiagnosticsTag = "BnCamPreviewDiag"

    private fun logCameraLifetimeCounters(event: String, extra: String = "") {
        Log.i(
            previewDiagnosticsTag,
            "event=CAMERA_LIFETIME_COUNTS trigger=$event " +
                "hardStarts=${lifetimeHardStartCount.get()} " +
                "openRequests=${lifetimeCameraOpenRequestCount.get()} " +
                "opened=${lifetimeCameraOpenedCount.get()} " +
                "closeRequests=${lifetimeCameraCloseRequestCount.get()} " +
                "closedAck=${lifetimeCameraClosedAckCount.get()} " +
                "sessionRequests=${lifetimeSessionRequestCount.get()} " +
                "sessionsConfigured=${lifetimeSessionConfiguredCount.get()} " +
                "physicalHandovers=${lifetimePhysicalHandoverCount.get()} " + extra
        )
    }

    // Systeem-geluid voor de sluiter
    private val mediaActionSound = MediaActionSound().apply { load(MediaActionSound.SHUTTER_CLICK) }

    // ========================================================
    // ENGINE STATE & ZSL BUFFER
    // ========================================================
    var cameraDevice: CameraDevice? = null
    var captureSession: CameraCaptureSession? = null
    var imageReader: ImageReader? = null

    private data class CustomRawPreviewBinding(
        val source: ViewfinderEffectiveSource,
        val formatCode: Int,
        val width: Int,
        val height: Int,
        val generation: Int,
        val advertised: Boolean
    )

    private data class PendingViewfinderDisplayTransition(
        val source: ViewfinderEffectiveSource,
        val generation: Int,
        val requiresProducerFrame: Boolean,
        var producerFrameReady: Boolean = false,
        var firstProducerTimestampNs: Long = Long.MIN_VALUE
    )

    private var customRawPreviewReader: ImageReader? = null
    @Volatile private var customRawPreviewBinding: CustomRawPreviewBinding? = null
    @Volatile private var customRawPreviewDisabledGeneration: Int = -1
    // DELTA 0217A: a custom/vendor RAW preview output is preferred only after an exact frame from
    // the active generation has been PRESENTED by EGL. Renderer publication alone is not display
    // truth. Until presentation is proven the canonical warm RAW ring remains authoritative.
    @Volatile private var customRawPreviewPresentedReadyGeneration: Int = -1
    private val rawPreviewProducerAuthorityTracker = RawPreviewProducerAuthorityTracker()
    @Volatile private var customRawPreviewLastFrameElapsedNs: Long = 0L
    @Volatile private var customRawPreviewLastSensorTimestampNs: Long = 0L
    @Volatile private var customRawPreviewFrameCount: Long = 0L

    @Volatile
    var activeZslFormat: Int = ImageFormat.YUV_420_888

    @Volatile
    private var activePipelineIdentity: PipelineIdentity? = null

    @Volatile
    private var activeLensId: String? = null

    @Volatile
    private var staleFramesDropped: Int = 0

    @Volatile
    private var pipelineGeneration: Int = FrameGenerationId.get()

    @Volatile
    private var isPipelineResetting: Boolean = false

    @Volatile
    private var pipelineResetWorkerScheduled: Boolean = false

    @Volatile
    private var sessionConfiguredGeneration: Int = -1

    // A pipeline generation can intentionally survive an in-app navigation surface swap.
    // Track capture-session configuration separately so a late callback from an obsolete
    // same-generation session can never reclaim captureSession/current repeating ownership.
    @Volatile
    private var sessionConfigurationEpoch: Long = 0L

    // UI controls may only mutate the request builder owned by a session whose initial repeating
    // request is known to be active. A pending configuration epoch is not yet control ownership.
    @Volatile
    private var activeConfiguredSessionEpoch: Long = -1L

    // One sequence spans a physical CameraDevice start and every CaptureSession created while
    // that device remains active. This makes startup hiccups diagnosable without inferring them
    // from shutter/lens sounds: device-open and session-create counts are reported separately.
    private val cameraStartupSequenceCounter = java.util.concurrent.atomic.AtomicLong(0L)

    @Volatile
    private var activeCameraStartupSequenceId: Long = 0L

    @Volatile
    private var startupCameraOpenCount: Int = 0

    @Volatile
    private var startupSessionRequestCount: Int = 0

    @Volatile
    private var startupSessionConfiguredCount: Int = 0

    // Process-lifetime hardware/session counters. These deliberately never reset per startup so
    // device logs can distinguish an unavoidable physical-module activation from app-induced
    // CameraDevice power cycling.
    private val lifetimeHardStartCount = java.util.concurrent.atomic.AtomicLong(0L)
    private val lifetimeCameraOpenRequestCount = java.util.concurrent.atomic.AtomicLong(0L)
    private val lifetimeCameraOpenedCount = java.util.concurrent.atomic.AtomicLong(0L)
    private val lifetimeCameraCloseRequestCount = java.util.concurrent.atomic.AtomicLong(0L)
    private val lifetimeCameraClosedAckCount = java.util.concurrent.atomic.AtomicLong(0L)
    private val lifetimeSessionRequestCount = java.util.concurrent.atomic.AtomicLong(0L)
    private val lifetimeSessionConfiguredCount = java.util.concurrent.atomic.AtomicLong(0L)
    private val lifetimePhysicalHandoverCount = java.util.concurrent.atomic.AtomicLong(0L)

    @Volatile
    private var pendingPipelineResetRequest: PendingPipelineResetRequest? = null

    @Volatile
    private var lastPipelineResetCompletedMs: Long = 0L

    @Volatile
    private var pipelineCaptureGateLastReason: String = "none"

    @Volatile
    private var pipelineCaptureGateWaitMs: Long = 0L

    @Volatile
    private var pipelineCaptureGateResetTriggered: Boolean = false

    @Volatile
    private var pipelineCaptureGateReady: Boolean = true

    @Volatile
    private var activeVendorOperationProbe: VendorOperationModeProbeState? = null

    @Volatile
    private var lastVendorProbeAdvanceMs: Long = 0L

    private var lastPreviewSurface: Surface? = null
    private var lastPreferredFormat: String = "YUV"
    private var lastProfileId: String = "unknown"

    @Volatile
    private var viewfinderStreamSetting: ViewfinderStream = ViewfinderStream.YUV

    @Volatile
    private var viewfinderProfileId: String = "unknown"

    @Volatile
    private var effectiveViewfinderSource: ViewfinderEffectiveSource = ViewfinderEffectiveSource.YUV

    @Volatile
    private var effectiveViewfinderGeneration: Int = -1

    @Volatile
    private var targetViewfinderSource: ViewfinderEffectiveSource = ViewfinderEffectiveSource.YUV

    @Volatile
    private var targetViewfinderGeneration: Int = -1

    private var pendingViewfinderDisplayTransition: PendingViewfinderDisplayTransition? = null

    @Volatile
    private var rawPreviewConfiguredGeneration: Int = -1

    @Volatile
    private var rawPreviewConfigBuildInFlight: Boolean = false

    @Volatile
    private var lastRawPreviewConfigRefreshMs: Long = 0L

    @Volatile
    private var rawPreviewRouteRevision: Long = 0L

    @Volatile
    private var rawPreviewFrameListener: ((RawPreviewFrame) -> Unit)? = null

    @Volatile
    private var effectiveViewfinderListener: ((ViewfinderEffectiveSource, Int) -> Unit)? = null

    private val viewfinderCallbackRegistrationCounter = java.util.concurrent.atomic.AtomicLong(0L)
    @Volatile private var activeViewfinderCallbackRegistrationId: Long = 0L

    private val rawPreviewScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val capturePreviewContinuityTracker = CapturePreviewContinuityTracker()
    private val bufferAnalysisScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val warmBufferWatchdogScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sessionTransitionScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val phoneAssistanceSensorHelper = com.bncam.core.sensors.ColorSensorHelper(context.applicationContext)
    init {
        bufferAnalysisScope.launch {
            sensorProfileRegistry.prewarm()
            com.bncam.core.debug.DiagnosticsAggregator.record(
                stream = com.bncam.core.debug.DiagnosticsAggregator.Stream.PROFILE,
                scope = "SESSION",
                section = "PHYSICAL SENSOR PROFILE REGISTRY",
                content = sensorProfileRegistry.debugSummary()
            )
        }
        sessionTransitionScope.launch {
            SettingsRepository(context.applicationContext).phoneAssistanceSensorsFlow.collectLatest { enabled ->
                if (enabled) phoneAssistanceSensorHelper.startListening()
                else phoneAssistanceSensorHelper.stopListening()
                com.bncam.core.debug.DiagnosticsAggregator.record(
                    stream = com.bncam.core.debug.DiagnosticsAggregator.Stream.PROFILE,
                    scope = "SESSION",
                    section = "PHONE ASSISTANCE SENSORS",
                    content = "enabled=$enabled; listening=${phoneAssistanceSensorHelper.auxiliaryManager.isListening}; " +
                        "sensor=${phoneAssistanceSensorHelper.auxiliaryManager.selectedSensorName ?: "none"}; " +
                        "capability=${phoneAssistanceSensorHelper.auxiliaryManager.selectedCapability}"
                )
            }
        }
    }
    private val focusAnalysisRequests = Channel<BufferFrameAnalysisRequest>(Channel.CONFLATED)
    private val yuvAnalysisRequests = Channel<YuvFrameAnalysisRequest>(Channel.CONFLATED)
    private var warmBufferWatchdogJob: Job? = null
    @Volatile private var lastWarmBufferWatchdogRebuildMs: Long = 0L
    @Volatile private var warmBufferWatchdogRebuildAttemptsSinceHealthy: Int = 0
    private val rawPreviewRenderer = RawPreviewRenderer rawPreviewFrame@{ frame ->
        // A render may finish after the pipeline generation has advanced.
        // Drop stale completions immediately to preserve generation integrity.
        if (frame.pipelineGeneration != pipelineGeneration ||
            frame.pipelineGeneration != targetViewfinderGeneration
        ) {
            frame.close()
            return@rawPreviewFrame
        }
        if (frame.producerKind == RawPreviewProducerKind.CUSTOM_IMAGE_READER) {
            val binding = customRawPreviewBinding
            if (binding != null && binding.generation == frame.pipelineGeneration && binding.source == frame.source &&
                frame.source == targetViewfinderSource && customRawPreviewDisabledGeneration != frame.pipelineGeneration
            ) {
                // Publication is useful diagnostics, but it no longer grants producer authority.
                // The exact custom frame must first reach EGL PRESENTED state in FocusPeakingView.
                rawPreviewProducerAuthorityTracker.customRendererPublished(frame.pipelineGeneration)
                Log.i(
                    tag,
                    "CUSTOM_RAW_PREVIEW_PUBLICATION_OBSERVED source=${frame.source.name} " +
                        "generation=${frame.pipelineGeneration} timestampNs=${frame.sensorTimestampNs}"
                )
            }
        }
        capturePreviewContinuityTracker.previewFrame()
        capturePreviewContinuityTracker.repeatingRequestState(
            captureSession != null && currentCaptureRequest != null
        )
        applyPhysicalRawPreviewAwbObservation(frame)
        runEnabledRawPreviewAnalysis(frame)
        com.bncam.core.debug.RawPreviewFirstActivationTrace.kotlinPublication(
            source = frame.source.name,
            generation = frame.pipelineGeneration,
            sensorTimestampNs = frame.sensorTimestampNs
        )
        // A render may finish after the user has already requested YUV (or another RAW route).
        // Never let that completion overwrite the last-known-good texture during a display handoff.
        // The target route owns viewfinder publication; non-viewfinder frames are closed after analysis.
        if (frame.source == targetViewfinderSource) {
            // Renderer completion is deliberately NOT a display commit. FocusPeakingView stages the
            // candidate, imports/draws it, and reports the exact frame back only after presentation.
            rawPreviewFrameListener?.invoke(frame) ?: frame.close()
        } else {
            frame.close()
        }
    }

    @Volatile
    private var configuredPreviewStreamWidth: Int = 0

    @Volatile
    private var configuredPreviewStreamHeight: Int = 0

    @Volatile
    private var lastPreviewOutputPhysicalCameraId: String? = null

    @Volatile
    private var lastImageReaderOutputPhysicalCameraId: String? = null

    private val pipelineLock = Any()
    private val requestSubmissionLock = Any()

    private data class PendingPreviewControl(
        val key: String,
        val reason: String,
        val generation: Int,
        val sessionEpoch: Long,
        val action: () -> Unit
    )

    private val previewControlLock = Any()
    private val pendingPreviewControls = LinkedHashMap<String, PendingPreviewControl>()
    private var previewControlDrainPosted: Boolean = false
    private var previewControlDrainHandler: Handler? = null

    /**
     * UI controls are latest-wins commands for the current Camera2 session epoch. They are
     * serialized on CameraBackground so Compose/input callbacks never execute CameraService
     * Binder calls or mutate the active CaptureRequest.Builder directly.
     */
    private fun enqueuePreviewControl(key: String, reason: String, action: () -> Unit) {
        val handler = backgroundHandler ?: return
        val command = PendingPreviewControl(
            key = key,
            reason = reason,
            generation = pipelineGeneration,
            sessionEpoch = activeConfiguredSessionEpoch,
            action = action
        )
        val shouldPost = synchronized(previewControlLock) {
            pendingPreviewControls[key] = command
            if (previewControlDrainPosted && previewControlDrainHandler === handler) {
                false
            } else {
                previewControlDrainPosted = true
                previewControlDrainHandler = handler
                true
            }
        }
        if (shouldPost && !handler.post { drainPreviewControls(handler) }) {
            synchronized(previewControlLock) {
                if (previewControlDrainHandler === handler) {
                    previewControlDrainPosted = false
                    previewControlDrainHandler = null
                    pendingPreviewControls.clear()
                }
            }
            Log.w(tag, "Preview control drain rejected reason=$reason")
        }
    }

    private fun drainPreviewControls(ownerHandler: Handler) {
        while (true) {
            val batch = synchronized(previewControlLock) {
                if (previewControlDrainHandler !== ownerHandler) {
                    return
                }
                if (pendingPreviewControls.isEmpty()) {
                    previewControlDrainPosted = false
                    previewControlDrainHandler = null
                    return
                }
                pendingPreviewControls.values.toList().also { pendingPreviewControls.clear() }
            }
            batch.forEach { command ->
                if (command.generation != pipelineGeneration ||
                    command.sessionEpoch != activeConfiguredSessionEpoch
                ) {
                    Log.i(
                        previewDiagnosticsTag,
                        "event=STALE_PREVIEW_CONTROL_DROPPED key=${command.key} reason=${command.reason} " +
                            "generation=${command.generation} activeGeneration=$pipelineGeneration " +
                            "epoch=${command.sessionEpoch} activeEpoch=$activeConfiguredSessionEpoch"
                    )
                    return@forEach
                }
                runCatching(command.action).onFailure { error ->
                    Log.e(tag, "Preview control failed key=${command.key} reason=${command.reason}", error)
                }
            }
        }
    }

    private fun clearPendingPreviewControls(reason: String) {
        val cleared = synchronized(previewControlLock) {
            val count = pendingPreviewControls.size
            pendingPreviewControls.clear()
            previewControlDrainPosted = false
            previewControlDrainHandler = null
            count
        }
        if (cleared > 0) {
            Log.i(previewDiagnosticsTag, "event=PREVIEW_CONTROLS_CLEARED reason=$reason count=$cleared")
        }
    }
    // One serialized owner for CameraDevice/session/producer transitions. UI navigation no
    // longer owns Camera2 lifecycle; Settings is an overlay over the persistent viewfinder host.
    private val pipelineTransitionMutex = Mutex()
    @Volatile private var pipelineTransitionState = PipelineTransitionState.PREVIEW_ATTACHED

    // A Compose Surface is not safe to release merely because close() was called. Keep an
    // identity-bound close barrier and wait for CameraCaptureSession.StateCallback.onClosed().
    private val sessionLifecycleLock = Any()
    private val sessionCloseBarriers = IdentityHashMap<CameraCaptureSession, CompletableDeferred<Unit>>()
    private val sessionSurfaceNames = IdentityHashMap<CameraCaptureSession, IdentityHashMap<Surface, String>>()
    private val sessionOwnedReaders = IdentityHashMap<CameraCaptureSession, Set<ImageReader>>()
    private val readerOwningSessions = IdentityHashMap<ImageReader, MutableSet<CameraCaptureSession>>()
    private val retiringImageReaders = IdentityHashMap<ImageReader, String>()
    private val controlRequestEpochTracker = ControlRequestEpochTracker()
    private var activeOisDecision: OisDecision? = null
    private val directOisValidationLock = Any()
    private var directOisValidationDecision: OisDecision? = null
    private var directOisValidationOffFrames: Int = 0

    // Hardware Control States
    private var currentCaptureRequest: CaptureRequest.Builder? = null
    private var captureCallback: CameraCaptureSession.CaptureCallback? = null
    @Volatile
    var currentEvOffset: Float = 0f

    @Volatile
    private var requestedManualIso: Int? = null

    @Volatile
    private var requestedManualExposureNs: Long? = null

    @Volatile
    private var manualExposureAwaitingMetadata: Boolean = false

    @Volatile
    private var activeProfileExposurePreferences: CaptureExposurePreferences = CaptureExposurePreferences()

    @Volatile
    private var activeProfileExposurePlan: ProfileExposurePriorityPlan? = null

    @Volatile
    private var activeProfileExposurePlanGeneration: Int = -1

    @Volatile
    private var activeProfileExposureBounds: ExposureBounds? = null

    @Volatile
    private var profileExposureAwaitingAeBaseline: Boolean = false

    @Volatile
    private var profileExposureAeBaselineIso: Int? = null

    @Volatile
    private var profileExposureAeBaselineExposureNs: Long? = null

    @Volatile
    private var profileExposureAeBaselineGeneration: Int = -1

    @Volatile
    private var profileExposureBootstrapMinControlEpoch: Long = -1L

    @Volatile
    private var profileExposureAeBaselineControllerLuma: Float? = null

    @Volatile
    private var profileExposureLastAdaptationElapsedNs: Long = 0L

    private val profileExposureAdaptationLock = Any()
    private val profileExposurePreferencesRevision = java.util.concurrent.atomic.AtomicLong(0L)

    // Phase 1 default RAW acquisition owner. Camera2 AE remains the brightness meter; this state
    // only determines a motion-safe exposure-time priority for the repeating RAW producer.
    private val defaultRawShutterMotionMeter = RawPreviewMotionMeter()

    @Volatile
    private var latestDefaultRawMotion: RawMotionMeasurement? = null

    @Volatile
    private var latestDefaultRawMotionGeneration: Int = -1

    @Volatile
    private var defaultRawShutterAeBaselineIso: Int? = null

    @Volatile
    private var defaultRawShutterAeBaselineExposureNs: Long? = null

    @Volatile
    private var defaultRawShutterAeBaselineGeneration: Int = -1

    @Volatile
    private var defaultRawShutterAwaitingAeBaseline: Boolean = false

    @Volatile
    private var defaultRawShutterBootstrapMinControlEpoch: Long = -1L

    @Volatile
    private var defaultRawShutterLastControlUpdateNs: Long = 0L

    @Volatile
    private var defaultRawShutterLastMotionCeilingNs: Long = 0L

    @Volatile
    private var defaultRawShutterLastSafeExposureCeilingNs: Long = 0L

    @Volatile
    private var activeResolvedAntibandingMode: Int? = null

    // One stabilized Camera2 flicker authority per active pipeline generation. The raw HAL
    // statistic is deliberately not allowed to rewrite shutter/FPS on a single-frame observation.
    private val rawFlickerStabilityTracker = RawFlickerStabilityTracker()

    @Volatile
    private var rawFlickerTrackerGeneration: Int = -1

    @Volatile
    private var latestRawFlickerSnapshot: RawFlickerStabilitySnapshot =
        rawFlickerStabilityTracker.current()

    @Volatile
    private var defaultRawShutterManualFallbackActive: Boolean = false

    @Volatile
    private var defaultRawShutterFallbackTargetLuma: Float? = null

    @Volatile
    private var latestDefaultRawExposureTruth: DefaultRawExposureRealizationTruth? = null

    @Volatile
    private var lastDefaultRawExposureTruthLogKey: String = ""

    private val defaultRawApi36AuthorityTracker = DefaultRawApi36AuthorityTracker()
    private val defaultRawPhotometricConvergenceTracker = DefaultRawPhotometricConvergenceTracker()
    private val defaultRawMeteringTracker = DefaultRawMeteringTracker()
    private val defaultRawAeReferenceGate = DefaultRawAeReferenceGate()

    @Volatile
    private var latestDefaultRawMetering: DefaultRawMeteringSnapshot? = null

    @Volatile
    private var latestDefaultRawExposureTarget: DefaultRawFinalExposureTarget? = null

    @Volatile
    private var lastDefaultRawExposureTargetLogKey: String = ""

    @Volatile
    private var latestDefaultRawExposureAllocation: DefaultRawExposureAllocation? = null

    @Volatile
    private var latestDefaultRawPhotometricConvergence: DefaultRawPhotometricConvergenceSnapshot? = null

    @Volatile
    private var defaultRawPhotometricTargetLuma: Float? = null

    @Volatile
    private var defaultRawAllocationReady: Boolean = false

    @Volatile
    private var defaultRawFramesSinceExposureRequest: Int = 0

    @Volatile
    private var defaultRawLastObservedControlEpoch: Long = -1L

    @Volatile
    private var defaultRawLastAeStable: Boolean = false

    @Volatile
    private var lastDefaultRawPhotometricConvergenceLogKey: String = ""

    @Volatile
    private var defaultRawShutterFallbackPlan: DefaultRawManualFallbackPlan? = null

    @Volatile
    private var defaultRawShutterFallbackLastAdaptationNs: Long = 0L

    private val defaultRawShutterFallbackLock = Any()

    @Volatile
    private var requestedLiveWhiteBalanceKelvin: Int? = null

    private val liveWhiteBalanceRequestEpoch = java.util.concurrent.atomic.AtomicLong(0L)
    @Volatile private var liveWhiteBalanceResolutionJob: Job? = null
    private val liveWhiteBalanceCharacteristicsLock = Any()
    private var liveWhiteBalanceCharacteristicsCameraId: String? = null
    private var cachedLiveWhiteBalanceCharacteristics: CameraCharacteristics? = null

    @Volatile
    private var lastLiveWhiteBalanceSummary: String = "AUTO"

    private val whiteBalanceStateEngine = WhiteBalanceStateEngine()

    @Volatile
    private var lastPhysicalAwbDiagnosticsMs: Long = 0L

    @Volatile
    private var activeTapAeRegion: MeteringRectangle? = null

    @Volatile
    private var activeTapAePhysicalCameraId: String? = null
    @Volatile
    private var activePhysicalAfRegion: MeteringRectangle? = null
    @Volatile
    private var activePhysicalAfCameraId: String? = null
    @Volatile
    private var activePhysicalAfRequestBounds: Rect? = null

    @Volatile
    private var initialAeMeteringRegions: Array<MeteringRectangle>? = null

    @Volatile
    private var initialAeMeteringGeneration: Int = -1

    @Volatile
    private var lastMeteringPlanSummary: String = "not_applied"

    @Volatile
    private var lastRequestedLogicalAeRegionsSummary: String = "unset"

    @Volatile
    private var lastRequestedPhysicalAeRegionsSummary: String = "unset"

    @Volatile
    private var lastRequestedPhysicalAeCameraId: String? = null

    @Volatile
    private var lastLogicalCustomAeRegionsRequested: Boolean = false

    @Volatile
    private var lastPhysicalCustomAeRegionsRequested: Boolean = false

    @Volatile
    private var lastMeteringEchoLogSignature: String = ""

    @Volatile
    private var lastExposurePlanSummary: String = "mode=AUTO;not_applied"

    @Volatile
    private var lastFaceMeteringRect: Rect? = null

    @Volatile
    private var lastFaceTrackingId: Int? = null

    private val _priorityFaceBounds = MutableStateFlow<Rect?>(null)
    val priorityFaceBounds = _priorityFaceBounds.asStateFlow()

    @Volatile
    private var currentFaceDetectionRequested: Boolean = false

    @Volatile
    private var currentFacePriorityFocusEnabled: Boolean = false

    @Volatile
    private var lastFaceRequestUpdateMs: Long = 0L

    // NIEUW: Live Data States (Focus & Lock)
    private val _liveFocusDiopters = MutableStateFlow(0f)
    val liveFocusDiopters = _liveFocusDiopters.asStateFlow()
    private val _focusPeakingGuidance = MutableStateFlow(FocusPeakingGuidance())
    val focusPeakingGuidance = _focusPeakingGuidance.asStateFlow()
    private val _liveWhiteBalanceDisplayCompensation = MutableStateFlow(LiveWhiteBalanceDisplayCompensation())
    val liveWhiteBalanceDisplayCompensation = _liveWhiteBalanceDisplayCompensation.asStateFlow()
    private val _viewfinderRebuildVisualState = MutableStateFlow(ViewfinderRebuildVisualState())
    val viewfinderRebuildVisualState = _viewfinderRebuildVisualState.asStateFlow()
    val afGroundTruthOverlay = com.bncam.core.debug.AfGroundTruthTrace.overlay
    @Volatile private var liveWhiteBalanceTargetSensorGains: FloatArray? = null
    @Volatile private var lastLiveWhiteBalanceDisplayUpdateMs: Long = 0L
    val passiveFocusAchieved = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private var previousAfState = -1
    private var lastPassiveFocusDiopters = -1f

    private val predictiveAfTracker = com.bncam.core.capture.PredictiveAfTracker()

    // 🔥 NIEUW: Flash & AE State Trackers
    @Volatile
    private var currentFlashMode = "Off"
    @Volatile
    var currentMeteringStyle = MeteringMode.AUTO_DEFAULT_AE.settingValue

    @Volatile
    private var configuredFocusMode: String = "Continuous"

    // One authoritative preview-focus owner. This replaces the older collection of boolean guards
    // that could drift apart between Compose, face detection, tracking and manual focus.
    private val _focusOwnership = MutableStateFlow(FocusOwnershipState())
    val focusOwnership = _focusOwnership.asStateFlow()

    private fun transitionFocusOwner(
        owner: FocusOwner,
        reason: String,
        focusLocked: Boolean = false,
        aeLocked: Boolean = false,
        trackingPinned: Boolean = owner == FocusOwner.TRACK_PINNED
    ) {
        val previous = _focusOwnership.value
        if (previous.owner == owner &&
            previous.focusLocked == focusLocked &&
            previous.aeLocked == aeLocked &&
            previous.trackingPinned == trackingPinned &&
            previous.reason == reason
        ) return
        val next = FocusOwnershipState(
            owner = owner,
            focusLocked = focusLocked,
            aeLocked = aeLocked,
            trackingPinned = trackingPinned,
            reason = reason,
            generation = previous.generation + 1L
        )
        _focusOwnership.value = next
        // Compatibility stream: historically this meant both states were locked. Keep it truthful.
        _isAeAfLocked.value = focusLocked && aeLocked
        Log.i(tag, "FOCUS_OWNER ${previous.owner}->${next.owner} reason=$reason focusLocked=$focusLocked aeLocked=$aeLocked")
    }

    private fun focusOwnerBlocksFacePriority(): Boolean = !_focusOwnership.value.facePriorityMayOwn

    private fun snapshotFocusCaptureContext(): FocusCaptureContext {
        val ownership = _focusOwnership.value
        val tracking = _focusTrackingState.value
        return FocusCaptureContext(
            owner = ownership.owner,
            trackingPhase = tracking.phase,
            trackingConfidence = tracking.confidence,
            trackingPinned = ownership.trackingPinned
        )
    }

    private fun snapshotPortraitCaptureContext(viewfinderMode: ViewfinderMode = ViewfinderMode.PHOTO): com.bncam.core.capture.PortraitCaptureContext {
        val portraitRequested = (viewfinderMode == ViewfinderMode.PORTRAIT)
        if (!portraitRequested) {
            return com.bncam.core.capture.PortraitCaptureContext(requested = false, status = "DISABLED")
        }
        val artifact = latestPortraitMask.get()
            ?: return com.bncam.core.capture.PortraitCaptureContext(
                requested = true,
                status = "MASK_UNAVAILABLE"
            )
        val referenceTimestamp = lastCaptureResult?.get(CaptureResult.SENSOR_TIMESTAMP)
            ?: android.os.SystemClock.elapsedRealtimeNanos()
        val ageMs = kotlin.math.abs(referenceTimestamp - artifact.timestampNs) / 1_000_000L
        val trackingActive = _focusOwnership.value.trackingActive
        val maxMaskAgeMs = if (trackingActive) 1_500L else 900L
        if (ageMs > maxMaskAgeMs) {
            return com.bncam.core.capture.PortraitCaptureContext(
                requested = true,
                status = "MASK_STALE_${ageMs}ms_LIMIT_${maxMaskAgeMs}ms"
            )
        }
        val targetBounds = if (trackingActive) {
            _trackedObjectBounds.value?.let { android.graphics.RectF(it) }
        } else null
        return com.bncam.core.capture.PortraitCaptureContext(
            requested = true,
            mask = artifact,
            targetBoundsNormalized = targetBounds ?: android.graphics.RectF(artifact.subjectBoundsNormalized),
            status = "READY_${artifact.selectionReason}"
        )
    }

    @Volatile
    var liveHighDrRisk: Boolean = false
    @Volatile
    private var latestExposureStatistics: com.bncam.core.capture.ExposureStatistics? = null
    @Volatile
    var lastShotVerdictHighDrRisk: Boolean = false
    @Volatile
    var lastShotExposureNs: Long = 0L
    @Volatile
    var lastShotIso: Int = 0

    @Volatile
    private var lastAfState: Int? = null
    @Volatile
    private var lastAeState: Int? = null
    @Volatile
    private var lastCaptureResult: CaptureResult? = null

    @Volatile
    private var lastCaptureResultGeneration: Int = -1

    private var isTorchActive = false

    private data class Camera3AObservation(
        val pipelineGeneration: Int,
        val frameNumber: Long,
        val controlRequestEpoch: Long,
        val aeMode: Int?,
        val aeState: Int?,
        val afState: Int?,
        val flashState: Int?,
        val observedElapsedRealtimeMs: Long
    )

    private data class FlashCameraControlPlan(
        val aeMode: Int,
        val precaptureFlashMode: Int,
        val stillFlashMode: Int,
        val reason: String
    )

    private data class FlashPrecaptureWaitResult(
        val completed: Boolean,
        val triggerObserved: Boolean,
        val finalAeState: Int?,
        val reason: String,
        val elapsedMs: Long
    )

    /**
     * Narrow hand-off object for the dedicated flash coroutine.
     *
     * Keeping the flash request state machine out of executeCapture prevents the Kotlin/JVM
     * coroutine transformer from having to traverse one monolithic capture CFG while preserving
     * the exact same Camera2 transaction and returned shutter metadata.
     */
    private data class DedicatedFlashCaptureResult(
        val shutterTimestampNs: Long,
        val shutterTimestampDomain: String,
        val controlRequestEpochAtShutter: Long,
        val vendorDebugCaptureResult: TotalCaptureResult?,
        val captureFailureReason: String?
    )

    @Volatile
    private var latestCamera3AObservation: Camera3AObservation? = null
    private val camera3AObservationLock = Any()
    private val camera3AObservationHistory = java.util.ArrayDeque<Camera3AObservation>(48)

    private fun recordCamera3AObservation(observation: Camera3AObservation) {
        latestCamera3AObservation = observation
        synchronized(camera3AObservationLock) {
            camera3AObservationHistory.addLast(observation)
            while (camera3AObservationHistory.size > 48) {
                camera3AObservationHistory.removeFirst()
            }
        }
    }

    private fun findCamera3AObservation(
        expectedGeneration: Int,
        controlRequestEpoch: Long
    ): Camera3AObservation? = synchronized(camera3AObservationLock) {
        camera3AObservationHistory
            .toList()
            .asReversed()
            .firstOrNull { observation ->
                observation.pipelineGeneration == expectedGeneration &&
                    observation.controlRequestEpoch == controlRequestEpoch
            }
    }

    private fun camera3AObservationsSince(
        expectedGeneration: Int,
        minimumFrameNumber: Long
    ): List<Camera3AObservation> = synchronized(camera3AObservationLock) {
        camera3AObservationHistory.filter { observation ->
            observation.pipelineGeneration == expectedGeneration &&
                observation.frameNumber >= minimumFrameNumber
        }
    }

    // 1. Focus track configuratie - NU MAXIMAAL GEVOELIG
    private val objectTrackerOptions =
        com.google.mlkit.vision.objects.defaults.ObjectDetectorOptions.Builder()
            .setDetectorMode(com.google.mlkit.vision.objects.defaults.ObjectDetectorOptions.STREAM_MODE)
            .enableMultipleObjects()
            // NIEUW: We zetten de drempel op 0.1 (bijna niets).
            // Hierdoor worden ook handen, armen en vage vormen gevolgd.
            // We zetten classification uit, want het maakt ons niet uit WAT het is, als hij het maar volgt.
            .build()

    private val objectTracker =
        com.google.mlkit.vision.objects.ObjectDetection.getClient(objectTrackerOptions)
    private val barcodeScanner = com.google.mlkit.vision.barcode.BarcodeScanning.getClient()

    private val captureAttempts = CaptureAttemptCoordinator { event, detail ->
        Log.i("BnCamCaptureLifecycle", "$event $detail")
    }

    // Shutter admission freezes only the *selection* exposure contract, never Camera2 itself.
    // This prevents a preview/anti-flicker control update that happens after the user's press from
    // retroactively making every already-captured Near-ZSL frame ineligible. The repeating producer
    // stays live and keeps filling the ring while admission/processing proceeds.
    private val captureSelectionConstraintLock = Any()
    @Volatile private var captureSelectionConstraintFreezeAttemptId: Long? = null
    private var deferredSelectionExposureConstraintUpdate: DeferredSelectionExposureConstraintUpdate? = null
    private var frozenSelectionExposureConstraintSnapshot:
        com.bncam.core.buffer.FrameSelectionExposureConstraintSnapshot? = null

    init {
        // Manager-lifetime collector: keep it under an owned scope so Activity teardown can cancel
        // the subscription and release the BnCameraManager object graph.
        bufferAnalysisScope.launch(Dispatchers.IO) {
            com.bncam.core.output.CaptureProcessingQueue.events.collect { snapshot ->
                reconcileCaptureWorkSnapshot(snapshot)
            }
        }
    }

    private fun reconcileCaptureWorkSnapshot(
        snapshot: com.bncam.core.output.CaptureWorkSnapshot
    ) {
        val matchingAttemptId = captureAttempts.findByWorkId(snapshot.workId) ?: return
        when (snapshot.state) {
            com.bncam.core.output.CaptureWorkState.PUBLISHED -> {
                val uriStr =
                    snapshot.thumbnailUri ?: snapshot.jpegUri
                val uri = uriStr?.let { Uri.parse(it) }
                val publicationSucceeded =
                    snapshot.publicationResult !=
                        com.bncam.core.output.CapturePublicationResult.FAILURE
                finishCaptureAttempt(
                    matchingAttemptId,
                    uri,
                    "output_published:${snapshot.publicationResult}",
                    publicationSucceeded
                )
            }
            com.bncam.core.output.CaptureWorkState.FAILED -> {
                finishCaptureAttempt(
                    matchingAttemptId,
                    null,
                    snapshot.failureReason ?: "processing_failed"
                )
            }
            else -> Unit
        }
    }

    private val isCapturing: Boolean
        get() = captureAttempts.snapshot.captureInProgress

    // 2. States voor je User Interface en AF Engine
    private val _trackedObjectBounds = MutableStateFlow<android.graphics.RectF?>(null)
    val trackedObjectBounds = _trackedObjectBounds.asStateFlow()
    private val _focusTrackingActive = MutableStateFlow(false)
    val focusTrackingActive = _focusTrackingActive.asStateFlow()
    private val _focusTrackingState = MutableStateFlow(FocusTrackingState())
    val focusTrackingState = _focusTrackingState.asStateFlow()

    private var activeTrackingId: Int? = null
    private var pendingTapX: Float? = null
    private var pendingTapY: Float? = null
    @Volatile private var focusTrackingPinned: Boolean = false
    @Volatile private var focusTrackingMirrorX: Boolean = false
    private var trackingLostFrames: Int = 0
    private var lastTrackedBoxPx: Rect? = null
    private var trackingSmoothedX: Float = Float.NaN
    private var trackingSmoothedY: Float = Float.NaN
    private var trackingVelocityX: Float = 0f
    private var trackingVelocityY: Float = 0f
    private var trackingLastObservationNs: Long = 0L
    private var trackingLastHardwareSubmitNs: Long = 0L
    private var lastHwFocusRegionPct: Float = -1f

    private val portraitSubjectSegmenter = com.bncam.core.capture.PortraitSubjectSegmenter()
    private val latestPortraitMask = java.util.concurrent.atomic.AtomicReference<com.bncam.core.capture.PortraitMaskArtifact?>(null)
    private val portraitSegmentationBusy = java.util.concurrent.atomic.AtomicBoolean(false)
    private val lastPortraitSegmentationNs = java.util.concurrent.atomic.AtomicLong(0L)
    @Volatile private var portraitTapSeedX: Float? = null
    @Volatile private var portraitTapSeedY: Float? = null

    private val focusTimingScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default
    )
    private var trackingTimeoutJob: kotlinx.coroutines.Job? = null
    private var tapFocusTimeoutJob: kotlinx.coroutines.Job? = null
    private var pointLockJob: kotlinx.coroutines.Job? = null

    private var isTrackingBusy = false
    @Volatile private var isQrAnalysisBusy = false

    private val _isAeAfLocked = MutableStateFlow(false)
    val isAeAfLocked = _isAeAfLocked.asStateFlow()

    val cameraState = MutableStateFlow(CameraEngineState.CAMERA_STARTING)
    private val _captureContractError = MutableStateFlow<String?>(null)
    val captureContractError = _captureContractError.asStateFlow()

    private val _liveHistogram = MutableStateFlow<List<Float>>(List(16) { 0f })
    val liveHistogram = _liveHistogram.asStateFlow()
    private val _liveRgbHistogram = MutableStateFlow(LiveRgbHistogram.empty())
    val liveRgbHistogram = _liveRgbHistogram.asStateFlow()

    val ringBuffer = FrameRingBuffer(capacity = 50)

    init {
        bufferAnalysisScope.launch {
            for (request in focusAnalysisRequests) {
                processFocusConfidenceAnalysis(request)
            }
        }
        bufferAnalysisScope.launch {
            for (request in yuvAnalysisRequests) {
                processEnabledYuvAnalysis(request)
            }
        }
    }

    /** Updates display routing only; this deliberately never rebuilds the Camera2 session. */
    fun configureViewfinderStream(
        setting: ViewfinderStream,
        profileId: String,
        requestedLensId: String? = null,
        onEffectiveSourceChanged: (ViewfinderEffectiveSource, Int) -> Unit,
        onRawFrame: (RawPreviewFrame) -> Unit
    ): Long {
        val registrationId = viewfinderCallbackRegistrationCounter.incrementAndGet()
        activeViewfinderCallbackRegistrationId = registrationId
        val previousStreamSetting = viewfinderStreamSetting
        val traceIdentity = synchronized(pipelineLock) { activePipelineIdentity }
        if (previousStreamSetting != ViewfinderStream.SELECTED_BUFFER &&
            setting == ViewfinderStream.SELECTED_BUFFER && traceIdentity != null
        ) {
            val traceSource = ViewfinderEffectiveSource.fromImageFormat(traceIdentity.bufferFormat)
            val traceGeneration = pipelineGeneration
            com.bncam.core.debug.RawPreviewFirstActivationTrace.beginSelectedBuffer(
                generation = traceGeneration,
                expectedSource = traceSource.name,
                producerReady = imageReader != null && activeZslFormat == traceIdentity.bufferFormat,
                preexistingWarmSensorTimestampNs = ringBuffer.latestImageTimestamp(traceGeneration)
            )
        }
        val canPreserveResidentRawConfig =
            rawPreviewConfiguredGeneration == pipelineGeneration &&
                rawPreviewRenderer.currentConfig != null &&
                synchronized(pipelineLock) { activePipelineIdentity?.bufferFormat }?.let {
                    it == ImageFormat.RAW10 || it == ImageFormat.RAW_SENSOR
                } == true
        viewfinderStreamSetting = setting
        viewfinderProfileId = profileId
        if (setting == ViewfinderStream.SELECTED_BUFFER &&
            traceIdentity != null &&
            (traceIdentity.bufferFormat == ImageFormat.RAW10 ||
                traceIdentity.bufferFormat == ImageFormat.RAW_SENSOR)
        ) {
            // Exposure statistics and default RAW motion metering have dedicated sensor/GPU-domain
            // sources. Keep compact NV21 disabled unless an image-domain consumer actually needs it.
            refreshRawPreviewCompactAnalysisRequest()
            prepareRawPreviewBackendAsync(
                "selected_buffer_request:${formatName(traceIdentity.bufferFormat)}"
            )
        }
        refreshActiveProfileExposurePreferences(profileId, registrationId)
        effectiveViewfinderListener = onEffectiveSourceChanged
        rawPreviewFrameListener = onRawFrame
        Log.i(
            tag,
            "VIEWFINDER_STREAM_SETTING=${setting.persistedValue} registration=$registrationId"
        )
        val callbackMatchesActiveSensor = requestedLensId == null ||
            traceIdentity?.selectedLensId == requestedLensId
        if (callbackMatchesActiveSensor) {
            refreshEffectiveViewfinderSource(
                expectedCallbackRegistrationId = registrationId,
                preserveResidentRawConfig = canPreserveResidentRawConfig
            )
        } else {
            // Compose publishes the target lens/profile pair before Camera2 finishes its serialized
            // handover. Keep the callbacks registered, but do not restage the old generation under
            // the target UI lens. The real pipeline transition calls refreshEffectiveViewfinderSource
            // after it owns the requested sensor and a new generation.
            Log.i(
                tag,
                "VIEWFINDER_TARGET_DEFERRED requestedLens=$requestedLensId " +
                    "activeSensor=${traceIdentity?.selectedLensId ?: "none"} generation=$pipelineGeneration"
            )
        }
        return registrationId
    }

    fun setViewfinderStreamDirect(setting: ViewfinderStream) {
        val listener = effectiveViewfinderListener
        val rawListener = rawPreviewFrameListener
        val profileId = viewfinderProfileId
        if (listener != null && rawListener != null) {
            configureViewfinderStream(
                setting = setting,
                profileId = profileId,
                onEffectiveSourceChanged = listener,
                onRawFrame = rawListener
            )
        } else {
            viewfinderStreamSetting = setting
            refreshEffectiveViewfinderSource()
        }
    }

    /**
     * Resolves profile-owned acquisition exposure preferences off the Camera2 thread. Applying a
     * profile never rebuilds the session: the current repeating request is updated through the
     * serialized preview-control owner. Priority modes deliberately bootstrap through one fresh
     * Camera2-AE result before taking manual shutter/ISO authority.
     */
    private fun refreshActiveProfileExposurePreferences(profileId: String, registrationId: Long) {
        val revision = profileExposurePreferencesRevision.incrementAndGet()
        if (profileId.isBlank() || profileId == "unknown") {
            activeProfileExposurePreferences = CaptureExposurePreferences()
            activeProfileExposurePlan = null
            activeProfileExposurePlanGeneration = -1
            activeProfileExposureBounds = null
            profileExposureAwaitingAeBaseline = false
            clearProfileExposureAeBaseline()
            clearDefaultRawShutterPriorityState(resetMotion = false)
            enqueuePreviewControl("profile_exposure_priority", "PROFILE_EXPOSURE_PREFS_CLEARED") {
                updatePreviewRepeatingRequest()
            }
            return
        }
        sessionTransitionScope.launch {
            val repo = SettingsRepository(context)
            val loaded = CaptureExposurePreferences.fromPersisted(
                priorityMode = repo.getProfileString(
                    profileId, CaptureSettingKeys.EXPOSURE_PRIORITY_MODE, "Balanced"
                ).first(),
                shutterMultiplier = repo.getProfileFloat(
                    profileId, CaptureSettingKeys.SHUTTER_PRIORITY_MULTIPLIER, 1.0f
                ).first(),
                isoMultiplier = repo.getProfileFloat(
                    profileId, CaptureSettingKeys.ISO_PRIORITY_MULTIPLIER, 1.0f
                ).first(),
                captureEvBias = repo.getProfileFloat(
                    profileId, CaptureSettingKeys.CAPTURE_EV_BIAS, 0.0f
                ).first(),
                shotBiasExposure = repo.getProfileString(
                    profileId, CaptureSettingKeys.SHOT_BIAS_EXPOSURE, "Auto"
                ).first(),
                maxFrameExposure = repo.getProfileString(
                    profileId, CaptureSettingKeys.SHOT_BIAS_MAX_FRAME_EXPOSURE, "Max exposure time"
                ).first()
            )
            if (revision != profileExposurePreferencesRevision.get() ||
                registrationId != activeViewfinderCallbackRegistrationId ||
                profileId != viewfinderProfileId
            ) return@launch

            activeProfileExposurePreferences = loaded
            activeProfileExposurePlan = null
            activeProfileExposurePlanGeneration = -1
            activeProfileExposureBounds = null
            clearProfileExposureAeBaseline()
            clearDefaultRawShutterPriorityState(resetMotion = false)
            profileExposureAwaitingAeBaseline = loaded.requiresAeBaseline()
            profileExposureBootstrapMinControlEpoch = if (profileExposureAwaitingAeBaseline) {
                controlRequestEpochTracker.currentSubmittedEpoch() + 1L
            } else {
                -1L
            }
            Log.i(
                tag,
                "PROFILE_SHOT_BIAS profile=$profileId exposure=${loaded.shotBiasExposure.persistedValue} " +
                    "maxFrame=${loaded.maxFrameExposure.persistedValue} captureEvBias=${loaded.captureEvBias} " +
                    "bootstrapAe=$profileExposureAwaitingAeBaseline"
            )
            enqueuePreviewControl("profile_exposure_priority", "PROFILE_EXPOSURE_PREFS:$profileId") {
                updatePreviewRepeatingRequest()
            }
        }
    }

    private fun clearProfileExposureAeBaseline() {
        profileExposureAeBaselineIso = null
        profileExposureAeBaselineExposureNs = null
        profileExposureAeBaselineGeneration = -1
        profileExposureBootstrapMinControlEpoch = -1L
        profileExposureAeBaselineControllerLuma = null
        profileExposureLastAdaptationElapsedNs = 0L
    }

    private fun activeProfileExposureTotalEv(): Float =
        (activeProfileExposurePreferences.captureEvBias + currentEvOffset).coerceIn(-4f, 4f)

    /** Invalidate only the RAW preview calibration snapshot after a live creative control changes. */
    fun notifyViewfinderLiveTuningChanged() {
        rawPreviewRouteRevision++
        rawPreviewConfiguredGeneration = -1
        rawPreviewConfigBuildInFlight = false
        lastRawPreviewConfigRefreshMs = 0L
        Log.i(
            tag,
            "VIEWFINDER_LIVE_TUNING_INVALIDATED generation=$pipelineGeneration revision=$rawPreviewRouteRevision"
        )
    }

    fun reportRawPreviewGlUploadTime(uploadTimeMs: Float) {
        rawPreviewRenderer.reportGlUploadCost(uploadTimeMs)
    }

    fun clearViewfinderStreamCallbacks(registrationId: Long) {
        if (registrationId <= 0L || activeViewfinderCallbackRegistrationId != registrationId) {
            Log.i(
                tag,
                "VIEWFINDER_CALLBACK_CLEAR_STALE requested=$registrationId active=$activeViewfinderCallbackRegistrationId"
            )
            return
        }
        activeViewfinderCallbackRegistrationId = 0L
        effectiveViewfinderListener = null
        rawPreviewFrameListener = null
        // Callback disposal is UI ownership only. Do not transiently reroute the persistent
        // viewfinder to YUV while Compose replaces a registration (for example on profile change).
    }

    private fun beginViewfinderRebuildVisualTransition(generation: Int, reason: String) {
        _viewfinderRebuildVisualState.value = ViewfinderRebuildVisualState(
            active = true,
            generation = generation,
            reason = reason
        )
        Log.i(tag, "VIEWFINDER_REBUILD_VISUAL_BEGIN generation=$generation reason=$reason")
    }

    private fun finishViewfinderRebuildVisualTransition(generation: Int, reason: String) {
        val current = _viewfinderRebuildVisualState.value
        if (!current.active || current.generation != generation) return
        _viewfinderRebuildVisualState.value = ViewfinderRebuildVisualState(
            active = false,
            generation = generation,
            reason = reason
        )
        Log.i(tag, "VIEWFINDER_REBUILD_VISUAL_END generation=$generation reason=$reason")
    }

    private fun abortViewfinderRebuildVisualTransition(reason: String) {
        val current = _viewfinderRebuildVisualState.value
        if (!current.active) return
        _viewfinderRebuildVisualState.value = ViewfinderRebuildVisualState(
            active = false,
            generation = current.generation,
            reason = reason
        )
        Log.w(tag, "VIEWFINDER_REBUILD_VISUAL_ABORT generation=${current.generation} reason=$reason")
    }

    /**
     * Prepare RAW preview independently from frame rendering. If preparation finishes while RAW is
     * already the display target, explicitly re-prime the newest warm frame so activation never
     * depends on a later capture or a coincidental new callback.
     */
    private fun prepareRawPreviewBackendAsync(reason: String) {
        rawPreviewRenderer.prepareBackendAsync(reason) { prepared ->
            if (prepared) {
                rawPreviewScope.launch {
                    val source = targetViewfinderSource
                    val generation = targetViewfinderGeneration
                    if (source == ViewfinderEffectiveSource.YUV || generation != pipelineGeneration) {
                        return@launch
                    }
                    primeRawViewfinderFromWarmBuffer(
                        source = source,
                        generation = generation,
                        routeRevision = rawPreviewRouteRevision
                    )
                }
            }
        }
    }

    /**
     * Start full-resolution RAW still resource preparation as soon as the pipeline identity is
     * known. Waiting for the first ImageReader RAW frame made the old prewarm race the user's first
     * shutter and could occupy the shared capture-domain Vulkan lock for several seconds.
     *
     * The worker remains asynchronous; Camera2/session construction never waits here. The
     * FrameRingBuffer first-frame request is intentionally kept as a fallback/retry path.
     */
    private fun prewarmRawStillWorkingSetAsync(
        identity: PipelineIdentity,
        generation: Int,
        reason: String
    ) {
        if (identity.bufferFormat != ImageFormat.RAW10 &&
            identity.bufferFormat != ImageFormat.RAW_SENSOR
        ) return
        com.bncam.core.vulkan.RawStillWorkingSetPrewarmer.request(
            width = identity.width,
            height = identity.height,
            generation = generation
        )
        traceCaptureRuntime(
            "RAW_STILL_PREWARM_EARLY_REQUEST generation=$generation " +
                "format=${formatName(identity.bufferFormat)} size=${identity.width}x${identity.height} " +
                "reason=$reason"
        )
    }

    private fun refreshEffectiveViewfinderSource(
        expectedCallbackRegistrationId: Long? = null,
        preserveResidentRawConfig: Boolean = false
    ) {
        val identity = synchronized(pipelineLock) { activePipelineIdentity }
        val profileId = viewfinderProfileId.takeUnless { it == "unknown" }
            ?: identity?.requestedProfileId ?: ""
        val source = resolveEffectiveViewfinderSource(
            viewfinderStreamSetting,
            identity?.let { ViewfinderEffectiveSource.fromImageFormat(it.bufferFormat) }
                ?: ViewfinderEffectiveSource.YUV,
            profileId
        )
        val generation = pipelineGeneration
        val previousDisplayedGeneration = effectiveViewfinderGeneration
        targetViewfinderSource = source
        targetViewfinderGeneration = generation
        com.bncam.core.debug.RawPreviewFirstActivationTrace.effectiveSourceSelected(
            source = source.name,
            generation = generation
        )
        if (source != ViewfinderEffectiveSource.YUV && imageReader != null &&
            identity != null && activeZslFormat == identity.bufferFormat
        ) {
            com.bncam.core.debug.RawPreviewFirstActivationTrace.producerReady(
                source = source.name,
                generation = generation,
                detail = "authoritative_image_reader_resident"
            )
        }

        // A pure YUV <-> Selected-buffer display switch does not change RAW calibration, profile
        // authority or producer generation. Keep the already validated RAW renderer configuration
        // resident in that case. Rebuilding it created a bootstrap/full-config brightness flash and
        // made a return to RAW wait for calibration again even though nothing physical changed.
        val keepResidentRawConfig = preserveResidentRawConfig &&
            rawPreviewConfiguredGeneration == generation &&
            rawPreviewRenderer.currentConfig != null
        if (!keepResidentRawConfig) {
            rawPreviewRouteRevision++
            rawPreviewConfiguredGeneration = -1
            rawPreviewConfigBuildInFlight = false
            lastRawPreviewConfigRefreshMs = 0L
            // Retire old RAW worker requests/configuration, but never clear the already uploaded GL
            // texture here. That texture is the last-known-good display until the target is proven.
            rawPreviewRenderer.configure(null)
        }

        synchronized(pipelineLock) {
            val requiresProducerFrame = generation != previousDisplayedGeneration
            pendingViewfinderDisplayTransition = PendingViewfinderDisplayTransition(
                source = source,
                generation = generation,
                requiresProducerFrame = requiresProducerFrame,
                producerFrameReady = !requiresProducerFrame
            )
        }
        // RuntimeProfileFactory already resolves CameraCharacteristics/stream timing on the
        // serialized camera worker. Do not issue a CameraService Binder query from a Compose
        // callback merely to populate diagnostics.
        val advertisedMinFrameDurationNs = RawPipelineRuntimeOwner.getProfile()
            ?.takeIf { it.sessionGeneration == generation && identity != null &&
                it.format == identity.bufferFormat &&
                it.geometry.bufferWidth == identity.width &&
                it.geometry.bufferHeight == identity.height
            }
            ?.capabilities
            ?.minFrameDurationNs
            ?: 0L
        RawPreviewCadenceDiagnostics.route(source, generation, advertisedMinFrameDurationNs)

        val callbackRegistrationStillCurrent = expectedCallbackRegistrationId == null ||
            activeViewfinderCallbackRegistrationId == expectedCallbackRegistrationId
        if (!callbackRegistrationStillCurrent) {
            Log.i(
                tag,
                "VIEWFINDER_TARGET_CALLBACK_STALE expected=$expectedCallbackRegistrationId " +
                    "active=$activeViewfinderCallbackRegistrationId source=${source.name} generation=$generation"
            )
            return
        }

        // Stage display intent separately from committed display authority. This lets the GL view
        // attempt the exact target frame while retaining the last-known-good texture as fallback.
        com.bncam.ui.screens.capture.FocusPeakingView.stageViewfinderTarget(source, generation)

        Log.i(
            tag,
            "VIEWFINDER_TARGET_STAGED source=${source.name} generation=$generation " +
                "displayed=${effectiveViewfinderSource.name}/$effectiveViewfinderGeneration"
        )

        // Switching only the display route must not wait for another Camera2 RAW image when the
        // selected RAW producer is already warm. Re-prime the renderer from the newest complete
        // frame of the current generation. Native retain happens inside offerBorrowedHardwareBuffer
        // before the scoped ring borrow returns, so this does not extend Image/HardwareBuffer
        // ownership or disturb the near-ZSL ring. Hard starts / producer resets naturally no-op
        // here because their newly activated generation has no complete frames yet.
        if (source != ViewfinderEffectiveSource.YUV) {
            primeRawViewfinderFromWarmBuffer(source, generation, rawPreviewRouteRevision)
        }
    }

    private fun primeRawViewfinderFromWarmBuffer(
        source: ViewfinderEffectiveSource,
        generation: Int,
        routeRevision: Long
    ) {
        val timestamp = ringBuffer.latestImageTimestamp(generation) ?: return
        com.bncam.core.debug.RawPreviewFirstActivationTrace.rawFrameAvailable(
            source = source.name,
            generation = generation,
            sensorTimestampNs = timestamp,
            origin = "warm_ring_prime"
        )
        val recentMetadata = if (lastCaptureResultGeneration == generation) {
            lastCaptureResult as? TotalCaptureResult
        } else {
            null
        }

        if (rawPreviewConfiguredGeneration != generation || rawPreviewRenderer.currentConfig == null) {
            ensureRawPreviewConfig(recentMetadata, generation)
        }

        val offered = ringBuffer.withBorrowedImageFrame(timestamp, generation) { hardwareBuffer ->
            if (generation == pipelineGeneration && source == targetViewfinderSource &&
                generation == targetViewfinderGeneration && routeRevision == rawPreviewRouteRevision
            ) {
                rawPreviewRenderer.offerBorrowedHardwareBuffer(
                    buffer = hardwareBuffer,
                    sensorTimestampNs = timestamp,
                    pipelineGeneration = generation
                )
            }
        }
        Log.i(
            tag,
            "RAW_VIEWFINDER_WARM_PRIME source=${source.name} generation=$generation " +
                "timestampNs=$timestamp offered=$offered"
        )
    }

    /**
     * A RAW route can be staged before its calibration snapshot is ready. Once configuration
     * becomes valid, immediately hand the newest already-resident RAW frame to the renderer rather
     * than waiting for a future ImageReader callback (or, accidentally, for shutter capture).
     */
    private fun offerLatestWarmRawFrameAfterConfig(
        source: ViewfinderEffectiveSource,
        generation: Int,
        routeRevision: Long
    ) {
        if (source == ViewfinderEffectiveSource.YUV ||
            generation != pipelineGeneration || generation != targetViewfinderGeneration ||
            source != targetViewfinderSource || routeRevision != rawPreviewRouteRevision ||
            rawPreviewConfiguredGeneration != generation
        ) return
        val timestamp = ringBuffer.latestImageTimestamp(generation) ?: return
        ringBuffer.withBorrowedImageFrame(timestamp, generation) { hardwareBuffer ->
            if (generation == pipelineGeneration && generation == targetViewfinderGeneration &&
                source == targetViewfinderSource && routeRevision == rawPreviewRouteRevision &&
                rawPreviewConfiguredGeneration == generation
            ) {
                rawPreviewRenderer.offerBorrowedHardwareBuffer(
                    buffer = hardwareBuffer,
                    sensorTimestampNs = timestamp,
                    pipelineGeneration = generation
                )
            }
        }
    }

    private fun markViewfinderProducerFrameReady(
        source: ViewfinderEffectiveSource,
        generation: Int,
        sensorTimestampNs: Long
    ) {
        synchronized(pipelineLock) {
            val pending = pendingViewfinderDisplayTransition ?: return
            if (pending.generation != generation || pending.source != source) return
            pending.producerFrameReady = true
            if (sensorTimestampNs > 0L && pending.firstProducerTimestampNs == Long.MIN_VALUE) {
                pending.firstProducerTimestampNs = sensorTimestampNs
            }
        }
    }

    private fun commitViewfinderDisplayTransition(
        source: ViewfinderEffectiveSource,
        generation: Int,
        reason: String
    ): Boolean {
        val listener: ((ViewfinderEffectiveSource, Int) -> Unit)?
        synchronized(pipelineLock) {
            val pending = pendingViewfinderDisplayTransition ?: return false
            if (pending.source != source || pending.generation != generation) return false
            if (pending.requiresProducerFrame && !pending.producerFrameReady) return false
            if (generation != pipelineGeneration || source != targetViewfinderSource ||
                generation != targetViewfinderGeneration
            ) return false
            effectiveViewfinderSource = source
            effectiveViewfinderGeneration = generation
            pendingViewfinderDisplayTransition = null
            listener = effectiveViewfinderListener
        }
        listener?.invoke(source, generation)
        finishViewfinderRebuildVisualTransition(
            generation = generation,
            reason = "DISPLAY_COMMITTED:$reason"
        )
        Log.i(
            tag,
            "VIEWFINDER_DISPLAY_COMMITTED source=${source.name} generation=$generation reason=$reason"
        )
        return true
    }

    /**
     * Final viewfinder authority is granted only from presentation truth.
     *
     * RAW uses EGL display-present-time. YUV uses the first UI-vsync after a successful OES GL
     * submit because GLSurfaceView does not expose a SurfaceFlinger present fence for that path.
     * Renderer publication / SurfaceTexture arrival alone must never commit a display transition.
     */
    fun reportViewfinderFramePresented(
        source: ViewfinderEffectiveSource,
        generation: Int,
        sensorTimestampNs: Long,
        presentationTimestampNs: Long,
        presentationSignal: String,
        producerKind: RawPreviewProducerKind? = null
    ) {
        if (generation != pipelineGeneration || generation != targetViewfinderGeneration ||
            source != targetViewfinderSource
        ) {
            Log.i(
                tag,
                "VIEWFINDER_PRESENTATION_STALE source=${source.name} generation=$generation " +
                    "target=${targetViewfinderSource.name}/$targetViewfinderGeneration " +
                    "activeGeneration=$pipelineGeneration signal=$presentationSignal"
            )
            return
        }

        markViewfinderProducerFrameReady(source, generation, sensorTimestampNs)

        if (source != ViewfinderEffectiveSource.YUV &&
            producerKind == RawPreviewProducerKind.CUSTOM_IMAGE_READER
        ) {
            val binding = customRawPreviewBinding
            if (binding != null && binding.generation == generation && binding.source == source &&
                customRawPreviewDisabledGeneration != generation
            ) {
                rawPreviewProducerAuthorityTracker.customFramePresented(generation)
                customRawPreviewPresentedReadyGeneration = generation
                Log.i(
                    tag,
                    "CUSTOM_RAW_PREVIEW_PRESENTATION_PROVEN source=${source.name} generation=$generation " +
                        "timestampNs=$sensorTimestampNs signal=$presentationSignal"
                )
            }
        }

        val reason = if (source == ViewfinderEffectiveSource.YUV) {
            "FIRST_PRESENTED_YUV_FRAME"
        } else {
            "FIRST_PRESENTED_RAW_FRAME"
        }
        if (commitViewfinderDisplayTransition(source, generation, reason)) {
            if (source != ViewfinderEffectiveSource.YUV) {
                com.bncam.core.debug.RawPreviewFirstActivationTrace.displayTransitionCommitted(
                    source = source.name,
                    generation = generation,
                    sensorTimestampNs = sensorTimestampNs
                )
            }
            com.bncam.core.debug.DiagnosticsAggregator.record(
                stream = com.bncam.core.debug.DiagnosticsAggregator.Stream.PERFORMANCE,
                scope = "VIEWFINDER",
                section = "DISPLAY AUTHORITY COMMITTED",
                content = "source=${source.name};generation=$generation;" +
                    "sensorTimestampNs=$sensorTimestampNs;presentationTimestampNs=$presentationTimestampNs;" +
                    "signal=$presentationSignal;producer=${producerKind?.name ?: "YUV_OES"}"
            )
        }
    }

    /** Local presentation failure is diagnostic only; it must not tear down Camera2 or capture. */
    fun reportViewfinderPresentationFailure(
        source: ViewfinderEffectiveSource,
        generation: Int,
        sensorTimestampNs: Long,
        reason: String
    ) {
        if (generation != targetViewfinderGeneration || source != targetViewfinderSource) return
        Log.w(
            tag,
            "VIEWFINDER_PRESENTATION_NOT_PROVEN source=${source.name} generation=$generation " +
                "timestampNs=$sensorTimestampNs reason=$reason retaining=${effectiveViewfinderSource.name}/$effectiveViewfinderGeneration"
        )
        com.bncam.core.debug.DiagnosticsAggregator.record(
            stream = com.bncam.core.debug.DiagnosticsAggregator.Stream.PERFORMANCE,
            scope = "VIEWFINDER",
            section = "DISPLAY PRESENTATION FAILURE",
            content = "source=${source.name};generation=$generation;sensorTimestampNs=$sensorTimestampNs;" +
                "reason=$reason;retained=${effectiveViewfinderSource.name}/$effectiveViewfinderGeneration"
        )
    }

    /**
     * SurfaceTexture arrival proves only that the YUV producer is alive. The actual route commit is
     * deferred until FocusPeakingView has submitted that OES texture and reached the next UI-vsync.
     */
    fun reportYuvViewfinderFrameAvailable(surfaceTimestampNs: Long) {
        val stagedGeneration = synchronized(pipelineLock) {
            pendingViewfinderDisplayTransition
                ?.takeIf { it.source == ViewfinderEffectiveSource.YUV }
                ?.generation
        } ?: return
        markViewfinderProducerFrameReady(
            ViewfinderEffectiveSource.YUV,
            stagedGeneration,
            surfaceTimestampNs
        )
    }

    private fun customRawPreviewStallThresholdNs(generation: Int): Long {
        val timing = ringBuffer.streamTimingEstimate()
        val cadenceNs = ((timing.frameDurationMedianMs ?: 33.3) * 1_000_000.0).toLong()
            .coerceAtLeast(8_000_000L)
        val exposureNs = if (lastCaptureResultGeneration == generation) {
            (lastCaptureResult as? TotalCaptureResult)
                ?.get(CaptureResult.SENSOR_EXPOSURE_TIME)
                ?.coerceAtLeast(0L) ?: 0L
        } else 0L
        val expectedIntervalNs = maxOf(cadenceNs, exposureNs)
        return maxOf(1_000_000_000L, expectedIntervalNs * 6L)
    }

    private fun isCustomRawPreviewFresh(
        generation: Int,
        nowElapsedNs: Long = android.os.SystemClock.elapsedRealtimeNanos()
    ): Boolean {
        if (customRawPreviewDisabledGeneration == generation) return false
        if (customRawPreviewBinding?.generation != generation || customRawPreviewReader == null ||
            customRawPreviewPresentedReadyGeneration != generation
        ) return false
        val last = customRawPreviewLastFrameElapsedNs
        if (last <= 0L) return false
        val inputFresh = nowElapsedNs - last <= customRawPreviewStallThresholdNs(generation)
        return rawPreviewProducerAuthorityTracker.maySuppressCanonical(generation, inputFresh)
    }

    private fun disableCustomRawPreviewForGeneration(generation: Int, reason: String) {
        if (generation != pipelineGeneration || customRawPreviewBinding?.generation != generation) return
        if (customRawPreviewDisabledGeneration == generation && customRawPreviewPresentedReadyGeneration != generation) return
        customRawPreviewDisabledGeneration = generation
        customRawPreviewPresentedReadyGeneration = -1
        rawPreviewProducerAuthorityTracker.reset(generation)
        recordRawSessionOutputDiagnostic(
            section = "CUSTOM_RAW_PREVIEW_FALLBACK_CANONICAL",
            content = "generation=$generation;reason=$reason;" +
                "lastCustomFrameElapsedNs=$customRawPreviewLastFrameElapsedNs;" +
                "lastCustomSensorTimestampNs=$customRawPreviewLastSensorTimestampNs;" +
                "customFrameCount=$customRawPreviewFrameCount;${rawRingPressureSummary()}"
        )
    }

    private fun offerRawPreviewImage(timestamp: Long, generation: Int) {
        // RAW preview owns an independently retained AHardwareBuffer handle and therefore does not
        // need to stop while capture leases the warm-buffer frame. Keep the live view running
        // during single- and multi-frame capture; the one-pending-frame renderer naturally drops
        // obsolete preview work if the GPU is busy instead of freezing the last displayed frame.
        if (targetViewfinderSource == ViewfinderEffectiveSource.YUV) return
        if (isCustomRawPreviewFresh(generation)) return
        val recentMetadata = if (lastCaptureResultGeneration == generation) {
            lastCaptureResult as? TotalCaptureResult
        } else null
        val ringHandoffTrace = com.bncam.ui.screens.capture.RawPreviewTrace.beginRingHandoff()
        try {
            ringBuffer.withBorrowedImageFrame(timestamp, generation) { hardwareBuffer ->
                if (rawPreviewConfiguredGeneration != generation) {
                    recentMetadata?.let { ensureRawPreviewConfig(it, generation) }
                }
                rawPreviewRenderer.offerBorrowedHardwareBuffer(hardwareBuffer, timestamp, generation)
            }
        } finally {
            com.bncam.ui.screens.capture.RawPreviewTrace.end(ringHandoffTrace)
        }
    }

    private data class RawPreviewDemosaicDecision(
        val bridgeMode: Int,
        val reason: String
    )

    private fun resolveRawPreviewDemosaic(
        requestedBridgeMode: Int,
        captureResult: CaptureResult,
        sensitivityIso: Int,
        exposureTimeNs: Long
    ): RawPreviewDemosaicDecision {
        if (requestedBridgeMode != com.bncam.core.quality.DemosaicMode.AUTO.bridgeValue) {
            return RawPreviewDemosaicDecision(requestedBridgeMode.coerceIn(1, 3), "manual_profile")
        }

        val exposureMs = exposureTimeNs.coerceAtLeast(0L) / 1_000_000.0f
        val isoPressure = ((sensitivityIso - 400f) / 1200f).coerceIn(0f, 1f)
        val exposurePressure = ((exposureMs - 30f) / 90f).coerceIn(0f, 1f)
        val noisePressure = max(isoPressure, 0.65f * exposurePressure)
        val afState = captureResult.get(CaptureResult.CONTROL_AF_STATE) ?: lastAfState
        val afLockedOrFocused = afState == CaptureResult.CONTROL_AF_STATE_PASSIVE_FOCUSED ||
            afState == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED
        val sensorTimestamp = captureResult.get(CaptureResult.SENSOR_TIMESTAMP) ?: 0L
        val prediction = sensorTimestamp.takeIf { it > 0L }?.let {
            predictiveAfTracker.predictFocusDistance(it)
        }
        val predictiveStable = prediction == null || prediction.confidence < 0.30f ||
            abs(prediction.focusVelocityDioptersPerSec) <= 0.12f
        val facePresent = _detectedFaces.value.isNotEmpty()

        // Robustness wins first in genuinely noisy/long-exposure conditions, irrespective of
        // subject class. This prevents Auto preview from forcing a detail-biased demosaic where
        // chroma/read noise is the dominant information source.
        if (sensitivityIso >= 1000 || noisePressure >= 0.62f) {
            return RawPreviewDemosaicDecision(
                com.bncam.core.quality.DemosaicMode.NORMAL.bridgeValue,
                "auto_malvar_noisy iso=$sensitivityIso exposureMs=$exposureMs noise=$noisePressure"
            )
        }

        // Portrait evidence favours RCD's stable opponent-colour reconstruction. This is a bias,
        // not a hard person=>algorithm contract; the noise gate above can still select Malvar.
        if (facePresent) {
            return RawPreviewDemosaicDecision(
                com.bncam.core.quality.DemosaicMode.BILINEAR.bridgeValue,
                "auto_rcd_portrait noise=$noisePressure"
            )
        }

        // A genuinely settled focus trajectory and sufficient SNR allow the more detail-oriented
        // AMAZE preview. Predictive AF velocity prevents a transient AF scan from masquerading as
        // a static detailed subject.
        if (afLockedOrFocused && predictiveStable && noisePressure <= 0.30f) {
            return RawPreviewDemosaicDecision(
                com.bncam.core.quality.DemosaicMode.QUALITY.bridgeValue,
                "auto_amaze_static_detail afState=$afState velocity=${prediction?.focusVelocityDioptersPerSec ?: 0f} noise=$noisePressure"
            )
        }

        return RawPreviewDemosaicDecision(
            com.bncam.core.quality.DemosaicMode.BILINEAR.bridgeValue,
            "auto_rcd_balanced afState=$afState velocity=${prediction?.focusVelocityDioptersPerSec ?: 0f} noise=$noisePressure"
        )
    }

    private fun rawPreviewFocusDetailPriority(captureResult: CaptureResult): Float {
        val afMode = captureResult.get(CaptureResult.CONTROL_AF_MODE)
        val afState = captureResult.get(CaptureResult.CONTROL_AF_STATE) ?: lastAfState
        if (afMode == CaptureResult.CONTROL_AF_MODE_OFF) return 1.0f

        val focused = afState == CaptureResult.CONTROL_AF_STATE_PASSIVE_FOCUSED ||
            afState == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED
        val scanning = afState == CaptureResult.CONTROL_AF_STATE_PASSIVE_SCAN ||
            afState == CaptureResult.CONTROL_AF_STATE_ACTIVE_SCAN
        // Scanning is exactly when the photographer needs maximum live focus readability. Do not
        // soften the preview while AF is moving; noise protection remains ISO/exposure/tonal based
        // in the shader and therefore independent from this focus-judgement priority.
        if (scanning) return 1.0f
        if (!focused) return 0.85f

        val sensorTimestamp = captureResult.get(CaptureResult.SENSOR_TIMESTAMP) ?: 0L
        val prediction = sensorTimestamp.takeIf { it > 0L }?.let {
            predictiveAfTracker.predictFocusDistance(it)
        }
        val velocity = prediction?.focusVelocityDioptersPerSec?.let(::abs) ?: 0.0f
        val predictiveConfidence = prediction?.confidence ?: 0.0f
        return when {
            predictiveConfidence >= 0.30f && velocity > 0.20f -> 0.75f
            predictiveConfidence >= 0.30f && velocity > 0.12f -> 0.88f
            else -> 1.0f
        }
    }

    private fun ensureRawPreviewConfig(result: TotalCaptureResult?, generation: Int) {
        val nowMs = android.os.SystemClock.elapsedRealtime()
        val identity = synchronized(pipelineLock) { activePipelineIdentity } ?: return
        val source = ViewfinderEffectiveSource.fromImageFormat(identity.bufferFormat)
        val profileId = viewfinderProfileId.takeUnless { it == "unknown" }
            ?: identity.requestedProfileId
        val routeRevision = rawPreviewRouteRevision
        if (generation != pipelineGeneration || source != targetViewfinderSource ||
            generation != targetViewfinderGeneration || source == ViewfinderEffectiveSource.YUV
        ) return

        if (rawPreviewConfiguredGeneration != generation || rawPreviewRenderer.currentConfig == null) {
            buildBootstrapRawPreviewConfig(
                identity = identity,
                result = result,
                generation = generation,
                source = source,
                profileId = profileId
            )?.let { bootstrap ->
                rawPreviewRenderer.configure(bootstrap)
                rawPreviewConfiguredGeneration = generation
                com.bncam.core.debug.RawPreviewFirstActivationTrace.configurationReady(
                    source = source.name,
                    generation = generation,
                    bootstrap = true
                )
                lastRawPreviewConfigRefreshMs = nowMs
                offerLatestWarmRawFrameAfterConfig(source, generation, routeRevision)
                Log.i(
                    tag,
                    "RAW_PREVIEW_BOOTSTRAP source=${source.name} generation=$generation " +
                        "white=${bootstrap.whiteLevel} cfa=${bootstrap.cfaPattern}"
                )
            }
        }

        val current = rawPreviewRenderer.currentConfig
        if (rawPreviewConfiguredGeneration == generation &&
            current != null && !current.isBootstrap &&
            nowMs - lastRawPreviewConfigRefreshMs < RAW_PREVIEW_CONFIG_REFRESH_MS
        ) return
        if (rawPreviewConfigBuildInFlight) return
        rawPreviewConfigBuildInFlight = true
        rawPreviewScope.launch {
            try {
                val repo = SettingsRepository(context)
                val captureMode = repo.getProfileCaptureModeFlow(profileId).first()
                val preferences = RenderQualityConfig.snapshotPreferences(
                    repo = repo,
                    profileId = profileId,
                    frameSourceFormat = identity.bufferFormat,
                    captureMode = captureMode
                )
                val lensSettings = repo.readLensHardwareSettingsSnapshot(
                    identity.physicalCameraId ?: identity.logicalCameraId
                )
                val calibrationCameraId = identity.physicalCameraId ?: identity.logicalCameraId
                val characteristics = cameraManager.getCameraCharacteristics(calibrationCameraId)
                val calibrationResult = result?.let { previewCaptureResult(it, identity.physicalCameraId) }
                val quality = RenderQualityConfig.load(
                    repo = repo,
                    profileId = profileId,
                    frameSourceFormat = identity.bufferFormat,
                    captureMode = captureMode,
                    characteristics = characteristics,
                    captureResult = calibrationResult,
                    lensHardwareSettings = lensSettings,
                    preferenceSnapshot = preferences,
                    stableAutoWhiteBalance = stableAutoWhiteBalanceSnapshotForActiveCamera()
                )
                val previewRawContract = RawWhiteDomainBinding.bindForQualityConfig(
                    contract = RawBlackDomainBinding.bindForQualityConfig(
                        contract = RawDomainContractResolver.resolve(
                            lensId = calibrationCameraId,
                            sourceFormat = identity.bufferFormat,
                            width = identity.width,
                            height = identity.height,
                            characteristics = characteristics,
                            captureResult = calibrationResult,
                            qualityConfig = quality
                        ),
                        qualityConfig = quality
                    ),
                    qualityConfig = quality
                )
                val previewDevelopedLevels =
                    RawPreviewCalibrationTransform.developedLevelsInSourceDomain(previewRawContract)
                val sensitivityIso = (calibrationResult?.get(CaptureResult.SENSOR_SENSITIVITY) ?: 100)
                    .coerceAtLeast(1)
                val exposureTimeNs = (calibrationResult?.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: 0L)
                    .coerceAtLeast(0L)
                // FASE 5: automatic RAW preview exposure is owned by the signed Vulkan spatial map.
                // Do not keep the former ISO/post-RAW-boost global multiplier as a second owner.
                val previewExposureGain = 1.0f
                val previewDemosaic = if (calibrationResult != null) {
                    resolveRawPreviewDemosaic(
                        requestedBridgeMode = quality.demosaic.requestedMode.bridgeValue,
                        captureResult = calibrationResult,
                        sensitivityIso = sensitivityIso,
                        exposureTimeNs = exposureTimeNs
                    )
                } else {
                    RawPreviewDemosaicDecision(
                        quality.demosaic.requestedMode.bridgeValue.coerceIn(1, 3),
                        "fallback_without_result"
                    )
                }
                val exactPreviewColorPair = calibrationResult?.let { current ->
                    runCatching {
                        current.get(CaptureResult.COLOR_CORRECTION_GAINS) != null &&
                            current.get(CaptureResult.COLOR_CORRECTION_TRANSFORM) != null
                    }.getOrDefault(false)
                } == true
                val previewWbGains = if (quality.whiteBalanceGains.fromMetadata && !exactPreviewColorPair) {
                    stabilizeRawPreviewAutoWb(quality.whiteBalanceGains.toNativeArray(), generation)
                } else {
                    // Exact Camera2 gains and transform are an atomic pair; never replace only
                    // the WB half with a historical stable value.
                    quality.whiteBalanceGains.toNativeArray()
                }
                val config = RawPreviewRenderConfig(
                    source = source,
                    pipelineGeneration = generation,
                    profileId = profileId,
                    cfaPattern = quality.cfaPattern,
                    demosaicMode = previewDemosaic.bridgeMode,
                    blackLevels = previewDevelopedLevels.blackLevels,
                    whiteLevel = previewDevelopedLevels.whiteLevel,
                    wbGains = previewWbGains,
                    colorMatrix = quality.colorCorrectionMatrix.toNativeArray(),
                    exposureGain = previewExposureGain,
                    captureSensitivityIso = sensitivityIso,
                    captureExposureTimeNs = exposureTimeNs,
                    physicalGreenNoiseSo = rawPreviewPhysicalGreenNoiseSo(
                        quality.cfaPattern, calibrationResult, quality.finalCalibration
                    ),
                    focusDetailPriority = calibrationResult?.let { rawPreviewFocusDetailPriority(it) } ?: 0f,
                    profileToneExposure = quality.profileToneTuning.exposure,
                    profileToneHighlights = quality.profileToneTuning.highlights,
                    profileToneShadows = quality.profileToneTuning.shadows,
                    profileToneWhites = quality.profileToneTuning.whites,
                    profileToneBlacks = quality.profileToneTuning.blacks,
                    profileToneContrast = quality.profileToneTuning.contrast,
                    profileLocalToneBias = quality.profileToneTuning.localToneBias,
                    profileSaturation = quality.profileColorTuning.saturation,
                    profileContrast = quality.profileColorTuning.contrast,
                    profileVibrance = quality.profileColorTuning.vibrance,
                    profilePop = quality.profileColorTuning.pop,
                    profileColorRecovery = quality.profileColorTuning.colorRecovery,
                    // Capture sharpening/NR are not live-viewfinder stages. The RAW preview has
                    // its own conservative base-detail pass for focus readability; stacking profile
                    // detail/NR here made cadence and appearance depend on capture processing.
                    profileDetailAmount = 0f,
                    profileDetailRadius = 1f,
                    profileDetailDetail = 0.25f,
                    profileDetailMasking = 0f,
                    profileNrLuminance = 0f,
                    profileNrLuminanceDetail = 0.5f,
                    profileNrLuminanceContrast = 0f,
                    profileNrColor = 0f,
                    profileNrColorDetail = 0.5f,
                    profileNrColorSmoothness = 0.5f,
                    toneCurve = quality.curves.toneNodes.toFloatArray(),
                    gammaCurve = quality.curves.gammaNodes.toFloatArray(),
                    sectionCurve = quality.curves.sectionNodes.toFloatArray(),
                    rotationDegrees = rawPreviewRotationDegrees(characteristics)
                )
                if (generation == pipelineGeneration && source == targetViewfinderSource &&
                    generation == targetViewfinderGeneration && routeRevision == rawPreviewRouteRevision
                ) {
                    rawPreviewRenderer.configure(config)
                    rawPreviewConfiguredGeneration = generation
                    com.bncam.core.debug.RawPreviewFirstActivationTrace.configurationReady(
                        source = source.name,
                        generation = generation,
                        bootstrap = false
                    )
                    lastRawPreviewConfigRefreshMs = android.os.SystemClock.elapsedRealtime()
                    offerLatestWarmRawFrameAfterConfig(source, generation, routeRevision)
                    Log.i(
                        tag,
                        "RAW_PREVIEW_DEMOSAIC requested=${quality.demosaic.requestedMode.displayName} " +
                            "effectiveBridge=${previewDemosaic.bridgeMode} reason=${previewDemosaic.reason}"
                    )
                    Log.i(
                        tag,
                        "RAW_PREVIEW_WHITE_AUTHORITY white=${previewDevelopedLevels.whiteLevel} " +
                            "source=${previewDevelopedLevels.source} payloadWhite=${previewRawContract.payloadWhiteLevel} " +
                            "developedWhite=${previewRawContract.developedRawWhiteLevel}"
                    )
                }
            } catch (error: Exception) {
                Log.w(tag, "RAW preview calibration refresh failed", error)
            } finally {
                rawPreviewConfigBuildInFlight = false
            }
        }
    }

    private fun rawPreviewPhysicalGreenNoiseSo(
        cfaPattern: Int,
        captureResult: CaptureResult?,
        finalCalibration: com.bncam.core.quality.FinalSensorCalibration? = null
    ): FloatArray {
        finalCalibration?.noiseSnapshot?.let { snapshot ->
            val s = snapshot.effectiveS
            val o = snapshot.effectiveO
            if (s.size >= 4 && o.size >= 4 && snapshot.signalModelConfidence > 0f &&
                s.all { it.isFinite() && it >= 0.0 } && o.all { it.isFinite() && it >= 0.0 }) {
                return floatArrayOf(
                    (0.5 * (s[1] + s[2])).toFloat(),
                    (0.5 * (o[1] + o[2])).toFloat(),
                    snapshot.signalModelConfidence.coerceIn(0f, 1f)
                )
            }
        }
        val profile = runCatching { captureResult?.get(CaptureResult.SENSOR_NOISE_PROFILE) }.getOrNull()
        if (profile != null && profile.size >= 4) {
            // Android orders pairs by CFA plane. RGGB/BGGR have green at 1/2; GRBG/GBRG at 0/3.
            val greenIndices = when (cfaPattern) {
                CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_GRBG,
                CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_GBRG -> intArrayOf(0, 3)
                else -> intArrayOf(1, 2)
            }
            val firstGreen = profile.getOrNull(greenIndices[0])
            val secondGreen = profile.getOrNull(greenIndices[1])
            if (firstGreen != null && secondGreen != null) {
                val firstSignal = firstGreen.first
                val firstOffset = firstGreen.second
                val secondSignal = secondGreen.first
                val secondOffset = secondGreen.second
                if (firstSignal != null && firstOffset != null && secondSignal != null && secondOffset != null &&
                    firstSignal.isFinite() && firstOffset.isFinite() &&
                    secondSignal.isFinite() && secondOffset.isFinite() &&
                    firstSignal >= 0.0 && firstOffset >= 0.0 &&
                    secondSignal >= 0.0 && secondOffset >= 0.0
                ) {
                    return floatArrayOf(
                        (0.5 * (firstSignal + secondSignal)).toFloat(),
                        (0.5 * (firstOffset + secondOffset)).toFloat(),
                        1.0f
                    )
                }
            }
        }
        return floatArrayOf(0f, 0f, 0f)
    }

    private fun buildBootstrapRawPreviewConfig(
        identity: PipelineIdentity,
        result: TotalCaptureResult?,
        generation: Int,
        source: ViewfinderEffectiveSource,
        profileId: String
    ): RawPreviewRenderConfig? = runCatching {
        val calibrationCameraId = identity.physicalCameraId ?: identity.logicalCameraId
        val characteristics = cameraManager.getCameraCharacteristics(calibrationCameraId)
        val calibrationResult = result?.let { previewCaptureResult(it, identity.physicalCameraId) }
        val dynamicWhite = calibrationResult?.get(CaptureResult.SENSOR_DYNAMIC_WHITE_LEVEL)
            ?.takeIf { it > 0 }
        val staticWhite = characteristics.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL)
            ?.takeIf { it > 0 }
        val reportedWhite = dynamicWhite ?: staticWhite
        // A packed RAW10 sample has a structurally known 10-bit source ceiling, so bootstrap can
        // render it safely while the full developed-white configuration is loading. RAW_SENSOR is
        // only a 16-bit container: without Camera2 white metadata, 65535 would invent a physical
        // saturation point and darken metering/preview. In that case skip bootstrap and let the
        // authoritative async configuration fail closed instead.
        if (reportedWhite == null && source != ViewfinderEffectiveSource.RAW10) {
            Log.w(
                tag,
                "RAW_PREVIEW_BOOTSTRAP_WHITE_UNAVAILABLE source=${source.name} " +
                    "lens=$calibrationCameraId; waiting for authoritative configuration"
            )
            return@runCatching null
        }
        val sourceAuthorityWhite = reportedWhite ?: 1023
        val staticBlack = characteristics.get(CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN)
        val reportedBlack = calibrationResult?.get(CaptureResult.SENSOR_DYNAMIC_BLACK_LEVEL)
            ?.takeIf { it.size >= 4 }
            ?.copyOf(4)
            ?: floatArrayOf(
                staticBlack?.getOffsetForIndex(0, 0)?.toFloat() ?: 0f,
                staticBlack?.getOffsetForIndex(1, 0)?.toFloat() ?: 0f,
                staticBlack?.getOffsetForIndex(0, 1)?.toFloat() ?: 0f,
                staticBlack?.getOffsetForIndex(1, 1)?.toFloat() ?: 0f
            )
        val nativeWhite = if (source == ViewfinderEffectiveSource.RAW10) 1023 else sourceAuthorityWhite
        val levelScale = nativeWhite.toFloat() / sourceAuthorityWhite.coerceAtLeast(1).toFloat()
        val nativeBlack = FloatArray(4) { index ->
            (reportedBlack[index] * levelScale).coerceIn(0f, nativeWhite.coerceAtLeast(2) - 1f)
        }
        val rggb = calibrationResult?.get(CaptureResult.COLOR_CORRECTION_GAINS)
        val wb = rggb?.let {
            stabilizeRawPreviewAutoWb(
                floatArrayOf(it.red, it.greenEven, it.greenOdd, it.blue),
                generation
            )
        } ?: floatArrayOf(1f, 1f, 1f, 1f)
        val sensitivityIso = (calibrationResult?.get(CaptureResult.SENSOR_SENSITIVITY) ?: 100)
            .coerceAtLeast(1)
        val exposureTimeNs = (calibrationResult?.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: 0L)
            .coerceAtLeast(0L)
        // Bootstrap obeys the same ownership rule as steady-state preview.
        val exposureGain = 1.0f
        fun linearCurve(size: Int) = FloatArray(size) { index ->
            index.toFloat() / (size - 1).coerceAtLeast(1).toFloat()
        }
        val bootstrapCfaPattern = characteristics.get(
            CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT
        ) ?: CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_RGGB
        RawPreviewRenderConfig(
            source = source,
            pipelineGeneration = generation,
            profileId = profileId,
            cfaPattern = bootstrapCfaPattern,
            // Bootstrap is deliberately deterministic and matches the noise-robust BnCam product default.
            demosaicMode = com.bncam.core.quality.DemosaicMode.DEFAULT.bridgeValue,
            blackLevels = nativeBlack,
            whiteLevel = nativeWhite,
            wbGains = wb,
            colorMatrix = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f),
            exposureGain = exposureGain,
            captureSensitivityIso = sensitivityIso,
            captureExposureTimeNs = exposureTimeNs,
            physicalGreenNoiseSo = rawPreviewPhysicalGreenNoiseSo(
                bootstrapCfaPattern, calibrationResult, null
            ),
            focusDetailPriority = calibrationResult?.let { rawPreviewFocusDetailPriority(it) } ?: 0f,
            profileToneExposure = 0f,
            profileToneHighlights = 0f,
            profileToneShadows = 0f,
            profileToneWhites = 0f,
            profileToneBlacks = 0f,
            profileToneContrast = 0f,
            profileLocalToneBias = 0f,
            profileSaturation = 0f,
            profileContrast = 0f,
            profileVibrance = 0f,
            profilePop = 0f,
            profileColorRecovery = 0f,
            profileDetailAmount = 0f,
            profileDetailRadius = 1f,
            profileDetailDetail = 0.25f,
            profileDetailMasking = 0f,
            profileNrLuminance = 0f,
            profileNrLuminanceDetail = 0.5f,
            profileNrLuminanceContrast = 0f,
            profileNrColor = 0f,
            profileNrColorDetail = 0.5f,
            profileNrColorSmoothness = 0.5f,
            toneCurve = linearCurve(16),
            gammaCurve = linearCurve(16),
            sectionCurve = linearCurve(7),
            rotationDegrees = rawPreviewRotationDegrees(characteristics),
            isBootstrap = true
        )
    }.onFailure { error ->
        Log.w(tag, "RAW preview bootstrap configuration failed", error)
    }.getOrNull()

    private fun previewCaptureResult(
        result: TotalCaptureResult,
        physicalCameraId: String?
    ): CaptureResult? {
        if (physicalCameraId.isNullOrBlank()) return result
        val physical = runCatching { physicalCaptureResultOrNull(result, physicalCameraId) }
            .onFailure { failure ->
                Log.w(
                    "SensorAuthority",
                    "PHYSICAL_METADATA_UNAVAILABLE physicalCameraId=$physicalCameraId " +
                        "frameNumber=${result.frameNumber} sequenceId=${result.sequenceId}",
                    failure
                )
            }
            .getOrNull()
        if (physical == null) {
            Log.w(
                "SensorAuthority",
                "PHYSICAL_METADATA_UNAVAILABLE physicalCameraId=$physicalCameraId " +
                    "frameNumber=${result.frameNumber} sequenceId=${result.sequenceId}; logical parent not substituted"
            )
        }
        return physical
    }

    fun setPreviewOrientationCorrection(degrees: Int) {
        val normalized = normalizeRightAngle(degrees)
        if (previewOrientationCorrectionDegrees == normalized) return
        previewOrientationCorrectionDegrees = normalized
        // Display-only calibration: rebuild RAW preview render configuration, never Camera2.
        rawPreviewConfiguredGeneration = -1
        lastRawPreviewConfigRefreshMs = 0L
        Log.i(tag, "PREVIEW_ORIENTATION correction=$normalized")
    }

    @Suppress("DEPRECATION")
    private fun currentMlAnalysisRotationDegrees(): Int {
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val displayDegrees = when (windowManager.defaultDisplay.rotation) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
        val sensorDegrees = _sensorOrientation.value
        return if (_lensFacing.value == CameraCharacteristics.LENS_FACING_FRONT) {
            (sensorDegrees + displayDegrees) % 360
        } else {
            (sensorDegrees - displayDegrees + 360) % 360
        }
    }

    @Suppress("DEPRECATION")
    private fun rawPreviewRotationDegrees(characteristics: CameraCharacteristics): Int {
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val baseRotation = getJpegOrientation(characteristics, windowManager.defaultDisplay.rotation)
        return normalizeRightAngle(baseRotation + previewOrientationCorrectionDegrees)
    }

    private val _detectedFaces = MutableStateFlow<Array<Face>>(emptyArray())
    val detectedFaces = _detectedFaces.asStateFlow()

    private val _sensorRect =
        MutableStateFlow(Rect()) // Nodig om de gezichten op het scherm te mappen
    val sensorRect = _sensorRect.asStateFlow()

    private val _sensorOrientation = MutableStateFlow(90)
    val sensorOrientation = _sensorOrientation.asStateFlow()

    private val _lensFacing = MutableStateFlow(CameraCharacteristics.LENS_FACING_BACK)
    val lensFacing = _lensFacing.asStateFlow()

    private val _detectedQrCode = MutableStateFlow<String?>(null)
    val detectedQrCode = _detectedQrCode.asStateFlow()

    @Volatile private var histogramAnalysisEnabled = false
    @Volatile private var qrAnalysisEnabled = false
    @Volatile private var objectTrackingAnalysisEnabled = false
    @Volatile private var portraitAnalysisEnabled = false
    private val rawPreviewAnalysisFrameCounter = java.util.concurrent.atomic.AtomicLong(0L)
    private val yuvAnalysisFrameCounter = java.util.concurrent.atomic.AtomicLong(0L)
    private val yuvExposureSampleLock = Any()
    private var yuvExposureSampleBuffer: ByteBuffer? = null

    private fun refreshRawPreviewCompactAnalysisRequest() {
        val demand = RawPreviewAnalysisDemand(
            qrEnabled = qrAnalysisEnabled,
            objectTrackingEnabled = objectTrackingAnalysisEnabled,
            focusTrackingActive = _focusTrackingActive.value,
            portraitEnabled = portraitAnalysisEnabled,
            capturing = isCapturing
        )
        rawPreviewRenderer.setMlAnalysisRequested(
            RawPreviewFastPathPolicy.needsCompactNv21(demand)
        )
    }

    fun setOptionalAnalysisEnabled(
        histogram: Boolean,
        qr: Boolean,
        objectTracking: Boolean,
        portraitEffect: Boolean = false
    ) {
        histogramAnalysisEnabled = histogram
        qrAnalysisEnabled = qr
        objectTrackingAnalysisEnabled = objectTracking
        portraitAnalysisEnabled = portraitEffect
        refreshRawPreviewCompactAnalysisRequest()
        if (!histogram) {
            _liveHistogram.value = List(16) { 0f }
            _liveRgbHistogram.value = LiveRgbHistogram.empty()
        }
        if (!qr) _detectedQrCode.value = null
        if (!objectTracking) {
            stopFocusTracking(reason = "analysis_disabled", restoreConfiguredAf = true)
        }
        if (!portraitEffect) {
            latestPortraitMask.set(null)
            portraitSegmentationBusy.set(false)
        }
        Log.i(
            tag,
            "Optional analysis gates histogram=$histogram qr=$qr objectTracking=$objectTracking portrait=$portraitEffect"
        )
    }

    private suspend fun runEnabledYuvAnalysis(image: android.media.Image, frameIndex: Int, deviceRotation: Int) {
        // Live exposure statistics are advisory preview work. Do not submit this Vulkan analysis
        // while a shutter capture owns the production GPU path: the next preview frame will refresh
        // the statistics after capture without perturbing capture latency or resource ordering.
        if (!isCapturing && needsLiveExposureStatistics() && frameIndex % 3 == 0) {
            calculateExposureStatistics(image)
        }
        if (qrAnalysisEnabled && frameIndex % 10 == 0) scanQrCode(image, deviceRotation)
        if (objectTrackingAnalysisEnabled && _focusTrackingActive.value) trackObjectsInFrame(image, deviceRotation)
        if (portraitAnalysisEnabled && shouldRunPortraitSegmentation()) {
            runCatching {
                portraitSegmentationBusy.set(true)
                portraitSubjectSegmenter.segmentMediaImage(
                    image = image,
                    rotationDegrees = deviceRotation,
                    timestampNs = image.timestamp,
                    seed = currentPortraitSubjectSeed()
                )
            }.onSuccess { artifact ->
                publishPortraitMask(artifact, "YUV")
            }.onFailure { failure ->
                Log.w(tag, "Portrait YUV subject segmentation skipped: ${failure.message}")
            }
            portraitSegmentationBusy.set(false)
        }
    }

    private fun scheduleEnabledYuvAnalysis(
        timestampNs: Long,
        generation: Int,
        expectedFormat: Int,
        deviceRotation: Int
    ) {
        val wantsExposureStatistics = !isCapturing && needsLiveExposureStatistics()
        val wantsTrackingAnalysis = objectTrackingAnalysisEnabled && _focusTrackingActive.value && !isCapturing
        val wantsPortraitAnalysis = portraitAnalysisEnabled && !isCapturing
        if (!wantsExposureStatistics && !qrAnalysisEnabled && !wantsTrackingAnalysis && !wantsPortraitAnalysis) return
        yuvAnalysisRequests.trySend(
            YuvFrameAnalysisRequest(
                timestampNs = timestampNs,
                generation = generation,
                expectedFormat = expectedFormat,
                deviceRotation = deviceRotation
            )
        )
    }

    private suspend fun processEnabledYuvAnalysis(request: YuvFrameAnalysisRequest) {
        val lease = ringBuffer.leaseCompleteFrameForAnalysis(
            timestampNs = request.timestampNs,
            generationId = request.generation,
            expectedFormat = request.expectedFormat
        ) ?: return
        val analysisFrameIndex = yuvAnalysisFrameCounter.incrementAndGet().toInt()
        try {
            val image = lease.pair.image ?: return
            runEnabledYuvAnalysis(image, analysisFrameIndex, request.deviceRotation)
        } catch (e: Exception) {
            Log.w(tag, "Live YUV helper analysis skipped for this frame: ${e.message}")
        } finally {
            lease.release()
        }
    }

    private fun scheduleFocusConfidenceAnalysis(
        generation: Int,
        expectedFormat: Int
    ) {
        focusAnalysisRequests.trySend(
            BufferFrameAnalysisRequest(
                generation = generation,
                expectedFormat = expectedFormat
            )
        )
    }

    private fun resolveFocusAnalysisRegion(metadata: TotalCaptureResult): Pair<Rect?, Rect?> {
        val deviceId = cameraDevice?.id ?: return null to null
        val logicalChars = runCatching { cameraManager.getCameraCharacteristics(deviceId) }.getOrNull()
            ?: return null to null
        val activePhysicalId = synchronized(pipelineLock) {
            activePipelineIdentity
                ?.takeIf { it.cameraRouteKind == CameraRouteKind.LOGICAL_PHYSICAL }
                ?.physicalCameraId
        }
        val physicalMetadata = if (activePhysicalId != null) {
            physicalCaptureResultOrNull(metadata, activePhysicalId)
        } else {
            null
        }
        val physicalChars = activePhysicalId?.let { id ->
            runCatching { cameraManager.getCameraCharacteristics(id) }.getOrNull()
        }
        val geometryChars: CameraCharacteristics
        val resultForGeometry: CaptureResult
        if (activePhysicalId != null) {
            if (physicalMetadata == null || physicalChars == null) {
                Log.w(
                    "SensorAuthority",
                    "PHYSICAL_METADATA_UNAVAILABLE context=FOCUS_ANALYSIS physicalCameraId=$activePhysicalId " +
                        "frameNumber=${metadata.frameNumber}; logical geometry metadata not substituted"
                )
                return null to null
            }
            geometryChars = physicalChars
            resultForGeometry = physicalMetadata
        } else {
            geometryChars = logicalChars
            resultForGeometry = metadata
        }
        val activeArray = geometryChars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
            ?: return null to null
        val afRegion = resultForGeometry.get(CaptureResult.CONTROL_AF_REGIONS)
            ?.asSequence()
            ?.filter { it.meteringWeight > 0 && !it.rect.isEmpty }
            ?.maxByOrNull { it.meteringWeight }
            ?.rect
            ?.let(::Rect)

        // For a physical stream, focus confidence must use that physical sensor's coordinate
        // domain. Falling back to the logical active array is a real spatial offset on devices
        // whose logical owner and selected tele/ultrawide have different active-array geometry.
        val coordinateBounds = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            resultForGeometry.get(CaptureResult.CONTROL_ZOOM_RATIO) != null
        ) {
            Rect(activeArray)
        } else {
            resultForGeometry.get(CaptureResult.SCALER_CROP_REGION)?.let(::Rect) ?: Rect(activeArray)
        }
        val clippedRegion = afRegion?.apply {
            if (!intersect(coordinateBounds)) setEmpty()
        }?.takeUnless { it.isEmpty }
        return clippedRegion to coordinateBounds
    }

    private suspend fun processFocusConfidenceAnalysis(request: BufferFrameAnalysisRequest) {
        // The channel is intentionally conflated only as a wake-up signal. Once awake, process
        // every still-resident complete frame that has not yet received focus metrics. This avoids
        // an unbounded timestamp backlog without silently turning focus confidence into a
        // latest-frame-only feature.
        while (true) {
            val lease = ringBuffer.leaseNextCompleteFrameForFocusAnalysis(
                generationId = request.generation,
                expectedFormat = request.expectedFormat
            ) ?: return
            val expectedVersion = lease.pair.frameVersion
            val timestampNs = lease.pair.timestamp
            try {
                val image = lease.pair.image ?: return
                val metadata = lease.pair.metadata ?: return
                handleDefaultRawWarmFrameMotion(
                    image = image,
                    generation = request.generation,
                    expectedFormat = request.expectedFormat
                )
                val (focusRoi, focusCoordinateBounds) = resolveFocusAnalysisRegion(metadata)
                val metrics = com.bncam.core.quality.FocusConfidenceEngine.evaluate(
                    image = image,
                    metadata = metadata,
                    roiSensor = focusRoi,
                    activeSensorArray = focusCoordinateBounds
                )
                val committed = ringBuffer.commitFocusMetrics(
                    pair = lease.pair,
                    expectedVersion = expectedVersion,
                    metrics = metrics
                )
                if (committed) {
                    _focusPeakingGuidance.value = FocusPeakingGuidance(
                        focusConfidence = metrics.focusConfidence,
                        confidenceState = metrics.confidenceState,
                        afState = metrics.afState,
                        lensState = metrics.lensState,
                        afRegion = metrics.afRegion?.let(::Rect),
                        coordinateBounds = focusCoordinateBounds?.let(::Rect),
                        subjectRoiUsed = metrics.subjectRoiUsed,
                        timestampNs = metrics.timestamp
                    )
                }
            } catch (e: Exception) {
                Log.w(
                    tag,
                    "FocusConfidenceEngine evaluation failed for ts=$timestampNs: ${e.message}"
                )
                // Do not spin indefinitely on one frame if an analyzer failure is persistent. A
                // later frame arrival will wake the worker again and retry under fresh ownership.
                return
            } finally {
                lease.release()
            }
        }
    }

    /**
     * RAW selected-buffer routes do not own a CPU YUV ImageReader. Reuse the already-developed
     * compact RAW preview output for exposure analysis instead of adding another camera stream.
     * This keeps RAW stream combinations portable and avoids starving the authoritative warm ring.
     */
    private fun runEnabledRawPreviewAnalysis(frame: RawPreviewFrame) {
        val wantsExposureAnalysis = needsLiveExposureStatistics()
        val wantsQrAnalysis = qrAnalysisEnabled
        val wantsTrackingAnalysis = objectTrackingAnalysisEnabled &&
            _focusTrackingActive.value && !isCapturing
        val wantsPortraitAnalysis = portraitAnalysisEnabled && !isCapturing
        if (!wantsExposureAnalysis && !wantsQrAnalysis && !wantsTrackingAnalysis &&
            !wantsPortraitAnalysis
        ) return

        val frameIndex = rawPreviewAnalysisFrameCounter.incrementAndGet()
        val runExposureAnalysis = wantsExposureAnalysis && frameIndex % 3L == 0L
        val runQr = wantsQrAnalysis && frameIndex % 10L == 0L
        val runPortrait = wantsPortraitAnalysis && shouldRunPortraitSegmentation()
        if (!runExposureAnalysis && !runQr && !wantsTrackingAnalysis && !runPortrait) return

        val width = frame.width.coerceAtLeast(1)
        val height = frame.height.coerceAtLeast(1)

        if (runExposureAnalysis && frame.gpuResidentOutputUsed) {
            val sampleCount = frame.displaySampleCount.coerceAtLeast(0)
            val safeSampleCount = sampleCount.coerceAtLeast(1).toFloat()
            val rawSamples = frame.rawSampleCount.coerceAtLeast(0)
            publishLiveExposureStatistics(
                ExposureStatistics(
                    source = "${frame.source.name}_VULKAN",
                    lumaHistogram64 = frame.displayLumaHistogram64.copyOf(),
                    redHistogram64 = frame.displayRHistogram64.copyOf(),
                    greenHistogram64 = frame.displayGHistogram64.copyOf(),
                    blueHistogram64 = frame.displayBHistogram64.copyOf(),
                    sampleCount = sampleCount,
                    shadowFraction = frame.displayShadowSampleCount.coerceAtLeast(0) / safeSampleCount,
                    highlightFraction = frame.displayHighlightSampleCount.coerceAtLeast(0) / safeSampleCount,
                    redClipFraction = frame.displayRClipSampleCount.coerceAtLeast(0) / safeSampleCount,
                    greenClipFraction = frame.displayGClipSampleCount.coerceAtLeast(0) / safeSampleCount,
                    blueClipFraction = frame.displayBClipSampleCount.coerceAtLeast(0) / safeSampleCount,
                    rawNearClipFraction = if (rawSamples > 0) {
                        frame.rawNearClipSampleCount.coerceAtLeast(0).toFloat() / rawSamples
                    } else null,
                    linearLumaHistogram256 = frame.linearLumaHistogram256.copyOf(),
                    highlightPoint = if (frame.displayHighlightX >= 0f && frame.displayHighlightY >= 0f) {
                        NormalizedPoint(frame.displayHighlightX, frame.displayHighlightY).bounded()
                    } else null
                )
            )
        } else if (runExposureAnalysis) {
            val expectedBytes = width.toLong() * height.toLong() * 4L
            if (expectedBytes > 0L && expectedBytes <= frame.rgba.capacity().toLong()) {
                val rgba = frame.rgba.duplicate()
                val luma64 = IntArray(64)
                val red64 = IntArray(64)
                val green64 = IntArray(64)
                val blue64 = IntArray(64)
                var shadowCount = 0
                var highlightCount = 0
                var redClipCount = 0
                var greenClipCount = 0
                var blueClipCount = 0
                var totalCount = 0
                var highlightXSum = 0.0
                var highlightYSum = 0.0
                var highlightWeightSum = 0.0
                val sampleStep = max(1, min(width, height) / 96)

                for (y in 0 until height step sampleStep) {
                    for (x in 0 until width step sampleStep) {
                        val offset = (y * width + x) * 4
                        if (offset < 0 || offset + 2 >= rgba.capacity()) continue
                        val r = rgba.get(offset).toInt() and 0xFF
                        val g = rgba.get(offset + 1).toInt() and 0xFF
                        val b = rgba.get(offset + 2).toInt() and 0xFF
                        val lum = (0.2126f * r + 0.7152f * g + 0.0722f * b).toInt().coerceIn(0, 255)
                        luma64[(lum * 64 / 256).coerceIn(0, 63)]++
                        red64[(r * 64 / 256).coerceIn(0, 63)]++
                        green64[(g * 64 / 256).coerceIn(0, 63)]++
                        blue64[(b * 64 / 256).coerceIn(0, 63)]++
                        if (r >= 251) redClipCount++
                        if (g >= 251) greenClipCount++
                        if (b >= 251) blueClipCount++
                        if (lum < 30) shadowCount++
                        if (lum > 220) {
                            highlightCount++
                            val weight = (lum - 219).toDouble()
                            highlightXSum += x * weight
                            highlightYSum += y * weight
                            highlightWeightSum += weight
                        }
                        totalCount++
                    }
                }
                val point = if (highlightWeightSum > 0.0) {
                    NormalizedPoint(
                        (highlightXSum / highlightWeightSum / max(1, width - 1)).toFloat(),
                        (highlightYSum / highlightWeightSum / max(1, height - 1)).toFloat()
                    ).bounded()
                } else null
                val safeCount = totalCount.coerceAtLeast(1).toFloat()
                publishLiveExposureStatistics(
                    ExposureStatistics(
                        source = "${frame.source.name}_CPU_PREVIEW_FALLBACK",
                        lumaHistogram64 = luma64,
                        redHistogram64 = red64,
                        greenHistogram64 = green64,
                        blueHistogram64 = blue64,
                        sampleCount = totalCount,
                        shadowFraction = shadowCount / safeCount,
                        highlightFraction = highlightCount / safeCount,
                        redClipFraction = redClipCount / safeCount,
                        greenClipFraction = greenClipCount / safeCount,
                        blueClipFraction = blueClipCount / safeCount,
                        rawNearClipFraction = null,
                        highlightPoint = point
                    )
                )
            }
        }

        if (runQr || wantsTrackingAnalysis || runPortrait) {
            rawPreviewToNv21(frame)?.let { converted ->
                if (runQr) {
                    scanQrNv21(converted.bytes, converted.width, converted.height, frame.rotationDegrees)
                }
                if (wantsTrackingAnalysis) {
                    trackObjectsNv21(converted.bytes, converted.width, converted.height, frame.rotationDegrees)
                }
                if (runPortrait && portraitSegmentationBusy.compareAndSet(false, true)) {
                    val seed = currentPortraitSubjectSeed()
                    val timestampNs = frame.sensorTimestampNs
                    bufferAnalysisScope.launch {
                        try {
                            val artifact = portraitSubjectSegmenter.segmentNv21(
                                bytes = converted.bytes,
                                width = converted.width,
                                height = converted.height,
                                rotationDegrees = frame.rotationDegrees,
                                timestampNs = timestampNs,
                                seed = seed
                            )
                            publishPortraitMask(artifact, frame.source.name)
                        } catch (failure: Throwable) {
                            Log.w(tag, "Portrait RAW subject segmentation skipped: ${failure.message}")
                        } finally {
                            portraitSegmentationBusy.set(false)
                        }
                    }
                }
            }
        }
    }

    private fun currentPortraitSubjectSeed(): com.bncam.core.capture.PortraitSubjectSeed {
        val trackingBounds = _trackedObjectBounds.value?.let { android.graphics.RectF(it) }
            ?.takeIf { _focusOwnership.value.trackingActive }
        return com.bncam.core.capture.PortraitSubjectSeed(
            tapX = portraitTapSeedX,
            tapY = portraitTapSeedY,
            trackedBounds = trackingBounds
        )
    }

    private fun shouldRunPortraitSegmentation(): Boolean {
        if (!portraitAnalysisEnabled || isCapturing || portraitSegmentationBusy.get()) return false
        val now = android.os.SystemClock.elapsedRealtimeNanos()
        val last = lastPortraitSegmentationNs.get()
        if (last > 0L && now - last < 350_000_000L) return false
        return lastPortraitSegmentationNs.compareAndSet(last, now)
    }

    private fun publishPortraitMask(artifact: com.bncam.core.capture.PortraitMaskArtifact?, source: String) {
        if (!portraitAnalysisEnabled || artifact == null) return
        latestPortraitMask.set(artifact)
        Log.d(
            tag,
            "PORTRAIT_MASK source=$source size=${artifact.maskWidth}x${artifact.maskHeight} " +
                "selection=${artifact.selectionReason} bounds=${artifact.subjectBoundsNormalized}"
        )
    }

    private fun isActiveRawWarmProducer(): Boolean {
        val format = synchronized(pipelineLock) { activePipelineIdentity?.bufferFormat }
        return format == ImageFormat.RAW10 || format == ImageFormat.RAW_SENSOR
    }

    private fun applyDefaultRawFrameSelectionExposureConstraintNow(
        exposureTargetNs: Long?,
        source: String,
        isoTarget: Int? = null
    ) {
        val identity = synchronized(pipelineLock) { activePipelineIdentity }
        val format = identity?.bufferFormat
        if (exposureTargetNs == null || exposureTargetNs <= 0L || format == null) {
            ringBuffer.clearSelectionExposureConstraint(source)
            return
        }
        if (format != ImageFormat.RAW10 && format != ImageFormat.RAW_SENSOR) {
            ringBuffer.clearSelectionExposureConstraint(source)
            return
        }
        ringBuffer.setSelectionExposureConstraint(
            generationId = pipelineGeneration,
            expectedFormat = format,
            exposureTargetNs = exposureTargetNs,
            isoTarget = isoTarget,
            source = source
        )
    }

    private fun updateDefaultRawFrameSelectionExposureConstraint(
        exposureTargetNs: Long?,
        source: String,
        isoTarget: Int? = null
    ) {
        var deferredAttemptId: Long? = null
        synchronized(captureSelectionConstraintLock) {
            val frozenAttemptId = captureSelectionConstraintFreezeAttemptId
            if (frozenAttemptId != null) {
                // Keep only the newest requested state. It will be applied atomically after the
                // synchronous shutter-admission scope releases its pinned Near-ZSL anchor.
                deferredSelectionExposureConstraintUpdate =
                    DeferredSelectionExposureConstraintUpdate(exposureTargetNs, source, isoTarget)
                deferredAttemptId = frozenAttemptId
            } else {
                // Apply while holding the same mutex used by freeze/release. Without this, an
                // updater could observe "not frozen", get descheduled, and mutate the ring after a
                // shutter has already frozen its selection contract.
                applyDefaultRawFrameSelectionExposureConstraintNow(exposureTargetNs, source, isoTarget)
            }
        }
        deferredAttemptId?.let { attemptId ->
            traceCaptureRuntime(
                "SELECTION_EXPOSURE_DEFER attemptId=$attemptId source=$source " +
                    "targetNs=${exposureTargetNs ?: 0L} iso=${isoTarget ?: 0}"
            )
        }
    }

    private fun freezeSelectionExposureConstraintForCapture(attemptId: Long) {
        val snapshot = synchronized(captureSelectionConstraintLock) {
            // Snapshot and freeze are one transaction with respect to every manager-owned selection
            // update. The ring itself is independently synchronized.
            ringBuffer.selectionExposureConstraintSnapshot().also { current ->
                captureSelectionConstraintFreezeAttemptId = attemptId
                deferredSelectionExposureConstraintUpdate = null
                frozenSelectionExposureConstraintSnapshot = current
            }
        }
        traceCaptureRuntime(
            "SELECTION_EXPOSURE_FREEZE attemptId=$attemptId snapshot=${snapshot.summary()}"
        )
    }

    private fun relaxSelectionExposureConstraintForGuaranteedAnchor(
        attemptId: Long,
        reason: String
    ) {
        var preserved = "none"
        val relaxed = synchronized(captureSelectionConstraintLock) {
            if (captureSelectionConstraintFreezeAttemptId != attemptId) {
                false
            } else {
                preserved = frozenSelectionExposureConstraintSnapshot?.summary() ?: "none"
                ringBuffer.clearSelectionExposureConstraint("GUARANTEED_ANCHOR:$reason")
                true
            }
        }
        if (relaxed) {
            traceCaptureRuntime(
                "SELECTION_EXPOSURE_RELAX attemptId=$attemptId reason=$reason preservedSnapshot=$preserved"
            )
        }
    }

    private fun releaseSelectionExposureConstraintAfterCapture(attemptId: Long) {
        var releaseTrace: String? = null
        synchronized(captureSelectionConstraintLock) {
            if (captureSelectionConstraintFreezeAttemptId != attemptId) return
            val deferred = deferredSelectionExposureConstraintUpdate
            val frozen = frozenSelectionExposureConstraintSnapshot

            // Keep the mutex until the final ring state is restored. A new live preview update may
            // otherwise overtake this release and then be overwritten by an older deferred state.
            captureSelectionConstraintFreezeAttemptId = null
            deferredSelectionExposureConstraintUpdate = null
            frozenSelectionExposureConstraintSnapshot = null

            when {
                deferred != null -> {
                    applyDefaultRawFrameSelectionExposureConstraintNow(
                        exposureTargetNs = deferred.exposureTargetNs,
                        source = "${deferred.source}:DEFERRED_AFTER_CAPTURE",
                        isoTarget = deferred.isoTarget
                    )
                }
                frozen?.active == true &&
                    frozen.pipelineGeneration == pipelineGeneration &&
                    frozen.requestedExposureTargetNs != null &&
                    frozen.requestedExposureTargetNs > 0L -> {
                    ringBuffer.setSelectionExposureConstraint(
                        generationId = frozen.pipelineGeneration,
                        expectedFormat = frozen.expectedFormat,
                        exposureTargetNs = frozen.requestedExposureTargetNs,
                        isoTarget = frozen.requestedIsoTarget,
                        source = "${frozen.source}:RESTORED_AFTER_CAPTURE"
                    )
                }
                frozen?.active == false -> {
                    ringBuffer.clearSelectionExposureConstraint("RESTORE_INACTIVE_AFTER_CAPTURE")
                }
            }
            releaseTrace =
                "SELECTION_EXPOSURE_RELEASE attemptId=$attemptId deferred=${deferred != null} " +
                    "restoreFrozen=${deferred == null && frozen?.active == true}"
        }
        releaseTrace?.let(::traceCaptureRuntime)
    }

    private fun nearZslPhysicalAgeAtShutterMs(
        candidate: FrameRingBuffer.LeasedCandidate,
        userShutterTimestampNs: Long
    ): Double? {
        val frame = candidate.frame
        if (userShutterTimestampNs <= 0L) return null
        return if (frame.sensorTimestampComparableToElapsedRealtime) {
            (userShutterTimestampNs - NearZslEligibilityPolicy.calculateFullExposureEndNs(frame)) / 1_000_000.0
        } else {
            val completedElapsedNs = frame.pairCompleteElapsedNs.takeIf { it > 0L }
                ?: maxOf(frame.imageArrivalElapsedNs, frame.metadataArrivalElapsedNs)
            if (completedElapsedNs > 0L) {
                (userShutterTimestampNs - completedElapsedNs) / 1_000_000.0
            } else null
        }
    }

    private fun zslCaptureCandidateEvidence(
        candidate: FrameRingBuffer.LeasedCandidate,
        physicalAgeMs: Double
    ): ZslCaptureCandidateEvidence {
        val frame = candidate.frame
        val afState = frame.afState
        val afTransitioning =
            afState == CaptureResult.CONTROL_AF_STATE_PASSIVE_SCAN ||
                afState == CaptureResult.CONTROL_AF_STATE_ACTIVE_SCAN
        val afExplicitlyUnfocused =
            afState == CaptureResult.CONTROL_AF_STATE_PASSIVE_UNFOCUSED ||
                afState == CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED
        val lensMoving = frame.lensState == CaptureResult.LENS_STATE_MOVING
        val aeState = try {
            frame.metadata?.get(CaptureResult.CONTROL_AE_STATE)
        } catch (_: Throwable) {
            null
        }
        val awbState = try {
            frame.metadata?.get(CaptureResult.CONTROL_AWB_STATE)
        } catch (_: Throwable) {
            null
        }
        return ZslCaptureCandidateEvidence(
            frameVersion = frame.frameVersion,
            physicalAgeMs = physicalAgeMs,
            afTransitioning = afTransitioning,
            afExplicitlyUnfocused = afExplicitlyUnfocused,
            lensMoving = lensMoving,
            aeTransitioning =
                aeState == CaptureResult.CONTROL_AE_STATE_SEARCHING ||
                    aeState == CaptureResult.CONTROL_AE_STATE_PRECAPTURE,
            awbTransitioning =
                awbState == CaptureResult.CONTROL_AWB_STATE_SEARCHING
        )
    }

    private fun leasePreShutterAnchor(
        userShutterTimestampNs: Long,
        expectedGeneration: Int,
        expectedFormat: Int,
        maximumAgeMs: Double,
        genuineOnly: Boolean
    ): FrameRingBuffer.LeasedCandidate? {
        if (pipelineGeneration != expectedGeneration || ringBuffer.currentGeneration() != expectedGeneration) {
            return null
        }
        // This is the shutter-time atomic ownership operation. Lease every currently eligible pair
        // for the few microseconds needed to choose one capture candidate, then immediately release
        // all non-winners. The capture-candidate role is intentionally independent from viewfinder
        // publication and live metering freshness: only the exact warm frame's own state is allowed
        // to demote a transitional candidate. Candidate quality never blocks the shutter.
        val leased = ringBuffer.queryAndLeaseCandidates(
            userShutterTimestampNs = userShutterTimestampNs,
            maxCount = ringBuffer.currentCapacity().coerceAtLeast(1),
            shutterTimestampDomain = "ELAPSED_REALTIME",
            expectedFormat = expectedFormat
        )
        if (leased.isEmpty()) return null

        val eligible = leased
            .asSequence()
            .filter { candidate ->
                candidate.frame.generationId == expectedGeneration &&
                    candidate.frame.format == expectedFormat
            }
            .mapNotNull { candidate ->
                val ageMs = nearZslPhysicalAgeAtShutterMs(candidate, userShutterTimestampNs)
                    ?: return@mapNotNull null
                val ageAccepted = if (genuineOnly) {
                    NearZslAnchorAdmissionPolicy.isGenuinePreShutterAge(ageMs)
                } else {
                    NearZslAnchorAdmissionPolicy.isUsableDegradedPreShutterAge(ageMs, maximumAgeMs)
                }
                if (!ageAccepted) {
                    return@mapNotNull null
                }
                candidate to zslCaptureCandidateEvidence(candidate, ageMs)
            }
            .toList()
        val selectedEvidence =
            ZslCaptureCandidateRolePolicy.selectBest(eligible.map { (_, evidence) -> evidence })
        val chosen = selectedEvidence?.let { selected ->
            eligible.firstOrNull { (_, evidence) ->
                evidence.frameVersion == selected.frameVersion
            }?.first
        }

        if (selectedEvidence != null) {
            traceCaptureRuntime(
                "CAPTURE_CANDIDATE_ROLE_SELECTED " +
                    "frameVersion=${selectedEvidence.frameVersion} " +
                    "physicalAgeMs=${selectedEvidence.physicalAgeMs} " +
                    "state=${selectedEvidence.state.name} " +
                    "reason=${selectedEvidence.reason()} " +
                    "genuineOnly=$genuineOnly"
            )
        }

        leased.forEach { candidate ->
            if (candidate !== chosen) candidate.lease.release()
        }
        return chosen
    }

    private fun leaseFreshPreShutterAnchor(
        userShutterTimestampNs: Long,
        expectedGeneration: Int,
        expectedFormat: Int
    ): FrameRingBuffer.LeasedCandidate? =
        leasePreShutterAnchor(
            userShutterTimestampNs = userShutterTimestampNs,
            expectedGeneration = expectedGeneration,
            expectedFormat = expectedFormat,
            maximumAgeMs = Double.MAX_VALUE,
            genuineOnly = true
        )

    private fun leaseFreshRepeatingAnchorAfterShutter(
        userShutterTimestampNs: Long,
        expectedGeneration: Int,
        expectedFormat: Int,
        maximumTransportAgeMs: Double,
        maximumDegradedPreShutterAgeMs: Double
    ): FrameRingBuffer.LeasedCandidate? {
        if (pipelineGeneration != expectedGeneration || ringBuffer.currentGeneration() != expectedGeneration) {
            return null
        }
        val nowNs = android.os.SystemClock.elapsedRealtimeNanos()
        val leased = ringBuffer.queryAndLeaseCandidates(
            userShutterTimestampNs = 0L,
            maxCount = ringBuffer.currentCapacity().coerceAtLeast(1),
            shutterTimestampDomain = "ELAPSED_REALTIME",
            expectedFormat = expectedFormat
        )
        if (leased.isEmpty()) return null

        val chosen = leased
            .asSequence()
            .mapNotNull { candidate ->
                val frame = candidate.frame
                if (frame.generationId != expectedGeneration || frame.format != expectedFormat) {
                    return@mapNotNull null
                }
                // The cold/reliability fallback is still sourced exclusively from the existing
                // Camera2 repeating producer. A one-shot/burst result is never allowed to masquerade
                // as the warm-buffer continuation frame.
                if (frame.requestProvenance?.snapshot?.submissionType != CameraRequestSubmissionType.REPEATING) {
                    return@mapNotNull null
                }
                val completionNs = frame.pairCompleteElapsedNs.takeIf { it > 0L }
                    ?: maxOf(frame.imageArrivalElapsedNs, frame.metadataArrivalElapsedNs)
                if (frame.sensorTimestampComparableToElapsedRealtime) {
                    val physicalAgeMs =
                        nearZslPhysicalAgeAtShutterMs(candidate, userShutterTimestampNs)
                    if (!NearZslAnchorAdmissionPolicy.isAdmissibleFirstValidPhysicalAge(
                            physicalAgeAtUserShutterMs = physicalAgeMs,
                            maximumDegradedPreShutterAgeMs = maximumDegradedPreShutterAgeMs
                        )
                    ) {
                        return@mapNotNull null
                    }
                }
                if (completionNs <= 0L || !NearZslAnchorAdmissionPolicy.isFreshFirstValidCompletion(
                        pairCompleteElapsedNs = completionNs,
                        userShutterTimestampNs = userShutterTimestampNs,
                        nowElapsedNs = nowNs,
                        maximumTransportAgeMs = maximumTransportAgeMs
                    )
                ) {
                    return@mapNotNull null
                }
                candidate to completionNs
            }
            // First valid means first completed pair after the accepted shutter, not whichever
            // frame happened to be newest when this coroutine resumed. This keeps cold-start
            // latency deterministic and prevents a short scheduling stall from skipping ahead.
            .minByOrNull { (_, completionNs) -> completionNs }
            ?.first

        leased.forEach { candidate ->
            if (candidate !== chosen) candidate.lease.release()
        }
        return chosen
    }

    private fun effectiveShutterForRepeatingAnchor(
        candidate: FrameRingBuffer.LeasedCandidate,
        userShutterTimestampNs: Long
    ): Pair<Long, String> {
        val frame = candidate.frame
        val completionNs = frame.pairCompleteElapsedNs.takeIf { it > 0L }
            ?: maxOf(frame.imageArrivalElapsedNs, frame.metadataArrivalElapsedNs)
        val resolved = NearZslAnchorAdmissionPolicy.effectiveShutterForFirstValidRepeatingFrame(
            userShutterTimestampNs = userShutterTimestampNs,
            fullExposureEndNs = if (frame.sensorTimestampComparableToElapsedRealtime) {
                NearZslEligibilityPolicy.calculateFullExposureEndNs(frame)
            } else null,
            pairCompleteElapsedNs = completionNs,
            sensorTimestampComparableToElapsedRealtime = frame.sensorTimestampComparableToElapsedRealtime
        )
        return resolved.timestampNs to resolved.domain
    }

    private suspend fun awaitSingleNearZslAnchor(
        attemptId: Long,
        userShutterTimestampNs: Long,
        expectedGeneration: Int,
        expectedFormat: Int,
        coldStartAtUserShutter: Boolean
    ): NearZslSingleAnchorReservation? {
        val startNs = android.os.SystemClock.elapsedRealtimeNanos()
        val timing = ringBuffer.streamTimingEstimate()
        val budget = NearZslAnchorAdmissionPolicy.resolveBudget(
            frameDurationMedianMs = timing.frameDurationMedianMs,
            pairCompletionLagMedianMs = timing.pairCompletionLagMedianMs,
            coldStartAtUserShutter = coldStartAtUserShutter
        )
        val preShutterPairingGraceMs = budget.preShutterPairingGraceMs
        val firstValidWaitMs = budget.firstValidRepeatingFrameWaitMs
        val maximumTransportAgeMs = budget.maximumTransportAgeMs

        // The strict exposure-product selector was already given one atomic chance at the exact
        // shutter entry. If it yielded no anchor, exposure matching becomes a quality preference:
        // relax it *before* waiting for late image/metadata pairing so a genuine pre-shutter pair
        // cannot be overwritten while admission waits for a selector target that no longer exists.
        relaxSelectionExposureConstraintForGuaranteedAnchor(
            attemptId = attemptId,
            reason = if (coldStartAtUserShutter) "COLD_START_FIRST_VALID_FRAME" else "NO_IMMEDIATE_GENUINE_PRE_SHUTTER_FRAME"
        )

        fun elapsedMs(): Double =
            (android.os.SystemClock.elapsedRealtimeNanos() - startNs).coerceAtLeast(0L) / 1_000_000.0

        var eventSequence = ringBuffer.currentEventSequence()
        val preShutterDeadlineNs = startNs + preShutterPairingGraceMs * 1_000_000L
        while (preShutterPairingGraceMs > 0L &&
            android.os.SystemClock.elapsedRealtimeNanos() <= preShutterDeadlineNs
        ) {
            leaseFreshPreShutterAnchor(
                userShutterTimestampNs,
                expectedGeneration,
                expectedFormat
            )?.let { candidate ->
                return NearZslSingleAnchorReservation(
                    candidate = candidate,
                    temporalClass = "GENUINE_PRE_SHUTTER_LATE_PAIR",
                    effectiveShutterTimestampNs = userShutterTimestampNs,
                    effectiveShutterTimestampDomain = "ELAPSED_REALTIME",
                    waitMs = elapsedMs(),
                    physicalAgeAtUserShutterMs =
                        nearZslPhysicalAgeAtShutterMs(candidate, userShutterTimestampNs)
                )
            }
            if (pipelineGeneration != expectedGeneration || ringBuffer.currentGeneration() != expectedGeneration) {
                return null
            }
            kotlinx.coroutines.withTimeoutOrNull(25L) { ringBuffer.awaitEventAfter(eventSequence) }
            eventSequence = ringBuffer.currentEventSequence()
        }

        // Before accepting anything temporally after the press, make one reliability-floor pass
        // over still-valid pre-shutter pairs with the exposure-selection preference relaxed. This
        // preserves Near-ZSL semantics for ordinary warm operation even when the latest 120 ms pair
        // missed a transient shutter/ISO selection constraint. Very stale frames remain forbidden.
        if (!coldStartAtUserShutter) {
            leasePreShutterAnchor(
                userShutterTimestampNs = userShutterTimestampNs,
                expectedGeneration = expectedGeneration,
                expectedFormat = expectedFormat,
                maximumAgeMs = budget.maximumDegradedPreShutterAgeMs,
                genuineOnly = false
            )?.let { candidate ->
                return NearZslSingleAnchorReservation(
                    candidate = candidate,
                    temporalClass = "DEGRADED_PRE_SHUTTER_ANCHOR",
                    effectiveShutterTimestampNs = userShutterTimestampNs,
                    effectiveShutterTimestampDomain = "ELAPSED_REALTIME",
                    waitMs = elapsedMs(),
                    physicalAgeAtUserShutterMs =
                        nearZslPhysicalAgeAtShutterMs(candidate, userShutterTimestampNs)
                )
            }
        }

        // Keep the same continuously repeating warm producer and accept its first complete, fresh
        // frame instead of issuing a dedicated still request or failing the shutter. This is an
        // explicitly degraded temporal class, not fake Near-ZSL.
        val fallbackDeadlineNs =
            android.os.SystemClock.elapsedRealtimeNanos() + firstValidWaitMs * 1_000_000L
        while (android.os.SystemClock.elapsedRealtimeNanos() <= fallbackDeadlineNs) {
            leaseFreshRepeatingAnchorAfterShutter(
                userShutterTimestampNs = userShutterTimestampNs,
                expectedGeneration = expectedGeneration,
                expectedFormat = expectedFormat,
                maximumTransportAgeMs = maximumTransportAgeMs,
                maximumDegradedPreShutterAgeMs = budget.maximumDegradedPreShutterAgeMs
            )?.let { candidate ->
                val (effectiveTimestamp, domain) =
                    effectiveShutterForRepeatingAnchor(candidate, userShutterTimestampNs)
                return NearZslSingleAnchorReservation(
                    candidate = candidate,
                    temporalClass = "FIRST_VALID_WARM_REPEATING_FRAME",
                    effectiveShutterTimestampNs = effectiveTimestamp,
                    effectiveShutterTimestampDomain = domain,
                    waitMs = elapsedMs(),
                    physicalAgeAtUserShutterMs =
                        nearZslPhysicalAgeAtShutterMs(candidate, userShutterTimestampNs)
                )
            }
            if (pipelineGeneration != expectedGeneration || ringBuffer.currentGeneration() != expectedGeneration) {
                return null
            }
            kotlinx.coroutines.withTimeoutOrNull(30L) { ringBuffer.awaitEventAfter(eventSequence) }
            eventSequence = ringBuffer.currentEventSequence()
        }
        return null
    }

    private fun shouldRequestDefaultRawShutterMotionAnalysis(): Boolean =
        isActiveRawWarmProducer() &&
            requestedManualIso == null && requestedManualExposureNs == null &&
            !activeProfileExposurePreferences.requiresAeBaseline() &&
            currentFlashMode == "Off"

    private fun clearDefaultRawShutterPriorityState(resetMotion: Boolean) {
        updateDefaultRawFrameSelectionExposureConstraint(
            exposureTargetNs = null,
            source = "DEFAULT_RAW_STATE_CLEAR",
            isoTarget = null
        )
        defaultRawShutterAeBaselineIso = null
        defaultRawShutterAeBaselineExposureNs = null
        defaultRawShutterAeBaselineGeneration = -1
        defaultRawShutterAwaitingAeBaseline = false
        defaultRawShutterBootstrapMinControlEpoch = -1L
        defaultRawShutterLastControlUpdateNs = 0L
        defaultRawShutterLastMotionCeilingNs = 0L
        defaultRawShutterLastSafeExposureCeilingNs = 0L
        defaultRawShutterManualFallbackActive = false
        defaultRawShutterFallbackTargetLuma = null
        defaultRawPhotometricTargetLuma = null
        defaultRawMeteringTracker.reset(pipelineGeneration)
        defaultRawAeReferenceGate.reset(pipelineGeneration)
        latestDefaultRawMetering = null
        latestDefaultRawExposureTarget = null
        latestDefaultRawExposureAllocation = null
        lastDefaultRawExposureTargetLogKey = ""
        defaultRawAllocationReady = false
        defaultRawFramesSinceExposureRequest = 0
        defaultRawLastObservedControlEpoch = -1L
        defaultRawLastAeStable = false
        lastDefaultRawPhotometricConvergenceLogKey = ""
        latestDefaultRawExposureTruth = null
        lastDefaultRawExposureTruthLogKey = ""
        latestDefaultRawPhotometricConvergence = defaultRawPhotometricConvergenceTracker.reset(
            currentGeneration = pipelineGeneration,
            nowElapsedNs = android.os.SystemClock.elapsedRealtimeNanos()
        )
        defaultRawShutterFallbackPlan = null
        defaultRawShutterFallbackLastAdaptationNs = 0L
        if (resetMotion) {
            defaultRawShutterMotionMeter.reset()
            latestDefaultRawMotion = null
            latestDefaultRawMotionGeneration = -1
        }
    }

    /**
     * A metering-domain change invalidates the scene brightness reference used by default RAW
     * shutter-priority. Reusing the pre-change ISO x exposure product can pin exposure time to a
     * target from another AE region while Camera2 is already at an ISO bound, which presents as a
     * full-frame exposure pulse/hunt. Keep motion evidence, but reacquire brightness truth from a
     * fresh repeating Camera2-AE epoch before shutter-priority resumes.
     */
    private fun restartDefaultRawAeReferenceForMeteringChange(reason: String): Boolean =
        restartDefaultRawAeReference(reason, preserveRealizationTruth = false)

    private fun restartDefaultRawAeReference(
        reason: String,
        preserveRealizationTruth: Boolean
    ): Boolean {
        if (!shouldRequestDefaultRawShutterMotionAnalysis()) return false
        val retainedTruth = latestDefaultRawExposureTruth.takeIf { preserveRealizationTruth }
        clearDefaultRawShutterPriorityState(resetMotion = false)
        if (retainedTruth != null) latestDefaultRawExposureTruth = retainedTruth
        defaultRawShutterAwaitingAeBaseline = true
        defaultRawShutterBootstrapMinControlEpoch =
            controlRequestEpochTracker.currentSubmittedEpoch() + 1L
        Log.i(
            tag,
            "DEFAULT_RAW_AE_REFERENCE_RESTART reason=$reason generation=$pipelineGeneration " +
                "minEpoch=$defaultRawShutterBootstrapMinControlEpoch"
        )
        return true
    }

    private fun isDefaultRawAeStateStable(aeState: Int?): Boolean =
        aeState == CaptureResult.CONTROL_AE_STATE_CONVERGED ||
            aeState == CaptureResult.CONTROL_AE_STATE_FLASH_REQUIRED ||
            aeState == CaptureResult.CONTROL_AE_STATE_LOCKED

    private fun updateDefaultRawExposureRealizationTruth(
        request: CaptureRequest,
        result: TotalCaptureResult,
        generation: Int
    ) {
        val resolvedRequest = controlRequestEpochTracker.resolveTag(request.tag, generation)
        val snapshot = resolvedRequest.provenance?.snapshot ?: return
        val summary = snapshot.exposurePolicySummary
        if (!summary.contains("defaultRawPriority=true")) return

        val route = when {
            summary.contains("priorityModeSupported=true") &&
                summary.contains("priorityModeRequested=EXPOSURE_TIME") ->
                DefaultRawExposureRoute.API36_EXPOSURE_TIME_PRIORITY
            summary.contains("fallback=MANUAL_LINEAR_LUMA_FEEDBACK") ->
                DefaultRawExposureRoute.MANUAL_FALLBACK
            else -> DefaultRawExposureRoute.AE_BOOTSTRAP
        }
        val requestedPriorityMode = if (Build.VERSION.SDK_INT >= 36) {
            runCatching { request.get(CaptureRequest.CONTROL_AE_PRIORITY_MODE) }.getOrNull()
        } else {
            null
        }
        val resultPriorityMode = if (Build.VERSION.SDK_INT >= 36) {
            runCatching { result.get(CaptureResult.CONTROL_AE_PRIORITY_MODE) }.getOrNull()
        } else {
            null
        }
        val truth = DefaultRawExposureRealizationEvaluator.evaluate(
            route = route,
            pipelineGeneration = generation,
            controlRequestEpoch = snapshot.identity.controlRequestEpoch,
            requestedAeMode = request.get(CaptureRequest.CONTROL_AE_MODE),
            resultAeMode = result.get(CaptureResult.CONTROL_AE_MODE),
            requestedPriorityMode = requestedPriorityMode,
            resultPriorityMode = resultPriorityMode,
            requestedExposureNs = request.get(CaptureRequest.SENSOR_EXPOSURE_TIME)?.takeIf { it > 0L },
            actualExposureNs = result.get(CaptureResult.SENSOR_EXPOSURE_TIME)?.takeIf { it > 0L },
            requestedIso = request.get(CaptureRequest.SENSOR_SENSITIVITY)?.takeIf { it > 0 },
            actualIso = result.get(CaptureResult.SENSOR_SENSITIVITY)?.takeIf { it > 0 },
            actualFrameDurationNs = result.get(CaptureResult.SENSOR_FRAME_DURATION)?.takeIf { it > 0L }
        )
        latestDefaultRawExposureTruth = truth
        val repeatingResult = snapshot.submissionType == CameraRequestSubmissionType.REPEATING
        if (repeatingResult) {
            if (defaultRawLastObservedControlEpoch != truth.controlRequestEpoch) {
                defaultRawLastObservedControlEpoch = truth.controlRequestEpoch
                defaultRawFramesSinceExposureRequest = 0
            } else {
                defaultRawFramesSinceExposureRequest++
            }
        }
        defaultRawLastAeStable = isDefaultRawAeStateStable(result.get(CaptureResult.CONTROL_AE_STATE))
        defaultRawAeReferenceGate.observeAeState(
            currentGeneration = generation,
            stable = defaultRawLastAeStable,
            nowElapsedNs = android.os.SystemClock.elapsedRealtimeNanos()
        )

        val logKey = "${truth.route}:${truth.status}:${truth.controlRequestEpoch}"
        if (logKey != lastDefaultRawExposureTruthLogKey) {
            lastDefaultRawExposureTruthLogKey = logKey
            if (truth.status.contains("NOT_REALIZED") ||
                truth.status.contains("MISMATCH") || truth.status.contains("INVALID")
            ) {
                Log.w(tag, "DEFAULT_RAW_EXPOSURE_REALIZATION ${truth.summary()}")
            } else {
                Log.i(tag, "DEFAULT_RAW_EXPOSURE_REALIZATION ${truth.summary()}")
            }
        }

        if (route == DefaultRawExposureRoute.API36_EXPOSURE_TIME_PRIORITY && generation == pipelineGeneration) {
            val identity = synchronized(pipelineLock) { activePipelineIdentity }
            val sensitivityCameraId = identity?.physicalCameraId ?: identity?.logicalCameraId ?: cameraDevice?.id
            val sensitivityRange = sensitivityCameraId?.let { id ->
                runCatching {
                    cameraManager.getCameraCharacteristics(id)
                        .get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
                }.getOrNull()
            }
            val decision = defaultRawApi36AuthorityTracker.observe(
                currentGeneration = generation,
                repeatingResult = repeatingResult,
                realizationStatus = truth.status,
                aeSearching = result.get(CaptureResult.CONTROL_AE_STATE) == CaptureResult.CONTROL_AE_STATE_SEARCHING,
                actualIso = truth.actualIso,
                minIso = sensitivityRange?.lower,
                maxIso = sensitivityRange?.upper
            )
            if (decision.action != DefaultRawApi36AuthorityAction.KEEP_PRIORITY) {
                Log.w(tag, "DEFAULT_RAW_API36_AUTHORITY ${decision.summary()}")
                if (!defaultRawShutterAwaitingAeBaseline) {
                    val reason = when (decision.action) {
                        DefaultRawApi36AuthorityAction.REBOOTSTRAP_AE_REFERENCE -> when {
                            decision.reason.contains("max_iso") -> "API36_AE_SEARCHING_AT_MAX_ISO"
                            decision.reason.contains("min_iso") -> "API36_AE_SEARCHING_AT_MIN_ISO"
                            else -> "API36_AE_REFERENCE_REBOOTSTRAP"
                        }
                        DefaultRawApi36AuthorityAction.REJECT_API36_PRIORITY -> when {
                            decision.reason.contains("max_iso") -> "API36_MAX_ISO_UNSOLVED_REJECTED"
                            decision.reason.contains("min_iso") -> "API36_MIN_ISO_UNSOLVED_REJECTED"
                            else -> "API36_PRIORITY_NOT_REALIZED"
                        }
                        DefaultRawApi36AuthorityAction.KEEP_PRIORITY -> "API36_KEEP"
                    }
                    if (restartDefaultRawAeReference(reason, preserveRealizationTruth = true)) {
                        backgroundHandler?.post { updatePreviewRepeatingRequest() }
                    }
                }
            }
        }
    }

    private fun resetRawFlickerAuthority(reason: String, generation: Int = pipelineGeneration) {
        rawFlickerTrackerGeneration = generation
        latestRawFlickerSnapshot = rawFlickerStabilityTracker.reset()
        Log.i(tag, "RAW_FLICKER_AUTHORITY_RESET generation=$generation reason=$reason")
    }

    private fun updateRawFlickerAuthority(result: TotalCaptureResult, generation: Int) {
        val rawOwnsDynamicFlicker = PreviewFlickerAuthorityPolicy.shouldObserveSceneFlicker(
            isRawWarmProducer = isActiveRawWarmProducer()
        )
        if (!rawOwnsDynamicFlicker || generation != pipelineGeneration ||
            activeResolvedAntibandingMode != CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_AUTO
        ) return

        if (rawFlickerTrackerGeneration != generation) {
            resetRawFlickerAuthority("PIPELINE_GENERATION_CHANGED", generation)
        }

        val physicalId = synchronized(pipelineLock) {
            activePipelineIdentity
                ?.takeIf { it.cameraRouteKind == CameraRouteKind.LOGICAL_PHYSICAL }
                ?.physicalCameraId
        }
        val physicalResult = physicalCaptureResultOrNull(result, physicalId)
        val reported: Int?
        val resultOwner: String
        if (physicalId != null) {
            reported = physicalResult?.get(CaptureResult.STATISTICS_SCENE_FLICKER)
            resultOwner = if (physicalResult != null) {
                "PHYSICAL:$physicalId"
            } else {
                "PHYSICAL_METADATA_UNAVAILABLE:$physicalId"
            }
        } else {
            reported = result.get(CaptureResult.STATISTICS_SCENE_FLICKER)
            resultOwner = "STANDALONE"
        }
        val observation = when (reported) {
            CameraMetadata.STATISTICS_SCENE_FLICKER_50HZ -> RawFlickerObservation.HZ_50
            CameraMetadata.STATISTICS_SCENE_FLICKER_60HZ -> RawFlickerObservation.HZ_60
            CameraMetadata.STATISTICS_SCENE_FLICKER_NONE -> RawFlickerObservation.NONE_DETECTED
            null -> RawFlickerObservation.UNAVAILABLE
            else -> RawFlickerObservation.NONE_DETECTED
        }
        val previous = latestRawFlickerSnapshot
        val next = rawFlickerStabilityTracker.observe(
            observation = observation,
            nowNs = android.os.SystemClock.elapsedRealtimeNanos()
        )
        latestRawFlickerSnapshot = next

        val authorityChanged = previous.stableFrequency != next.stableFrequency ||
            previous.fallbackActive != next.fallbackActive
        if (!authorityChanged) return

        Log.i(
            tag,
            "RAW_FLICKER_AUTHORITY_CHANGED generation=$generation observation=$observation resultOwner=$resultOwner " +
                "frequency=${next.stableFrequency} fallback=${next.fallbackActive} source=${next.source}"
        )
        // A stream switch may race this capture-result callback. Re-check ownership before a
        // control resubmission so a retiring RAW result can never perturb the new YUV AE loop.
        if (!PreviewFlickerAuthorityPolicy.shouldResubmitForFlickerAuthorityChange(
                isRawWarmProducer = isActiveRawWarmProducer()
            )
        ) return
        enqueuePreviewControl("flicker_cadence", "FLICKER_AUTHORITY_CHANGED:${next.stableFrequency}") {
            updatePreviewRepeatingRequest()
        }
    }

    private fun resolveDefaultRawFlickerConstraint(): RawFlickerConstraint {
        return when (activeResolvedAntibandingMode) {
            CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_50HZ -> RawFlickerConstraint(
                frequency = RawFlickerFrequency.HZ_50,
                source = "RESOLVED_ANTIBANDING_50HZ"
            )
            CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_60HZ -> RawFlickerConstraint(
                frequency = RawFlickerFrequency.HZ_60,
                source = "RESOLVED_ANTIBANDING_60HZ"
            )
            CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_AUTO -> {
                val stable = latestRawFlickerSnapshot.takeIf {
                    rawFlickerTrackerGeneration == pipelineGeneration
                }
                RawFlickerConstraint(
                    frequency = stable?.stableFrequency ?: RawFlickerFrequency.NONE,
                    source = stable?.source ?: "AUTO_SCENE_FLICKER_AWAITING_RESULT",
                    fallbackActive = stable?.fallbackActive ?: false
                )
            }
            CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_OFF -> RawFlickerConstraint(
                frequency = RawFlickerFrequency.NONE,
                source = "ANTIBANDING_OFF"
            )
            else -> RawFlickerConstraint(
                frequency = RawFlickerFrequency.NONE,
                source = "ANTIBANDING_MODE_UNAVAILABLE"
            )
        }
    }

    private fun publishDefaultRawMotionMeasurement(
        measurement: com.bncam.core.capture.RawMotionMeasurement,
        generation: Int
    ) {
        latestDefaultRawMotion = measurement
        latestDefaultRawMotionGeneration = generation
        if (!measurement.ready) return

        val strictest = listOfNotNull(
            measurement.cameraExposureCeilingNs,
            measurement.sceneExposureCeilingNs
        ).minOrNull() ?: return
        val previous = defaultRawShutterLastMotionCeilingNs
        val ratioChanged = previous <= 0L ||
            strictest.toDouble() / previous.toDouble() !in 0.80..1.25
        val now = android.os.SystemClock.elapsedRealtimeNanos()
        val cadenceReady = now - defaultRawShutterLastControlUpdateNs >= 250_000_000L
        if (ratioChanged && cadenceReady) {
            defaultRawShutterLastMotionCeilingNs = strictest
            defaultRawShutterLastControlUpdateNs = now
            enqueuePreviewControl("default_raw_shutter_priority", "RAW_MOTION_UPDATE") {
                updatePreviewRepeatingRequest()
            }
        }
    }

    private fun handleDefaultRawWarmFrameMotion(
        image: android.media.Image,
        generation: Int,
        expectedFormat: Int
    ) {
        if (isCapturing || !shouldRequestDefaultRawShutterMotionAnalysis()) return
        if (generation != pipelineGeneration) return
        if (latestDefaultRawMotionGeneration != generation) {
            defaultRawShutterMotionMeter.reset()
            latestDefaultRawMotion = null
            latestDefaultRawMotionGeneration = generation
            defaultRawShutterLastMotionCeilingNs = 0L
        }
        val plane = image.planes.firstOrNull() ?: return
        val sample = when (expectedFormat) {
            ImageFormat.RAW10 -> WarmRawMotionSampler.sampleRaw10(
                buffer = plane.buffer,
                rowStride = plane.rowStride,
                width = image.width,
                height = image.height,
                timestampNs = image.timestamp
            )
            ImageFormat.RAW_SENSOR -> WarmRawMotionSampler.sampleRawSensor(
                buffer = plane.buffer,
                rowStride = plane.rowStride,
                pixelStride = plane.pixelStride,
                width = image.width,
                height = image.height,
                timestampNs = image.timestamp
            )
            else -> null
        } ?: return
        val measurement = defaultRawShutterMotionMeter.observeLuma8(
            luma = sample.bytes,
            width = sample.width,
            height = sample.height,
            fullWidth = sample.fullWidth,
            fullHeight = sample.fullHeight,
            timestampNs = sample.timestampNs
        )
        publishDefaultRawMotionMeasurement(measurement, generation)
    }

    private fun needsProfileExposureStatistics(): Boolean =
        activeProfileExposurePreferences.requiresAeBaseline() &&
            requestedManualIso == null && requestedManualExposureNs == null

    private fun needsDefaultRawShutterFallbackStatistics(): Boolean =
        defaultRawShutterManualFallbackActive && shouldRequestDefaultRawShutterMotionAnalysis()

    private fun needsLiveExposureStatistics(): Boolean =
        histogramAnalysisEnabled || needsProfileExposureStatistics() ||
            needsDefaultRawShutterFallbackStatistics() ||
            shouldRequestDefaultRawShutterMotionAnalysis()

    private fun updateDefaultRawPhotometricConvergence(statistics: ExposureStatistics) {
        if (!shouldRequestDefaultRawShutterMotionAnalysis() || statistics.sampleCount <= 0) return
        val observedLuma = statistics.exposureControllerLuma()
            .takeIf { it.isFinite() && it > 0f }

        if (defaultRawPhotometricTargetLuma == null &&
            defaultRawShutterAeBaselineGeneration == pipelineGeneration &&
            !defaultRawShutterAwaitingAeBaseline && observedLuma != null
        ) {
            // Do not bind the set-point to a statistic produced before the HAL convergence window.
            // The AE result and RAW histogram are asynchronous; only the synchronized reference gate
            // is allowed to create a new photometric anchor.
            val candidate = defaultRawMeteringTracker.resolve(
                currentGeneration = pipelineGeneration,
                nowElapsedRealtimeNs = android.os.SystemClock.elapsedRealtimeNanos(),
                source = "PHOTOMETRIC_TARGET_RESOLVE"
            )
            if (defaultRawAeReferenceGate.canAccept(pipelineGeneration, candidate)) {
                defaultRawPhotometricTargetLuma = candidate.controllerLuma
            }
        }

        val truth = latestDefaultRawExposureTruth
        val route = truth?.route ?: when {
            defaultRawShutterManualFallbackActive -> DefaultRawExposureRoute.MANUAL_FALLBACK
            lastExposurePlanSummary.contains("priorityModeRequested=EXPOSURE_TIME") ->
                DefaultRawExposureRoute.API36_EXPOSURE_TIME_PRIORITY
            else -> DefaultRawExposureRoute.AE_BOOTSTRAP
        }
        val targetLuma = defaultRawPhotometricTargetLuma ?: defaultRawShutterFallbackTargetLuma
        val snapshot = defaultRawPhotometricConvergenceTracker.observe(
            currentGeneration = pipelineGeneration,
            currentRoute = route,
            allocationReady = defaultRawAllocationReady,
            targetLuma = targetLuma,
            observedLuma = observedLuma,
            realizationStatus = truth?.status,
            aeStable = defaultRawLastAeStable,
            nowElapsedNs = android.os.SystemClock.elapsedRealtimeNanos()
        )
        latestDefaultRawPhotometricConvergence = snapshot
        val key = "${snapshot.route}:${snapshot.photometricConverged}:${snapshot.reason}"
        if (key != lastDefaultRawPhotometricConvergenceLogKey) {
            lastDefaultRawPhotometricConvergenceLogKey = key
            Log.i(tag, "DEFAULT_RAW_PHOTOMETRIC_STATE ${snapshot.summary()}")
        }
    }

    private fun updateDefaultRawMeteringArchitecture(statistics: ExposureStatistics) {
        if (!shouldRequestDefaultRawShutterMotionAnalysis()) return
        val now = android.os.SystemClock.elapsedRealtimeNanos()
        val observedLuma = statistics.exposureControllerLuma()
            .takeIf { it.isFinite() && it > 0f }
        val metering = defaultRawMeteringTracker.observe(
            currentGeneration = pipelineGeneration,
            source = statistics.source,
            controllerLuma = observedLuma,
            rawNearClipFraction = statistics.rawNearClipFraction ?: statistics.maximumDisplayClipFraction,
            sampleCount = statistics.sampleCount,
            nowElapsedRealtimeNs = now
        )
        latestDefaultRawMetering = metering

        val currentProduct = defaultRawShutterFallbackPlan?.exposureProduct
            ?: if (defaultRawShutterAeBaselineGeneration == pipelineGeneration) {
                val iso = defaultRawShutterAeBaselineIso
                val exposure = defaultRawShutterAeBaselineExposureNs
                if (iso != null && iso > 0 && exposure != null && exposure > 0L) {
                    iso.toDouble() * exposure.toDouble()
                } else {
                    null
                }
            } else {
                null
            }
        val targetLuma = defaultRawPhotometricTargetLuma ?: defaultRawShutterFallbackTargetLuma
        val target = if (targetLuma != null && currentProduct != null) {
            DefaultRawExposureTargetModel.resolve(
                targetLuma = targetLuma,
                metering = metering,
                currentExposureProduct = currentProduct
            )
        } else {
            null
        }
        latestDefaultRawExposureTarget = target

        val key = if (target != null) {
            "${metering.freshness}:${kotlin.math.round(target.ideal.exposureErrorEv * 20f) / 20f}:" +
                "${kotlin.math.round(target.highlightProtectionEv * 20f) / 20f}:${target.reason}"
        } else {
            "${metering.freshness}:target_unavailable"
        }
        if (key != lastDefaultRawExposureTargetLogKey) {
            lastDefaultRawExposureTargetLogKey = key
            Log.i(
                tag,
                "DEFAULT_RAW_EXPOSURE_TARGET ${metering.summary()};" +
                    (target?.summary() ?: "idealTarget=unavailable")
            )
        }
    }

    private fun defaultRawConvergenceSummarySuffix(): String {
        val convergence = latestDefaultRawPhotometricConvergence
        val fallback = defaultRawShutterFallbackPlan
        val metering = latestDefaultRawMetering
        val target = latestDefaultRawExposureTarget
        return "allocationReady=$defaultRawAllocationReady;" +
            "photometricConverged=${convergence?.photometricConverged ?: false};" +
            "exposureErrorEv=${convergence?.exposureErrorEv ?: "unavailable"};" +
            "meteringFreshness=${metering?.freshness ?: "unavailable"};" +
            "idealExposureProduct=${target?.ideal?.idealExposureProduct ?: "unavailable"};" +
            "finalExposureProduct=${target?.finalExposureProduct ?: "unavailable"};" +
            "highlightProtectionEv=${target?.highlightProtectionEv ?: 0f};" +
            "allocatedExposureNs=${latestDefaultRawExposureAllocation?.exposureTimeNs ?: fallback?.exposureTimeNs ?: "unavailable"};" +
            "allocatedIso=${latestDefaultRawExposureAllocation?.sensitivityIso ?: fallback?.sensitivityIso ?: "unavailable"};" +
            "allocationLimit=${latestDefaultRawExposureAllocation?.limitingConstraint ?: fallback?.limitingConstraint ?: "unavailable"};" +
            "feedbackCorrectionEv=${fallback?.feedbackCorrectionEv ?: 0f};" +
            "motionReallocationEv=${fallback?.motionReallocationEv ?: 0f};" +
            "framesSinceExposureRequest=$defaultRawFramesSinceExposureRequest"
    }

    private fun maybeAdaptDefaultRawShutterFallback(statistics: ExposureStatistics) {
        if (!needsDefaultRawShutterFallbackStatistics() || isCapturing || statistics.sampleCount <= 0) return
        val observedLuma = statistics.exposureControllerLuma()
            .takeIf { it.isFinite() && it > 0f } ?: return

        if (defaultRawShutterFallbackTargetLuma == null) {
            if (defaultRawShutterAeBaselineGeneration != pipelineGeneration ||
                defaultRawShutterAeBaselineIso == null || defaultRawShutterAeBaselineExposureNs == null
            ) return
            val target = DefaultRawTargetContinuity.resolveFallbackTarget(
                fallbackTargetLuma = defaultRawShutterFallbackTargetLuma,
                photometricTargetLuma = defaultRawPhotometricTargetLuma,
                observedLuma = observedLuma
            ) ?: return
            // Route changes must preserve the established photometric set-point. Re-anchoring to
            // the current observation here would turn residual underexposure into the new target.
            defaultRawShutterFallbackTargetLuma = target
            if (defaultRawPhotometricTargetLuma == null) defaultRawPhotometricTargetLuma = target
            defaultRawShutterFallbackLastAdaptationNs = android.os.SystemClock.elapsedRealtimeNanos()
            Log.i(
                tag,
                "DEFAULT_RAW_SHUTTER_FALLBACK_TARGET_CONTINUITY targetLuma=$target " +
                    "observedLuma=$observedLuma source=${statistics.source}"
            )
            enqueuePreviewControl("default_raw_shutter_fallback", "RAW_FALLBACK_TARGET_CONTINUITY") {
                updatePreviewRepeatingRequest()
            }
            return
        }

        val previous = defaultRawShutterFallbackPlan ?: return
        val clipping = statistics.rawNearClipFraction ?: statistics.maximumDisplayClipFraction
        val now = android.os.SystemClock.elapsedRealtimeNanos()
        val minimumAdaptationIntervalNs =
            DefaultRawShutterManualFallbackPolicy.recommendedUpdateIntervalNs(
                previous = previous,
                observedLuma = observedLuma,
                rawNearClipFraction = clipping
            )
        if (now - defaultRawShutterFallbackLastAdaptationNs < minimumAdaptationIntervalNs) return
        val safeCeiling = defaultRawShutterLastSafeExposureCeilingNs.takeIf { it > 0L } ?: return
        val deviceId = cameraDevice?.id ?: return
        val chars = runCatching { cameraManager.getCameraCharacteristics(deviceId) }.getOrNull() ?: return
        val isoRange = chars.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE) ?: return
        val exposureRange = chars.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE) ?: return
        val bounds = ExposureBounds(isoRange.lower, isoRange.upper, exposureRange.lower, exposureRange.upper)

        val adapted = synchronized(defaultRawShutterFallbackLock) {
            val latest = defaultRawShutterFallbackPlan ?: return@synchronized null
            DefaultRawShutterManualFallbackPolicy.adapt(
                previous = latest,
                observedLuma = observedLuma,
                rawNearClipFraction = clipping,
                safeExposureCeilingNs = safeCeiling,
                bounds = bounds,
                flickerConstraint = resolveDefaultRawFlickerConstraint()
            ).also { candidate ->
                if (kotlin.math.abs(candidate.lastCorrectionEv) >= 0.025f ||
                    candidate.exposureTimeNs != latest.exposureTimeNs ||
                    candidate.sensitivityIso != latest.sensitivityIso
                ) {
                    defaultRawShutterFallbackPlan = candidate
                    defaultRawShutterFallbackLastAdaptationNs = now
                }
            }
        } ?: return
        if (adapted.lastCorrectionEv == 0f &&
            adapted.exposureTimeNs == previous.exposureTimeNs &&
            adapted.sensitivityIso == previous.sensitivityIso
        ) return

        Log.d(tag, "DEFAULT_RAW_SHUTTER_FALLBACK_ADAPT source=${statistics.source} ${adapted.summary()}")
        enqueuePreviewControl("default_raw_shutter_fallback", "RAW_FALLBACK_LUMA_ADAPT") {
            updatePreviewRepeatingRequest()
        }
    }

    private fun maybeAdaptProfileExposurePriority(statistics: ExposureStatistics) {
        if (isCapturing || !needsProfileExposureStatistics() || profileExposureAwaitingAeBaseline) return
        if (statistics.sampleCount <= 0) return
        val currentGeneration = pipelineGeneration
        val currentPlan = activeProfileExposurePlan
            ?.takeIf {
                activeProfileExposurePlanGeneration == currentGeneration &&
                    it.ready && !it.autoExposure
            } ?: return

        val observedLuma = statistics.exposureControllerLuma()
            .takeIf { it.isFinite() && it > 0f } ?: return
        val totalEv = activeProfileExposureTotalEv()
        val evScale = Math.pow(2.0, totalEv.toDouble()).toFloat()
        val baselineLuma = profileExposureAeBaselineControllerLuma
            ?.takeIf { it.isFinite() && it > 0f }
            ?: (observedLuma / evScale.coerceAtLeast(0.0625f)).coerceIn(0.002f, 0.90f).also {
                profileExposureAeBaselineControllerLuma = it
            }
        val targetLuma = (baselineLuma * evScale).coerceIn(0.005f, 0.90f)
        val clipping = (statistics.rawNearClipFraction ?: statistics.maximumDisplayClipFraction)
            .takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0f
        val nowNs = android.os.SystemClock.elapsedRealtimeNanos()

        val adapted = synchronized(profileExposureAdaptationLock) {
            if (nowNs - profileExposureLastAdaptationElapsedNs < 250_000_000L) return
            val latestPlan = activeProfileExposurePlan
                ?.takeIf {
                    activeProfileExposurePlanGeneration == currentGeneration &&
                        it.ready && !it.autoExposure && it.mode == currentPlan.mode
                } ?: return
            val bounds = activeProfileExposureBounds ?: return
            val candidate = ProfileExposurePriorityPlanner.adaptToLuma(
                previous = latestPlan,
                observedLuma = observedLuma,
                targetLuma = targetLuma,
                clippingFraction = clipping,
                bounds = bounds
            )
            val oldProduct = latestPlan.achievedExposureProduct ?: return
            val newProduct = candidate.achievedExposureProduct ?: return
            val deltaEv = kotlin.math.abs(
                kotlin.math.log2((newProduct / oldProduct).coerceAtLeast(1.0e-9))
            )
            if (deltaEv < 0.04 && candidate.limitingConstraint == latestPlan.limitingConstraint) return
            activeProfileExposurePlan = candidate
            activeProfileExposurePlanGeneration = currentGeneration
            profileExposureLastAdaptationElapsedNs = nowNs
            candidate
        }

        Log.d(
            tag,
            "PROFILE_EXPOSURE_LIVE_ADAPT mode=${adapted.mode.persistedValue} source=${statistics.source} " +
                "observedLuma=$observedLuma targetLuma=$targetLuma clip=$clipping " +
                "exposureNs=${adapted.exposureTimeNs} iso=${adapted.sensitivityIso} limit=${adapted.limitingConstraint}"
        )
        enqueuePreviewControl("profile_exposure_live", "PROFILE_EXPOSURE_LIVE_ADAPT") {
            updatePreviewRepeatingRequest()
        }
    }

    private fun publishLiveExposureStatistics(statistics: ExposureStatistics) {
        if (statistics.sampleCount <= 0) return
        latestExposureStatistics = statistics
        maybeAdaptDefaultRawShutterFallback(statistics)
        maybeAdaptProfileExposurePriority(statistics)
        updateDefaultRawPhotometricConvergence(statistics)
        updateDefaultRawMeteringArchitecture(statistics)

        // Standard AE metering is fully Camera2-region driven. Statistics remain useful for the
        // optional live histogram and diagnostics, but no longer inject hidden EV compensation.
        if (histogramAnalysisEnabled) {
            _liveRgbHistogram.tryEmit(statistics.normalizedHistogram())
            val luma16 = FloatArray(16) { group ->
                var total = 0
                for (offset in 0 until 4) total += statistics.lumaHistogram64[group * 4 + offset]
                total.toFloat()
            }
            val maximum = luma16.maxOrNull() ?: 0f
            _liveHistogram.tryEmit(if (maximum > 0f) luma16.map { it / maximum } else List(16) { 0f })
        }

        liveHighDrRisk = statistics.highDynamicRangeRisk
        Log.d(
            tag,
            "EXPOSURE_STATS source=${statistics.source} mode=${MeteringMode.fromSetting(currentMeteringStyle).settingValue} " +
                "highDr=$liveHighDrRisk rawNearClip=${statistics.rawNearClipFraction} " +
                "displayClip=${statistics.maximumDisplayClipFraction}"
        )
    }

    private class PreviewNv21(val bytes: ByteArray, val width: Int, val height: Int)

    private fun rawPreviewToNv21(frame: RawPreviewFrame): PreviewNv21? {
        val compactGpuNv21 = frame.analysisNv21
        if (compactGpuNv21 != null && frame.analysisNv21Width > 0 && frame.analysisNv21Height > 0) {
            val width = frame.analysisNv21Width and -2
            val height = frame.analysisNv21Height and -2
            val byteCount = width * height * 3 / 2
            if (width > 0 && height > 0 && byteCount <= compactGpuNv21.capacity()) {
                val output = ByteArray(byteCount)
                val source = compactGpuNv21.duplicate().apply { position(0); limit(byteCount) }
                source.get(output, 0, byteCount)
                return PreviewNv21(output, width, height)
            }
        }
        // A GPU-resident frame deliberately has no full RGBA host copy. If ML analysis was toggled
        // between frames, wait for the next frame carrying the compact Vulkan NV21 analysis image rather
        // than reading stale bytes from the legacy output slot.
        if (frame.gpuResidentOutputUsed) return null

        val sourceWidth = frame.width.coerceAtLeast(1)
        val sourceHeight = frame.height.coerceAtLeast(1)
        if (sourceWidth.toLong() * sourceHeight.toLong() * 4L > frame.rgba.capacity().toLong()) return null
        var decimation = 1
        while (max(sourceWidth / decimation, sourceHeight / decimation) > 640) decimation *= 2
        var width = (sourceWidth / decimation).coerceAtLeast(2) and -2
        var height = (sourceHeight / decimation).coerceAtLeast(2) and -2
        if (width <= 0 || height <= 0) return null
        val output = ByteArray(width * height * 3 / 2)
        val rgba = frame.rgba.duplicate()
        var dst = 0
        for (y in 0 until height) {
            val sourceY = (y * decimation).coerceAtMost(sourceHeight - 1)
            for (x in 0 until width) {
                val sourceX = (x * decimation).coerceAtMost(sourceWidth - 1)
                val offset = (sourceY * sourceWidth + sourceX) * 4
                val r = rgba.get(offset).toInt() and 0xFF
                val g = rgba.get(offset + 1).toInt() and 0xFF
                val b = rgba.get(offset + 2).toInt() and 0xFF
                output[dst++] = ((77 * r + 150 * g + 29 * b) shr 8).coerceIn(0, 255).toByte()
            }
        }
        // Neutral chroma is sufficient for QR/object geometry and avoids an expensive RGB→YUV
        // conversion. ML Kit receives real developed RAW luminance and the correct image aspect.
        java.util.Arrays.fill(output, width * height, output.size, 128.toByte())
        return PreviewNv21(output, width, height)
    }

    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null

    private fun startBackgroundThread() {
        val active = backgroundThread
        if (active?.isAlive == true) return
        backgroundThread = HandlerThread("CameraBackground").also { it.start() }
        backgroundHandler = Handler(backgroundThread!!.looper)
    }

    private fun stopBackgroundThread(threadToStop: HandlerThread? = backgroundThread) {
        if (threadToStop == null) return
        clearPendingPreviewControls("camera_background_stop")
        threadToStop.quitSafely()
        if (backgroundThread === threadToStop) {
            backgroundThread = null
            backgroundHandler = null
        }
        Log.i(
            previewDiagnosticsTag,
            "event=CAMERA_BACKGROUND_THREAD_STOP_REQUESTED thread=${threadToStop.name} " +
                "caller=${Thread.currentThread().name} blockingJoin=false"
        )
    }

    private data class SubmittedCameraRequest(
        val sequenceId: Int,
        val request: CaptureRequest,
        val prepared: PreparedControlRequest
    )

    private data class SubmittedCameraBurst(
        val sequenceId: Int,
        val requests: List<CaptureRequest>,
        val prepared: PreparedControlRequest,
        val submittedElapsedRealtimeNs: Long
    )

    private fun snapshotRect(rect: Rect?): ImmutableRectSnapshot? = rect?.let {
        ImmutableRectSnapshot(it.left, it.top, it.right, it.bottom)
    }

    private fun snapshotMeteringRegions(
        regions: Array<MeteringRectangle>?
    ): List<ImmutableMeteringRegionSnapshot> = regions.orEmpty().map { region ->
        ImmutableMeteringRegionSnapshot(
            rect = snapshotRect(region.rect)
                ?: error("MeteringRectangle must contain a rectangle."),
            weight = region.meteringWeight
        )
    }

    private fun snapshotControlRequestState(
        builder: CaptureRequest.Builder
    ): ControlRequestState {
        fun <T> read(key: CaptureRequest.Key<T>): T? =
            runCatching { builder.get(key) }.getOrNull()

        val fpsRange = read(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE)
        return ControlRequestState.create(
            aeRegions = snapshotMeteringRegions(read(CaptureRequest.CONTROL_AE_REGIONS)),
            afRegions = snapshotMeteringRegions(read(CaptureRequest.CONTROL_AF_REGIONS)),
            controlMode = read(CaptureRequest.CONTROL_MODE),
            controlSceneMode = read(CaptureRequest.CONTROL_SCENE_MODE),
            captureIntent = read(CaptureRequest.CONTROL_CAPTURE_INTENT),
            aeMode = read(CaptureRequest.CONTROL_AE_MODE),
            aeLock = read(CaptureRequest.CONTROL_AE_LOCK),
            aeExposureCompensation =
                read(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION),
            aePrecaptureTrigger =
                read(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER),
            aeAntibandingMode =
                read(CaptureRequest.CONTROL_AE_ANTIBANDING_MODE),
            afMode = read(CaptureRequest.CONTROL_AF_MODE),
            afTrigger = read(CaptureRequest.CONTROL_AF_TRIGGER),
            flashMode = read(CaptureRequest.FLASH_MODE),
            sensorSensitivityIso = read(CaptureRequest.SENSOR_SENSITIVITY),
            sensorExposureTimeNs = read(CaptureRequest.SENSOR_EXPOSURE_TIME),
            sensorFrameDurationNs = read(CaptureRequest.SENSOR_FRAME_DURATION),
            lensFocusDistance = read(CaptureRequest.LENS_FOCUS_DISTANCE),
            lensOpticalStabilizationMode = read(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE),
            videoStabilizationMode = read(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE),
            aeTargetFpsLower = fpsRange?.lower,
            aeTargetFpsUpper = fpsRange?.upper,
            cropRegion = snapshotRect(read(CaptureRequest.SCALER_CROP_REGION)),
            zoomRatio = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                read(CaptureRequest.CONTROL_ZOOM_RATIO)
            } else {
                null
            }
        )
    }

    private fun prepareRequestProvenance(
        builder: CaptureRequest.Builder,
        pipelineGenerationAtSubmission: Int,
        submissionType: CameraRequestSubmissionType,
        reason: String
    ): PreparedControlRequest = controlRequestEpochTracker.prepare(
        pipelineGeneration = pipelineGenerationAtSubmission,
        state = snapshotControlRequestState(builder),
        submissionType = submissionType,
        submissionReason = reason,
        meteringPolicySummary = lastMeteringPlanSummary,
        exposurePolicySummary = lastExposurePlanSummary,
        submittedElapsedRealtimeNs =
            android.os.SystemClock.elapsedRealtimeNanos(),
        focusOwner = _focusOwnership.value.owner.name
    )

    private fun applyOptimalAeTargetFpsRange(
        builder: CaptureRequest.Builder,
        cameraId: String
    ) {
        runCatching {
            val chars = cameraManager.getCameraCharacteristics(cameraId)
            val availableRanges = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES) ?: return
            if (availableRanges.isEmpty()) return

            val identity = synchronized(pipelineLock) { activePipelineIdentity }
            val rawIdentity = identity?.takeIf { candidate ->
                candidate.bufferFormat == ImageFormat.RAW10 ||
                    candidate.bufferFormat == ImageFormat.RAW_SENSOR
            }
            // Dynamic STATISTICS_SCENE_FLICKER authority is RAW-only. Normal YUV preview already
            // runs Camera2 AE with the configured anti-banding mode; feeding YUV flicker-result
            // transitions back into acquisition control creates a redundant AE feedback loop.
            val flickerConstraint = if (rawIdentity != null) {
                resolveDefaultRawFlickerConstraint()
            } else {
                RawFlickerConstraint(
                    frequency = RawFlickerFrequency.NONE,
                    source = "CAMERA2_HAL_OWNS_NON_RAW_ANTIBANDING",
                    fallbackActive = false
                )
            }
            fun flickerRangeFor(sustainableUpperFps: Int?): Pair<android.util.Range<Int>?, String> {
                val plan = RawFlickerCadencePolicy.resolve(
                    availableRanges = availableRanges.map { FlickerFpsRange(it.lower, it.upper) },
                    sustainableUpperFps = sustainableUpperFps?.takeIf { it > 0 },
                    frequency = flickerConstraint.frequency,
                    preferAdaptiveLower = true
                )
                val selected = plan.selected?.let { android.util.Range(it.lower, it.upper) }
                return selected to plan.strategy
            }

            if (rawIdentity != null) {
                // Full-resolution RAW owns the cadence decision. Do not force the generic 60 FPS
                // preview policy onto a 12 MP RAW stream: some HALs satisfy that request by using
                // a faster cropped sensor readout while keeping the advertised RAW allocation.
                // Camera2 exposes the minimum frame duration for the exact selected output; use
                // that as the upper bound for the RAW near-ZSL repeating request.
                val streamCharacteristicsId = rawIdentity.physicalCameraId ?: rawIdentity.logicalCameraId
                val streamCharacteristics = runCatching {
                    cameraManager.getCameraCharacteristics(streamCharacteristicsId)
                }.getOrNull() ?: chars
                val streamMap = streamCharacteristics.get(
                    CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP
                )
                val selectedSize = android.util.Size(rawIdentity.width, rawIdentity.height)
                val minimumFrameDurationNs = runCatching {
                    streamMap?.getOutputMinFrameDuration(rawIdentity.bufferFormat, selectedSize) ?: 0L
                }.getOrDefault(0L)
                val sustainableUpperFps = if (minimumFrameDurationNs > 0L) {
                    // +0.5 handles the normal nanosecond rounding of nominal 15/24/30 FPS modes.
                    ((1_000_000_000.0 / minimumFrameDurationNs.toDouble()) + 0.5)
                        .toInt()
                        .coerceAtLeast(1)
                } else {
                    0
                }

                // A resolved flicker frequency must never overrule RAW stream-completeness truth.
                // Without an exact min-frame-duration contract, preserve the old conservative RAW
                // cadence and let shutter integration provide anti-flicker protection.
                val (flickerSelected, flickerStrategy) = if (sustainableUpperFps > 0) {
                    flickerRangeFor(sustainableUpperFps)
                } else {
                    null to "RAW_FLICKER_CADENCE_WITHHELD_NO_STREAM_DURATION_CONTRACT"
                }
                val rawCompatibleRanges = if (sustainableUpperFps > 0) {
                    availableRanges.filter { range -> range.upper <= sustainableUpperFps }
                } else {
                    emptyList()
                }
                val selectedRawRange = flickerSelected
                    ?: rawCompatibleRanges
                        .sortedWith(
                            compareByDescending<android.util.Range<Int>> { it.upper }
                                .thenByDescending { it.lower in 7..20 && it.lower < it.upper }
                                .thenBy { it.lower }
                        )
                        .firstOrNull()
                    // If the HAL reports no usable min-duration contract, prioritize sensor
                    // completeness over preview cadence by taking its least aggressive AE range.
                    ?: availableRanges
                        .sortedWith(
                            compareBy<android.util.Range<Int>> { it.upper }
                                .thenBy { it.lower }
                        )
                        .firstOrNull()

                if (selectedRawRange != null) {
                    builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, selectedRawRange)
                    val strategy = when {
                        flickerSelected != null -> flickerStrategy
                        minimumFrameDurationNs <= 0L -> "RAW_STREAM_CONSERVATIVE_NO_DURATION_CONTRACT"
                        else -> "RAW_STREAM_MIN_FRAME_DURATION"
                    }
                    Log.i(
                        tag,
                        "AE_TARGET_FPS_RANGE_SELECTED range=$selectedRawRange strategy=$strategy " +
                            "flicker=${flickerConstraint.frequency} flickerSource=${flickerConstraint.source} " +
                            "fallback=${flickerConstraint.fallbackActive} " +
                            "format=${formatName(rawIdentity.bufferFormat)} size=${rawIdentity.width}x${rawIdentity.height} " +
                            "streamCamera=$streamCharacteristicsId minFrameDurationNs=$minimumFrameDurationNs " +
                            "sustainableUpperFps=$sustainableUpperFps"
                    )
                    return
                }
            }

            if (identity?.bufferFormat == ImageFormat.YUV_420_888) {
                // Full-FOV YUV geometry is also authoritative. A globally advertised 60 FPS AE
                // range is not proof that the exact selected YUV stream can sustain 60 FPS. Some
                // HALs satisfy that request by switching to a faster cropped sensor readout. Bound
                // cadence to the selected physical stream's own minimum-frame-duration contract so
                // 60 FPS remains enabled where the full-FOV stream genuinely supports it, while an
                // ultra-wide that is full-FOV only at 30 FPS stays full-FOV.
                val streamCharacteristicsId = identity.physicalCameraId ?: identity.logicalCameraId
                val streamCharacteristics = runCatching {
                    cameraManager.getCameraCharacteristics(streamCharacteristicsId)
                }.getOrNull() ?: chars
                val streamMap = streamCharacteristics.get(
                    CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP
                )
                val selectedSize = android.util.Size(identity.width, identity.height)
                val imageReaderMinFrameDurationNs = runCatching {
                    streamMap?.getOutputMinFrameDuration(ImageFormat.YUV_420_888, selectedSize) ?: 0L
                }.getOrDefault(0L)
                val previewSize = if (configuredPreviewStreamWidth > 0 && configuredPreviewStreamHeight > 0) {
                    android.util.Size(configuredPreviewStreamWidth, configuredPreviewStreamHeight)
                } else {
                    null
                }
                val previewMinFrameDurationNs = previewSize?.let { size ->
                    runCatching {
                        streamMap?.getOutputMinFrameDuration(
                            android.graphics.SurfaceTexture::class.java,
                            size
                        ) ?: 0L
                    }.getOrDefault(0L)
                } ?: 0L
                val minimumFrameDurationNs = maxOf(
                    imageReaderMinFrameDurationNs,
                    previewMinFrameDurationNs
                )

                if (minimumFrameDurationNs > 0L) {
                    val sustainableUpperFps =
                        ((1_000_000_000.0 / minimumFrameDurationNs.toDouble()) + 0.5)
                            .toInt()
                            .coerceAtLeast(1)
                    val (flickerSelected, flickerStrategy) = flickerRangeFor(sustainableUpperFps)
                    val compatibleRanges = availableRanges
                        .filter { range -> range.upper <= sustainableUpperFps }
                    val selectedYuvRange = flickerSelected
                        ?: compatibleRanges
                            .sortedWith(
                                compareByDescending<android.util.Range<Int>> { it.upper }
                                    .thenBy { it.lower }
                            )
                            .firstOrNull()
                        ?: availableRanges
                            .sortedWith(
                                compareBy<android.util.Range<Int>> { it.upper }
                                    .thenBy { it.lower }
                            )
                            .firstOrNull()

                    if (selectedYuvRange != null) {
                        builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, selectedYuvRange)
                        val strategy = if (flickerSelected != null) flickerStrategy else
                            "YUV_FULL_FOV_STREAM_MIN_FRAME_DURATION"
                        Log.i(
                            tag,
                            "AE_TARGET_FPS_RANGE_SELECTED range=$selectedYuvRange strategy=$strategy " +
                                "flicker=${flickerConstraint.frequency} flickerSource=${flickerConstraint.source} " +
                                "fallback=${flickerConstraint.fallbackActive} " +
                                "imageReaderSize=${identity.width}x${identity.height} " +
                                "previewSize=${previewSize?.let { "${it.width}x${it.height}" } ?: "unknown"} " +
                                "streamCamera=$streamCharacteristicsId " +
                                "imageReaderMinFrameDurationNs=$imageReaderMinFrameDurationNs " +
                                "previewMinFrameDurationNs=$previewMinFrameDurationNs " +
                                "limitingMinFrameDurationNs=$minimumFrameDurationNs " +
                                "sustainableUpperFps=$sustainableUpperFps"
                        )
                        return
                    }
                }
            }

            // If exact stream-duration truth is unavailable, a resolved mains frequency still gets
            // a stable advertised cadence before the generic high-FPS viewfinder policy.
            val (genericFlickerRange, genericFlickerStrategy) = flickerRangeFor(null)
            if (genericFlickerRange != null) {
                builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, genericFlickerRange)
                Log.i(
                    tag,
                    "AE_TARGET_FPS_RANGE_SELECTED range=$genericFlickerRange strategy=$genericFlickerStrategy " +
                        "flicker=${flickerConstraint.frequency} flickerSource=${flickerConstraint.source} " +
                        "fallback=${flickerConstraint.fallbackActive} streamContract=unavailable"
                )
                return
            }

            // Last-resort YUV/viewfinder fallback when the HAL gives no exact stream-duration
            // contract. Preserve the existing preference for a variable 60 FPS range.
            val variable60Range = availableRanges.filter { it.lower <= 30 && it.upper >= 60 }
                .maxByOrNull { it.upper }

            if (variable60Range != null) {
                builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, variable60Range)
                Log.i(tag, "AE_TARGET_FPS_RANGE_SELECTED range=$variable60Range strategy=VARIABLE_60_FPS isRaw=${rawIdentity != null}")
                return
            }

            // 2. If no variable 60 FPS range is exposed, look for a fixed/max 60 FPS range
            val max60Range = availableRanges.filter { it.upper >= 60 }.maxByOrNull { it.lower }

            // Check recent exposure time for hysteretic switching on fixed range devices
            val recentExposureNs = (lastCaptureResult as? TotalCaptureResult)?.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: 0L
            val recentExposureMs = recentExposureNs / 1_000_000.0f
            val lowLightNeeded = recentExposureMs > 20.0f

            val selectedRange = if (max60Range != null && !lowLightNeeded) {
                max60Range
            } else {
                availableRanges.filter { it.upper <= 30 }.maxByOrNull { it.upper }
                    ?: availableRanges.maxByOrNull { it.upper }
            }

            if (selectedRange != null) {
                builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, selectedRange)
                Log.i(tag, "AE_TARGET_FPS_RANGE_SELECTED range=$selectedRange strategy=HYSTERETIC_EXPOSURE_SWITCHING exposureMs=$recentExposureMs isRaw=${rawIdentity != null}")
            }
        }.onFailure { Log.w(tag, "Failed to apply optimal AE target FPS range", it) }
    }

    /**
     * Last-write stabilization contract applied immediately before every Camera2 submission.
     *
     * The Honor hidden-physical tele route under-reports OIS capability metadata, while GCam
     * proves that the same logical->physical route accepts the standard OIS request and returns
     * LENS_OPTICAL_STABILIZATION_MODE_ON. Keeping this at the single submission choke point also
     * prevents later AF/AE/vendor mutations from accidentally carrying an earlier OFF value.
     */
    private fun enforceOpticalStabilizationBeforeSubmission(
        builder: CaptureRequest.Builder,
        reason: String
    ) {
        val decision = activeOisDecision ?: return
        if (!decision.applied) return

        when (decision.appliedMethod) {
            OisDecision.OisMethod.LOGICAL_OIS -> {
                runCatching {
                    builder.set(
                        CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
                        CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON
                    )
                    builder.set(
                        CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                        CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF
                    )
                }.onSuccess {
                    Log.d(
                        "OisResolver",
                        "OIS_SUBMISSION_ENFORCED reason=$reason method=LOGICAL_OIS " +
                            "logical=${decision.logicalCameraId} physical=${decision.physicalCameraId ?: "none"} " +
                            "ois=ON eis=OFF"
                    )
                }.onFailure { throwable ->
                    Log.e(
                        "OisResolver",
                        "Failed to enforce standard OIS at submission reason=$reason " +
                            "logical=${decision.logicalCameraId} physical=${decision.physicalCameraId ?: "none"}",
                        throwable
                    )
                }
            }

            OisDecision.OisMethod.PHYSICAL_OIS -> {
                if (decision.physicalCameraId != null) {
                    runCatching {
                        builder.setPhysicalCameraKey(
                            CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
                            CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON,
                            decision.physicalCameraId
                        )
                        builder.set(
                            CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                            CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF
                        )
                    }.onFailure { throwable ->
                        Log.e(
                            "OisResolver",
                            "Failed to enforce physical OIS at submission reason=$reason physical=${decision.physicalCameraId}",
                            throwable
                        )
                    }
                }
            }

            OisDecision.OisMethod.VENDOR -> {
                // Vendor optical command is applied earlier; only defend against accidental EIS.
                runCatching {
                    builder.set(
                        CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                        CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF
                    )
                }
            }

            OisDecision.OisMethod.PREVIEW_STAB,
            OisDecision.OisMethod.VIDEO_STAB,
            OisDecision.OisMethod.FAILED -> Unit
        }
    }

    /**
     * Hidden-but-direct cameras may accept a standard OIS request despite incomplete static
     * metadata. CaptureResult is the qualification authority: ON permanently qualifies this
     * runtime route; 30 explicit OFF echoes fail it closed without substituting crop-changing EIS.
     */
    private fun validateRuntimeProbedDirectOis(result: TotalCaptureResult) {
        val decision = activeOisDecision ?: return
        if (!decision.requiresRuntimeResultValidation || !decision.applied) return

        val reported = result.get(CaptureResult.LENS_OPTICAL_STABILIZATION_MODE) ?: return
        var rejectedDecision: OisDecision? = null
        synchronized(directOisValidationLock) {
            if (directOisValidationDecision !== decision) {
                directOisValidationDecision = decision
                directOisValidationOffFrames = 0
            }
            if (reported == CaptureResult.LENS_OPTICAL_STABILIZATION_MODE_ON) {
                activeOisDecision = OisDecision(
                    logicalCameraId = decision.logicalCameraId,
                    physicalCameraId = decision.physicalCameraId,
                    appliedMethod = decision.appliedMethod,
                    appliedValue = decision.appliedValue,
                    applied = true,
                    reason = decision.reason + "; CaptureResult qualified direct OIS ON",
                    vendorOpticalKeyName = decision.vendorOpticalKeyName,
                    requiresRuntimeResultValidation = false
                )
                directOisValidationDecision = null
                directOisValidationOffFrames = 0
                Log.i("OisResolver", "OIS_DIRECT_RUNTIME_QUALIFIED camera=${decision.logicalCameraId} result=ON")
            } else {
                directOisValidationOffFrames += 1
                if (directOisValidationOffFrames >= 30) {
                    rejectedDecision = OisDecision(
                        logicalCameraId = decision.logicalCameraId,
                        physicalCameraId = null,
                        appliedMethod = OisDecision.OisMethod.FAILED,
                        appliedValue = CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF,
                        applied = false,
                        reason = "Direct OIS runtime probe rejected: CaptureResult reported OFF for 30 frames",
                        requiresRuntimeResultValidation = false
                    )
                    activeOisDecision = rejectedDecision
                    directOisValidationDecision = null
                    directOisValidationOffFrames = 0
                }
            }
        }

        if (rejectedDecision != null) {
            Log.w("OisResolver", "OIS_DIRECT_RUNTIME_REJECTED camera=${decision.logicalCameraId} result=OFF")
            backgroundHandler?.post {
                val builder = currentCaptureRequest ?: return@post
                val session = captureSession ?: return@post
                runCatching {
                    builder.set(
                        CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
                        CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF
                    )
                    builder.set(
                        CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                        CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF
                    )
                    submitRepeatingRequestWithProvenance(
                        session = session,
                        builder = builder,
                        callback = captureCallback,
                        handler = backgroundHandler,
                        reason = "DIRECT_OIS_RUNTIME_PROBE_REJECTED"
                    )
                }.onFailure { throwable ->
                    Log.e("OisResolver", "Failed to apply rejected direct OIS result", throwable)
                }
            }
        }
    }

    private fun traceAfWriter(
        builder: CaptureRequest.Builder,
        sourceFunction: String,
        reason: String
    ) {
        com.bncam.core.debug.AfGroundTruthTrace.recordWriter(
            builder = builder,
            sourceFunction = sourceFunction,
            reason = reason
        )
    }

    private fun submitRepeatingRequestWithProvenance(
        session: CameraCaptureSession,
        builder: CaptureRequest.Builder,
        callback: CameraCaptureSession.CaptureCallback?,
        handler: Handler?,
        reason: String,
        pipelineGenerationAtSubmission: Int = pipelineGeneration
    ): SubmittedCameraRequest = synchronized(requestSubmissionLock) {
        applyOptimalAeTargetFpsRange(builder, session.device.id)
        enforceOpticalStabilizationBeforeSubmission(builder, reason)
        val prepared = prepareRequestProvenance(
            builder = builder,
            pipelineGenerationAtSubmission = pipelineGenerationAtSubmission,
            submissionType = CameraRequestSubmissionType.REPEATING,
            reason = reason
        )
        builder.setTag(prepared.tag)
        val request = builder.build()
        val sequenceId = session.setRepeatingRequest(request, callback, handler)
        com.bncam.core.debug.AfGroundTruthTrace.recordSubmission(
            builder = builder,
            request = request,
            requestSequenceNumber = sequenceId,
            submissionType = "REPEATING",
            reason = reason
        )
        val activeLogicalId = synchronized(pipelineLock) { activePipelineIdentity?.logicalCameraId } ?: session.device.id
        val activePhysicalId = synchronized(pipelineLock) { activePipelineIdentity?.physicalCameraId }
        com.bncam.core.debug.HalParityAuditor.auditRepeatingRequest(activeLogicalId, activePhysicalId, request)
        com.bncam.core.debug.AfParityAuditor.auditAfRequest(activeLogicalId, activePhysicalId, request, reasonTag = reason)
        controlRequestEpochTracker.commit(prepared)
        Log.i(
            "CameraRequestProvenance",
            "event=REQUEST_SUBMITTED type=REPEATING reason=$reason " +
                    "pipelineGeneration=${prepared.tag.pipelineGeneration} " +
                    "controlRequestEpoch=${prepared.tag.controlRequestEpoch} " +
                    "epochAdvanced=${prepared.advancesEpoch} sequenceId=$sequenceId " +
                    "requestSnapshot={${prepared.tag.snapshot.diagnosticSummary()}}"
        )
        SubmittedCameraRequest(sequenceId, request, prepared)
    }


    private fun submitOneShotRequestWithProvenance(
        session: CameraCaptureSession,
        builder: CaptureRequest.Builder,
        callback: CameraCaptureSession.CaptureCallback?,
        handler: Handler?,
        reason: String,
        pipelineGenerationAtSubmission: Int = pipelineGeneration
    ): SubmittedCameraRequest = synchronized(requestSubmissionLock) {
        enforceOpticalStabilizationBeforeSubmission(builder, reason)
        val prepared = prepareRequestProvenance(
            builder = builder,
            pipelineGenerationAtSubmission = pipelineGenerationAtSubmission,
            submissionType = CameraRequestSubmissionType.ONE_SHOT,
            reason = reason
        )
        builder.setTag(prepared.tag)
        val request = builder.build()
        val sequenceId = session.capture(request, callback, handler)
        com.bncam.core.debug.AfGroundTruthTrace.recordSubmission(
            builder = builder,
            request = request,
            requestSequenceNumber = sequenceId,
            submissionType = "ONE_SHOT",
            reason = reason
        )
        controlRequestEpochTracker.commit(prepared)
        Log.i(
            "CameraRequestProvenance",
            "event=REQUEST_SUBMITTED type=ONE_SHOT reason=$reason " +
                    "pipelineGeneration=${prepared.tag.pipelineGeneration} " +
                    "controlRequestEpoch=${prepared.tag.controlRequestEpoch} " +
                    "epochAdvanced=${prepared.advancesEpoch} sequenceId=$sequenceId " +
                    "requestSnapshot={${prepared.tag.snapshot.diagnosticSummary()}}"
        )
        SubmittedCameraRequest(sequenceId, request, prepared)
    }

    private fun submitBurstWithSharedProvenance(
        session: CameraCaptureSession,
        builders: List<CaptureRequest.Builder>,
        callback: CameraCaptureSession.CaptureCallback?,
        handler: Handler?,
        pipelineGenerationAtSubmission: Int = pipelineGeneration
    ): SubmittedCameraBurst = synchronized(requestSubmissionLock) {
        val reason = "HDR_ENHANCED_MAIN_BURST"
        require(builders.isNotEmpty()) { "Camera2 burst requires at least one request." }
        builders.forEach { builder -> enforceOpticalStabilizationBeforeSubmission(builder, reason) }
        val firstState = snapshotControlRequestState(builders.first())
        check(builders.all { snapshotControlRequestState(it) == firstState }) {
            "Shared-provenance burst requires identical Camera2 control state for every main frame."
        }
        val submittedAtNs = android.os.SystemClock.elapsedRealtimeNanos()
        val prepared = controlRequestEpochTracker.prepare(
            pipelineGeneration = pipelineGenerationAtSubmission,
            state = firstState,
            submissionType = CameraRequestSubmissionType.BURST,
            submissionReason = reason,
            meteringPolicySummary = lastMeteringPlanSummary,
            exposurePolicySummary = lastExposurePlanSummary,
            submittedElapsedRealtimeNs = submittedAtNs,
            focusOwner = _focusOwnership.value.owner.name
        )
        val requests = builders.map { builder ->
            builder.setTag(prepared.tag)
            builder.build()
        }
        val sequenceId = session.captureBurst(requests, callback, handler)
        requests.forEachIndexed { index, request ->
            com.bncam.core.debug.AfGroundTruthTrace.recordSubmission(
                builder = builders[index],
                request = request,
                requestSequenceNumber = sequenceId,
                submissionType = "BURST",
                reason = reason
            )
        }
        controlRequestEpochTracker.commit(prepared)
        Log.i(
            "CameraRequestProvenance",
            "event=REQUEST_SUBMITTED type=BURST reason=$reason count=${requests.size} " +
                "pipelineGeneration=${prepared.tag.pipelineGeneration} " +
                "controlRequestEpoch=${prepared.tag.controlRequestEpoch} " +
                "epochAdvanced=${prepared.advancesEpoch} sequenceId=$sequenceId " +
                "submittedElapsedRealtimeNs=$submittedAtNs requestSnapshot={${prepared.tag.snapshot.diagnosticSummary()}}"
        )
        SubmittedCameraBurst(sequenceId, requests, prepared, submittedAtNs)
    }

    private fun currentSubmittedControlRequestEpoch(): Long =
        controlRequestEpochTracker.currentSubmittedEpoch()

    private fun calculateExposureStatistics(image: android.media.Image) {
        if (calculateExposureStatisticsVulkan(image)) return
        calculateExposureStatisticsCpuFailsafe(image)
    }

    /**
     * Production-primary YUV analysis path. CPU work is intentionally limited to gathering a
     * compact regular Y/U/V sample grid from Image planes. YUV->RGB, luminance, histograms,
     * clipping and highlight localization execute in the process-scoped Vulkan runtime.
     */
    private fun calculateExposureStatisticsVulkan(image: android.media.Image): Boolean {
        return try {
            val planes = image.planes
            if (planes.size < 3) return false
            val width = image.width
            val height = image.height
            if (width <= 0 || height <= 0) return false
            val yPlane = planes[0]
            val uPlane = planes[1]
            val vPlane = planes[2]
            val yBuffer = yPlane.buffer
            val uBuffer = uPlane.buffer
            val vBuffer = vPlane.buffer
            val yBase = yBuffer.position()
            val uBase = uBuffer.position()
            val vBase = vBuffer.position()
            val rawStep = max(2, min(width, height) / 128)
            val sampleStep = if ((rawStep and 1) == 0) rawStep else rawStep + 1
            val sampleWidth = (width + sampleStep - 1) / sampleStep
            val sampleHeight = (height + sampleStep - 1) / sampleStep
            val sampleCount = sampleWidth * sampleHeight
            if (sampleCount <= 0 || sampleCount > 65_536) return false

            synchronized(yuvExposureSampleLock) {
                val requiredBytes = sampleCount * Int.SIZE_BYTES
                val packed = yuvExposureSampleBuffer
                    ?.takeIf { it.capacity() >= requiredBytes }
                    ?.also { it.order(ByteOrder.nativeOrder()) }
                    ?: ByteBuffer.allocateDirect(requiredBytes)
                        .order(ByteOrder.nativeOrder())
                        .also { yuvExposureSampleBuffer = it }
                packed.clear()

                var written = 0
                for (sampleY in 0 until sampleHeight) {
                    val y = min(sampleY * sampleStep, height - 1)
                    val yRow = yBase + y * yPlane.rowStride
                    val uvY = y shr 1
                    val uRow = uBase + uvY * uPlane.rowStride
                    val vRow = vBase + uvY * vPlane.rowStride
                    for (sampleX in 0 until sampleWidth) {
                        val x = min(sampleX * sampleStep, width - 1)
                        val yIndex = yRow + x * yPlane.pixelStride.coerceAtLeast(1)
                        val uvX = x shr 1
                        val uIndex = uRow + uvX * uPlane.pixelStride.coerceAtLeast(1)
                        val vIndex = vRow + uvX * vPlane.pixelStride.coerceAtLeast(1)
                        if (yIndex !in yBase until yBuffer.limit() ||
                            uIndex !in uBase until uBuffer.limit() ||
                            vIndex !in vBase until vBuffer.limit()
                        ) {
                            // Preserve the rectangular GPU grid. Invalid edge samples are neutral
                            // black rather than changing sampleCount/dimensions mid-dispatch.
                            packed.putInt((128 shl 16) or (128 shl 8) or 16)
                        } else {
                            val y8 = yBuffer.get(yIndex).toInt() and 0xFF
                            val u8 = uBuffer.get(uIndex).toInt() and 0xFF
                            val v8 = vBuffer.get(vIndex).toInt() and 0xFF
                            packed.putInt(y8 or (u8 shl 8) or (v8 shl 16))
                        }
                        written++
                    }
                }
                if (written != sampleCount) return false
                packed.position(0)
                packed.limit(requiredBytes)
                val stats = ImageUtils.analyzeYuvExposureStatistics(packed, sampleWidth, sampleHeight)
                    ?: return false
                if (stats.size < 265) return false
                val totalCount = stats[258].coerceAtLeast(0)
                if (totalCount <= 0) return false
                val safeCount = totalCount.toFloat()
                val highlightWeight = stats[264].coerceAtLeast(0)
                val point = if (highlightWeight > 0) {
                    NormalizedPoint(
                        x = (stats[262].toDouble() / highlightWeight / max(1, sampleWidth - 1)).toFloat(),
                        y = (stats[263].toDouble() / highlightWeight / max(1, sampleHeight - 1)).toFloat()
                    ).bounded()
                } else null
                publishLiveExposureStatistics(
                    ExposureStatistics(
                        source = "YUV_VULKAN_SAMPLED",
                        lumaHistogram64 = stats.copyOfRange(0, 64),
                        redHistogram64 = stats.copyOfRange(64, 128),
                        greenHistogram64 = stats.copyOfRange(128, 192),
                        blueHistogram64 = stats.copyOfRange(192, 256),
                        sampleCount = totalCount,
                        shadowFraction = stats[256].coerceAtLeast(0) / safeCount,
                        highlightFraction = stats[257].coerceAtLeast(0) / safeCount,
                        redClipFraction = stats[259].coerceAtLeast(0) / safeCount,
                        greenClipFraction = stats[260].coerceAtLeast(0) / safeCount,
                        blueClipFraction = stats[261].coerceAtLeast(0) / safeCount,
                        rawNearClipFraction = null,
                        highlightPoint = point
                    )
                )
                true
            }
        } catch (e: Exception) {
            Log.w(tag, "Vulkan YUV exposure statistics failed; using CPU fail-safe: ${e.message}")
            false
        }
    }

    private fun calculateExposureStatisticsCpuFailsafe(image: android.media.Image) {
        try {
            val planes = image.planes
            if (planes.size < 3) return
            val yPlane = planes[0]
            val uPlane = planes[1]
            val vPlane = planes[2]
            val yBuffer = yPlane.buffer
            val uBuffer = uPlane.buffer
            val vBuffer = vPlane.buffer
            val yBase = yBuffer.position()
            val uBase = uBuffer.position()
            val vBase = vBuffer.position()
            val width = image.width
            val height = image.height
            val luma64 = IntArray(64)
            val red64 = IntArray(64)
            val green64 = IntArray(64)
            val blue64 = IntArray(64)
            var shadowCount = 0
            var highlightCount = 0
            var redClipCount = 0
            var greenClipCount = 0
            var blueClipCount = 0
            var totalCount = 0
            var highlightXSum = 0.0
            var highlightYSum = 0.0
            var highlightWeightSum = 0.0

            val rawStep = max(2, min(width, height) / 128)
            val sampleStep = if ((rawStep and 1) == 0) rawStep else rawStep + 1
            for (y in 0 until height step sampleStep) {
                val yRow = yBase + y * yPlane.rowStride
                val uvY = y shr 1
                val uRow = uBase + uvY * uPlane.rowStride
                val vRow = vBase + uvY * vPlane.rowStride
                for (x in 0 until width step sampleStep) {
                    val yIndex = yRow + x * yPlane.pixelStride.coerceAtLeast(1)
                    val uvX = x shr 1
                    val uIndex = uRow + uvX * uPlane.pixelStride.coerceAtLeast(1)
                    val vIndex = vRow + uvX * vPlane.pixelStride.coerceAtLeast(1)
                    if (yIndex !in yBase until yBuffer.limit() ||
                        uIndex !in uBase until uBuffer.limit() || vIndex !in vBase until vBuffer.limit()
                    ) continue

                    val y8 = yBuffer.get(yIndex).toInt() and 0xFF
                    val u = (uBuffer.get(uIndex).toInt() and 0xFF) - 128
                    val v = (vBuffer.get(vIndex).toInt() and 0xFF) - 128
                    val c = max(y8 - 16, 0)
                    val r = ((298 * c + 409 * v + 128) shr 8).coerceIn(0, 255)
                    val g = ((298 * c - 100 * u - 208 * v + 128) shr 8).coerceIn(0, 255)
                    val b = ((298 * c + 516 * u + 128) shr 8).coerceIn(0, 255)
                    val lum = (0.2126f * r + 0.7152f * g + 0.0722f * b).toInt().coerceIn(0, 255)

                    luma64[(lum * 64 / 256).coerceIn(0, 63)]++
                    red64[(r * 64 / 256).coerceIn(0, 63)]++
                    green64[(g * 64 / 256).coerceIn(0, 63)]++
                    blue64[(b * 64 / 256).coerceIn(0, 63)]++
                    if (r >= 251) redClipCount++
                    if (g >= 251) greenClipCount++
                    if (b >= 251) blueClipCount++
                    if (lum < 30) shadowCount++
                    if (lum > 220) {
                        highlightCount++
                        val weight = (lum - 219).toDouble()
                        highlightXSum += x * weight
                        highlightYSum += y * weight
                        highlightWeightSum += weight
                    }
                    totalCount++
                }
            }
            if (totalCount <= 0) return
            val safeCount = totalCount.toFloat()
            val point = if (highlightWeightSum > 0.0) {
                NormalizedPoint(
                    (highlightXSum / highlightWeightSum / max(1, width - 1)).toFloat(),
                    (highlightYSum / highlightWeightSum / max(1, height - 1)).toFloat()
                ).bounded()
            } else null
            publishLiveExposureStatistics(
                ExposureStatistics(
                    source = "YUV_CPU_FAILSAFE",
                    lumaHistogram64 = luma64,
                    redHistogram64 = red64,
                    greenHistogram64 = green64,
                    blueHistogram64 = blue64,
                    sampleCount = totalCount,
                    shadowFraction = shadowCount / safeCount,
                    highlightFraction = highlightCount / safeCount,
                    redClipFraction = redClipCount / safeCount,
                    greenClipFraction = greenClipCount / safeCount,
                    blueClipFraction = blueClipCount / safeCount,
                    rawNearClipFraction = null,
                    highlightPoint = point
                )
            )
        } catch (e: Exception) {
            Log.w(tag, "YUV exposure statistics skipped: ${e.message}")
        }
    }

    private fun scanQrCode(image: android.media.Image, deviceRotation: Int) {
        try {
            scanQrNv21(
                bytes = ImageUtils.yuv420ToNv21(image),
                width = image.width,
                height = image.height,
                deviceRotation = deviceRotation
            )
        } catch (_: Exception) {
            _detectedQrCode.value = null
        }
    }

    private fun scanQrNv21(bytes: ByteArray, width: Int, height: Int, deviceRotation: Int) {
        if (isQrAnalysisBusy) return
        isQrAnalysisBusy = true
        try {
            val inputImage = com.google.mlkit.vision.common.InputImage.fromByteArray(
                bytes, width, height, deviceRotation,
                com.google.mlkit.vision.common.InputImage.IMAGE_FORMAT_NV21
            )
            barcodeScanner.process(inputImage)
                .addOnSuccessListener { barcodes ->
                    _detectedQrCode.value = if (!managerShutdownRequested.get() && qrAnalysisEnabled) {
                        barcodes.firstOrNull {
                            it.valueType == com.google.mlkit.vision.barcode.common.Barcode.TYPE_URL
                        }?.url?.url
                    } else {
                        null
                    }
                }
                .addOnFailureListener {
                    _detectedQrCode.value = null
                }
                .addOnCompleteListener {
                    isQrAnalysisBusy = false
                }
        } catch (_: Exception) {
            isQrAnalysisBusy = false
            _detectedQrCode.value = null
        }
    }

    private fun trackObjectsInFrame(image: android.media.Image, deviceRotation: Int) {
        if (isCapturing || !_focusTrackingActive.value || isTrackingBusy) return
        try {
            trackObjectsNv21(
                bytes = ImageUtils.yuv420ToNv21(image),
                width = image.width,
                height = image.height,
                deviceRotation = deviceRotation
            )
        } catch (_: Exception) {
            isTrackingBusy = false
        }
    }

    private fun trackObjectsNv21(bytes: ByteArray, width: Int, height: Int, deviceRotation: Int) {
        if (isCapturing || !_focusTrackingActive.value || isTrackingBusy) return
        isTrackingBusy = true
        try {
            val inputImage = com.google.mlkit.vision.common.InputImage.fromByteArray(
                bytes, width, height, deviceRotation,
                com.google.mlkit.vision.common.InputImage.IMAGE_FORMAT_NV21
            )
            // ML Kit reports boxes in the rotation-corrected analysis space. Keep that space
            // identical to the uncorrected viewfinder-normalized coordinates used by AF mapping.
            val coordinateWidth = if (deviceRotation == 90 || deviceRotation == 270) {
                height.toFloat()
            } else {
                width.toFloat()
            }.coerceAtLeast(1f)
            val coordinateHeight = if (deviceRotation == 90 || deviceRotation == 270) {
                width.toFloat()
            } else {
                height.toFloat()
            }.coerceAtLeast(1f)

            objectTracker.process(inputImage).addOnSuccessListener { detectedObjects ->
                if (!_focusTrackingActive.value) {
                    isTrackingBusy = false
                    return@addOnSuccessListener
                }

                var trackedObject: com.google.mlkit.vision.objects.DetectedObject? = null
                var reacquiredThisFrame = false

                // A tap first selects an object actually containing the touch point. Only if no
                // box contains it do we accept a nearby object, and even then within a bounded
                // distance. This prevents a tap on one subject from snapping to a different one.
                val tapX = pendingTapX
                val tapY = pendingTapY
                if (tapX != null && tapY != null) {
                    val tapPixelX = tapX * coordinateWidth
                    val tapPixelY = tapY * coordinateHeight
                    trackedObject = selectTrackingCandidate(
                        detectedObjects = detectedObjects,
                        tapPixelX = tapPixelX,
                        tapPixelY = tapPixelY,
                        coordinateWidth = coordinateWidth,
                        coordinateHeight = coordinateHeight
                    )
                    if (trackedObject != null) {
                        activeTrackingId = trackedObject.trackingId
                        val box = trackedObject.boundingBox
                        trackingOffsetPctX = ((tapPixelX - box.left) / max(1f, box.width().toFloat()))
                            .coerceIn(0.12f, 0.88f)
                        trackingOffsetPctY = ((tapPixelY - box.top) / max(1f, box.height().toFloat()))
                            .coerceIn(0.12f, 0.88f)
                        lastTrackedBoxPx = Rect(box)
                        trackingLostFrames = 0
                        pendingTapX = null
                        pendingTapY = null
                        val firstAcquisition = _focusOwnership.value.owner == FocusOwner.TRACK_ACQUIRING
                        transitionFocusOwner(
                            owner = if (focusTrackingPinned) FocusOwner.TRACK_PINNED else FocusOwner.TRACK_TIMED,
                            reason = "track_acquired",
                            trackingPinned = focusTrackingPinned
                        )
                        if (firstAcquisition) {
                            releaseTouchAeToStandardMeteringForTracking()
                        }
                    } else {
                        // Keep acquisition alive for later frames instead of silently killing the
                        // requested tracker. The UI stays on the exact touch point meanwhile.
                        trackingLostFrames++
                        _focusTrackingState.value = FocusTrackingState(
                            phase = FocusTrackingPhase.ACQUIRING,
                            confidence = FocusTrackingPolicy.acquisitionConfidence(trackingLostFrames),
                            lostFrames = trackingLostFrames,
                            pinned = focusTrackingPinned,
                            reason = "waiting_for_tapped_subject"
                        )
                    }
                } else {
                    trackedObject = activeTrackingId?.let { id ->
                        detectedObjects.firstOrNull { it.trackingId == id }
                    }
                    if (trackedObject == null && lastTrackedBoxPx != null) {
                        trackedObject = reacquireTrackingCandidate(
                            detectedObjects = detectedObjects,
                            previousBox = lastTrackedBoxPx!!,
                            coordinateWidth = coordinateWidth,
                            coordinateHeight = coordinateHeight,
                            lostFrames = trackingLostFrames
                        )
                        if (trackedObject != null) {
                            activeTrackingId = trackedObject.trackingId
                            reacquiredThisFrame = true
                        }
                    }
                }

                if (trackedObject != null) {
                    val box = trackedObject.boundingBox
                    lastTrackedBoxPx = Rect(box)
                    trackingLostFrames = 0

                    val detectorX = ((box.left + trackingOffsetPctX * box.width()) / coordinateWidth)
                        .coerceIn(0f, 1f)
                    val displayX = if (focusTrackingMirrorX) 1f - detectorX else detectorX
                    val displayY = ((box.top + trackingOffsetPctY * box.height()) / coordinateHeight)
                        .coerceIn(0f, 1f)
                    val smoothed = smoothTrackingPoint(displayX, displayY)

                    val rawLeft = (box.left / coordinateWidth).coerceIn(0f, 1f)
                    val rawRight = (box.right / coordinateWidth).coerceIn(0f, 1f)
                    val displayLeft = if (focusTrackingMirrorX) 1f - rawRight else rawLeft
                    val displayRight = if (focusTrackingMirrorX) 1f - rawLeft else rawRight
                    _trackedObjectBounds.value = android.graphics.RectF(
                        displayLeft.coerceIn(0f, 1f),
                        (box.top / coordinateHeight).coerceIn(0f, 1f),
                        displayRight.coerceIn(0f, 1f),
                        (box.bottom / coordinateHeight).coerceIn(0f, 1f)
                    )
                    val boxWidthPct = (box.width() / coordinateWidth).coerceIn(0f, 1f)
                    val boxHeightPct = (box.height() / coordinateHeight).coerceIn(0f, 1f)
                    val adaptiveRegionPct = FocusTrackingPolicy.adaptiveAfRegionPct(
                        boxWidthPct,
                        boxHeightPct
                    )
                    _focusTrackingState.value = FocusTrackingState(
                        phase = FocusTrackingPhase.TRACKING,
                        confidence = if (reacquiredThisFrame) 0.72f else 0.96f,
                        lostFrames = 0,
                        pinned = focusTrackingPinned,
                        reason = if (reacquiredThisFrame) "reacquired" else "tracking_id"
                    )
                    updateDynamicHardwareFocus(smoothed.first, smoothed.second, adaptiveRegionPct)
                } else if (pendingTapX == null) {
                    // A STREAM_MODE tracking id can disappear for a few frames during occlusion or
                    // fast motion. Keep the last target visible and continuously attempt bounded
                    // reacquisition rather than snapping AF elsewhere.
                    trackingLostFrames++
                    val cappedLost = trackingLostFrames.coerceAtMost(FocusTrackingPolicy.LOST_FRAME_CAP)
                    _focusTrackingState.value = FocusTrackingState(
                        phase = FocusTrackingPolicy.phaseForLostFrames(cappedLost),
                        confidence = FocusTrackingPolicy.confidenceForLostFrames(cappedLost),
                        lostFrames = cappedLost,
                        pinned = focusTrackingPinned,
                        reason = if (cappedLost >= 14) "target_lost" else "reacquiring"
                    )
                    if (trackingLostFrames > FocusTrackingPolicy.LOST_FRAME_CAP) trackingLostFrames = FocusTrackingPolicy.LOST_FRAME_CAP
                }
                isTrackingBusy = false
            }.addOnFailureListener { error ->
                Log.w(tag, "Focus Track detector failure: ${error.message}")
                isTrackingBusy = false
            }
        } catch (e: Exception) {
            isTrackingBusy = false
            Log.w(tag, "Focus Track analysis failed: ${e.message}")
        }
    }

    private fun selectTrackingCandidate(
        detectedObjects: List<com.google.mlkit.vision.objects.DetectedObject>,
        tapPixelX: Float,
        tapPixelY: Float,
        coordinateWidth: Float,
        coordinateHeight: Float
    ): com.google.mlkit.vision.objects.DetectedObject? {
        val containing = detectedObjects.filter { objectResult ->
            val box = objectResult.boundingBox
            tapPixelX >= box.left && tapPixelX <= box.right &&
                tapPixelY >= box.top && tapPixelY <= box.bottom
        }
        if (containing.isNotEmpty()) {
            // The smallest containing box is normally the most specific tapped subject when a
            // detector emits nested person/face/object boxes.
            return containing.minByOrNull {
                it.boundingBox.width().toLong() * it.boundingBox.height().toLong()
            }
        }

        val diagonal = kotlin.math.hypot(coordinateWidth, coordinateHeight).coerceAtLeast(1f)
        return detectedObjects.map { objectResult ->
            val box = objectResult.boundingBox
            val dx = box.centerX() - tapPixelX
            val dy = box.centerY() - tapPixelY
            objectResult to (kotlin.math.hypot(dx, dy) / diagonal)
        }.filter { (_, normalizedDistance) -> normalizedDistance <= 0.20f }
            .minByOrNull { it.second }
            ?.first
    }

    private fun reacquireTrackingCandidate(
        detectedObjects: List<com.google.mlkit.vision.objects.DetectedObject>,
        previousBox: Rect,
        coordinateWidth: Float,
        coordinateHeight: Float,
        lostFrames: Int
    ): com.google.mlkit.vision.objects.DetectedObject? {
        if (detectedObjects.isEmpty()) return null
        val diagonal = kotlin.math.hypot(coordinateWidth, coordinateHeight).coerceAtLeast(1f)
        val previousArea = max(1L, previousBox.width().toLong() * previousBox.height().toLong()).toFloat()
        val previousAspect = previousBox.width().toFloat() / previousBox.height().coerceAtLeast(1).toFloat()
        val searchRadius = FocusTrackingPolicy.reacquisitionSearchRadius(lostFrames)
        return detectedObjects.mapNotNull { candidate ->
            val box = candidate.boundingBox
            val dx = box.centerX() - previousBox.centerX()
            val dy = box.centerY() - previousBox.centerY()
            val centerDistance = kotlin.math.hypot(dx.toFloat(), dy.toFloat()) / diagonal
            if (centerDistance > searchRadius) return@mapNotNull null
            val area = max(1L, box.width().toLong() * box.height().toLong()).toFloat()
            val sizePenalty = kotlin.math.abs(
                kotlin.math.ln((area / previousArea).coerceAtLeast(1e-4f).toDouble())
            ).toFloat()
            val aspect = box.width().toFloat() / box.height().coerceAtLeast(1).toFloat()
            val aspectPenalty = kotlin.math.abs(
                kotlin.math.ln((aspect / previousAspect.coerceAtLeast(1e-4f)).coerceAtLeast(1e-4f).toDouble())
            ).toFloat()
            val overlapReward = trackingIou(previousBox, box)
            val score = centerDistance * 2.35f + sizePenalty * 0.28f +
                aspectPenalty * 0.22f - overlapReward * 0.95f
            candidate to score
        }.minByOrNull { it.second }?.first
    }

    private fun trackingIou(a: Rect, b: Rect): Float {
        val left = max(a.left, b.left)
        val top = max(a.top, b.top)
        val right = min(a.right, b.right)
        val bottom = min(a.bottom, b.bottom)
        val intersection = max(0, right - left).toLong() * max(0, bottom - top).toLong()
        if (intersection <= 0L) return 0f
        val areaA = max(1L, a.width().toLong() * a.height().toLong())
        val areaB = max(1L, b.width().toLong() * b.height().toLong())
        return (intersection.toDouble() / (areaA + areaB - intersection).coerceAtLeast(1L).toDouble())
            .toFloat()
    }

    private fun smoothTrackingPoint(rawX: Float, rawY: Float): Pair<Float, Float> {
        val nowNs = android.os.SystemClock.elapsedRealtimeNanos()
        if (!trackingSmoothedX.isFinite() || !trackingSmoothedY.isFinite() || trackingLastObservationNs == 0L) {
            trackingSmoothedX = rawX
            trackingSmoothedY = rawY
            trackingVelocityX = 0f
            trackingVelocityY = 0f
            trackingLastObservationNs = nowNs
            return rawX to rawY
        }
        val dt = ((nowNs - trackingLastObservationNs) / 1_000_000_000f).coerceIn(0.008f, 0.20f)
        val dx = rawX - trackingSmoothedX
        val dy = rawY - trackingSmoothedY
        val motion = kotlin.math.hypot(dx, dy)
        // High alpha keeps moving subjects responsive; the small residual smoothing only removes
        // detector box jitter that would otherwise make the physical lens chatter.
        val alpha = (0.86f + motion * 1.35f).coerceIn(0.86f, 0.97f)
        val previousX = trackingSmoothedX
        val previousY = trackingSmoothedY
        trackingSmoothedX = (trackingSmoothedX + dx * alpha).coerceIn(0f, 1f)
        trackingSmoothedY = (trackingSmoothedY + dy * alpha).coerceIn(0f, 1f)
        val observedVx = (trackingSmoothedX - previousX) / dt
        val observedVy = (trackingSmoothedY - previousY) / dt
        trackingVelocityX = trackingVelocityX * 0.55f + observedVx * 0.45f
        trackingVelocityY = trackingVelocityY * 0.55f + observedVy * 0.45f
        trackingLastObservationNs = nowNs

        // A very small forward projection compensates analysis latency on a moving subject without
        // allowing the focus point to run away during abrupt direction changes.
        val predictionHorizon = min(0.028f, dt * 0.55f)
        return (
            trackingSmoothedX + trackingVelocityX * predictionHorizon
        ).coerceIn(0f, 1f) to (
            trackingSmoothedY + trackingVelocityY * predictionHorizon
        ).coerceIn(0f, 1f)
    }

    private fun clearPhysicalAfRegionState(builder: CaptureRequest.Builder? = null) {
        val physicalId = activePhysicalAfCameraId
        if (builder != null && physicalId != null) {
            runCatching {
                builder.setPhysicalCameraKey(CaptureRequest.CONTROL_AF_REGIONS, null, physicalId)
            }
        }
        activePhysicalAfRegion = null
        activePhysicalAfCameraId = null
        activePhysicalAfRequestBounds = null
    }

    private fun applyAuthoritativeFocusToStillBuilder(
        target: CaptureRequest.Builder,
        source: CaptureRequest.Builder,
        focusContext: FocusCaptureContext,
        reason: String
    ) {
        target.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE)
        val sourceMode = source.get(CaptureRequest.CONTROL_AF_MODE)
            ?: resolveConfiguredIdleAfMode(cameraDevice?.id)
        val sourceRegions = source.get(CaptureRequest.CONTROL_AF_REGIONS)
        val sourceDistance = source.get(CaptureRequest.LENS_FOCUS_DISTANCE)

        if (focusContext.owner == FocusOwner.MANUAL) {
            target.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
            target.set(CaptureRequest.CONTROL_AF_REGIONS, null)
            sourceDistance?.let { target.set(CaptureRequest.LENS_FOCUS_DISTANCE, it) }
        } else {
            target.set(CaptureRequest.CONTROL_AF_MODE, sourceMode)
            val physicalId = activePhysicalAfCameraId
            val physicalRegion = activePhysicalAfRegion
            if (physicalId != null && physicalRegion != null) {
                target.set(CaptureRequest.CONTROL_AF_REGIONS, null)
                runCatching {
                    target.setPhysicalCameraKey(
                        CaptureRequest.CONTROL_AF_REGIONS,
                        arrayOf(physicalRegion),
                        physicalId
                    )
                }.onFailure { failure ->
                    // Some HALs accept a physical AF key on repeating requests but reject it on a
                    // TEMPLATE_STILL_CAPTURE builder. Map the same physical target into the logical
                    // owner domain rather than silently losing the subject at shutter time.
                    val logicalId = cameraDevice?.id
                    val logicalChars = logicalId?.let { id ->
                        runCatching { cameraManager.getCameraCharacteristics(id) }.getOrNull()
                    }
                    val logicalBounds = target.get(CaptureRequest.SCALER_CROP_REGION)
                        ?: logicalChars?.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
                    val physicalBounds = activePhysicalAfRequestBounds
                        ?: runCatching {
                            cameraManager.getCameraCharacteristics(physicalId)
                                .get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
                        }.getOrNull()
                    val mappedFallback = if (logicalBounds != null && physicalBounds != null) {
                        CameraOwnerDomainMapper.mapMeteringRectangle(
                            physicalRegion = physicalRegion,
                            physicalBounds = physicalBounds,
                            logicalOwnerBounds = logicalBounds
                        )
                    } else null
                    target.set(
                        CaptureRequest.CONTROL_AF_REGIONS,
                        mappedFallback?.let { arrayOf(it) } ?: sourceRegions
                    )
                    Log.w(
                        tag,
                        "Physical still AF key rejected; logical mapped fallback applied " +
                            "reason=$reason physicalId=$physicalId mapped=${mappedFallback != null}",
                        failure
                    )
                }
            } else {
                target.set(CaptureRequest.CONTROL_AF_REGIONS, sourceRegions)
            }
        }
        Log.i(
            tag,
            "CAPTURE_FOCUS_PRESERVED reason=$reason owner=${focusContext.owner} " +
                "mode=${target.get(CaptureRequest.CONTROL_AF_MODE)} physicalAf=${activePhysicalAfCameraId ?: "none"}"
        )
    }

    private fun updateDynamicHardwareFocus(normX: Float, normY: Float, regionSizePct: Float) {
        if (!_focusTrackingActive.value) return
        val nowNs = android.os.SystemClock.elapsedRealtimeNanos()
        val safeRegionSizePct = regionSizePct.coerceIn(0.035f, 0.14f)
        val movedEnough = lastHwFocusX < 0f || lastHwFocusY < 0f ||
            abs(normX - lastHwFocusX) >= 0.0018f || abs(normY - lastHwFocusY) >= 0.0018f ||
            lastHwFocusRegionPct < 0f || abs(safeRegionSizePct - lastHwFocusRegionPct) >= 0.006f
        val cadenceReady = nowNs - trackingLastHardwareSubmitNs >= FocusTrackingPolicy.HARDWARE_UPDATE_INTERVAL_NS
        if (!movedEnough || !cadenceReady) return

        lastHwFocusX = normX
        lastHwFocusY = normY
        lastHwFocusRegionPct = safeRegionSizePct
        trackingLastHardwareSubmitNs = nowNs

        // Latest-wins serialization prevents stale ML callbacks from mutating Camera2 requests on
        // the ML/main thread or building a backlog while a faster target is moving.
        enqueuePreviewControl("focus_tracking", "FOCUS_TRACK_UPDATE") {
            if (!_focusTrackingActive.value) return@enqueuePreviewControl
            val session = captureSession ?: return@enqueuePreviewControl
            val request = currentCaptureRequest ?: return@enqueuePreviewControl
            val handler = backgroundHandler ?: return@enqueuePreviewControl
            try {
                val controlCameraId = cameraDevice?.id ?: return@enqueuePreviewControl
                val logicalChars = cameraManager.getCameraCharacteristics(controlCameraId)
                val identity = synchronized(pipelineLock) { activePipelineIdentity }
                val physicalId = identity
                    ?.takeIf { it.cameraRouteKind == CameraRouteKind.LOGICAL_PHYSICAL }
                    ?.physicalCameraId
                val physicalChars = physicalId?.let { id ->
                    runCatching { cameraManager.getCameraCharacteristics(id) }.getOrNull()
                }
                val geometryChars = physicalChars ?: logicalChars
                val maxAfRegions = geometryChars.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF) ?: 0
                if (maxAfRegions <= 0) return@enqueuePreviewControl
                val physicalResultCrop = if (
                    physicalId != null &&
                    lastCaptureResultGeneration == pipelineGeneration
                ) {
                    (lastCaptureResult as? TotalCaptureResult)
                        ?.let { physicalCaptureResultOrNull(it, physicalId) }
                        ?.get(CaptureResult.SCALER_CROP_REGION)
                } else null
                val mapped = AfCoordinateMapper.mapNormalizedPreviewPoint(
                    normPoint = NormalizedPoint(normX, normY),
                    characteristics = geometryChars,
                    currentCropRegion = if (physicalId != null) {
                        physicalResultCrop
                    } else {
                        request.get(CaptureRequest.SCALER_CROP_REGION)
                    },
                    previewStreamWidth = configuredPreviewStreamWidth,
                    previewStreamHeight = configuredPreviewStreamHeight,
                    distortionCorrectionMode = request.get(CaptureRequest.DISTORTION_CORRECTION_MODE),
                    regionSizePct = safeRegionSizePct
                ) ?: return@enqueuePreviewControl

                predictiveAfTracker.clear()
                if (!_focusOwnership.value.trackingActive) return@enqueuePreviewControl
                val physicalRequestKeys = if (physicalId != null) {
                    logicalChars.availablePhysicalCameraRequestKeys.orEmpty().toSet()
                } else emptySet()
                val physicalWritable = CaptureRequest.CONTROL_AF_REGIONS in physicalRequestKeys
                if (physicalId != null && physicalWritable) {
                    request.set(CaptureRequest.CONTROL_AF_REGIONS, null)
                    request.setPhysicalCameraKey(
                        CaptureRequest.CONTROL_AF_REGIONS,
                        arrayOf(mapped.meteringRectangle),
                        physicalId
                    )
                    activePhysicalAfRegion = mapped.meteringRectangle
                    activePhysicalAfCameraId = physicalId
                    activePhysicalAfRequestBounds = Rect(mapped.coordinateBounds)
                } else if (physicalId != null && physicalChars != null) {
                    clearPhysicalAfRegionState(request)
                    val logicalBounds = request.get(CaptureRequest.SCALER_CROP_REGION)
                        ?: logicalChars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
                        ?: return@enqueuePreviewControl
                    val logicalRegion = CameraOwnerDomainMapper.mapMeteringRectangle(
                        physicalRegion = mapped.meteringRectangle,
                        physicalBounds = mapped.coordinateBounds,
                        logicalOwnerBounds = logicalBounds
                    ) ?: return@enqueuePreviewControl
                    request.set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(logicalRegion))
                } else {
                    clearPhysicalAfRegionState(request)
                    request.set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(mapped.meteringRectangle))
                }
                request.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                request.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE)
                traceAfWriter(request, "updateDynamicHardwareFocus", "FOCUS_TRACK_AF_REGION_WRITES")
                submitRepeatingRequestWithProvenance(
                    session = session,
                    builder = request,
                    callback = captureCallback,
                    handler = handler,
                    reason = "FOCUS_TRACK_AF_REGION"
                )
            } catch (e: Exception) {
                Log.e(tag, "Failed to update Focus Track AF", e)
            }
        }
    }

    // ========================================================
    // PIPELINE IDENTITY & RESET DEBUG
    // ========================================================
    private fun normalizeRequestedFrameSource(value: String): String {
        val upper = value.uppercase()
        return when {
            upper.contains("RAW_SENSOR") -> "RAW_SENSOR"
            upper.contains("RAW10") -> "RAW10"
            else -> "YUV"
        }
    }

    private fun formatName(format: Int): String = when (format) {
        ImageFormat.RAW_SENSOR -> "RAW_SENSOR"
        ImageFormat.RAW10 -> "RAW10"
        ImageFormat.YUV_420_888 -> "YUV_420_888"
        ImageFormat.JPEG -> "JPEG"
        else -> format.toString()
    }

    // The ring deliberately keeps Image objects open because their HardwareBuffers are invalid
    // after Image.close(). Keep two acquisition slots outside the warm-buffer capacity so the
    // authoritative acquireNextImage() listener can keep draining while the ring retires its
    // oldest owned Image.
    private fun imageReaderMaxImages(bufferCapacity: Int): Int = bufferCapacity + 2


    private fun looksLikeVendorSessionRebuildKey(keyName: String): Boolean {
        val key = keyName.lowercase()
        return key.contains("sessionparameters") ||
                key.contains("session.parameters") ||
                key.contains("session_parameters") ||
                key.contains("session") ||
                key.contains("dcg") ||
                key.contains("dualconversion") ||
                key.contains("dual_conversion") ||
                key.contains("dual.gain") ||
                key.contains("dualgain") ||
                key.contains("idcg") ||
                key.contains("sensormode") ||
                key.contains("sensor.mode") ||
                key.contains("sensorcurrentmode") ||
                key.contains("customizedsensormode") ||
                key.contains("isz") ||
                key.contains("insensorzoom")
    }

    private fun looksLikeVendorSessionOperationModeKey(keyName: String): Boolean {
        val key = keyName.lowercase()
        return key.contains("reprocessablesessionmodetag") ||
                key.contains("sessionoperationmode") ||
                key.contains("session.operation.mode") ||
                key.contains("session_operation_mode") ||
                key.contains("operationmode") ||
                key.contains("operation.mode") ||
                key.contains("operation_mode")
    }

    private fun parseFirstVendorInt(rawValue: String): Int? {
        return rawValue
            .split(",", ";", " ", "[", "]")
            .firstNotNullOfOrNull { token ->
                val clean = token.trim()
                when {
                    clean.isBlank() -> null
                    clean.startsWith("0x", ignoreCase = true) -> clean.removePrefix("0x").removePrefix("0X").toIntOrNull(16)
                    else -> clean.toIntOrNull()
                }
            }
    }

    private fun vendorOperationModeCandidateSessionTypes(): List<Int> {
        // 0 = normal Android SESSION_REGULAR. 32768+n = vendor operation mode n.
        // This keeps the app universal: it probes operation modes 0..50 instead of
        // baking in any Honor/Vivo-specific sensor-mode number.
        return listOf(SessionConfiguration.SESSION_REGULAR) + (0..50).map { 32768 + it }
    }

    private fun activeVendorFeatureSignature(activeTags: List<com.bncam.data.settings.VendorTagConfig>): String {
        val normalized = activeTags
            .filter { it.enabled }
            .filter { config ->
                config.target == VendorTagTarget.SESSION ||
                        config.requiresSessionRebuild ||
                        looksLikeVendorSessionRebuildKey(config.keyName)
            }
            .sortedWith(compareBy({ it.recipeId }, { it.keyName }, { it.value }, { it.type }, { it.target.name }))
            .joinToString("\n") { config ->
                listOf(
                    config.recipeId.ifBlank { "manual" },
                    config.keyName,
                    config.value,
                    config.type,
                    config.target.name
                ).joinToString("|")
            }

        return if (normalized.isBlank()) "none" else Integer.toHexString(normalized.hashCode())
    }

    private fun shouldProbeOperationMode(activeTags: List<com.bncam.data.settings.VendorTagConfig>): Boolean {
        return activeTags.any { config ->
            config.enabled &&
                    !looksLikeVendorSessionOperationModeKey(config.keyName) &&
                    (config.target == VendorTagTarget.SESSION || config.requiresSessionRebuild) &&
                    looksLikeVendorSessionRebuildKey(config.keyName)
        }
    }

    private fun resolveVendorSessionType(
        settings: SessionRequestSettingsSnapshot,
        cameraId: String,
        sessionBuilder: CaptureRequest.Builder
    ): Int {
        val activeTags = settings.activeVendorTags

        val requiredMode = activeTags
            .filter { it.enabled }
            .mapNotNull { config ->
                com.bncam.vendor.FeatureRegistry.getRequiredModeForTag(config.keyName)
            }
            .maxOrNull()

        if (requiredMode != null) {
            try {
                val constructor = CaptureRequest.Key::class.java.getDeclaredConstructor(String::class.java, Class::class.java)
                constructor.isAccessible = true
                val modeKey = constructor.newInstance(
                    "org.codeaurora.qcamera3.sensorMode",
                    Int::class.javaObjectType
                ) as CaptureRequest.Key<Int>
                sessionBuilder.set(modeKey, requiredMode)
                Log.i(tag, "DYNAMIC_MODE_INJECT: Sensor Mode $requiredMode geactiveerd.")
            } catch (e: Exception) {
                Log.e(tag, "Mode injection failed: ${e.message}")
            }
        }

        activeVendorOperationProbe = null

        val explicitOperationTag = activeTags.firstOrNull { config ->
            config.enabled &&
                config.target == VendorTagTarget.SESSION &&
                looksLikeVendorSessionOperationModeKey(config.keyName)
        }
        val explicitMode = explicitOperationTag?.let { parseFirstVendorInt(it.value) }
        if (explicitMode != null && explicitMode >= 32768) {
            Log.w(
                tag,
                "Vendor SESSION TYPE resolved from explicit operation-mode tag ${explicitOperationTag.keyName}: $explicitMode"
            )
            return explicitMode
        }

        val fallbackOperationTag = activeTags.firstOrNull { config ->
            config.enabled &&
                config.target == VendorTagTarget.SESSION &&
                config.keyName.contains("session", ignoreCase = true) &&
                (parseFirstVendorInt(config.value) ?: 0) >= 32768
        }
        val fallbackMode = fallbackOperationTag?.let { parseFirstVendorInt(it.value) }
        if (fallbackMode != null && fallbackMode >= 32768) {
            Log.w(tag, "Vendor SESSION TYPE fallback resolved from ${fallbackOperationTag.keyName}: $fallbackMode")
            return fallbackMode
        }

        if (!shouldProbeOperationMode(activeTags)) {
            return SessionConfiguration.SESSION_REGULAR
        }

        val featureSignature = settings.vendorFeatureSignature
        if (featureSignature == "none") {
            return SessionConfiguration.SESSION_REGULAR
        }

        settings.learnedVendorSessionType?.let { learned ->
            sessionTransitionScope.launch {
                SettingsRepository(context).setVendorOperationModeProbeActive(cameraId, false)
            }
            Log.w(
                tag,
                "Vendor SESSION TYPE learned mapping lens=$cameraId signature=$featureSignature sessionType=$learned"
            )
            return learned
        }

        if (!settings.vendorProbeActive) {
            Log.i(
                tag,
                "Vendor OP MODE probe deferred during normal startup lens=$cameraId signature=$featureSignature; " +
                    "using SESSION_REGULAR until explicit discovery is armed"
            )
            return SessionConfiguration.SESSION_REGULAR
        }

        val candidates = vendorOperationModeCandidateSessionTypes()
        val index = settings.vendorProbeIndex.coerceIn(0, candidates.lastIndex)
        val sessionType = candidates[index]
        val watchedTags = activeTags.filter { config ->
            config.enabled &&
                (config.target == VendorTagTarget.SESSION || config.requiresSessionRebuild) &&
                !looksLikeVendorSessionOperationModeKey(config.keyName)
        }

        activeVendorOperationProbe = VendorOperationModeProbeState(
            lensId = cameraId,
            featureSignature = featureSignature,
            candidateIndex = index,
            sessionType = sessionType,
            sessionTypeLabel = if (sessionType == SessionConfiguration.SESSION_REGULAR) {
                "SESSION_REGULAR"
            } else {
                "VENDOR_OP_${sessionType - 32768}"
            },
            watchedTags = watchedTags
        )

        Log.w(
            tag,
            "Vendor OP MODE AUTO PROBE lens=$cameraId signature=$featureSignature candidateIndex=$index " +
                "sessionType=$sessionType label=${activeVendorOperationProbe?.sessionTypeLabel} watchedTags=${watchedTags.size}"
        )
        return sessionType
    }

    private fun captureResultKeyClassForType(type: String): Class<*> {
        val normalized = type.lowercase().replace(" ", "").replace("_", "")
        return when {
            normalized.contains("byte[]") || normalized.contains("bytearray") -> ByteArray::class.java
            normalized.contains("short[]") || normalized.contains("shortarray") -> ShortArray::class.java
            normalized.contains("int[]") || normalized.contains("integer[]") || normalized.contains("intarray") -> IntArray::class.java
            normalized.contains("long[]") || normalized.contains("longarray") -> LongArray::class.java
            normalized.contains("float[]") || normalized.contains("floatarray") -> FloatArray::class.java
            normalized.contains("double[]") || normalized.contains("doublearray") -> DoubleArray::class.java
            normalized.contains("boolean[]") || normalized.contains("bool[]") || normalized.contains("booleanarray") -> BooleanArray::class.java
            normalized == "byte" -> Byte::class.javaObjectType
            normalized == "short" -> Short::class.javaObjectType
            normalized == "int" || normalized == "integer" || normalized == "int32" -> Int::class.javaObjectType
            normalized == "long" -> Long::class.javaObjectType
            normalized == "float" -> Float::class.javaObjectType
            normalized == "double" -> Double::class.javaObjectType
            normalized == "boolean" || normalized == "bool" -> Boolean::class.javaObjectType
            else -> IntArray::class.java
        }
    }

    private fun createCaptureResultKey(name: String, typeClass: Class<*>): CaptureResult.Key<*> {
        val constructor = runCatching {
            CaptureResult.Key::class.java.getConstructor(String::class.java, Class::class.java)
        }.getOrElse {
            CaptureResult.Key::class.java.getDeclaredConstructor(String::class.java, Class::class.java)
        }
        constructor.isAccessible = true
        return constructor.newInstance(name, typeClass) as CaptureResult.Key<*>
    }

    private fun vendorValuePreview(value: Any?): String {
        return when (value) {
            null -> "null"
            is IntArray -> value.joinToString(",")
            is ByteArray -> value.joinToString(",")
            is ShortArray -> value.joinToString(",")
            is LongArray -> value.joinToString(",")
            is FloatArray -> value.joinToString(",")
            is DoubleArray -> value.joinToString(",")
            is BooleanArray -> value.joinToString(",")
            else -> value.toString()
        }
    }

    private fun probeTagEchoMatches(result: TotalCaptureResult, config: com.bncam.data.settings.VendorTagConfig): Boolean {
        return try {
            val key = createCaptureResultKey(config.keyName, captureResultKeyClassForType(config.type))
            @Suppress("UNCHECKED_CAST")
            val value = result.get(key as CaptureResult.Key<Any>)
            val actual = vendorValuePreview(value).trim()
            val expected = config.value.trim().removePrefix("[").removeSuffix("]")
            actual.isNotBlank() && actual != "null" && actual == expected
        } catch (_: Exception) {
            false
        }
    }

    private fun handleVendorOperationModeProbe(result: TotalCaptureResult, previewSurface: Surface) {
        val probe = activeVendorOperationProbe ?: return
        if (probe.watchedTags.isEmpty()) return

        probe.framesObserved++
        val matched = probe.watchedTags.firstOrNull { config -> probeTagEchoMatches(result, config) }
        if (matched != null) {
            probe.matchedKey = matched.keyName
            activeVendorOperationProbe = null
            sessionTransitionScope.launch {
                val repo = SettingsRepository(context)
                repo.saveLearnedVendorSessionType(
                    lensId = probe.lensId,
                    featureSignature = probe.featureSignature,
                    sessionType = probe.sessionType,
                    evidence = "matchedKey=${matched.keyName};candidateIndex=${probe.candidateIndex};frames=${probe.framesObserved};label=${probe.sessionTypeLabel}"
                )
                repo.setVendorOperationModeProbeActive(probe.lensId, false)
            }
            writePipelineLifecycleDebug(
                event = "VENDOR_OPERATION_MODE_PROBE_VERIFIED",
                decision = null,
                extra = "lensId=${probe.lensId}\nfeatureSignature=${probe.featureSignature}\ncandidateIndex=${probe.candidateIndex}\nsessionType=${probe.sessionType}\nsessionTypeLabel=${probe.sessionTypeLabel}\nmatchedKey=${matched.keyName}\nframesObserved=${probe.framesObserved}"
            )
            Log.w(tag, "Vendor op-mode probe VERIFIED sessionType=${probe.sessionType} key=${matched.keyName}")
            return
        }

        if (probe.framesObserved < 10) return

        val now = System.currentTimeMillis()
        if (now - lastVendorProbeAdvanceMs < 1500L) return
        lastVendorProbeAdvanceMs = now

        val nextIndex = probe.candidateIndex + 1
        val candidates = vendorOperationModeCandidateSessionTypes()
        if (nextIndex > candidates.lastIndex) {
            activeVendorOperationProbe = null
            sessionTransitionScope.launch {
                val repo = SettingsRepository(context)
                repo.setVendorOperationModeProbeIndex(
                    lensId = probe.lensId,
                    featureSignature = probe.featureSignature,
                    index = candidates.lastIndex
                )
                repo.setVendorOperationModeProbeActive(probe.lensId, false)
            }
            writePipelineLifecycleDebug(
                event = "VENDOR_OPERATION_MODE_PROBE_EXHAUSTED",
                decision = null,
                extra = "lensId=${probe.lensId}\nfeatureSignature=${probe.featureSignature}\nlastCandidateIndex=${probe.candidateIndex}\nlastSessionType=${probe.sessionType}\nwatchedTags=${probe.watchedTags.joinToString { it.keyName }}"
            )
            Log.w(tag, "Vendor op-mode probe exhausted for lens=${probe.lensId} signature=${probe.featureSignature}")
            return
        }

        writePipelineLifecycleDebug(
            event = "VENDOR_OPERATION_MODE_PROBE_NEXT",
            decision = null,
            extra = "lensId=${probe.lensId}\nfeatureSignature=${probe.featureSignature}\npreviousIndex=${probe.candidateIndex}\npreviousSessionType=${probe.sessionType}\nnextIndex=$nextIndex\nnextSessionType=${candidates[nextIndex]}\nreason=no matching result echo after ${probe.framesObserved} frames"
        )

        // Persist the new probe index before the reset can build its next immutable settings
        // snapshot. The Camera2 result callback returns immediately; ordering is owned by IO scope.
        sessionTransitionScope.launch {
            SettingsRepository(context).setVendorOperationModeProbeIndex(
                lensId = probe.lensId,
                featureSignature = probe.featureSignature,
                index = nextIndex
            )
            softResetPipeline(
                previewSurface = previewSurface,
                newFormat = lastPreferredFormat,
                profileId = lastProfileId,
                forceSessionRebuild = true,
                reason = "VENDOR_OPERATION_MODE_PROBE_NEXT"
            )
        }
    }

    private data class VendorConfigFingerprint(
        val signature: String,
        val requiresSessionRebuild: Boolean,
        val enabledTagCount: Int,
        val sessionTagCount: Int
    )

    private data class RawPreviewBindingResolution(
        val signature: String,
        val formatCode: Int?
    )

    private data class SessionRequestSettingsSnapshot(
        val opticalStabilization: Boolean,
        val hotPixelMode: String,
        val noiseReductionHint: String,
        val edgeModeHint: String,
        val tonemapHint: String,
        val antiBanding: String,
        val flashMode: String,
        val faceDetection: Boolean,
        val facePriorityFocus: Boolean,
        val activeVendorTags: List<com.bncam.data.settings.VendorTagConfig>,
        val vendorRegistry: List<DynamicVendorTag>,
        val vendorFeatureSignature: String,
        val learnedVendorSessionType: Int?,
        val vendorProbeActive: Boolean,
        val vendorProbeIndex: Int
    )

    private suspend fun loadSessionRequestSettings(cameraId: String): SessionRequestSettingsSnapshot {
        val settingsRepo = SettingsRepository(context)
        val activeVendorTags = settingsRepo.getActiveVendorTagsForLens(cameraId)
        val featureSignature = activeVendorFeatureSignature(activeVendorTags)
        val learnedSessionType = if (featureSignature != "none") {
            settingsRepo.getLearnedVendorSessionType(cameraId, featureSignature)
        } else {
            null
        }
        val probeActive = featureSignature != "none" && settingsRepo.isVendorOperationModeProbeActive(cameraId)
        val probeIndex = if (featureSignature != "none") {
            settingsRepo.getVendorOperationModeProbeIndex(cameraId, featureSignature)
        } else {
            0
        }
        val vendorRegistry = withContext(Dispatchers.IO) {
            VendorScanner.scanSensor(
                context = context,
                cameraManager = cameraManager,
                lensId = cameraId,
                forceRefresh = false
            )
        }
        return SessionRequestSettingsSnapshot(
            opticalStabilization = settingsRepo.opticalStabilizationFlow.first(),
            hotPixelMode = settingsRepo.hotPixelModeFlow.first(),
            noiseReductionHint = settingsRepo.noiseReductionHintFlow.first(),
            edgeModeHint = settingsRepo.edgeModeHintFlow.first(),
            tonemapHint = settingsRepo.tonemapHintFlow.first(),
            antiBanding = settingsRepo.antiBandingFlow.first(),
            flashMode = settingsRepo.flashModeFlow.first(),
            faceDetection = settingsRepo.faceDetectionFlow.first(),
            facePriorityFocus = settingsRepo.facePriorityFocusFlow.first(),
            activeVendorTags = activeVendorTags,
            vendorRegistry = vendorRegistry,
            vendorFeatureSignature = featureSignature,
            learnedVendorSessionType = learnedSessionType,
            vendorProbeActive = probeActive,
            vendorProbeIndex = probeIndex
        )
    }

    private suspend fun buildVendorConfigFingerprint(cameraId: String): VendorConfigFingerprint {
        return try {
            val settingsRepo = SettingsRepository(context)
            val activeTags = settingsRepo.getActiveVendorTagsForLens(cameraId)

            val sessionTags = activeTags.filter { config ->
                config.target == VendorTagTarget.SESSION ||
                    config.requiresSessionRebuild ||
                    looksLikeVendorSessionRebuildKey(config.keyName)
            }
            val normalizedSession = sessionTags
                .sortedWith(
                    compareBy(
                        { it.keyName },
                        { it.target.name },
                        { it.recipeId },
                        { it.value },
                        { it.type }
                    )
                )
                .joinToString(separator = "\n") { config ->
                    listOf(
                        config.keyName,
                        config.target.name,
                        config.value,
                        config.type,
                        config.recipeId,
                        config.enabled.toString(),
                        config.requiresSessionRebuild.toString()
                    ).joinToString("|")
                }

            val sessionCount = sessionTags.size

            VendorConfigFingerprint(
                signature = if (normalizedSession.isBlank()) "none" else Integer.toHexString(normalizedSession.hashCode()),
                requiresSessionRebuild = sessionCount > 0,
                enabledTagCount = activeTags.size,
                sessionTagCount = sessionCount
            )
        } catch (e: Exception) {
            Log.w(tag, "Vendor config fingerprint failed for cameraId=$cameraId: ${e.message}")
            VendorConfigFingerprint(
                signature = "error:${e.javaClass.simpleName}",
                requiresSessionRebuild = true,
                enabledTagCount = -1,
                sessionTagCount = -1
            )
        }
    }

    private fun supportsRawCapability(chars: CameraCharacteristics): Boolean {
        val capabilities = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: return false
        return capabilities.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW)
    }

    private fun outputSizesAvailable(
        map: android.hardware.camera2.params.StreamConfigurationMap?,
        format: Int
    ): Boolean {
        return map?.getOutputSizes(format)?.isNotEmpty() == true
    }

    private fun configureNearZslTimestampObservability(cameraId: String) {
        val timestampSource = runCatching {
            cameraManager.getCameraCharacteristics(cameraId)
                .get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE)
        }.getOrNull()
            ?: CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_UNKNOWN
        ringBuffer.configureTimestampSource(cameraId, timestampSource)
    }

    private fun memoryBudgetInputs(context: android.content.Context): Pair<Long, Long> {
        return try {
            val actManager = context.getSystemService(android.content.Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            val memInfo = android.app.ActivityManager.MemoryInfo()
            actManager.getMemoryInfo(memInfo)
            val memoryClassBytes = actManager.memoryClass.toLong() * 1024L * 1024L
            memInfo.totalMem.coerceAtLeast(memoryClassBytes) to memoryClassBytes
        } catch (e: Exception) {
            val processLimit = Runtime.getRuntime().maxMemory().coerceAtLeast(1L)
            Log.w(tag, "ActivityManager memory budget unavailable; using reported process maxMemory only", e)
            processLimit to processLimit
        }
    }

    private fun getLensRole(cameraId: String): String {
        try {
            val candidates = buildAutoLensCandidates()
            val candidate = candidates.firstOrNull { it.id == cameraId }
            if (candidate != null) {
                return candidate.category.slotName
            }
        } catch (e: Exception) {
            Log.e(tag, "Failed to resolve lens role for $cameraId", e)
        }
        return "unknown"
    }

    private suspend fun resolveCustomRawPreviewBinding(
        cameraId: String,
        requestedSource: String
    ): RawPreviewBindingResolution {
        if (viewfinderStreamSetting != ViewfinderStream.SELECTED_BUFFER) {
            return RawPreviewBindingResolution("none", null)
        }
        val source = ViewfinderEffectiveSource.fromFrameSource(requestedSource)
        if (source == ViewfinderEffectiveSource.YUV) {
            return RawPreviewBindingResolution("none", null)
        }
        val encoded = runCatching {
            SettingsRepository(context).getRawPreviewFormatCodeFlow(cameraId, source.name).first()
        }.getOrDefault("AUTO")
        val code = parseCameraFormatCode(encoded)
            ?: return RawPreviewBindingResolution("none", null)
        return when (rawPreviewFormatCompatibility(source.name, code)) {
            RawPreviewFormatCompatibility.CANONICAL,
            RawPreviewFormatCompatibility.INCOMPATIBLE_STANDARD -> RawPreviewBindingResolution("none", null)
            RawPreviewFormatCompatibility.CUSTOM_VENDOR_UNVERIFIED ->
                RawPreviewBindingResolution("${source.name}:$code", code)
        }
    }

    /**
     * Resolves one user-selectable camera id to the CameraDevice that owns it and, when
     * applicable, the physical child that Camera2 outputs must target. Keep this as the single
     * source of truth for hard starts, pipeline identity and physical-lens handover decisions.
     */
    fun resolveCameraDeviceRoute(cameraId: String): CameraDeviceRoute {
        return try {
            val directIds = publicCameraIds

            // A selected ID advertised by CameraManager is always opened directly. This includes
            // public physical children: never replace the requested sensor with its logical owner.
            // Only hidden physical IDs continue to the direct-probe/physical-output qualification.
            if (CameraDeviceRoutePolicy.shouldOpenDirectly(cameraId, directIds)) {
                Log.i(
                    previewDiagnosticsTag,
                    "event=CAMERA_DEVICE_ROUTE_DIRECT requestedLens=$cameraId reason=directly_openable_full_fov"
                )
                return CameraDeviceRoute(
                    requestedLensId = cameraId,
                    logicalCameraId = cameraId,
                    physicalCameraId = null,
                    routeKind = CameraRouteKind.PUBLIC_DIRECT
                )
            }

            when (hiddenCameraRouteQualifications[cameraId]) {
                CameraRouteKind.PROBED_DIRECT -> CameraDeviceRoute(
                    requestedLensId = cameraId,
                    logicalCameraId = cameraId,
                    physicalCameraId = null,
                    routeKind = CameraRouteKind.PROBED_DIRECT
                )
                CameraRouteKind.LOGICAL_PHYSICAL -> {
                    val logicalParentId = findLogicalParentCameraId(cameraId, directIds)
                        ?: throw IllegalStateException(
                            "Qualified fallback camera $cameraId no longer has a public logical parent"
                        )
                    Log.i(
                        previewDiagnosticsTag,
                        "event=CAMERA_DEVICE_ROUTE_LOGICAL_PHYSICAL requestedLens=$cameraId " +
                            "logicalOwner=$logicalParentId reason=direct_open_failed"
                    )
                    CameraDeviceRoute(
                        requestedLensId = cameraId,
                        logicalCameraId = logicalParentId,
                        physicalCameraId = cameraId,
                        routeKind = CameraRouteKind.LOGICAL_PHYSICAL
                    )
                }
                else -> CameraDeviceRoute(
                    requestedLensId = cameraId,
                    logicalCameraId = cameraId,
                    physicalCameraId = null,
                    routeKind = CameraRouteKind.DIRECT_PROBE_PENDING
                )
            }
        } catch (e: Exception) {
            Log.w(tag, "Failed to resolve CameraDevice route for lens $cameraId", e)
            CameraDeviceRoute(
                requestedLensId = cameraId,
                logicalCameraId = cameraId,
                physicalCameraId = null,
                routeKind = CameraRouteKind.DIRECT_PROBE_PENDING
            )
        }
    }

    private fun findLogicalParentCameraId(
        physicalCameraId: String,
        publicCameraIds: Set<String> = this.publicCameraIds
    ): String? = publicCameraIds.firstOrNull { candidateLogicalId ->
        runCatching {
            val chars = getCachedCameraCharacteristics(candidateLogicalId)
            val caps = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
            val isLogicalMultiCamera =
                caps?.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA) == true
            isLogicalMultiCamera && chars.physicalCameraIds.contains(physicalCameraId)
        }.getOrDefault(false)
    }

    private enum class DirectCameraProbeOutcome {
        SUCCESS,
        FAILURE,
        INDETERMINATE
    }

    private data class DirectCameraProbeCallbackResult(
        val outcome: DirectCameraProbeOutcome,
        val detail: String
    )

    /**
     * Opens an unlisted camera ID without outputs solely to establish whether that ID can own a
     * CameraDevice. This runs only after the hard-start close barrier. Success is not returned
     * until CameraDevice.onClosed acknowledges the temporary probe device.
     */
    @SuppressLint("MissingPermission")
    private suspend fun probeDirectCameraOpen(cameraId: String): DirectCameraProbeCallbackResult {
        val handler = backgroundHandler
            ?: return DirectCameraProbeCallbackResult(
                DirectCameraProbeOutcome.INDETERMINATE,
                "background_handler_missing"
            )
        val callbackResult = CompletableDeferred<DirectCameraProbeCallbackResult>()
        val closeAcknowledged = CompletableDeferred<Unit>()
        var probeDevice: CameraDevice? = null

        val callback = object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                probeDevice = camera
                callbackResult.complete(
                    DirectCameraProbeCallbackResult(DirectCameraProbeOutcome.SUCCESS, "onOpened")
                )
                runCatching { camera.close() }.onFailure {
                    closeAcknowledged.complete(Unit)
                }
            }

            override fun onDisconnected(camera: CameraDevice) {
                probeDevice = camera
                callbackResult.complete(
                    DirectCameraProbeCallbackResult(DirectCameraProbeOutcome.FAILURE, "onDisconnected")
                )
                runCatching { camera.close() }.onFailure {
                    closeAcknowledged.complete(Unit)
                }
            }

            override fun onError(camera: CameraDevice, error: Int) {
                probeDevice = camera
                val transient = error == CameraDevice.StateCallback.ERROR_CAMERA_IN_USE ||
                    error == CameraDevice.StateCallback.ERROR_MAX_CAMERAS_IN_USE ||
                    error == CameraDevice.StateCallback.ERROR_CAMERA_DISABLED
                callbackResult.complete(
                    DirectCameraProbeCallbackResult(
                        if (transient) DirectCameraProbeOutcome.INDETERMINATE
                        else DirectCameraProbeOutcome.FAILURE,
                        "onError:$error"
                    )
                )
                runCatching { camera.close() }.onFailure {
                    closeAcknowledged.complete(Unit)
                }
            }

            override fun onClosed(camera: CameraDevice) {
                closeAcknowledged.complete(Unit)
            }
        }

        try {
            cameraManager.openCamera(cameraId, callback, handler)
        } catch (error: Throwable) {
            val transient = error is SecurityException ||
                (error is android.hardware.camera2.CameraAccessException &&
                    (error.reason == android.hardware.camera2.CameraAccessException.CAMERA_IN_USE ||
                        error.reason == android.hardware.camera2.CameraAccessException.MAX_CAMERAS_IN_USE ||
                        error.reason == android.hardware.camera2.CameraAccessException.CAMERA_DISABLED))
            return DirectCameraProbeCallbackResult(
                if (transient) DirectCameraProbeOutcome.INDETERMINATE
                else DirectCameraProbeOutcome.FAILURE,
                "openException:${error.javaClass.simpleName}:${error.message.orEmpty()}"
            )
        }

        val result = withTimeoutOrNull(DIRECT_CAMERA_PROBE_TIMEOUT_MS) {
            callbackResult.await()
        } ?: run {
            probeDevice?.let { runCatching { it.close() } }
            return DirectCameraProbeCallbackResult(
                DirectCameraProbeOutcome.INDETERMINATE,
                "callback_timeout"
            )
        }
        val closed = withTimeoutOrNull(CAMERA_HARD_CLOSE_RECOVERY_TIMEOUT_MS) {
            closeAcknowledged.await()
            true
        } ?: false
        if (!closed) {
            probeDevice?.let { runCatching { it.close() } }
            return DirectCameraProbeCallbackResult(
                DirectCameraProbeOutcome.INDETERMINATE,
                "close_ack_timeout_after_${result.detail}"
            )
        }
        return result
    }

    private suspend fun qualifyCameraDeviceRoute(cameraId: String): CameraDeviceRoute {
        val initialRoute = resolveCameraDeviceRoute(cameraId)
        if (initialRoute.isQualified) {
            com.bncam.core.debug.AfGroundTruthTrace.recordCameraRoute(
                stage = "QUALIFIED",
                selectedCameraId = cameraId,
                directOpenResult = when (initialRoute.routeKind) {
                    CameraRouteKind.PUBLIC_DIRECT -> "PUBLIC_LISTED"
                    CameraRouteKind.PROBED_DIRECT -> "SUCCESS_CACHED"
                    CameraRouteKind.LOGICAL_PHYSICAL -> "FAILURE_CACHED"
                    CameraRouteKind.DIRECT_PROBE_PENDING -> "PENDING"
                },
                chosenRoute = initialRoute.routeKind.name,
                logicalCameraId = initialRoute.logicalCameraId,
                physicalChildCameraId = initialRoute.physicalCameraId,
                openedCameraDeviceId = null
            )
            return initialRoute
        }

        // A soft path must never probe while another CameraDevice is active. Returning a direct
        // candidate makes the ownership comparison fail and sends the caller through hard start.
        if (cameraDevice != null || captureSession != null ||
            closingCameraDeviceCount.get() > 0 || openingCameraDeviceCount.get() > 0
        ) {
            return initialRoute
        }

        val probe = probeDirectCameraOpen(cameraId)
        val qualifiedKind = when (probe.outcome) {
            DirectCameraProbeOutcome.SUCCESS -> CameraRouteKind.PROBED_DIRECT
            DirectCameraProbeOutcome.FAILURE -> {
                requireNotNull(findLogicalParentCameraId(cameraId)) {
                    "Camera $cameraId failed direct open and has no logical/physical fallback"
                }
                CameraRouteKind.LOGICAL_PHYSICAL
            }
            DirectCameraProbeOutcome.INDETERMINATE -> throw IllegalStateException(
                "Direct camera qualification was indeterminate for $cameraId (${probe.detail})"
            )
        }
        hiddenCameraRouteQualifications[cameraId] = qualifiedKind
        val qualifiedRoute = resolveCameraDeviceRoute(cameraId)
        com.bncam.core.debug.AfGroundTruthTrace.recordCameraRoute(
            stage = "QUALIFIED",
            selectedCameraId = cameraId,
            directOpenResult = if (probe.outcome == DirectCameraProbeOutcome.SUCCESS) "SUCCESS" else "FAILURE",
            chosenRoute = qualifiedRoute.routeKind.name,
            logicalCameraId = qualifiedRoute.logicalCameraId,
            physicalChildCameraId = qualifiedRoute.physicalCameraId,
            openedCameraDeviceId = null,
            detail = probe.detail
        )
        return qualifiedRoute
    }

    fun canRetainLogicalCameraDeviceForLensSwitch(
        fromLensId: String,
        toLensId: String
    ): Boolean {
        if (fromLensId == toLensId) return true
        val fromRoute = resolveCameraDeviceRoute(fromLensId)
        val toRoute = resolveCameraDeviceRoute(toLensId)
        return fromRoute.sharesLogicalCameraDeviceWith(toRoute)
    }

    private suspend fun buildPipelineIdentity(
        cameraId: String,
        requestedProfileId: String,
        requestedFormat: String
    ): PipelineIdentity? {
        val requestedSource = normalizeRequestedFrameSource(requestedFormat)

        val deviceRoute = qualifyCameraDeviceRoute(cameraId)
        val logicalId = deviceRoute.logicalCameraId
        val physicalId = deviceRoute.physicalCameraId

        val role = getLensRole(cameraId)
        val chars = try {
            getCachedCameraCharacteristics(cameraId)
        } catch (e: Exception) {
            Log.e(tag, "Failed to get characteristics for $cameraId", e)
            return null
        }
        val hasRawCapability = supportsRawCapability(chars)

        val isProfileDisabled = DefaultIspProfile.isDisabledProfileId(requestedProfileId)
        val effectiveRequestedSource = if (isProfileDisabled) "YUV" else requestedSource

        val effectiveFormat = when (effectiveRequestedSource) {
            "RAW_SENSOR" -> ImageFormat.RAW_SENSOR
            "RAW10" -> ImageFormat.RAW10
            else -> ImageFormat.YUV_420_888
        }

        val standardMap = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val standardSizes = standardMap?.getOutputSizes(effectiveFormat)?.toList().orEmpty()
        var recommendedSizes = emptyList<android.util.Size>()

        try {
            val recMap = chars.getRecommendedStreamConfigurationMap(
                android.hardware.camera2.params.RecommendedStreamConfigurationMap.USECASE_PREVIEW
            )
            recommendedSizes = recMap?.getOutputSizes(effectiveFormat)?.toList().orEmpty()
        } catch (_: Exception) {
            // Standard SCALER_STREAM_CONFIGURATION_MAP remains the portable fallback.
        }

        val supportsYuv = outputSizesAvailable(standardMap, ImageFormat.YUV_420_888)
        val supportsRaw10 = hasRawCapability && outputSizesAvailable(standardMap, ImageFormat.RAW10)
        val supportsRawSensor = hasRawCapability && outputSizesAvailable(standardMap, ImageFormat.RAW_SENSOR)

        val unsupportedReason = when (effectiveRequestedSource) {
            "RAW_SENSOR" -> when {
                !hasRawCapability -> "Camera $cameraId does not advertise REQUEST_AVAILABLE_CAPABILITIES_RAW."
                !supportsRawSensor -> "Camera $cameraId does not advertise RAW_SENSOR output sizes."
                else -> null
            }
            "RAW10" -> when {
                !hasRawCapability -> "Camera $cameraId does not advertise REQUEST_AVAILABLE_CAPABILITIES_RAW."
                !supportsRaw10 -> "Camera $cameraId does not advertise RAW10 output sizes."
                else -> null
            }
            "YUV" -> if (!supportsYuv) "Camera $cameraId does not advertise YUV_420_888 output sizes." else null
            else -> "Unsupported requested frame source: $effectiveRequestedSource"
        }

        if (unsupportedReason != null) {
            Log.e(
                tag,
                "PIPELINE_UNSUPPORTED_FRAME_SOURCE_NO_FALLBACK requested=$requestedSource " +
                        "profile=$requestedProfileId camera=$cameraId reason=$unsupportedReason " +
                        "support={YUV=$supportsYuv, RAW10=$supportsRaw10, RAW_SENSOR=$supportsRawSensor, RAW_CAPABILITY=$hasRawCapability}"
            )
            return null
        }

        val sensorRect =
            chars.get(CameraCharacteristics.SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE)
                ?: chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
        val sensorWidth = sensorRect?.width() ?: 0
        val sensorHeight = sensorRect?.height() ?: 0
        fun android.util.Size.extent() = CameraStreamGeometryPolicy.Extent(width, height)
        fun selectByExtent(
            source: List<android.util.Size>,
            extents: Collection<CameraStreamGeometryPolicy.Extent>
        ): List<android.util.Size> {
            val keys = extents.mapTo(hashSetOf()) { it.width to it.height }
            return source.filter { (it.width to it.height) in keys }
        }

        val standardFullFov = CameraStreamGeometryPolicy.fullFovCandidates(
            candidates = standardSizes.map { it.extent() },
            sensorWidth = sensorWidth,
            sensorHeight = sensorHeight,
            aspectTolerance = if (
                effectiveFormat == ImageFormat.RAW10 || effectiveFormat == ImageFormat.RAW_SENSOR
            ) 0.025 else CameraStreamGeometryPolicy.DEFAULT_ASPECT_TOLERANCE
        )
        val recommendedFullFov = CameraStreamGeometryPolicy.fullFovCandidates(
            candidates = recommendedSizes.map { it.extent() },
            sensorWidth = sensorWidth,
            sensorHeight = sensorHeight
        )

        val availableSizes: List<android.util.Size>
        val geometrySource: String
        if (effectiveFormat == ImageFormat.YUV_420_888) {
            // A preview recommendation is an optimization hint, not permission to reduce FOV.
            // Keep the optimized pool only when its aspect matches the selected physical sensor.
            // If it exposes only cropped variants (commonly 16:9), return to the standard Camera2
            // YUV pool and preserve the sensor's full active-array aspect.
            val prioritized = CameraStreamGeometryPolicy.prioritizedFullFovPool(
                preferred = recommendedSizes.map { it.extent() },
                standard = standardSizes.map { it.extent() },
                sensorWidth = sensorWidth,
                sensorHeight = sensorHeight
            )
            val allSources = (recommendedSizes + standardSizes).distinctBy { it.width to it.height }
            availableSizes = selectByExtent(allSources, prioritized)
            geometrySource = when {
                recommendedFullFov.isNotEmpty() -> "RECOMMENDED_PREVIEW_FULL_FOV"
                standardFullFov.isNotEmpty() -> "STANDARD_YUV_FULL_FOV_FALLBACK"
                else -> "STANDARD_YUV_GEOMETRY_FALLBACK"
            }
        } else {
            // RAW remains a still-master stream: only the standard Camera2 list is authoritative.
            val selectedRawExtents = standardFullFov.ifEmpty { standardSizes.map { it.extent() } }
            availableSizes = selectByExtent(standardSizes, selectedRawExtents)
            geometrySource = if (standardFullFov.isNotEmpty()) {
                "STANDARD_RAW_FULL_FOV"
            } else {
                "STANDARD_RAW_GEOMETRY_FALLBACK"
            }
        }

        val bestSize = availableSizes.maxByOrNull { it.width.toLong() * it.height.toLong() } ?: run {
            Log.e(tag, "PIPELINE_UNSUPPORTED_FRAME_SOURCE_NO_FALLBACK requested=$requestedSource camera=$cameraId reason=no output size for ${formatName(effectiveFormat)}")
            return null
        }

        Log.i(
            tag,
            "PIPELINE_STREAM_GEOMETRY_SELECTED format=${formatName(effectiveFormat)} " +
                "selected=${bestSize.width}x${bestSize.height} candidates=${availableSizes.size} " +
                "source=$geometrySource sensor=${sensorWidth}x$sensorHeight " +
                "standardCandidates=${standardSizes.size} recommendedCandidates=${recommendedSizes.size}"
        )

        val (totalRamBytes, memoryClassBytes) = memoryBudgetInputs(context)
        val bufferBudget = CaptureBufferBudget.resolve(
            format = effectiveFormat,
            width = bestSize.width,
            height = bestSize.height,
            totalRamBytes = totalRamBytes,
            memoryClassBytes = memoryClassBytes
        )
        val maxImages = bufferBudget.capacity
        Log.i(
            tag,
            "CAPTURE_BUFFER_BUDGET format=${formatName(effectiveFormat)} size=${bestSize.width}x${bestSize.height} " +
                    "frameBytes=${bufferBudget.estimatedFrameBytes} targetBytes=${bufferBudget.targetBudgetBytes} " +
                    "capacity=$maxImages estimatedCapacityBytes=${bufferBudget.estimatedCapacityBytes} " +
                    "runtimeSafe=${bufferBudget.runtimeSafeCapacity} " +
                    "productTarget=${bufferBudget.routeMaximumFrames} " +
                    "resolutionReason=${bufferBudget.capacityResolution.resolutionReason} " +
                    "limitingReasons=${bufferBudget.capacityResolution.limitingReasons.joinToString(",").ifBlank { "none" }}"
        )

        val vendorFingerprint = buildVendorConfigFingerprint(cameraId)
        val rawPreviewBinding = resolveCustomRawPreviewBinding(cameraId, requestedSource)

        return PipelineIdentity(
            selectedLensId = cameraId,
            requestedProfileId = requestedProfileId,
            requestedFrameSource = requestedSource,
            effectiveFrameSource = formatName(effectiveFormat),
            bufferFormat = effectiveFormat,
            logicalCameraId = logicalId,
            physicalCameraId = physicalId,
            cameraRouteKind = deviceRoute.routeKind,
            lensRole = role,
            backendRoute = when (effectiveFormat) {
                ImageFormat.RAW_SENSOR -> "CAMERA2_RAW_SENSOR_WARM_IMAGEREADER_BUFFER"
                ImageFormat.RAW10 -> "CAMERA2_RAW10_WARM_IMAGEREADER_BUFFER"
                else -> "CAMERA2_YUV_WARM_IMAGEREADER_BUFFER"
            },
            width = bestSize.width,
            height = bestSize.height,
            maxImages = maxImages,
            rawPreviewBindingSignature = rawPreviewBinding.signature,
            rawPreviewFormatCode = rawPreviewBinding.formatCode,
            vendorConfigSignature = vendorFingerprint.signature,
            vendorSessionRebuildRequired = vendorFingerprint.requiresSessionRebuild
        )
    }

    private fun decidePipelineReset(
        previous: PipelineIdentity?,
        requested: PipelineIdentity
    ): PipelineResetDecision {
        if (previous == null) {
            return PipelineResetDecision(
                required = true,
                reasons = listOf("NO_ACTIVE_PIPELINE"),
                previous = null,
                requested = requested
            )
        }

        val reasons = mutableListOf<String>()
        // Profile identity and capture strategy are logical capture configuration, not physical
        // Camera2 stream identity. They must not tear down an otherwise compatible warm session.
        if (previous.vendorConfigSignature != requested.vendorConfigSignature) reasons += "SESSION_VENDOR_TAG_CONFIG_CHANGED"
        if (!previous.vendorSessionRebuildRequired && requested.vendorSessionRebuildRequired) reasons += "VENDOR_SESSION_TAGS_ENABLED"
        if (previous.effectiveFrameSource != requested.effectiveFrameSource) reasons += "FRAME_SOURCE_CHANGED"
        if (previous.bufferFormat != requested.bufferFormat) reasons += "BUFFER_FORMAT_CHANGED"
        if (previous.logicalCameraId != requested.logicalCameraId) reasons += "CAMERA_ID_CHANGED"
        if (previous.physicalCameraId != requested.physicalCameraId) reasons += "PHYSICAL_CAMERA_ID_CHANGED"
        if (previous.cameraRouteKind != requested.cameraRouteKind) reasons += "CAMERA_ROUTE_KIND_CHANGED"
        if (previous.lensRole != requested.lensRole) reasons += "LENS_ROLE_CHANGED"
        if (previous.backendRoute != requested.backendRoute) reasons += "BACKEND_ROUTE_CHANGED"
        if (previous.width != requested.width || previous.height != requested.height) reasons += "BUFFER_SIZE_CHANGED"
        if (previous.maxImages != requested.maxImages) reasons += "MAX_IMAGES_CHANGED"
        if (previous.rawPreviewBindingSignature != requested.rawPreviewBindingSignature) {
            reasons += "RAW_PREVIEW_BINDING_CHANGED"
        }

        return PipelineResetDecision(
            required = reasons.isNotEmpty(),
            reasons = reasons,
            previous = previous,
            requested = requested
        )
    }

    private fun PipelineIdentity.toDebugString(): String {
        return "selectedLens=$selectedLensId profile=$requestedProfileId requested=$requestedFrameSource effective=$effectiveFrameSource " +
                "format=${formatName(bufferFormat)} camera=$logicalCameraId physical=${physicalCameraId ?: "none"} " +
                "cameraRoute=$cameraRouteKind " +
                "lensRole=${lensRole ?: "none"} route=$backendRoute size=${width}x$height maxImages=$maxImages " +
                "rawPreviewBinding=$rawPreviewBindingSignature vendorSig=$vendorConfigSignature " +
                "vendorSessionRebuildRequired=$vendorSessionRebuildRequired"
    }

    private fun resolvePhysicalYuvFullFovDecision(): PhysicalYuvFullFovDecision {
        val identity = synchronized(pipelineLock) { activePipelineIdentity }
            ?: return PhysicalYuvFullFovPolicy.resolve(
                isYuv = false,
                physicalCameraId = null,
                lensRole = null,
                minimumLogicalZoomRatio = null,
                maximumLogicalZoomRatio = null
            )

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return PhysicalYuvFullFovPolicy.resolve(
                isYuv = identity.bufferFormat == ImageFormat.YUV_420_888,
                physicalCameraId = identity.physicalCameraId,
                lensRole = identity.lensRole,
                minimumLogicalZoomRatio = null,
                maximumLogicalZoomRatio = null
            )
        }

        val zoomRange = runCatching {
            cameraManager.getCameraCharacteristics(identity.logicalCameraId)
                .get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
        }.getOrNull()

        return PhysicalYuvFullFovPolicy.resolve(
            isYuv = identity.bufferFormat == ImageFormat.YUV_420_888,
            physicalCameraId = identity.physicalCameraId,
            lensRole = identity.lensRole,
            minimumLogicalZoomRatio = zoomRange?.lower,
            maximumLogicalZoomRatio = zoomRange?.upper
        )
    }

    /**
     * Keep a hidden physical ultra-wide YUV route at that sensor's native field of view.
     *
     * A logical CameraDevice at CONTROL_ZOOM_RATIO=1.0 represents the logical main-camera FOV,
     * not necessarily the native FOV of a forced physical ultra-wide output. Camera2 explicitly
     * requires CONTROL_ZOOM_RATIO for zoom-out; SCALER_CROP_REGION cannot express <1.0x. RAW is
     * deliberately excluded because Camera2's post-RAW zoom/crop coordinate transform does not
     * apply to RAW capture.
     */
    private fun applyPhysicalYuvFullFovZoom(
        builder: CaptureRequest.Builder,
        relativeDigitalZoom: Float = 1f,
        reason: String
    ): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false

        val identity = synchronized(pipelineLock) { activePipelineIdentity } ?: return false
        val decision = resolvePhysicalYuvFullFovDecision()
        if (!decision.enabled) return false

        val logicalCharacteristics = runCatching {
            cameraManager.getCameraCharacteristics(identity.logicalCameraId)
        }.getOrNull() ?: return false
        val activeArray = logicalCharacteristics
            .get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return false
        val effectiveZoomRatio = decision.resolveEffectiveZoom(relativeDigitalZoom)

        builder.set(CaptureRequest.CONTROL_ZOOM_RATIO, effectiveZoomRatio)
        // With zoomRatio != 1 Camera2 permits cropRegion only for aspect-ratio windowing. Keeping
        // the logical active array here prevents an accidental second digital crop.
        builder.set(CaptureRequest.SCALER_CROP_REGION, Rect(activeArray))
        traceAfWriter(builder, "applyPhysicalYuvFullFovZoom", reason)

        Log.i(
            previewDiagnosticsTag,
            "event=PHYSICAL_YUV_FULL_FOV_ZOOM_APPLIED reason=$reason " +
                "logicalCamera=${identity.logicalCameraId} physicalCamera=${identity.physicalCameraId} " +
                "lensRole=${identity.lensRole} nativeZoom=${decision.nativeZoomRatio} " +
                "relativeDigitalZoom=$relativeDigitalZoom effectiveZoom=$effectiveZoomRatio " +
                "maxZoom=${decision.maximumZoomRatio} activeArray=$activeArray"
        )
        return true
    }

    @SuppressLint("NewApi")
    private fun logPreviewDiagnostics(
        event: String,
        request: CaptureRequest? = null,
        result: TotalCaptureResult? = null,
        extra: String = ""
    ) {
        val identity = synchronized(pipelineLock) { activePipelineIdentity }
        val selectedLensId = activeLensId ?: identity?.physicalCameraId ?: identity?.logicalCameraId
        val logicalCameraId = identity?.logicalCameraId ?: cameraDevice?.id
        val physicalCameraId = identity?.physicalCameraId
        val selectedCharacteristicsId = physicalCameraId ?: selectedLensId ?: logicalCameraId

        val logicalCharacteristics = logicalCameraId?.let { id ->
            runCatching { cameraManager.getCameraCharacteristics(id) }.getOrNull()
        }
        val physicalCharacteristics = physicalCameraId?.let { id ->
            runCatching { cameraManager.getCameraCharacteristics(id) }.getOrNull()
        }
        val selectedCharacteristics = selectedCharacteristicsId?.let { id ->
            runCatching { cameraManager.getCameraCharacteristics(id) }.getOrNull()
        }

        val logicalActiveArray =
            logicalCharacteristics?.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
        val physicalActiveArray =
            physicalCharacteristics?.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
        val selectedActiveArray =
            selectedCharacteristics?.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
        val availablePhysicalFocals =
            selectedCharacteristics?.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)

        val physicalResult = if (
            result != null &&
            physicalCameraId != null
        ) {
            physicalCaptureResultOrNull(result, physicalCameraId)
        } else {
            null
        }
        val selectedPhysicalFocalLength = if (physicalCameraId != null) {
            physicalResult?.get(CaptureResult.LENS_FOCAL_LENGTH)
                ?: availablePhysicalFocals?.singleOrNull()
        } else {
            result?.get(CaptureResult.LENS_FOCAL_LENGTH)
                ?: availablePhysicalFocals?.singleOrNull()
        }

        val requestedCrop = request?.get(CaptureRequest.SCALER_CROP_REGION)
        val resultCrop = result?.get(CaptureResult.SCALER_CROP_REGION)
        val physicalResultCrop = physicalResult?.get(CaptureResult.SCALER_CROP_REGION)
        val requestedZoomRatio = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            request?.get(CaptureRequest.CONTROL_ZOOM_RATIO)
        } else {
            null
        }
        val resultZoomRatio = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            result?.get(CaptureResult.CONTROL_ZOOM_RATIO)
        } else {
            null
        }
        val physicalResultZoomRatio = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            physicalResult?.get(CaptureResult.CONTROL_ZOOM_RATIO)
        } else {
            null
        }
        val requestedOisMode =
            request?.get(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE)
        // CaptureRequest exposes only global request values. The per-physical getter lives on
        // CaptureRequest.Builder (API 28+), so a built request cannot be queried for a physical
        // override here. For diagnostics, report the exact active OIS plan that was used to build
        // the request instead of calling a non-existent CaptureRequest API.
        val requestedPhysicalOisMode: Int? = activeOisDecision
            ?.takeIf { decision ->
                physicalCameraId != null &&
                    decision.appliedMethod == OisDecision.OisMethod.PHYSICAL_OIS &&
                    decision.physicalCameraId == physicalCameraId &&
                    decision.applied
            }
            ?.appliedValue
        val resultLogicalOisMode =
            result?.get(CaptureResult.LENS_OPTICAL_STABILIZATION_MODE)
        val resultPhysicalOisMode =
            physicalResult?.get(CaptureResult.LENS_OPTICAL_STABILIZATION_MODE)
        val requestedVideoStabilizationMode =
            request?.get(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE)
        val resultLogicalVideoStabilizationMode =
            result?.get(CaptureResult.CONTROL_VIDEO_STABILIZATION_MODE)
        val resultPhysicalVideoStabilizationMode =
            physicalResult?.get(CaptureResult.CONTROL_VIDEO_STABILIZATION_MODE)

        fun Rect?.asDiagnosticString(): String = this?.flattenToString() ?: "unset"
        fun Float?.asDiagnosticString(): String = this?.toString() ?: "unset"

        val reader = imageReader
        val zoomGeometryCharacteristicsId = cameraDevice?.id
        Log.i(
            previewDiagnosticsTag,
            buildString {
                append("event=$event ")
                append("generation=$pipelineGeneration ")
                append("pipelineGeneration=$pipelineGeneration ")
                append(
                    "currentSubmittedControlRequestEpoch=${
                        currentSubmittedControlRequestEpoch()
                    } "
                )
                append("selectedLensId=${selectedLensId ?: "none"} ")
                append("logicalCameraId=${logicalCameraId ?: "none"} ")
                append("physicalCameraId=${physicalCameraId ?: "none"} ")
                append("cameraDeviceId=${cameraDevice?.id ?: "none"} ")
                append("selectedPhysicalFocalMm=${selectedPhysicalFocalLength.asDiagnosticString()} ")
                append(
                    "availableSelectedLensFocalsMm=${
                        availablePhysicalFocals?.joinToString(prefix = "[", postfix = "]")
                            ?: "unset"
                    } "
                )
                append("logicalActiveArray=${logicalActiveArray.asDiagnosticString()} ")
                append("physicalActiveArray=${physicalActiveArray.asDiagnosticString()} ")
                append("selectedLensActiveArray=${selectedActiveArray.asDiagnosticString()} ")
                append("zoomGeometryCharacteristicsId=${zoomGeometryCharacteristicsId ?: "none"} ")
                append(
                    "zoomGeometryUsesLogicalParent=${
                        physicalCameraId != null && zoomGeometryCharacteristicsId == logicalCameraId
                    } "
                )
                append("requestedScalerCrop=${requestedCrop.asDiagnosticString()} ")
                append("resultLogicalScalerCrop=${resultCrop.asDiagnosticString()} ")
                append("resultPhysicalScalerCrop=${physicalResultCrop.asDiagnosticString()} ")
                append("requestedControlZoomRatio=${requestedZoomRatio.asDiagnosticString()} ")
                append("resultLogicalControlZoomRatio=${resultZoomRatio.asDiagnosticString()} ")
                append("resultPhysicalControlZoomRatio=${physicalResultZoomRatio.asDiagnosticString()} ")
                append("requestedOisMode=${requestedOisMode ?: "unset"} ")
                append("requestedPhysicalOisMode=${requestedPhysicalOisMode ?: "unset"} ")
                append("resultLogicalOisMode=${resultLogicalOisMode ?: "unset"} ")
                append("resultPhysicalOisMode=${resultPhysicalOisMode ?: "unset"} ")
                append(
                    "requestedVideoStabilizationMode=${
                        requestedVideoStabilizationMode ?: "unset"
                    } "
                )
                append(
                    "resultLogicalVideoStabilizationMode=${
                        resultLogicalVideoStabilizationMode ?: "unset"
                    } "
                )
                append(
                    "resultPhysicalVideoStabilizationMode=${
                        resultPhysicalVideoStabilizationMode ?: "unset"
                    } "
                )
                append("previewStream=${configuredPreviewStreamWidth}x$configuredPreviewStreamHeight ")
                append(
                    "imageReader=${
                        if (reader != null) "${reader.width}x${reader.height}/${formatName(reader.imageFormat)}"
                        else "none"
                    } "
                )
                append(
                    "previewOutputConfigurationPhysicalCameraId=${
                        lastPreviewOutputPhysicalCameraId ?: "none"
                    } "
                )
                append(
                    "imageReaderOutputConfigurationPhysicalCameraId=${
                        lastImageReaderOutputPhysicalCameraId ?: "none"
                    }"
                )
                if (extra.isNotBlank()) append(" $extra")
            }
        )
    }

    private fun writePipelineLifecycleDebug(event: String, decision: PipelineResetDecision?, extra: String = "") {
        // Root-level debug files are intentionally disabled. Shot debug must live only
        // inside the per-shot folders created by ShotLogger.startNewShot(...).
        val resetRequired = decision?.required ?: false
        if (resetRequired || extra.isNotBlank()) {
            val reasons = decision?.reasons?.joinToString(",")?.ifBlank { "none" } ?: "none"
            Log.d(tag, "PipelineLifecycle event=$event resetRequired=$resetRequired reasons=$reasons extra=$extra")
        }
    }

    private fun expectedEffectiveFrameSourceName(requestedFormat: String): String = when (normalizeRequestedFrameSource(requestedFormat)) {
        "RAW_SENSOR" -> "RAW_SENSOR"
        "RAW10" -> "RAW10"
        else -> "YUV_420_888"
    }

    private fun pipelineReadinessReason(
        requestedProfileId: String,
        requestedFormat: String,
        cameraId: String,
        requirement: WarmBufferReadinessRequirement
    ): String {
        val expectedSource = expectedEffectiveFrameSourceName(requestedFormat)
        val identity = synchronized(pipelineLock) { activePipelineIdentity }

        if (identity == null) return "NO_ACTIVE_PIPELINE expected=$expectedSource profile=$requestedProfileId"
        if (isPipelineResetting) return "PIPELINE_RESET_IN_PROGRESS expected=$expectedSource active=${identity.effectiveFrameSource}"
        if (identity.logicalCameraId != cameraId && identity.physicalCameraId != cameraId) {
            return "CAMERA_ID_MISMATCH expectedCamera=$cameraId activeLogical=${identity.logicalCameraId} activePhysical=${identity.physicalCameraId ?: "none"}"
        }
        // Profile identity is capture/render configuration, not Camera2 stream readiness. A profile
        // switch that keeps the same lens + frame source must reuse the warm session and buffer.
        // The active profile is frozen independently into CaptureRecipe/RenderQualityConfig below.
        if (identity.effectiveFrameSource != expectedSource) return "FRAME_SOURCE_MISMATCH expected=$expectedSource active=${identity.effectiveFrameSource}"
        if (activeZslFormat != identity.bufferFormat) return "ACTIVE_FORMAT_MISMATCH expected=${formatName(identity.bufferFormat)} active=${formatName(activeZslFormat)}"
        if (captureSession == null) return "CAPTURE_SESSION_NOT_READY expected=$expectedSource"
        val reader = imageReader ?: return "IMAGE_READER_NOT_READY expected=$expectedSource"
        if (reader.imageFormat != identity.bufferFormat) {
            return "IMAGE_READER_FORMAT_MISMATCH expected=${formatName(identity.bufferFormat)} reader=${formatName(reader.imageFormat)}"
        }
        val receivedFormats = ringBuffer.completeFrameFormats()
        if (receivedFormats.any { it != identity.bufferFormat }) {
            return "BUFFER_FRAME_FORMAT_MISMATCH expected=${formatName(identity.bufferFormat)} actual=${receivedFormats.joinToString { formatName(it) }}"
        }
        if (sessionConfiguredGeneration != pipelineGeneration) {
            return "SESSION_GENERATION_NOT_CONFIGURED expectedGeneration=$pipelineGeneration configuredGeneration=$sessionConfiguredGeneration expected=$expectedSource"
        }

        val chars = cameraManager.getCameraCharacteristics(cameraId)
        val latestRes = lastCaptureResult
        val readinessState = CaptureReadinessGate.determineState(
            ringBuffer = ringBuffer,
            characteristics = chars,
            latestResult = latestRes,
            activeFormat = activeZslFormat,
            requirement = requirement
        )
        if (readinessState == ReadinessState.COLD_EMPTY) {
            return "READINESS_COLD_EMPTY expected=$expectedSource"
        }
        if (readinessState == ReadinessState.FILLING) {
            return "READINESS_FILLING expected=$expectedSource"
        }
        if (readinessState == ReadinessState.METADATA_UNSTABLE) {
            return "READINESS_METADATA_UNSTABLE expected=$expectedSource"
        }
        if (readinessState == ReadinessState.AF_SCANNING) {
            return "READINESS_AF_SCANNING expected=$expectedSource"
        }
        if (readinessState == ReadinessState.AE_AWB_CONVERGING) {
            return "READINESS_AE_AWB_CONVERGING expected=$expectedSource"
        }

        // A multi-frame set naturally spans several sensor frame intervals. Requiring every
        // member of that set to fit inside the stream-health window makes valid RAW bursts
        // impossible at slower frame rates (for example five RAW10 frames in 350 ms).
        // Readiness therefore has two independent requirements:
        //   1. the complete requested set is already leasable from the current generation; and
        //   2. at least the newest member proves that the stream is still alive.
        val leasableCompleteFrames = ringBuffer.completeFrameCount()
        if (leasableCompleteFrames < requirement.requiredCompleteFrames) {
            return "LEASABLE_WARM_BUFFER_NOT_READY expected=$expectedSource " +
                    "purpose=${requirement.purpose} leasableComplete=$leasableCompleteFrames " +
                    "required=${requirement.requiredCompleteFrames}"
        }

        val freshCompleteFrames = ringBuffer.freshMetadataCompleteFrameCount(
            referenceTimestampNs = android.os.SystemClock.elapsedRealtimeNanos(),
            freshnessWindowMs = requirement.streamHealthFreshnessWindowMs
        )

        if (freshCompleteFrames < 1) {
            return "WARM_BUFFER_STREAM_STALE expected=$expectedSource " +
                    "purpose=${requirement.purpose} freshComplete=$freshCompleteFrames " +
                    "streamHealthFreshnessWindowMs=${requirement.streamHealthFreshnessWindowMs}"
        }

        return "READY"
    }

    private suspend fun waitForPipelineReadyForCapture(
        requestedProfileId: String,
        requestedFormat: String,
        cameraId: String,
        requirement: WarmBufferReadinessRequirement,
        timeoutMs: Long = 4200L
    ): Boolean {
        val expectedSource = expectedEffectiveFrameSourceName(requestedFormat)
        val startMs = android.os.SystemClock.elapsedRealtime()
        var resetRequestedFromGate = false
        var lastReason = "not_checked"
        var ringEventSequence = ringBuffer.currentEventSequence()

        while (android.os.SystemClock.elapsedRealtime() - startMs < timeoutMs) {
            lastReason = pipelineReadinessReason(
                requestedProfileId = requestedProfileId,
                requestedFormat = requestedFormat,
                cameraId = cameraId,
                requirement = requirement
            )
            if (lastReason == "READY") {
                val activeIdentity = synchronized(pipelineLock) { activePipelineIdentity }
                val settingsRepo = SettingsRepository(context)
                val userRequestedOis = settingsRepo.opticalStabilizationFlow.first()

                val oisDecision = OisResolver.resolve(
                    cameraManager = cameraManager,
                    activeLensId = cameraId,
                    userRequestedOis = userRequestedOis,
                    lensRole = activeIdentity?.lensRole,
                    openedCameraId = cameraDevice?.id,
                    directRouteWasRuntimeProbed = activeIdentity?.cameraRouteKind ==
                        CameraRouteKind.PROBED_DIRECT
                )

                val oisExpectationActive = oisDecision.applied && oisDecision.appliedMethod != OisDecision.OisMethod.FAILED

                if (oisExpectationActive) {
                    val latestFrames = ringBuffer.latestCompleteFrameSnapshots(5)
                    val oisIsStabilized = latestFrames.any { frame ->
                        val metadata = frame.metadata
                        val logicalOis = metadata.get(CaptureResult.LENS_OPTICAL_STABILIZATION_MODE)
                        val logicalVideoStab = metadata.get(CaptureResult.CONTROL_VIDEO_STABILIZATION_MODE)
                        var physicalOis: Int? = null
                        if (oisDecision.physicalCameraId != null) {
                            val physicalResult = physicalCaptureResultOrNull(metadata, oisDecision.physicalCameraId)
                            physicalOis = physicalResult?.get(CaptureResult.LENS_OPTICAL_STABILIZATION_MODE)
                        }

                        when (oisDecision.appliedMethod) {
                            OisDecision.OisMethod.PHYSICAL_OIS,
                            OisDecision.OisMethod.LOGICAL_OIS -> {
                                logicalOis == CaptureResult.LENS_OPTICAL_STABILIZATION_MODE_ON ||
                                    physicalOis == CaptureResult.LENS_OPTICAL_STABILIZATION_MODE_ON
                            }
                            OisDecision.OisMethod.VENDOR -> {
                                // Vendor optical OIS has no portable CaptureResult contract.
                                // Request acceptance is authoritative; never turn missing standard
                                // metadata into a shutter lockout.
                                true
                            }
                            OisDecision.OisMethod.PREVIEW_STAB -> {
                                logicalVideoStab == CaptureResult.CONTROL_VIDEO_STABILIZATION_MODE_PREVIEW_STABILIZATION
                            }
                            OisDecision.OisMethod.VIDEO_STAB -> {
                                logicalVideoStab == CaptureResult.CONTROL_VIDEO_STABILIZATION_MODE_ON
                            }
                            else -> true
                        }
                    }

                    if (oisIsStabilized) {
                        Log.i(
                            tag,
                            "Stabilization resolved as ${oisDecision.appliedMethod.name} is initialized and active. Proceeding to capture."
                        )
                    } else {
                        // OIS result echo is quality evidence, not shutter admission authority.
                        // Some OEM logical/physical routes report the requested optical mode late
                        // or intermittently even while frames are valid. Blocking a multi-frame
                        // shutter here used to keep the attempt alive for up to the full gate
                        // timeout, so repeated taps looked as if the shutter was being ignored.
                        // Downstream frame scoring/alignment still owns motion rejection.
                        Log.i(
                            tag,
                            "Stabilization ${oisDecision.appliedMethod.name} not yet reported active; " +
                                "capture proceeds in degraded-ready mode."
                        )
                    }
                }

                pipelineCaptureGateReady = true
                pipelineCaptureGateLastReason = "READY"
                pipelineCaptureGateWaitMs = android.os.SystemClock.elapsedRealtime() - startMs
                pipelineCaptureGateResetTriggered = resetRequestedFromGate
                if (resetRequestedFromGate || pipelineCaptureGateWaitMs > 0L) {
                    Log.i(
                        tag,
                        "CAPTURE_PIPELINE_GATE_READY expected=$expectedSource waitMs=$pipelineCaptureGateWaitMs resetTriggered=$resetRequestedFromGate"
                    )
                }
                return true
            }

            val needsReset = lastReason.startsWith("FRAME_SOURCE_MISMATCH") ||
                    lastReason.startsWith("ACTIVE_FORMAT_MISMATCH") ||
                    lastReason.startsWith("IMAGE_READER_FORMAT_MISMATCH") ||
                    lastReason.startsWith("BUFFER_FRAME_FORMAT_MISMATCH") ||
                    lastReason.startsWith("CAMERA_ID_MISMATCH") ||
                    lastReason.startsWith("NO_ACTIVE_PIPELINE")

            if (!resetRequestedFromGate && needsReset && !isPipelineResetting) {
                val surface = lastPreviewSurface
                if (surface != null && cameraDevice != null) {
                    resetRequestedFromGate = true
                    Log.w(
                        tag,
                        "CAPTURE_PIPELINE_GATE_RESET reason=$lastReason requestedFormat=$requestedFormat profile=$requestedProfileId"
                    )
                    softResetPipeline(
                        previewSurface = surface,
                        newFormat = requestedFormat,
                        profileId = requestedProfileId,
                        forceSessionRebuild = true,
                        reason = "CAPTURE_GATE_${lastReason.substringBefore(' ')}"
                    )
                }
            }

            val bufferDrivenReason =
                lastReason.startsWith("READINESS_COLD_EMPTY") ||
                    lastReason.startsWith("READINESS_FILLING") ||
                    lastReason.startsWith("LEASABLE_WARM_BUFFER_NOT_READY") ||
                    lastReason.startsWith("WARM_BUFFER_STREAM_STALE") ||
                    lastReason.startsWith("CAPTURE_SESSION_NOT_READY") ||
                    lastReason.startsWith("SESSION_GENERATION_NOT_CONFIGURED") ||
                    lastReason.startsWith("IMAGE_READER_NOT_READY")
            if (bufferDrivenReason) {
                // Wake on the producer event itself instead of polling at a fixed 50 ms cadence.
                // Before enough timing samples exist use a conservative 24 ms pulse; afterwards
                // follow the real stream cadence while keeping a bounded re-check for non-ring
                // session state transitions.
                val medianFrameMs = ringBuffer.streamTimingEstimate().frameDurationMedianMs
                val eventPulseMs = ((medianFrameMs ?: 24.0) * 1.25)
                    .toLong().coerceIn(8L, 50L)
                val remainingMs = (timeoutMs -
                    (android.os.SystemClock.elapsedRealtime() - startMs)).coerceAtLeast(1L)
                val wake = withTimeoutOrNull(minOf(eventPulseMs, remainingMs)) {
                    ringBuffer.awaitEventAfter(ringEventSequence)
                }
                if (wake != null) ringEventSequence = wake.sequence
            } else {
                // Non-buffer state (for example a short reset transition) still needs a bounded
                // re-check, but 16 ms avoids adding a full 50 ms shutter-latency quantum.
                kotlinx.coroutines.delay(16L)
            }
        }

        pipelineCaptureGateReady = false
        pipelineCaptureGateLastReason = lastReason
        pipelineCaptureGateWaitMs = android.os.SystemClock.elapsedRealtime() - startMs
        pipelineCaptureGateResetTriggered = resetRequestedFromGate
        Log.e(
            tag,
            "CAPTURE_PIPELINE_NOT_READY expected=$expectedSource requestedFormat=$requestedFormat profile=$requestedProfileId " +
                    "waitMs=$pipelineCaptureGateWaitMs resetTriggered=$resetRequestedFromGate reason=$lastReason"
        )
        return false
    }

    private fun stopWarmBufferWatchdog() {
        warmBufferWatchdogJob?.cancel()
        warmBufferWatchdogJob = null
    }

    /**
     * Keeps the repeating warm stream self-healing before the user presses shutter.
     *
     * Tier 1 is a non-destructive repeating-request resubmit. Tier 2 rebuilds the session only
     * after that resubmit has itself had several measured frame intervals to recover. Rebuild is
     * one-shot until a healthy complete pair has been observed again, preventing reset loops.
     */
    private fun startWarmBufferWatchdog(sessionGeneration: Int) {
        stopWarmBufferWatchdog()
        warmBufferWatchdogJob = warmBufferWatchdogScope.launch {
            var emptyEpisodeStartMs = 0L
            var lastResubmitMs = 0L
            var resubmittedInEpisode = false
            var resubmitElapsedMs = 0L
            var lastRawHealthRecoveryMs = 0L
            var lastRawHealthStage: com.bncam.ui.screens.capture.RawPreviewHealthStage? = null

            while (sessionGeneration == pipelineGeneration) {
                val timing = ringBuffer.streamTimingEstimate()
                val frameMs = (timing.frameDurationMedianMs ?: 33.3).coerceIn(8.0, 100.0)
                val pulseMs = (frameMs * 2.0).toLong().coerceIn(24L, 160L)
                delay(pulseMs)

                if (sessionGeneration != pipelineGeneration ||
                    sessionConfiguredGeneration != sessionGeneration ||
                    cameraDevice == null || captureSession == null
                ) {
                    return@launch
                }

                val health = ringBuffer.healthDiagnostics()
                val streamHealthRequirement = WarmBufferReadinessPolicy.streamHealth(
                    format = activeZslFormat,
                    bufferCapacity = ringBuffer.currentCapacity()
                )
                val watchdogNowNs = android.os.SystemClock.elapsedRealtimeNanos()
                val ringPressure = rawRingPressureSummary()
                com.bncam.ui.screens.capture.RawPreviewHealthMonitor.updateRingPressure(
                    sessionGeneration,
                    ringPressure
                )
                rawPreviewRenderer.currentConfig?.takeIf {
                    it.pipelineGeneration == sessionGeneration && it.source != ViewfinderEffectiveSource.YUV
                }?.let {
                    com.bncam.ui.screens.capture.RawPreviewHealthMonitor.updateOutputSlotHealth(
                        sessionGeneration,
                        rawPreviewRenderer.healthSummary()
                    )
                }
                val rawHealth = com.bncam.ui.screens.capture.RawPreviewHealthMonitor.snapshot(watchdogNowNs)
                val targetRawHealthApplies = targetViewfinderSource != ViewfinderEffectiveSource.YUV &&
                    targetViewfinderGeneration == sessionGeneration &&
                    rawHealth.pipelineGeneration == sessionGeneration
                if (targetRawHealthApplies &&
                    rawHealth.stage != com.bncam.ui.screens.capture.RawPreviewHealthStage.HEALTHY &&
                    rawHealth.stage != com.bncam.ui.screens.capture.RawPreviewHealthStage.STARTING &&
                    rawHealth.stage != com.bncam.ui.screens.capture.RawPreviewHealthStage.INACTIVE
                ) {
                    val nowMsForHealth = android.os.SystemClock.elapsedRealtime()
                    val stageChanged = lastRawHealthStage != rawHealth.stage
                    val recoveryCooldownMs = (rawHealth.stallThresholdNs / 1_000_000L).coerceAtLeast(1_000L)
                    if (stageChanged || nowMsForHealth - lastRawHealthRecoveryMs >= recoveryCooldownMs) {
                        com.bncam.core.debug.DiagnosticsAggregator.record(
                            stream = com.bncam.core.debug.DiagnosticsAggregator.Stream.PERFORMANCE,
                            scope = "VIEWFINDER source=${rawHealth.source} generation=$sessionGeneration",
                            section = "RAW PREVIEW HEALTH STALL",
                            content = rawHealth.report(watchdogNowNs)
                        )
                        Log.w(
                            "RawPreviewHealth",
                            "event=STALL stage=${rawHealth.stage} generation=$sessionGeneration " +
                                "thresholdMs=${rawHealth.stallThresholdNs / 1_000_000.0} " +
                                "eglGeneration=${rawHealth.eglGeneration}"
                        )
                    }
                    lastRawHealthStage = rawHealth.stage
                    if (nowMsForHealth - lastRawHealthRecoveryMs >= recoveryCooldownMs) {
                        val recoveryAttempted = when (rawHealth.stage) {
                            com.bncam.ui.screens.capture.RawPreviewHealthStage.RENDERER_PUBLICATION ->
                                rawPreviewRenderer.forceCpuFallbackForHealth("renderer_publication_stall")
                            com.bncam.ui.screens.capture.RawPreviewHealthStage.RENDERER_OFFER -> {
                                primeRawViewfinderFromWarmBuffer(
                                    targetViewfinderSource,
                                    sessionGeneration,
                                    rawPreviewRouteRevision
                                )
                                true
                            }
                            com.bncam.ui.screens.capture.RawPreviewHealthStage.GL_ACCEPT,
                            com.bncam.ui.screens.capture.RawPreviewHealthStage.GL_DRAW ->
                                com.bncam.ui.screens.capture.FocusPeakingView.requestRawDisplayRecovery(
                                    sessionGeneration,
                                    rawHealth.stage.name.lowercase(),
                                    rebuildRawTexturePool = false
                                )
                            com.bncam.ui.screens.capture.RawPreviewHealthStage.EGL_PRESENTATION ->
                                com.bncam.ui.screens.capture.FocusPeakingView.requestRawDisplayRecovery(
                                    sessionGeneration,
                                    "egl_presentation_stall",
                                    rebuildRawTexturePool = true
                                )
                            com.bncam.ui.screens.capture.RawPreviewHealthStage.RAW_IMAGE_READER -> {
                                if (customRawPreviewBinding?.generation == sessionGeneration &&
                                    customRawPreviewPresentedReadyGeneration == sessionGeneration
                                ) {
                                    disableCustomRawPreviewForGeneration(
                                        sessionGeneration,
                                        "health_raw_image_reader_stall"
                                    )
                                    true
                                } else {
                                    false // Existing warm-buffer transport watchdog owns session recovery.
                                }
                            }
                            com.bncam.ui.screens.capture.RawPreviewHealthStage.CAMERA_CAPTURE_RESULT ->
                                false // Existing Camera2 transport watchdog owns repeating/session recovery.
                            com.bncam.ui.screens.capture.RawPreviewHealthStage.RGB_OUTPUT ->
                                false // Diagnostic only: never alter exposure/tone to hide a black-output fault.
                            else -> false
                        }
                        if (recoveryAttempted) {
                            lastRawHealthRecoveryMs = nowMsForHealth
                            com.bncam.ui.screens.capture.RawPreviewHealthMonitor.recoveryAttempt(
                                sessionGeneration,
                                "stage=${rawHealth.stage}",
                                watchdogNowNs
                            )
                        }
                    }
                } else if (targetRawHealthApplies) {
                    lastRawHealthStage = rawHealth.stage
                }
                val freshCompleteFrames = ringBuffer.freshMetadataCompleteFrameCount(
                    referenceTimestampNs = watchdogNowNs,
                    freshnessWindowMs = streamHealthRequirement.streamHealthFreshnessWindowMs
                )
                if (customRawPreviewBinding?.generation == sessionGeneration &&
                    customRawPreviewPresentedReadyGeneration == sessionGeneration &&
                    !isCustomRawPreviewFresh(sessionGeneration, watchdogNowNs)
                ) {
                    disableCustomRawPreviewForGeneration(
                        sessionGeneration,
                        "custom_stream_stalled_after_ready"
                    )
                }
                // Old complete pairs are not proof that the repeating producer is still alive.
                // The capture gate already rejects a stale ring; the watchdog must use the same
                // freshness truth or it can declare a frozen producer healthy forever simply
                // because yesterday's frames are still retained in the ring.
                if (health.validCompleteFrameCount > 0 && freshCompleteFrames > 0) {
                    if (resubmittedInEpisode) {
                        Log.i(
                            "NearZslWatchdog",
                            "event=RECOVERED generation=$sessionGeneration complete=${health.validCompleteFrameCount} " +
                                    "freshComplete=$freshCompleteFrames startupState=${health.startupPairingState}"
                        )
                    }
                    emptyEpisodeStartMs = 0L
                    resubmittedInEpisode = false
                    resubmitElapsedMs = 0L
                    warmBufferWatchdogRebuildAttemptsSinceHealthy = 0
                    continue
                }

                if (isCapturing || isPipelineResetting) continue

                val nowMs = android.os.SystemClock.elapsedRealtime()
                if (emptyEpisodeStartMs == 0L) emptyEpisodeStartMs = nowMs
                val emptyForMs = nowMs - emptyEpisodeStartMs
                val graceMs = (frameMs * 4.0).toLong().coerceIn(140L, 420L)
                val cooldownSatisfied = nowMs - lastResubmitMs >= 1_000L
                if (!resubmittedInEpisode) {
                    if (emptyForMs < graceMs || !cooldownSatisfied) continue

                    val session = captureSession ?: continue
                    val builder = currentCaptureRequest ?: continue
                    val handler = backgroundHandler ?: continue
                    try {
                        submitRepeatingRequestWithProvenance(
                            session = session,
                            builder = builder,
                            callback = captureCallback,
                            handler = handler,
                            reason = "WARM_BUFFER_WATCHDOG_RESUBMIT",
                            pipelineGenerationAtSubmission = sessionGeneration
                        )
                        lastResubmitMs = nowMs
                        resubmittedInEpisode = true
                        resubmitElapsedMs = nowMs
                        Log.w(
                            "NearZslWatchdog",
                            "event=REPEATING_RESUBMIT generation=$sessionGeneration emptyForMs=$emptyForMs " +
                                    "graceMs=$graceMs startupState=${health.startupPairingState} " +
                                    "validComplete=${health.validCompleteFrameCount} freshComplete=$freshCompleteFrames " +
                                    "pendingPairs=${health.pendingPairs} producerHeadroom=${health.actualProducerHeadroom}"
                        )
                    } catch (t: Throwable) {
                        lastResubmitMs = nowMs
                        resubmittedInEpisode = true
                        resubmitElapsedMs = nowMs
                        Log.w(tag, "Warm-buffer watchdog repeating resubmit failed", t)
                    }
                    continue
                }

                // Tier 2 is reserved for a genuinely stalled Camera2 transport. Seeing both an
                // image and metadata proves that the physical session is alive; tearing that
                // session down merely because timestamp pairing has not completed yet creates the
                // visible live -> freeze -> live startup hiccup and cannot repair a pairing issue.
                // Give real transport stalls a substantially longer startup grace before the one-
                // shot rebuild so normal HAL warm-up never looks like a camera reopen.
                val staleProducer = health.validCompleteFrameCount > 0 && freshCompleteFrames == 0
                val transportMissing = staleProducer ||
                    health.startupPairingState == "WAITING_IMAGE_AND_METADATA" ||
                    health.startupPairingState == "WAITING_IMAGE" ||
                    health.startupPairingState == "WAITING_METADATA"
                if (!transportMissing) {
                    if (health.startupPairingState == "IMAGE_METADATA_PRESENT_PAIR_NOT_COMPLETE") {
                        Log.w(
                            "NearZslWatchdog",
                            "event=PAIRING_LAG_NO_SESSION_REBUILD generation=$sessionGeneration " +
                                "startupState=${health.startupPairingState} pendingPairs=${health.pendingPairs} " +
                                "pairingFailures=${health.pairingFailures}"
                        )
                    }
                    continue
                }

                val escalationGraceMs = (frameMs * 24.0).toLong().coerceIn(1_500L, 3_000L)
                val rebuildCooldownSatisfied = nowMs - lastWarmBufferWatchdogRebuildMs >= 5_000L
                if (resubmitElapsedMs > 0L &&
                    nowMs - resubmitElapsedMs >= escalationGraceMs &&
                    warmBufferWatchdogRebuildAttemptsSinceHealthy < 1 &&
                    rebuildCooldownSatisfied
                ) {
                    val surface = lastPreviewSurface ?: continue
                    val format = lastPreferredFormat
                    val profile = lastProfileId
                    lastWarmBufferWatchdogRebuildMs = nowMs
                    warmBufferWatchdogRebuildAttemptsSinceHealthy++
                    Log.e(
                        "NearZslWatchdog",
                        "event=SESSION_REBUILD_ESCALATION_TRANSPORT_STALL generation=$sessionGeneration " +
                                "postResubmitWaitMs=${nowMs - resubmitElapsedMs} " +
                                "startupState=${health.startupPairingState} staleProducer=$staleProducer " +
                                "validComplete=${health.validCompleteFrameCount} freshComplete=$freshCompleteFrames " +
                                "pendingPairs=${health.pendingPairs} producerHeadroom=${health.actualProducerHeadroom}"
                    )
                    backgroundHandler?.post {
                        val freshAtEscalation = ringBuffer.freshMetadataCompleteFrameCount(
                            referenceTimestampNs = android.os.SystemClock.elapsedRealtimeNanos(),
                            freshnessWindowMs = streamHealthRequirement.streamHealthFreshnessWindowMs
                        )
                        if (sessionGeneration == pipelineGeneration &&
                            freshAtEscalation == 0 &&
                            !isCapturing && !isPipelineResetting
                        ) {
                            softResetPipeline(
                                previewSurface = surface,
                                newFormat = format,
                                profileId = profile,
                                forceSessionRebuild = true,
                                reason = "WARM_BUFFER_WATCHDOG_TRANSPORT_STALLED_AFTER_RESUBMIT"
                            )
                        }
                    }
                    return@launch
                }
            }
        }
    }

    private fun registerSessionOutputOwnership(
        session: CameraCaptureSession,
        namedSurfaces: List<Pair<Surface, String>>,
        readers: List<ImageReader>,
        generation: Int,
        epoch: Long,
        reason: String
    ) {
        synchronized(sessionLifecycleLock) {
            sessionCloseBarriers.getOrPut(session) { CompletableDeferred() }
            val names = sessionSurfaceNames.getOrPut(session) { IdentityHashMap() }
            namedSurfaces.forEach { (surface, name) -> names[surface] = name }
            val exactReaders = readers.toSet()
            sessionOwnedReaders[session] = exactReaders
            exactReaders.forEach { reader ->
                readerOwningSessions.getOrPut(reader) {
                    java.util.Collections.newSetFromMap(IdentityHashMap<CameraCaptureSession, Boolean>())
                }.add(session)
            }
        }
        Log.i(
            previewDiagnosticsTag,
            "event=SESSION_OUTPUT_OWNERSHIP_REGISTERED generation=$generation epoch=$epoch " +
                "sessionIdentity=${System.identityHashCode(session)} reason=$reason " +
                "surfaces=${namedSurfaces.joinToString { (surface, name) -> "$name@${System.identityHashCode(surface)}" }} " +
                "readers=${readers.joinToString { System.identityHashCode(it).toString() }}"
        )
    }

    private fun sessionSurfaceDiagnosticName(session: CameraCaptureSession, surface: Surface): String =
        synchronized(sessionLifecycleLock) {
            sessionSurfaceNames[session]?.get(surface)
        } ?: "UNKNOWN_SURFACE"

    // CaptureRequest does not expose its target Surface set through the public Camera2 API.
    // Keep diagnostics truthful: onCaptureBufferLost() already supplies the exact lost target,
    // while generic capture failures can only report the outputs owned by this session.
    private fun sessionOutputDiagnostics(session: CameraCaptureSession): String =
        synchronized(sessionLifecycleLock) {
            sessionSurfaceNames[session]
                ?.entries
                ?.joinToString(prefix = "[", postfix = "]") { (surface, name) ->
                    "$name@${System.identityHashCode(surface)}"
                }
                ?: "[]"
        }

    private fun rawRingPressureSummary(): String {
        val pressure = ringBuffer.imageReaderPressureDiagnostics()
        return "ringCapacity=${pressure.ringCapacity};imageReaderMaxImages=${pressure.imageReaderMaxImages};" +
            "residentImageSlots=${pressure.ringResidentImageSlots};producerHeadroom=${pressure.producerHeadroom};" +
            "leasedFrames=${pressure.leasedFrames};cumulativeImagesAcquired=${pressure.cumulativeImagesAcquired};" +
            "acquireFailures=${pressure.imageReaderAcquireFailureCount};" +
            "maxImagesExhaustion=${pressure.imageReaderMaxImagesExhaustionCount};" +
            "drainCallbacks=${pressure.drainCallbackCount};drainBatchHighWatermark=${pressure.drainBatchHighWatermark};" +
            "drainServiceMedianMs=${pressure.drainServiceMedianMs ?: -1.0};" +
            "drainServiceMaxMs=${pressure.drainServiceMaxMs ?: -1.0};" +
            "ringOverwriteCount=${pressure.ringOverwriteCount};droppedIncomingFrames=${pressure.droppedIncomingFrames};" +
            "backpressureDetected=${pressure.backpressureDetected}"
    }

    private fun recordRawPreviewValidationEnvironment(reason: String, generation: Int) {
        val identity = synchronized(pipelineLock) { activePipelineIdentity } ?: return
        if (identity.bufferFormat != ImageFormat.RAW10 && identity.bufferFormat != ImageFormat.RAW_SENSOR) return
        val characteristicsId = identity.physicalCameraId ?: identity.logicalCameraId
        val characteristics = runCatching { cameraManager.getCameraCharacteristics(characteristicsId) }.getOrNull()
        val map = characteristics?.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        fun sizes(format: Int): String = runCatching {
            map?.getOutputSizes(format).orEmpty()
                .sortedByDescending { it.width.toLong() * it.height.toLong() }
                .take(12)
                .joinToString(prefix = "[", postfix = "]") { "${it.width}x${it.height}" }
        }.getOrDefault("[]")
        val activeMinDurationNs = runCatching {
            map?.getOutputMinFrameDuration(
                identity.bufferFormat,
                android.util.Size(identity.width, identity.height)
            ) ?: 0L
        }.getOrDefault(0L)
        val fpsRanges = characteristics?.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
            ?.joinToString(prefix = "[", postfix = "]") { "${it.lower}:${it.upper}" } ?: "[]"
        val timestampSource = characteristics?.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE) ?: -1
        val hardwareLevel = characteristics?.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL) ?: -1
        val interop = com.bncam.ui.screens.capture.RawPreviewInteropCapabilities.latest
        val display = com.bncam.ui.screens.capture.FocusPeakingView.getProvenanceSummary()
        val content = buildString {
            appendLine("reason=$reason generation=$generation")
            appendLine("manufacturer=${Build.MANUFACTURER} model=${Build.MODEL} fingerprint=${Build.FINGERPRINT} sdk=${Build.VERSION.SDK_INT} buildType=${com.bncam.BuildConfig.BUILD_TYPE}")
            appendLine("selectedLens=${identity.selectedLensId} logicalCamera=${identity.logicalCameraId} physicalCamera=${identity.physicalCameraId ?: "none"} characteristicsId=$characteristicsId hardwareLevel=$hardwareLevel")
            appendLine("source=${identity.effectiveFrameSource} format=${identity.bufferFormat} activeSize=${identity.width}x${identity.height} maxImages=${identity.maxImages} activeMinFrameDurationNs=$activeMinDurationNs")
            appendLine("rawSensorSizes=${sizes(ImageFormat.RAW_SENSOR)}")
            appendLine("raw10Sizes=${sizes(ImageFormat.RAW10)}")
            appendLine("aeTargetFpsRanges=$fpsRanges timestampSource=$timestampSource")
            appendLine("customRawPreviewFormatCode=${identity.rawPreviewFormatCode ?: "none"} customBinding=${customRawPreviewBinding ?: "none"} " +
                "customPresentedReadyGeneration=$customRawPreviewPresentedReadyGeneration " +
                rawPreviewProducerAuthorityTracker.summary(pipelineGeneration))
            appendLine("interopEglGeneration=${interop?.eglGeneration ?: -1} interopReady=${interop?.readyForAhbEglImageInterop ?: false} interopBlockers=${interop?.blockers?.joinToString() ?: "unprobed"} vulkanAhbUsage=0x${(interop?.vulkanOutputAhbUsage ?: 0L).toString(16)}")
            appendLine("displayProvenance=$display")
        }
        com.bncam.core.debug.DiagnosticsAggregator.record(
            stream = com.bncam.core.debug.DiagnosticsAggregator.Stream.PERFORMANCE,
            scope = "VIEWFINDER source=${identity.effectiveFrameSource} generation=$generation",
            section = "RAW PREVIEW VALIDATION ENVIRONMENT",
            content = content
        )
        Log.i(previewDiagnosticsTag, "event=RAW_PREVIEW_VALIDATION_ENVIRONMENT generation=$generation reason=$reason")
    }

    private fun recordRawSessionOutputDiagnostic(section: String, content: String) {
        Log.w(previewDiagnosticsTag, "event=$section $content")
        com.bncam.core.debug.DiagnosticsAggregator.record(
            stream = com.bncam.core.debug.DiagnosticsAggregator.Stream.CAPTURE,
            scope = "VIEWFINDER",
            section = section,
            content = content
        )
    }

    private fun closeImageReaderNow(reader: ImageReader, reason: String) {
        runCatching { reader.close() }
            .onFailure { Log.w(tag, "Retired ImageReader close failed reason=$reason", it) }
        Log.i(
            previewDiagnosticsTag,
            "event=IMAGE_READER_PHYSICALLY_CLOSED readerIdentity=${System.identityHashCode(reader)} reason=$reason"
        )
    }

    /**
     * Physical ImageReader close is forbidden while any configured/retiring CameraCaptureSession
     * can still reference its Surface. Retirement is therefore a two-step operation: detach the
     * producer listener now, close only after every owning session has acknowledged onClosed().
     */
    private fun closeRetiredImageReader(reader: ImageReader?, reason: String) {
        if (reader == null) return
        val closeNow = synchronized(sessionLifecycleLock) {
            val owners = readerOwningSessions[reader]
            if (owners.isNullOrEmpty()) {
                retiringImageReaders.remove(reader)
                true
            } else {
                retiringImageReaders[reader] = reason
                false
            }
        }
        if (closeNow) {
            closeImageReaderNow(reader, reason)
        } else {
            Log.i(
                previewDiagnosticsTag,
                "event=IMAGE_READER_CLOSE_DEFERRED readerIdentity=${System.identityHashCode(reader)} " +
                    "reason=$reason"
            )
        }
    }

    private fun releaseSessionOutputOwnership(session: CameraCaptureSession) {
        val readyToClose = mutableListOf<Pair<ImageReader, String>>()
        synchronized(sessionLifecycleLock) {
            sessionSurfaceNames.remove(session)
            val readers = sessionOwnedReaders.remove(session).orEmpty()
            readers.forEach { reader ->
                val owners = readerOwningSessions[reader]
                owners?.remove(session)
                if (owners.isNullOrEmpty()) {
                    readerOwningSessions.remove(reader)
                    retiringImageReaders.remove(reader)?.let { reason ->
                        readyToClose += reader to reason
                    }
                }
            }
        }
        readyToClose.forEach { (reader, reason) -> closeImageReaderNow(reader, reason) }
    }

    private fun closeCustomRawPreviewReader(reason: String) {
        val retiring = customRawPreviewReader
        retiring?.setOnImageAvailableListener(null, null)
        if (retiring != null || customRawPreviewBinding != null) {
            Log.i(tag, "CUSTOM_RAW_PREVIEW_RETIRE_REQUESTED reason=$reason binding=$customRawPreviewBinding")
        }
        customRawPreviewReader = null
        customRawPreviewBinding = null
        customRawPreviewPresentedReadyGeneration = -1
        rawPreviewProducerAuthorityTracker.reset(pipelineGeneration)
        customRawPreviewLastFrameElapsedNs = 0L
        customRawPreviewLastSensorTimestampNs = 0L
        customRawPreviewFrameCount = 0L
        closeRetiredImageReader(retiring, "custom_raw:$reason")
    }

    /**
     * Removes the current custom RAW preview reader from active ownership without closing it.
     * The caller marks the returned reader for retirement against the exact Camera2 session.
     */
    private fun detachCustomRawPreviewReaderForRetirement(reason: String): ImageReader? {
        val retiring = customRawPreviewReader
        if (retiring != null) {
            retiring.setOnImageAvailableListener(null, null)
            Log.i(tag, "CUSTOM_RAW_PREVIEW_RETIRE_REQUESTED reason=$reason binding=$customRawPreviewBinding")
        }
        customRawPreviewReader = null
        customRawPreviewBinding = null
        customRawPreviewPresentedReadyGeneration = -1
        rawPreviewProducerAuthorityTracker.reset(pipelineGeneration)
        customRawPreviewLastFrameElapsedNs = 0L
        customRawPreviewLastSensorTimestampNs = 0L
        customRawPreviewFrameCount = 0L
        return retiring
    }

    private fun retireReadersWhenSessionCloses(
        ticket: SessionCloseTicket,
        readers: List<ImageReader?>,
        reason: String
    ) {
        val ownedReaders = readers.filterNotNull()
        if (ownedReaders.isEmpty()) return
        ownedReaders.forEach { reader -> closeRetiredImageReader(reader, reason) }
        sessionTransitionScope.launch {
            ticket.closeBarrier.await()
            Log.i(
                previewDiagnosticsTag,
                "event=RETIRED_SESSION_OUTPUTS_RELEASED generation=${ticket.generation} " +
                    "epoch=${ticket.sessionEpoch} count=${ownedReaders.size} reason=$reason"
            )
        }
    }

    private fun chooseCustomRawPreviewSize(
        map: android.hardware.camera2.params.StreamConfigurationMap,
        formatCode: Int,
        fallbackWidth: Int,
        fallbackHeight: Int
    ): Pair<android.util.Size, Boolean> {
        val advertised = runCatching { map.getOutputSizes(formatCode)?.toList().orEmpty() }
            .getOrDefault(emptyList())
        if (advertised.isEmpty()) {
            return android.util.Size(fallbackWidth, fallbackHeight) to false
        }

        val targetAspect = fallbackWidth.toDouble() / fallbackHeight.coerceAtLeast(1).toDouble()
        val aspectCompatible = advertised.filter { size ->
            val aspect = size.width.toDouble() / size.height.coerceAtLeast(1).toDouble()
            kotlin.math.abs(aspect - targetAspect) / targetAspect.coerceAtLeast(0.01) <= 0.035
        }.ifEmpty { advertised }

        data class Candidate(val size: android.util.Size, val durationNs: Long)
        val candidates = aspectCompatible.map { size ->
            Candidate(
                size,
                runCatching { map.getOutputMinFrameDuration(formatCode, size) }.getOrDefault(0L)
            )
        }
        val thirtyFpsNs = 33_333_334L
        val fast = candidates.filter { it.durationNs <= 0L || it.durationNs <= thirtyFpsNs }
        val pool = fast.ifEmpty { candidates }
        val selected = pool.maxWithOrNull(
            compareBy<Candidate> { it.size.width.toLong() * it.size.height.toLong() }
                .thenByDescending { if (it.durationNs > 0L) -it.durationNs else Long.MIN_VALUE }
        )?.size ?: android.util.Size(fallbackWidth, fallbackHeight)
        return selected to true
    }

    private fun prepareCustomRawPreviewReader(cameraId: String, generation: Int): Surface? {
        val identity = synchronized(pipelineLock) { activePipelineIdentity } ?: return null
        if (customRawPreviewDisabledGeneration == generation || viewfinderStreamSetting != ViewfinderStream.SELECTED_BUFFER) {
            closeCustomRawPreviewReader("disabled_or_yuv_viewfinder")
            return null
        }
        val source = ViewfinderEffectiveSource.fromFrameSource(identity.requestedFrameSource)
        if (source == ViewfinderEffectiveSource.YUV) {
            closeCustomRawPreviewReader("non_raw_profile")
            return null
        }

        val requestedCode = identity.rawPreviewFormatCode
        if (requestedCode == null) {
            closeCustomRawPreviewReader("canonical_binding")
            return null
        }
        when (rawPreviewFormatCompatibility(source.name, requestedCode)) {
            RawPreviewFormatCompatibility.CANONICAL -> {
                closeCustomRawPreviewReader("canonical_binding")
                return null
            }
            RawPreviewFormatCompatibility.INCOMPATIBLE_STANDARD -> {
                Log.w(
                    tag,
                    "CUSTOM_RAW_PREVIEW_REJECTED stage=format_contract source=${source.name} " +
                        "code=$requestedCode reason=known_standard_layout_mismatch"
                )
                closeCustomRawPreviewReader("known_standard_layout_mismatch")
                return null
            }
            RawPreviewFormatCompatibility.CUSTOM_VENDOR_UNVERIFIED -> Unit
        }

        val chars = runCatching { cameraManager.getCameraCharacteristics(cameraId) }.getOrNull() ?: return null
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return null
        val (size, advertised) = chooseCustomRawPreviewSize(
            map = map,
            formatCode = requestedCode,
            fallbackWidth = identity.width,
            fallbackHeight = identity.height
        )
        val existing = customRawPreviewBinding
        if (existing != null && existing.source == source && existing.formatCode == requestedCode &&
            existing.width == size.width && existing.height == size.height && existing.generation == generation &&
            customRawPreviewReader != null
        ) {
            return customRawPreviewReader?.surface
        }

        closeCustomRawPreviewReader("binding_changed")
        val reader = try {
            ImageReader.newInstance(size.width, size.height, requestedCode, 3)
        } catch (t: Throwable) {
            Log.w(
                tag,
                "CUSTOM_RAW_PREVIEW_REJECTED stage=ImageReader source=${source.name} code=$requestedCode " +
                    "size=${size.width}x${size.height} advertised=$advertised reason=${t.message}"
            )
            customRawPreviewDisabledGeneration = generation
            return null
        }
        val binding = CustomRawPreviewBinding(source, requestedCode, size.width, size.height, generation, advertised)
        customRawPreviewReader = reader
        customRawPreviewBinding = binding
        customRawPreviewPresentedReadyGeneration = -1
        rawPreviewProducerAuthorityTracker.reset(generation)
        customRawPreviewLastFrameElapsedNs = 0L
        customRawPreviewLastSensorTimestampNs = 0L
        customRawPreviewFrameCount = 0L
        reader.setOnImageAvailableListener({ available ->
            val image = runCatching { available.acquireLatestImage() }.getOrNull() ?: return@setOnImageAvailableListener
            val rawImageTrace = com.bncam.ui.screens.capture.RawPreviewTrace.beginImageAvailable()
            try {
                if (generation != pipelineGeneration || customRawPreviewBinding != binding) return@setOnImageAvailableListener
                if (targetViewfinderSource != source || targetViewfinderGeneration != generation) {
                    return@setOnImageAvailableListener
                }
                val timestamp = image.timestamp.takeIf { it > 0L } ?: android.os.SystemClock.elapsedRealtimeNanos()
                customRawPreviewLastFrameElapsedNs = android.os.SystemClock.elapsedRealtimeNanos()
                customRawPreviewLastSensorTimestampNs = timestamp
                customRawPreviewFrameCount += 1L
                RawPreviewCadenceDiagnostics.sourceArrived(source, generation, timestamp)
                com.bncam.core.debug.RawPreviewFirstActivationTrace.rawFrameAvailable(
                    source = source.name,
                    generation = generation,
                    sensorTimestampNs = timestamp,
                    origin = "custom_raw_image_reader"
                )
                val recentMetadata = if (lastCaptureResultGeneration == generation) lastCaptureResult as? TotalCaptureResult else null
                if (rawPreviewConfiguredGeneration != generation) recentMetadata?.let { ensureRawPreviewConfig(it, generation) }
                if (customRawPreviewDisabledGeneration == generation) {
                    return@setOnImageAvailableListener
                }
                val hardwareBuffer = runCatching { image.hardwareBuffer }.getOrNull()
                if (hardwareBuffer != null && rawPreviewConfiguredGeneration == generation) {
                    // Input arrival and renderer publication are not display proof. Keep the
                    // canonical warm RAW ring authoritative until this custom frame is EGL PRESENTED.
                    val ringHandoffTrace = com.bncam.ui.screens.capture.RawPreviewTrace.beginRingHandoff()
                    try {
                        rawPreviewRenderer.offerBorrowedHardwareBuffer(
                            buffer = hardwareBuffer,
                            sensorTimestampNs = timestamp,
                            pipelineGeneration = generation,
                            useRuntimeCrop = false,
                            producerKind = RawPreviewProducerKind.CUSTOM_IMAGE_READER
                        )
                    } finally {
                        com.bncam.ui.screens.capture.RawPreviewTrace.end(ringHandoffTrace)
                    }
                }
            } finally {
                image.close()
                com.bncam.ui.screens.capture.RawPreviewTrace.end(rawImageTrace)
            }
        }, backgroundHandler)
        Log.i(
            tag,
            "CUSTOM_RAW_PREVIEW_READY source=${source.name} code=$requestedCode " +
                "size=${size.width}x${size.height} advertised=$advertised generation=$generation"
        )
        return reader.surface
    }

    // ========================================================
    // CAMERA LIFECYCLE
    // ========================================================
    @SuppressLint("MissingPermission")
    suspend fun startCameraAndZsl(
        cameraId: String,
        previewSurface: Surface,
        preferredFormat: String = "YUV",
        profileId: String = "unknown",
        previewWidth: Int = 0,
        previewHeight: Int = 0
    ): Boolean = pipelineTransitionMutex.withLock {
        startCameraAndZslOwned(
            cameraId = cameraId,
            previewSurface = previewSurface,
            preferredFormat = preferredFormat,
            profileId = profileId,
            previewWidth = previewWidth,
            previewHeight = previewHeight
        )
    }

    @SuppressLint("MissingPermission")
    private suspend fun startCameraAndZslOwned(
        cameraId: String,
        previewSurface: Surface,
        preferredFormat: String,
        profileId: String,
        previewWidth: Int,
        previewHeight: Int
    ): Boolean {
        val previousLensId = activeLensId
        val startupSequenceId = cameraStartupSequenceCounter.incrementAndGet()
        lifetimeHardStartCount.incrementAndGet()
        activeCameraStartupSequenceId = startupSequenceId
        startupCameraOpenCount = 0
        startupSessionRequestCount = 0
        startupSessionConfiguredCount = 0
        Log.i(
            previewDiagnosticsTag,
            "event=CAMERA_START_SEQUENCE_BEGIN startupSequence=$startupSequenceId " +
                "requestedLensId=$cameraId preferredFormat=$preferredFormat profileId=$profileId " +
                "previousLensId=${previousLensId ?: "none"}"
        )

        // A hard start owns the close->open barrier. Never depend on the caller to have waited
        // long enough for a previous CameraDevice to retire; opening against a still-closing HAL
        // is a known way to strand the next session.
        if (cameraDevice != null || captureSession != null || imageReader != null) {
            Log.w(tag, "Existing Camera2 pipeline detected before hard start; retiring it first.")
            // Internal lens/stream replacement keeps the already-running Camera2 worker. Device
            // and session ownership still pass through the full close barrier; only the host
            // HandlerThread is retained to avoid a needless quit/start cycle between sensors.
            closeCameraOwned(
                reason = "HARD_START_REPLACING_ACTIVE_PIPELINE",
                retainBackgroundThread = true
            )
        }
        val cameraHardwareReleased = awaitCameraHardwareClosed(
            maxWaitMs = CAMERA_HARD_CLOSE_RECOVERY_TIMEOUT_MS
        )
        if (!cameraHardwareReleased) {
            cameraState.value = CameraEngineState.ERROR
            Log.e(
                tag,
                "CAMERA_HARD_START_ABORTED reason=previous_device_still_closing " +
                    "remaining=${closingCameraDeviceCount.get()} cameraId=$cameraId"
            )
            return false
        }

        this.activeLensId = cameraId
        pipelineTransitionState = PipelineTransitionState.STARTING
        lastPreviewSurface = previewSurface
        lastPreferredFormat = preferredFormat
        lastProfileId = profileId
        configuredPreviewStreamWidth = previewWidth
        configuredPreviewStreamHeight = previewHeight

        startBackgroundThread()
        ringBuffer.clear()
        predictiveAfTracker.clear()

        pushHardwareConfigToNative(cameraId)

        try {
            val requestedIdentity = buildPipelineIdentity(cameraId, profileId, preferredFormat)
            if (requestedIdentity == null) {
                Log.e(tag, "PIPELINE_START_FAILED: Geen compatibele resolutie gevonden voor $preferredFormat op lens $cameraId")
                closeCameraOwned("HARD_START_IDENTITY_FAILED")
                return false
            }

            if (requestedIdentity.bufferFormat == ImageFormat.RAW10 ||
                requestedIdentity.bufferFormat == ImageFormat.RAW_SENSOR
            ) {
                prepareRawPreviewBackendAsync(
                    "raw_profile_start:${formatName(requestedIdentity.bufferFormat)}"
                )
            }

            synchronized(pipelineLock) {
                pipelineGeneration = FrameGenerationId.increment()
                staleFramesDropped = 0
                sessionConfiguredGeneration = -1
                activePipelineIdentity = requestedIdentity
                activeZslFormat = requestedIdentity.bufferFormat
            }
            logPreviewDiagnostics(
                event = "LENS_SWITCH",
                extra = "previousSelectedLensId=${previousLensId ?: "none"} requestedFrameSource=$preferredFormat"
            )
            val startGeneration = pipelineGeneration
            prewarmRawStillWorkingSetAsync(
                identity = requestedIdentity,
                generation = startGeneration,
                reason = "HARD_START_IDENTITY_READY"
            )
            ringBuffer.activateGeneration(startGeneration)
            ringBuffer.predictiveAfTracker = predictiveAfTracker
            _focusPeakingGuidance.value = FocusPeakingGuidance()
            refreshEffectiveViewfinderSource()
            configureNearZslTimestampObservability(cameraId)
            restoreTrackingOwnershipAfterPipelineTransition("pipeline_hard_start")
            clearTouchAeOverride()
            initialAeMeteringRegions = null
            initialAeMeteringGeneration = -1
            lastFaceMeteringRect = null
            lastFaceTrackingId = null
            _priorityFaceBounds.value = null

            writePipelineLifecycleDebug(
                event = "PIPELINE_HARD_START",
                decision = PipelineResetDecision(true, listOf("HARD_START_OR_SENSOR_START"), null, requestedIdentity),
                extra = "oldGeneration=${startGeneration - 1}\nnewGeneration=$startGeneration\nimageReaderClosed=true\nbufferPoolCleared=true\nactivePipelineAfterReset=${requestedIdentity.toDebugString()}"
            )

            imageReader = ImageReader.newInstance(
                requestedIdentity.width,
                requestedIdentity.height,
                requestedIdentity.bufferFormat,
                imageReaderMaxImages(requestedIdentity.maxImages)
            )

            ringBuffer.resizeBuffer(requestedIdentity.maxImages)
            ringBuffer.recordImageReaderConfiguration(
                maxImages = imageReader?.maxImages ?: 0,
                format = requestedIdentity.bufferFormat,
                width = requestedIdentity.width,
                height = requestedIdentity.height,
                generationId = startGeneration
            )

            val rowStrideEstimate = when (requestedIdentity.bufferFormat) {
                ImageFormat.RAW10 -> (requestedIdentity.width * 10 + 7) / 8
                ImageFormat.RAW_SENSOR -> requestedIdentity.width * 2
                else -> requestedIdentity.width
            }
            com.bncam.core.runtime.RawPipelineRuntimeProfileFactory.buildAndPublish(
                context = context,
                logicalCameraId = requestedIdentity.logicalCameraId,
                physicalCameraId = requestedIdentity.physicalCameraId ?: requestedIdentity.logicalCameraId,
                format = requestedIdentity.bufferFormat,
                sessionGeneration = startGeneration,
                bufferWidth = requestedIdentity.width,
                bufferHeight = requestedIdentity.height,
                rowStride = rowStrideEstimate
            )

            var frameCounter = 0
            fun handleAcquiredImage(image: android.media.Image) {
                val imageOwner = CloseOnce { image.close() }
                var transferredToRing = false
                captureAttempts.imageReaderAcquired()
                ringBuffer.recordImageAcquired(generationId = startGeneration)
                try {
                    if (requestedIdentity.bufferFormat == ImageFormat.RAW_SENSOR &&
                        (frameCounter < 3 || frameCounter % 30 == 0)
                    ) {
                        Log.i(tag, "RAW_SENSOR ImageReader image arrived timestamp=${image.timestamp} size=${image.width}x${image.height} format=${image.format}")
                    }
                    if (startGeneration != pipelineGeneration) {
                        staleFramesDropped++
                        return
                    }
                    RawPreviewCadenceDiagnostics.sourceArrived(
                        source = ViewfinderEffectiveSource.fromImageFormat(requestedIdentity.bufferFormat),
                        generation = startGeneration,
                        sensorTimestampNs = image.timestamp
                    )
                    com.bncam.core.debug.RawPreviewFirstActivationTrace.rawFrameAvailable(
                        source = ViewfinderEffectiveSource.fromImageFormat(requestedIdentity.bufferFormat).name,
                        generation = startGeneration,
                        sensorTimestampNs = image.timestamp,
                        origin = "authoritative_raw_image_reader"
                    )
                    if (frameCounter == 0 &&
                        (requestedIdentity.bufferFormat == ImageFormat.RAW10 ||
                            requestedIdentity.bufferFormat == ImageFormat.RAW_SENSOR)
                    ) {
                        com.bncam.core.runtime.RawPipelineRuntimeProfileFactory.refinePublishedLayoutFromImage(
                            image = image,
                            sessionGeneration = startGeneration
                        )
                    }
                    transferredToRing = ringBuffer.addImage(
                        image = image,
                        generationId = startGeneration,
                        expectedFormat = requestedIdentity.bufferFormat
                    )
                    if (transferredToRing) {
                        markViewfinderProducerFrameReady(
                            ViewfinderEffectiveSource.fromImageFormat(requestedIdentity.bufferFormat),
                            startGeneration,
                            image.timestamp
                        )
                        if (requestedIdentity.bufferFormat == ImageFormat.YUV_420_888) {
                            scheduleEnabledYuvAnalysis(
                                timestampNs = image.timestamp,
                                generation = startGeneration,
                                expectedFormat = requestedIdentity.bufferFormat,
                                deviceRotation = currentMlAnalysisRotationDegrees()
                            )
                        }
                        scheduleFocusConfidenceAnalysis(
                            generation = startGeneration,
                            expectedFormat = requestedIdentity.bufferFormat
                        )
                        offerRawPreviewImage(image.timestamp, startGeneration)
                    }
                    if (!transferredToRing) {
                        Log.e(
                            tag,
                            "FRAME_REJECTED profile=${requestedIdentity.requestedProfileId} " +
                                    "requested=${formatName(requestedIdentity.bufferFormat)} actual=${formatName(image.format)} " +
                                    "generation=$startGeneration timestamp=${image.timestamp} bufferSize=${ringBuffer.completeFrameCount()}"
                        )
                    }
                } catch (e: Exception) {
                    Log.e(tag, "Failed to consume ImageReader image", e)
                } finally {
                    if (!transferredToRing) {
                        try { imageOwner.close() } catch (closeError: Exception) {
                            Log.w(tag, "Image.close failed", closeError)
                        }
                    }
                    captureAttempts.imageReaderClosed()
                    frameCounter++
                }
            }

            imageReader?.setOnImageAvailableListener({ reader ->
                val callbackStartNs = android.os.SystemClock.elapsedRealtimeNanos()
                val rawImageTrace = if (requestedIdentity.bufferFormat == ImageFormat.RAW10 ||
                    requestedIdentity.bufferFormat == ImageFormat.RAW_SENSOR
                ) com.bncam.ui.screens.capture.RawPreviewTrace.beginImageAvailable() else false
                var drainedImages = 0
                captureAttempts.imageAvailable()
                try {
                    while (true) {
                        val image = try {
                            reader.acquireNextImage()
                        } catch (e: IllegalStateException) {
                            val maxImagesExhausted = ringBuffer.producerHeadroom() <= 0
                            ringBuffer.recordImageReaderAcquireFailure(
                                generationId = startGeneration,
                                maxImagesExhausted = maxImagesExhausted
                            )
                            Log.e(
                                tag,
                                "ImageReader acquireNextImage IllegalStateException " +
                                    "maxImages=${reader.maxImages} maxImagesExhausted=$maxImagesExhausted " +
                                    "pressure=${ringBuffer.imageReaderPressureDiagnostics()}",
                                e
                            )
                            recordRawSessionOutputDiagnostic(
                                "IMAGE_READER_ACQUIRE_FAILED",
                                "generation=$startGeneration;maxImages=${reader.maxImages};" +
                                    "maxImagesExhausted=$maxImagesExhausted;${rawRingPressureSummary()}"
                            )
                            null
                        } catch (e: Exception) {
                            ringBuffer.recordImageReaderAcquireFailure(generationId = startGeneration)
                            Log.w(tag, "ImageReader acquire failed", e)
                            null
                        } ?: break
                        drainedImages++
                        handleAcquiredImage(image)
                    }
                } finally {
                    ringBuffer.recordImageReaderDrain(
                        generationId = startGeneration,
                        callbackServiceNs = android.os.SystemClock.elapsedRealtimeNanos() - callbackStartNs,
                        drainedImages = drainedImages
                    )
                    com.bncam.ui.screens.capture.RawPreviewTrace.end(rawImageTrace)
                }
            }, backgroundHandler)

            val startupReady = CompletableDeferred<Boolean>()
            // Settings/vendor discovery can run in parallel with CameraDevice.openCamera(). The
            // CameraDevice callback itself never waits on DataStore or scanner IO.
            val initialSessionSettingsDeferred = sessionTransitionScope.async {
                loadSessionRequestSettings(requestedIdentity.selectedLensId)
            }
            beginCameraOpenRequest(startGeneration)
            com.bncam.core.debug.Phase0PerformanceTrace.lensTransitionMilestone(
                targetLensId = cameraId,
                event = "camera_open_requested",
                detail = "logical=${requestedIdentity.logicalCameraId}"
            )
            lifetimeCameraOpenRequestCount.incrementAndGet()
            logCameraLifetimeCounters(
                event = "CAMERA_OPEN_REQUEST",
                extra = "requestedLogical=${requestedIdentity.logicalCameraId} requestedLens=$cameraId"
            )
            cameraManager.openCamera(requestedIdentity.logicalCameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    if (startGeneration != pipelineGeneration) {
                        startupReady.complete(false)
                        requestCameraDeviceClose(camera, "OBSOLETE_OPEN_CALLBACK")
                        settleCameraOpenRequest(startGeneration)
                        return
                    }
                    settleCameraOpenRequest(startGeneration)
                    cameraDevice = camera
                    com.bncam.core.debug.Phase0PerformanceTrace.lensTransitionMilestone(
                        targetLensId = cameraId,
                        event = "camera_device_opened",
                        detail = "logical=${camera.id}"
                    )
                    lifetimeCameraOpenedCount.incrementAndGet()
                    logCameraLifetimeCounters(
                        event = "CAMERA_DEVICE_OPENED",
                        extra = "logical=${camera.id} requestedLens=$cameraId"
                    )
                    com.bncam.core.debug.AfGroundTruthTrace.recordCameraRoute(
                        stage = "OPENED",
                        selectedCameraId = cameraId,
                        directOpenResult = when (requestedIdentity.cameraRouteKind) {
                            CameraRouteKind.PUBLIC_DIRECT -> "PUBLIC_LISTED"
                            CameraRouteKind.PROBED_DIRECT -> "SUCCESS"
                            CameraRouteKind.LOGICAL_PHYSICAL -> "FAILURE"
                            CameraRouteKind.DIRECT_PROBE_PENDING -> "PENDING"
                        },
                        chosenRoute = requestedIdentity.cameraRouteKind.name,
                        logicalCameraId = requestedIdentity.logicalCameraId,
                        physicalChildCameraId = requestedIdentity.physicalCameraId,
                        openedCameraDeviceId = camera.id
                    )
                    if (activeCameraStartupSequenceId == startupSequenceId) {
                        startupCameraOpenCount += 1
                    }
                    Log.i(
                        previewDiagnosticsTag,
                        "event=CAMERA_DEVICE_OPENED startupSequence=$startupSequenceId " +
                            "deviceOpens=$startupCameraOpenCount generation=$startGeneration cameraId=${camera.id}"
                    )
                    sessionTransitionScope.launch {
                        val sessionSettings = runCatching {
                            initialSessionSettingsDeferred.await()
                        }.getOrElse { error ->
                            Log.e(tag, "Initial Camera2 session settings load failed", error)
                            startupReady.complete(false)
                            closeCamera("INITIAL_SESSION_SETTINGS_FAILED")
                            return@launch
                        }
                        val handler = backgroundHandler
                        if (handler == null) {
                            startupReady.complete(false)
                            closeCamera("INITIAL_SESSION_HANDLER_MISSING")
                            return@launch
                        }
                        handler.post {
                            if (startGeneration != pipelineGeneration || cameraDevice !== camera) {
                                // The serialized hard-start owner is the only path that may retire
                                // a CameraDevice after onOpened ownership was accepted. Completing
                                // startupReady=false hands retirement back to that owner and avoids
                                // issuing a duplicate CameraDevice.close() for the same instance.
                                Log.i(
                                    previewDiagnosticsTag,
                                    "event=OBSOLETE_SESSION_SETTINGS_READY generation=$startGeneration " +
                                        "activeGeneration=$pipelineGeneration cameraStillOwned=${cameraDevice === camera}"
                                )
                                startupReady.complete(false)
                                return@post
                            }
                            createCaptureSession(
                                camera = camera,
                                previewSurface = previewSurface,
                                sessionSettings = sessionSettings,
                                reason = "HARD_START_INITIAL_SESSION",
                                onSessionReady = { ready ->
                                    startupReady.complete(ready)
                                }
                            )
                        }
                    }
                }

                override fun onDisconnected(camera: CameraDevice) {
                    startupReady.complete(false)
                    if (cameraDevice === camera) {
                        settleCameraOpenRequest(startGeneration)
                        closeCamera("CAMERA_DEVICE_DISCONNECTED")
                    } else {
                        requestCameraDeviceClose(camera, "DISCONNECTED_BEFORE_ACTIVE_OWNERSHIP")
                        settleCameraOpenRequest(startGeneration)
                    }
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    Log.e(tag, "CameraDevice open/start error=$error cameraId=${camera.id}")
                    startupReady.complete(false)
                    if (cameraDevice === camera) {
                        settleCameraOpenRequest(startGeneration)
                        closeCamera("CAMERA_DEVICE_ERROR_$error")
                    } else {
                        requestCameraDeviceClose(camera, "ERROR_BEFORE_ACTIVE_OWNERSHIP_$error")
                        settleCameraOpenRequest(startGeneration)
                    }
                }

                override fun onClosed(camera: CameraDevice) {
                    synchronized(cameraDeviceLifecycleLock) {
                        cameraDeviceCloseBarriers.remove(camera)?.complete(Unit)
                    }
                    closingCameraDeviceCount.updateAndGet { count -> (count - 1).coerceAtLeast(0) }
                    lifetimeCameraClosedAckCount.incrementAndGet()
                    Log.i(tag, "CameraDevice hardware close acknowledged; remainingClosing=${closingCameraDeviceCount.get()}")
                    logCameraLifetimeCounters(
                        event = "CAMERA_DEVICE_CLOSED_ACK",
                        extra = "logical=${camera.id} remainingClosing=${closingCameraDeviceCount.get()}"
                    )
                }
            }, backgroundHandler)

            val ready = kotlinx.coroutines.withTimeoutOrNull(CAMERA_HARD_START_READY_TIMEOUT_MS) {
                startupReady.await()
            } ?: false
            if (!ready) {
                cameraState.value = CameraEngineState.ERROR
                Log.e(
                    tag,
                    "CAMERA_HARD_START_NOT_READY cameraId=$cameraId logical=${requestedIdentity.logicalCameraId} " +
                        "generation=$startGeneration timeoutMs=$CAMERA_HARD_START_READY_TIMEOUT_MS"
                )
                closeCameraOwned("HARD_START_NOT_READY")
                return false
            }
            pipelineTransitionState = PipelineTransitionState.PREVIEW_ATTACHED
            Log.i(
                previewDiagnosticsTag,
                "event=CAMERA_START_SEQUENCE_READY startupSequence=$startupSequenceId " +
                    "deviceOpens=$startupCameraOpenCount sessionsConfigured=$startupSessionConfiguredCount " +
                    "generation=$startGeneration cameraId=${requestedIdentity.logicalCameraId}"
            )
            return true

        } catch (e: Exception) {
            settleCameraOpenRequest(pipelineGeneration)
            Log.e(tag, "Fout bij openen van camera $cameraId", e)
            cameraState.value = CameraEngineState.ERROR
            closeCameraOwned("HARD_START_EXCEPTION")
            return false
        }
    }

        // ========================================================
        // SOFT RESET: IDENTITY-DRIVEN FORMAT / PROFILE / SETTINGS REFRESH
        // ========================================================
        fun softResetPipeline(
            previewSurface: Surface,
            newFormat: String,
            profileId: String = "unknown",
            forceSessionRebuild: Boolean = false,
            reason: String = "FORCED_SESSION_REBUILD"
        ) {
            val request = PendingPipelineResetRequest(
                previewSurface = previewSurface,
                newFormat = newFormat,
                profileId = profileId,
                forceSessionRebuild = forceSessionRebuild,
                reason = reason
            )
            val startWorker = synchronized(pipelineLock) {
                // Latest request wins while one transition is active. This coalesces the common
                // profile + format + vendor burst into one follow-up session rather than opening
                // several Camera2 sessions back-to-back.
                pendingPipelineResetRequest = request
                if (pipelineResetWorkerScheduled) {
                    Log.w(
                        tag,
                        "PIPELINE_RESET_COALESCED requestedFormat=$newFormat profile=$profileId reason=$reason"
                    )
                    false
                } else {
                    pipelineResetWorkerScheduled = true
                    true
                }
            }
            if (!startWorker) return

            sessionTransitionScope.launch {
                while (true) {
                    val nextRequest = synchronized(pipelineLock) {
                        val next = pendingPipelineResetRequest
                        if (next == null) {
                            pipelineResetWorkerScheduled = false
                            isPipelineResetting = false
                            lastPipelineResetCompletedMs = android.os.SystemClock.elapsedRealtime()
                            null
                        } else {
                            pendingPipelineResetRequest = null
                            next
                        }
                    } ?: break

                    pipelineTransitionMutex.withLock {
                        if (pipelineTransitionState != PipelineTransitionState.PREVIEW_ATTACHED) {
                            Log.i(
                                tag,
                                "PIPELINE_RESET_DROPPED_NO_ACTIVE_PREVIEW format=${nextRequest.newFormat} " +
                                    "profile=${nextRequest.profileId} state=$pipelineTransitionState"
                            )
                        } else {
                            synchronized(pipelineLock) { isPipelineResetting = true }
                            pipelineTransitionState = PipelineTransitionState.RECONFIGURING
                            try {
                                val success = performSoftResetPipeline(nextRequest)
                                if (!success) {
                                    abortViewfinderRebuildVisualTransition(
                                        "PIPELINE_RESET_FAILED:${nextRequest.reason}"
                                    )
                                    Log.e(
                                        tag,
                                        "PIPELINE_RESET_TRANSACTION_FAILED format=${nextRequest.newFormat} " +
                                            "profile=${nextRequest.profileId} reason=${nextRequest.reason}"
                                    )
                                }
                            } finally {
                                synchronized(pipelineLock) { isPipelineResetting = false }
                                if (pipelineTransitionState == PipelineTransitionState.RECONFIGURING) {
                                    pipelineTransitionState = if (cameraDevice != null && captureSession != null) {
                                        PipelineTransitionState.PREVIEW_ATTACHED
                                    } else {
                                        PipelineTransitionState.CLOSED
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        private suspend fun performSoftResetPipeline(request: PendingPipelineResetRequest): Boolean {
            val transitionStartedNs = android.os.SystemClock.elapsedRealtimeNanos()
            fun transitionElapsedMs(): Double =
                (android.os.SystemClock.elapsedRealtimeNanos() - transitionStartedNs) / 1_000_000.0
            val previewSurface = request.previewSurface
            val newFormat = request.newFormat
            val profileId = request.profileId
            val forceSessionRebuild = request.forceSessionRebuild
            val reason = request.reason
            lastPreviewSurface = previewSurface
            lastPreferredFormat = newFormat
            lastProfileId = profileId
            stopWarmBufferWatchdog()

            val camera = cameraDevice ?: run {
                Log.w(tag, "Soft reset ignored: camera is not open.")
                return false
            }

            val cameraId = activeLensId ?: camera.id

            pushHardwareConfigToNative(cameraId)

            val identityStartedNs = android.os.SystemClock.elapsedRealtimeNanos()
            val requestedIdentity = try {
                buildPipelineIdentity(cameraId, profileId, newFormat)
            } catch (e: Exception) {
                Log.e(
                    tag,
                    "PIPELINE_RESET_IDENTITY_FAILED cameraId=$cameraId requestedFormat=$newFormat",
                    e
                )
                null
            }
            val identityWallMs =
                (android.os.SystemClock.elapsedRealtimeNanos() - identityStartedNs) / 1_000_000.0

            if (requestedIdentity == null) {
                Log.e(
                    tag,
                    "PIPELINE_RESET_ABORTED_NO_FALLBACK cameraId=$cameraId requestedFormat=$newFormat profile=$profileId. " +
                            "Closing active capture session instead of silently falling back to YUV."
                )
                val unsupportedSession = captureSession
                val unsupportedCloseTicket = unsupportedSession?.let {
                    requestCaptureSessionClose(it, "PIPELINE_RESET_UNSUPPORTED")
                }
                captureCallback = null
                currentCaptureRequest = null
                if (unsupportedCloseTicket != null && !awaitCaptureSessionClosed(unsupportedCloseTicket)) {
                    Log.e(tag, "PIPELINE_RESET_UNSUPPORTED_ABORTED old session still owns outputs")
                    return false
                }
                imageReader?.setOnImageAvailableListener(null, null)
                imageReader?.close()
                imageReader = null
                closeCustomRawPreviewReader("pipeline_reset_unsupported")
                ringBuffer.clear()
                synchronized(pipelineLock) {
                    pipelineGeneration = FrameGenerationId.increment()
                    activePipelineIdentity = null
                    sessionConfiguredGeneration = -1
                    staleFramesDropped = 0
                }
                ringBuffer.activateGeneration(pipelineGeneration)
                refreshEffectiveViewfinderSource()
                return false
            }

            if (requestedIdentity.bufferFormat == ImageFormat.RAW10 ||
                requestedIdentity.bufferFormat == ImageFormat.RAW_SENSOR
            ) {
                prepareRawPreviewBackendAsync(
                    "raw_profile_reset:${formatName(requestedIdentity.bufferFormat)}"
                )
            }

            val baseDecision = synchronized(pipelineLock) {
                decidePipelineReset(activePipelineIdentity, requestedIdentity)
            }

            val decision = if (forceSessionRebuild && !baseDecision.required) {
                baseDecision.copy(
                    required = true,
                    reasons = listOf(reason, "FORCED_VENDOR_OR_SETTINGS_SESSION_REBUILD")
                )
            } else {
                baseDecision
            }

            if (!decision.required) {
                // Logical/profile/request-only changes do not need a Camera2 session rebuild.
                // Refresh the logical identity in place so diagnostics/capture routing see the new
                // profile without changing generation, ImageReader ownership or warm-buffer data.
                synchronized(pipelineLock) {
                    activePipelineIdentity = requestedIdentity
                }
                // Refresh the existing repeating request so the latest metering/processing/vendor
                // request state becomes live without clearing ImageReader or the warm ring.
                updatePreviewRepeatingRequest()
                // performSoftResetPipeline() stops the watchdog before identity resolution. A
                // no-rebuild transaction must immediately restore it; otherwise any later warm
                // buffer stall has no self-healing owner until a full session reconfiguration or
                // viewfinder reattach happens. That made multi-frame readiness intermittent after
                // logically harmless profile/settings refreshes.
                startWarmBufferWatchdog(pipelineGeneration)
                Log.i(
                    tag,
                    "PIPELINE_RESET_SKIPPED: Effective physical pipeline unchanged; repeating request refreshed. ${requestedIdentity.toDebugString()}"
                )
                writePipelineLifecycleDebug(
                    event = "PIPELINE_RESET_SKIPPED",
                    decision = decision,
                    extra = "oldGeneration=$pipelineGeneration\nnewGeneration=$pipelineGeneration\nimageReaderClosed=false\nbufferPoolCleared=false\nactivePipelineAfterReset=${activePipelineIdentity?.toDebugString() ?: "none"}"
                )
                return true
            }

            val rawPreviewSessionOnlyChange =
                !forceSessionRebuild &&
                    decision.reasons.isNotEmpty() &&
                    decision.reasons.all { it == "RAW_PREVIEW_BINDING_CHANGED" }
            if (rawPreviewSessionOnlyChange) {
                stopWarmBufferWatchdog()
                val oldSession = captureSession
                val retiringCustomRawReader =
                    detachCustomRawPreviewReaderForRetirement("raw_preview_binding_session_refresh")
                val oldSessionCloseTicket = oldSession?.let {
                    requestCaptureSessionClose(it, "RAW_PREVIEW_BINDING_SESSION_ONLY_REFRESH")
                }
                captureCallback = null
                currentCaptureRequest = null
                synchronized(pipelineLock) {
                    activePipelineIdentity = requestedIdentity
                    activeZslFormat = requestedIdentity.bufferFormat
                    sessionConfiguredGeneration = -1
                }
                lastPreferredFormat = newFormat
                lastProfileId = profileId
                refreshEffectiveViewfinderSource()
                val configured = awaitCaptureSessionConfiguration(
                    camera = camera,
                    previewSurface = previewSurface,
                    reason = "RAW_PREVIEW_BINDING_SESSION_ONLY_REFRESH"
                )
                val oldSessionClosed = oldSessionCloseTicket?.closeBarrier?.isCompleted ?: true
                if (oldSessionClosed) {
                    closeRetiredImageReader(
                        retiringCustomRawReader,
                        "raw_preview_binding_session_refresh_acknowledged"
                    )
                } else if (oldSessionCloseTicket != null) {
                    // Replacement onConfigured may legally precede oldSession.onClosed(). The old
                    // output stays retained and is retired by the exact close callback; this is not
                    // a failure of the new session and must not trigger a hard camera restart.
                    retireReadersWhenSessionCloses(
                        oldSessionCloseTicket,
                        listOf(retiringCustomRawReader),
                        "raw_preview_binding_session_refresh_late_ack"
                    )
                }
                if (!configured) return false
                Log.i(
                    tag,
                    "RAW_PREVIEW_BINDING_SESSION_ONLY_REFRESH generation=$pipelineGeneration " +
                        "ringFrames=${ringBuffer.completeFrameCount()} binding=${requestedIdentity.rawPreviewBindingSignature}"
                )
                writePipelineLifecycleDebug(
                    event = "RAW_PREVIEW_BINDING_SESSION_ONLY_REFRESH",
                    decision = decision,
                    extra = "generation=$pipelineGeneration\nimageReaderClosed=false\n" +
                        "bufferPoolCleared=false\nringBufferCleared=false\n" +
                        "previousSessionClosed=$oldSessionClosed\n" +
                        "activePipelineAfterReset=${requestedIdentity.toDebugString()}"
                )
                return true
            }

            val oldGeneration = pipelineGeneration
            synchronized(pipelineLock) {
                pipelineGeneration = FrameGenerationId.increment()
                sessionConfiguredGeneration = -1
            }

            val resetGeneration = pipelineGeneration
            prewarmRawStillWorkingSetAsync(
                identity = requestedIdentity,
                generation = resetGeneration,
                reason = "SOFT_RESET_IDENTITY_READY"
            )
            if (ViewfinderRebuildVisualPolicy.requiresBlackTransition(
                    decisionReasons = decision.reasons,
                    requestReason = reason
                )
            ) {
                beginViewfinderRebuildVisualTransition(resetGeneration, reason)
            }
            ringBuffer.activateGeneration(resetGeneration)
            predictiveAfTracker.clear()
            _focusPeakingGuidance.value = FocusPeakingGuidance()
            restoreTrackingOwnershipAfterPipelineTransition("pipeline_reset")
            clearTouchAeOverride()
            initialAeMeteringRegions = null
            initialAeMeteringGeneration = -1
            lastFaceMeteringRect = null
            lastFaceTrackingId = null
            _priorityFaceBounds.value = null

            Log.w(
                tag,
                "PIPELINE_RESET_START generation=$resetGeneration cameraId=$cameraId reasons=${decision.reasons.joinToString()} requestedFormat=$newFormat currentFormat=${
                    formatName(
                        activeZslFormat
                    )
                }"
            )

            var imageReaderClosed = false

            try {
                val oldSession = captureSession
                val retiringImageReader = imageReader
                retiringImageReader?.setOnImageAvailableListener(null, null)
                val retiringCustomRawReader =
                    detachCustomRawPreviewReaderForRetirement("physical_pipeline_reset")
                val oldSessionCloseTicket = oldSession?.let {
                    requestCaptureSessionClose(
                        it,
                        "PIPELINE_PRODUCER_RESET:${decision.reasons.joinToString("+")}"
                    )
                }
                captureCallback = null
                currentCaptureRequest = null

                activePipelineIdentity = requestedIdentity
                activeZslFormat = requestedIdentity.bufferFormat
                staleFramesDropped = 0
                refreshEffectiveViewfinderSource()

                ringBuffer.clear()
                configureNearZslTimestampObservability(cameraId)

                // Build the replacement producer before releasing the previous reader. Android
                // is allowed to configure the new session before dispatching oldSession.onClosed().
                // The old ImageReader therefore remains physically alive until that exact ack.
                imageReader = ImageReader.newInstance(
                    requestedIdentity.width,
                    requestedIdentity.height,
                    requestedIdentity.bufferFormat,
                    imageReaderMaxImages(requestedIdentity.maxImages)
                )
                ringBuffer.resizeBuffer(requestedIdentity.maxImages)
                ringBuffer.recordImageReaderConfiguration(
                    maxImages = imageReader?.maxImages ?: 0,
                    format = requestedIdentity.bufferFormat,
                    width = requestedIdentity.width,
                    height = requestedIdentity.height,
                    generationId = resetGeneration
                )

                // A producer reset is a new RAW ownership generation. Rebuild the runtime profile
                // transactionally here as well as on a hard start; otherwise RAW10 -> RAW_SENSOR
                // (or the reverse) leaves preview/ISP geometry bound to the retired format.
                val resetRowStrideEstimate = when (requestedIdentity.bufferFormat) {
                    ImageFormat.RAW10 -> (requestedIdentity.width * 10 + 7) / 8
                    ImageFormat.RAW_SENSOR -> requestedIdentity.width * 2
                    else -> requestedIdentity.width
                }
                com.bncam.core.runtime.RawPipelineRuntimeProfileFactory.buildAndPublish(
                    context = context,
                    logicalCameraId = requestedIdentity.logicalCameraId,
                    physicalCameraId = requestedIdentity.physicalCameraId ?: requestedIdentity.logicalCameraId,
                    format = requestedIdentity.bufferFormat,
                    sessionGeneration = resetGeneration,
                    bufferWidth = requestedIdentity.width,
                    bufferHeight = requestedIdentity.height,
                    rowStride = resetRowStrideEstimate
                )
                Log.i(
                    tag,
                    "ImageReader CREATED: reset generation=$resetGeneration has isolated ownership; " +
                        "RAW runtime profile rebuilt for ${formatName(requestedIdentity.bufferFormat)} " +
                        "${requestedIdentity.width}x${requestedIdentity.height}."
                )

                var frameCounter = 0
                fun handleAcquiredImage(image: android.media.Image) {
                    val imageOwner = CloseOnce { image.close() }
                    var transferredToRing = false
                    captureAttempts.imageReaderAcquired()
                    ringBuffer.recordImageAcquired(generationId = resetGeneration)
                    try {
                        if (requestedIdentity.bufferFormat == ImageFormat.RAW_SENSOR &&
                            (frameCounter < 3 || frameCounter % 30 == 0)
                        ) {
                            Log.i(
                                tag,
                                "RAW_SENSOR ImageReader image arrived timestamp=${image.timestamp} size=${image.width}x${image.height} format=${image.format}"
                            )
                        }
                        if (resetGeneration != pipelineGeneration) {
                            staleFramesDropped++
                            return
                        }
                        RawPreviewCadenceDiagnostics.sourceArrived(
                            source = ViewfinderEffectiveSource.fromImageFormat(requestedIdentity.bufferFormat),
                            generation = resetGeneration,
                            sensorTimestampNs = image.timestamp
                        )
                        com.bncam.core.debug.RawPreviewFirstActivationTrace.rawFrameAvailable(
                            source = ViewfinderEffectiveSource.fromImageFormat(requestedIdentity.bufferFormat).name,
                            generation = resetGeneration,
                            sensorTimestampNs = image.timestamp,
                            origin = "authoritative_raw_image_reader_reset"
                        )
                        if (frameCounter == 0 &&
                            (requestedIdentity.bufferFormat == ImageFormat.RAW10 ||
                                requestedIdentity.bufferFormat == ImageFormat.RAW_SENSOR)
                        ) {
                            com.bncam.core.runtime.RawPipelineRuntimeProfileFactory.refinePublishedLayoutFromImage(
                                image = image,
                                sessionGeneration = resetGeneration
                            )
                        }
                        transferredToRing = ringBuffer.addImage(
                            image = image,
                            generationId = resetGeneration,
                            expectedFormat = requestedIdentity.bufferFormat
                        )
                        if (transferredToRing) {
                            markViewfinderProducerFrameReady(
                                ViewfinderEffectiveSource.fromImageFormat(requestedIdentity.bufferFormat),
                                resetGeneration,
                                image.timestamp
                            )
                            if (requestedIdentity.bufferFormat == ImageFormat.YUV_420_888) {
                                scheduleEnabledYuvAnalysis(
                                    timestampNs = image.timestamp,
                                    generation = resetGeneration,
                                    expectedFormat = requestedIdentity.bufferFormat,
                                    deviceRotation = currentMlAnalysisRotationDegrees()
                                )
                            }
                            scheduleFocusConfidenceAnalysis(
                                generation = resetGeneration,
                                expectedFormat = requestedIdentity.bufferFormat
                            )
                            offerRawPreviewImage(image.timestamp, resetGeneration)
                        }
                        if (!transferredToRing) {
                            Log.e(
                                tag,
                                "FRAME_REJECTED profile=${requestedIdentity.requestedProfileId} " +
                                    "requested=${formatName(requestedIdentity.bufferFormat)} actual=${formatName(image.format)} " +
                                    "generation=$resetGeneration timestamp=${image.timestamp} bufferSize=${ringBuffer.completeFrameCount()}"
                            )
                        }
                    } catch (e: Exception) {
                        Log.e(tag, "Failed to consume ImageReader image", e)
                    } finally {
                        if (!transferredToRing) {
                            try { imageOwner.close() } catch (closeError: Exception) {
                                Log.w(tag, "Image.close failed", closeError)
                            }
                        }
                        captureAttempts.imageReaderClosed()
                        frameCounter++
                    }
                }

                imageReader?.setOnImageAvailableListener({ reader ->
                    val callbackStartNs = android.os.SystemClock.elapsedRealtimeNanos()
                    var drainedImages = 0
                    captureAttempts.imageAvailable()
                    try {
                        while (true) {
                            val image = try {
                                reader.acquireNextImage()
                            } catch (e: IllegalStateException) {
                                val maxImagesExhausted = ringBuffer.producerHeadroom() <= 0
                                ringBuffer.recordImageReaderAcquireFailure(
                                    generationId = resetGeneration,
                                    maxImagesExhausted = maxImagesExhausted
                                )
                                Log.e(
                                    tag,
                                    "ImageReader acquireNextImage IllegalStateException " +
                                        "maxImages=${reader.maxImages} maxImagesExhausted=$maxImagesExhausted " +
                                        "pressure=${ringBuffer.imageReaderPressureDiagnostics()}",
                                    e
                                )
                                recordRawSessionOutputDiagnostic(
                                    "IMAGE_READER_ACQUIRE_FAILED",
                                    "generation=$resetGeneration;maxImages=${reader.maxImages};" +
                                        "maxImagesExhausted=$maxImagesExhausted;${rawRingPressureSummary()}"
                                )
                                null
                            } catch (e: Exception) {
                                ringBuffer.recordImageReaderAcquireFailure(generationId = resetGeneration)
                                Log.w(tag, "ImageReader acquire failed", e)
                                null
                            } ?: break
                            drainedImages++
                            handleAcquiredImage(image)
                        }
                    } finally {
                        ringBuffer.recordImageReaderDrain(
                            generationId = resetGeneration,
                            callbackServiceNs = android.os.SystemClock.elapsedRealtimeNanos() - callbackStartNs,
                            drainedImages = drainedImages
                        )
                    }
                }, backgroundHandler)

                val sessionHandshakeStartedNs = android.os.SystemClock.elapsedRealtimeNanos()
                val configured = awaitCaptureSessionConfiguration(
                    camera = camera,
                    previewSurface = previewSurface,
                    reason = "PIPELINE_PRODUCER_RESET:${decision.reasons.joinToString("+")}"
                )
                val sessionHandshakeWallMs =
                    (android.os.SystemClock.elapsedRealtimeNanos() - sessionHandshakeStartedNs) / 1_000_000.0

                val oldSessionClosed = oldSessionCloseTicket?.closeBarrier?.isCompleted ?: true
                if (oldSessionClosed) {
                    closeRetiredImageReader(retiringImageReader, "physical_pipeline_reset_acknowledged")
                    closeRetiredImageReader(retiringCustomRawReader, "physical_pipeline_reset_acknowledged")
                    imageReaderClosed = retiringImageReader != null
                } else if (oldSessionCloseTicket != null) {
                    // Keep retiring outputs alive until the exact old-session onClosed callback.
                    // A configured replacement is already the active producer and must not be torn
                    // down merely because the old close acknowledgement arrives later.
                    retireReadersWhenSessionCloses(
                        oldSessionCloseTicket,
                        listOf(retiringImageReader, retiringCustomRawReader),
                        "physical_pipeline_reset_late_ack"
                    )
                }

                if (!configured) {
                    Log.e(
                        tag,
                        "PIPELINE_RESET_SESSION_NOT_READY generation=$resetGeneration " +
                            "configured=false previousSessionClosed=$oldSessionClosed"
                    )
                    recordPipelineResetLatency(
                        requestedFormat = newFormat,
                        reason = reason,
                        identityWallMs = identityWallMs,
                        sessionHandshakeWallMs = sessionHandshakeWallMs,
                        totalWallMs = transitionElapsedMs(),
                        outcome = "PRODUCER_SESSION_FAILED"
                    )
                    return false
                }

                writePipelineLifecycleDebug(
                    event = "PIPELINE_RESET_END",
                    decision = decision,
                    extra = "oldGeneration=$oldGeneration\nnewGeneration=$resetGeneration\n" +
                        "imageReaderClosed=$imageReaderClosed\nbufferPoolCleared=true\n" +
                        "previousSessionClosed=$oldSessionClosed\n" +
                        "activePipelineAfterReset=${activePipelineIdentity?.toDebugString() ?: "none"}"
                )
                Log.w(
                    tag,
                    "PIPELINE_RESET_END generation=$resetGeneration configured=true " +
                        "previousSessionClosed=$oldSessionClosed retirement=${if (oldSessionClosed) "complete" else "deferred"}"
                )
                recordPipelineResetLatency(
                    requestedFormat = newFormat,
                    reason = reason,
                    identityWallMs = identityWallMs,
                    sessionHandshakeWallMs = sessionHandshakeWallMs,
                    totalWallMs = transitionElapsedMs(),
                    outcome = "PRODUCER_SESSION_READY"
                )
                return true
            } catch (e: Exception) {
                Log.e(tag, "PIPELINE_RESET_FATAL generation=$resetGeneration", e)
                return false
            }
        }


        private fun normalizeCam2Setting(value: String): String =
            value.trim().lowercase(Locale.US).replace("_", " ").replace("-", " ")

        private fun noiseReductionModeFromSetting(value: String): Int = when (normalizeCam2Setting(value)) {
            "fast" -> CaptureRequest.NOISE_REDUCTION_MODE_FAST
            "high quality", "highquality" -> CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY
            "minimal" -> CaptureRequest.NOISE_REDUCTION_MODE_MINIMAL
            "zsl", "zero shutter lag", "zero shutterlag" -> CaptureRequest.NOISE_REDUCTION_MODE_ZERO_SHUTTER_LAG
            else -> CaptureRequest.NOISE_REDUCTION_MODE_OFF
        }

        private fun edgeModeFromSetting(value: String): Int = when (normalizeCam2Setting(value)) {
            "fast" -> CaptureRequest.EDGE_MODE_FAST
            "high quality", "highquality" -> CaptureRequest.EDGE_MODE_HIGH_QUALITY
            "zsl", "zero shutter lag", "zero shutterlag" -> CaptureRequest.EDGE_MODE_ZERO_SHUTTER_LAG
            else -> CaptureRequest.EDGE_MODE_OFF
        }

        private fun hotPixelModeFromSetting(value: String): Int = when (normalizeCam2Setting(value)) {
            "fast" -> CaptureRequest.HOT_PIXEL_MODE_FAST
            "high quality", "highquality" -> CaptureRequest.HOT_PIXEL_MODE_HIGH_QUALITY
            else -> CaptureRequest.HOT_PIXEL_MODE_OFF
        }

        private fun tonemapModeFromSetting(value: String): Int = when (normalizeCam2Setting(value)) {
            "high quality", "highquality" -> CaptureRequest.TONEMAP_MODE_HIGH_QUALITY
            "contrast curve", "contrastcurve" -> CaptureRequest.TONEMAP_MODE_CONTRAST_CURVE
            // Camera2 exposes no TONEMAP_MODE_OFF. FAST is only the API carrier for the
            // explicitly documented UI contract "no custom curve"; persistence remains Off.
            "off", "fast" -> CaptureRequest.TONEMAP_MODE_FAST
            else -> CaptureRequest.TONEMAP_MODE_FAST
        }

        private fun resolveSupportedMode(
            setting: String,
            requested: Int,
            availableModes: IntArray?,
            fallbackOrder: List<Int>
        ): Int? {
            val supported = availableModes?.distinct().orEmpty()
            if (requested in supported) return requested

            val fallback = fallbackOrder.firstOrNull { it in supported } ?: supported.firstOrNull()
            if (fallback == null) {
                Log.w(
                    tag,
                    "$setting requested Camera2 mode $requested, but this lens exposes no supported modes; " +
                        "leaving the request key unset."
                )
                return null
            }

            Log.w(
                tag,
                "$setting requested Camera2 mode $requested but this lens supports " +
                    "${supported.joinToString(prefix = "[", postfix = "]")}; applying adaptive fallback=$fallback."
            )
            return fallback
        }

        private fun noiseReductionFallbacks(requested: Int): List<Int> = when (requested) {
            CaptureRequest.NOISE_REDUCTION_MODE_OFF -> listOf(
                CaptureRequest.NOISE_REDUCTION_MODE_OFF,
                CaptureRequest.NOISE_REDUCTION_MODE_MINIMAL,
                CaptureRequest.NOISE_REDUCTION_MODE_FAST
            )
            CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY -> listOf(
                CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY,
                CaptureRequest.NOISE_REDUCTION_MODE_FAST,
                CaptureRequest.NOISE_REDUCTION_MODE_MINIMAL,
                CaptureRequest.NOISE_REDUCTION_MODE_OFF
            )
            CaptureRequest.NOISE_REDUCTION_MODE_ZERO_SHUTTER_LAG -> listOf(
                CaptureRequest.NOISE_REDUCTION_MODE_ZERO_SHUTTER_LAG,
                CaptureRequest.NOISE_REDUCTION_MODE_FAST,
                CaptureRequest.NOISE_REDUCTION_MODE_MINIMAL,
                CaptureRequest.NOISE_REDUCTION_MODE_OFF
            )
            else -> listOf(
                CaptureRequest.NOISE_REDUCTION_MODE_FAST,
                CaptureRequest.NOISE_REDUCTION_MODE_MINIMAL,
                CaptureRequest.NOISE_REDUCTION_MODE_OFF,
                CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY
            )
        }

        private fun edgeFallbacks(requested: Int): List<Int> = when (requested) {
            CaptureRequest.EDGE_MODE_OFF -> listOf(
                CaptureRequest.EDGE_MODE_OFF,
                CaptureRequest.EDGE_MODE_FAST
            )
            CaptureRequest.EDGE_MODE_HIGH_QUALITY -> listOf(
                CaptureRequest.EDGE_MODE_HIGH_QUALITY,
                CaptureRequest.EDGE_MODE_FAST,
                CaptureRequest.EDGE_MODE_OFF
            )
            CaptureRequest.EDGE_MODE_ZERO_SHUTTER_LAG -> listOf(
                CaptureRequest.EDGE_MODE_ZERO_SHUTTER_LAG,
                CaptureRequest.EDGE_MODE_FAST,
                CaptureRequest.EDGE_MODE_OFF
            )
            else -> listOf(
                CaptureRequest.EDGE_MODE_FAST,
                CaptureRequest.EDGE_MODE_OFF,
                CaptureRequest.EDGE_MODE_HIGH_QUALITY
            )
        }

        private fun antibandingModeFromSetting(value: String): Int = when (normalizeCam2Setting(value).replace(" ", "")) {
            "50hz" -> CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_50HZ
            "60hz" -> CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_60HZ
            "auto" -> CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_AUTO
            else -> CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_OFF
        }

        private fun cam2ModeName(value: Int): String = when (value) {
            CaptureRequest.NOISE_REDUCTION_MODE_OFF -> "OFF"
            CaptureRequest.NOISE_REDUCTION_MODE_FAST -> "FAST"
            CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY -> "HIGH_QUALITY"
            CaptureRequest.NOISE_REDUCTION_MODE_MINIMAL -> "MINIMAL"
            CaptureRequest.NOISE_REDUCTION_MODE_ZERO_SHUTTER_LAG -> "ZERO_SHUTTER_LAG"
            else -> value.toString()
        }

        private fun tonemapModeName(value: Int): String = when (value) {
            CaptureRequest.TONEMAP_MODE_FAST -> "FAST"
            CaptureRequest.TONEMAP_MODE_HIGH_QUALITY -> "HIGH_QUALITY"
            CaptureRequest.TONEMAP_MODE_CONTRAST_CURVE -> "CONTRAST_CURVE"
            else -> value.toString()
        }

        /**
         * Stabilization keys advertised as Camera2 session keys should be fixed before the
         * capture session is configured. This gives the HAL time to arm the mechanical OIS
         * controller and prevents a competing digital stabilization mode from being selected.
         * Per-physical OIS fallback remains a repeating-request control; only the logical
         * standard OIS route is written as a standard session parameter.
         */
        private fun applyOpticalStabilizationSessionParameters(
            builder: CaptureRequest.Builder,
            characteristics: CameraCharacteristics,
            opticalStabilization: Boolean
        ): Boolean {
            val sessionKeys = characteristics.availableSessionKeys
            if (sessionKeys.isEmpty()) return false

            val decision = activeOisDecision
            var applied = false

            if (sessionKeys.contains(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE)) {
                runCatching {
                    builder.set(
                        CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                        CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF
                    )
                }.onSuccess {
                    applied = true
                }.onFailure { throwable ->
                    Log.w(
                        "OisResolver",
                        "HAL advertised video stabilization as a session key but rejected OFF",
                        throwable
                    )
                }
            }

            if (sessionKeys.contains(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE)) {
                val sessionOisMode = when {
                    opticalStabilization && decision?.appliedMethod == OisDecision.OisMethod.LOGICAL_OIS ->
                        CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON
                    !opticalStabilization || decision?.appliedMethod == OisDecision.OisMethod.FAILED ->
                        CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF
                    else -> null
                }
                if (sessionOisMode != null) {
                    runCatching {
                        builder.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, sessionOisMode)
                    }.onSuccess {
                        applied = true
                    }.onFailure { throwable ->
                        Log.w(
                            "OisResolver",
                            "HAL advertised OIS as a session key but rejected mode=$sessionOisMode",
                            throwable
                        )
                    }
                }
            }

            if (applied) {
                Log.i(
                    "OisResolver",
                    "OIS_SESSION_ARM method=${decision?.appliedMethod ?: "NONE"} " +
                        "requested=$opticalStabilization " +
                        "oisSessionKey=${sessionKeys.contains(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE)} " +
                        "videoSessionKey=${sessionKeys.contains(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE)}"
                )
            }
            return applied
        }

        @SuppressLint("NewApi")
        private fun applyOisDataTelemetryRequest(
            builder: CaptureRequest.Builder,
            logicalCharacteristics: CameraCharacteristics,
            decision: OisDecision,
            opticalStabilization: Boolean
        ) {
            if (!opticalStabilization) return

            var logicalRequested = false
            var physicalRequested = false
            val logicalModes = logicalCharacteristics.get(
                CameraCharacteristics.STATISTICS_INFO_AVAILABLE_OIS_DATA_MODES
            )
            if (logicalModes?.contains(CaptureRequest.STATISTICS_OIS_DATA_MODE_ON) == true) {
                runCatching {
                    builder.set(
                        CaptureRequest.STATISTICS_OIS_DATA_MODE,
                        CaptureRequest.STATISTICS_OIS_DATA_MODE_ON
                    )
                }.onSuccess {
                    logicalRequested = true
                }.onFailure { throwable ->
                    Log.w("OisResolver", "Logical OIS sample telemetry request rejected", throwable)
                }
            }

            val physicalId = decision.physicalCameraId
            if (!physicalId.isNullOrBlank()) {
                val physicalModes = runCatching {
                    cameraManager.getCameraCharacteristics(physicalId).get(
                        CameraCharacteristics.STATISTICS_INFO_AVAILABLE_OIS_DATA_MODES
                    )
                }.getOrNull()
                val physicalKeyWritable = logicalCharacteristics.availablePhysicalCameraRequestKeys
                    ?.contains(CaptureRequest.STATISTICS_OIS_DATA_MODE) == true
                if (
                    physicalKeyWritable &&
                    physicalModes?.contains(CaptureRequest.STATISTICS_OIS_DATA_MODE_ON) == true
                ) {
                    runCatching {
                        builder.setPhysicalCameraKey(
                            CaptureRequest.STATISTICS_OIS_DATA_MODE,
                            CaptureRequest.STATISTICS_OIS_DATA_MODE_ON,
                            physicalId
                        )
                    }.onSuccess {
                        physicalRequested = true
                    }.onFailure { throwable ->
                        Log.w(
                            "OisResolver",
                            "Physical OIS sample telemetry request rejected for camera $physicalId",
                            throwable
                        )
                    }
                }
            }

            Log.i(
                "OisResolver",
                "OIS_DATA_TELEMETRY logicalRequested=$logicalRequested " +
                    "physicalRequested=$physicalRequested physical=${physicalId ?: "none"} " +
                    "logicalModes=${logicalModes?.joinToString() ?: "unavailable"}"
            )
        }

        private fun applyViewfinderCam2ApiSettings(
            requestBuilder: CaptureRequest.Builder,
            characteristics: CameraCharacteristics,
            cameraId: String,
            activeLensId: String,
            opticalStabilization: Boolean,
            hotPixelMode: String,
            noiseReductionHint: String,
            edgeModeHint: String,
            tonemapHint: String,
            antiBanding: String
        ) {
            val oisDecision = OisResolver.resolve(
                cameraManager = cameraManager,
                activeLensId = activeLensId,
                userRequestedOis = opticalStabilization,
                lensRole = synchronized(pipelineLock) { activePipelineIdentity?.lensRole },
                openedCameraId = cameraId,
                verifiedPhysicalCameraId = synchronized(pipelineLock) {
                    activePipelineIdentity?.physicalCameraId
                },
                directRouteWasRuntimeProbed = synchronized(pipelineLock) {
                    activePipelineIdentity?.cameraRouteKind == CameraRouteKind.PROBED_DIRECT
                }
            )

            activeOisDecision = oisDecision
            applyOisDataTelemetryRequest(
                builder = requestBuilder,
                logicalCharacteristics = characteristics,
                decision = oisDecision,
                opticalStabilization = opticalStabilization
            )

            val settingsRepo = SettingsRepository(context)
            val statusText = if (opticalStabilization) {
                when (oisDecision.appliedMethod) {
                    OisDecision.OisMethod.PHYSICAL_OIS -> "Physical OIS active"
                    OisDecision.OisMethod.LOGICAL_OIS -> "Standard OIS active"
                    OisDecision.OisMethod.PREVIEW_STAB -> "Preview stabilization active"
                    OisDecision.OisMethod.VIDEO_STAB -> "Video stabilization active"
                    OisDecision.OisMethod.VENDOR -> "Vendor stabilization active"
                    OisDecision.OisMethod.FAILED -> "Stabilization probing failed"
                }
            } else {
                "Disabled"
            }
            sessionTransitionScope.launch {
                settingsRepo.setStabilizationStatus(statusText)
            }

            // Apply OIS or Video stabilization based on resolved method
            when (oisDecision.appliedMethod) {
                OisDecision.OisMethod.PHYSICAL_OIS -> {
                    if (oisDecision.physicalCameraId != null) {
                        requestBuilder.setPhysicalCameraKey(
                            CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
                            CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON,
                            oisDecision.physicalCameraId
                        )
                        Log.i("OisResolver", "Applied physical OIS ON to camera ${oisDecision.physicalCameraId}")
                    }
                    requestBuilder.set(
                        CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                        CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF
                    )
                }
                OisDecision.OisMethod.LOGICAL_OIS -> {
                    requestBuilder.set(
                        CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
                        CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON
                    )
                    requestBuilder.set(
                        CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                        CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF
                    )
                }
                OisDecision.OisMethod.PREVIEW_STAB,
                OisDecision.OisMethod.VIDEO_STAB -> {
                    // Defensive fail-closed handling for stale/legacy decisions. The Optical
                    // Stabilization setting must never activate an EIS mode that crops framing.
                    requestBuilder.set(
                        CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                        CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF
                    )
                    requestBuilder.set(
                        CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
                        CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF
                    )
                    Log.w(
                        "OisResolver",
                        "Rejected digital stabilization fallback for Optical Stabilization setting: " +
                            oisDecision.appliedMethod.name
                    )
                }
                OisDecision.OisMethod.VENDOR -> {
                    val requestKeys = characteristics.availableCaptureRequestKeys
                    val selectedVendorKeyName = oisDecision.vendorOpticalKeyName
                    val activeKey = requestKeys.firstOrNull { key ->
                        selectedVendorKeyName != null && key.name == selectedVendorKeyName
                    }
                    if (activeKey != null) {
                        val type = OisResolver.getCaptureRequestKeyType(activeKey)
                        try {
                            @Suppress("UNCHECKED_CAST")
                            fun <T> setTypedVendorKey(value: T) {
                                requestBuilder.set(activeKey as CaptureRequest.Key<T>, value)
                            }

                            when {
                                type == Byte::class.java || type == Byte::class.javaObjectType -> {
                                    setTypedVendorKey(1.toByte())
                                    Log.i("OisResolver", "Applied vendor key: ${activeKey.name} with value: 1 (Byte)")
                                }
                                type == Int::class.java || type == Int::class.javaObjectType -> {
                                    setTypedVendorKey(1)
                                    Log.i("OisResolver", "Applied vendor key: ${activeKey.name} with value: 1 (Int)")
                                }
                                type == Boolean::class.java || type == Boolean::class.javaObjectType -> {
                                    setTypedVendorKey(true)
                                    Log.i("OisResolver", "Applied vendor key: ${activeKey.name} with value: true (Boolean)")
                                }
                                type == Long::class.java || type == Long::class.javaObjectType -> {
                                    setTypedVendorKey(1L)
                                    Log.i("OisResolver", "Applied vendor key: ${activeKey.name} with value: 1 (Long)")
                                }
                                else -> {
                                    Log.w("OisResolver", "Unknown vendor key type for ${activeKey.name}: $type - skipping write")
                                }
                            }
                        } catch (e: Exception) {
                            Log.e("OisResolver", "Failed to write vendor key ${activeKey.name}", e)
                        }
                    }

                    requestBuilder.set(
                        CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                        CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF
                    )
                }
                OisDecision.OisMethod.FAILED -> {
                    requestBuilder.set(
                        CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
                        CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF
                    )
                    requestBuilder.set(
                        CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                        CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF
                    )
                }
            }

            val requestedHotPixel = hotPixelModeFromSetting(hotPixelMode)
            val resolvedHotPixel = resolveSupportedMode(
                setting = "Hot Pixel '$hotPixelMode'",
                requested = requestedHotPixel,
                availableModes = characteristics.get(CameraCharacteristics.HOT_PIXEL_AVAILABLE_HOT_PIXEL_MODES),
                fallbackOrder = when (requestedHotPixel) {
                    CaptureRequest.HOT_PIXEL_MODE_OFF -> listOf(
                        CaptureRequest.HOT_PIXEL_MODE_OFF,
                        CaptureRequest.HOT_PIXEL_MODE_FAST,
                        CaptureRequest.HOT_PIXEL_MODE_HIGH_QUALITY
                    )
                    CaptureRequest.HOT_PIXEL_MODE_HIGH_QUALITY -> listOf(
                        CaptureRequest.HOT_PIXEL_MODE_HIGH_QUALITY,
                        CaptureRequest.HOT_PIXEL_MODE_FAST,
                        CaptureRequest.HOT_PIXEL_MODE_OFF
                    )
                    else -> listOf(
                        CaptureRequest.HOT_PIXEL_MODE_FAST,
                        CaptureRequest.HOT_PIXEL_MODE_OFF,
                        CaptureRequest.HOT_PIXEL_MODE_HIGH_QUALITY
                    )
                }
            )
            resolvedHotPixel?.let { requestBuilder.set(CaptureRequest.HOT_PIXEL_MODE, it) }

            val requestedNoiseReduction = noiseReductionModeFromSetting(noiseReductionHint)
            val resolvedNoiseReduction = resolveSupportedMode(
                setting = "Noise Reduction '$noiseReductionHint'",
                requested = requestedNoiseReduction,
                availableModes = characteristics.get(CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES),
                fallbackOrder = noiseReductionFallbacks(requestedNoiseReduction)
            )
            resolvedNoiseReduction?.let { requestBuilder.set(CaptureRequest.NOISE_REDUCTION_MODE, it) }

            val requestedEdge = edgeModeFromSetting(edgeModeHint)
            val resolvedEdge = resolveSupportedMode(
                setting = "Edge '$edgeModeHint'",
                requested = requestedEdge,
                availableModes = characteristics.get(CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES),
                fallbackOrder = edgeFallbacks(requestedEdge)
            )
            resolvedEdge?.let { requestBuilder.set(CaptureRequest.EDGE_MODE, it) }

            val requestedTonemap = tonemapModeFromSetting(tonemapHint)
            val resolvedTonemap = resolveSupportedMode(
                setting = "Tonemap '$tonemapHint'",
                requested = requestedTonemap,
                availableModes = characteristics.get(CameraCharacteristics.TONEMAP_AVAILABLE_TONE_MAP_MODES),
                fallbackOrder = when (requestedTonemap) {
                    CaptureRequest.TONEMAP_MODE_CONTRAST_CURVE -> listOf(
                        CaptureRequest.TONEMAP_MODE_CONTRAST_CURVE,
                        CaptureRequest.TONEMAP_MODE_FAST,
                        CaptureRequest.TONEMAP_MODE_HIGH_QUALITY
                    )
                    CaptureRequest.TONEMAP_MODE_HIGH_QUALITY -> listOf(
                        CaptureRequest.TONEMAP_MODE_HIGH_QUALITY,
                        CaptureRequest.TONEMAP_MODE_FAST,
                        CaptureRequest.TONEMAP_MODE_CONTRAST_CURVE
                    )
                    else -> listOf(
                        CaptureRequest.TONEMAP_MODE_FAST,
                        CaptureRequest.TONEMAP_MODE_HIGH_QUALITY,
                        CaptureRequest.TONEMAP_MODE_CONTRAST_CURVE
                    )
                }
            )
            resolvedTonemap?.let { requestBuilder.set(CaptureRequest.TONEMAP_MODE, it) }
            if (resolvedTonemap == CaptureRequest.TONEMAP_MODE_CONTRAST_CURVE) {
                val identityCurve = floatArrayOf(0.0f, 0.0f, 1.0f, 1.0f)
                requestBuilder.set(
                    CaptureRequest.TONEMAP_CURVE,
                    TonemapCurve(identityCurve, identityCurve, identityCurve)
                )
            } else {
                requestBuilder.set(CaptureRequest.TONEMAP_CURVE, null)
            }

            val requestedAntibanding = antibandingModeFromSetting(antiBanding)
            val resolvedAntibanding = resolveSupportedMode(
                setting = "Anti-banding '$antiBanding'",
                requested = requestedAntibanding,
                availableModes = characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_ANTIBANDING_MODES),
                fallbackOrder = when (requestedAntibanding) {
                    CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_50HZ -> listOf(
                        CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_50HZ,
                        CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_AUTO,
                        CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_OFF,
                        CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_60HZ
                    )
                    CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_60HZ -> listOf(
                        CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_60HZ,
                        CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_AUTO,
                        CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_OFF,
                        CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_50HZ
                    )
                    CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_AUTO -> listOf(
                        CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_AUTO,
                        CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_50HZ,
                        CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_60HZ,
                        CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_OFF
                    )
                    else -> listOf(
                        CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_OFF,
                        CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_AUTO,
                        CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_50HZ,
                        CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_60HZ
                    )
                }
            )
            if (activeResolvedAntibandingMode != resolvedAntibanding) {
                resetRawFlickerAuthority(
                    reason = "ANTIBANDING_MODE_CHANGED:${activeResolvedAntibandingMode ?: "UNSET"}->${resolvedAntibanding ?: "UNSET"}"
                )
            }
            activeResolvedAntibandingMode = resolvedAntibanding
            resolvedAntibanding?.let {
                requestBuilder.set(CaptureRequest.CONTROL_AE_ANTIBANDING_MODE, it)
            }

            Log.i(
                tag,
                "Cam2API viewfinder settings applied cameraId=$cameraId " +
                        "requestedOis=$opticalStabilization resolvedMethod=${oisDecision.appliedMethod.name} applied=${oisDecision.applied} " +
                        "hotPixel='$hotPixelMode'->${resolvedHotPixel ?: "UNSET"} " +
                        "noise='$noiseReductionHint'->${resolvedNoiseReduction?.let(::cam2ModeName) ?: "UNSET"} " +
                        "edge='$edgeModeHint'->${resolvedEdge ?: "UNSET"} " +
                        "tonemap='$tonemapHint'->${resolvedTonemap?.let(::tonemapModeName) ?: "UNSET"} " +
                        "tonemapContract=${if (normalizeCam2Setting(tonemapHint) == "off") "NO_CUSTOM_CURVE_API_FAST_CARRIER" else "EXPLICIT_MODE"} " +
                        "antibanding='$antiBanding'->${resolvedAntibanding ?: "UNSET"}"
            )
        }

        private fun resolveConfiguredIdleAfMode(cameraId: String?): Int {
            val available = cameraId?.let { id ->
                runCatching {
                    cameraManager.getCameraCharacteristics(id)
                        .get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)
                        ?.toSet()
                }.getOrNull()
            }.orEmpty()

            val preferred = if (configuredFocusMode == "Tap-to-Focus") {
                CaptureRequest.CONTROL_AF_MODE_AUTO
            } else {
                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
            }
            val fallbacks = if (configuredFocusMode == "Tap-to-Focus") {
                listOf(
                    CaptureRequest.CONTROL_AF_MODE_AUTO,
                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE,
                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO,
                    CaptureRequest.CONTROL_AF_MODE_OFF
                )
            } else {
                listOf(
                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE,
                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO,
                    CaptureRequest.CONTROL_AF_MODE_AUTO,
                    CaptureRequest.CONTROL_AF_MODE_OFF
                )
            }
            if (available.isEmpty()) return preferred
            return fallbacks.firstOrNull { it in available } ?: CaptureRequest.CONTROL_AF_MODE_OFF
        }

        private data class WarmRepeatingRequestTemplate(
            val template: Int,
            val captureIntent: Int,
            val reason: String
        )

        /**
         * Camera2 only honors per-physical capture controls on requests created with the
         * physical-camera request overload. Keep this centralized so every hidden-physical
         * pipeline (repeating, session parameters and one-shot stills) is built from the same
         * legal request contract. Directly opened cameras continue to use the normal builder.
         */
        private fun createPipelineCaptureRequestBuilder(
            camera: CameraDevice,
            template: Int
        ): CaptureRequest.Builder {
            val physicalCameraId = synchronized(pipelineLock) {
                activePipelineIdentity?.physicalCameraId
            }
            if (physicalCameraId.isNullOrBlank()) {
                return camera.createCaptureRequest(template)
            }

            val advertisedPhysicalIds = runCatching {
                cameraManager.getCameraCharacteristics(camera.id).physicalCameraIds
            }.getOrElse { throwable ->
                throw IllegalStateException(
                    "Cannot resolve physical request contract for logical camera ${camera.id}",
                    throwable
                )
            }
            check(physicalCameraId in advertisedPhysicalIds) {
                "Active physical camera $physicalCameraId is not owned by logical camera ${camera.id}"
            }

            Log.i(
                tag,
                "Creating physical-aware CaptureRequest template=$template logical=${camera.id} " +
                    "physical=$physicalCameraId"
            )
            return camera.createCaptureRequest(template, setOf(physicalCameraId))
        }

        /**
         * The warm RAW ImageReader is not a display-only preview stream: its frames are the actual
         * near-ZSL still masters. Never seed that producer from TEMPLATE_PREVIEW, because vendor
         * HALs may legitimately optimize a preview-intent sensor mode around the display aspect
         * and leave the remainder of a larger RAW allocation inactive.
         *
         * Prefer the application-operated ZSL template when Camera2 guarantees it through a
         * reprocessing capability. Otherwise use the universally supported still-capture template
         * for RAW. YUV keeps the normal preview template.
         */
        private fun resolveWarmRepeatingRequestTemplate(
            camera: CameraDevice,
            bufferFormat: Int
        ): WarmRepeatingRequestTemplate {
            val isRaw = bufferFormat == ImageFormat.RAW10 || bufferFormat == ImageFormat.RAW_SENSOR
            if (!isRaw) {
                return WarmRepeatingRequestTemplate(
                    template = CameraDevice.TEMPLATE_PREVIEW,
                    captureIntent = CaptureRequest.CONTROL_CAPTURE_INTENT_PREVIEW,
                    reason = "YUV_DISPLAY_PREVIEW"
                )
            }

            val capabilities = runCatching {
                cameraManager.getCameraCharacteristics(camera.id)
                    .get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
                    ?.toSet()
            }.getOrNull().orEmpty()
            val guaranteedApplicationZsl =
                capabilities.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_PRIVATE_REPROCESSING) ||
                    capabilities.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_YUV_REPROCESSING)

            return if (guaranteedApplicationZsl) {
                WarmRepeatingRequestTemplate(
                    template = CameraDevice.TEMPLATE_ZERO_SHUTTER_LAG,
                    captureIntent = CaptureRequest.CONTROL_CAPTURE_INTENT_ZERO_SHUTTER_LAG,
                    reason = "RAW_NEAR_ZSL_APPLICATION_ZSL_TEMPLATE"
                )
            } else {
                WarmRepeatingRequestTemplate(
                    template = CameraDevice.TEMPLATE_STILL_CAPTURE,
                    captureIntent = CaptureRequest.CONTROL_CAPTURE_INTENT_STILL_CAPTURE,
                    reason = "RAW_NEAR_ZSL_FULL_QUALITY_STILL_TEMPLATE"
                )
            }
        }

        private fun createCaptureSession(
            camera: CameraDevice,
            previewSurface: Surface?,
            sessionSettings: SessionRequestSettingsSnapshot,
            reason: String = "UNSPECIFIED_SESSION_CREATE",
            onSessionReady: ((Boolean) -> Unit)? = null
        ) {
            val sessionGeneration = pipelineGeneration
            val startupSequenceId = activeCameraStartupSequenceId
            startupSessionRequestCount += 1
            lifetimeSessionRequestCount.incrementAndGet()
            val sessionRequestIndex = startupSessionRequestCount
            Log.i(
                previewDiagnosticsTag,
                "event=CAPTURE_SESSION_REQUESTED startupSequence=$startupSequenceId " +
                    "sessionRequest=$sessionRequestIndex deviceOpens=$startupCameraOpenCount " +
                    "generation=$sessionGeneration previewAttached=${previewSurface != null} reason=$reason"
            )
            val sessionEpoch = synchronized(pipelineLock) {
                sessionConfigurationEpoch += 1L
                sessionConfigurationEpoch
            }
            try {
                val sessionBufferFormat = synchronized(pipelineLock) {
                    activePipelineIdentity?.bufferFormat ?: activeZslFormat
                }
                val warmTemplate = resolveWarmRepeatingRequestTemplate(camera, sessionBufferFormat)
                val requestBuilder = createPipelineCaptureRequestBuilder(camera, warmTemplate.template)
                // Capture the HAL/template AE region before BnCam applies a standard metering mode.
                // Auto restores this exact initial state for preview and still requests.
                initialAeMeteringRegions = requestBuilder
                    .get(CaptureRequest.CONTROL_AE_REGIONS)
                    ?.copyOf()
                initialAeMeteringGeneration = sessionGeneration
                requestBuilder.set(CaptureRequest.CONTROL_CAPTURE_INTENT, warmTemplate.captureIntent)
                Log.i(
                    tag,
                    "WARM_REPEATING_REQUEST_CONTRACT format=${formatName(sessionBufferFormat)} " +
                        "template=${warmTemplate.template} captureIntent=${warmTemplate.captureIntent} " +
                        "reason=${warmTemplate.reason} generation=$sessionGeneration"
                )
                previewSurface?.let { requestBuilder.addTarget(it) }

                // Dwing direct de opgeslagen hardware metering af bij de start van de sessie
                applyMeteringPolicy(requestBuilder)

                // All buffer modes, including RAW_SENSOR, must be real warm-buffer routes.
                // Capture the exact reader identity used by this session; mutable manager fields
                // may already point at a replacement before this session finally dispatches onClosed().
                val sessionCanonicalReader = imageReader
                sessionCanonicalReader?.surface?.let { requestBuilder.addTarget(it) }
                // A custom RAW preview reader is display-only and exists only while a real
                // preview Surface is attached. UI navigation itself never rebuilds this session.
                val customRawPreviewSurface = if (previewSurface != null) {
                    prepareCustomRawPreviewReader(
                        cameraId = activePipelineIdentity?.physicalCameraId ?: camera.id,
                        generation = sessionGeneration
                    )
                } else {
                    // A transition caller may still be retiring the previous display session.
                    // Do not close a reader whose Surface may still be referenced by that session;
                    // retirement is completed only after its identity-bound onClosed barrier.
                    null
                }
                customRawPreviewSurface?.let { requestBuilder.addTarget(it) }
                val sessionCustomRawReader = if (customRawPreviewSurface != null) customRawPreviewReader else null
                val sessionNamedSurfaces = buildList<Pair<Surface, String>> {
                    previewSurface?.let { add(it to "YUV_VIEWFINDER") }
                    sessionCanonicalReader?.surface?.let { surface ->
                        add(
                            surface to if (sessionBufferFormat == ImageFormat.RAW10 ||
                                sessionBufferFormat == ImageFormat.RAW_SENSOR
                            ) "CANONICAL_RAW_RING" else "YUV_WARM_RING"
                        )
                    }
                    customRawPreviewSurface?.let { add(it to "CUSTOM_RAW_PREVIEW") }
                }
                val sessionReaders = listOfNotNull(sessionCanonicalReader, sessionCustomRawReader)

                var sessionParameters: CaptureRequest? = null
                var vendorSessionType: Int = SessionConfiguration.SESSION_REGULAR

                val idleAfMode = resolveConfiguredIdleAfMode(camera.id)
                requestBuilder.set(
                    CaptureRequest.CONTROL_AF_MODE,
                    idleAfMode
                )
                requestBuilder.set(
                    CaptureRequest.CONTROL_AE_MODE,
                    CaptureRequest.CONTROL_AE_MODE_ON
                )
                // LENS_FOCUS_DISTANCE is a manual-focus control. Do not command the actuator to
                // infinity while AF owns the lens; some OEM HALs still react mechanically even
                // though Camera2 specifies the value as application-controlled only in AF_MODE_OFF.
                if (idleAfMode == CaptureRequest.CONTROL_AF_MODE_OFF) {
                    requestBuilder.set(CaptureRequest.LENS_FOCUS_DISTANCE, 0f)
                }
                traceAfWriter(
                    requestBuilder,
                    "createCaptureSession",
                    "SESSION_INITIAL_AF_AND_FOCUS_DISTANCE_WRITES"
                )

                // RAW JPEG rendering needs lens shading metadata when the HAL can provide it.
                // Keep this scoped to RAW pipelines so the working YUV route remains unchanged.
                if (sessionBufferFormat == ImageFormat.RAW10 || sessionBufferFormat == ImageFormat.RAW_SENSOR) {
                    try {
                        val chars = cameraManager.getCameraCharacteristics(camera.id)
                        val modes =
                            chars.get(CameraCharacteristics.STATISTICS_INFO_AVAILABLE_LENS_SHADING_MAP_MODES)
                        if (modes?.contains(CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE_ON) == true) {
                            requestBuilder.set(
                                CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE,
                                CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE_ON
                            )
                            Log.i(
                                tag,
                                "LensShadingMap requested for RAW pipeline format=${
                                    formatName(sessionBufferFormat)
                                }"
                            )
                        } else {
                            Log.i(
                                tag,
                                "LensShadingMap unavailable for RAW pipeline format=${
                                    formatName(sessionBufferFormat)
                                }"
                            )
                        }
                    } catch (t: Throwable) {
                        Log.w(tag, "LensShadingMap request setup failed for RAW pipeline", t)
                    }

                    // P0 RAW defect truth: request the Camera2 sensor hot-pixel map independently
                    // from HOT_PIXEL_MODE. The map is metadata only; BnCam remains the owner of
                    // pre-demosaic RAW correction. Missing optional metadata is a clean fallback.
                    try {
                        val hotPixelMapDecision =
                            com.bncam.core.isp.raw.RawMetadataCaptureRequestPolicy.applyHotPixelMapRequest(
                                builder = requestBuilder,
                                characteristics = cameraManager.getCameraCharacteristics(camera.id),
                                frameSourceFormat = sessionBufferFormat
                            )
                        Log.i(
                            tag,
                            "RawHotPixelMap request requested=${hotPixelMapDecision.requested} " +
                                "supported=${hotPixelMapDecision.mapModeSupported} " +
                                "reason=${hotPixelMapDecision.reason} " +
                                "format=${formatName(sessionBufferFormat)}"
                        )
                    } catch (t: Throwable) {
                        Log.w(tag, "RawHotPixelMap request setup failed for RAW pipeline", t)
                    }
                }

                // -------------------------------------------------------------
                // HARDWARE/SESSION SETTINGS — immutable snapshot prepared off callback thread.
                // -------------------------------------------------------------
                try {
                    val chars = cameraManager.getCameraCharacteristics(camera.id)
                    val activeLensIdResolved = synchronized(pipelineLock) {
                        activePipelineIdentity?.let { identity ->
                            identity.physicalCameraId ?: identity.logicalCameraId
                        }
                    } ?: camera.id
                    applyViewfinderCam2ApiSettings(
                        requestBuilder = requestBuilder,
                        characteristics = chars,
                        cameraId = camera.id,
                        activeLensId = activeLensIdResolved,
                        opticalStabilization = sessionSettings.opticalStabilization,
                        hotPixelMode = sessionSettings.hotPixelMode,
                        noiseReductionHint = sessionSettings.noiseReductionHint,
                        edgeModeHint = sessionSettings.edgeModeHint,
                        tonemapHint = sessionSettings.tonemapHint,
                        antiBanding = sessionSettings.antiBanding
                    )

                    when (sessionSettings.flashMode) {
                        "On" -> {
                            requestBuilder.set(
                                CaptureRequest.CONTROL_AE_MODE,
                                CaptureRequest.CONTROL_AE_MODE_ON_ALWAYS_FLASH
                            )
                            requestBuilder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF)
                        }

                        "Auto" -> {
                            requestBuilder.set(
                                CaptureRequest.CONTROL_AE_MODE,
                                CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH
                            )
                            requestBuilder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF)
                        }

                        else -> {
                            requestBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                            requestBuilder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF)
                        }
                    }

                    currentFaceDetectionRequested = sessionSettings.faceDetection
                    currentFacePriorityFocusEnabled = sessionSettings.facePriorityFocus

                    _sensorRect.value =
                        chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: Rect()
                    _sensorOrientation.value =
                        chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
                    _lensFacing.value = chars.get(CameraCharacteristics.LENS_FACING)
                        ?: CameraCharacteristics.LENS_FACING_BACK

                    applyFaceDetectionMode(
                        builder = requestBuilder,
                        characteristics = chars,
                        requestedForUi = currentFaceDetectionRequested,
                        priorityFocus = currentFacePriorityFocusEnabled
                    )

                    try {
                        VendorInjectionEngine.clearAttempts(camera.id)
                        VendorInjectionEngine.clearEchoStatuses(camera.id)

                        val sessionBuilder = createPipelineCaptureRequestBuilder(camera, CameraDevice.TEMPLATE_PREVIEW)
                        val standardStabilizationSessionApplied =
                            applyOpticalStabilizationSessionParameters(
                                builder = sessionBuilder,
                                characteristics = chars,
                                opticalStabilization = sessionSettings.opticalStabilization
                            )
                        vendorSessionType = resolveVendorSessionType(
                            settings = sessionSettings,
                            cameraId = camera.id,
                            sessionBuilder = sessionBuilder
                        )

                        writePipelineLifecycleDebug(
                            event = "VENDOR_SESSION_TYPE_RESOLVED",
                            decision = null,
                            extra = "cameraId=${camera.id}\nvendorSessionType=$vendorSessionType\n" +
                                "vendorSessionTypeHex=0x${vendorSessionType.toString(16).uppercase()}\n" +
                                "willUseVendorSessionType=${vendorSessionType != SessionConfiguration.SESSION_REGULAR}"
                        )

                        val sessionAttempts = VendorInjectionEngine.applyPreparedTags(
                            lensId = camera.id,
                            builder = sessionBuilder,
                            stage = VendorRequestStage.SESSION,
                            activeTags = sessionSettings.activeVendorTags,
                            registry = sessionSettings.vendorRegistry
                        )
                        if (standardStabilizationSessionApplied || sessionAttempts.any { it.appliedToBuilder }) {
                            sessionParameters = sessionBuilder.build()
                            Log.i(
                                tag,
                                "SESSION parameters prepared before createCaptureSession lens=${camera.id} " +
                                    "standardStabilization=$standardStabilizationSessionApplied " +
                                    "vendorApplied=${sessionAttempts.count { it.appliedToBuilder }}"
                            )
                        }

                        val repeatingAttempts = VendorInjectionEngine.applyPreparedTags(
                            lensId = camera.id,
                            builder = requestBuilder,
                            stage = VendorRequestStage.REPEATING_REQUEST,
                            activeTags = sessionSettings.activeVendorTags,
                            registry = sessionSettings.vendorRegistry
                        )
                        Log.i(
                            tag,
                            "Vendor injection prepared lens=${camera.id} " +
                                "registry=${sessionSettings.vendorRegistry.size} " +
                                "sessionApplied=${sessionAttempts.count { it.appliedToBuilder }} " +
                                "repeatingApplied=${repeatingAttempts.count { it.appliedToBuilder }}"
                        )
                    } catch (e: Exception) {
                        Log.e(tag, "Vendor injection preparation failed for lens=${camera.id}", e)
                    }
                } catch (e: Exception) {
                    cameraState.value = CameraEngineState.ERROR
                    _captureContractError.value = e.message ?: "Camera2 processing settings are unsupported."
                    throw IllegalStateException("Camera2 request contract could not be applied.", e)
                }
                // -------------------------------------------------------------

                // Vendor/session setup is complete. Reassert app-owned optical geometry last so
                // a hidden physical ultra-wide YUV stream cannot inherit the logical 1.0x crop.
                applyPhysicalYuvFullFovZoom(
                    builder = requestBuilder,
                    relativeDigitalZoom = 1f,
                    reason = "WARM_REPEATING_SESSION"
                )
                applyMeteringPolicy(requestBuilder)
                applyExposurePolicy(requestBuilder)
                applyLiveWhiteBalancePolicy(requestBuilder)


                var firstStablePreviewDiagnosticsLogged = false
                captureCallback = object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureCompleted(
                        session: CameraCaptureSession,
                        request: CaptureRequest,
                        result: TotalCaptureResult
                    ) {
                        super.onCaptureCompleted(session, request, result)
                        captureAttempts.captureResultReceived()

                        val auditLogicalId = synchronized(pipelineLock) { activePipelineIdentity?.logicalCameraId } ?: camera.id
                        val auditPhysicalId = synchronized(pipelineLock) { activePipelineIdentity?.physicalCameraId }
                        com.bncam.core.debug.AfGroundTruthTrace.recordCaptureResult(
                            logicalCameraId = auditLogicalId,
                            activePhysicalCameraId = auditPhysicalId,
                            request = request,
                            result = result
                        )
                        com.bncam.core.debug.HalParityAuditor.auditCaptureResult(auditLogicalId, auditPhysicalId, result)
                        com.bncam.core.debug.AfParityAuditor.auditCaptureResultAfParity(auditLogicalId, auditPhysicalId, result)
                        validateRuntimeProbedDirectOis(result)

                        val decision = activeOisDecision
                        val lensRoleStr = synchronized(pipelineLock) { activePipelineIdentity?.lensRole } ?: "Unknown"
                        val requested = decision?.appliedMethod?.name ?: "OFF"

                        val logicalOisReported = result.get(CaptureResult.LENS_OPTICAL_STABILIZATION_MODE)
                        val logicalVideoStabReported = result.get(CaptureResult.CONTROL_VIDEO_STABILIZATION_MODE)
                        val activePhysicalId = decision?.physicalCameraId

                        var physicalOisReported: Int? = null
                        if (activePhysicalId != null) {
                            val physicalResult = physicalCaptureResultOrNull(result, activePhysicalId)
                            physicalOisReported = physicalResult?.get(CaptureResult.LENS_OPTICAL_STABILIZATION_MODE)
                        }

                        val resultText = when (decision?.appliedMethod) {
                            OisDecision.OisMethod.PHYSICAL_OIS, OisDecision.OisMethod.LOGICAL_OIS -> {
                                if (
                                    logicalOisReported == CaptureResult.LENS_OPTICAL_STABILIZATION_MODE_ON ||
                                    physicalOisReported == CaptureResult.LENS_OPTICAL_STABILIZATION_MODE_ON
                                ) "ON" else "OFF"
                            }
                            OisDecision.OisMethod.VENDOR -> {
                                if (logicalOisReported == CaptureResult.LENS_OPTICAL_STABILIZATION_MODE_ON) {
                                    "ON"
                                } else {
                                    "VENDOR_REQUESTED"
                                }
                            }
                            OisDecision.OisMethod.PREVIEW_STAB -> {
                                if (logicalVideoStabReported == CaptureResult.CONTROL_VIDEO_STABILIZATION_MODE_PREVIEW_STABILIZATION) "PREVIEW_STAB" else "OFF"
                            }
                            OisDecision.OisMethod.VIDEO_STAB -> {
                                if (logicalVideoStabReported == CaptureResult.CONTROL_VIDEO_STABILIZATION_MODE_ON) "VIDEO_STAB" else "OFF"
                            }
                            else -> "OFF"
                        }

                        val physResultText = if (physicalOisReported != null) {
                            if (physicalOisReported == CaptureResult.LENS_OPTICAL_STABILIZATION_MODE_ON) "ON" else "OFF"
                        } else {
                            "null"
                        }

                        val frameNumber = result.frameNumber
                        if (frameNumber % 60 == 0L) {
                            Log.i("OisResolver", "OIS_RESULT lens=$lensRoleStr requested=$requested result=$resultText physicalResult=$physResultText")
                        }

                        // NIEUW: Trigger de echo scanner
                        com.bncam.vendor.VendorInjectionEngine.verifyEchoes(
                            cameraDevice?.id ?: "",
                            result
                        )
                        previewSurface?.let { handleVendorOperationModeProbe(result, it) }

                        lastCaptureResult = result
                        lastCaptureResultGeneration = sessionGeneration
                        updateRawFlickerAuthority(result, sessionGeneration)
                        updateDefaultRawExposureRealizationTruth(request, result, sessionGeneration)
                        validateMeteringResultEcho(result)
                        updateAutoWhiteBalanceState(result, sessionGeneration)
                        updateLiveWhiteBalanceDisplayCompensation(result)
                        if (manualExposureAwaitingMetadata &&
                            result.get(CaptureResult.SENSOR_SENSITIVITY)?.let { it > 0 } == true &&
                            result.get(CaptureResult.SENSOR_EXPOSURE_TIME)?.let { it > 0L } == true
                        ) {
                            manualExposureAwaitingMetadata = false
                            backgroundHandler?.post { updatePreviewRepeatingRequest() }
                        }
                        if (profileExposureAwaitingAeBaseline &&
                            sessionGeneration == pipelineGeneration &&
                            requestedManualIso == null && requestedManualExposureNs == null &&
                            activeProfileExposurePreferences.requiresAeBaseline()
                        ) {
                            val resolvedRequest = controlRequestEpochTracker.resolveTag(request.tag, sessionGeneration)
                            val requestSnapshot = resolvedRequest.provenance?.snapshot
                            val requestAeMode = request.get(CaptureRequest.CONTROL_AE_MODE)
                            val measuredIso = result.get(CaptureResult.SENSOR_SENSITIVITY)?.takeIf { it > 0 }
                            val measuredExposureNs = result.get(CaptureResult.SENSOR_EXPOSURE_TIME)?.takeIf { it > 0L }
                            val freshRepeatingAe =
                                requestSnapshot?.submissionType == CameraRequestSubmissionType.REPEATING &&
                                    requestSnapshot.identity.controlRequestEpoch >= profileExposureBootstrapMinControlEpoch &&
                                    requestAeMode != null && requestAeMode != CaptureRequest.CONTROL_AE_MODE_OFF
                            if (freshRepeatingAe && measuredIso != null && measuredExposureNs != null) {
                                profileExposureAeBaselineIso = measuredIso
                                profileExposureAeBaselineExposureNs = measuredExposureNs
                                profileExposureAeBaselineGeneration = sessionGeneration
                                profileExposureAeBaselineControllerLuma = latestExposureStatistics
                                    ?.takeIf { it.sampleCount > 0 }
                                    ?.exposureControllerLuma()
                                    ?.takeIf { it.isFinite() && it > 0f }
                                profileExposureLastAdaptationElapsedNs = 0L
                                val handler = backgroundHandler
                                if (handler != null && handler.post { updatePreviewRepeatingRequest() }) {
                                    profileExposureAwaitingAeBaseline = false
                                    profileExposureBootstrapMinControlEpoch = -1L
                                    Log.i(
                                        tag,
                                        "PROFILE_EXPOSURE_AE_BASELINE_ACCEPTED generation=$sessionGeneration " +
                                            "epoch=${requestSnapshot.identity.controlRequestEpoch} iso=$measuredIso " +
                                            "exposureNs=$measuredExposureNs shotBias=${activeProfileExposurePreferences.shotBiasExposure.persistedValue}"
                                    )
                                }
                            }
                        }
                        if (defaultRawShutterAwaitingAeBaseline &&
                            sessionGeneration == pipelineGeneration &&
                            requestedManualIso == null && requestedManualExposureNs == null &&
                            !activeProfileExposurePreferences.requiresAeBaseline() &&
                            isActiveRawWarmProducer() && currentFlashMode == "Off"
                        ) {
                            val resolvedRequest = controlRequestEpochTracker.resolveTag(request.tag, sessionGeneration)
                            val requestSnapshot = resolvedRequest.provenance?.snapshot
                            val requestAeMode = request.get(CaptureRequest.CONTROL_AE_MODE)
                            val measuredIso = result.get(CaptureResult.SENSOR_SENSITIVITY)?.takeIf { it > 0 }
                            val measuredExposureNs = result.get(CaptureResult.SENSOR_EXPOSURE_TIME)?.takeIf { it > 0L }
                            val aeStateForBaseline = result.get(CaptureResult.CONTROL_AE_STATE)
                            val aeStable = aeStateForBaseline == CaptureResult.CONTROL_AE_STATE_CONVERGED ||
                                aeStateForBaseline == CaptureResult.CONTROL_AE_STATE_FLASH_REQUIRED ||
                                aeStateForBaseline == CaptureResult.CONTROL_AE_STATE_LOCKED
                            val freshRepeatingAe =
                                requestSnapshot?.submissionType == CameraRequestSubmissionType.REPEATING &&
                                    requestSnapshot.identity.controlRequestEpoch >= defaultRawShutterBootstrapMinControlEpoch &&
                                    requestAeMode != null && requestAeMode != CaptureRequest.CONTROL_AE_MODE_OFF
                            val referenceMetering = defaultRawMeteringTracker.resolve(
                                currentGeneration = sessionGeneration,
                                nowElapsedRealtimeNs = android.os.SystemClock.elapsedRealtimeNanos(),
                                source = "AE_REFERENCE_ACCEPTANCE"
                            )
                            val synchronizedMeteringReady =
                                defaultRawAeReferenceGate.canAccept(sessionGeneration, referenceMetering)
                            if (freshRepeatingAe && aeStable && measuredIso != null && measuredExposureNs != null &&
                                synchronizedMeteringReady
                            ) {
                                val referenceLuma = requireNotNull(referenceMetering.controllerLuma)
                                defaultRawShutterAeBaselineIso = measuredIso
                                defaultRawShutterAeBaselineExposureNs = measuredExposureNs
                                defaultRawShutterAeBaselineGeneration = sessionGeneration
                                defaultRawPhotometricTargetLuma = referenceLuma
                                defaultRawShutterFallbackTargetLuma = referenceLuma
                                latestDefaultRawMetering = referenceMetering
                                val handler = backgroundHandler
                                if (handler != null && handler.post { updatePreviewRepeatingRequest() }) {
                                    defaultRawShutterAwaitingAeBaseline = false
                                    defaultRawShutterBootstrapMinControlEpoch = -1L
                                    Log.i(
                                        tag,
                                        "DEFAULT_RAW_SHUTTER_AE_BASELINE_ACCEPTED generation=$sessionGeneration " +
                                            "epoch=${requestSnapshot.identity.controlRequestEpoch} iso=$measuredIso " +
                                            "exposureNs=$measuredExposureNs aeState=$aeStateForBaseline " +
                                            "targetLuma=$referenceLuma ${defaultRawAeReferenceGate.summary(sessionGeneration)}"
                                    )
                                }
                            }
                        }
                        val afState = result.get(CaptureResult.CONTROL_AF_STATE) ?: -1
                        val aeState = result.get(CaptureResult.CONTROL_AE_STATE) ?: -1
                        lastAfState = afState
                        lastAeState = aeState

                        val expTime = result.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: 0L
                        val sensitivity = result.get(CaptureResult.SENSOR_SENSITIVITY) ?: 0
                        if (lastShotExposureNs > 0L && lastShotIso > 0 && lastShotVerdictHighDrRisk) {
                            val expRatio = expTime.toDouble() / lastShotExposureNs
                            val isoRatio = sensitivity.toDouble() / lastShotIso
                            if (expRatio < 0.7 || expRatio > 1.3 || isoRatio < 0.7 || isoRatio > 1.3) {
                                lastShotVerdictHighDrRisk = false
                                lastShotExposureNs = 0L
                                lastShotIso = 0
                                Log.i(tag, "lastShotVerdict decayed due to preview exposure change (>30%): expRatio=$expRatio isoRatio=$isoRatio")
                            }
                        }

                        // STATE MACHINE: Bepaal of de viewfinder en de belichting stabiel zijn
                        if (sessionGeneration == pipelineGeneration) {
                            val isAfStable =
                                afState == CaptureResult.CONTROL_AF_STATE_PASSIVE_FOCUSED || afState == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED
                            val isAeStable =
                                aeState == CaptureResult.CONTROL_AE_STATE_CONVERGED || aeState == CaptureResult.CONTROL_AE_STATE_FLASH_REQUIRED

                            if (isAfStable && isAeStable && !firstStablePreviewDiagnosticsLogged) {
                                firstStablePreviewDiagnosticsLogged = true
                                logPreviewDiagnostics(
                                    event = "FIRST_STABLE_PREVIEW_RESULT",
                                    request = request,
                                    result = result,
                                    extra = "afState=$afState aeState=$aeState frameNumber=${result.frameNumber}"
                                )
                            }

                            if (cameraState.value == CameraEngineState.CAMERA_STARTING || cameraState.value == CameraEngineState.PREVIEW_STARTING) {
                                if (isAfStable && isAeStable) {
                                    cameraState.value = CameraEngineState.PREVIEW_STABLE
                                }
                            }

                            val streamHealthRequirement =
                                WarmBufferReadinessPolicy.streamHealth(
                                    format = activeZslFormat,
                                    bufferCapacity = ringBuffer.currentCapacity()
                                )
                            val freshCompleteFrames = ringBuffer.freshMetadataCompleteFrameCount(
                                referenceTimestampNs = android.os.SystemClock.elapsedRealtimeNanos(),
                                freshnessWindowMs =
                                    streamHealthRequirement.streamHealthFreshnessWindowMs
                            )
                            if (
                                freshCompleteFrames >=
                                streamHealthRequirement.requiredCompleteFrames
                            ) {
                                cameraState.value = CameraEngineState.CAPTURE_READY
                            }
                        }

                        // 1. LIVE FOCUS AFSTAND
                        val currentDiopters = result.get(CaptureResult.LENS_FOCUS_DISTANCE) ?: 0f
                        _liveFocusDiopters.value = currentDiopters

                        // 2. KIJK OF DE LENS KLAAR IS MET ZOEKEN
                        if (afState != previousAfState) {
                            if (afState == CaptureResult.CONTROL_AF_STATE_PASSIVE_FOCUSED) {
                                if (abs(currentDiopters - lastPassiveFocusDiopters) > 0.1f) {
                                    passiveFocusAchieved.tryEmit(Unit)
                                }
                                lastPassiveFocusDiopters = currentDiopters
                            } else if (afState == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED) {
                                // FIX: Sla óók de handmatige focusafstand op!
                                // Zo schrikt de auto-focus niet wakker als de 3s timer afloopt.
                                lastPassiveFocusDiopters = currentDiopters
                            }
                            previousAfState = afState
                        }

                        val faces = result.get(CaptureResult.STATISTICS_FACES)
                        val validFaces = faces?.filter { it.score >= 50 } ?: emptyList()
                        _detectedFaces.value = validFaces.toTypedArray()

                        // Camera2 does not expose per-face depth here. Apparent size is only the
                        // initial proximity proxy; sticky ID/geometry continuity prevents target hopping.
                        if (currentFacePriorityFocusEnabled &&
                            !_focusOwnership.value.focusLocked && !focusOwnerBlocksFacePriority()
                        ) {
                            val apparentNearestFace = selectStickyPriorityFace(validFaces)
                            val nextFaceRect = apparentNearestFace?.bounds?.let(::Rect)
                            val previousFaceRect = lastFaceMeteringRect
                            val nowMs = android.os.SystemClock.elapsedRealtime()
                            val materiallyChanged = when {
                                previousFaceRect == null && nextFaceRect != null -> true
                                previousFaceRect != null && nextFaceRect == null -> true
                                previousFaceRect != null && nextFaceRect != null -> {
                                    abs(previousFaceRect.centerX() - nextFaceRect.centerX()) >
                                        max(5, previousFaceRect.width() / 20) ||
                                        abs(previousFaceRect.centerY() - nextFaceRect.centerY()) >
                                        max(5, previousFaceRect.height() / 20) ||
                                        abs(previousFaceRect.width() - nextFaceRect.width()) >
                                        max(6, previousFaceRect.width() / 12)
                                }
                                else -> false
                            }
                            if (materiallyChanged && nowMs - lastFaceRequestUpdateMs >= 80L) {
                                lastFaceRequestUpdateMs = nowMs
                                lastFaceMeteringRect = nextFaceRect
                                lastFaceTrackingId = apparentNearestFace?.id?.takeIf { it != Face.ID_UNSUPPORTED }
                                _priorityFaceBounds.value = nextFaceRect?.let(::Rect)
                                transitionFocusOwner(
                                    owner = if (nextFaceRect != null) FocusOwner.FACE_PRIORITY else FocusOwner.AUTO,
                                    reason = if (nextFaceRect != null) "face_priority_target" else "face_priority_lost"
                                )
                                predictiveAfTracker.clear()
                                val builder = currentCaptureRequest
                                if (builder != null) {
                                    val controlChars = runCatching {
                                        cameraManager.getCameraCharacteristics(cameraDevice?.id ?: camera.id)
                                    }.getOrNull()
                                    if (currentFacePriorityFocusEnabled) {
                                        val maxAf = controlChars?.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF) ?: 0
                                        if (nextFaceRect != null && maxAf > 0) {
                                            val activeArray = controlChars?.get(
                                                CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE
                                            )
                                            val focusBounds = builder.get(CaptureRequest.SCALER_CROP_REGION)
                                                ?: activeArray
                                            val focusRect = focusBounds?.let {
                                                nextFaceRect.clampedInside(it)
                                            } ?: nextFaceRect
                                            builder.set(
                                                CaptureRequest.CONTROL_AF_REGIONS,
                                                arrayOf(MeteringRectangle(
                                                    focusRect,
                                                    MeteringRectangle.METERING_WEIGHT_MAX
                                                ))
                                            )
                                            builder.set(
                                                CaptureRequest.CONTROL_AF_MODE,
                                                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
                                            )
                                        } else {
                                            // A disappearing face must actively clear the prior AF
                                            // region; otherwise the old face remains a hidden target.
                                            builder.set(CaptureRequest.CONTROL_AF_REGIONS, null)
                                            builder.set(
                                                CaptureRequest.CONTROL_AF_MODE,
                                                resolveConfiguredIdleAfMode(cameraDevice?.id)
                                            )
                                        }
                                        traceAfWriter(
                                            builder,
                                            "captureCallback.onCaptureCompleted",
                                            "FACE_PRIORITY_AF_WRITES"
                                        )
                                    }
                                    updatePreviewRepeatingRequest()
                                }
                            }
                        } else if (lastFaceMeteringRect != null && !_focusOwnership.value.focusLocked) {
                            lastFaceMeteringRect = null
                            lastFaceTrackingId = null
                            _priorityFaceBounds.value = null
                            if (_focusOwnership.value.owner == FocusOwner.FACE_PRIORITY) {
                                transitionFocusOwner(FocusOwner.AUTO, "face_priority_released")
                            }
                        }

                        // 3. ZSL METADATA
                        if (sessionGeneration != pipelineGeneration) {
                            return
                        }

                        val timestamp = result.get(CaptureResult.SENSOR_TIMESTAMP)
                        if (timestamp != null) {
                            val cadenceFpsRange = result.get(CaptureResult.CONTROL_AE_TARGET_FPS_RANGE)
                            RawPreviewCadenceDiagnostics.metadataArrived(
                                source = ViewfinderEffectiveSource.fromImageFormat(sessionBufferFormat),
                                generation = sessionGeneration,
                                sensorTimestampNs = timestamp,
                                sensorFrameDurationNs = result.get(CaptureResult.SENSOR_FRAME_DURATION) ?: 0L,
                                exposureTimeNs = result.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: 0L,
                                requestedFpsLower = cadenceFpsRange?.lower ?: 0,
                                requestedFpsUpper = cadenceFpsRange?.upper ?: 0
                            )
                            val tagResolution =
                                controlRequestEpochTracker.resolveTag(
                                    tag = request.tag,
                                    expectedPipelineGeneration =
                                        sessionGeneration
                                )
                            if (!tagResolution.exact) {
                                Log.w(
                                    "CameraRequestProvenance",
                                    "event=CAPTURE_RESULT_PROVENANCE_UNPROVEN " +
                                            "sensorTimestampNs=$timestamp " +
                                            "sessionPipelineGeneration=$sessionGeneration " +
                                            "currentPipelineGeneration=$pipelineGeneration " +
                                            "status=${tagResolution.status}"
                                )
                            }
                            recordCamera3AObservation(
                                Camera3AObservation(
                                    pipelineGeneration = sessionGeneration,
                                    frameNumber = result.frameNumber,
                                    controlRequestEpoch =
                                        tagResolution.provenance?.identity?.controlRequestEpoch ?: 0L,
                                    aeMode = request.get(CaptureRequest.CONTROL_AE_MODE),
                                    aeState = result.get(CaptureResult.CONTROL_AE_STATE),
                                    afState = result.get(CaptureResult.CONTROL_AF_STATE),
                                    flashState = result.get(CaptureResult.FLASH_STATE),
                                    observedElapsedRealtimeMs = android.os.SystemClock.elapsedRealtime()
                                )
                            )
                            val sensorMetadataSnapshot = frameSensorMetadataSnapshot(
                                result = result,
                                expectedGeneration = sessionGeneration,
                                fallbackLogicalCameraId = camera.id
                            )
                            ringBuffer.addMetadata(
                                timestamp = sensorMetadataSnapshot?.sensorTimestampNs ?: timestamp,
                                result = result,
                                generationId = sessionGeneration,
                                requestProvenance =
                                    tagResolution.provenance,
                                sensorMetadataSnapshot = sensorMetadataSnapshot
                            )
                            scheduleFocusConfidenceAnalysis(
                                generation = sessionGeneration,
                                expectedFormat = sessionBufferFormat
                            )
                            if (sessionBufferFormat == ImageFormat.YUV_420_888) {
                                scheduleEnabledYuvAnalysis(
                                    timestampNs = timestamp,
                                    generation = sessionGeneration,
                                    expectedFormat = sessionBufferFormat,
                                    deviceRotation = currentMlAnalysisRotationDegrees()
                                )
                            }
                            // The Image callback is the single owner of live-preview submission.
                            // Metadata refreshes calibration only; submitting the paired buffer here
                            // processed and presented every SENSOR_TIMESTAMP a second time.
                            if (targetViewfinderSource != ViewfinderEffectiveSource.YUV && !isCapturing) {
                                ensureRawPreviewConfig(result, sessionGeneration)
                            }
                        }
                    }

                    override fun onCaptureBufferLost(
                        session: CameraCaptureSession,
                        request: CaptureRequest,
                        target: Surface,
                        frameNumber: Long
                    ) {
                        val targetName = sessionSurfaceDiagnosticName(session, target)
                        val content =
                            "generation=$sessionGeneration;epoch=$sessionEpoch;frameNumber=$frameNumber;" +
                                "target=$targetName;targetIdentity=${System.identityHashCode(target)};" +
                                "selectedViewfinder=${targetViewfinderSource.name};" +
                                "sessionOutputs=${sessionOutputDiagnostics(session)};" +
                                rawRingPressureSummary()
                        recordRawSessionOutputDiagnostic("CAMERA2_CAPTURE_BUFFER_LOST", content)
                        if (targetName == "CUSTOM_RAW_PREVIEW") {
                            disableCustomRawPreviewForGeneration(sessionGeneration, "camera2_buffer_lost")
                        }
                    }

                    override fun onCaptureFailed(
                        session: CameraCaptureSession,
                        request: CaptureRequest,
                        failure: android.hardware.camera2.CaptureFailure
                    ) {
                        val content =
                            "generation=$sessionGeneration;epoch=$sessionEpoch;reason=${failure.reason};" +
                                "sequenceId=${failure.sequenceId};frameNumber=${failure.frameNumber};" +
                                "wasImageCaptured=${failure.wasImageCaptured()};" +
                                "selectedViewfinder=${targetViewfinderSource.name};" +
                                "sessionOutputs=${sessionOutputDiagnostics(session)};" +
                                rawRingPressureSummary()
                        recordRawSessionOutputDiagnostic("CAMERA2_CAPTURE_FAILED", content)
                    }

                    override fun onCaptureSequenceAborted(session: CameraCaptureSession, sequenceId: Int) {
                        Log.e(tag, "captureSequenceAborted generation=$sessionGeneration sequenceId=$sequenceId")
                    }
                }

                val stateCallback = object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        registerSessionOutputOwnership(
                            session = session,
                            namedSurfaces = sessionNamedSurfaces,
                            readers = sessionReaders,
                            generation = sessionGeneration,
                            epoch = sessionEpoch,
                            reason = reason
                        )
                        if (sessionGeneration != pipelineGeneration || sessionEpoch != sessionConfigurationEpoch) {
                            Log.i(
                                tag,
                                "Ignoring obsolete Camera2 session callback generation=$sessionGeneration epoch=$sessionEpoch " +
                                    "activeGeneration=$pipelineGeneration activeEpoch=$sessionConfigurationEpoch"
                            )
                            session.close()
                            onSessionReady?.invoke(false)
                            return
                        }

                        startupSessionConfiguredCount += 1
                        lifetimeSessionConfiguredCount.incrementAndGet()
                        logCameraLifetimeCounters(
                            event = "CAPTURE_SESSION_CONFIGURED",
                            extra = "logical=${camera.id} generation=$sessionGeneration reason=$reason"
                        )
                        Log.i(
                            previewDiagnosticsTag,
                            "event=CAPTURE_SESSION_CONFIGURED startupSequence=$startupSequenceId " +
                                "sessionRequest=$sessionRequestIndex sessionsConfigured=$startupSessionConfiguredCount " +
                                "deviceOpens=$startupCameraOpenCount generation=$sessionGeneration " +
                                "previewAttached=${previewSurface != null} reason=$reason"
                        )
                        synchronized(sessionLifecycleLock) {
                            sessionCloseBarriers.getOrPut(session) { CompletableDeferred() }
                        }
                        captureSession = session
                        currentCaptureRequest = requestBuilder
                        try {
                            submitRepeatingRequestWithProvenance(
                                session = session,
                                builder = requestBuilder,
                                callback = captureCallback,
                                handler = backgroundHandler,
                                reason = "SESSION_INITIAL_REPEATING",
                                pipelineGenerationAtSubmission =
                                    sessionGeneration
                            )
                            activeConfiguredSessionEpoch = sessionEpoch
                            synchronized(pipelineLock) {
                                if (sessionGeneration == pipelineGeneration) {
                                    sessionConfiguredGeneration = sessionGeneration
                                }
                            }
                            ringBuffer.recordSessionConfigured(sessionGeneration)
                            startWarmBufferWatchdog(sessionGeneration)
                            recordRawPreviewValidationEnvironment(reason, sessionGeneration)
                            synchronized(pipelineLock) {
                                activePipelineIdentity?.selectedLensId
                            }?.let { targetLensId ->
                                com.bncam.core.debug.Phase0PerformanceTrace.lensTransitionMilestone(
                                    targetLensId = targetLensId,
                                    event = "session_configured",
                                    detail = "logical=${camera.id};reason=$reason"
                                )
                            }
                            Log.d(tag, "ZSL Engine Draait! Hartslag (metadata) geactiveerd.")
                            onSessionReady?.invoke(true)
                        } catch (e: Exception) {
                            if (activeConfiguredSessionEpoch == sessionEpoch) {
                                activeConfiguredSessionEpoch = -1L
                            }
                            synchronized(pipelineLock) {
                                if (sessionGeneration == pipelineGeneration) {
                                    sessionConfiguredGeneration = -1
                                }
                            }
                            Log.e(tag, "Repeating request failed", e)
                            onSessionReady?.invoke(false)
                        }
                    }

                    override fun onClosed(session: CameraCaptureSession) {
                        releaseSessionOutputOwnership(session)
                        synchronized(sessionLifecycleLock) {
                            sessionCloseBarriers.remove(session)?.complete(Unit)
                        }
                        if (captureSession === session) {
                            captureSession = null
                            currentCaptureRequest = null
                            if (activeConfiguredSessionEpoch == sessionEpoch) {
                                activeConfiguredSessionEpoch = -1L
                            }
                        }
                        Log.i(
                            previewDiagnosticsTag,
                            "event=CAPTURE_SESSION_CLOSED generation=$sessionGeneration previewAttached=${previewSurface != null} reason=$reason"
                        )
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        registerSessionOutputOwnership(
                            session = session,
                            namedSurfaces = sessionNamedSurfaces,
                            readers = sessionReaders,
                            generation = sessionGeneration,
                            epoch = sessionEpoch,
                            reason = "$reason:configure_failed"
                        )
                        if (sessionGeneration != pipelineGeneration || sessionEpoch != sessionConfigurationEpoch) {
                            session.close()
                            onSessionReady?.invoke(false)
                            return
                        }
                        if (customRawPreviewBinding?.generation == sessionGeneration && customRawPreviewReader != null) {
                            Log.w(
                                tag,
                                "CUSTOM_RAW_PREVIEW_SESSION_REJECTED binding=$customRawPreviewBinding; retrying canonical session"
                            )
                            val retiringCustomRawReader =
                                detachCustomRawPreviewReaderForRetirement("camera2_configure_failed")
                            val failedSessionTicket = requestCaptureSessionClose(
                                session,
                                "CUSTOM_RAW_PREVIEW_CONFIGURE_FAILED"
                            )
                            customRawPreviewDisabledGeneration = sessionGeneration
                            if (sessionGeneration == pipelineGeneration && cameraDevice === camera) {
                                // This retry remains part of the same serialized transition: the
                                // transition owner is still suspended on onSessionReady and is not
                                // released until this canonical retry has completed.
                                createCaptureSession(
                                    camera = camera,
                                    previewSurface = previewSurface,
                                    sessionSettings = sessionSettings,
                                    reason = "CUSTOM_RAW_PREVIEW_RETRY_CANONICAL",
                                    onSessionReady = { retryReady ->
                                        val failedSessionClosed = failedSessionTicket.closeBarrier.isCompleted
                                        if (failedSessionClosed) {
                                            closeRetiredImageReader(
                                                retiringCustomRawReader,
                                                "custom_raw_configure_failed_acknowledged"
                                            )
                                        } else {
                                            // The canonical replacement is valid as soon as its own
                                            // onConfigured/repeating contract succeeds. A late
                                            // onClosed from the rejected custom-output session only
                                            // controls retirement of that session's ImageReader.
                                            retireReadersWhenSessionCloses(
                                                failedSessionTicket,
                                                listOf(retiringCustomRawReader),
                                                "custom_raw_configure_failed_late_ack"
                                            )
                                        }
                                        onSessionReady?.invoke(retryReady)
                                    }
                                )
                            } else {
                                retireReadersWhenSessionCloses(
                                    failedSessionTicket,
                                    listOf(retiringCustomRawReader),
                                    "custom_raw_configure_failed_obsolete"
                                )
                                onSessionReady?.invoke(false)
                            }
                            return
                        }
                        synchronized(pipelineLock) {
                            if (sessionGeneration == pipelineGeneration && sessionEpoch == sessionConfigurationEpoch) {
                                sessionConfiguredGeneration = -1
                            }
                        }
                        Log.e(tag, "Capture Session configuratie gefaald previewAttached=${previewSurface != null}")
                        onSessionReady?.invoke(false)
                        val probe = activeVendorOperationProbe
                        if (probe != null) {
                            writePipelineLifecycleDebug(
                                event = "VENDOR_OPERATION_MODE_PROBE_CONFIG_FAILED",
                                decision = null,
                                extra = "lensId=${probe.lensId}\nfeatureSignature=${probe.featureSignature}\ncandidateIndex=${probe.candidateIndex}\nsessionType=${probe.sessionType}\nsessionTypeLabel=${probe.sessionTypeLabel}"
                            )
                            val candidates = vendorOperationModeCandidateSessionTypes()
                            val nextIndex = probe.candidateIndex + 1
                            if (nextIndex <= candidates.lastIndex) {
                                sessionTransitionScope.launch {
                                    SettingsRepository(context).setVendorOperationModeProbeIndex(
                                        lensId = probe.lensId,
                                        featureSignature = probe.featureSignature,
                                        index = nextIndex
                                    )
                                    previewSurface?.let { attachedPreview ->
                                        softResetPipeline(
                                            previewSurface = attachedPreview,
                                            newFormat = lastPreferredFormat,
                                            profileId = lastProfileId,
                                            forceSessionRebuild = true,
                                            reason = "VENDOR_OPERATION_MODE_PROBE_CONFIG_FAILED_NEXT"
                                        )
                                    }
                                }
                            } else {
                                activeVendorOperationProbe = null
                                sessionTransitionScope.launch {
                                    SettingsRepository(context).setVendorOperationModeProbeActive(probe.lensId, false)
                                }
                            }
                        }
                    }
                }

                val outputs = mutableListOf<OutputConfiguration>()
                val activePhysicalId = synchronized(pipelineLock) { activePipelineIdentity?.physicalCameraId }
                val activeLogicalId = synchronized(pipelineLock) { activePipelineIdentity?.logicalCameraId } ?: camera.id
                com.bncam.core.debug.HalParityAuditor.auditStaticCapabilities(cameraManager, activeLogicalId, activePhysicalId)
                com.bncam.core.debug.AfParityAuditor.auditRouteAfCapability(cameraManager, activeLogicalId, activePhysicalId)

                if (previewSurface != null) {
                    val previewOutput = OutputConfiguration(previewSurface)
                    if (activePhysicalId != null) {
                        previewOutput.setPhysicalCameraId(activePhysicalId)
                    }
                    lastPreviewOutputPhysicalCameraId = activePhysicalId
                    outputs.add(previewOutput)
                } else {
                    lastPreviewOutputPhysicalCameraId = null
                }

                imageReader?.surface?.let {
                    val readerOutput = OutputConfiguration(it)
                    if (activePhysicalId != null) {
                        readerOutput.setPhysicalCameraId(activePhysicalId)
                    }
                    lastImageReaderOutputPhysicalCameraId = activePhysicalId
                    outputs.add(readerOutput)
                }
                if (imageReader?.surface == null) {
                    lastImageReaderOutputPhysicalCameraId = null
                }

                customRawPreviewSurface?.let { customSurface ->
                    val customOutput = OutputConfiguration(customSurface)
                    if (activePhysicalId != null) customOutput.setPhysicalCameraId(activePhysicalId)
                    outputs.add(customOutput)
                }

                val sessionConfig = SessionConfiguration(
                    vendorSessionType,
                    outputs,
                    { runnable -> backgroundHandler?.post(runnable) ?: runnable.run() },
                    stateCallback
                )

                sessionParameters?.let { params ->
                    try {
                        sessionConfig.setSessionParameters(params)
                        Log.i(
                            tag,
                            "Session parameters attached for lens=${camera.id} sessionType=$vendorSessionType"
                        )
                    } catch (e: Exception) {
                        Log.e(tag, "Session parameters failed for lens=${camera.id}", e)
                    }
                }

                val outputBindings = mutableListOf<Pair<String, String?>>()
                if (previewSurface != null) outputBindings.add("preview" to lastPreviewOutputPhysicalCameraId)
                if (imageReader?.surface != null) outputBindings.add("imageReader" to lastImageReaderOutputPhysicalCameraId)
                if (customRawPreviewSurface != null) outputBindings.add("customRawPreview" to activePhysicalId)
                com.bncam.core.debug.HalParityAuditor.auditSessionCreation(
                    activeLogicalId,
                    activePhysicalId,
                    vendorSessionType,
                    outputBindings,
                    sessionParameters
                )

                Log.i(
                    tag,
                    "Creating Camera2 session lens=${camera.id} sessionType=$vendorSessionType outputs=${outputs.size} " +
                        "previewAttached=${previewSurface != null} epoch=$sessionEpoch"
                )
                logPreviewDiagnostics(
                    event = "SESSION_CREATION",
                    request = requestBuilder.build(),
                    extra = "sessionType=$vendorSessionType outputCount=${outputs.size}"
                )
                writePipelineLifecycleDebug(
                    event = "CAMERA2_CREATE_CAPTURE_SESSION",
                    decision = null,
                    extra = "cameraId=${camera.id}\nsessionType=$vendorSessionType\nsessionTypeHex=0x${
                        vendorSessionType.toString(
                            16
                        ).uppercase()
                    }\noutputs=${outputs.size}\nhasSessionParameters=${sessionParameters != null}"
                )
                val phase0TargetLensId = synchronized(pipelineLock) {
                    activePipelineIdentity?.selectedLensId
                }
                phase0TargetLensId?.let { targetLensId ->
                    com.bncam.core.debug.Phase0PerformanceTrace.lensTransitionMilestone(
                        targetLensId = targetLensId,
                        event = "session_creation_requested",
                        detail = "logical=${camera.id};reason=$reason"
                    )
                }
                camera.createCaptureSession(sessionConfig)

            } catch (e: Exception) {
                Log.e(tag, "Session creation failed", e)
                onSessionReady?.invoke(false)
            }
        }

    private data class SessionCloseTicket(
        val session: CameraCaptureSession,
        val closeBarrier: CompletableDeferred<Unit>,
        val reason: String,
        val generation: Int,
        val sessionEpoch: Long
    )

    private fun sessionCloseBarrierFor(session: CameraCaptureSession): CompletableDeferred<Unit> =
        synchronized(sessionLifecycleLock) {
            sessionCloseBarriers.getOrPut(session) { CompletableDeferred() }
        }

    /**
     * Requests retirement of exactly one CameraCaptureSession without pretending that Camera2 has
     * acknowledged the close. Android may configure a replacement session before dispatching
     * onClosed() for the previous session, so close-request and close-ack are deliberately separate.
     */
    private fun requestCaptureSessionClose(
        session: CameraCaptureSession,
        reason: String
    ): SessionCloseTicket {
        val ticket = SessionCloseTicket(
            session = session,
            closeBarrier = sessionCloseBarrierFor(session),
            reason = reason,
            generation = pipelineGeneration,
            sessionEpoch = sessionConfigurationEpoch
        )
        try {
            session.stopRepeating()
        } catch (t: Throwable) {
            Log.w(tag, "Failed to stop repeating before $reason", t)
        }
        try {
            session.abortCaptures()
        } catch (t: Throwable) {
            Log.w(tag, "Failed to abort in-flight requests before $reason", t)
        }
        if (captureSession === session) {
            captureSession = null
            currentCaptureRequest = null
            activeConfiguredSessionEpoch = -1L
            clearPendingPreviewControls("session_close:$reason")
        }
        session.close()
        Log.i(
            previewDiagnosticsTag,
            "event=CAPTURE_SESSION_CLOSE_REQUESTED generation=${ticket.generation} " +
                "epoch=${ticket.sessionEpoch} reason=$reason"
        )
        return ticket
    }

    /** Returns true only after the matching CameraCaptureSession.StateCallback.onClosed(). */
    private suspend fun awaitCaptureSessionClosed(
        ticket: SessionCloseTicket,
        timeoutMs: Long = SESSION_TRANSITION_TIMEOUT_MS
    ): Boolean {
        val closed = withTimeoutOrNull(timeoutMs.coerceAtLeast(0L)) {
            ticket.closeBarrier.await()
            true
        } ?: false
        if (!closed) {
            Log.e(
                tag,
                "Camera2 session onClosed acknowledgement timed out reason=${ticket.reason} " +
                    "generation=${ticket.generation} epoch=${ticket.sessionEpoch} state=$pipelineTransitionState"
            )
        }
        return closed
    }

    private fun recordPipelineResetLatency(
        requestedFormat: String,
        reason: String,
        identityWallMs: Double,
        sessionHandshakeWallMs: Double,
        totalWallMs: Double,
        outcome: String
    ) {
        val content =
            "requestedFormat=$requestedFormat;reason=$reason;outcome=$outcome;" +
                "identityWallMs=${String.format(Locale.US, "%.3f", identityWallMs)};" +
                "sessionHandshakeWallMs=${String.format(Locale.US, "%.3f", sessionHandshakeWallMs)};" +
                "totalWallMs=${String.format(Locale.US, "%.3f", totalWallMs)};" +
                "generation=$pipelineGeneration;activeFormat=${formatName(activeZslFormat)}"
        com.bncam.core.debug.DiagnosticsAggregator.record(
            stream = com.bncam.core.debug.DiagnosticsAggregator.Stream.PERFORMANCE,
            scope = "CAMERA PIPELINE",
            section = "PIPELINE RESET LATENCY",
            content = content
        )
        Log.i(previewDiagnosticsTag, "event=PIPELINE_RESET_LATENCY $content")
    }

    private suspend fun awaitCaptureSessionConfiguration(
        camera: CameraDevice,
        previewSurface: Surface?,
        reason: String,
        timeoutMs: Long = SESSION_TRANSITION_TIMEOUT_MS
    ): Boolean {
        val handshakeStartedNs = android.os.SystemClock.elapsedRealtimeNanos()
        val completion = CompletableDeferred<Boolean>()
        val settingsLensId = synchronized(pipelineLock) {
            activePipelineIdentity?.selectedLensId
        } ?: camera.id
        val sessionSettingsStartedNs = android.os.SystemClock.elapsedRealtimeNanos()
        val sessionSettings = runCatching {
            loadSessionRequestSettings(settingsLensId)
        }.getOrElse { error ->
            Log.e(tag, "Camera2 session settings load failed reason=$reason camera=${camera.id}", error)
            return false
        }
        val sessionSettingsWallMs =
            (android.os.SystemClock.elapsedRealtimeNanos() - sessionSettingsStartedNs) / 1_000_000.0
        val camera2ConfigureStartedNs = android.os.SystemClock.elapsedRealtimeNanos()
        createCaptureSession(
            camera = camera,
            previewSurface = previewSurface,
            sessionSettings = sessionSettings,
            reason = reason,
            onSessionReady = { success -> if (!completion.isCompleted) completion.complete(success) }
        )
        val configured = withTimeoutOrNull(timeoutMs) { completion.await() }
        val camera2ConfigureWallMs =
            (android.os.SystemClock.elapsedRealtimeNanos() - camera2ConfigureStartedNs) / 1_000_000.0
        val handshakeWallMs =
            (android.os.SystemClock.elapsedRealtimeNanos() - handshakeStartedNs) / 1_000_000.0
        val handshakeContent =
            "reason=$reason;camera=${camera.id};settingsLensId=$settingsLensId;" +
                "settingsWallMs=${String.format(Locale.US, "%.3f", sessionSettingsWallMs)};" +
                "camera2ConfigureWallMs=${String.format(Locale.US, "%.3f", camera2ConfigureWallMs)};" +
                "handshakeWallMs=${String.format(Locale.US, "%.3f", handshakeWallMs)};" +
                "configured=${configured ?: false};timeoutMs=$timeoutMs;generation=$pipelineGeneration"
        com.bncam.core.debug.DiagnosticsAggregator.record(
            stream = com.bncam.core.debug.DiagnosticsAggregator.Stream.PERFORMANCE,
            scope = "CAMERA PIPELINE",
            section = "SESSION CONFIGURATION LATENCY",
            content = handshakeContent
        )
        Log.i(previewDiagnosticsTag, "event=SESSION_CONFIGURATION_LATENCY $handshakeContent")
        if (configured == null) {
            synchronized(pipelineLock) {
                // Invalidate the pending callback. The caller will hard-close/restart; a late
                // onConfigured must never reclaim session ownership in the meantime.
                sessionConfigurationEpoch += 1L
                sessionConfiguredGeneration = -1
            }
            Log.e(
                tag,
                "Camera2 session handshake timed out reason=$reason generation=$pipelineGeneration"
            )
            return false
        }
        return configured
    }

    /**
     * Retarget an already-open logical multi-camera to another physical child without closing the
     * CameraDevice. This is the normal path for rear-camera sibling switches (for example main ->
     * tele) when Camera2 exposes both children under the same logical owner.
     *
     * Returns false when the routes are incompatible or when the session handover cannot be
     * proven complete. The caller may then use the deterministic hard-start fallback.
     */
    suspend fun handoverToLensWithinOpenLogicalCamera(
        cameraId: String,
        previewSurface: Surface,
        preferredFormat: String,
        profileId: String,
        previewWidth: Int = 0,
        previewHeight: Int = 0
    ): Boolean = pipelineTransitionMutex.withLock {
        if (pipelineTransitionState != PipelineTransitionState.PREVIEW_ATTACHED) {
            return@withLock false
        }
        if (isCapturing) return@withLock false

        val camera = cameraDevice ?: return@withLock false
        val previousIdentity = synchronized(pipelineLock) { activePipelineIdentity }
            ?: return@withLock false

        // Fast reject the common public-direct sensor switch before doing the expensive pipeline
        // identity work (stream maps, RAW capability checks and profile-backed preview binding).
        // Hidden/unqualified physical IDs remain on the full qualification path below.
        val quickRoute = resolveCameraDeviceRoute(cameraId)
        if (quickRoute.routeKind != CameraRouteKind.DIRECT_PROBE_PENDING &&
            (quickRoute.logicalCameraId != previousIdentity.logicalCameraId ||
                quickRoute.logicalCameraId != camera.id)
        ) {
            Log.i(
                previewDiagnosticsTag,
                "event=PHYSICAL_LENS_HANDOVER_FAST_REJECT reason=different_logical_owner " +
                    "from=${previousIdentity.physicalCameraId ?: previousIdentity.logicalCameraId} " +
                    "to=$cameraId oldLogical=${previousIdentity.logicalCameraId} " +
                    "newLogical=${quickRoute.logicalCameraId}"
            )
            return@withLock false
        }

        val requestedIdentity = runCatching {
            buildPipelineIdentity(cameraId, profileId, preferredFormat)
        }.getOrElse { error ->
            Log.e(tag, "PHYSICAL_LENS_HANDOVER_IDENTITY_FAILED target=$cameraId", error)
            return@withLock false
        } ?: return@withLock false

        val sameLogicalOwner =
            previousIdentity.logicalCameraId == requestedIdentity.logicalCameraId &&
                camera.id == requestedIdentity.logicalCameraId
        if (!sameLogicalOwner) {
            Log.i(
                previewDiagnosticsTag,
                "event=PHYSICAL_LENS_HANDOVER_HARD_FALLBACK reason=different_logical_owner " +
                    "from=${previousIdentity.physicalCameraId ?: previousIdentity.logicalCameraId} " +
                    "to=$cameraId oldLogical=${previousIdentity.logicalCameraId} " +
                    "newLogical=${requestedIdentity.logicalCameraId}"
            )
            return@withLock false
        }

        val previousSelectedLens = activeLensId
        pipelineTransitionState = PipelineTransitionState.LENS_SWITCHING
        stopWarmBufferWatchdog()
        activeLensId = cameraId
        lastPreviewSurface = previewSurface
        lastPreferredFormat = preferredFormat
        lastProfileId = profileId
        configuredPreviewStreamWidth = previewWidth
        configuredPreviewStreamHeight = previewHeight

        Log.i(
            previewDiagnosticsTag,
            "event=PHYSICAL_LENS_HANDOVER_BEGIN logical=${camera.id} " +
                "from=${previousIdentity.physicalCameraId ?: previousIdentity.logicalCameraId} " +
                "to=${requestedIdentity.physicalCameraId ?: requestedIdentity.logicalCameraId} " +
                "generation=$pipelineGeneration"
        )

        val success = performSoftResetPipeline(
            PendingPipelineResetRequest(
                previewSurface = previewSurface,
                newFormat = preferredFormat,
                profileId = profileId,
                forceSessionRebuild = false,
                reason = "SAME_LOGICAL_PHYSICAL_LENS_HANDOVER"
            )
        )

        if (!success) {
            Log.e(
                previewDiagnosticsTag,
                "event=PHYSICAL_LENS_HANDOVER_FAILED logical=${camera.id} " +
                    "from=${previousSelectedLens ?: "none"} to=$cameraId action=hard_close_for_fallback"
            )
            pipelineTransitionState = PipelineTransitionState.CLOSING
            closeCameraOwned("PHYSICAL_LENS_HANDOVER_FAILED")
            return@withLock false
        }

        val finalIdentity = synchronized(pipelineLock) { activePipelineIdentity }
        val routeConfirmed =
            finalIdentity?.logicalCameraId == requestedIdentity.logicalCameraId &&
                finalIdentity.physicalCameraId == requestedIdentity.physicalCameraId &&
                sessionConfiguredGeneration == pipelineGeneration &&
                captureSession != null && cameraDevice === camera
        if (!routeConfirmed) {
            Log.e(
                previewDiagnosticsTag,
                "event=PHYSICAL_LENS_HANDOVER_UNCONFIRMED logical=${camera.id} target=$cameraId " +
                    "generation=$pipelineGeneration action=hard_close_for_fallback"
            )
            pipelineTransitionState = PipelineTransitionState.CLOSING
            closeCameraOwned("PHYSICAL_LENS_HANDOVER_UNCONFIRMED")
            return@withLock false
        }

        pipelineTransitionState = PipelineTransitionState.PREVIEW_ATTACHED
        lifetimePhysicalHandoverCount.incrementAndGet()
        logCameraLifetimeCounters(
            event = "PHYSICAL_LENS_HANDOVER_READY",
            extra = "logical=${camera.id} from=${previousSelectedLens ?: "none"} to=$cameraId"
        )
        Log.i(
            previewDiagnosticsTag,
            "event=PHYSICAL_LENS_HANDOVER_READY logical=${camera.id} " +
                "from=${previousIdentity.physicalCameraId ?: previousIdentity.logicalCameraId} " +
                "to=${finalIdentity.physicalCameraId ?: finalIdentity.logicalCameraId} " +
                "generation=$pipelineGeneration cameraDeviceRetained=true"
        )
        writePipelineLifecycleDebug(
            event = "PHYSICAL_LENS_HANDOVER_READY",
            decision = null,
            extra = "logicalCameraId=${camera.id}\n" +
                "fromPhysical=${previousIdentity.physicalCameraId ?: "none"}\n" +
                "toPhysical=${finalIdentity.physicalCameraId ?: "none"}\n" +
                "generation=$pipelineGeneration\ncameraDeviceRetained=true\n" +
                "sessionConfigured=true"
        )
        true
    }

    /**
     * Reuse an already-active warm pipeline only when the exact same Compose Surface is still
     * attached to the current CameraCaptureSession. A new Surface can never be retargeted by
     * mutating a Kotlin reference; callers must use a real serialized handover or hard start.
     */
    suspend fun reattachPreviewToWarmPipeline(
        cameraId: String,
        previewSurface: Surface,
        preferredFormat: String,
        profileId: String,
        previewWidth: Int = 0,
        previewHeight: Int = 0
    ): Boolean = pipelineTransitionMutex.withLock {
        val reader = imageReader ?: return@withLock false
        val currentIdentity = synchronized(pipelineLock) { activePipelineIdentity } ?: return@withLock false

        // An actual lens switch can never be an exact warm-pipeline reattach. Reject it before
        // resolving stream maps/profile-backed identity for the target sensor.
        if (pipelineTransitionState != PipelineTransitionState.PREVIEW_ATTACHED ||
            cameraDevice == null || captureSession == null ||
            activeLensId != cameraId ||
            lastPreviewSurface !== previewSurface
        ) {
            return@withLock false
        }

        val requestedIdentity = runCatching {
            buildPipelineIdentity(cameraId, profileId, preferredFormat)
        }.getOrNull() ?: return@withLock false

        if (!currentIdentity.sameWarmProducerAs(requestedIdentity) ||
            reader.width != requestedIdentity.width ||
            reader.height != requestedIdentity.height ||
            reader.imageFormat != requestedIdentity.bufferFormat
        ) {
            return@withLock false
        }

        configuredPreviewStreamWidth = previewWidth
        configuredPreviewStreamHeight = previewHeight
        startWarmBufferWatchdog(pipelineGeneration)
        return@withLock runCatching {
            updatePreviewRepeatingRequest()
            synchronized(pipelineLock) { sessionConfiguredGeneration = pipelineGeneration }
            Log.i(tag, "WARM_PIPELINE_REUSED generation=$pipelineGeneration surfaceIdentityRetained=true")
            true
        }.getOrElse { error ->
            Log.w(tag, "Warm pipeline reuse failed; caller must perform serialized reconfiguration", error)
            false
        }
    }

    private fun finalizeHardCloseResources(
        retiringImageReader: ImageReader?,
        retiringCustomRawReader: ImageReader?,
        threadToStop: HandlerThread?,
        reason: String,
        onSafeToRelease: (() -> Unit)?
    ) {
        closeRetiredImageReader(retiringImageReader, "$reason:authoritative_reader")
        closeRetiredImageReader(retiringCustomRawReader, "$reason:custom_raw_reader")
        stopBackgroundThread(threadToStop)
        onSafeToRelease?.invoke()
        Log.i(
            previewDiagnosticsTag,
            "event=PIPELINE_HARD_CLOSE_RESOURCES_RETIRED reason=$reason " +
                "opening=${openingCameraDeviceCount.get()} closing=${closingCameraDeviceCount.get()}"
        )
    }

    private fun deferHardCloseResourceRetirement(
        sessionTicket: SessionCloseTicket?,
        deviceTicket: CameraDeviceCloseTicket?,
        pendingOpenSettled: CompletableDeferred<Unit>?,
        retiringImageReader: ImageReader?,
        retiringCustomRawReader: ImageReader?,
        threadToStop: HandlerThread?,
        reason: String,
        onSafeToRelease: (() -> Unit)?
    ) {
        sessionTransitionScope.launch {
            sessionTicket?.closeBarrier?.await()
            deviceTicket?.closeBarrier?.await()
            pendingOpenSettled?.await()
            while (openingCameraDeviceCount.get() > 0 || closingCameraDeviceCount.get() > 0) {
                delay(5L)
            }
            finalizeHardCloseResources(
                retiringImageReader,
                retiringCustomRawReader,
                threadToStop,
                "$reason:late_ack",
                onSafeToRelease
            )
        }
    }

    private suspend fun closeCameraOwned(
        reason: String,
        onSafeToRelease: (() -> Unit)? = null,
        retainBackgroundThread: Boolean = false
    ): Boolean {
        pipelineTransitionState = PipelineTransitionState.CLOSING
        abortViewfinderRebuildVisualTransition("CAMERA_CLOSING:$reason")
        stopWarmBufferWatchdog()
        captureAttempts.forceReset("camera_closed:$reason")
        rawPreviewRenderer.pauseAndAwaitIdle()

        val threadToStop = backgroundThread.takeUnless { retainBackgroundThread }
        if (retainBackgroundThread) {
            Log.i(
                previewDiagnosticsTag,
                "event=CAMERA_BACKGROUND_THREAD_RETAINED reason=$reason " +
                    "thread=${backgroundThread?.name ?: "none"}"
            )
        }
        val retiringImageReader = imageReader
        retiringImageReader?.setOnImageAvailableListener(null, null)
        imageReader = null
        val retiringCustomRawReader = detachCustomRawPreviewReaderForRetirement("camera_close:$reason")
        val pendingOpenSettled = synchronized(cameraDeviceLifecycleLock) { pendingCameraOpenSettled }

        lastPreviewSurface = null
        activeLensId = null
        lastCaptureResult = null
        lastCaptureResultGeneration = -1
        synchronized(pipelineLock) {
            pipelineGeneration = FrameGenerationId.increment()
            sessionConfigurationEpoch += 1L
            isPipelineResetting = false
            sessionConfiguredGeneration = -1
            pendingPipelineResetRequest = null
            activePipelineIdentity = null
        }
        ringBuffer.activateGeneration(pipelineGeneration)
        predictiveAfTracker.clear()
        _focusPeakingGuidance.value = FocusPeakingGuidance()
        refreshEffectiveViewfinderSource()
        clearPhysicalAfRegionState()
        transitionFocusOwner(FocusOwner.AUTO, "pipeline_close")
        clearTouchAeOverride()
        initialAeMeteringRegions = null
        initialAeMeteringGeneration = -1
        lastFaceMeteringRect = null
        lastFaceTrackingId = null
        _priorityFaceBounds.value = null
        _detectedFaces.value = emptyArray()
        lastPreviewOutputPhysicalCameraId = null
        lastImageReaderOutputPhysicalCameraId = null

        val retiringSession = captureSession
        val sessionTicket = retiringSession?.let {
            requestCaptureSessionClose(it, "HARD_CLOSE:$reason")
        }
        captureSession = null
        captureCallback = null
        currentCaptureRequest = null

        val retiringDevice = cameraDevice
        cameraDevice = null
        val deviceTicket = retiringDevice?.let {
            requestCameraDeviceClose(it, "HARD_CLOSE:$reason")
        }

        ringBuffer.clear()
        writePipelineLifecycleDebug(
            event = "PIPELINE_HARD_CLOSE_REQUESTED",
            decision = null,
            extra = "reason=$reason\nreaderReleaseDeferred=true\nactivePipelineAfterReset=none"
        )

        val deviceClosed = deviceTicket?.let { awaitCameraDeviceClosed(it) } ?: true
        val hardwareSettled = deviceClosed && awaitCameraHardwareClosed(
            maxWaitMs = CAMERA_HARD_CLOSE_RECOVERY_TIMEOUT_MS
        )
        val sessionClosed = when {
            sessionTicket == null -> true
            // CameraDevice.StateCallback.onClosed is the authoritative hardware-ownership
            // barrier. Android closes every session owned by that device before this callback.
            // Some HALs never dispatch the separate session onClosed callback after device.close;
            // waiting for it serialized every direct physical-ID switch behind a 2.5 s timeout.
            deviceTicket != null && hardwareSettled -> {
                if (!sessionTicket.closeBarrier.isCompleted) {
                    Log.i(
                        previewDiagnosticsTag,
                        "event=CAPTURE_SESSION_CLOSE_IMPLIED_BY_DEVICE_ACK " +
                            "generation=${sessionTicket.generation} epoch=${sessionTicket.sessionEpoch} " +
                            "reason=${sessionTicket.reason}"
                    )
                }
                true
            }
            else -> awaitCaptureSessionClosed(sessionTicket)
        }
        val safeToRelease = sessionClosed && hardwareSettled

        if (safeToRelease) {
            finalizeHardCloseResources(
                retiringImageReader,
                retiringCustomRawReader,
                threadToStop,
                reason,
                onSafeToRelease
            )
        } else {
            Log.e(
                tag,
                "PIPELINE_HARD_CLOSE_DEFERRED reason=$reason sessionClosed=$sessionClosed " +
                    "hardwareSettled=$hardwareSettled opening=${openingCameraDeviceCount.get()} " +
                    "closing=${closingCameraDeviceCount.get()}"
            )
            deferHardCloseResourceRetirement(
                sessionTicket = sessionTicket,
                deviceTicket = deviceTicket,
                pendingOpenSettled = pendingOpenSettled,
                retiringImageReader = retiringImageReader,
                retiringCustomRawReader = retiringCustomRawReader,
                threadToStop = threadToStop,
                reason = reason,
                onSafeToRelease = onSafeToRelease
            )
        }

        pipelineTransitionState = PipelineTransitionState.CLOSED
        Log.d(tag, "Camera hard close transaction finished reason=$reason safeToRelease=$safeToRelease")
        return safeToRelease
    }

    private fun enqueueCameraClose(
        reason: String,
        expectedSurface: Surface? = null,
        onSafeToRelease: (() -> Unit)? = null
    ) {
        val targetDevice = cameraDevice
        val targetGeneration = pipelineGeneration
        sessionTransitionScope.launch {
            pipelineTransitionMutex.withLock {
                val staleRequest = if (expectedSurface != null) {
                    lastPreviewSurface !== expectedSurface
                } else if (targetDevice != null) {
                    pipelineGeneration != targetGeneration && cameraDevice !== targetDevice
                } else {
                    pipelineGeneration != targetGeneration
                }
                if (staleRequest) {
                    Log.i(
                        previewDiagnosticsTag,
                        "event=STALE_CAMERA_CLOSE_SKIPPED reason=$reason targetGeneration=$targetGeneration " +
                            "activeGeneration=$pipelineGeneration surfaceStillOwned=false"
                    )
                    onSafeToRelease?.invoke()
                    return@withLock
                }
                closeCameraOwned(reason, onSafeToRelease)
            }
        }
    }

    /**
     * Final owner teardown for the Activity-scoped camera manager. Camera2/ImageReader resources
     * must finish their existing serialized hard-close transaction before process-resident preview
     * executors and manager coroutine scopes are cancelled.
     */
    fun shutdown(reason: String = "MANAGER_OWNER_DESTROYED") {
        if (!managerShutdownRequested.compareAndSet(false, true)) return

        // Stop accepting new UI publication immediately. Camera/resource retirement itself stays
        // serialized on sessionTransitionScope and cannot race an in-flight lens/session change.
        activeViewfinderCallbackRegistrationId = 0L
        effectiveViewfinderListener = null
        rawPreviewFrameListener = null
        histogramAnalysisEnabled = false
        qrAnalysisEnabled = false
        objectTrackingAnalysisEnabled = false
        portraitAnalysisEnabled = false
        clearFocusTrackingState(reason = "manager_shutdown", restoreConfiguredAf = false)
        latestPortraitMask.set(null)
        portraitSegmentationBusy.set(false)
        _detectedQrCode.value = null
        rawPreviewRenderer.setMlAnalysisRequested(false)
        stopWarmBufferWatchdog()

        sessionTransitionScope.launch {
            pipelineTransitionMutex.withLock {
                closeCameraOwned(
                    reason = "MANAGER_SHUTDOWN:$reason",
                    onSafeToRelease = { finalizeManagerShutdown(reason) }
                )
            }
        }
    }

    private fun finalizeManagerShutdown(reason: String) {
        if (!managerShutdownFinalized.compareAndSet(false, true)) return

        owningLifecycle?.lifecycle?.removeObserver(managerLifecycleObserver)
        phoneAssistanceSensorHelper.stopListening()
        trackingTimeoutJob?.cancel()
        tapFocusTimeoutJob?.cancel()
        pointLockJob?.cancel()
        focusAnalysisRequests.close()
        yuvAnalysisRequests.close()

        // Stop manager-owned analysis work before closing ML/native analysis clients. Barcode and
        // object-detector Tasks are not coroutine-owned, so the shutdown gates above also prevent
        // late completions from reactivating preview analysis or focus tracking.
        bufferAnalysisScope.coroutineContext[Job]?.cancel()
        focusTimingScope.coroutineContext[Job]?.cancel()
        runCatching { objectTracker.close() }.onFailure {
            Log.w(tag, "Object tracker close failed during manager shutdown: ${it.message}")
        }
        runCatching { barcodeScanner.close() }.onFailure {
            Log.w(tag, "Barcode scanner close failed during manager shutdown: ${it.message}")
        }
        runCatching { portraitSubjectSegmenter.close() }.onFailure {
            Log.w(tag, "Portrait subject segmenter close failed during manager shutdown: ${it.message}")
        }
        runCatching { mediaActionSound.release() }.onFailure {
            Log.w(tag, "MediaActionSound release failed during manager shutdown: ${it.message}")
        }

        // closeCameraOwned() already called pauseAndAwaitIdle() before this safe-release callback,
        // so no native Vulkan render can still own an output AHB when the renderer is destroyed.
        rawPreviewRenderer.close()

        rawPreviewScope.coroutineContext[Job]?.cancel()
        warmBufferWatchdogScope.coroutineContext[Job]?.cancel()

        if (activeInstance === this) activeInstance = null
        Log.i(tag, "CAMERA_MANAGER_SHUTDOWN_FINALIZED reason=$reason")

        // Cancel last: this callback itself runs on the transition scope after Camera2/device/readers
        // are acknowledged safe to release (including the deferred late-ack path).
        sessionTransitionScope.coroutineContext[Job]?.cancel()
    }

    fun closeCamera(reason: String = "EXTERNAL_CLOSE_REQUEST") {
        enqueueCameraClose(reason)
    }

    fun closeCameraForSurfaceRelease(
        surface: Surface,
        reason: String = "PREVIEW_SURFACE_OWNER_DISPOSED",
        onSafeToRelease: () -> Unit
    ) {
        Log.i(
            previewDiagnosticsTag,
            "event=PREVIEW_SURFACE_RETIRE_REQUESTED generation=$pipelineGeneration " +
                "surfaceIdentity=${System.identityHashCode(surface)} reason=$reason"
        )
        enqueueCameraClose(reason, expectedSurface = surface) {
            Log.i(
                previewDiagnosticsTag,
                "event=PREVIEW_SURFACE_SAFE_TO_RELEASE surfaceIdentity=${System.identityHashCode(surface)} " +
                    "reason=$reason"
            )
            onSafeToRelease()
        }
    }

    private fun selectStickyPriorityFace(validFaces: List<Face>): Face? {
        if (validFaces.isEmpty()) return null
        val previousId = lastFaceTrackingId
        if (previousId != null && previousId != Face.ID_UNSUPPORTED) {
            validFaces.firstOrNull { it.id == previousId }?.let { return it }
        }
        val previous = lastFaceMeteringRect
        if (previous != null) {
            val previousArea = max(1L, previous.width().toLong() * previous.height().toLong()).toFloat()
            val diagonal = kotlin.math.hypot(
                _sensorRect.value.width().toFloat(),
                _sensorRect.value.height().toFloat()
            ).coerceAtLeast(1f)
            val continuity = validFaces.map { face ->
                val box = face.bounds
                val dx = box.centerX() - previous.centerX()
                val dy = box.centerY() - previous.centerY()
                val centerDistance = kotlin.math.hypot(dx.toFloat(), dy.toFloat()) / diagonal
                val area = max(1L, box.width().toLong() * box.height().toLong()).toFloat()
                val sizePenalty = kotlin.math.abs(kotlin.math.ln((area / previousArea).coerceAtLeast(1e-4f).toDouble())).toFloat()
                val overlap = trackingIou(previous, box)
                face to (centerDistance * 2.0f + sizePenalty * 0.25f - overlap * 1.15f)
            }.minByOrNull { it.second }
            if (continuity != null && continuity.second <= 0.42f) return continuity.first
        }
        return validFaces.maxByOrNull {
            it.bounds.width().toLong() * it.bounds.height().toLong()
        }
    }

    fun setFaceIntelligence(
            faceDetection: Boolean,
            facePriorityFocus: Boolean
        ) {
            if (currentFaceDetectionRequested == faceDetection &&
                currentFacePriorityFocusEnabled == facePriorityFocus
            ) return
            currentFaceDetectionRequested = faceDetection
            currentFacePriorityFocusEnabled = facePriorityFocus
            enqueuePreviewControl("face_intelligence", "SET_FACE_INTELLIGENCE") {
                val builder = currentCaptureRequest ?: return@enqueuePreviewControl
                val deviceId = cameraDevice?.id ?: return@enqueuePreviewControl
                val chars = runCatching { cameraManager.getCameraCharacteristics(deviceId) }.getOrNull()
                    ?: return@enqueuePreviewControl
                applyFaceDetectionMode(
                    builder = builder,
                    characteristics = chars,
                    requestedForUi = faceDetection,
                    priorityFocus = facePriorityFocus
                )
                if (!facePriorityFocus && !_focusTrackingActive.value) {
                    if (_focusOwnership.value.owner == FocusOwner.FACE_PRIORITY) {
                        transitionFocusOwner(FocusOwner.AUTO, "face_priority_setting_disabled")
                    }
                    builder.set(CaptureRequest.CONTROL_AF_REGIONS, null)
                    builder.set(
                        CaptureRequest.CONTROL_AF_MODE,
                        resolveConfiguredIdleAfMode(deviceId)
                    )
                    traceAfWriter(builder, "setFaceIntelligence", "FACE_PRIORITY_AF_DISABLED")
                }
                if (!faceDetection && !facePriorityFocus) {
                    _detectedFaces.value = emptyArray()
                    lastFaceMeteringRect = null
                    lastFaceTrackingId = null
                    _priorityFaceBounds.value = null
                }
                updatePreviewRepeatingRequest()
            }
        }

        private fun applyFaceDetectionMode(
            builder: CaptureRequest.Builder,
            characteristics: CameraCharacteristics,
            requestedForUi: Boolean,
            priorityFocus: Boolean
        ) {
            val required = requestedForUi || priorityFocus
            val modes = characteristics.get(
                CameraCharacteristics.STATISTICS_INFO_AVAILABLE_FACE_DETECT_MODES
            ) ?: intArrayOf()
            val selectedMode = when {
                !required -> CaptureRequest.STATISTICS_FACE_DETECT_MODE_OFF
                modes.contains(CaptureRequest.STATISTICS_FACE_DETECT_MODE_FULL) ->
                    CaptureRequest.STATISTICS_FACE_DETECT_MODE_FULL
                modes.contains(CaptureRequest.STATISTICS_FACE_DETECT_MODE_SIMPLE) ->
                    CaptureRequest.STATISTICS_FACE_DETECT_MODE_SIMPLE
                else -> CaptureRequest.STATISTICS_FACE_DETECT_MODE_OFF
            }
            builder.set(CaptureRequest.STATISTICS_FACE_DETECT_MODE, selectedMode)
            if (required && selectedMode == CaptureRequest.STATISTICS_FACE_DETECT_MODE_OFF) {
                Log.w(tag, "Face intelligence requested but Camera2 reports no face-detection mode")
                _detectedFaces.value = emptyArray()
            }
            // Do not set CONTROL_SCENE_MODE_FACE_PRIORITY without CONTROL_MODE_USE_SCENE_MODE.
            // BnCam keeps normal Camera2 controls authoritative and implements optional face
            // focus via AF regions instead of silently handing the whole request to a scene mode.
            Log.i(
                tag,
                "Face intelligence applied mode=$selectedMode ui=$requestedForUi focus=$priorityFocus"
            )
        }

        // ========================================================
        // METERING ENGINE
        // ========================================================
        fun setMeteringStyle(style: String) {
            val resolved = MeteringMode.fromSetting(style).settingValue
            if (currentMeteringStyle == resolved) return
            currentMeteringStyle = resolved
            clearTouchAeOverride()
            Log.i(tag, "Metering Style updated to: $currentMeteringStyle")
            enqueuePreviewControl("preview_policy", "SET_METERING_STYLE") {
                restartDefaultRawAeReferenceForMeteringChange("METERING_STYLE_CHANGED")
                Log.i(tag, "Metering updated in-place for active generation=$pipelineGeneration.")
                updatePreviewRepeatingRequest()
            }
        }

        private fun clearTouchAeOverride() {
            activeTapAeRegion = null
            activeTapAePhysicalCameraId = null
        }

        fun updatePreviewRepeatingRequest() {
            val session = captureSession ?: return
            val request = currentCaptureRequest ?: return
            val handler = backgroundHandler ?: return
            applyMeteringPolicy(request)
            applyExposurePolicy(request)
            applyLiveWhiteBalancePolicy(request)
            try {
                submitRepeatingRequestWithProvenance(
                    session = session,
                    builder = request,
                    callback = captureCallback,
                    handler = handler,
                    reason = "PREVIEW_CONTROL_UPDATE"
                )
            } catch (e: Exception) {
                Log.e(tag, "Failed to update preview repeating request", e)
            }
        }

        private fun resolveMeteringCoordinateBounds(
            characteristics: CameraCharacteristics,
            builder: CaptureRequest.Builder
        ): Rect? {
            val maximumResolutionMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                builder.get(CaptureRequest.SENSOR_PIXEL_MODE) ==
                    CaptureRequest.SENSOR_PIXEL_MODE_MAXIMUM_RESOLUTION
            } else {
                false
            }
            val distortionCorrectionOff =
                builder.get(CaptureRequest.DISTORTION_CORRECTION_MODE) ==
                    CaptureRequest.DISTORTION_CORRECTION_MODE_OFF

            val resolved = when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    maximumResolutionMode && distortionCorrectionOff ->
                    characteristics.get(
                        CameraCharacteristics.SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE_MAXIMUM_RESOLUTION
                    ) ?: characteristics.get(
                        CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE_MAXIMUM_RESOLUTION
                    )

                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && maximumResolutionMode ->
                    characteristics.get(
                        CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE_MAXIMUM_RESOLUTION
                    )

                distortionCorrectionOff ->
                    characteristics.get(CameraCharacteristics.SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE)
                        ?: characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)

                else -> characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
            }
            return resolved?.let(::Rect)
        }

        private fun meteringRegionsSummary(regions: Array<MeteringRectangle>?): String =
            regions?.joinToString(prefix = "[", postfix = "]") { region ->
                val r = region.rect
                "${r.left},${r.top},${r.right},${r.bottom}@${region.meteringWeight}"
            } ?: "null"

        private fun mapMeteringRegions(
            plan: MeteringPlan,
            bounds: Rect?,
            maxRegions: Int
        ): Array<MeteringRectangle>? {
            if (!plan.supported || plan.restoreInitialAeRegions || bounds == null || maxRegions <= 0) {
                return null
            }
            return plan.regions
                .mapNotNull { it.toMeteringRectangle(bounds) }
                .take(maxRegions)
                .toTypedArray()
                .takeIf { it.isNotEmpty() }
        }

        private fun validateMeteringResultEcho(result: TotalCaptureResult) {
            if (!lastLogicalCustomAeRegionsRequested && !lastPhysicalCustomAeRegionsRequested) return

            val logicalEcho = meteringRegionsSummary(result.get(CaptureResult.CONTROL_AE_REGIONS))
            val physicalId = lastRequestedPhysicalAeCameraId
            val physicalEcho = if (
                physicalId != null
            ) {
                physicalCaptureResultOrNull(result, physicalId)
                    ?.get(CaptureResult.CONTROL_AE_REGIONS)
                    ?.let { meteringRegionsSummary(it) }
                    ?: "null"
            } else {
                "n/a"
            }
            val logicalMatch = !lastLogicalCustomAeRegionsRequested ||
                logicalEcho == lastRequestedLogicalAeRegionsSummary
            val physicalMatch = !lastPhysicalCustomAeRegionsRequested ||
                physicalEcho == lastRequestedPhysicalAeRegionsSummary
            val signature =
                "mode=$currentMeteringStyle;logicalReq=$lastRequestedLogicalAeRegionsSummary;logicalEcho=$logicalEcho;" +
                    "physicalId=${physicalId ?: "none"};physicalReq=$lastRequestedPhysicalAeRegionsSummary;" +
                    "physicalEcho=$physicalEcho;logicalMatch=$logicalMatch;physicalMatch=$physicalMatch"
            if (signature == lastMeteringEchoLogSignature) return
            lastMeteringEchoLogSignature = signature
            if (logicalMatch && physicalMatch) {
                Log.i("BNCAM_AE_METERING", "AE_REGION_ECHO_OK $signature")
            } else {
                Log.w("BNCAM_AE_METERING", "AE_REGION_ECHO_MISMATCH $signature")
            }
        }

        fun applyMeteringPolicy(builder: CaptureRequest.Builder) {
            val deviceId = cameraDevice?.id ?: return
            val chars = try {
                cameraManager.getCameraCharacteristics(deviceId)
            } catch (_: Exception) {
                return
            }

            val logicalMaxAeRegions = (chars.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AE) ?: 0)
                .coerceAtLeast(0)
            val logicalBounds = resolveMeteringCoordinateBounds(chars, builder)
            val cropRegion = builder.get(CaptureRequest.SCALER_CROP_REGION)
            val activePhysicalId = synchronized(pipelineLock) { activePipelineIdentity?.physicalCameraId }
            val physicalAeWritable = if (
                activePhysicalId != null
            ) {
                runCatching {
                    CaptureRequest.CONTROL_AE_REGIONS in chars.availablePhysicalCameraRequestKeys.orEmpty()
                }.getOrDefault(false)
            } else {
                false
            }
            val physicalChars = if (physicalAeWritable && activePhysicalId != null) {
                runCatching { cameraManager.getCameraCharacteristics(activePhysicalId) }.getOrNull()
            } else {
                null
            }
            val physicalMaxAeRegions = (physicalChars
                ?.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AE) ?: 0).coerceAtLeast(0)
            val physicalBounds = physicalChars?.let { resolveMeteringCoordinateBounds(it, builder) }
            val resolvedMode = MeteringMode.fromSetting(currentMeteringStyle)
            val logicalPlan = CameraMeteringPolicy.plan(
                mode = resolvedMode,
                maxAeRegions = logicalMaxAeRegions
            )
            val physicalPlan = CameraMeteringPolicy.plan(
                mode = resolvedMode,
                maxAeRegions = physicalMaxAeRegions
            )

            var logicalRequestedRegions: Array<MeteringRectangle>? = null
            var physicalRequestedRegions: Array<MeteringRectangle>? = null
            var logicalCustomRequested = false
            var physicalCustomRequested = false
            var touchOverrideApplied = false
            var source = ""

            // A tap region is already expressed in the coordinate domain of its owner. Never copy
            // a physical-sensor rectangle into the opened logical camera request (or vice versa).
            val touchRegion = activeTapAeRegion
            val touchPhysicalId = activeTapAePhysicalCameraId
            // Explicit metering selections are authoritative AE owners. A focus tap may
            // temporarily own AE only while metering is Auto; otherwise it remains AF-only.
            if (touchRegion != null && resolvedMode != MeteringMode.AUTO_DEFAULT_AE) {
                clearTouchAeOverride()
            }
            if (touchRegion != null && resolvedMode == MeteringMode.AUTO_DEFAULT_AE) {
                if (
                    touchPhysicalId != null && touchPhysicalId == activePhysicalId &&
                    physicalAeWritable && physicalMaxAeRegions > 0
                ) {
                    if (logicalMaxAeRegions > 0) {
                        builder.set(CaptureRequest.CONTROL_AE_REGIONS, null)
                    }
                    builder.setPhysicalCameraKey(
                        CaptureRequest.CONTROL_AE_REGIONS,
                        arrayOf(touchRegion),
                        touchPhysicalId
                    )
                    physicalRequestedRegions = arrayOf(touchRegion)
                    physicalCustomRequested = true
                    touchOverrideApplied = true
                    source = "touch_focus_override;domain=physical:$touchPhysicalId"
                } else if (touchPhysicalId == null && logicalMaxAeRegions > 0) {
                    builder.set(CaptureRequest.CONTROL_AE_REGIONS, arrayOf(touchRegion))
                    if (
                        physicalAeWritable && activePhysicalId != null && physicalMaxAeRegions > 0
                    ) {
                        builder.setPhysicalCameraKey(
                            CaptureRequest.CONTROL_AE_REGIONS,
                            null,
                            activePhysicalId
                        )
                    }
                    logicalRequestedRegions = arrayOf(touchRegion)
                    logicalCustomRequested = true
                    touchOverrideApplied = true
                    source = "touch_focus_override;domain=logical:$deviceId"
                } else {
                    // The route changed or the owner no longer supports AE regions. Retire the
                    // stale tap owner and fall through to the selected standard metering mode.
                    clearTouchAeOverride()
                }
            }

            if (!touchOverrideApplied) {
                logicalRequestedRegions = when {
                    logicalMaxAeRegions <= 0 -> null
                    logicalPlan.restoreInitialAeRegions -> initialAeMeteringRegions
                        ?.takeIf { initialAeMeteringGeneration == pipelineGeneration }
                        ?.copyOf()
                    else -> mapMeteringRegions(logicalPlan, logicalBounds, logicalMaxAeRegions)
                }
                if (logicalMaxAeRegions > 0) {
                    builder.set(CaptureRequest.CONTROL_AE_REGIONS, logicalRequestedRegions)
                }
                logicalCustomRequested =
                    resolvedMode != MeteringMode.AUTO_DEFAULT_AE && logicalRequestedRegions != null

                if (
                    physicalAeWritable && activePhysicalId != null && physicalMaxAeRegions > 0
                ) {
                    physicalRequestedRegions = if (physicalPlan.restoreInitialAeRegions) {
                        // No physical override exists in the warm template. Clearing the physical
                        // key restores inheritance/default behavior for Auto.
                        null
                    } else {
                        mapMeteringRegions(physicalPlan, physicalBounds, physicalMaxAeRegions)
                    }
                    builder.setPhysicalCameraKey(
                        CaptureRequest.CONTROL_AE_REGIONS,
                        physicalRequestedRegions,
                        activePhysicalId
                    )
                    physicalCustomRequested =
                        resolvedMode != MeteringMode.AUTO_DEFAULT_AE && physicalRequestedRegions != null
                }

                val logicalState = when {
                    logicalPlan.restoreInitialAeRegions -> "default"
                    logicalCustomRequested -> "custom"
                    logicalMaxAeRegions <= 0 -> "unsupported"
                    else -> "fallback"
                }
                val physicalState = when {
                    activePhysicalId == null -> "none"
                    !physicalAeWritable -> "not_writable"
                    physicalMaxAeRegions <= 0 -> "unsupported"
                    physicalPlan.restoreInitialAeRegions -> "default"
                    physicalCustomRequested -> "custom"
                    else -> "fallback"
                }
                source = "logical:$deviceId=$logicalState;physical:${activePhysicalId ?: "none"}=$physicalState"
            }

            lastRequestedLogicalAeRegionsSummary = meteringRegionsSummary(logicalRequestedRegions)
            lastRequestedPhysicalAeRegionsSummary = meteringRegionsSummary(physicalRequestedRegions)
            lastRequestedPhysicalAeCameraId = activePhysicalId
            lastLogicalCustomAeRegionsRequested = logicalCustomRequested
            lastPhysicalCustomAeRegionsRequested = physicalCustomRequested
            lastMeteringEchoLogSignature = ""

            // Metering spatial weighting and user EV are independent. The removed legacy BnCam
            // statistics controller no longer adds hidden auto-EV on top of the HAL's AE result.
            val evRange = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)
            val evStep = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)
            val profileCaptureEv = if (
                requestedManualIso == null && requestedManualExposureNs == null &&
                !activeProfileExposurePreferences.requiresAeBaseline()
            ) activeProfileExposurePreferences.captureEvBias else 0f
            val effectiveAeEv = currentEvOffset + profileCaptureEv
            if (evRange != null && evStep != null) {
                val stepEv = evStep.toFloat()
                val steps = if (stepEv.isFinite() && stepEv > 0f) {
                    (effectiveAeEv / stepEv).roundToInt()
                } else {
                    Log.w(tag, "Invalid Camera2 AE compensation step=$stepEv; applying neutral compensation")
                    0
                }
                builder.set(
                    CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION,
                    steps.coerceIn(evRange.lower, evRange.upper)
                )
            }

            lastMeteringPlanSummary =
                "mode=${resolvedMode.settingValue};source=$source;touchOverride=$touchOverrideApplied;" +
                    "logicalMaxAeRegions=$logicalMaxAeRegions;physicalMaxAeRegions=$physicalMaxAeRegions;" +
                    "logicalBounds=$logicalBounds;physicalBounds=$physicalBounds;crop=$cropRegion;" +
                    "logicalRequested=$lastRequestedLogicalAeRegionsSummary;" +
                    "physicalRequested=$lastRequestedPhysicalAeRegionsSummary;" +
                    "userEv=$currentEvOffset;profileCaptureEv=$profileCaptureEv;effectiveAeEv=$effectiveAeEv;hiddenAutoEv=disabled"
            traceAfWriter(builder, "applyMeteringPolicy", "AE_REGIONS_STANDARD_METERING_WRITE")
            Log.d(tag, "Camera2 metering plan $lastMeteringPlanSummary")
        }

        private fun Rect.clampedInside(bounds: Rect): Rect = Rect(
            left.coerceIn(bounds.left, bounds.right - 1),
            top.coerceIn(bounds.top, bounds.bottom - 1),
            right.coerceIn(bounds.left + 1, bounds.right),
            bottom.coerceIn(bounds.top + 1, bounds.bottom)
        )

        private fun NormalizedMeteringRegion.toMeteringRectangle(bounds: Rect): MeteringRectangle? {
            val mappedLeft = (bounds.left + left * bounds.width()).toInt().coerceIn(bounds.left, bounds.right - 1)
            val mappedTop = (bounds.top + top * bounds.height()).toInt().coerceIn(bounds.top, bounds.bottom - 1)
            val mappedRight = (bounds.left + right * bounds.width()).toInt().coerceIn(mappedLeft + 1, bounds.right)
            val mappedBottom = (bounds.top + bottom * bounds.height()).toInt().coerceIn(mappedTop + 1, bounds.bottom)
            val rect = Rect(mappedLeft, mappedTop, mappedRight, mappedBottom)
            return if (rect.width() > 0 && rect.height() > 0) MeteringRectangle(rect, weight) else null
        }

        private fun stabilizeRawPreviewAutoWb(
            targetGains: FloatArray,
            generation: Int
        ): FloatArray {
            if (targetGains.size < 4 || targetGains.take(4).any { !it.isFinite() || it <= 0f }) {
                return targetGains
            }
            val identity = synchronized(pipelineLock) { activePipelineIdentity }
            val scopeKey = identity?.physicalCameraId ?: identity?.logicalCameraId
            val stable = scopeKey?.let { whiteBalanceStateEngine.snapshot(it) }
            // Same-lens session generations intentionally retain the last stable state. The
            // generation parameter remains part of this boundary for diagnostics and to make it
            // explicit that cross-lens inheritance is forbidden by the state engine's scope key.
            if (stable != null) {
                if (stable.pipelineGeneration != generation) {
                    Log.d(tag, "RAW_PREVIEW_WB_RETAINED_ACROSS_GENERATION scope=$scopeKey from=${stable.pipelineGeneration} to=$generation")
                }
                return stable.copyGains()
            }
            return targetGains.copyOf(4)
        }

        private fun applyPhysicalRawPreviewAwbObservation(frame: RawPreviewFrame) {
            if (liveWhiteBalanceTargetSensorGains != null || !frame.physicalAwbDataReady) return
            if (frame.pipelineGeneration != pipelineGeneration || frame.sensorTimestampNs <= 0L) return
            val identity = synchronized(pipelineLock) { activePipelineIdentity } ?: return
            if (identity.bufferFormat != ImageFormat.RAW10 && identity.bufferFormat != ImageFormat.RAW_SENSOR) return
            val finalRgb = frame.physicalAwbFinalRgb
            if (finalRgb.size < 3 || finalRgb.any { !it.isFinite() || it <= 0f }) return
            val scopeKey = identity.physicalCameraId ?: identity.logicalCameraId
            val finalGains = floatArrayOf(finalRgb[0], 1f, 1f, finalRgb[2])
            val before = whiteBalanceStateEngine.snapshot(scopeKey)
            val after = whiteBalanceStateEngine.observePhysical(
                scopeKey = scopeKey,
                pipelineGeneration = frame.pipelineGeneration,
                finalGains = finalGains,
                colorMatrix = frame.camera2PriorColorMatrix,
                confidence = frame.physicalAwbConfidence,
                dataAuthority = frame.physicalAwbDataAuthority,
                neutralSupport = frame.physicalAwbNeutralSupport,
                mixedLightScore = frame.physicalAwbMixedLightScore,
                priorDisagreement = frame.physicalAwbPriorDisagreement,
                validTileCount = frame.physicalAwbValidTileCount,
                dataReady = frame.physicalAwbDataReady,
                sensorTimestampNs = frame.sensorTimestampNs
            ) ?: return
            val matrix = after.copyColorMatrix()
            if (matrix != null) {
                rawPreviewRenderer.updateAutoWhiteBalanceColorPair(after.copyGains(), matrix)
            }

            val nowMs = android.os.SystemClock.elapsedRealtime()
            val meaningfulTransition = before?.source != after.source ||
                before?.sceneChangeDetected != after.sceneChangeDetected
            if (meaningfulTransition || nowMs - lastPhysicalAwbDiagnosticsMs >= 1_000L) {
                lastPhysicalAwbDiagnosticsMs = nowMs
                val prior = frame.physicalAwbPriorRgb
                val data = frame.physicalAwbDataRgb
                val applied = after.copyGains()
                val matrixTruth = RawColorTransformEngine.validateSensorToLinearSrgbMatrix(
                    frame.camera2PriorColorMatrix
                )
                val matrixRowSums = matrixTruth.rowSums.joinToString(
                    prefix = "[", postfix = "]"
                ) { "%.4f".format(Locale.US, it) }
                Log.i(
                    tag,
                    "BNCAM_PHYSICAL_AWB scope=$scopeKey generation=${frame.pipelineGeneration} " +
                        "priorRgb=${prior.joinToString(prefix = "[", postfix = "]") { "%.4f".format(Locale.US, it) }} " +
                        "dataRgb=${data.joinToString(prefix = "[", postfix = "]") { "%.4f".format(Locale.US, it) }} " +
                        "finalRgb=${finalRgb.joinToString(prefix = "[", postfix = "]") { "%.4f".format(Locale.US, it) }} " +
                        "appliedRgb=[${"%.4f".format(Locale.US, applied[0])},1.0000,${"%.4f".format(Locale.US, applied[3])}] " +
                        "confidence=${"%.3f".format(Locale.US, frame.physicalAwbConfidence)} " +
                        "dataAuthority=${"%.3f".format(Locale.US, frame.physicalAwbDataAuthority)} " +
                        "neutralSupport=${"%.3f".format(Locale.US, frame.physicalAwbNeutralSupport)} " +
                        "validTiles=${frame.physicalAwbValidTileCount} accepted=${frame.physicalAwbAcceptedSampleCount} " +
                        "mixedLightScore=${"%.3f".format(Locale.US, frame.physicalAwbMixedLightScore)} " +
                        "priorDisagreement=${"%.3f".format(Locale.US, frame.physicalAwbPriorDisagreement)} " +
                        "ccmValid=${matrixTruth.valid} ccmDet=${"%.5f".format(Locale.US, matrixTruth.determinant)} " +
                        "ccmRowSums=$matrixRowSums ccmNeutralAxisDeviation=${"%.4f".format(Locale.US, matrixTruth.neutralAxisDeviation)} " +
                        "temporalDelta=${"%.3f".format(Locale.US, after.temporalDelta)} " +
                        "sceneChange=${after.sceneChangeDetected} source=${after.source}"
                )
            }
        }

        private fun updateAutoWhiteBalanceState(
            result: TotalCaptureResult,
            generation: Int
        ) {
            val identity = synchronized(pipelineLock) { activePipelineIdentity } ?: return
            val scopeKey = identity.physicalCameraId ?: identity.logicalCameraId
            val calibrationResult = previewCaptureResult(result, identity.physicalCameraId)
                ?: run {
                    Log.w(
                        "SensorAuthority",
                        "PHYSICAL_METADATA_UNAVAILABLE context=WB_OBSERVATION " +
                            "physicalCameraId=${identity.physicalCameraId ?: "none"} " +
                            "frameNumber=${result.frameNumber}; skipping WB observation"
                    )
                    return
                }
            val gains = calibrationResult.get(CaptureResult.COLOR_CORRECTION_GAINS) ?: return
            val awbState = calibrationResult.get(CaptureResult.CONTROL_AWB_STATE)
            val convergence = when (awbState) {
                CaptureResult.CONTROL_AWB_STATE_CONVERGED -> WhiteBalanceConvergence.CONVERGED
                CaptureResult.CONTROL_AWB_STATE_LOCKED -> WhiteBalanceConvergence.LOCKED
                CaptureResult.CONTROL_AWB_STATE_SEARCHING -> WhiteBalanceConvergence.SEARCHING
                CaptureResult.CONTROL_AWB_STATE_INACTIVE -> WhiteBalanceConvergence.INACTIVE
                else -> WhiteBalanceConvergence.UNKNOWN
            }
            val camera2Gains = floatArrayOf(gains.red, gains.greenEven, gains.greenOdd, gains.blue)
            val transform = calibrationResult.get(CaptureResult.COLOR_CORRECTION_TRANSFORM)
            val camera2Matrix = transform?.let(RawColorTransformEngine::colorSpaceTransformToArray)
            val before = whiteBalanceStateEngine.snapshot(scopeKey)
            val after = whiteBalanceStateEngine.observe(
                scopeKey = scopeKey,
                pipelineGeneration = generation,
                gains = camera2Gains,
                convergence = convergence,
                colorMatrix = camera2Matrix,
                sensorTimestampNs = calibrationResult.get(CaptureResult.SENSOR_TIMESTAMP)
                    ?: 0L
            )
            if (liveWhiteBalanceTargetSensorGains == null) {
                val timestampNs = calibrationResult.get(CaptureResult.SENSOR_TIMESTAMP)
                    ?: 0L
                if (camera2Matrix != null && timestampNs > 0L) {
                    // Exact timestamp-matched Camera2 metadata remains the independent physical
                    // prior. The temporal render pair is stored separately so filtered output can
                    // never feed itself back into PhysicalAwbEstimator.
                    rawPreviewRenderer.updateExactFrameCamera2ColorPair(
                        sensorTimestampNs = timestampNs,
                        gains = camera2Gains,
                        colorMatrix = camera2Matrix
                    )
                    val temporalMatrix = after?.copyColorMatrix()
                    if (after != null && temporalMatrix != null) {
                        rawPreviewRenderer.updateAutoWhiteBalanceColorPair(
                            after.copyGains(), temporalMatrix
                        )
                    }
                }
            }
            if (after != null && (before == null || (after.sceneChangeDetected && before?.sceneChangeDetected != true))) {
                Log.i(
                    tag,
                    "BNCAM_AWB_TEMPORAL scope=$scopeKey generation=$generation " +
                        "confidence=${after.confidence} samples=${after.acceptedSampleCount} " +
                        "convergence=${after.convergence} temporalDelta=${after.temporalDelta} " +
                        "sceneChange=${after.sceneChangeDetected}"
                )
            }
        }

        private fun stableAutoWhiteBalanceSnapshotForActiveCamera(): StableWhiteBalanceSnapshot? {
            val identity = synchronized(pipelineLock) { activePipelineIdentity } ?: return null
            val scopeKey = identity.physicalCameraId ?: identity.logicalCameraId
            return whiteBalanceStateEngine.snapshot(scopeKey)?.takeIf { it.confidence >= 0.55f }
        }

        /**
         * BnCam manual/profile WB is implemented in our own colour pipeline. It therefore does
         * not depend on vendor support for CONTROL_AWB_MODE_OFF / manual Camera2 colour keys.
         * Keeping Camera2 AWB running also preserves trustworthy per-frame neutral metadata.
         */
        fun supportsManualWhiteBalance(cameraId: String): Boolean =
            runCatching { cameraManager.getCameraCharacteristics(cameraId) }.isSuccess

        private fun liveWhiteBalanceCharacteristics(cameraId: String): CameraCharacteristics? {
            synchronized(liveWhiteBalanceCharacteristicsLock) {
                if (liveWhiteBalanceCharacteristicsCameraId == cameraId) {
                    cachedLiveWhiteBalanceCharacteristics?.let { return it }
                }
            }
            val resolved = runCatching { cameraManager.getCameraCharacteristics(cameraId) }.getOrNull()
                ?: return null
            synchronized(liveWhiteBalanceCharacteristicsLock) {
                liveWhiteBalanceCharacteristicsCameraId = cameraId
                cachedLiveWhiteBalanceCharacteristics = resolved
            }
            return resolved
        }

        fun setViewfinderWhiteBalance(
            profileSettings: ProfileAwbSettings,
            liveKelvin: Int?,
            cameraId: String
        ) {
            val requestEpoch = liveWhiteBalanceRequestEpoch.incrementAndGet()
            val requestedKelvin = liveKelvin?.coerceIn(2000, 10000)
            val base = profileSettings.sanitized()
            liveWhiteBalanceResolutionJob?.cancel()
            liveWhiteBalanceResolutionJob = sessionTransitionScope.launch {
                val effective = requestedKelvin?.let { targetKelvin ->
                    base.copy(
                        mode = ProfileAwbModes.MANUAL_KELVIN,
                        kelvin = targetKelvin,
                        illuminantModel = if (targetKelvin >= 4000) {
                            ProfileAwbModels.CIE_DAYLIGHT
                        } else {
                            ProfileAwbModels.PLANCKIAN
                        }
                    ).sanitized()
                } ?: base

                val targetColorSolution = if (effective.mode == ProfileAwbModes.SYSTEM_AUTO) {
                    null
                } else {
                    liveWhiteBalanceCharacteristics(cameraId)?.let { characteristics ->
                        runCatching {
                            RawColorTransformEngine.computeProfileWhiteBalance(characteristics, effective)
                        }.getOrNull()?.takeIf { solution ->
                            solution.isValid && solution.bayerWbGains.size >= 4 &&
                                solution.bayerWbGains.take(4).all { it.isFinite() && it in 0.35f..4.50f } &&
                                RawColorTransformEngine.validateSensorToLinearSrgbMatrix(solution.mPostCompensated).valid
                        }
                    }
                }
                val targetSensorGains = targetColorSolution?.bayerWbGains
                val targetColorMatrix = targetColorSolution?.mPostCompensated

                if (requestEpoch != liveWhiteBalanceRequestEpoch.get()) return@launch

                requestedLiveWhiteBalanceKelvin = requestedKelvin
                liveWhiteBalanceTargetSensorGains = targetSensorGains?.copyOf(4)
                if (targetSensorGains != null && targetColorMatrix != null) {
                    // Explicit profile/manual WB intentionally overrides System-Auto state. Publish
                    // the calibrated WB diagonal + post-WB CCM atomically; a gain-only override
                    // would pair a new illuminant with the previous Camera2 color transform.
                    rawPreviewRenderer.clearExactFrameCamera2ColorPairs()
                    rawPreviewRenderer.clearAutoWhiteBalanceColorPair()
                    rawPreviewRenderer.updateWhiteBalanceColorPair(targetSensorGains, targetColorMatrix)
                } else {
                    // System Auto keeps Camera2 exact-frame metadata as the physical prior while
                    // the independent temporal pair controls rendering once validated.
                    rawPreviewRenderer.updateWhiteBalanceGains(null)
                    val stable = stableAutoWhiteBalanceSnapshotForActiveCamera()
                    val stableMatrix = stable?.copyColorMatrix()
                    if (stable != null && stableMatrix != null) {
                        rawPreviewRenderer.updateAutoWhiteBalanceColorPair(stable.copyGains(), stableMatrix)
                    } else {
                        rawPreviewRenderer.clearAutoWhiteBalanceColorPair()
                    }
                    lastRawPreviewConfigRefreshMs = 0L
                }
                if (targetSensorGains == null) {
                    _liveWhiteBalanceDisplayCompensation.value = LiveWhiteBalanceDisplayCompensation()
                }
                lastLiveWhiteBalanceSummary = when {
                    effective.mode == ProfileAwbModes.SYSTEM_AUTO -> "BNCAM_AUTO;lens=$cameraId"
                    targetSensorGains == null ->
                        "BNCAM_PROFILE_TARGET_UNAVAILABLE;mode=${effective.mode};kelvin=${effective.kelvin}K;lens=$cameraId"
                    requestedKelvin != null ->
                        "BNCAM_LIVE;kelvin=${effective.kelvin}K;tint=${effective.tint};lens=$cameraId"
                    else ->
                        "BNCAM_PROFILE;mode=${effective.mode};kelvin=${effective.kelvin}K;tint=${effective.tint};lens=$cameraId"
                }
            }
            // No Camera2 request/session update. RAW consumes an atomic per-frame render target;
            // YUV maps the same absolute target relative to the latest HAL AWB result below.
        }

        fun setLiveWhiteBalanceKelvin(kelvin: Int?, cameraId: String) {
            setViewfinderWhiteBalance(ProfileAwbSettings(), kelvin, cameraId)
        }

        private fun updateLiveWhiteBalanceDisplayCompensation(result: CaptureResult) {
            // Manual/profile/live WB supplies an explicit absolute target. System Auto remains
            // continuously estimated by Camera2; BnCam no longer owns a separate AWB-lock state.
            val target = liveWhiteBalanceTargetSensorGains ?: run {
                if (_liveWhiteBalanceDisplayCompensation.value.active) {
                    _liveWhiteBalanceDisplayCompensation.value = LiveWhiteBalanceDisplayCompensation()
                }
                return
            }
            val identity = synchronized(pipelineLock) { activePipelineIdentity } ?: return
            if (identity.bufferFormat != ImageFormat.YUV_420_888) return
            val nowMs = android.os.SystemClock.elapsedRealtime()
            if (nowMs - lastLiveWhiteBalanceDisplayUpdateMs < 33L) return
            val base = result.get(CaptureResult.COLOR_CORRECTION_GAINS) ?: return
            val relative = ProfileYuvAwbMapper.resolve(
                baseCameraGains = floatArrayOf(base.red, base.greenEven, base.greenOdd, base.blue),
                effectiveProfileGains = target
            )
            val next = LiveWhiteBalanceDisplayCompensation(
                red = relative.getOrElse(0) { 1f }.coerceIn(0.5f, 2f),
                green = relative.getOrElse(1) { 1f }.coerceIn(0.5f, 2f),
                blue = relative.getOrElse(2) { 1f }.coerceIn(0.5f, 2f),
                active = true
            )
            val previous = _liveWhiteBalanceDisplayCompensation.value
            val materiallyChanged = !previous.active ||
                abs(previous.red - next.red) >= 0.005f ||
                abs(previous.green - next.green) >= 0.005f ||
                abs(previous.blue - next.blue) >= 0.005f
            if (materiallyChanged) {
                _liveWhiteBalanceDisplayCompensation.value = next
            }
            lastLiveWhiteBalanceDisplayUpdateMs = nowMs
        }

        private fun applyLiveWhiteBalancePolicy(builder: CaptureRequest.Builder) {
            // Camera2 AWB remains the continuously-running neutral estimator. Manual/profile/live
            // WB is applied by BnCam's colour pipeline; there is intentionally no user AWB lock.
            builder.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
            // Explicitly clear a stale lock bit on a recycled request builder from older app state.
            runCatching { builder.set(CaptureRequest.CONTROL_AWB_LOCK, false) }
            runCatching {
                builder.set(CaptureRequest.COLOR_CORRECTION_MODE, CaptureRequest.COLOR_CORRECTION_MODE_FAST)
            }
            lastLiveWhiteBalanceSummary = if (requestedLiveWhiteBalanceKelvin == null) {
                "CAMERA2_AUTO+BNCAM_PROFILE"
            } else {
                "CAMERA2_AUTO+BNCAM_LIVE_${requestedLiveWhiteBalanceKelvin}K"
            }
        }

        fun setExposure(exposureEV: Float, cameraId: String) {
            currentEvOffset = exposureEV.coerceIn(-2f, 2f)
            if (activeProfileExposurePreferences.requiresAeBaseline() &&
                requestedManualIso == null && requestedManualExposureNs == null
            ) {
                // Live EV is an exposure-product offset under Priority. Keep the original fresh AE
                // baseline so changing EV cannot compound the shutter/ISO multiplier.
                activeProfileExposurePlan = null
                activeProfileExposurePlanGeneration = -1
                profileExposureLastAdaptationElapsedNs = 0L
                val baselineReady =
                    profileExposureAeBaselineGeneration == pipelineGeneration &&
                        profileExposureAeBaselineIso != null && profileExposureAeBaselineExposureNs != null
                profileExposureAwaitingAeBaseline = !baselineReady
                if (!baselineReady) {
                    profileExposureBootstrapMinControlEpoch = controlRequestEpochTracker.currentSubmittedEpoch() + 1L
                }
            }
            enqueuePreviewControl("preview_policy", "SET_EXPOSURE_EV:$cameraId") {
                updatePreviewRepeatingRequest()
            }
        }

        // ========================================================
        // MANUAL EXPOSURE (ISO & SHUTTER SPEED)
        // ========================================================
        fun setManualIsoAndShutter(iso: Int?, shutterSpeedNs: Long?, cameraId: String) {
            requestedManualIso = iso
            requestedManualExposureNs = shutterSpeedNs
            // Manual exposure owns the request while active. Returning to AUTO must reacquire a
            // fresh Camera2 AE baseline instead of reusing a pre-manual target sensitivity.
            clearDefaultRawShutterPriorityState(resetMotion = false)
            activeProfileExposurePlan = null
            activeProfileExposurePlanGeneration = -1
            val returningToProfilePriority =
                iso == null && shutterSpeedNs == null &&
                    activeProfileExposurePreferences.requiresAeBaseline()
            if (returningToProfilePriority) {
                // Manual exposure may have been active for an arbitrary interval. Do not reuse the
                // pre-manual AE baseline; reacquire one from a proven repeating AE request.
                clearProfileExposureAeBaseline()
                profileExposureAwaitingAeBaseline = true
                profileExposureBootstrapMinControlEpoch = controlRequestEpochTracker.currentSubmittedEpoch() + 1L
            } else {
                profileExposureAwaitingAeBaseline = false
            }
            Log.i(tag, "Exposure controls requested lens=$cameraId iso=${iso ?: "AUTO"} shutterNs=${shutterSpeedNs ?: "AUTO"}")
            enqueuePreviewControl("preview_policy", "SET_MANUAL_EXPOSURE:$cameraId") {
                updatePreviewRepeatingRequest()
            }
        }

        private fun resolveExposurePlan(characteristics: CameraCharacteristics): ExposurePlan? {
            val isoRange = characteristics.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
            val exposureRange = characteristics.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
            if (isoRange == null || exposureRange == null) {
                lastExposurePlanSummary = "mode=AUTO;manualUnsupported=true;reason=missing_sensor_ranges"
                return null
            }
            val resolvedMode = MeteringMode.fromSetting(currentMeteringStyle)
            val modeMultiplier = when (resolvedMode) {
                MeteringMode.SPOT -> 0.70f
                MeteringMode.CENTER_WEIGHTED -> 1.00f
                MeteringMode.FRAME_AVERAGE -> 1.30f
                MeteringMode.AUTO_DEFAULT_AE -> 1.00f
            }
            val bounds = ExposureBounds(
                minIso = isoRange.lower,
                maxIso = isoRange.upper,
                minExposureNs = exposureRange.lower,
                maxExposureNs = exposureRange.upper
            )
            val plan = CameraExposurePolicy.resolve(
                requestedIso = requestedManualIso,
                requestedExposureNs = requestedManualExposureNs,
                measuredIso = lastCaptureResult?.get(CaptureResult.SENSOR_SENSITIVITY)?.takeIf { it > 0 },
                measuredExposureNs = lastCaptureResult?.get(CaptureResult.SENSOR_EXPOSURE_TIME)?.takeIf { it > 0L },
                bounds = bounds
            )
            if (requestedManualIso != null && requestedManualExposureNs == null && plan.exposureTimeNs != null) {
                val adaptedExposureNs = (plan.exposureTimeNs * modeMultiplier).toLong()
                    .coerceIn(bounds.minExposureNs, bounds.maxExposureNs)
                return plan.copy(
                    exposureTimeNs = adaptedExposureNs,
                    frameDurationNs = max(adaptedExposureNs, bounds.minExposureNs)
                )
            }
            return plan
        }

        private fun resolveProfileExposurePriorityPlan(
            characteristics: CameraCharacteristics
        ): ProfileExposurePriorityPlan? {
            val preferences = activeProfileExposurePreferences.sanitized()
            if (!preferences.requiresAeBaseline()) {
                activeProfileExposurePlan = null
                activeProfileExposurePlanGeneration = -1
                activeProfileExposureBounds = null
                profileExposureAwaitingAeBaseline = false
                clearProfileExposureAeBaseline()
                return null
            }
            val isoRange = characteristics.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
            val exposureRange = characteristics.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
            if (isoRange == null || exposureRange == null) {
                activeProfileExposurePlan = null
                activeProfileExposurePlanGeneration = -1
                activeProfileExposureBounds = null
                profileExposureAwaitingAeBaseline = false
                clearProfileExposureAeBaseline()
                lastExposurePlanSummary =
                    "shotBias=${preferences.shotBiasExposure.persistedValue};auto=true;reason=missing_sensor_ranges"
                return null
            }

            val currentGeneration = pipelineGeneration
            activeProfileExposurePlan?.takeIf {
                activeProfileExposurePlanGeneration == currentGeneration &&
                    it.ready && !it.autoExposure
            }?.let { return it }

            val bounds = ExposureBounds(
                minIso = isoRange.lower,
                maxIso = isoRange.upper,
                minExposureNs = exposureRange.lower,
                maxExposureNs = preferences.maxFrameExposure.resolveNs(exposureRange.upper)
                    .coerceIn(exposureRange.lower, exposureRange.upper)
            )
            activeProfileExposureBounds = bounds
            val baselineReady =
                profileExposureAeBaselineGeneration == currentGeneration &&
                    profileExposureAeBaselineIso != null && profileExposureAeBaselineExposureNs != null &&
                    !profileExposureAwaitingAeBaseline
            if (!baselineReady && !profileExposureAwaitingAeBaseline) {
                profileExposureAwaitingAeBaseline = true
                profileExposureBootstrapMinControlEpoch = controlRequestEpochTracker.currentSubmittedEpoch() + 1L
            }
            val plan = ProfileExposurePriorityPlanner.initialPlan(
                preferences = preferences,
                measuredIso = profileExposureAeBaselineIso.takeIf { baselineReady },
                measuredExposureNs = profileExposureAeBaselineExposureNs.takeIf { baselineReady },
                bounds = bounds,
                additionalEvBias = currentEvOffset
            )
            activeProfileExposurePlan = plan
            activeProfileExposurePlanGeneration = currentGeneration
            profileExposureAwaitingAeBaseline = !plan.ready
            return plan
        }

        private fun setAePriorityModeOff(builder: CaptureRequest.Builder) {
            if (Build.VERSION.SDK_INT >= 36) {
                runCatching {
                    builder.set(
                        CaptureRequest.CONTROL_AE_PRIORITY_MODE,
                        CaptureRequest.CONTROL_AE_PRIORITY_MODE_OFF
                    )
                }
            }
        }

        private fun supportsExposureTimePriority(characteristics: CameraCharacteristics): Boolean {
            if (Build.VERSION.SDK_INT < 36) return false
            val modes = runCatching {
                characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_PRIORITY_MODES)
            }.getOrNull() ?: return false
            return modes.contains(CaptureRequest.CONTROL_AE_PRIORITY_MODE_SENSOR_EXPOSURE_TIME_PRIORITY)
        }

        private fun resolveDefaultRawShutterPriorityPlan(
            characteristics: CameraCharacteristics,
            builder: CaptureRequest.Builder
        ): DefaultRawShutterPriorityPlan? {
            if (!shouldRequestDefaultRawShutterMotionAnalysis()) {
                if (defaultRawShutterAeBaselineGeneration != -1 || defaultRawShutterAwaitingAeBaseline) {
                    clearDefaultRawShutterPriorityState(resetMotion = false)
                }
                return null
            }
            val isoRange = characteristics.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE) ?: return null
            val exposureRange = characteristics.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE) ?: return null
            val generation = pipelineGeneration
            val baselineReady = defaultRawShutterAeBaselineGeneration == generation &&
                defaultRawShutterAeBaselineIso != null && defaultRawShutterAeBaselineExposureNs != null &&
                !defaultRawShutterAwaitingAeBaseline
            if (!baselineReady) {
                if (!defaultRawShutterAwaitingAeBaseline) {
                    defaultRawShutterAwaitingAeBaseline = true
                    defaultRawShutterBootstrapMinControlEpoch =
                        controlRequestEpochTracker.currentSubmittedEpoch() + 1L
                }
                return DefaultRawShutterPriorityPolicy.resolve(
                    measuredIso = null,
                    measuredExposureNs = null,
                    bounds = ExposureBounds(
                        isoRange.lower, isoRange.upper, exposureRange.lower, exposureRange.upper
                    ),
                    ceilings = RawShutterSafetyCeilings(),
                    flickerConstraint = resolveDefaultRawFlickerConstraint()
                )
            }

            val motion = latestDefaultRawMotion?.takeIf {
                latestDefaultRawMotionGeneration == generation && it.ready
            }
            val fpsLower = builder.get(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE)?.lower
                ?.takeIf { it > 0 }
            val baselineExposure = defaultRawShutterAeBaselineExposureNs!!
            // Never shorten a stable Camera2 baseline solely because an FPS range was advertised.
            // Motion may still demand a shorter exposure. The cadence ceiling only limits quality-
            // seeking extensions beyond the HAL's already-realized baseline.
            val cadenceCeiling = fpsLower?.let { fps ->
                max(baselineExposure, 1_000_000_000L / fps.toLong())
                    .coerceIn(exposureRange.lower, exposureRange.upper)
            }
            val profile = DynamicSensorProfile.fromCharacteristics(characteristics)
            val lensStability = profile.maxHandheldShutterNs.coerceIn(exposureRange.lower, exposureRange.upper)
            val nearClip = latestExposureStatistics?.rawNearClipFraction

            return DefaultRawShutterPriorityPolicy.resolve(
                measuredIso = defaultRawShutterAeBaselineIso,
                measuredExposureNs = baselineExposure,
                bounds = ExposureBounds(
                    isoRange.lower, isoRange.upper, exposureRange.lower, exposureRange.upper
                ),
                ceilings = RawShutterSafetyCeilings(
                    cameraMotionNs = motion?.cameraExposureCeilingNs,
                    sceneMotionNs = motion?.sceneExposureCeilingNs,
                    streamCadenceNs = cadenceCeiling,
                    lensStabilityNs = lensStability,
                    allowLensStabilityFallback = true
                ),
                flickerConstraint = resolveDefaultRawFlickerConstraint(),
                rawNearClipFraction = nearClip
            )
        }

        private fun applyExposurePolicy(builder: CaptureRequest.Builder) {
            val deviceId = cameraDevice?.id ?: return
            // Cadence and shutter are one acquisition contract. Resolve FPS first so the exposure
            // planner sees the same frame timing that the repeating request will actually submit.
            applyOptimalAeTargetFpsRange(builder, deviceId)
            val characteristics = runCatching { cameraManager.getCameraCharacteristics(deviceId) }.getOrNull() ?: return
            val explicitManual = requestedManualIso != null || requestedManualExposureNs != null
            val profilePlan = if (!explicitManual) resolveProfileExposurePriorityPlan(characteristics) else null

            if (profilePlan != null) {
                updateDefaultRawFrameSelectionExposureConstraint(null, "PROFILE_EXPOSURE_AUTHORITY")
                if (profilePlan.autoExposure || !profilePlan.ready) {
                    manualExposureAwaitingMetadata = false
                    profileExposureAwaitingAeBaseline = !profilePlan.ready
                    val flashControlPlan = resolveFlashControlPlan(characteristics, currentFlashMode)
                    builder.set(
                        CaptureRequest.CONTROL_AE_MODE,
                        flashControlPlan?.aeMode ?: CaptureRequest.CONTROL_AE_MODE_ON
                    )
                    setAePriorityModeOff(builder)
                    builder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF)
                    builder.set(CaptureRequest.SENSOR_SENSITIVITY, null)
                    builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, null)
                    builder.set(CaptureRequest.SENSOR_FRAME_DURATION, null)
                    lastExposurePlanSummary = "profilePriority=true;${profilePlan.summary()}"
                    Log.d(tag, "Camera2 profile exposure bootstrap $lastExposurePlanSummary")
                    return
                }

                manualExposureAwaitingMetadata = false
                profileExposureAwaitingAeBaseline = false
                setAePriorityModeOff(builder)
                builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                builder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF)
                builder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, 0)
                builder.set(CaptureRequest.SENSOR_SENSITIVITY, profilePlan.sensitivityIso!!)
                builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, profilePlan.exposureTimeNs!!)
                builder.set(CaptureRequest.SENSOR_FRAME_DURATION, profilePlan.frameDurationNs!!)
                lastExposurePlanSummary =
                    "profilePriority=true;${profilePlan.summary()};flashSuppressed=${currentFlashMode != "Off"}"
                Log.d(tag, "Camera2 profile exposure plan $lastExposurePlanSummary")
                return
            }

            val defaultRawPlan = if (!explicitManual) {
                resolveDefaultRawShutterPriorityPlan(characteristics, builder)
            } else null
            if (defaultRawPlan != null) {
                if (!defaultRawPlan.ready) {
                    defaultRawAllocationReady = false
                    updateDefaultRawFrameSelectionExposureConstraint(null, "DEFAULT_RAW_AE_BOOTSTRAP")
                    defaultRawShutterManualFallbackActive = false
                    defaultRawShutterFallbackTargetLuma = null
                    defaultRawShutterFallbackPlan = null
                    defaultRawShutterLastSafeExposureCeilingNs = 0L
                    manualExposureAwaitingMetadata = false
                    setAePriorityModeOff(builder)
                    builder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                    builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                    builder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF)
                    builder.set(CaptureRequest.SENSOR_SENSITIVITY, null)
                    builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, null)
                    builder.set(CaptureRequest.SENSOR_FRAME_DURATION, null)
                    val bootstrapPrioritySupported = supportsExposureTimePriority(characteristics)
                    val bootstrapPriorityRoute = DefaultRawApi36RoutePolicy.resolve(
                        priorityModeSupported = bootstrapPrioritySupported,
                        authorityAllowed = defaultRawApi36AuthorityTracker.isAllowed(pipelineGeneration)
                    )
                    lastExposurePlanSummary =
                        "defaultRawPriority=true;priorityModeSupported=$bootstrapPrioritySupported;" +
                            "priorityModeAllowed=${bootstrapPriorityRoute.useExposureTimePriority};" +
                            "priorityRoute=${bootstrapPriorityRoute.reason};" + defaultRawPlan.summary()
                    lastExposurePlanSummary += ";" + defaultRawConvergenceSummarySuffix()
                    return
                }

                val priorityModeSupported = supportsExposureTimePriority(characteristics)
                val api36Route = DefaultRawApi36RoutePolicy.resolve(
                    priorityModeSupported = priorityModeSupported,
                    authorityAllowed = defaultRawApi36AuthorityTracker.isAllowed(pipelineGeneration)
                )
                val centralPriorityAllocation = run {
                    val isoRange = characteristics.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
                    val exposureRange = characteristics.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
                    val product = defaultRawPlan.referenceExposureProduct
                    val safeCeiling = defaultRawPlan.safeExposureCeilingNs
                    if (isoRange != null && exposureRange != null && product != null && safeCeiling != null) {
                        DefaultRawExposureAllocator.allocate(
                            exposureProduct = product,
                            safeExposureCeilingNs = safeCeiling,
                            bounds = ExposureBounds(
                                isoRange.lower, isoRange.upper, exposureRange.lower, exposureRange.upper
                            ),
                            flickerConstraint = resolveDefaultRawFlickerConstraint(),
                            previousExposureNs = null,
                            preferHeldFlickerShutter = false
                        )
                    } else {
                        null
                    }
                }
                val priorityModeAllowed = api36Route.useExposureTimePriority && centralPriorityAllocation != null
                if (priorityModeAllowed) {
                    val priorityAllocation = requireNotNull(centralPriorityAllocation) {
                        "API36 priority route admitted without a central exposure allocation"
                    }
                    defaultRawAllocationReady = true
                    defaultRawShutterManualFallbackActive = false
                    defaultRawShutterFallbackTargetLuma = null
                    defaultRawShutterFallbackPlan = null
                    defaultRawShutterLastSafeExposureCeilingNs =
                        defaultRawPlan.safeExposureCeilingNs ?: 0L
                    manualExposureAwaitingMetadata = false
                    builder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                    builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                    builder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF)
                    if (Build.VERSION.SDK_INT >= 36) {
                        builder.set(
                            CaptureRequest.CONTROL_AE_PRIORITY_MODE,
                            CaptureRequest.CONTROL_AE_PRIORITY_MODE_SENSOR_EXPOSURE_TIME_PRIORITY
                        )
                    }
                    latestDefaultRawExposureAllocation = priorityAllocation
                    builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, priorityAllocation.exposureTimeNs)
                    updateDefaultRawFrameSelectionExposureConstraint(
                        priorityAllocation.exposureTimeNs,
                        "DEFAULT_RAW_API36_EXPOSURE_TIME_PRIORITY",
                        isoTarget = priorityAllocation.sensitivityIso
                    )
                    // Android 16 exposure-time priority keeps AE active. The documented contract
                    // ignores application sensitivity/frame-duration values on this route, so do
                    // not leave stale manual ownership in the request.
                    if (api36Route.aeOwnsSensorSensitivity) {
                        builder.set(CaptureRequest.SENSOR_SENSITIVITY, null)
                    }
                    if (api36Route.aeOwnsSensorFrameDuration) {
                        builder.set(CaptureRequest.SENSOR_FRAME_DURATION, null)
                    }
                    lastExposurePlanSummary =
                        "defaultRawPriority=true;priorityModeSupported=true;priorityModeAllowed=true;" +
                            "priorityModeRequested=EXPOSURE_TIME;priorityRoute=${api36Route.reason};" +
                            "centralAllocator=true;${priorityAllocation.summary()};" +
                            defaultRawPlan.summary()
                    lastExposurePlanSummary += ";" + defaultRawConvergenceSummarySuffix()
                    Log.d(tag, "Camera2 default RAW shutter-priority $lastExposurePlanSummary")
                    return
                }

                // AE exposure-time priority is optional. When unavailable, bootstrap one
                // scene-linear luma anchor under Camera2 AE and then maintain that target with a
                // bounded manual feedback loop.
                latestDefaultRawExposureAllocation = null
                defaultRawShutterManualFallbackActive = true
                val observedForLastResort = latestExposureStatistics
                    ?.exposureControllerLuma()?.takeIf { it.isFinite() && it > 0f }
                val targetLuma = DefaultRawTargetContinuity.resolveFallbackTarget(
                    fallbackTargetLuma = defaultRawShutterFallbackTargetLuma,
                    photometricTargetLuma = defaultRawPhotometricTargetLuma,
                    observedLuma = observedForLastResort
                )
                if (targetLuma != null) {
                    if (defaultRawPhotometricTargetLuma == null) defaultRawPhotometricTargetLuma = targetLuma
                    if (defaultRawShutterFallbackTargetLuma == null) defaultRawShutterFallbackTargetLuma = targetLuma
                }
                val isoRange = characteristics.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
                val exposureRange = characteristics.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
                val safeCeiling = defaultRawPlan.safeExposureCeilingNs
                defaultRawShutterLastSafeExposureCeilingNs = safeCeiling ?: 0L
                if (targetLuma == null || isoRange == null || exposureRange == null ||
                    safeCeiling == null || defaultRawPlan.referenceExposureProduct == null
                ) {
                    defaultRawAllocationReady = false
                    updateDefaultRawFrameSelectionExposureConstraint(null, "DEFAULT_RAW_FALLBACK_LUMA_ANCHOR_BOOTSTRAP")
                    setAePriorityModeOff(builder)
                    builder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                    builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                    builder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF)
                    builder.set(CaptureRequest.SENSOR_SENSITIVITY, null)
                    builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, null)
                    builder.set(CaptureRequest.SENSOR_FRAME_DURATION, null)
                    lastExposurePlanSummary =
                        "defaultRawPriority=true;priorityModeSupported=$priorityModeSupported;" +
                            "priorityModeAllowed=$priorityModeAllowed;priorityRoute=${api36Route.reason};" +
                            "fallback=AE_LUMA_ANCHOR_BOOTSTRAP;" +
                            defaultRawPlan.summary()
                    lastExposurePlanSummary += ";" + defaultRawConvergenceSummarySuffix()
                    return
                }

                val bounds = ExposureBounds(
                    isoRange.lower, isoRange.upper, exposureRange.lower, exposureRange.upper
                )
                val fallbackPlan = synchronized(defaultRawShutterFallbackLock) {
                    val existing = defaultRawShutterFallbackPlan
                    val candidate = if (existing == null) {
                        DefaultRawShutterManualFallbackPolicy.initial(
                            referenceExposureProduct = defaultRawPlan.referenceExposureProduct,
                            targetLuma = targetLuma,
                            safeExposureCeilingNs = safeCeiling,
                            bounds = bounds,
                            flickerConstraint = resolveDefaultRawFlickerConstraint()
                        )
                    } else {
                        DefaultRawShutterManualFallbackPolicy.reallocateForMotion(
                            previous = existing,
                            safeExposureCeilingNs = safeCeiling,
                            bounds = bounds,
                            flickerConstraint = resolveDefaultRawFlickerConstraint()
                        )
                    }
                    if (candidate != null) defaultRawShutterFallbackPlan = candidate
                    candidate
                }
                if (fallbackPlan == null) {
                    defaultRawAllocationReady = false
                    updateDefaultRawFrameSelectionExposureConstraint(null, "DEFAULT_RAW_INVALID_FALLBACK_PLAN")
                    setAePriorityModeOff(builder)
                    builder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                    builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                    builder.set(CaptureRequest.SENSOR_SENSITIVITY, null)
                    builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, null)
                    builder.set(CaptureRequest.SENSOR_FRAME_DURATION, null)
                    lastExposurePlanSummary =
                        "defaultRawPriority=true;priorityModeSupported=$priorityModeSupported;" +
                            "priorityModeAllowed=$priorityModeAllowed;priorityRoute=${api36Route.reason};fallback=INVALID_FALLBACK_PLAN"
                    lastExposurePlanSummary += ";" + defaultRawConvergenceSummarySuffix()
                    return
                }

                defaultRawAllocationReady = true
                manualExposureAwaitingMetadata = false
                setAePriorityModeOff(builder)
                builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                builder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF)
                builder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, 0)
                builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, fallbackPlan.exposureTimeNs)
                updateDefaultRawFrameSelectionExposureConstraint(
                    fallbackPlan.exposureTimeNs,
                    "DEFAULT_RAW_MANUAL_FALLBACK",
                    isoTarget = fallbackPlan.sensitivityIso
                )
                builder.set(CaptureRequest.SENSOR_SENSITIVITY, fallbackPlan.sensitivityIso)
                builder.set(CaptureRequest.SENSOR_FRAME_DURATION, fallbackPlan.frameDurationNs)
                lastExposurePlanSummary =
                    "defaultRawPriority=true;priorityModeSupported=$priorityModeSupported;" +
                        "priorityModeAllowed=$priorityModeAllowed;priorityRoute=${api36Route.reason};" +
                        "fallback=MANUAL_LINEAR_LUMA_FEEDBACK;${fallbackPlan.summary()};" +
                        defaultRawPlan.summary()
                lastExposurePlanSummary += ";" + defaultRawConvergenceSummarySuffix()
                Log.d(tag, "Camera2 default RAW shutter manual fallback $lastExposurePlanSummary")
                return
            }

            defaultRawAllocationReady = false
            updateDefaultRawFrameSelectionExposureConstraint(null, "NON_DEFAULT_RAW_EXPOSURE_AUTHORITY")
            val plan = resolveExposurePlan(characteristics)
            if (plan == null || plan.autoExposure || !plan.ready) {
                manualExposureAwaitingMetadata = plan?.ready == false
                val flashControlPlan = resolveFlashControlPlan(characteristics, currentFlashMode)
                setAePriorityModeOff(builder)
                builder.set(
                    CaptureRequest.CONTROL_AE_MODE,
                    flashControlPlan?.aeMode ?: CaptureRequest.CONTROL_AE_MODE_ON
                )
                // Preview never uses the photographic SINGLE pulse. Tap-to-focus may temporarily
                // enable TORCH later as an AF assist; shutter-time flash owns the real preflash/main
                // flash transaction.
                builder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF)
                builder.set(CaptureRequest.SENSOR_SENSITIVITY, plan?.sensitivityIso)
                builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, plan?.exposureTimeNs)
                builder.set(CaptureRequest.SENSOR_FRAME_DURATION, plan?.frameDurationNs)
                lastExposurePlanSummary = plan?.summary() ?: lastExposurePlanSummary
                if (plan?.ready == false) {
                    Log.w(tag, "Manual exposure pending without guessed fallback: ${plan.summary()}")
                }
                return
            }

            manualExposureAwaitingMetadata = false
            setAePriorityModeOff(builder)
            builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
            builder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF)
            builder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, 0)
            builder.set(CaptureRequest.SENSOR_SENSITIVITY, plan.sensitivityIso!!)
            builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, plan.exposureTimeNs!!)
            builder.set(CaptureRequest.SENSOR_FRAME_DURATION, plan.frameDurationNs!!)
            lastExposurePlanSummary = plan.summary() + ";flashSuppressedForManual=${currentFlashMode != "Off"}"
            Log.d(tag, "Camera2 exposure plan $lastExposurePlanSummary")
        }

    private fun cancelFocusTimingJobs() {
        trackingTimeoutJob?.cancel()
        trackingTimeoutJob = null
        tapFocusTimeoutJob?.cancel()
        tapFocusTimeoutJob = null
        pointLockJob?.cancel()
        pointLockJob = null
    }

    private suspend fun awaitMonotonicDeadline(deadlineElapsedMs: Long) {
        while (true) {
            val remaining = deadlineElapsedMs - android.os.SystemClock.elapsedRealtime()
            if (remaining <= 0L) return
            kotlinx.coroutines.delay(remaining.coerceAtMost(250L))
        }
    }

    /** Camera-engine-owned timeout for a normal tap. Compose no longer owns focus lifetime. */
    fun holdTapFocusFor(durationMs: Long) {
        val safeDuration = durationMs.coerceIn(250L, 30_000L)
        tapFocusTimeoutJob?.cancel()
        val deadline = android.os.SystemClock.elapsedRealtime() + safeDuration
        tapFocusTimeoutJob = focusTimingScope.launch {
            awaitMonotonicDeadline(deadline)
            restoreConfiguredAutoFocusAfterTap()
        }
    }

    /**
     * Ends an ordinary tap-focus hold and cleanly restores configured metering and autofocus.
     */
    fun restoreConfiguredAutoFocusAfterTap() {
        enqueuePreviewControl("autofocus_restore", "RESTORE_CONFIGURED_AF_AFTER_TAP") {
            restoreConfiguredAutoFocusOwned(preserveAeState = false)
        }
    }

    /**
     * Long-press semantics: focus at the requested point first, wait briefly for the HAL to settle,
     * then lock AF+AE. A timeout locks the best current state rather than hanging indefinitely.
     */
    fun focusAndLockAt(
        xPct: Float,
        yPct: Float,
        cameraId: String,
        traceTap: com.bncam.core.debug.AfGroundTruthUiTap,
        convergenceTimeoutMs: Long = 900L
    ) {
        tapToFocusAt(xPct, yPct, cameraId, traceTap)
        pointLockJob?.cancel()
        val deadline = android.os.SystemClock.elapsedRealtime() + convergenceTimeoutMs.coerceIn(250L, 2_000L)
        pointLockJob = focusTimingScope.launch {
            var tapOwnershipObserved = false
            var converged = false
            while (android.os.SystemClock.elapsedRealtime() < deadline) {
                val owner = _focusOwnership.value.owner
                if (!tapOwnershipObserved) {
                    if (owner == FocusOwner.TAP) {
                        tapOwnershipObserved = true
                    } else {
                        kotlinx.coroutines.delay(15L)
                        continue
                    }
                } else if (owner != FocusOwner.TAP) {
                    return@launch
                }

                val af = lastAfState
                val ae = lastAeState
                val afReady = af == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED ||
                    af == CaptureResult.CONTROL_AF_STATE_PASSIVE_FOCUSED ||
                    af == CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED
                val aeReady = ae == null || ae == CaptureResult.CONTROL_AE_STATE_CONVERGED ||
                    ae == CaptureResult.CONTROL_AE_STATE_LOCKED ||
                    ae == CaptureResult.CONTROL_AE_STATE_FLASH_REQUIRED
                if (afReady && aeReady) {
                    converged = true
                    break
                }
                kotlinx.coroutines.delay(30L)
            }
            if (tapOwnershipObserved && _focusOwnership.value.owner == FocusOwner.TAP) {
                Log.i(tag, "POINT_LOCK settle=${if (converged) "converged" else "timeout"}")
                lockFocusAndExposure()
            }
        }
    }

    fun setFocus(focusFraction: Float, cameraId: String) {
        enqueuePreviewControl("focus_distance", "SET_FOCUS_DISTANCE:$cameraId") {
            setFocusOwned(focusFraction, cameraId)
        }
    }

    private fun setFocusOwned(focusFraction: Float, cameraId: String) {
        val session = captureSession ?: return
        val request = currentCaptureRequest ?: return
        val handler = backgroundHandler ?: return

        try {
            val chars = cameraManager.getCameraCharacteristics(cameraId)
            val minFocus =
                chars.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f

            if (minFocus > 0f) {
                cancelFocusTimingJobs()
                if (_focusOwnership.value.trackingActive) {
                    clearFocusTrackingState(reason = "manual_focus", restoreConfiguredAf = false)
                }
                transitionFocusOwner(FocusOwner.MANUAL, "manual_focus_distance")

                val invertedFraction = 1f - focusFraction
                val hardwareFocusDistance =
                    minFocus * (invertedFraction * invertedFraction * invertedFraction)

                clearPhysicalAfRegionState(request)
                request.set(CaptureRequest.CONTROL_AF_REGIONS, null)
                request.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                request.set(CaptureRequest.LENS_FOCUS_DISTANCE, hardwareFocusDistance)
                traceAfWriter(request, "setFocusOwned", "MANUAL_FOCUS_DISTANCE_WRITES")
                submitRepeatingRequestWithProvenance(
                    session = session,
                    builder = request,
                    callback = captureCallback,
                    handler = handler,
                    reason = "MANUAL_FOCUS_DISTANCE"
                )
            }
        } catch (e: Exception) {
            Log.e("BnCameraManager", "Focus Error", e)
        }
    }

    // NIEUW: Veilige Digitale Zoom Functie
    fun setZoom(zoomLevel: Float) {
        enqueuePreviewControl("digital_zoom", "SET_DIGITAL_ZOOM") {
            setZoomOwned(zoomLevel)
        }
    }

    private fun setZoomOwned(zoomLevel: Float) {
        val session = captureSession ?: run {
            if (zoomLevel == 1f) {
                logPreviewDiagnostics(
                    event = "ZOOM_1_NOT_APPLIED",
                    extra = "reason=noCaptureSession"
                )
            }
            return
        }
        val request = currentCaptureRequest ?: run {
            if (zoomLevel == 1f) {
                logPreviewDiagnostics(
                    event = "ZOOM_1_NOT_APPLIED",
                    extra = "reason=noCurrentCaptureRequest"
                )
            }
            return
        }
        val handler = backgroundHandler ?: run {
            if (zoomLevel == 1f) {
                logPreviewDiagnostics(
                    event = "ZOOM_1_NOT_APPLIED",
                    extra = "reason=noBackgroundHandler"
                )
            }
            return
        }
        try {
            val zoomGeometryCameraId = cameraDevice?.id ?: return
            val chars = cameraManager.getCameraCharacteristics(zoomGeometryCameraId)
            val activeRect =
                chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return

            // A hidden physical ultra-wide YUV output lives in the logical camera's zoom space.
            // Treat UI 1.0x as the selected physical lens' native FOV and express further digital
            // zoom through CONTROL_ZOOM_RATIO; cropRegion cannot represent logical zoom-out.
            val physicalYuvDecision = resolvePhysicalYuvFullFovDecision()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && physicalYuvDecision.enabled) {
                val effectiveZoom = physicalYuvDecision.resolveEffectiveZoom(zoomLevel)
                request.set(CaptureRequest.CONTROL_ZOOM_RATIO, effectiveZoom)
                request.set(CaptureRequest.SCALER_CROP_REGION, Rect(activeRect))
                traceAfWriter(request, "setZoomOwned", "PHYSICAL_ULTRA_WIDE_YUV_ZOOM_WRITES")
                applyMeteringPolicy(request)
                applyExposurePolicy(request)
                val repeatingRequest = submitRepeatingRequestWithProvenance(
                    session = session,
                    builder = request,
                    callback = captureCallback,
                    handler = handler,
                    reason = "PHYSICAL_ULTRA_WIDE_YUV_ZOOM_AND_METERING_UPDATE"
                ).request
                logPreviewDiagnostics(
                    event = "PHYSICAL_ULTRA_WIDE_YUV_ZOOM_APPLIED",
                    request = repeatingRequest,
                    extra = "relativeZoom=$zoomLevel nativeZoom=${physicalYuvDecision.nativeZoomRatio} " +
                        "effectiveZoom=$effectiveZoom"
                )
                return
            }

            // Never invent a digital-zoom capability when the HAL does not advertise one.
            val maxZoom =
                (chars.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f)
                    .coerceAtLeast(1f)

            // Zorg dat de gebruiker nooit voorbij 1.0x (uitzoomen) of de max van de lens kan!
            val safeZoom = zoomLevel.coerceIn(1f, maxZoom)

            val w = activeRect.width()
            val h = activeRect.height()

            val cropW = (w / safeZoom).toInt()
            val cropH = (h / safeZoom).toInt()
            val cropX = (w - cropW) / 2
            val cropY = (h - cropH) / 2

            val cropRegion = Rect(cropX, cropY, cropX + cropW, cropY + cropH)

            request.set(CaptureRequest.SCALER_CROP_REGION, cropRegion)
            traceAfWriter(request, "setZoomOwned", "DIGITAL_ZOOM_CROP_WRITE")
            applyMeteringPolicy(request)
            applyExposurePolicy(request)
            val repeatingRequest = submitRepeatingRequestWithProvenance(
                session = session,
                builder = request,
                callback = captureCallback,
                handler = handler,
                reason = "DIGITAL_ZOOM_AND_METERING_UPDATE"
            ).request
            if (safeZoom == 1f) {
                logPreviewDiagnostics(
                    event = "ZOOM_1_APPLIED",
                    request = repeatingRequest,
                    extra = "requestedZoom=$zoomLevel safeZoom=$safeZoom zoomGeometryCameraId=$zoomGeometryCameraId"
                )
            }
        } catch (e: Exception) {
            Log.e(tag, "Fout bij instellen van zoom", e)
        }
    }

    // ========================================================
    // LIVE FLITSER CONTROLS
    // ========================================================
    fun setFlashMode(flashMode: String) {
        currentFlashMode = flashMode
        enqueuePreviewControl("preview_policy", "SET_FLASH_MODE") {
            updatePreviewRepeatingRequest()
        }
    }

    fun setFocusMode(mode: String) {
        val resolved = if (mode.equals("Tap-to-Focus", ignoreCase = true)) {
            "Tap-to-Focus"
        } else {
            "Continuous"
        }
        if (configuredFocusMode == resolved) return
        configuredFocusMode = resolved
        Log.i(tag, "Focus mode updated to $configuredFocusMode")
        enqueuePreviewControl("focus_mode", "SET_FOCUS_MODE") {
            restoreConfiguredAutoFocusOwned()
        }
    }

    /**
     * Restore the idle AF behaviour selected in Viewfinder Settings.
     * Continuous resumes continuous-picture AF; Tap-to-Focus stays idle in AF_MODE_AUTO
     * until the next explicit tap trigger.
     */
    fun restoreConfiguredAutoFocus() {
        enqueuePreviewControl("autofocus_restore", "RESTORE_CONFIGURED_AF") {
            restoreConfiguredAutoFocusOwned(preserveAeState = false)
        }
    }

    private fun restoreConfiguredAutoFocusOwned(preserveAeState: Boolean = false) {
        val session = captureSession ?: return
        val request = currentCaptureRequest ?: return
        val handler = backgroundHandler ?: return
        try {
            tapFocusTimeoutJob?.cancel()
            tapFocusTimeoutJob = null
            pointLockJob?.cancel()
            pointLockJob = null
            transitionFocusOwner(FocusOwner.AUTO, if (preserveAeState) "tap_timeout" else "configured_af_restore")

            val retiringTouchAeOverride = !preserveAeState && activeTapAeRegion != null
            if (!preserveAeState) {
                clearTouchAeOverride()
            }

            clearPhysicalAfRegionState(request)
            request.set(CaptureRequest.CONTROL_AF_REGIONS, null)

            val idleAfMode = resolveConfiguredIdleAfMode(cameraDevice?.id)
            request.set(
                CaptureRequest.CONTROL_AF_MODE,
                idleAfMode
            )
            // Keep manual focus-distance writes scoped to AF_MODE_OFF. Continuous/AUTO AF owns
            // the actuator position and must not receive a competing infinity-focus command.
            if (idleAfMode == CaptureRequest.CONTROL_AF_MODE_OFF) {
                request.set(CaptureRequest.LENS_FOCUS_DISTANCE, 0f)
            }

            if (preserveAeState) {
                // A normal tap timeout owns AF only. Rewriting AE_LOCK, AE_REGIONS, AE mode,
                // compensation, or sensor timing here makes the HAL discard a converged exposure
                // transaction and visibly hunt again. Torch shutdown is independent and retains
                // the existing post-focus flash behaviour without replacing AE ownership.
                if (isTorchActive) {
                    request.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF)
                }
                isTorchActive = false
            } else {
                request.set(CaptureRequest.CONTROL_AE_LOCK, false)
                isTorchActive = false
                applyMeteringPolicy(request)
                if (retiringTouchAeOverride) {
                    restartDefaultRawAeReferenceForMeteringChange("TOUCH_AE_REGION_RETIRED")
                }
                applyExposurePolicy(request)
            }

            request.set(
                CaptureRequest.CONTROL_AF_TRIGGER,
                CaptureRequest.CONTROL_AF_TRIGGER_CANCEL
            )
            traceAfWriter(request, "restoreConfiguredAutoFocusOwned", "CONFIGURED_AF_CANCEL_WRITES")
            submitOneShotRequestWithProvenance(
                session = session,
                builder = request,
                callback = captureCallback,
                handler = handler,
                reason = "CONFIGURED_AF_CANCEL_TRIGGER"
            )

            request.set(
                CaptureRequest.CONTROL_AF_TRIGGER,
                CaptureRequest.CONTROL_AF_TRIGGER_IDLE
            )
            traceAfWriter(request, "restoreConfiguredAutoFocusOwned", "CONFIGURED_AF_RESTORE_WRITES")
            submitRepeatingRequestWithProvenance(
                session = session,
                builder = request,
                callback = captureCallback,
                handler = handler,
                reason = "CONFIGURED_AF_RESTORE"
            )
        } catch (e: Exception) {
            Log.e(tag, "Failed to restore configured autofocus repeating request", e)
        }
    }

    // Compatibility entry point used by existing UI/capture reset paths. It now restores
    // the configured mode instead of forcing Continuous.
    fun triggerContinuousAutoFocus() {
        restoreConfiguredAutoFocus()
    }

    fun tapToFocusAt(
        xPct: Float,
        yPct: Float,
        cameraId: String,
        traceTap: com.bncam.core.debug.AfGroundTruthUiTap
    ) {
        val transactionId = com.bncam.core.debug.AfGroundTruthTrace.beginTap(traceTap)
        enqueuePreviewControl("tap_focus", "TAP_TO_FOCUS:$cameraId") {
            tapToFocusAtOwned(xPct, yPct, cameraId, transactionId, traceTap)
        }
    }

    private fun tapToFocusAtOwned(
        xPct: Float,
        yPct: Float,
        cameraId: String,
        transactionId: String,
        traceTap: com.bncam.core.debug.AfGroundTruthUiTap
    ) {
        portraitTapSeedX = xPct.coerceIn(0f, 1f)
        portraitTapSeedY = yPct.coerceIn(0f, 1f)
        latestPortraitMask.set(null)
        val session = captureSession ?: return
        val request = currentCaptureRequest ?: return
        val handler = backgroundHandler ?: return
        val controlCameraId = cameraDevice?.id ?: return

        // Use the characteristics of the selected physical sensor for touch
        // geometry even when Camera2 is opened through a logical owner. The logical camera remains
        // the request owner, but it must not define a tele/ultra-wide tap coordinate system.
        val activePhysicalId = synchronized(pipelineLock) { activePipelineIdentity?.physicalCameraId }
        val selectedLensGeometryId = cameraId.takeIf { it != controlCameraId }
        val geometryCameraId = activePhysicalId ?: selectedLensGeometryId ?: controlCameraId

        Log.i(
            tag,
            "Exact-touch AF generation=$pipelineGeneration selectedLens=$cameraId " +
                "controlCamera=$controlCameraId geometryCamera=$geometryCameraId physical=${activePhysicalId ?: "none"}"
        )
        predictiveAfTracker.clear()

        try {
            val controlChars = cameraManager.getCameraCharacteristics(controlCameraId)
            val geometryChars = runCatching {
                cameraManager.getCameraCharacteristics(geometryCameraId)
            }.getOrElse {
                Log.w(tag, "Physical focus geometry unavailable for $geometryCameraId; using control camera $controlCameraId", it)
                controlChars
            }

            val maxAfRegions = controlChars.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF) ?: 0
            val maxAeRegions = controlChars.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AE) ?: 0
            val mapped = com.bncam.core.capture.BnTouchFocusPolicy.map(
                normPoint = NormalizedPoint(xPct, yPct),
                characteristics = geometryChars,
                previewStreamWidth = configuredPreviewStreamWidth,
                previewStreamHeight = configuredPreviewStreamHeight
            ) ?: run {
                Log.w(tag, "Exact-touch AF rejected: physical sensor geometry unavailable")
                return
            }

            val traceIdentity = synchronized(pipelineLock) { activePipelineIdentity }
            val activePhysicalChars = activePhysicalId?.let { physicalId ->
                runCatching { cameraManager.getCameraCharacteristics(physicalId) }.getOrNull()
            }
            val traceMapping = com.bncam.core.debug.AfGroundTruthMappingContext(
                transactionId = transactionId,
                uiTap = traceTap,
                // BnTouchFocusPolicy.map currently receives its production default (zero).
                mappingDisplayRotationDegrees = 0,
                sensorOrientationDegrees = geometryChars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90,
                lensFacingFront = geometryChars.get(CameraCharacteristics.LENS_FACING) ==
                    CameraCharacteristics.LENS_FACING_FRONT,
                previewSurfaceWidth = configuredPreviewStreamWidth,
                previewSurfaceHeight = configuredPreviewStreamHeight,
                analysisBufferWidth = traceIdentity?.width ?: 0,
                analysisBufferHeight = traceIdentity?.height ?: 0,
                sensorVisibleBounds = Rect(mapped.visibleSensorBounds),
                mapperSensorNormalizedX = mapped.sensorNormPoint.x,
                mapperSensorNormalizedY = mapped.sensorNormPoint.y,
                requestedCropRegion = request.get(CaptureRequest.SCALER_CROP_REGION)?.let(::Rect),
                requestedZoomRatio = request.get(CaptureRequest.CONTROL_ZOOM_RATIO)
            )
            com.bncam.core.debug.AfGroundTruthTrace.recordTapMapping(
                mapping = traceMapping,
                selectedLensId = cameraId,
                openedCameraDeviceId = controlCameraId,
                logicalCameraId = traceIdentity?.logicalCameraId ?: controlCameraId,
                activePhysicalCameraId = activePhysicalId,
                geometryCameraId = geometryCameraId,
                requestedPhysicalCameraId = traceIdentity?.physicalCameraId,
                previewOutputPhysicalCameraId = lastPreviewOutputPhysicalCameraId,
                imageReaderOutputPhysicalCameraId = lastImageReaderOutputPhysicalCameraId,
                standalonePhysicalCameraListed = cameraManager.cameraIdList.contains(cameraId),
                activePipeline = linkedMapOf(
                    "pipelineGeneration" to pipelineGeneration,
                    "requestedProfileId" to traceIdentity?.requestedProfileId,
                    "requestedFrameSource" to traceIdentity?.requestedFrameSource,
                    "effectiveFrameSource" to traceIdentity?.effectiveFrameSource,
                    "bufferFormat" to traceIdentity?.bufferFormat,
                    "logicalCameraId" to traceIdentity?.logicalCameraId,
                    "physicalCameraId" to traceIdentity?.physicalCameraId,
                    "cameraRouteKind" to traceIdentity?.cameraRouteKind?.name,
                    "lensRole" to traceIdentity?.lensRole,
                    "backendRoute" to traceIdentity?.backendRoute,
                    "bufferWidth" to traceIdentity?.width,
                    "bufferHeight" to traceIdentity?.height,
                    "maxImages" to traceIdentity?.maxImages
                ),
                logicalCharacteristics = controlChars,
                physicalCharacteristics = activePhysicalChars
            )

            val availableAfModes = controlChars.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)
                ?.toSet().orEmpty()
            val requestedTapMode = if (configuredFocusMode == "Tap-to-Focus") {
                CaptureRequest.CONTROL_AF_MODE_AUTO
            } else {
                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
            }
            val resolvedTapMode = when {
                availableAfModes.isEmpty() -> CaptureRequest.CONTROL_AF_MODE_OFF
                requestedTapMode in availableAfModes -> requestedTapMode
                configuredFocusMode != "Tap-to-Focus" &&
                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO in availableAfModes ->
                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO
                CaptureRequest.CONTROL_AF_MODE_AUTO in availableAfModes ->
                    CaptureRequest.CONTROL_AF_MODE_AUTO
                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE in availableAfModes ->
                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO in availableAfModes ->
                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO
                else -> CaptureRequest.CONTROL_AF_MODE_OFF
            }
            val continuousTapMode = resolvedTapMode == CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE ||
                resolvedTapMode == CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO

            val fallbackPhysicalCameraId = traceIdentity
                ?.takeIf { it.cameraRouteKind == CameraRouteKind.LOGICAL_PHYSICAL }
                ?.physicalCameraId
            val physicalRequestKeys = if (
                fallbackPhysicalCameraId != null
            ) {
                controlChars.availablePhysicalCameraRequestKeys.orEmpty().toSet()
            } else {
                emptySet()
            }
            val physicalAfRegionWritable =
                CaptureRequest.CONTROL_AF_REGIONS in physicalRequestKeys
            val physicalAeRegionWritable =
                CaptureRequest.CONTROL_AE_REGIONS in physicalRequestKeys
            val logicalOwnerBounds = if (fallbackPhysicalCameraId != null) {
                val resultCrop = if (lastCaptureResultGeneration == pipelineGeneration) {
                    lastCaptureResult?.get(CaptureResult.SCALER_CROP_REGION)?.let(::Rect)
                } else {
                    null
                }
                resultCrop
                    ?: request.get(CaptureRequest.SCALER_CROP_REGION)?.let(::Rect)
                    ?: controlChars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)?.let(::Rect)
            } else {
                null
            }
            val ownerDomainMeteringRectangle = if (
                fallbackPhysicalCameraId != null &&
                (!physicalAfRegionWritable || !physicalAeRegionWritable)
            ) {
                logicalOwnerBounds?.let { ownerBounds ->
                    CameraOwnerDomainMapper.mapMeteringRectangle(
                        physicalRegion = mapped.meteringRectangle,
                        physicalBounds = mapped.requestBounds,
                        logicalOwnerBounds = ownerBounds
                    )
                } ?: run {
                    Log.e(
                        tag,
                        "Exact-touch AF rejected: fallback route has no valid logical-owner metering domain"
                    )
                    return
                }
            } else {
                null
            }

            // Acquire TAP ownership only after geometry/routing validation succeeds, but before the
            // first Camera2 transaction, so callbacks cannot replace the region mid-transaction.
            transitionFocusOwner(FocusOwner.TAP, "touch_focus")

            // Retire any prior touch regions before CANCEL. A logical/physical fallback must never
            // carry a physical-sensor rectangle in the global logical request domain.
            if (fallbackPhysicalCameraId != null) {
                request.set(CaptureRequest.CONTROL_AF_REGIONS, null)
                request.set(CaptureRequest.CONTROL_AE_REGIONS, null)
                if (physicalAfRegionWritable) {
                    request.setPhysicalCameraKey(
                        CaptureRequest.CONTROL_AF_REGIONS,
                        null,
                        fallbackPhysicalCameraId
                    )
                }
                if (physicalAeRegionWritable) {
                    request.setPhysicalCameraKey(
                        CaptureRequest.CONTROL_AE_REGIONS,
                        null,
                        fallbackPhysicalCameraId
                    )
                }
            }

            // Touch autofocus sequence: cancel any previous AF transaction first with a one-shot.
            request.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_CANCEL)
            request.set(
                CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER,
                CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_IDLE
            )
            traceAfWriter(request, "tapToFocusAtOwned", "TOUCH_AF_CANCEL_WRITES")
            submitOneShotRequestWithProvenance(
                session = session,
                builder = request,
                callback = captureCallback,
                handler = handler,
                reason = "TOUCH_AF_CANCEL"
            )

            request.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
            request.set(CaptureRequest.CONTROL_AF_MODE, resolvedTapMode)
            request.set(CaptureRequest.CONTROL_AE_LOCK, false)
            if (maxAfRegions > 0 && resolvedTapMode != CaptureRequest.CONTROL_AF_MODE_OFF) {
                if (fallbackPhysicalCameraId != null && physicalAfRegionWritable) {
                    request.set(CaptureRequest.CONTROL_AF_REGIONS, null)
                    request.setPhysicalCameraKey(
                        CaptureRequest.CONTROL_AF_REGIONS,
                        arrayOf(mapped.meteringRectangle),
                        fallbackPhysicalCameraId
                    )
                    activePhysicalAfRegion = mapped.meteringRectangle
                    activePhysicalAfCameraId = fallbackPhysicalCameraId
                    activePhysicalAfRequestBounds = Rect(mapped.requestBounds)
                } else {
                    clearPhysicalAfRegionState(request)
                    request.set(
                        CaptureRequest.CONTROL_AF_REGIONS,
                        arrayOf(ownerDomainMeteringRectangle ?: mapped.meteringRectangle)
                    )
                }
            } else {
                clearPhysicalAfRegionState(request)
                request.set(CaptureRequest.CONTROL_AF_REGIONS, null)
            }

            val manualExposureActive = requestedManualIso != null || requestedManualExposureNs != null

            // Focus taps own focus only. Global exposure remains owned by the selected metering
            // policy. This prevents tapping a clipped highlight from darkening the whole frame and
            // prevents tapping a shadow from globally raising exposure. Explicit metering modes
            // continue to work through applyMeteringPolicy(); manual exposure remains untouched.
            val touchAeRegionApplied = false
            clearTouchAeOverride()
            applyMeteringPolicy(request)

            // Tap-to-focus may use the flash LED only as a temporary AF assist. Do not assume
            // every physical lens has a usable flash route: the shutter-time flash planner remains
            // authoritative and the assist is enabled only when this Camera2 owner can actually
            // provide flash. Auto uses assist only after AE explicitly reports FLASH_REQUIRED; an
            // uncertain Auto state is revalidated at shutter instead of lighting the scene early.
            val tapFlashControlPlan = resolveFlashControlPlan(controlChars, currentFlashMode)
            val tapAssistRequested = !manualExposureActive && tapFlashControlPlan != null &&
                (currentFlashMode.equals("On", ignoreCase = true) ||
                    (currentFlashMode.equals("Auto", ignoreCase = true) &&
                        lastAeState == CaptureResult.CONTROL_AE_STATE_FLASH_REQUIRED))
            if (tapAssistRequested) {
                val availableAeModes =
                    controlChars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_MODES) ?: intArrayOf()
                val assistAeMode = if (
                    availableAeModes.contains(CaptureRequest.CONTROL_AE_MODE_ON)
                ) {
                    CaptureRequest.CONTROL_AE_MODE_ON
                } else {
                    tapFlashControlPlan.aeMode
                }
                request.set(CaptureRequest.CONTROL_AE_MODE, assistAeMode)
                request.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_TORCH)
                isTorchActive = true
                traceCaptureRuntime(
                    "FLASH_FOCUS_ASSIST enabled=true mode=$currentFlashMode aeMode=$assistAeMode " +
                        "plan=${tapFlashControlPlan.reason}"
                )
            } else {
                request.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF)
                isTorchActive = false
            }

            if (continuousTapMode || resolvedTapMode == CaptureRequest.CONTROL_AF_MODE_OFF) {
                // The continuous touch-focus mode is CONTINUOUS_PICTURE. In that mode a touch only
                // changes the persistent AF region; AE remains owned by the metering policy. START is not fired.
                request.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE)
                request.set(
                    CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER,
                    CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_IDLE
                )
                traceAfWriter(request, "tapToFocusAtOwned", "TOUCH_AF_CONTINUOUS_WRITES")
                submitRepeatingRequestWithProvenance(
                    session = session,
                    builder = request,
                    callback = captureCallback,
                    handler = handler,
                    reason = "TOUCH_AF_CONTINUOUS_REPEATING"
                )
            } else {
                // Tap-to-focus AUTO mode: START once, return triggers to IDLE with another one-shot,
                // then keep the region/mode alive in the repeating request.
                if (!manualExposureActive) {
                    request.set(
                        CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER,
                        CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_START
                    )
                }
                request.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START)
                traceAfWriter(request, "tapToFocusAtOwned", "TOUCH_AF_AUTO_START_WRITES")
                submitOneShotRequestWithProvenance(
                    session = session,
                    builder = request,
                    callback = captureCallback,
                    handler = handler,
                    reason = "TOUCH_AF_AUTO_START"
                )

                request.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE)
                request.set(
                    CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER,
                    CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_IDLE
                )
                traceAfWriter(request, "tapToFocusAtOwned", "TOUCH_AF_AUTO_IDLE_WRITES")
                submitOneShotRequestWithProvenance(
                    session = session,
                    builder = request,
                    callback = captureCallback,
                    handler = handler,
                    reason = "TOUCH_AF_AUTO_IDLE"
                )
                traceAfWriter(request, "tapToFocusAtOwned", "TOUCH_AF_AUTO_REPEATING_PERSISTENCE")
                submitRepeatingRequestWithProvenance(
                    session = session,
                    builder = request,
                    callback = captureCallback,
                    handler = handler,
                    reason = "TOUCH_AF_AUTO_REPEATING"
                )
            }

            com.bncam.core.debug.AfParityAuditor.auditTapTransaction(
                controlCameraId,
                activePhysicalId,
                xPct,
                yPct,
                mapped.meteringRectangle,
                mapped.requestBounds,
                mapped.coordinateSpaceLabel
            )
            Log.i(
                tag,
                "Exact-touch AF applied rect=${mapped.mappedSensorRect} afMode=$resolvedTapMode " +
                    "afRegions=${if (maxAfRegions > 0) 1 else 0} " +
                    "aeRegions=${if (touchAeRegionApplied) 1 else 0} " +
                    "controlCamera=$controlCameraId geometryCamera=$geometryCameraId " +
                    "fallbackPhysical=${fallbackPhysicalCameraId ?: "none"} " +
                    "physicalAfKey=$physicalAfRegionWritable physicalAeKey=$physicalAeRegionWritable " +
                    "${mapped.coordinateSpaceLabel}"
            )
        } catch (e: Exception) {
            transitionFocusOwner(FocusOwner.AUTO, "touch_focus_failed")
            Log.e(tag, "Failed to apply exact-touch autofocus", e)
        }
    }

    private fun releaseTouchAeToStandardMeteringForTracking() {
        enqueuePreviewControl("focus_tracking_ae", "FOCUS_TRACK_RELEASE_TOUCH_AE") {
            if (!_focusOwnership.value.trackingActive) return@enqueuePreviewControl
            val session = captureSession ?: return@enqueuePreviewControl
            val request = currentCaptureRequest ?: return@enqueuePreviewControl
            val handler = backgroundHandler ?: return@enqueuePreviewControl
            clearTouchAeOverride()
            request.set(CaptureRequest.CONTROL_AE_LOCK, false)
            applyMeteringPolicy(request)
            applyExposurePolicy(request)
            submitRepeatingRequestWithProvenance(
                session = session,
                builder = request,
                callback = captureCallback,
                handler = handler,
                reason = "FOCUS_TRACK_STANDARD_AE_RESTORE"
            )
        }
    }

    // --- Tracking Variabelen ---
    private var trackingOffsetPctX = 0.5f
    private var trackingOffsetPctY = 0.5f
    private var lastHwFocusX = -1f
    private var lastHwFocusY = -1f

    /**
     * Atomically orders exact-touch AF before Focus Track ownership on CameraBackground. This avoids
     * TRACK_ACQUIRING being overwritten by a delayed TAP transaction from the UI thread.
     */
    fun tapToFocusAndStartTracking(
        xPct: Float,
        yPct: Float,
        cameraId: String,
        traceTap: com.bncam.core.debug.AfGroundTruthUiTap,
        pinned: Boolean,
        mirrorX: Boolean = false,
        durationMs: Long? = null
    ) {
        val transactionId = com.bncam.core.debug.AfGroundTruthTrace.beginTap(traceTap)
        enqueuePreviewControl("tap_focus", "TAP_TO_FOCUS_AND_TRACK:$cameraId") {
            tapToFocusAtOwned(xPct, yPct, cameraId, transactionId, traceTap)
            startFocusTrackingTargetOwned(xPct, yPct, pinned, mirrorX, durationMs)
        }
    }

    /** Compatibility entry point for non-UI callers that already established the desired AF state. */
    fun startFocusTrackingTarget(
        xPct: Float,
        yPct: Float,
        pinned: Boolean,
        mirrorX: Boolean = false,
        durationMs: Long? = null
    ) {
        enqueuePreviewControl("focus_tracking_start", "FOCUS_TRACK_START") {
            startFocusTrackingTargetOwned(xPct, yPct, pinned, mirrorX, durationMs)
        }
    }

    private fun startFocusTrackingTargetOwned(
        xPct: Float,
        yPct: Float,
        pinned: Boolean,
        mirrorX: Boolean,
        durationMs: Long?
    ) {
        if (!objectTrackingAnalysisEnabled) return
        trackingTimeoutJob?.cancel()
        trackingTimeoutJob = null
        tapFocusTimeoutJob?.cancel()
        tapFocusTimeoutJob = null
        pointLockJob?.cancel()
        pointLockJob = null
        val safeX = xPct.coerceIn(0f, 1f)
        val safeY = yPct.coerceIn(0f, 1f)
        portraitTapSeedX = safeX
        portraitTapSeedY = safeY
        latestPortraitMask.set(null)
        predictiveAfTracker.clear()
        focusTrackingPinned = pinned
        transitionFocusOwner(
            owner = FocusOwner.TRACK_ACQUIRING,
            reason = if (pinned) "track_acquiring_pinned" else "track_acquiring_timed",
            trackingPinned = pinned
        )
        focusTrackingMirrorX = mirrorX
        pendingTapX = if (mirrorX) 1f - safeX else safeX
        pendingTapY = safeY
        activeTrackingId = null
        trackingLostFrames = 0
        lastTrackedBoxPx = null
        trackingOffsetPctX = 0.5f
        trackingOffsetPctY = 0.5f
        trackingSmoothedX = Float.NaN
        trackingSmoothedY = Float.NaN
        trackingVelocityX = 0f
        trackingVelocityY = 0f
        trackingLastObservationNs = 0L
        trackingLastHardwareSubmitNs = 0L
        lastHwFocusX = -1f
        lastHwFocusY = -1f
        lastHwFocusRegionPct = -1f
        _trackedObjectBounds.value = android.graphics.RectF(
            (safeX - 0.012f).coerceAtLeast(0f),
            (safeY - 0.012f).coerceAtLeast(0f),
            (safeX + 0.012f).coerceAtMost(1f),
            (safeY + 0.012f).coerceAtMost(1f)
        )
        _focusTrackingActive.value = true
        _focusTrackingState.value = FocusTrackingState(
            phase = FocusTrackingPhase.ACQUIRING,
            confidence = 0.35f,
            pinned = pinned,
            reason = "tap_acquisition"
        )
        refreshRawPreviewCompactAnalysisRequest()
        Log.i(tag, "FOCUS_TRACK_START x=$safeX y=$safeY pinned=$pinned mirrorX=$mirrorX durationMs=$durationMs")
        if (!pinned && durationMs != null) {
            val safeDuration = durationMs.coerceIn(250L, 30_000L)
            val deadline = android.os.SystemClock.elapsedRealtime() + safeDuration
            trackingTimeoutJob = focusTimingScope.launch {
                awaitMonotonicDeadline(deadline)
                if (_focusOwnership.value.trackingActive && !focusTrackingPinned) {
                    stopFocusTracking(reason = "focus_lock_timeout", restoreConfiguredAf = true)
                }
            }
        }
    }

    private fun restoreTrackingOwnershipAfterPipelineTransition(reason: String) {
        // AF regions are expressed in the coordinate domain of the current Camera2 owner. A
        // physical region from the previous pipeline must never survive a lens/session handover.
        clearPhysicalAfRegionState()
        if (!_focusTrackingActive.value) {
            transitionFocusOwner(FocusOwner.AUTO, reason)
            return
        }
        val bounds = _trackedObjectBounds.value
        val targetX = bounds?.let { (it.left + it.right) * 0.5f }
            ?: trackingSmoothedX.takeIf { it.isFinite() }
            ?: 0.5f
        val targetY = bounds?.let { (it.top + it.bottom) * 0.5f }
            ?: trackingSmoothedY.takeIf { it.isFinite() }
            ?: 0.5f
        activeTrackingId = null
        lastTrackedBoxPx = null
        pendingTapX = if (focusTrackingMirrorX) 1f - targetX else targetX
        pendingTapY = targetY
        trackingLostFrames = 0
        trackingSmoothedX = Float.NaN
        trackingSmoothedY = Float.NaN
        trackingVelocityX = 0f
        trackingVelocityY = 0f
        trackingLastObservationNs = 0L
        trackingLastHardwareSubmitNs = 0L
        lastHwFocusX = -1f
        lastHwFocusY = -1f
        lastHwFocusRegionPct = -1f
        transitionFocusOwner(
            owner = FocusOwner.TRACK_ACQUIRING,
            reason = "$reason:track_reacquire",
            trackingPinned = focusTrackingPinned
        )
        _focusTrackingState.value = FocusTrackingState(
            phase = FocusTrackingPhase.ACQUIRING,
            confidence = 0.25f,
            pinned = focusTrackingPinned,
            reason = reason
        )
        refreshRawPreviewCompactAnalysisRequest()
    }

    fun prepareFocusTrackingForLensHandover() {
        if (!_focusTrackingActive.value) return
        enqueuePreviewControl("focus_tracking_handover", "FOCUS_TRACK_LENS_HANDOVER") {
            if (!_focusTrackingActive.value) return@enqueuePreviewControl
            restoreTrackingOwnershipAfterPipelineTransition("lens_handover")
        }
    }

    private fun clearFocusTrackingState(reason: String, restoreConfiguredAf: Boolean): Boolean {
        trackingTimeoutJob?.cancel()
        trackingTimeoutJob = null
        val wasActive = _focusTrackingActive.value || _focusOwnership.value.trackingActive
        focusTrackingPinned = false
        focusTrackingMirrorX = false
        pendingTapX = null
        pendingTapY = null
        activeTrackingId = null
        trackingLostFrames = 0
        lastTrackedBoxPx = null
        trackingSmoothedX = Float.NaN
        trackingSmoothedY = Float.NaN
        trackingVelocityX = 0f
        trackingVelocityY = 0f
        trackingLastObservationNs = 0L
        trackingLastHardwareSubmitNs = 0L
        lastHwFocusX = -1f
        lastHwFocusY = -1f
        lastHwFocusRegionPct = -1f
        _trackedObjectBounds.value = null
        _focusTrackingActive.value = false
        _focusTrackingState.value = FocusTrackingState(reason = reason)
        refreshRawPreviewCompactAnalysisRequest()
        if (wasActive) {
            Log.i(tag, "FOCUS_TRACK_STOP reason=$reason restoreAf=$restoreConfiguredAf")
        }
        if (!restoreConfiguredAf && _focusOwnership.value.trackingActive) {
            transitionFocusOwner(FocusOwner.AUTO, "track_cleared:$reason")
        }
        return wasActive
    }

    fun stopFocusTracking(reason: String = "user_cancel", restoreConfiguredAf: Boolean = true) {
        val wasActive = clearFocusTrackingState(reason, restoreConfiguredAf)
        if (wasActive && restoreConfiguredAf) restoreConfiguredAutoFocusAfterTap()
    }

    // NIEUW: Zet alles vast op het huidige punt
    fun lockFocusAndExposure() {
        enqueuePreviewControl("ae_af_lock", "LOCK_FOCUS_AND_EXPOSURE") {
            lockFocusAndExposureOwned()
        }
    }

    private fun lockFocusAndExposureOwned() {
        val session = captureSession ?: return
        val request = currentCaptureRequest ?: return
        val handler = backgroundHandler ?: return
        try {
            trackingTimeoutJob?.cancel()
            trackingTimeoutJob = null
            tapFocusTimeoutJob?.cancel()
            tapFocusTimeoutJob = null
            predictiveAfTracker.clear()
            if (_focusOwnership.value.trackingActive) {
                clearFocusTrackingState(reason = "ae_af_lock", restoreConfiguredAf = false)
            }
            transitionFocusOwner(
                owner = FocusOwner.AE_AF_LOCK,
                reason = "ae_af_lock",
                focusLocked = true,
                aeLocked = true
            )
            request.set(CaptureRequest.CONTROL_AE_LOCK, true)

            val afMode = request.get(CaptureRequest.CONTROL_AF_MODE)
                ?: CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
            if (afMode != CaptureRequest.CONTROL_AF_MODE_OFF) {
                // AF_MODE_AUTO alone is not an AF lock operation. START initiates the one-shot AF
                // transaction; the HAL then holds FOCUSED_LOCKED / NOT_FOCUSED_LOCKED until CANCEL
                // or an AF mode change.
                request.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
                request.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START)
                traceAfWriter(request, "lockFocusAndExposureOwned", "AE_AF_LOCK_TRIGGER_WRITES")
                submitOneShotRequestWithProvenance(
                    session = session,
                    builder = request,
                    callback = captureCallback,
                    handler = handler,
                    reason = "AE_AF_LOCK_TRIGGER"
                )
                request.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE)
            }

            traceAfWriter(request, "lockFocusAndExposureOwned", "AE_AF_LOCK_HOLD_WRITES")
            submitRepeatingRequestWithProvenance(
                session = session,
                builder = request,
                callback = captureCallback,
                handler = handler,
                reason = "AE_AF_LOCK_HOLD"
            )
        } catch (e: Exception) {
            transitionFocusOwner(FocusOwner.AUTO, "ae_af_lock_failed")
            Log.e(tag, "Failed to lock focus and exposure", e)
        }
    }

    // ========================================================
    // LENS DISCOVERY
    // ========================================================
    private enum class LensSourceType {
        PHYSICAL,
        DIRECT,
        PROBED,
        UNKNOWN
    }

    private enum class AutoLensCategory(val slotName: String) {
        MAIN("Main"),
        ULTRA_WIDE("Ultra wide"),
        TELE("Tele"),
        FRONT("Front"),
        UNKNOWN("Unknown")
    }

    private data class AutoLensCandidate(
        val id: String,
        val facing: Int,
        val sourceType: LensSourceType,
        val parentLogicalId: String?,
        val isLogicalParent: Boolean,
        val focalLength: Float,
        val availableFocalLengths: List<Float>,
        val equivalentFocalLength35mm: Float?,
        val fov: Int,
        val sensorOrientation: Int,
        val category: AutoLensCategory,
        val categoryScore: Float,
        val ratioToMain: Float,
        val ratioSource: String,
        val debugReason: String
    )

    private fun sortedLensIds(ids: Collection<String>): List<String> {
        return ids.distinct().sortedWith { left, right ->
            val leftInt = left.toIntOrNull()
            val rightInt = right.toIntOrNull()
            when {
                leftInt != null && rightInt != null -> leftInt.compareTo(rightInt)
                leftInt != null -> -1
                rightInt != null -> 1
                else -> left.compareTo(right)
            }
        }
    }

    private fun lensSourcePriority(sourceType: LensSourceType): Int {
        return when (sourceType) {
            LensSourceType.PHYSICAL -> 0
            LensSourceType.DIRECT -> 1
            LensSourceType.PROBED -> 2
            LensSourceType.UNKNOWN -> 3
        }
    }

    @SuppressLint("InlinedApi")
    private fun buildAutoLensCandidates(
        additionalLensIds: Set<String> = emptySet(),
        hiddenProbeMaxInclusive: Int = 50
    ): List<AutoLensCandidate> {
        val directIds = try {
            cameraManager.cameraIdList.toSet()
        } catch (e: Exception) {
            Log.e(tag, "AUTO_LENS direct cameraIdList read failed", e)
            emptySet()
        }

        val physicalParentById = mutableMapOf<String, String>()

        for (directId in sortedLensIds(directIds)) {
            try {
                val chars = cameraManager.getCameraCharacteristics(directId)
                val caps = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
                val isLogicalParent =
                    caps?.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA) == true

                if (isLogicalParent) {
                    for (physicalId in sortedLensIds(chars.physicalCameraIds)) {
                        physicalParentById.putIfAbsent(physicalId, directId)
                    }
                }
            } catch (e: Exception) {
                Log.w(tag, "AUTO_LENS physical ID read failed directId=$directId", e)
            }
        }

        val probedIds = mutableSetOf<String>()
        val explicitIds = additionalLensIds.map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        if (hiddenProbeMaxInclusive >= 0) {
            for (i in 0..hiddenProbeMaxInclusive) {
                val id = i.toString()
                if (id in directIds || id in physicalParentById.keys) continue

                try {
                    cameraManager.getCameraCharacteristics(id)
                    probedIds.add(id)
                } catch (_: Exception) {
                    // ID is not exposed on this device.
                }
            }
        }
        // Persisted/manual IDs are authoritative discovery requests and are always validated,
        // regardless of the numeric probe range or whether the vendor uses a non-numeric ID.
        for (id in explicitIds) {
            if (id in directIds || id in physicalParentById.keys) continue
            try {
                cameraManager.getCameraCharacteristics(id)
                probedIds.add(id)
            } catch (_: Exception) {
                Log.w(tag, "MANUAL_LENS_ID unavailable id=$id")
            }
        }

        data class RawCandidate(
            val id: String,
            val facing: Int,
            val sourceType: LensSourceType,
            val parentLogicalId: String?,
            val isLogicalParent: Boolean,
            val focalLength: Float,
            val availableFocalLengths: List<Float>,
            val equivalentFocalLength35mm: Float?,
            val fov: Int,
            val sensorOrientation: Int
        )

        val rawCandidates = mutableListOf<RawCandidate>()
        val allIds = sortedLensIds(directIds + physicalParentById.keys + probedIds)

        for (id in allIds) {
            try {
                val chars = cameraManager.getCameraCharacteristics(id)
                val caps = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
                val isLogicalParent =
                    caps?.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA) == true
                val facing = chars.get(CameraCharacteristics.LENS_FACING)
                    ?: CameraCharacteristics.LENS_FACING_BACK
                val availableFocalLengths =
                    chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                        ?.asSequence()
                        ?.filter { it.isFinite() && it > 0f }
                        ?.distinct()
                        ?.sorted()
                        ?.toList()
                        .orEmpty()
                val focalLength = selectRepresentativeFocalLength(
                    chars = chars,
                    availableFocalLengths = availableFocalLengths,
                    isLogicalParent = isLogicalParent
                )
                val sourceType = when {
                    id in physicalParentById.keys -> LensSourceType.PHYSICAL
                    id in directIds -> LensSourceType.DIRECT
                    id in probedIds -> LensSourceType.PROBED
                    else -> LensSourceType.UNKNOWN
                }

                rawCandidates.add(
                    RawCandidate(
                        id = id,
                        facing = facing,
                        sourceType = sourceType,
                        parentLogicalId = physicalParentById[id],
                        isLogicalParent = isLogicalParent,
                        focalLength = focalLength,
                        availableFocalLengths = availableFocalLengths,
                        equivalentFocalLength35mm = calculateEquivalentFocalLength35mm(chars, focalLength),
                        fov = calculateFov(chars, focalLength),
                        sensorOrientation = normalizeRightAngle(
                            chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
                        )
                    )
                )
            } catch (e: Exception) {
                Log.w(tag, "AUTO_LENS candidate read failed id=$id", e)
            }
        }

        val backCandidates = rawCandidates.filter {
            it.facing == CameraCharacteristics.LENS_FACING_BACK && it.focalLength > 0f
        }
        // Logical multi-camera parents often report the union of multiple physical focal
        // lengths against one sensor-size record. That metadata is useful for manual access but
        // is not reliable enough to define the optical 1.0x reference when physical/direct
        // single-camera metadata is available.
        val mainReferencePool = backCandidates
            .filterNot { it.isLogicalParent }
            .ifEmpty { backCandidates }

        val mainReference = mainReferencePool
            .filter { it.equivalentFocalLength35mm != null }
            .minWithOrNull(
                compareBy<RawCandidate> { abs((it.equivalentFocalLength35mm ?: 26f) - 26f) }
                    .thenBy { lensSourcePriority(it.sourceType) }
                    .thenBy { it.id.toIntOrNull() ?: Int.MAX_VALUE }
                    .thenBy { it.id }
            )
            ?: mainReferencePool.minWithOrNull(
                compareBy<RawCandidate> { abs(it.focalLength - 5.0f) }
                    .thenBy { lensSourcePriority(it.sourceType) }
                    .thenBy { it.id.toIntOrNull() ?: Int.MAX_VALUE }
                    .thenBy { it.id }
            )

        val mainFocalLength = mainReference?.focalLength ?: 5.0f
        val mainEquivalentFocal = mainReference?.equivalentFocalLength35mm

        val candidates = rawCandidates.map { raw ->
            val ratioFromEquivalent = if (
                mainEquivalentFocal != null && mainEquivalentFocal > 0f &&
                raw.equivalentFocalLength35mm != null && raw.equivalentFocalLength35mm > 0f
            ) {
                raw.equivalentFocalLength35mm / mainEquivalentFocal
            } else null
            val ratio = ratioFromEquivalent ?: if (mainFocalLength > 0f && raw.focalLength > 0f) {
                raw.focalLength / mainFocalLength
            } else {
                1f
            }
            val ratioSource = if (ratioFromEquivalent != null) "35mm_equivalent" else "physical_focal_fallback"

            val category = when {
                raw.facing == CameraCharacteristics.LENS_FACING_FRONT -> AutoLensCategory.FRONT
                raw.facing != CameraCharacteristics.LENS_FACING_BACK -> AutoLensCategory.UNKNOWN
                raw.focalLength <= 0f -> AutoLensCategory.UNKNOWN
                ratio < 0.80f -> AutoLensCategory.ULTRA_WIDE
                ratio > 1.45f -> AutoLensCategory.TELE
                else -> AutoLensCategory.MAIN
            }

            // Prefer the normal adjacent optical module for primary slots rather than an
            // extreme hidden/macro/periscope route. Manual lens activation still exposes every
            // discovered ID below.
            val categoryScore = when (category) {
                AutoLensCategory.MAIN -> abs(ratio - 1f)
                AutoLensCategory.ULTRA_WIDE -> abs(ratio - 0.55f)
                AutoLensCategory.TELE -> abs(ratio - 2.5f)
                AutoLensCategory.FRONT -> 0f
                AutoLensCategory.UNKNOWN -> Float.MAX_VALUE
            }

            AutoLensCandidate(
                id = raw.id,
                facing = raw.facing,
                sourceType = raw.sourceType,
                parentLogicalId = raw.parentLogicalId,
                isLogicalParent = raw.isLogicalParent,
                focalLength = raw.focalLength,
                availableFocalLengths = raw.availableFocalLengths,
                equivalentFocalLength35mm = raw.equivalentFocalLength35mm,
                fov = raw.fov,
                sensorOrientation = raw.sensorOrientation,
                category = category,
                categoryScore = categoryScore,
                ratioToMain = ratio,
                ratioSource = ratioSource,
                debugReason = "source=${raw.sourceType} parent=${raw.parentLogicalId ?: "none"} " +
                        "logicalParent=${raw.isLogicalParent} focal=${raw.focalLength} " +
                        "availableFocals=${raw.availableFocalLengths.joinToString(prefix = "[", postfix = "]")} " +
                        "equiv35=${raw.equivalentFocalLength35mm ?: "unknown"} " +
                        "mainId=${mainReference?.id ?: "none"} mainFocal=$mainFocalLength " +
                        "mainEquiv35=${mainEquivalentFocal ?: "unknown"} ratio=$ratio " +
                        "ratioSource=$ratioSource fov=${raw.fov} orientation=${raw.sensorOrientation} " +
                        "category=${category.slotName} score=$categoryScore"
            )
        }

        Log.i(
            tag,
            "AUTO_LENS_DISCOVERY direct=${sortedLensIds(directIds)} " +
                    "physical=${sortedLensIds(physicalParentById.keys)} probed=${
                        sortedLensIds(
                            probedIds
                        )
                    } " +
                    "mainId=${mainReference?.id ?: "none"} mainFocal=$mainFocalLength " +
                    "mainEquiv35=${mainEquivalentFocal ?: "unknown"}"
        )
        candidates.forEach { candidate ->
            Log.i(tag, "AUTO_LENS_CANDIDATE id=${candidate.id} ${candidate.debugReason}")
        }

        return candidates.sortedWith(
            compareBy<AutoLensCandidate> { it.id.toIntOrNull() ?: Int.MAX_VALUE }
                .thenBy { it.id }
        )
    }

    @SuppressLint("InlinedApi")
    fun getAvailableLenses(
        additionalLensIds: Set<String> = emptySet(),
        hiddenProbeMaxInclusive: Int = 50
    ): List<LensInfo> {
        return try {
            val candidates = buildAutoLensCandidates(additionalLensIds, hiddenProbeMaxInclusive)
            val usedTitles = mutableSetOf<String>()
            var extraCounter = 1

            val backReferenceOrientation = candidates
                .filter {
                    it.facing == CameraCharacteristics.LENS_FACING_BACK &&
                        it.category == AutoLensCategory.MAIN && !it.isLogicalParent
                }
                .minWithOrNull(
                    compareBy<AutoLensCandidate> { lensSourcePriority(it.sourceType) }
                        .thenBy { it.categoryScore }
                        .thenBy { it.id.toIntOrNull() ?: Int.MAX_VALUE }
                )?.sensorOrientation
                ?: candidates.firstOrNull {
                    it.facing == CameraCharacteristics.LENS_FACING_BACK &&
                        it.category == AutoLensCategory.MAIN
                }?.sensorOrientation
                ?: 0
            val frontReferenceOrientation = candidates
                .filter { it.facing == CameraCharacteristics.LENS_FACING_FRONT }
                .minWithOrNull(
                    compareBy<AutoLensCandidate> { lensSourcePriority(it.sourceType) }
                        .thenBy { it.id.toIntOrNull() ?: Int.MAX_VALUE }
                )?.sensorOrientation
                ?: 0

            val lenses = candidates.map { candidate ->
                val category = candidate.category.slotName
                val ratioStr = getRatioString(candidate.ratioToMain)
                val focalStr = if (candidate.focalLength > 0f) {
                    if (candidate.availableFocalLengths.size > 1) {
                        val minFocal = candidate.availableFocalLengths.first()
                        val maxFocal = candidate.availableFocalLengths.last()
                        String.format(
                            Locale.US,
                            "%.1fmm base (%.1f-%.1fmm reported)",
                            candidate.focalLength,
                            minFocal,
                            maxFocal
                        )
                    } else {
                        String.format(Locale.US, "%.1fmm physical", candidate.focalLength)
                    }
                } else {
                    "unknown focal"
                }
                val equivalentFocalStr = candidate.equivalentFocalLength35mm?.let {
                    String.format(Locale.US, "%.0fmm eq", it)
                } ?: "eq unknown"
                val buttonName =
                    if (candidate.facing == CameraCharacteristics.LENS_FACING_FRONT) {
                        "Front"
                    } else {
                        ratioStr
                    }

                var listTitle = when {
                    candidate.category == AutoLensCategory.UNKNOWN -> "Unknown — ${candidate.id}"
                    candidate.sourceType == LensSourceType.DIRECT && candidate.isLogicalParent -> "$category — ${candidate.id}"
                    candidate.sourceType == LensSourceType.PROBED -> "$category — ${candidate.id}"
                    candidate.sourceType == LensSourceType.UNKNOWN -> "$category — ${candidate.id}"
                    else -> category
                }

                val sourceLabel = when (candidate.sourceType) {
                    LensSourceType.PHYSICAL -> "physical ${candidate.id} via ${candidate.parentLogicalId ?: "unknown"}"
                    LensSourceType.DIRECT -> if (candidate.isLogicalParent) {
                        "direct logical ${candidate.id}"
                    } else {
                        "direct ${candidate.id}"
                    }

                    LensSourceType.PROBED -> "probed hidden ${candidate.id}"
                    LensSourceType.UNKNOWN -> "unknown source ${candidate.id}"
                }

                val listDescription = buildString {
                    append(category)
                    if (candidate.category != AutoLensCategory.FRONT) append(" • $ratioStr")
                    append(" • $sourceLabel")
                    append(" • $focalStr")
                    append(" • $equivalentFocalStr")
                    append(" • ${candidate.fov}° diagonal FOV")
                    append(" • ${candidate.sensorOrientation}° sensor")
                    append(" • ${candidate.ratioSource.replace('_', ' ')}")
                }

                if (!listTitle.contains("—")) {
                    if (usedTitles.contains(listTitle)) {
                        listTitle = "Extra $extraCounter"
                        extraCounter++
                    }
                    usedTitles.add(listTitle)
                }

                val referenceOrientation = if (candidate.facing == CameraCharacteristics.LENS_FACING_FRONT) {
                    frontReferenceOrientation
                } else {
                    backReferenceOrientation
                }
                val yuvOrientationCorrection = normalizeRightAngle(
                    candidate.sensorOrientation - referenceOrientation
                )

                LensInfo(
                    id = candidate.id,
                    name = buttonName,
                    facing = candidate.facing,
                    isLogicalMultiCamera = candidate.isLogicalParent,
                    listTitle = listTitle,
                    listDescription = listDescription,
                    opticalZoomRatio = candidate.ratioToMain,
                    equivalentFocalLength35mm = candidate.equivalentFocalLength35mm,
                    sensorOrientationDegrees = candidate.sensorOrientation,
                    yuvPreviewOrientationCorrectionDegrees = yuvOrientationCorrection
                )
            }

            lenses.sortedWith(
                compareBy<LensInfo> { it.facing == CameraCharacteristics.LENS_FACING_FRONT }
                    .thenBy { it.listTitle.contains("—") }
                    .thenBy { it.id.toIntOrNull() ?: Int.MAX_VALUE }
                    .thenBy { it.id }
            )
        } catch (e: Exception) {
            Log.e(tag, "Fatale fout bij hardware uitlezen", e)
            listOf(
                LensInfo(
                    "0",
                    "1.0x",
                    CameraCharacteristics.LENS_FACING_BACK,
                    false,
                    "Main",
                    "Fallback lens"
                )
            )
        }
    }

    fun getManualDiscoveryLenses(additionalLensIds: Set<String> = emptySet()): List<LensInfo> {
        // Manual configuration is allowed to probe a broader vendor range because this path is
        // user-invoked and not part of camera startup. Arbitrary/non-numeric IDs remain possible
        // through `resolveManualLensId`.
        return getAvailableLenses(
            additionalLensIds = additionalLensIds,
            hiddenProbeMaxInclusive = 255
        )
    }

    fun resolveManualLensId(lensId: String): LensInfo? {
        val normalizedId = lensId.trim()
        if (normalizedId.isEmpty()) return null
        return getAvailableLenses(
            additionalLensIds = setOf(normalizedId),
            hiddenProbeMaxInclusive = -1
        ).firstOrNull { it.id == normalizedId }
    }

    fun getAutoAssignedSlots(): Map<String, String> {
        val candidates = buildAutoLensCandidates()
        val assignments = mutableMapOf<String, String>()

        fun findBestLensForCategory(category: AutoLensCategory): AutoLensCandidate? {
            val matching = candidates.filter { it.category == category }
            val selected = matching.minWithOrNull(
                compareBy<AutoLensCandidate> { lensSourcePriority(it.sourceType) }
                    .thenBy { it.categoryScore }
                    .thenBy { it.id.toIntOrNull() ?: Int.MAX_VALUE }
                    .thenBy { it.id }
            )

            Log.i(
                tag,
                "AUTO_LENS_SELECT category=${category.slotName} selected=${selected?.id ?: "none"} " +
                        "candidates=${matching.joinToString { "${it.id}:${it.sourceType}:score=${it.categoryScore}" }}"
            )

            return selected
        }

        listOf(
            AutoLensCategory.MAIN,
            AutoLensCategory.ULTRA_WIDE,
            AutoLensCategory.TELE,
            AutoLensCategory.FRONT
        ).forEach { category ->
            findBestLensForCategory(category)?.let { selected ->
                assignments[category.slotName] = selected.id
            }
        }

        Log.i(tag, "AUTO_LENS_ASSIGNMENTS $assignments")
        return assignments
    }

    private fun getRatioString(ratio: Float): String {
        return if (abs(ratio - 1.0f) < 0.1f) "1.0x" else String.format(
            Locale.US,
            "%.1fx",
            ratio
        )
    }

    private fun selectRepresentativeFocalLength(
        chars: CameraCharacteristics,
        availableFocalLengths: List<Float>,
        isLogicalParent: Boolean
    ): Float {
        if (availableFocalLengths.isEmpty()) return 0f
        if (availableFocalLengths.size == 1) return availableFocalLengths.first()

        // Physical/vendor routes occasionally expose more than one optical focal state. For a
        // real lens route, the widest native focal state is the stable lens-switch baseline used
        // by camera UIs. A logical multi-camera parent is different: its focal list can be the
        // union of several physical cameras, so choose the member closest to a normal main-camera
        // field of view only for descriptive/manual display. Logical parents are not preferred as
        // primary auto-assignment candidates when physical routes are available.
        if (!isLogicalParent) return availableFocalLengths.first()

        return availableFocalLengths
            .map { focal -> focal to calculateEquivalentFocalLength35mm(chars, focal) }
            .filter { (_, equivalent) -> equivalent != null }
            .minByOrNull { (_, equivalent) -> abs((equivalent ?: 26f) - 26f) }
            ?.first
            ?: availableFocalLengths.first()
    }

    private fun calculateEquivalentFocalLength35mm(
        chars: CameraCharacteristics,
        focalLengthMm: Float
    ): Float? {
        if (!focalLengthMm.isFinite() || focalLengthMm <= 0f) return null
        val sensorSize = chars.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE) ?: return null
        val width = sensorSize.width.toDouble()
        val height = sensorSize.height.toDouble()
        if (!width.isFinite() || !height.isFinite() || width <= 0.0 || height <= 0.0) return null

        // Reject obviously inconsistent vendor metadata before it can poison the x-ratio. Some
        // hidden camera IDs report a physical size copied from another route. Camera2's pixel
        // array gives us a cheap geometry cross-check without any device/model hardcoding.
        val pixelArray = chars.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
        if (pixelArray != null && pixelArray.width > 0 && pixelArray.height > 0) {
            val physicalAspect = max(width, height) / min(width, height)
            val pixelAspect = max(pixelArray.width, pixelArray.height).toDouble() /
                min(pixelArray.width, pixelArray.height).toDouble()
            val aspectRatioMismatch = max(physicalAspect, pixelAspect) / min(physicalAspect, pixelAspect)
            if (!aspectRatioMismatch.isFinite() || aspectRatioMismatch > 1.30) return null
        }

        val sensorDiagonal = sqrt(width * width + height * height)
        if (!sensorDiagonal.isFinite() || sensorDiagonal < 0.5 || sensorDiagonal > 80.0) return null
        val fullFrameDiagonalMm = 43.2666153056
        val equivalent = focalLengthMm.toDouble() * fullFrameDiagonalMm / sensorDiagonal
        return equivalent.takeIf { it.isFinite() && it in 3.0..400.0 }?.toFloat()
    }

    private fun calculateFov(chars: CameraCharacteristics, focalLengthMm: Float): Int {
        try {
            val sensorSize = chars.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
            if (focalLengthMm > 0f && sensorSize != null) {
                val w = sensorSize.width
                val h = sensorSize.height
                val diagonal = sqrt((w * w + h * h).toDouble())
                val fov = 2.0 * atan(diagonal / (2.0 * focalLengthMm))
                return Math.toDegrees(fov).roundToInt().coerceIn(1, 179)
            }
        } catch (_: Exception) { /* Skip */
        }
        return 0
    }

    private fun normalizeRightAngle(degrees: Int): Int {
        val normalized = ((degrees % 360) + 360) % 360
        return when {
            normalized < 45 -> 0
            normalized < 135 -> 90
            normalized < 225 -> 180
            normalized < 315 -> 270
            else -> 0
        }
    }

    // ========================================================
    // ROTATIE HELPER
    // ========================================================
    private fun getJpegOrientation(chars: CameraCharacteristics, deviceRotation: Int): Int {
        val sensorOrientation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
        val deviceDegrees = when (deviceRotation) {
            Surface.ROTATION_0 -> 0
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
        val facing = chars.get(CameraCharacteristics.LENS_FACING)
        return OutputRotationResolver.resolve(
            sensorOrientationDegrees = sensorOrientation,
            displayRotationDegrees = deviceDegrees,
            frontFacing = facing == CameraCharacteristics.LENS_FACING_FRONT
        )
    }

    private fun flashAeState(state: Int?): FlashAeState = when (state) {
        CaptureResult.CONTROL_AE_STATE_INACTIVE -> FlashAeState.INACTIVE
        CaptureResult.CONTROL_AE_STATE_SEARCHING -> FlashAeState.SEARCHING
        CaptureResult.CONTROL_AE_STATE_CONVERGED -> FlashAeState.CONVERGED
        CaptureResult.CONTROL_AE_STATE_LOCKED -> FlashAeState.LOCKED
        CaptureResult.CONTROL_AE_STATE_FLASH_REQUIRED -> FlashAeState.FLASH_REQUIRED
        CaptureResult.CONTROL_AE_STATE_PRECAPTURE -> FlashAeState.PRECAPTURE
        else -> FlashAeState.UNKNOWN
    }

    private fun resolveFlashControlPlan(
        characteristics: CameraCharacteristics,
        flashMode: String
    ): FlashCameraControlPlan? {
        if (characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) != true) return null
        val availableAeModes =
            characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_MODES) ?: intArrayOf()
        return when {
            flashMode.equals("On", ignoreCase = true) &&
                availableAeModes.contains(CaptureRequest.CONTROL_AE_MODE_ON_ALWAYS_FLASH) ->
                FlashCameraControlPlan(
                    aeMode = CaptureRequest.CONTROL_AE_MODE_ON_ALWAYS_FLASH,
                    precaptureFlashMode = CaptureRequest.FLASH_MODE_OFF,
                    stillFlashMode = CaptureRequest.FLASH_MODE_OFF,
                    reason = "ae_always_flash"
                )

            flashMode.equals("On", ignoreCase = true) &&
                availableAeModes.contains(CaptureRequest.CONTROL_AE_MODE_ON) ->
                FlashCameraControlPlan(
                    aeMode = CaptureRequest.CONTROL_AE_MODE_ON,
                    precaptureFlashMode = CaptureRequest.FLASH_MODE_SINGLE,
                    stillFlashMode = CaptureRequest.FLASH_MODE_SINGLE,
                    reason = "flash_single_fallback"
                )

            flashMode.equals("Auto", ignoreCase = true) &&
                availableAeModes.contains(CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH) ->
                FlashCameraControlPlan(
                    aeMode = CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH,
                    precaptureFlashMode = CaptureRequest.FLASH_MODE_OFF,
                    stillFlashMode = CaptureRequest.FLASH_MODE_OFF,
                    reason = "ae_auto_flash"
                )

            else -> null
        }
    }

    private fun resolveFlashShutterDecision(
        characteristics: CameraCharacteristics,
        manualExposureActive: Boolean
    ): Pair<FlashShutterDecision, FlashCameraControlPlan?> {
        val controlPlan = resolveFlashControlPlan(characteristics, currentFlashMode)
        val observation = latestCamera3AObservation
        val availableAeModes =
            characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_MODES) ?: intArrayOf()
        val autoFlashAeModeSupported =
            availableAeModes.contains(CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH)
        val decision = FlashCapturePolicy.decide(
            mode = currentFlashMode,
            manualExposureActive = manualExposureActive,
            flashHardwareAvailable =
                characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true &&
                    (currentFlashMode.equals("Off", ignoreCase = true) || controlPlan != null),
            autoFlashAeModeSupported = autoFlashAeModeSupported,
            currentPipelineGeneration = pipelineGeneration,
            observationGeneration = observation?.pipelineGeneration ?: -1,
            observationUsesAutoFlashAe =
                observation?.aeMode == CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH,
            observedAeState = flashAeState(observation?.aeState)
        )
        return decision to controlPlan
    }

    private suspend fun waitForExactControlRequestObservation(
        controlRequestEpoch: Long,
        expectedGeneration: Int,
        timeoutMs: Long
    ): Camera3AObservation? {
        if (controlRequestEpoch <= 0L) return null
        val deadline = android.os.SystemClock.elapsedRealtime() + timeoutMs.coerceAtLeast(1L)
        while (android.os.SystemClock.elapsedRealtime() <= deadline) {
            findCamera3AObservation(
                expectedGeneration = expectedGeneration,
                controlRequestEpoch = controlRequestEpoch
            )?.let { return it }
            kotlinx.coroutines.delay(10L)
        }
        return findCamera3AObservation(
            expectedGeneration = expectedGeneration,
            controlRequestEpoch = controlRequestEpoch
        )
    }

    private suspend fun waitForAfSettleAfterTrigger(
        triggerControlRequestEpoch: Long,
        expectedGeneration: Int,
        timeoutMs: Long = 900L
    ): Boolean {
        val startedMs = android.os.SystemClock.elapsedRealtime()
        val triggerObservation = waitForExactControlRequestObservation(
            controlRequestEpoch = triggerControlRequestEpoch,
            expectedGeneration = expectedGeneration,
            timeoutMs = timeoutMs.coerceAtMost(500L)
        ) ?: return false
        val triggerFrame = triggerObservation.frameNumber
        fun ready(state: Int?): Boolean = when (state) {
            CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED,
            CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED,
            CaptureResult.CONTROL_AF_STATE_PASSIVE_FOCUSED,
            CaptureResult.CONTROL_AF_STATE_PASSIVE_UNFOCUSED -> true
            null -> true
            else -> false
        }
        if (ready(triggerObservation.afState)) return true
        val deadline = startedMs + timeoutMs.coerceAtLeast(1L)
        while (android.os.SystemClock.elapsedRealtime() <= deadline) {
            val observation = latestCamera3AObservation
            if (observation != null &&
                observation.pipelineGeneration == expectedGeneration &&
                observation.frameNumber >= triggerFrame &&
                ready(observation.afState)
            ) {
                return true
            }
            kotlinx.coroutines.delay(15L)
        }
        return false
    }

    private suspend fun waitForFlashPrecaptureCompletion(
        triggerControlRequestEpoch: Long,
        expectedGeneration: Int,
        timeoutMs: Long = 1800L
    ): FlashPrecaptureWaitResult {
        val startedMs = android.os.SystemClock.elapsedRealtime()
        val deadline = startedMs + timeoutMs.coerceAtLeast(1L)
        var triggerObservation: Camera3AObservation? = null
        var triggerObservedAtMs = 0L

        fun terminal(state: Int?): Boolean = when (state) {
            CaptureResult.CONTROL_AE_STATE_CONVERGED,
            CaptureResult.CONTROL_AE_STATE_FLASH_REQUIRED,
            CaptureResult.CONTROL_AE_STATE_LOCKED -> true
            else -> false
        }

        while (android.os.SystemClock.elapsedRealtime() <= deadline) {
            if (triggerObservation == null) {
                triggerObservation = findCamera3AObservation(
                    expectedGeneration = expectedGeneration,
                    controlRequestEpoch = triggerControlRequestEpoch
                )
                if (triggerObservation != null) {
                    triggerObservedAtMs = android.os.SystemClock.elapsedRealtime()
                    // PRECAPTURE is transient and may legally be skipped. If the exact START
                    // request itself already reports a terminal state, the HAL has completed the
                    // sequence in the same result and that is authoritative.
                    if (terminal(triggerObservation.aeState)) {
                        return FlashPrecaptureWaitResult(
                            completed = true,
                            triggerObserved = true,
                            finalAeState = triggerObservation.aeState,
                            reason = "terminal_state_on_trigger_result",
                            elapsedMs = android.os.SystemClock.elapsedRealtime() - startedMs
                        )
                    }
                }
            }

            val trigger = triggerObservation
            if (trigger != null) {
                // Scan the bounded result history rather than one volatile latest result. At 60fps
                // the one-shot START result can otherwise be overwritten by a repeating result
                // before this coroutine is scheduled.
                val observations = camera3AObservationsSince(
                    expectedGeneration = expectedGeneration,
                    minimumFrameNumber = trigger.frameNumber
                )
                val terminalObservation = observations.lastOrNull { observation ->
                    terminal(observation.aeState)
                }
                if (terminalObservation != null) {
                    return FlashPrecaptureWaitResult(
                        completed = true,
                        triggerObserved = true,
                        finalAeState = terminalObservation.aeState,
                        reason = "terminal_state_after_precapture",
                        elapsedMs = android.os.SystemClock.elapsedRealtime() - startedMs
                    )
                }

                // CONTROL_AE_STATE is optional on some HALs. Once the exact START request has
                // unquestionably been observed, use a bounded compatibility delay rather than
                // treating a stale pre-trigger AE state as convergence.
                val newestObservation = observations.lastOrNull()
                if (newestObservation?.aeState == null &&
                    triggerObservedAtMs > 0L &&
                    android.os.SystemClock.elapsedRealtime() - triggerObservedAtMs >= 350L
                ) {
                    return FlashPrecaptureWaitResult(
                        completed = true,
                        triggerObserved = true,
                        finalAeState = null,
                        reason = "ae_state_unreported_compatibility_delay",
                        elapsedMs = android.os.SystemClock.elapsedRealtime() - startedMs
                    )
                }
            }
            kotlinx.coroutines.delay(10L)
        }

        val triggerObserved = triggerObservation != null
        return FlashPrecaptureWaitResult(
            completed = false,
            triggerObserved = triggerObserved,
            finalAeState = latestCamera3AObservation
                ?.takeIf { it.pipelineGeneration == expectedGeneration }
                ?.aeState,
            reason = if (triggerObserved) {
                "precapture_terminal_state_timeout"
            } else {
                "precapture_trigger_result_timeout"
            },
            elapsedMs = android.os.SystemClock.elapsedRealtime() - startedMs
        )
    }

    private fun finishCaptureAttempt(
        captureAttemptId: Long,
        outputUri: Uri?,
        reason: String,
        outputProduced: Boolean = outputUri != null
    ) {
        // This is the only normal terminal path. It resets capture/processing/save/pending
        // state and restores repeating continuity for success and every failure category.
        backgroundHandler?.post { updatePreviewRepeatingRequest() }
        capturePreviewContinuityTracker.repeatingRequestState(
            captureSession != null && currentCaptureRequest != null
        )
        capturePreviewContinuityTracker.finish(
            attemptId = captureAttemptId,
            sessionEpoch = activeConfiguredSessionEpoch,
            captureOutstandingImageCount = ringBuffer.leasedFrameCount()
        )?.let { continuity ->
            val summary = continuity.diagnosticSummary()
            traceCaptureRuntime("RAW_PREVIEW_CONTINUITY attemptId=$captureAttemptId $summary")
            com.bncam.core.debug.DiagnosticsAggregator.record(
                stream = com.bncam.core.debug.DiagnosticsAggregator.Stream.PERFORMANCE,
                scope = "CAPTURE attemptId=$captureAttemptId",
                section = "RAW CAPTURE PREVIEW CONTINUITY",
                content = summary
            )
        }
        val result = captureAttempts.terminalResult(captureAttemptId, outputProduced)
        captureAttempts.finish(captureAttemptId, result, reason)
        // Capture ownership changed. Re-evaluate whether QR/tracking/portrait still requires the
        // compact NV21 side image instead of leaving preview analysis in a stale pre-capture state.
        refreshRawPreviewCompactAnalysisRequest()
    }

    @Synchronized
    private fun applyRenderHealthFeedback(
        nativeStats: String,
        exposureTimeNs: Long,
        sensitivityIso: Int,
        source: String
    ) {
        if (managerShutdownRequested.get()) return
        val highDrRisk =
            nativeStats.contains("renderHealthVerdict=JPEG_RENDER_HIGH_DR_RESCUE_LIMITED") ||
                nativeStats.contains("renderHealthVerdict=JPEG_RENDER_GAIN_LIMITED_UNDEREXPOSED") ||
                nativeStats.contains("renderHealthVerdict=JPEG_RENDER_TARGET_MISS")
        if (highDrRisk) {
            lastShotVerdictHighDrRisk = true
            lastShotExposureNs = exposureTimeNs
            lastShotIso = sensitivityIso
            Log.i(
                tag,
                "lastShotVerdictHighDrRisk=true source=$source " +
                    "baselineExpNs=$lastShotExposureNs baselineIso=$lastShotIso"
            )
        } else {
            lastShotVerdictHighDrRisk = false
            lastShotExposureNs = 0L
            lastShotIso = 0
        }
    }

    /**
     * Device-side validation entry point. Select a YUV, RAW10, or RAW_SENSOR profile and invoke
     * this method; after a lens switch invoke it again to validate the recreated session.
     */
    suspend fun runFiveCaptureValidation(
        activeProfile: CameraProfile,
        activeLens: LensInfo,
        shotLogger: ShotLogger,
        deviceRotation: Int
    ): List<RepeatedCaptureShotResult> = RepeatedCaptureValidator.runFiveShots { shotIndex ->
        val previousDebugDirectory = shotLogger.getShotDir()?.absolutePath
        val userShutterNs = android.os.SystemClock.elapsedRealtimeNanos()
        val output = executeCapture(
            activeProfile = activeProfile,
            activeLens = activeLens,
            shotLogger = shotLogger,
            deviceRotation = deviceRotation,
            userShutterTimestampNs = userShutterNs
        )
        val completed = captureAttempts.lastCompleted
        val timing = completed?.timings
        val result = RepeatedCaptureShotResult(
            shotIndex = shotIndex,
            captureAttemptId = completed?.captureAttemptId ?: -1L,
            success = completed?.result == CaptureAttemptResult.SUCCESS && output != null,
            jpegProduced = output != null,
            debugProduced = shotLogger.getShotDir()?.absolutePath?.let { it != previousDebugDirectory } == true,
            stateReset = captureAttempts.snapshot.stateReset,
            nextCaptureAllowed = captureAttempts.snapshot.nextCaptureAllowed,
            timeToCaptureMs = (timing?.timeShutterToRequestMs ?: 0.0) +
                (timing?.timeRequestToImageMs ?: 0.0) +
                (timing?.timeImageToProcessingStartMs ?: 0.0),
            timeToProcessMs = timing?.timeProcessingMs ?: 0.0,
            timeToSaveMs = timing?.timeSaveMs ?: 0.0
        )
        Log.i(
            "BnCamRepeatValidation",
            "format=${formatName(activeZslFormat)} cameraId=${activeLens.id} lens=${activeLens.name} " +
                "shotIndex=${result.shotIndex} captureAttemptId=${result.captureAttemptId} " +
                "success=${result.success} jpegProduced=${result.jpegProduced} debugProduced=${result.debugProduced} " +
                "stateReset=${result.stateReset} nextCaptureAllowed=${result.nextCaptureAllowed} " +
                "timeToCaptureMs=${result.timeToCaptureMs} timeToProcessMs=${result.timeToProcessMs} " +
                "timeToSaveMs=${result.timeToSaveMs}"
        )
        result
    }


    // ========================================================
// DE CAPTURE ROUTER
// ========================================================
    /**
     * Captures one exact post-shutter HDR bracket without relaxing Near-ZSL eligibility. Every
     * Camera2 request is matched back to the ring by sensor timestamp + pipeline generation +
     * control-request epoch before ownership is transferred to MultiFrameRunner.
     */
    private suspend fun acquireHdrEnhancedBurst(
        settingsRepo: SettingsRepository,
        activeLens: LensInfo,
        recipe: com.bncam.core.capture.CaptureRecipe,
        activeFormat: Int,
        userShutterTimestampNs: Long,
        focusCaptureContext: FocusCaptureContext
    ): com.bncam.core.capture.HdrEnhancedCaptureContext? {
        if (activeFormat != ImageFormat.RAW10 && activeFormat != ImageFormat.RAW_SENSOR) {
            traceCaptureRuntime("HDR_ENHANCED_ABORT reason=raw_stream_required actualFormat=${formatName(activeFormat)}")
            return null
        }
        val session = captureSession ?: return null
        val device = cameraDevice ?: return null
        val reader = imageReader ?: return null
        val previewBuilder = currentCaptureRequest ?: return null
        val generationAtStart = pipelineGeneration
        val characteristics = runCatching {
            cameraManager.getCameraCharacteristics(device.id)
        }.getOrNull() ?: return null
        val capabilities = characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf()
        if (CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR !in capabilities) {
            traceCaptureRuntime("HDR_ENHANCED_ABORT reason=manual_sensor_capability_required")
            return null
        }

        // Pre-shutter data is planning evidence only. No pre-shutter frame is used as an HDR main
        // processing frame: the main stack below is acquired deliberately after the shutter press.
        val planningSnapshot = ringBuffer.queryCandidateSnapshots(
            userShutterTimestampNs = userShutterTimestampNs,
            maxCount = 1,
            shutterTimestampDomain = "ELAPSED_REALTIME"
        ).lastOrNull() ?: run {
            traceCaptureRuntime("HDR_ENHANCED_ABORT reason=no_pre_shutter_planning_frame")
            return null
        }
        val planningMetadata = planningSnapshot.metadata ?: run {
            traceCaptureRuntime("HDR_ENHANCED_ABORT reason=no_pre_shutter_planning_metadata")
            return null
        }
        val baseExposureNs = planningMetadata.get(CaptureResult.SENSOR_EXPOSURE_TIME)?.takeIf { it > 0L }
            ?: return null
        val baseIso = planningMetadata.get(CaptureResult.SENSOR_SENSITIVITY)?.takeIf { it > 0 }
            ?: return null

        val streamMap = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val streamSize = android.util.Size(reader.width, reader.height)
        val advertisedMinFrameDurationNs = runCatching {
            streamMap?.getOutputMinFrameDuration(activeFormat, streamSize) ?: 0L
        }.getOrDefault(0L)
        val observedFrameDurationNs = planningMetadata.get(CaptureResult.SENSOR_FRAME_DURATION)?.takeIf { it > 0L }
        val cadenceAuthorityNs = advertisedMinFrameDurationNs.takeIf { it in 1_000_000L..200_000_000L }
            ?: observedFrameDurationNs?.takeIf { it in 1_000_000L..200_000_000L }
            ?: run {
                traceCaptureRuntime(
                    "HDR_ENHANCED_ABORT reason=no_valid_stream_cadence format=${formatName(activeFormat)} size=${reader.width}x${reader.height}"
                )
                return null
            }

        // Statistics are strictly latest-available snapshots. Shutter never waits for a new one.
        val statisticsSnapshot = latestExposureStatistics
        val clippingSnapshot = statisticsSnapshot?.rawNearClipFraction
            ?: statisticsSnapshot?.maximumDisplayClipFraction
        val frameOrigin = when (activeFormat) {
            ImageFormat.RAW10 -> FrameOrigin.RAW10
            ImageFormat.RAW_SENSOR -> FrameOrigin.RAW_SENSOR
            else -> return null
        }
        val productCapacity = com.bncam.core.capture.FrameCapacityPolicy.maximumProcessingFrames(frameOrigin)
        val recipeRuntimeCapacity = recipe.processingFrameResolution.effectiveValue.coerceAtLeast(0)
        val hardwareCapacity = minOf(15, productCapacity, recipeRuntimeCapacity).coerceAtLeast(0)
        val memoryInfo = android.app.ActivityManager.MemoryInfo()
        val availableMemoryMb = runCatching {
            val activityManager = context.getSystemService(android.content.Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            activityManager.getMemoryInfo(memoryInfo)
            memoryInfo.availMem / (1024L * 1024L)
        }.getOrDefault(Long.MAX_VALUE)
        val thermalStatus = recipe.thermalState.toIntOrNull() ?: -1

        val exposureRange = characteristics.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
            ?: return null
        val sensitivityRange = characteristics.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
            ?: return null
        val maxFrameDurationNs = characteristics.get(CameraCharacteristics.SENSOR_INFO_MAX_FRAME_DURATION)
            ?: return null
        val hdrExposureBounds = com.bncam.core.capture.ExposureBounds(
            minIso = sensitivityRange.lower,
            maxIso = sensitivityRange.upper,
            minExposureNs = exposureRange.lower,
            maxExposureNs = minOf(exposureRange.upper, maxFrameDurationNs)
        )
        val hdrPlan = com.bncam.core.capture.HdrEnhancedPlanner.plan(
            baseExposureTimeNs = baseExposureNs,
            baseSensitivityIso = baseIso,
            sensorMinFrameDurationNs = cadenceAuthorityNs,
            rawHeadroomEv = null,
            histogramClippingFraction = clippingSnapshot,
            sceneMotionScore = null,
            thermalState = thermalStatus,
            availableMemoryMb = availableMemoryMb,
            hardwareFrameCapacity = hardwareCapacity,
            plannerMaximumFrames = 15,
            userCaptureBudgetMs = 1200L,
            hdrEnhancedFrameSetting = recipe.hdrEnhancedFrameSetting,
            // The planning frame already contains the active profile's repeating exposure.
            // Pass only the effective Shot Bias ownership so HDR highlight protection respects which
            // variable is fixed; never re-apply Capture EV or Shot Bias scaling here.
            exposurePriorityMode = recipe.executionSettings.captureExposurePreferences.effectivePriorityMode(),
            exposureBounds = hdrExposureBounds
        )
        if (hdrPlan.status != com.bncam.core.capture.HdrEnhancedStatus.PLANNED || hdrPlan.mainFrameCount <= 0) {
            traceCaptureRuntime("HDR_ENHANCED_ABORT reason=planner_failed detail=${hdrPlan.reason}")
            return null
        }
        traceCaptureRuntime(
            "HDR_ENHANCED_EXPOSURE_POLICY mode=${hdrPlan.profileExposurePriorityMode.persistedValue} " +
                "priorityHonored=${hdrPlan.profileExposurePriorityHonored} " +
                "constraint=${hdrPlan.profileExposurePriorityConstraint} " +
                "baseExposureNs=$baseExposureNs baseIso=$baseIso " +
                "mainExposureNs=${hdrPlan.mainExposureTimeNs} mainIso=${hdrPlan.mainSensitivityIso} " +
                "highlightProtectionEv=${hdrPlan.highlightProtectionEvTarget}"
        )

        val requestedExposureNs = hdrPlan.mainExposureTimeNs.coerceIn(exposureRange.lower, exposureRange.upper)
        val requestedIso = hdrPlan.mainSensitivityIso.coerceIn(sensitivityRange.lower, sensitivityRange.upper)
        val requestedFrameDurationNs = maxOf(cadenceAuthorityNs, requestedExposureNs).coerceAtMost(maxFrameDurationNs)

        if (pipelineGeneration != generationAtStart || captureSession !== session || imageReader !== reader) {
            traceCaptureRuntime("HDR_ENHANCED_ABORT reason=pipeline_changed_before_burst")
            return null
        }

        val vendorRegistry = runCatching {
            VendorScanner.scanSensor(
                context = context,
                cameraManager = cameraManager,
                lensId = device.id,
                forceRefresh = false
            )
        }.getOrNull()

        val builders = ArrayList<CaptureRequest.Builder>(hdrPlan.mainFrameCount)
        repeat(hdrPlan.mainFrameCount) { index ->
            val builder = createPipelineCaptureRequestBuilder(device, CameraDevice.TEMPLATE_STILL_CAPTURE)
            builder.addTarget(reader.surface)
            builder.set(CaptureRequest.CONTROL_CAPTURE_INTENT, CaptureRequest.CONTROL_CAPTURE_INTENT_STILL_CAPTURE)
            builder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF)
            applyViewfinderCam2ApiSettings(
                requestBuilder = builder,
                characteristics = characteristics,
                cameraId = device.id,
                activeLensId = activeLens.id,
                opticalStabilization = recipe.executionSettings.opticalStabilization,
                hotPixelMode = recipe.executionSettings.hotPixelMode,
                noiseReductionHint = recipe.executionSettings.noiseReductionHint,
                edgeModeHint = recipe.executionSettings.edgeModeHint,
                tonemapHint = recipe.executionSettings.tonemapHint,
                antiBanding = recipe.executionSettings.antiBanding
            )
            applyMeteringPolicy(builder)
            applyLiveWhiteBalancePolicy(builder)
            if (characteristics.get(CameraCharacteristics.CONTROL_AWB_LOCK_AVAILABLE) == true) {
                val awbMode = runCatching { builder.get(CaptureRequest.CONTROL_AWB_MODE) }.getOrNull()
                if (awbMode != CaptureRequest.CONTROL_AWB_MODE_OFF) {
                    builder.set(CaptureRequest.CONTROL_AWB_LOCK, true)
                }
            }
            applyAuthoritativeFocusToStillBuilder(
                target = builder,
                source = previewBuilder,
                focusContext = focusCaptureContext,
                reason = "HDR_ENHANCED_MAIN_${index.toString().padStart(2, '0')}"
            )
            builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
            builder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, 0)
            builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, requestedExposureNs)
            builder.set(CaptureRequest.SENSOR_SENSITIVITY, requestedIso)
            builder.set(CaptureRequest.SENSOR_FRAME_DURATION, requestedFrameDurationNs)

            if (vendorRegistry != null) {
                runCatching {
                    VendorInjectionEngine.applyToBuilder(
                        lensId = device.id,
                        builder = builder,
                        stage = VendorRequestStage.STILL_CAPTURE,
                        settingsRepo = settingsRepo,
                        registry = vendorRegistry
                    )
                }.onFailure { failure ->
                    Log.w(tag, "HDR Enhanced vendor injection failed for main frame $index", failure)
                }
            }
            traceAfWriter(builder, "acquireHdrEnhancedBurst", "HDR_ENHANCED_MAIN_${index.toString().padStart(2, '0')}_AF_WRITES")
            builders += builder
        }

        // Results are consumed as they arrive and exact Image/ring pairs are leased immediately.
        // Waiting until the full burst completed allowed early RAW_SENSOR frames to be evicted by
        // later burst/repeating frames before ownership transfer.
        val resultChannel = Channel<TotalCaptureResult?>(capacity = builders.size)
        val sequenceAborted = java.util.concurrent.atomic.AtomicBoolean(false)
        val callback = object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureCompleted(
                captureSession: CameraCaptureSession,
                request: CaptureRequest,
                result: TotalCaptureResult
            ) {
                captureAttempts.captureResultReceived()
                val sensorTimestamp = result.get(CaptureResult.SENSOR_TIMESTAMP)
                if (sensorTimestamp != null && pipelineGeneration == generationAtStart) {
                    val tagResolution = controlRequestEpochTracker.resolveTag(
                        tag = request.tag,
                        expectedPipelineGeneration = generationAtStart
                    )
                    val sensorMetadataSnapshot = frameSensorMetadataSnapshot(
                        result = result,
                        expectedGeneration = generationAtStart,
                        fallbackLogicalCameraId = device.id
                    )
                    ringBuffer.addMetadata(
                        timestamp = sensorMetadataSnapshot?.sensorTimestampNs ?: sensorTimestamp,
                        result = result,
                        generationId = generationAtStart,
                        requestProvenance = tagResolution.provenance,
                        sensorMetadataSnapshot = sensorMetadataSnapshot
                    )
                }
                resultChannel.trySend(result)
            }

            override fun onCaptureFailed(
                captureSession: CameraCaptureSession,
                request: CaptureRequest,
                failure: android.hardware.camera2.CaptureFailure
            ) {
                traceCaptureRuntime(
                    "HDR_ENHANCED_FRAME_FAILED sequenceId=${failure.sequenceId} frameNumber=${failure.frameNumber} reason=${failure.reason}"
                )
                resultChannel.trySend(null)
            }

            override fun onCaptureSequenceAborted(captureSession: CameraCaptureSession, sequenceId: Int) {
                sequenceAborted.set(true)
                resultChannel.close()
            }
        }

        val burstSubmitBeforeNs = android.os.SystemClock.elapsedRealtimeNanos()
        val submittedBurst = try {
            submitBurstWithSharedProvenance(
                session = session,
                builders = builders,
                callback = callback,
                handler = backgroundHandler,
                pipelineGenerationAtSubmission = generationAtStart
            )
        } catch (failure: Throwable) {
            resultChannel.close()
            traceCaptureRuntime("HDR_ENHANCED_ABORT reason=burst_submission_failed error=${failure.javaClass.simpleName}:${failure.message}")
            return null
        }

        val timestampSource = when (characteristics.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE)) {
            CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME -> "SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME"
            CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_UNKNOWN -> "SENSOR_INFO_TIMESTAMP_SOURCE_UNKNOWN"
            null -> "UNAVAILABLE"
            else -> "UNRECOGNIZED"
        }
        val explicitlySelectedPhysicalId = recipe.physicalCameraId
        var observedBurstPhysicalId: String? = explicitlySelectedPhysicalId
        val physicalIdLock = Any()
        val acceptedFramesByFrameNumber = mutableListOf<Pair<Long, com.bncam.core.capture.HdrEnhancedFrame>>()
        val acceptedFramesLock = Any()
        val expectedBurstMs = (hdrPlan.expectedBurstCadenceNs * hdrPlan.mainFrameCount.toLong()) / 1_000_000L
        val resultTimeoutMs = maxOf(2_500L, expectedBurstMs + 2_000L).coerceAtMost(20_000L)
        var terminalEventsReceived = 0

        suspend fun acquireAndStoreExactFrame(result: TotalCaptureResult) {
            val sensorTimestamp = result.get(CaptureResult.SENSOR_TIMESTAMP)?.takeIf { it > 0L }
                ?: return
            val lease = ringBuffer.awaitAndLeaseExactRequestFrame(
                sensorTimestampNs = sensorTimestamp,
                generationId = generationAtStart,
                controlRequestEpoch = submittedBurst.prepared.tag.controlRequestEpoch,
                expectedFormat = activeFormat,
                maxWaitMs = 1_500L
            ) ?: run {
                traceCaptureRuntime(
                    "HDR_ENHANCED_FRAME_REJECT frameNumber=${result.frameNumber} reason=exact_image_pair_not_available " +
                        "sensorTimestampNs=$sensorTimestamp epoch=${submittedBurst.prepared.tag.controlRequestEpoch}"
                )
                return
            }
            var ownershipStored = false
            try {
                val pair = lease.pair
                val actualExposure = result.get(CaptureResult.SENSOR_EXPOSURE_TIME)?.takeIf { it > 0L }
                    ?: return
                val actualIso = result.get(CaptureResult.SENSOR_SENSITIVITY)?.takeIf { it > 0 }
                    ?: return
                val provenance = com.bncam.core.capture.SelectedFrameProvenanceValidator.verify(
                    framePipelineGeneration = pair.generationId,
                    frameControlRequestEpoch = pair.controlRequestEpoch,
                    frameTimestampNs = pair.timestamp,
                    metadataTimestampNs = result.get(CaptureResult.SENSOR_TIMESTAMP),
                    provenance = pair.requestProvenance
                )
                val activePhysicalId = result.get(CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID)
                val physicalCameraConsistent = synchronized(physicalIdLock) {
                    when {
                        explicitlySelectedPhysicalId != null ->
                            activePhysicalId == null || activePhysicalId == explicitlySelectedPhysicalId
                        activePhysicalId == null -> true
                        observedBurstPhysicalId == null -> {
                            observedBurstPhysicalId = activePhysicalId
                            true
                        }
                        else -> activePhysicalId == observedBurstPhysicalId
                    }
                }
                val expectedPhysicalId = synchronized(physicalIdLock) {
                    explicitlySelectedPhysicalId ?: observedBurstPhysicalId
                }
                val exposureRatio = actualExposure.toDouble() / requestedExposureNs.toDouble()
                val isoRatio = actualIso.toDouble() / requestedIso.toDouble()
                val exposureConsistent = exposureRatio in 0.95..1.05 && isoRatio in 0.95..1.05
                if (!provenance.exact || !physicalCameraConsistent || !exposureConsistent) {
                    traceCaptureRuntime(
                        "HDR_ENHANCED_FRAME_REJECT frameNumber=${result.frameNumber} provenance=${provenance.status} " +
                            "activePhysical=$activePhysicalId expectedPhysical=${expectedPhysicalId ?: "UNAVAILABLE"} " +
                            "exposureRatio=$exposureRatio isoRatio=$isoRatio"
                    )
                    return
                }

                val focusScore = if (pair.focusEvaluated && pair.focusScore >= 0f) pair.focusScore else null
                val focusConfidence = if (pair.focusEvaluated) pair.focusConfidence else null
                val frame = com.bncam.core.capture.HdrEnhancedFrame(
                    index = -1,
                    semanticTag = "HDR_ENHANCED_MAIN_PENDING",
                    lease = lease,
                    actualExposureTimeNs = actualExposure,
                    actualSensitivityIso = actualIso,
                    timestampNs = sensorTimestamp,
                    requestSubmittedElapsedRealtimeNs = submittedBurst.submittedElapsedRealtimeNs,
                    captureSequenceId = submittedBurst.sequenceId,
                    captureFrameNumber = result.frameNumber,
                    sharpnessScore = focusScore,
                    motionScore = null,
                    clippingFraction = null,
                    focusConfidence = focusConfidence,
                    provenanceValid = true,
                    isAuxiliary = false
                )
                synchronized(acceptedFramesLock) {
                    acceptedFramesByFrameNumber += result.frameNumber to frame
                }
                ownershipStored = true
                traceCaptureRuntime(
                    "HDR_ENHANCED_FRAME_LEASED sequenceId=${submittedBurst.sequenceId} frameNumber=${result.frameNumber} " +
                        "userShutterTimestampNs=$userShutterTimestampNs userShutterDomain=ELAPSED_REALTIME " +
                        "burstSubmitTimestampNs=${submittedBurst.submittedElapsedRealtimeNs} burstSubmitDomain=ELAPSED_REALTIME " +
                        "sensorTimestampNs=$sensorTimestamp sensorTimestampSource=$timestampSource imageTimestampNs=${pair.timestamp} " +
                        "pipelineGeneration=${pair.generationId} controlRequestEpoch=${pair.controlRequestEpoch} " +
                        "expNs=$actualExposure iso=$actualIso activePhysicalId=${activePhysicalId ?: "UNAVAILABLE"}"
                )
            } finally {
                if (!ownershipStored) lease.release()
            }
        }

        try {
            val allTerminalEventsReceived = withTimeoutOrNull(resultTimeoutMs) {
                kotlinx.coroutines.coroutineScope {
                    val leaseJobs = mutableListOf<Job>()
                    repeat(builders.size) {
                        val event = resultChannel.receiveCatching()
                        if (event.isClosed) return@coroutineScope false
                        terminalEventsReceived++
                        val result = event.getOrNull() ?: return@repeat
                        // Start the exact Image/result match immediately so early burst frames are
                        // pinned before later burst/repeating frames can evict them from the ring.
                        leaseJobs += launch(Dispatchers.IO) {
                            acquireAndStoreExactFrame(result)
                        }
                    }
                    leaseJobs.joinAll()
                    true
                }
            } ?: false

            if (!allTerminalEventsReceived || terminalEventsReceived != builders.size || sequenceAborted.get()) {
                traceCaptureRuntime(
                    "HDR_ENHANCED_ABORT reason=burst_incomplete sequenceId=${submittedBurst.sequenceId} " +
                        "terminalEvents=$terminalEventsReceived expected=${builders.size} aborted=${sequenceAborted.get()} timeoutMs=$resultTimeoutMs"
                )
                return null
            }

            val acceptedFrames = synchronized(acceptedFramesLock) {
                acceptedFramesByFrameNumber.toList()
            }.sortedBy { it.first }
                .mapIndexed { index, (_, pendingFrame) ->
                    pendingFrame.copy(
                        index = index,
                        semanticTag = "HDR_ENHANCED_MAIN_${index.toString().padStart(2, '0')}"
                    )
                }
            if (acceptedFrames.size < 4) {
                traceCaptureRuntime(
                    "HDR_ENHANCED_ABORT reason=insufficient_valid_deliberate_frames requested=${hdrPlan.mainFrameCount} accepted=${acceptedFrames.size} minimum=4"
                )
                return null
            }

            val baseSelection = com.bncam.core.capture.HdrEnhancedBaseSelector.selectBestBase(acceptedFrames)
            if (baseSelection.selectedBaseIndex < 0) return null
            val captureContext = com.bncam.core.capture.HdrEnhancedCaptureContext(
                mainFrames = acceptedFrames,
                auxFrames = emptyList(),
                plan = hdrPlan,
                userShutterTimestampNs = userShutterTimestampNs,
                burstSubmitElapsedRealtimeNs = submittedBurst.submittedElapsedRealtimeNs,
                sensorTimestampSource = timestampSource,
                selectedBaseIndex = baseSelection.selectedBaseIndex,
                baseSelectionReason = baseSelection.selectionReason,
                baseSelectionMeasuredEvidenceAvailable = baseSelection.measuredEvidenceAvailable
            )
            traceCaptureRuntime(
                "HDR_ENHANCED_BURST_ACQUIRED requested=${hdrPlan.mainFrameCount} accepted=${acceptedFrames.size} " +
                    "selectedBaseIndex=${baseSelection.selectedBaseIndex} measuredBaseEvidence=${baseSelection.measuredEvidenceAvailable} " +
                    "selectionReason=${baseSelection.selectionReason} " +
                    "submitLeadMs=${(submittedBurst.submittedElapsedRealtimeNs - userShutterTimestampNs) / 1_000_000.0} " +
                    "submissionCallNs=${submittedBurst.submittedElapsedRealtimeNs - burstSubmitBeforeNs}"
            )
            // Each copied HdrEnhancedFrame references the same lease as its pending counterpart.
            // Clearing prevents this acquisition scope from releasing leases now owned by context.
            synchronized(acceptedFramesLock) { acceptedFramesByFrameNumber.clear() }
            return captureContext
        } finally {
            resultChannel.close()
            synchronized(acceptedFramesLock) {
                acceptedFramesByFrameNumber.forEach { (_, frame) -> frame.close() }
                acceptedFramesByFrameNumber.clear()
            }
        }
    }

    private suspend fun acquireComputationalHdrBracket(
        settingsRepo: SettingsRepository,
        activeLens: LensInfo,
        recipe: com.bncam.core.capture.CaptureRecipe,
        activeFormat: Int,
        userShutterTimestampNs: Long,
        focusCaptureContext: FocusCaptureContext
    ): HdrBracketCaptureContext? {
        val session = captureSession ?: return null
        val device = cameraDevice ?: return null
        val reader = imageReader ?: return null
        val previewBuilder = currentCaptureRequest ?: return null
        val generationAtStart = pipelineGeneration
        val characteristics = runCatching {
            cameraManager.getCameraCharacteristics(device.id)
        }.getOrNull() ?: return null

        val baseSnapshot = ringBuffer.queryCandidateSnapshots(
            userShutterTimestampNs = userShutterTimestampNs,
            maxCount = 1,
            shutterTimestampDomain = "ELAPSED_REALTIME"
        ).lastOrNull() ?: ringBuffer.queryCandidateSnapshots(
            userShutterTimestampNs = 0L,
            maxCount = 1,
            shutterTimestampDomain = "ELAPSED_REALTIME"
        ).lastOrNull() ?: run {
            traceCaptureRuntime("HDR_BRACKET_ABORT reason=no_exact_baseline_frame")
            return null
        }
        val baseMetadata = baseSnapshot.metadata ?: return null
        val baseExposureNs = baseMetadata.get(CaptureResult.SENSOR_EXPOSURE_TIME)?.takeIf { it > 0L }
            ?: return null
        val baseIso = baseMetadata.get(CaptureResult.SENSOR_SENSITIVITY)?.takeIf { it > 0 }
            ?: return null
        val baseFrameDurationNs = baseMetadata.get(CaptureResult.SENSOR_FRAME_DURATION)?.takeIf { it > 0L }
            ?: baseExposureNs

        val capabilities = characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf()
        val manualBounds = if (
            CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR in capabilities
        ) {
            val exposureRange = characteristics.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
            val sensitivityRange = characteristics.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
            val maxFrameDuration = characteristics.get(CameraCharacteristics.SENSOR_INFO_MAX_FRAME_DURATION)
            if (exposureRange != null && sensitivityRange != null && maxFrameDuration != null) {
                HdrManualSensorBounds(
                    exposureTimeMinNs = exposureRange.lower,
                    exposureTimeMaxNs = exposureRange.upper,
                    sensitivityIsoMin = sensitivityRange.lower,
                    sensitivityIsoMax = sensitivityRange.upper,
                    maxFrameDurationNs = maxFrameDuration
                )
            } else null
        } else null
        val aeRange = characteristics.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)
        val aeStep = characteristics.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)?.toFloat()
        val aeBounds = if (aeRange != null && aeStep != null && aeStep.isFinite() && aeStep > 0f) {
            HdrAeCompensationBounds(aeRange.lower, aeRange.upper, aeStep)
        } else null

        val scanningAf = baseSnapshot.afState == CaptureResult.CONTROL_AF_STATE_ACTIVE_SCAN ||
            baseSnapshot.afState == CaptureResult.CONTROL_AF_STATE_PASSIVE_SCAN
        val motionHigh = baseSnapshot.lensState == CaptureResult.LENS_STATE_MOVING ||
            scanningAf || baseExposureNs >= 33_000_000L
        val bracketPlan = HdrExposureBracketPlanner.plan(
            baseExposureTimeNs = baseExposureNs,
            baseSensitivityIso = baseIso,
            manualBounds = manualBounds,
            aeBounds = aeBounds,
            motionHigh = motionHigh
        )
        traceCaptureRuntime(
            "HDR_BRACKET_PLAN enabled=${bracketPlan.enabled} mode=${bracketPlan.controlMode} " +
                "motionLimited=${bracketPlan.motionLimited} reason=${bracketPlan.fallbackReason} " +
                "baseExposureNs=$baseExposureNs baseIso=$baseIso"
        )
        if (!bracketPlan.enabled || bracketPlan.controlMode == null) return null

        data class PendingHdrFrame(
            val plan: com.bncam.core.capture.HdrBracketFramePlan,
            val lease: com.bncam.core.buffer.FrameLease,
            val exposureTimeNs: Long,
            val sensitivityIso: Int
        )

        val pending = mutableListOf<PendingHdrFrame>()
        var ownershipTransferred = false
        val vendorRegistry = runCatching {
            VendorScanner.scanSensor(
                context = context,
                cameraManager = cameraManager,
                lensId = device.id,
                forceRefresh = false
            )
        }.getOrNull()

        try {
            for (framePlan in bracketPlan.frames) {
                if (pipelineGeneration != generationAtStart || captureSession !== session || imageReader !== reader) {
                    traceCaptureRuntime("HDR_BRACKET_ABORT pipeline_changed")
                    return null
                }
                val builder = createPipelineCaptureRequestBuilder(device, CameraDevice.TEMPLATE_STILL_CAPTURE)
                builder.addTarget(reader.surface)
                builder.set(CaptureRequest.CONTROL_CAPTURE_INTENT, CaptureRequest.CONTROL_CAPTURE_INTENT_STILL_CAPTURE)
                builder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF)

                applyViewfinderCam2ApiSettings(
                    requestBuilder = builder,
                    characteristics = characteristics,
                    cameraId = device.id,
                    activeLensId = activeLens.id,
                    opticalStabilization = recipe.executionSettings.opticalStabilization,
                    hotPixelMode = recipe.executionSettings.hotPixelMode,
                    noiseReductionHint = recipe.executionSettings.noiseReductionHint,
                    edgeModeHint = recipe.executionSettings.edgeModeHint,
                    tonemapHint = recipe.executionSettings.tonemapHint,
                    antiBanding = recipe.executionSettings.antiBanding
                )
                applyMeteringPolicy(builder)
                applyLiveWhiteBalancePolicy(builder)
                currentCaptureRequest?.let { previewFocusSource ->
                    applyAuthoritativeFocusToStillBuilder(
                        target = builder,
                        source = previewFocusSource,
                        focusContext = focusCaptureContext,
                        reason = "HDR_BRACKET_${framePlan.role.name}"
                    )
                }

                when (bracketPlan.controlMode) {
                    HdrExposureControlMode.MANUAL_SENSOR -> {
                        val exposureNs = framePlan.exposureTimeNs ?: return null
                        val iso = framePlan.sensitivityIso ?: return null
                        val maxFrameDuration = manualBounds?.maxFrameDurationNs ?: exposureNs
                        builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                        builder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, 0)
                        builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, exposureNs)
                        builder.set(CaptureRequest.SENSOR_SENSITIVITY, iso)
                        builder.set(
                            CaptureRequest.SENSOR_FRAME_DURATION,
                            maxOf(baseFrameDurationNs, exposureNs).coerceAtMost(maxFrameDuration)
                        )
                    }
                    HdrExposureControlMode.AE_COMPENSATION -> {
                        builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                        builder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, framePlan.aeCompensationIndex ?: 0)
                        builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, null)
                        builder.set(CaptureRequest.SENSOR_SENSITIVITY, null)
                        builder.set(CaptureRequest.SENSOR_FRAME_DURATION, null)
                    }
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && activeFormat == ImageFormat.YUV_420_888) {
                    val physicalYuvDecision = resolvePhysicalYuvFullFovDecision()
                    if (physicalYuvDecision.enabled) {
                        val previewEffectiveZoom = previewBuilder.get(CaptureRequest.CONTROL_ZOOM_RATIO)
                            ?: physicalYuvDecision.nativeZoomRatio
                        val relativeZoom = (previewEffectiveZoom / physicalYuvDecision.nativeZoomRatio)
                            .takeIf { it.isFinite() }
                            ?.coerceAtLeast(1f)
                            ?: 1f
                        applyPhysicalYuvFullFovZoom(
                            builder = builder,
                            relativeDigitalZoom = relativeZoom,
                            reason = "HDR_BRACKET_${framePlan.role.name}"
                        )
                        applyMeteringPolicy(builder)
                    }
                }

                if (vendorRegistry != null) {
                    runCatching {
                        VendorInjectionEngine.applyToBuilder(
                            lensId = device.id,
                            builder = builder,
                            stage = VendorRequestStage.STILL_CAPTURE,
                            settingsRepo = settingsRepo,
                            registry = vendorRegistry
                        )
                    }.onFailure { failure ->
                        Log.w(tag, "HDR vendor still-capture injection failed for ${framePlan.role}", failure)
                    }
                }

                val resultDeferred = CompletableDeferred<TotalCaptureResult?>()
                val callback = object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureCompleted(
                        captureSession: CameraCaptureSession,
                        request: CaptureRequest,
                        result: TotalCaptureResult
                    ) {
                        captureAttempts.captureResultReceived()
                        val sensorTimestamp = result.get(CaptureResult.SENSOR_TIMESTAMP)
                        if (sensorTimestamp != null && pipelineGeneration == generationAtStart) {
                            val tagResolution = controlRequestEpochTracker.resolveTag(
                                tag = request.tag,
                                expectedPipelineGeneration = generationAtStart
                            )
                            val sensorMetadataSnapshot = frameSensorMetadataSnapshot(
                                result = result,
                                expectedGeneration = generationAtStart,
                                fallbackLogicalCameraId = device.id
                            )
                            ringBuffer.addMetadata(
                                timestamp = sensorMetadataSnapshot?.sensorTimestampNs ?: sensorTimestamp,
                                result = result,
                                generationId = generationAtStart,
                                requestProvenance = tagResolution.provenance,
                                sensorMetadataSnapshot = sensorMetadataSnapshot
                            )
                        }
                        if (!resultDeferred.isCompleted) resultDeferred.complete(result)
                    }

                    override fun onCaptureFailed(
                        captureSession: CameraCaptureSession,
                        request: CaptureRequest,
                        failure: android.hardware.camera2.CaptureFailure
                    ) {
                        if (!resultDeferred.isCompleted) resultDeferred.complete(null)
                    }
                }
                traceAfWriter(builder, "acquireComputationalHdrBracket", "HDR_${framePlan.role.name}_AF_WRITES")
                val submission = submitOneShotRequestWithProvenance(
                    session = session,
                    builder = builder,
                    callback = callback,
                    handler = backgroundHandler,
                    reason = "HDR_BRACKET_${framePlan.role.name}",
                    pipelineGenerationAtSubmission = generationAtStart
                )
                val plannedExposureMs = (framePlan.exposureTimeNs ?: baseExposureNs) / 1_000_000L
                val resultTimeoutMs = maxOf(2_500L, plannedExposureMs + 2_000L).coerceAtMost(15_000L)
                val result = withTimeoutOrNull(resultTimeoutMs) { resultDeferred.await() } ?: run {
                    traceCaptureRuntime("HDR_BRACKET_ABORT role=${framePlan.role} reason=result_timeout_or_failure")
                    return null
                }
                val sensorTimestamp = result.get(CaptureResult.SENSOR_TIMESTAMP)?.takeIf { it > 0L } ?: run {
                    traceCaptureRuntime("HDR_BRACKET_ABORT role=${framePlan.role} reason=missing_sensor_timestamp")
                    return null
                }
                val lease = ringBuffer.awaitAndLeaseExactRequestFrame(
                    sensorTimestampNs = sensorTimestamp,
                    generationId = generationAtStart,
                    controlRequestEpoch = submission.prepared.tag.controlRequestEpoch,
                    expectedFormat = activeFormat,
                    maxWaitMs = 1_200L
                ) ?: run {
                    traceCaptureRuntime("HDR_BRACKET_ABORT role=${framePlan.role} reason=exact_frame_pair_timeout")
                    return null
                }
                val actualExposure = result.get(CaptureResult.SENSOR_EXPOSURE_TIME)?.takeIf { it > 0L }
                val actualIso = result.get(CaptureResult.SENSOR_SENSITIVITY)?.takeIf { it > 0 }
                if (actualExposure == null || actualIso == null) {
                    lease.release()
                    traceCaptureRuntime("HDR_BRACKET_ABORT role=${framePlan.role} reason=missing_actual_exposure")
                    return null
                }
                pending += PendingHdrFrame(framePlan, lease, actualExposure, actualIso)
            }

            val anchorPending = pending.singleOrNull { it.plan.role == com.bncam.core.capture.HdrBracketRole.ANCHOR }
                ?: return null
            val anchorProduct = anchorPending.exposureTimeNs.toDouble() * anchorPending.sensitivityIso.toDouble()
            if (!anchorProduct.isFinite() || anchorProduct <= 0.0) return null
            val captured = pending.map { item ->
                val scale = (item.exposureTimeNs.toDouble() * item.sensitivityIso.toDouble() / anchorProduct).toFloat()
                HdrCapturedFrame(
                    role = item.plan.role,
                    targetEvFromAnchor = item.plan.targetEvFromAnchor,
                    lease = item.lease,
                    actualExposureTimeNs = item.exposureTimeNs,
                    actualSensitivityIso = item.sensitivityIso,
                    actualExposureScaleToAnchor = scale
                )
            }
            val highlightCaptured = captured.single { it.role == com.bncam.core.capture.HdrBracketRole.HIGHLIGHT }
            val shadowCaptured = captured.single { it.role == com.bncam.core.capture.HdrBracketRole.SHADOW }
            val actualValidation = HdrExposureBracketPlanner.validateActualBracket(
                anchorExposureTimeNs = anchorPending.exposureTimeNs,
                highlightExposureTimeNs = highlightCaptured.actualExposureTimeNs,
                shadowExposureTimeNs = shadowCaptured.actualExposureTimeNs,
                highlightExposureScaleToAnchor = highlightCaptured.actualExposureScaleToAnchor,
                shadowExposureScaleToAnchor = shadowCaptured.actualExposureScaleToAnchor
            )
            if (!actualValidation.valid) {
                traceCaptureRuntime(
                    "HDR_BRACKET_ABORT reason=${actualValidation.reason} " +
                        "highlightScale=${highlightCaptured.actualExposureScaleToAnchor} " +
                        "shadowScale=${shadowCaptured.actualExposureScaleToAnchor} " +
                        "highlightIntegrationRatio=${actualValidation.highlightIntegrationRatio} " +
                        "shadowIntegrationRatio=${actualValidation.shadowIntegrationRatio}"
                )
                return null
            }
            val context = HdrBracketCaptureContext(
                frames = captured,
                controlMode = bracketPlan.controlMode,
                motionLimited = bracketPlan.motionLimited
            )
            ownershipTransferred = true
            traceCaptureRuntime(
                "HDR_BRACKET_ACQUIRED mode=${bracketPlan.controlMode} scales=" +
                    context.exposureScalesToAnchor.joinToString(prefix = "[", postfix = "]")
            )
            return context
        } finally {
            if (!ownershipTransferred) pending.forEach { it.lease.release() }
            runCatching {
                traceAfWriter(previewBuilder, "acquireComputationalHdrBracket", "HDR_PREVIEW_RESTORE_WRITES")
                submitRepeatingRequestWithProvenance(
                    session = session,
                    builder = previewBuilder,
                    callback = captureCallback,
                    handler = backgroundHandler,
                    reason = "HDR_PREVIEW_RESTORE",
                    pipelineGenerationAtSubmission = generationAtStart
                )
            }.onFailure { failure ->
                Log.e(tag, "Failed to restore repeating request after HDR bracket", failure)
            }
        }
    }

    private suspend fun executeDedicatedFlashCapture(
        activeProfile: CameraProfile,
        activeLens: LensInfo,
        settingsRepo: SettingsRepository,
        recipe: com.bncam.core.capture.CaptureRecipe,
        livePreferredFrameSource: String,
        focusCaptureContextAtShutter: FocusCaptureContext,
        flashControlPlan: FlashCameraControlPlan?
    ): DedicatedFlashCaptureResult? {
        val shutterTimestampNs: Long
        var shutterTimestampDomain = "UNSET"
        var currentSubmittedControlRequestEpochAtShutter = 0L
        var vendorDebugCaptureResult: TotalCaptureResult? = null
        var captureFailureReason: String? = null
        // =======================================================
        // ROBUST ACTIVE FLASH SEQUENCE (Bypass ZSL)
        // =======================================================
        Log.i(tag, "Executing robust flash sequence (Mode: $currentFlashMode)")
        val session = captureSession ?: return null
        val device = cameraDevice ?: return null
        val reader = imageReader ?: return null
        val previewBuilder = currentCaptureRequest ?: return null
        val flashPipelineGeneration = pipelineGeneration

        if (recipe.executionSettings.cameraSoundEnabled && !managerShutdownRequested.get()) {
            mediaActionSound.play(MediaActionSound.SHUTTER_CLICK)
        }

        val activeFlashControlPlan = flashControlPlan
            ?: throw IllegalStateException(
                "Dedicated flash still was selected without a supported Camera2 flash control plan."
            )
        val previewAeLockBeforeFlash =
            previewBuilder.get(CaptureRequest.CONTROL_AE_LOCK) == true

        // =======================================================
        // 1. PREPARE FLASH METERING — retire tap-assist torch and unlock AE temporarily.
        // =======================================================
        // Tap-to-focus may have enabled TORCH as a focus assist. That is not the final
        // photographic flash. Every shutter-time flash transaction starts from FLASH_OFF,
        // restores the selected metering region, and lets Camera2 run a real precapture.
        previewBuilder.set(CaptureRequest.CONTROL_AE_LOCK, false)
        applyMeteringPolicy(previewBuilder)
        applyExposurePolicy(previewBuilder)
        previewBuilder.set(CaptureRequest.CONTROL_AE_MODE, activeFlashControlPlan.aeMode)
        previewBuilder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF)
        previewBuilder.set(
            CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER,
            CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_IDLE
        )
        previewBuilder.set(
            CaptureRequest.CONTROL_AF_TRIGGER,
            CaptureRequest.CONTROL_AF_TRIGGER_IDLE
        )
        val flashPrepSubmission = submitRepeatingRequestWithProvenance(
            session = session,
            builder = previewBuilder,
            callback = captureCallback,
            handler = backgroundHandler,
            reason = if (isTorchActive) "FLASH_ASSIST_TORCH_RELEASE" else "FLASH_PRECAPTURE_PREP"
        )
        val flashPrepObserved = waitForExactControlRequestObservation(
            controlRequestEpoch = flashPrepSubmission.prepared.tag.controlRequestEpoch,
            expectedGeneration = pipelineGeneration,
            timeoutMs = 500L
        ) != null
        isTorchActive = false
        traceCaptureRuntime(
            "FLASH_PREP observed=$flashPrepObserved control=${activeFlashControlPlan.reason} " +
                "aeLockTemporarilyReleased=$previewAeLockBeforeFlash"
        )

        // =======================================================
        // 2. AF SETTLE — never replace an explicit focus owner at shutter time.
        // =======================================================
        val explicitFocusAtShutter = focusCaptureContextAtShutter.owner != FocusOwner.AUTO
        if (!explicitFocusAtShutter) {
            previewBuilder.set(
                CaptureRequest.CONTROL_AF_TRIGGER,
                CaptureRequest.CONTROL_AF_TRIGGER_START
            )
            traceAfWriter(previewBuilder, "executeCapture", "FLASH_AF_TRIGGER_WRITES")
            val flashAfSubmission = submitOneShotRequestWithProvenance(
                session = session,
                builder = previewBuilder,
                callback = captureCallback,
                handler = backgroundHandler,
                reason = "FLASH_AF_TRIGGER"
            )
            previewBuilder.set(
                CaptureRequest.CONTROL_AF_TRIGGER,
                CaptureRequest.CONTROL_AF_TRIGGER_IDLE
            )
            val afSettled = waitForAfSettleAfterTrigger(
                triggerControlRequestEpoch = flashAfSubmission.prepared.tag.controlRequestEpoch,
                expectedGeneration = pipelineGeneration
            )
            traceCaptureRuntime("FLASH_AF_SETTLE completed=$afSettled state=${lastAfState ?: "unreported"}")
        } else {
            Log.i(tag, "FLASH_AF_PRESERVE owner=${focusCaptureContextAtShutter.owner}")
        }

        // =======================================================
        // 3. AE PRECAPTURE — state-driven, never a fixed arbitrary sleep.
        // =======================================================
        previewBuilder.set(CaptureRequest.CONTROL_AE_MODE, activeFlashControlPlan.aeMode)
        previewBuilder.set(CaptureRequest.FLASH_MODE, activeFlashControlPlan.precaptureFlashMode)
        previewBuilder.set(
            CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER,
            CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_START
        )
        traceAfWriter(previewBuilder, "executeCapture", "FLASH_AE_PRECAPTURE_WRITES")
        val flashPrecaptureSubmission = submitOneShotRequestWithProvenance(
            session = session,
            builder = previewBuilder,
            callback = captureCallback,
            handler = backgroundHandler,
            reason = "FLASH_AE_PRECAPTURE_TRIGGER"
        )
        previewBuilder.set(
            CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER,
            CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_IDLE
        )
        previewBuilder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF)

        val precaptureWait = waitForFlashPrecaptureCompletion(
            triggerControlRequestEpoch =
                flashPrecaptureSubmission.prepared.tag.controlRequestEpoch,
            expectedGeneration = pipelineGeneration
        )
        traceCaptureRuntime(
            "FLASH_PRECAPTURE completed=${precaptureWait.completed} " +
                "triggerObserved=${precaptureWait.triggerObserved} " +
                "aeState=${precaptureWait.finalAeState ?: "unreported"} " +
                "reason=${precaptureWait.reason} elapsedMs=${precaptureWait.elapsedMs}"
        )
        if (!precaptureWait.completed) {
            Log.w(
                tag,
                "Flash precapture did not report a terminal AE state before timeout; " +
                    "continuing with the dedicated still request. reason=${precaptureWait.reason}"
            )
        }

        // =======================================================
        // 3. REAL STILL CAPTURE (Schone request, geen preview leakage!)
        // =======================================================
        val stillBuilder =
            createPipelineCaptureRequestBuilder(device, CameraDevice.TEMPLATE_STILL_CAPTURE)
        stillBuilder.addTarget(reader.surface) // ENKEL de ImageReader, NIET de preview surface

        stillBuilder.set(CaptureRequest.CONTROL_AE_MODE, activeFlashControlPlan.aeMode)
        stillBuilder.set(CaptureRequest.FLASH_MODE, activeFlashControlPlan.stillFlashMode)
        stillBuilder.set(CaptureRequest.CONTROL_AE_LOCK, false)

        stillBuilder.set(
            CaptureRequest.CONTROL_CAPTURE_INTENT,
            CaptureRequest.CONTROL_CAPTURE_INTENT_STILL_CAPTURE
        )

        try {
            val chars = cameraManager.getCameraCharacteristics(device.id)
            applyViewfinderCam2ApiSettings(
                requestBuilder = stillBuilder,
                characteristics = chars,
                cameraId = device.id,
                activeLensId = activeLens.id,
                opticalStabilization = recipe.executionSettings.opticalStabilization,
                hotPixelMode = recipe.executionSettings.hotPixelMode,
                noiseReductionHint = recipe.executionSettings.noiseReductionHint,
                edgeModeHint = recipe.executionSettings.edgeModeHint,
                tonemapHint = recipe.executionSettings.tonemapHint,
                antiBanding = recipe.executionSettings.antiBanding
            )
            applyMeteringPolicy(stillBuilder)
            applyExposurePolicy(stillBuilder)
            // Generic exposure policy clears FLASH_MODE for normal preview/still control.
            // Reassert the capability-resolved dedicated-flash contract after it has removed
            // any stale manual SENSOR_* keys.
            stillBuilder.set(CaptureRequest.CONTROL_AE_MODE, activeFlashControlPlan.aeMode)
            stillBuilder.set(CaptureRequest.FLASH_MODE, activeFlashControlPlan.stillFlashMode)
            stillBuilder.set(CaptureRequest.CONTROL_AE_LOCK, false)
            applyAuthoritativeFocusToStillBuilder(
                target = stillBuilder,
                source = previewBuilder,
                focusContext = focusCaptureContextAtShutter,
                reason = "FLASH_STILL_CAPTURE"
            )
        } catch (e: Exception) {
            throw IllegalStateException("Still-capture Camera2 request contract could not be applied.", e)
        }

        try {
            val vendorRegistry = VendorScanner.scanSensor(
                context = context,
                cameraManager = cameraManager,
                lensId = device.id,
                forceRefresh = false
            )

            val stillAttempts = VendorInjectionEngine.applyToBuilder(
                lensId = device.id,
                builder = stillBuilder,
                stage = VendorRequestStage.STILL_CAPTURE,
                settingsRepo = settingsRepo,
                registry = vendorRegistry
            )

            Log.i(
                tag,
                "Vendor still-capture injection lens=${device.id} " +
                        "applied=${stillAttempts.count { it.appliedToBuilder }}"
            )
        } catch (e: Exception) {
            Log.e(tag, "Vendor still-capture injection failed for lens=${device.id}", e)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val physicalYuvDecision = resolvePhysicalYuvFullFovDecision()
            if (physicalYuvDecision.enabled) {
                val previewEffectiveZoom =
                    previewBuilder.get(CaptureRequest.CONTROL_ZOOM_RATIO)
                        ?: physicalYuvDecision.nativeZoomRatio
                val relativeZoom =
                    (previewEffectiveZoom / physicalYuvDecision.nativeZoomRatio)
                        .takeIf { it.isFinite() }
                        ?.coerceAtLeast(1f)
                        ?: 1f
                applyPhysicalYuvFullFovZoom(
                    builder = stillBuilder,
                    relativeDigitalZoom = relativeZoom,
                    reason = "FLASH_STILL_CAPTURE"
                )
                // Rebuild AE and preserve AF in the same post-zoom coordinate space.
                applyMeteringPolicy(stillBuilder)
                stillBuilder.set(CaptureRequest.CONTROL_AE_MODE, activeFlashControlPlan.aeMode)
                stillBuilder.set(CaptureRequest.FLASH_MODE, activeFlashControlPlan.stillFlashMode)
                applyAuthoritativeFocusToStillBuilder(
                    target = stillBuilder,
                    source = previewBuilder,
                    focusContext = focusCaptureContextAtShutter,
                    reason = "FLASH_STILL_POST_ZOOM"
                )
            }
        }

        val deferredTimestamp = kotlinx.coroutines.CompletableDeferred<Long>()
        val callback = object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureCompleted(
                session: CameraCaptureSession,
                request: CaptureRequest,
                result: TotalCaptureResult
            ) {
                captureAttempts.captureResultReceived()
                vendorDebugCaptureResult = result
                val traceIdentity = synchronized(pipelineLock) { activePipelineIdentity }
                com.bncam.core.debug.AfGroundTruthTrace.recordCaptureResult(
                    logicalCameraId = traceIdentity?.logicalCameraId ?: device.id,
                    activePhysicalCameraId = traceIdentity?.physicalCameraId,
                    request = request,
                    result = result
                )

                val decision = activeOisDecision
                val lensRoleStr = synchronized(pipelineLock) { activePipelineIdentity?.lensRole } ?: "Unknown"
                val requested = decision?.appliedMethod?.name ?: "OFF"

                val logicalOisReported = result.get(CaptureResult.LENS_OPTICAL_STABILIZATION_MODE)
                val logicalVideoStabReported = result.get(CaptureResult.CONTROL_VIDEO_STABILIZATION_MODE)
                val activePhysicalId = decision?.physicalCameraId

                var physicalOisReported: Int? = null
                if (activePhysicalId != null) {
                    val physicalResult = physicalCaptureResultOrNull(result, activePhysicalId)
                    physicalOisReported = physicalResult?.get(CaptureResult.LENS_OPTICAL_STABILIZATION_MODE)
                }

                val resultText = when (decision?.appliedMethod) {
                    OisDecision.OisMethod.PHYSICAL_OIS, OisDecision.OisMethod.LOGICAL_OIS -> {
                        if (
                            logicalOisReported == CaptureResult.LENS_OPTICAL_STABILIZATION_MODE_ON ||
                            physicalOisReported == CaptureResult.LENS_OPTICAL_STABILIZATION_MODE_ON
                        ) "ON" else "OFF"
                    }
                    OisDecision.OisMethod.VENDOR -> {
                        if (logicalOisReported == CaptureResult.LENS_OPTICAL_STABILIZATION_MODE_ON) {
                            "ON"
                        } else {
                            "VENDOR_REQUESTED"
                        }
                    }
                    OisDecision.OisMethod.PREVIEW_STAB -> {
                        if (logicalVideoStabReported == CaptureResult.CONTROL_VIDEO_STABILIZATION_MODE_PREVIEW_STABILIZATION) "PREVIEW_STAB" else "OFF"
                    }
                    OisDecision.OisMethod.VIDEO_STAB -> {
                        if (logicalVideoStabReported == CaptureResult.CONTROL_VIDEO_STABILIZATION_MODE_ON) "VIDEO_STAB" else "OFF"
                    }
                    else -> "OFF"
                }

                val physResultText = if (physicalOisReported != null) {
                    if (physicalOisReported == CaptureResult.LENS_OPTICAL_STABILIZATION_MODE_ON) "ON" else "OFF"
                } else {
                    "null"
                }

                Log.i("OisResolver", "OIS_RESULT lens=$lensRoleStr requested=$requested result=$resultText physicalResult=$physResultText")

                val sensorTimestamp = result.get(CaptureResult.SENSOR_TIMESTAMP)
                val ts = sensorTimestamp
                    ?: android.os.SystemClock.elapsedRealtimeNanos()
                shutterTimestampDomain =
                    if (sensorTimestamp != null) "SENSOR_TIMESTAMP"
                    else "ELAPSED_REALTIME_FALLBACK_NO_SENSOR_TIMESTAMP"
                deferredTimestamp.complete(ts)
            }

            override fun onCaptureFailed(
                session: CameraCaptureSession,
                request: CaptureRequest,
                failure: android.hardware.camera2.CaptureFailure
            ) {
                captureFailureReason = "camera2_capture_failed:${failure.reason}"
                shutterTimestampDomain =
                    "ELAPSED_REALTIME_FALLBACK_CAPTURE_FAILED"
                deferredTimestamp.complete(android.os.SystemClock.elapsedRealtimeNanos())
            }
        }

        // Vuur de echte hardware flits af
        traceAfWriter(stillBuilder, "executeCapture", "FLASH_STILL_AF_WRITES")
        val flashStillSubmission = submitOneShotRequestWithProvenance(
            session = session,
            builder = stillBuilder,
            callback = callback,
            handler = backgroundHandler,
            reason = "FLASH_STILL_CAPTURE"
        )
        currentSubmittedControlRequestEpochAtShutter =
            flashStillSubmission.prepared.tag.controlRequestEpoch

        shutterTimestampNs = try {
            kotlinx.coroutines.withTimeout(2500) { deferredTimestamp.await() }
        } catch (e: Exception) {
            Log.e(
                tag,
                "Still-capture metadata timestamp timeout profile=${activeProfile.id} " +
                        "requested=$livePreferredFrameSource actual=${formatName(activeZslFormat)} " +
                        "generation=$pipelineGeneration bufferSize=${ringBuffer.completeFrameCount()}",
                e
            )
            shutterTimestampDomain =
                "ELAPSED_REALTIME_FALLBACK_METADATA_TIMEOUT"
            android.os.SystemClock.elapsedRealtimeNanos()
        }

        // Do not sleep and hope that the illuminated frame reached the ring. Prove that
        // the exact one-shot still request has become a complete Image+metadata pair.
        val exactFlashFrameLease = if (shutterTimestampDomain == "SENSOR_TIMESTAMP") {
            ringBuffer.awaitAndLeaseExactRequestFrame(
                sensorTimestampNs = shutterTimestampNs,
                generationId = flashPipelineGeneration,
                controlRequestEpoch = currentSubmittedControlRequestEpochAtShutter,
                expectedFormat = activeZslFormat,
                maxWaitMs = 1600L
            )
        } else {
            ringBuffer.awaitAndLeaseExactRequestEpochFrame(
                generationId = flashPipelineGeneration,
                controlRequestEpoch = currentSubmittedControlRequestEpochAtShutter,
                expectedFormat = activeZslFormat,
                maxWaitMs = 1600L
            )
        }
        if (exactFlashFrameLease != null) {
            try {
                val flashMetadata = exactFlashFrameLease.pair.metadata
                if (flashMetadata != null) vendorDebugCaptureResult = flashMetadata
                val firedState = flashMetadata?.get(CaptureResult.FLASH_STATE)
                val flashFired = firedState == CaptureResult.FLASH_STATE_FIRED ||
                    firedState == CaptureResult.FLASH_STATE_PARTIAL
                traceCaptureRuntime(
                    "FLASH_EXACT_FRAME epoch=$currentSubmittedControlRequestEpochAtShutter " +
                        "sensorTs=${exactFlashFrameLease.pair.timestamp} " +
                        "flashState=${firedState ?: "unreported"} fired=$flashFired " +
                        "aeState=${flashMetadata?.get(CaptureResult.CONTROL_AE_STATE) ?: "unreported"}"
                )
                if (currentFlashMode == "On" && firedState != null && !flashFired) {
                    Log.w(tag, "Flash mode On requested, but exact still result reports FLASH_STATE=$firedState")
                }
            } finally {
                exactFlashFrameLease.release()
            }
        }

        // Restore preview control without restoring tap-assist TORCH. If the user had an
        // explicit AE/AF lock, reapply its AE lock bit after flash metering temporarily
        // released it. Focus mode/regions themselves were never replaced.
        try {
            previewBuilder.set(
                CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER,
                CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_IDLE
            )
            previewBuilder.set(
                CaptureRequest.CONTROL_AF_TRIGGER,
                CaptureRequest.CONTROL_AF_TRIGGER_IDLE
            )
            previewBuilder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF)
            applyMeteringPolicy(previewBuilder)
            applyExposurePolicy(previewBuilder)
            previewBuilder.set(CaptureRequest.CONTROL_AE_MODE, activeFlashControlPlan.aeMode)
            previewBuilder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF)
            previewBuilder.set(CaptureRequest.CONTROL_AE_LOCK, previewAeLockBeforeFlash)
            traceAfWriter(previewBuilder, "executeCapture", "FLASH_PREVIEW_RESTORE_WRITES")
            submitRepeatingRequestWithProvenance(
                session = session,
                builder = previewBuilder,
                callback = captureCallback,
                handler = backgroundHandler,
                reason = "FLASH_PREVIEW_RESTORE"
            )
        } catch (e: Exception) {
            Log.e(
                tag,
                "Failed to restore repeating request after still capture profile=${activeProfile.id} " +
                        "requested=$livePreferredFrameSource actual=${formatName(activeZslFormat)} " +
                        "generation=$pipelineGeneration bufferSize=${ringBuffer.completeFrameCount()}",
                e
            )
        }

        if (exactFlashFrameLease == null) {
            throw IllegalStateException(
                "Dedicated flash still completed but its exact Image+metadata pair did not arrive " +
                    "in the active ring buffer. epoch=$currentSubmittedControlRequestEpochAtShutter " +
                    "format=${formatName(activeZslFormat)} generation=$flashPipelineGeneration"
            )
        }
        return DedicatedFlashCaptureResult(
            shutterTimestampNs = shutterTimestampNs,
            shutterTimestampDomain = shutterTimestampDomain,
            controlRequestEpochAtShutter = currentSubmittedControlRequestEpochAtShutter,
            vendorDebugCaptureResult = vendorDebugCaptureResult,
            captureFailureReason = captureFailureReason
        )
    }

    suspend fun executeCapture(
        activeProfile: CameraProfile,
        activeLens: LensInfo,
        shotLogger: ShotLogger,
        deviceRotation: Int,
        temporaryPreviewPath: String? = null,
        userShutterTimestampNs: Long,
        viewfinderMode: ViewfinderMode = ViewfinderMode.PHOTO
    ): Uri? = withContext(Dispatchers.IO) {
        require(userShutterTimestampNs > 0L) {
            "executeCapture requires a valid non-zero userShutterTimestampNs from actual shutter press."
        }
        // Every shutter intent gets a fresh error edge so identical consecutive admission
        // failures are still observable by the UI instead of being swallowed by StateFlow
        // de-duplication.
        _captureContractError.value = null
        val focusCaptureContextAtShutter = snapshotFocusCaptureContext()
        val portraitCaptureContextAtShutter = snapshotPortraitCaptureContext(viewfinderMode)
        val pipelineIdentity = synchronized(pipelineLock) { activePipelineIdentity }
        val attemptId = captureAttempts.begin(
            CaptureAttemptContext(
                cameraId = activeLens.id,
                physicalCameraId = pipelineIdentity?.physicalCameraId,
                lensName = activeLens.name,
                format = formatName(activeZslFormat),
                bufferMode = pipelineIdentity?.backendRoute ?: "ZSL",
                captureMode = "${viewfinderMode.name}:${activeProfile.captureStrategy.name}",
                generationId = pipelineGeneration,
                imageReaderMaxImages = pipelineIdentity?.maxImages ?: ringBuffer.currentCapacity(),
                ringBufferFrameCount = ringBuffer.completeFrameCount()
            )
        )
        if (attemptId == null) {
            Log.w(tag, "Capture ignored: previous capture is still running.")
            traceCaptureRuntime("CAPTURE_REJECT previous_capture_running")
            _captureContractError.value = "Capture is still being admitted; the duplicate shutter press was ignored."
            return@withContext null
        }
        // While capture owns the production path, tracking/portrait analysis is suspended. Keep
        // compact NV21 enabled only for consumers that remain valid during capture (currently QR).
        refreshRawPreviewCompactAnalysisRequest()

        var finalOutputUri: Uri? = null
        var finishReason = "capture did not produce an output"
        // All shutter-time leases are declared before entering the ownership scope so the finally
        // block can release them even if freezing, diagnostics, or route resolution fails.
        var preleasedNormalMultiAnchor: FrameRingBuffer.LeasedCandidate? = null
        var preleasedProvisionalNearZslAnchor: FrameRingBuffer.LeasedCandidate? = null
        var preleasedSingleAnchor: FrameRingBuffer.LeasedCandidate? = null
        var singleAnchorTemporalClass = "UNRESOLVED"
        var singleAnchorEffectiveShutterTimestampNs = userShutterTimestampNs
        var singleAnchorEffectiveShutterTimestampDomain = "ELAPSED_REALTIME"

        try {
            // This must be the first accepted-shutter ownership transaction. Freeze the live RAW
            // selection contract and atomically pin one current-generation complete pre-shutter pair
            // before diagnostics, settings Flow reads, recipe construction, or any other suspend
            // point can advance the ring or mutate exposure-selection authority.
            freezeSelectionExposureConstraintForCapture(attemptId)
            preleasedProvisionalNearZslAnchor = leaseFreshPreShutterAnchor(
                userShutterTimestampNs = userShutterTimestampNs,
                expectedGeneration = pipelineGeneration,
                expectedFormat = activeZslFormat
            )

            val healthAtEntry = ringBuffer.healthDiagnostics(userShutterTimestampNs)
            Log.i(
                "NearZslTiming",
                "event=CAPTURE_ENTRY_BUFFER_HEALTH " +
                        "targetCapacity=${healthAtEntry.targetCapacity} " +
                        "completeFrames=${healthAtEntry.completeFrames} " +
                        "leasedFrames=${healthAtEntry.leasedFrames} " +
                        "writableSlots=${healthAtEntry.writableSlots} " +
                        "pendingPairs=${healthAtEntry.pendingPairs} " +
                        "acquiredImageCount=${healthAtEntry.acquiredImageCount} " +
                        "imageReaderMaxImages=${healthAtEntry.imageReaderMaxImages} " +
                        "actualProducerHeadroom=${healthAtEntry.actualProducerHeadroom} " +
                        "evictions=${healthAtEntry.evictions} " +
                        "droppedIncomingFrames=${healthAtEntry.droppedIncomingFrames} " +
                        "leaseHighWatermark=${healthAtEntry.leaseHighWatermark} " +
                        "bufferRefillLatencyMs=${healthAtEntry.bufferRefillLatencyMs?.let { String.format(java.util.Locale.US, "%.3f", it) } ?: "null"} " +
                        "bufferRefillState=${healthAtEntry.bufferRefillState} " +
                        "captureReady=${healthAtEntry.captureReady} " +
                        "bufferState=${healthAtEntry.bufferState} " +
                        "validCompleteFrameCount=${healthAtEntry.validCompleteFrameCount} " +
                        "fullyPreShutterCandidateCount=${healthAtEntry.fullyPreShutterCandidateCount} " +
                        "frameAgeClockBasis=${healthAtEntry.frameAgeClockBasis} " +
                        "timeToFirstCompleteFrameMs=${healthAtEntry.timeToFirstCompleteFrameMs?.let { String.format(java.util.Locale.US, "%.3f", it) } ?: "null"} " +
                        "generationToSessionConfiguredMs=${healthAtEntry.generationToSessionConfiguredMs?.let { String.format(java.util.Locale.US, "%.3f", it) } ?: "null"} " +
                        "sessionConfiguredToFirstImageMs=${healthAtEntry.sessionConfiguredToFirstImageMs?.let { String.format(java.util.Locale.US, "%.3f", it) } ?: "null"} " +
                        "sessionConfiguredToFirstMetadataMs=${healthAtEntry.sessionConfiguredToFirstMetadataMs?.let { String.format(java.util.Locale.US, "%.3f", it) } ?: "null"} " +
                        "sessionConfiguredToFirstCompleteFrameMs=${healthAtEntry.sessionConfiguredToFirstCompleteFrameMs?.let { String.format(java.util.Locale.US, "%.3f", it) } ?: "null"} " +
                        "startupPairingState=${healthAtEntry.startupPairingState} " +
                        "coldStartWaitMs=${healthAtEntry.coldStartWaitMs?.let { String.format(java.util.Locale.US, "%.3f", it) } ?: "null"} " +
                        "warmTargetProgress=${String.format(java.util.Locale.US, "%.3f", healthAtEntry.warmTargetProgress)} " +
                        "selectionFailureReason=${healthAtEntry.selectionFailureReason ?: "none"}"
            )

            com.bncam.core.debug.RawRecoveryTrace.log(
                "EXECUTE_CAPTURE_ENTRY",
                "format=${formatName(activeZslFormat)}, strategy=${activeProfile.captureStrategy}, userShutterTs=$userShutterTimestampNs, " +
                        "captureReady=${healthAtEntry.captureReady}, bufferState=${healthAtEntry.bufferState}, validComplete=${healthAtEntry.validCompleteFrameCount}, " +
                        "fullyPreShutter=${healthAtEntry.fullyPreShutterCandidateCount}, failureReason=${healthAtEntry.selectionFailureReason ?: "none"}"
            )

            traceCaptureRuntime(
                "CAPTURE_BEGIN attemptId=$attemptId profile=${activeProfile.id} " +
                    "viewfinderMode=$viewfinderMode profileMode=${activeProfile.captureStrategy} lens=${activeLens.id} " +
                    "activeFormat=${formatName(activeZslFormat)} generation=$pipelineGeneration " +
                    "cameraState=${cameraState.value} ringFrames=${ringBuffer.completeFrameCount()}"
            )

            // Preview and capture have independent lifetimes. Never suppress RAW preview offers for
            // the duration of a still-capture attempt; processing may continue asynchronously for
            // seconds after acquisition has completed.
            if (targetViewfinderSource != ViewfinderEffectiveSource.YUV) {
                capturePreviewContinuityTracker.begin(
                    attemptId = attemptId,
                    sessionEpoch = activeConfiguredSessionEpoch,
                    repeatingRequestActive = captureSession != null && currentCaptureRequest != null
                )
            }
            captureAttempts.captureRequestSubmitted(attemptId)

            if (preleasedProvisionalNearZslAnchor != null) {
                traceCaptureRuntime(
                    "SHUTTER_ENTRY_ANCHOR_PIN attemptId=$attemptId " +
                        "frameVersion=${preleasedProvisionalNearZslAnchor?.frameVersion ?: 0L} " +
                        "frameTimestamp=${preleasedProvisionalNearZslAnchor?.timestampNs ?: 0L} " +
                        "physicalAgeMs=${preleasedProvisionalNearZslAnchor?.let { nearZslPhysicalAgeAtShutterMs(it, userShutterTimestampNs) } ?: Double.NaN}"
                )
            } else {
                traceCaptureRuntime(
                    "SHUTTER_ENTRY_ANCHOR_PENDING attemptId=$attemptId " +
                        "reason=no_genuine_pre_shutter_pair_at_entry"
                )
            }

            // Consume the previous-shot prior only after the shutter anchor owns its frame. Do not
            // mutate Camera2 repeating controls while this shutter is still securing its Near-ZSL
            // source frame; the request refresh is deferred to finally after selection ownership is
            // released.
            lastShotVerdictHighDrRisk = false
            lastShotExposureNs = 0L
            lastShotIso = 0

            val settingsRepo = SettingsRepository(context)
            // LIVE UPDATE: Use the latest capture strategy before RAW_SENSOR acquisition,
            // because RAW_SENSOR single frame needs 1 still while RAW_SENSOR multi frame needs up to 5 stills.
            val liveCaptureStrategy = com.bncam.core.debug.DebugTestOverride.forcedCaptureStrategy
                ?: settingsRepo.getProfileCaptureModeFlow(activeProfile.id).first()
            Log.i("BnCameraManager", "EXECUTE_CAPTURE: activeProfile.id=${activeProfile.id} liveCaptureStrategy=$liveCaptureStrategy")
            val livePreferredFrameSource =
                settingsRepo.getProfileFrameSourceFlow(activeProfile.id).first()
            val requestedOutputPolicy = settingsRepo.outputPolicyFlow.first()
            val frameOrigin = FrameOrigin.parse(livePreferredFrameSource)
            val outputPolicy = CaptureOutputPolicyResolver.effectivePolicy(
                frameOrigin = frameOrigin,
                requestedPolicy = requestedOutputPolicy
            )
            val manualExposureActive = requestedManualIso != null || requestedManualExposureNs != null
            val characteristics = cameraManager.getCameraCharacteristics(activeLens.id)
            // Flash belongs to the Camera2 request owner, not necessarily to the selected physical
            // lens descriptor. On a logical+physical route the tele/ultra-wide characteristics may
            // report no local flash even though the logical CameraDevice can drive the phone LED.
            val flashControlCameraId = synchronized(pipelineLock) {
                activePipelineIdentity?.logicalCameraId
            } ?: cameraDevice?.id ?: activeLens.id
            val flashCharacteristics = runCatching {
                cameraManager.getCameraCharacteristics(flashControlCameraId)
            }.getOrElse {
                Log.w(
                    tag,
                    "Flash owner characteristics unavailable for $flashControlCameraId; " +
                        "falling back to selected lens ${activeLens.id}",
                    it
                )
                characteristics
            }
            val (flashShutterDecision, flashControlPlan) =
                resolveFlashShutterDecision(flashCharacteristics, manualExposureActive)
            val dedicatedFlashStill = flashShutterDecision.requiresDedicatedStill
            traceCaptureRuntime(
                "FLASH_ROUTE mode=$currentFlashMode dedicatedStill=$dedicatedFlashStill " +
                    "reason=${flashShutterDecision.reason} autoUncertain=${flashShutterDecision.autoDecisionUncertain} " +
                    "controlPlan=${flashControlPlan?.reason ?: "none"} ownerCamera=$flashControlCameraId " +
                    "selectedLens=${activeLens.id} previewAeState=${latestCamera3AObservation?.aeState ?: "unreported"}"
            )
            val computationalHdrUserRequested =
                runCatching { withTimeoutOrNull(300L) { settingsRepo.computationalHdrEnabledFlow.first() } }
                    .getOrNull() ?: false
            Log.i(
                "BnCameraManager",
                "COMPUTATIONAL_HDR_CHECK flow=$computationalHdrUserRequested liveStrategy=$liveCaptureStrategy " +
                    "result=$computationalHdrUserRequested source=APP_SETTINGS_ONLY"
            )
            val ultraHdrRequested = settingsRepo.ultraHdrGainmapEnabledFlow.first()
            val nightOwnsAdaptiveMultiFrame = viewfinderMode == ViewfinderMode.NIGHT
            val authorityResolution = com.bncam.core.capture.CaptureAuthorityResolver.resolve(
                profileStrategy = liveCaptureStrategy,
                viewfinderMode = viewfinderMode,
                computationalHdrUserRequested = computationalHdrUserRequested,
                ultraHdrRequested = ultraHdrRequested,
                dedicatedFlashStill = dedicatedFlashStill,
                producesJpeg = outputPolicy.producesJpeg
            )
            val computationalHdrRequested = authorityResolution.computationalHdrRequested
            val computationalHdrRouteEnabled = authorityResolution.computationalHdrRouteEnabled
            val computationalHdrResolutionReason = when {
                !computationalHdrRequested -> "not_requested"
                !outputPolicy.producesJpeg -> "raw_only_has_no_computational_jpeg_target"
                dedicatedFlashStill -> "dedicated_flash_still:${flashShutterDecision.reason}"
                nightOwnsAdaptiveMultiFrame -> "night_mode_owns_adaptive_multi_frame"
                else -> authorityResolution.computationalHdrResolutionReason
            }
            val effectiveCaptureStrategy = authorityResolution.effectiveCaptureStrategy
            Log.i("BnCameraManager", "AUTHORITY_RESOLUTION: userHdr=$computationalHdrUserRequested liveStrategy=$liveCaptureStrategy resolvedStrategy=$effectiveCaptureStrategy runner=${authorityResolution.actualRunner} routeEnabled=$computationalHdrRouteEnabled")

            if (effectiveCaptureStrategy == CaptureStrategy.SINGLE_FRAME_ZSL &&
                !computationalHdrRouteEnabled &&
                !dedicatedFlashStill
            ) {
                preleasedSingleAnchor = preleasedProvisionalNearZslAnchor
                    ?: leaseFreshPreShutterAnchor(
                        userShutterTimestampNs = userShutterTimestampNs,
                        expectedGeneration = pipelineGeneration,
                        expectedFormat = activeZslFormat
                    )
                if (preleasedSingleAnchor === preleasedProvisionalNearZslAnchor) {
                    preleasedProvisionalNearZslAnchor = null
                }
                if (preleasedSingleAnchor != null) {
                    singleAnchorTemporalClass = "GENUINE_PRE_SHUTTER_IMMEDIATE"
                    traceCaptureRuntime(
                        "SINGLE_SHUTTER_ANCHOR_PIN attemptId=$attemptId temporalClass=$singleAnchorTemporalClass " +
                            "frameVersion=${preleasedSingleAnchor?.frameVersion ?: 0L} " +
                            "frameTimestamp=${preleasedSingleAnchor?.timestampNs ?: 0L} " +
                            "physicalAgeMs=${preleasedSingleAnchor?.let { nearZslPhysicalAgeAtShutterMs(it, userShutterTimestampNs) } ?: Double.NaN}"
                    )
                } else {
                    traceCaptureRuntime(
                        "SINGLE_SHUTTER_ANCHOR_PENDING attemptId=$attemptId reason=no_fresh_pre_shutter_pair_at_entry " +
                            "completeFrames=${ringBuffer.completeFrameCount()} " +
                            "selection=${ringBuffer.selectionExposureConstraintSnapshot().summary()}"
                    )
                }
            }

            // Preserve the exact normal Multi-Frame shutter anchor before recipe resolution,
            // readiness checks and any other suspend points can let the warm ring advance. This is
            // deliberately limited to the normal Near-ZSL route: deliberate HDR/flash owners carry
            // their own exact post-shutter leases. A missing anchor is not fabricated here; the
            // runner may still acquire a pair that was physically pre-shutter but completed shortly
            // after the press when SENSOR_TIMESTAMP is comparable to elapsedRealtime.
            if (effectiveCaptureStrategy == CaptureStrategy.MULTI_FRAME_ZSL &&
                !computationalHdrRouteEnabled &&
                !dedicatedFlashStill &&
                activeZslFormat == when (frameOrigin) {
                    FrameOrigin.YUV -> ImageFormat.YUV_420_888
                    FrameOrigin.RAW10 -> ImageFormat.RAW10
                    FrameOrigin.RAW_SENSOR -> ImageFormat.RAW_SENSOR
                }
            ) {
                preleasedNormalMultiAnchor = preleasedProvisionalNearZslAnchor
                    ?: ringBuffer.queryAndLeaseCandidates(
                        userShutterTimestampNs = userShutterTimestampNs,
                        maxCount = 1,
                        shutterTimestampDomain = "ELAPSED_REALTIME",
                        expectedFormat = activeZslFormat
                    ).lastOrNull()
                if (preleasedNormalMultiAnchor === preleasedProvisionalNearZslAnchor) {
                    preleasedProvisionalNearZslAnchor = null
                }
                traceCaptureRuntime(
                    "MULTI_SHUTTER_ANCHOR_PIN attemptId=$attemptId pinned=${preleasedNormalMultiAnchor != null} " +
                        "frameVersion=${preleasedNormalMultiAnchor?.frameVersion ?: 0L} " +
                        "frameTimestamp=${preleasedNormalMultiAnchor?.timestampNs ?: 0L} " +
                        "generation=${preleasedNormalMultiAnchor?.frame?.generationId ?: -1}"
                )
            }

            // A provisional shutter-entry lease is only a Near-ZSL admission resource. Dedicated
            // HDR/flash/non-ZSL routes have their own exact capture ownership and must not pin an
            // unrelated warm frame for the rest of this attempt.
            preleasedProvisionalNearZslAnchor?.let { unused ->
                traceCaptureRuntime(
                    "SHUTTER_ENTRY_ANCHOR_RELEASE_UNUSED attemptId=$attemptId " +
                        "effectiveStrategy=$effectiveCaptureStrategy frameVersion=${unused.frameVersion}"
                )
                unused.lease.release()
                preleasedProvisionalNearZslAnchor = null
            }

            val profileNightFrameCount = if (viewfinderMode == ViewfinderMode.NIGHT) {
                when (frameOrigin) {
                    FrameOrigin.YUV -> settingsRepo.getProfileMultiFrameFusionFramesYuvFlow(activeProfile.id).first()
                    FrameOrigin.RAW10 -> settingsRepo.getProfileMultiFrameFusionFramesRaw10Flow(activeProfile.id).first()
                    FrameOrigin.RAW_SENSOR -> settingsRepo.getProfileMultiFrameFusionFramesRawSensorFlow(activeProfile.id).first()
                }
            } else null
            val recentNightExposureResult = if (lastCaptureResultGeneration == pipelineGeneration) {
                (lastCaptureResult as? TotalCaptureResult)?.let { result ->
                    previewCaptureResult(result, pipelineIdentity?.physicalCameraId)
                }
            } else null
            val nightCapturePlan: NightCapturePlan? = if (
                viewfinderMode == ViewfinderMode.NIGHT &&
                !dedicatedFlashStill
            ) {
                NightCapturePolicy.resolve(
                    origin = frameOrigin,
                    measuredIso = recentNightExposureResult
                        ?.get(CaptureResult.SENSOR_SENSITIVITY),
                    measuredExposureNs = recentNightExposureResult
                        ?.get(CaptureResult.SENSOR_EXPOSURE_TIME),
                    profileRequestedFrames = profileNightFrameCount ?: 1,
                    runtimeSafeMaximum = ringBuffer.currentCapacity()
                )
            } else null
            if (viewfinderMode == ViewfinderMode.NIGHT) {
                traceCaptureRuntime(
                    "NIGHT_CAPTURE_POLICY active=${nightCapturePlan != null} " +
                        "reason=${nightCapturePlan?.reason ?: when {
                            dedicatedFlashStill -> "dedicated_flash_owns_capture"
                            else -> "unresolved"
                        }}"
                )
            }
            val streamMap = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?: throw IllegalStateException("Camera '${activeLens.id}' exposes no stream configuration map.")
            val rawCapability = characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
                ?.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW) == true
            val capabilities = CaptureCapabilities(
                yuv = streamMap.getOutputSizes(ImageFormat.YUV_420_888)?.isNotEmpty() == true,
                raw10 = streamMap.getOutputSizes(ImageFormat.RAW10)?.isNotEmpty() == true,
                rawSensor = streamMap.getOutputSizes(ImageFormat.RAW_SENSOR)?.isNotEmpty() == true,
                camera2RawCapability = rawCapability
            )
            val capturePlan: CaptureRequestPlan = CaptureRoutePlanner.plan(
                captureMode = CaptureMode.from(effectiveCaptureStrategy),
                frameOrigin = frameOrigin,
                outputPolicy = outputPolicy,
                renderProfileId = activeProfile.id,
                cameraId = activeLens.id,
                capabilities = capabilities,
                debugPolicy = PerformanceDebugPolicy(
                    shotLoggingEnabled = settingsRepo.enableShotLoggerFlow.first()
                )
            )
            traceCaptureRuntime(
                "CAPTURE_PLAN attemptId=$attemptId viewfinderMode=$viewfinderMode liveMode=$liveCaptureStrategy " +
                    "effectiveMode=$effectiveCaptureStrategy nightFrames=${nightCapturePlan?.requestedFrames ?: "profile"} " +
                    "requestedFormat=$livePreferredFrameSource actualFormat=${formatName(activeZslFormat)} " +
                    "route=${capturePlan.route.id} requestedOutputPolicy=$requestedOutputPolicy " +
                    "effectiveOutputPolicy=$outputPolicy"
            )
            val actualOrigin = when (activeZslFormat) {
                ImageFormat.YUV_420_888 -> FrameOrigin.YUV
                ImageFormat.RAW10 -> FrameOrigin.RAW10
                ImageFormat.RAW_SENSOR -> FrameOrigin.RAW_SENSOR
                else -> throw IllegalStateException("Unsupported active ImageReader format $activeZslFormat.")
            }
            if (actualOrigin != capturePlan.frameOrigin) {
                throw IllegalStateException(
                    "Capture contract requires ${capturePlan.frameOrigin}, but the active ImageReader is $actualOrigin. " +
                        "Wait for the selected profile pipeline to become active."
                )
            }
            _captureContractError.value = null
            Log.i("BnCamCaptureTiming", capturePlan.logValue())

            val thermalState = runCatching {
                val powerManager =
                    context.getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager
                powerManager.currentThermalStatus.toString()
            }.getOrDefault("UNAVAILABLE")
            val recipe = com.bncam.core.capture.CaptureRecipeFactory.create(
                repository = settingsRepo,
                request = com.bncam.core.capture.CaptureRecipeRequest(
                    applicationVersion = com.bncam.BuildConfig.VERSION_NAME,
                    profileId = activeProfile.id,
                    profileDefaultName = activeProfile.name,
                    captureStrategy = effectiveCaptureStrategy,
                    frameSourceFormat = activeZslFormat,
                    frameOrigin = frameOrigin,
                    outputPolicy = outputPolicy,
                    logicalCameraId = pipelineIdentity?.logicalCameraId ?: activeLens.id,
                    physicalCameraId = pipelineIdentity?.physicalCameraId,
                    lensId = activeLens.id,
                    pipelineGenerationId = pipelineGeneration,
                    runtimeSafeWarmBufferCapacity = ringBuffer.currentCapacity(),
                    thermalState = thermalState,
                    captureTimestampEpochMs = System.currentTimeMillis(),
                    capabilityResolutions = listOf(
                        com.bncam.core.capture.CapabilityResolutionRecord(
                            capability = "frame_source",
                            requested = livePreferredFrameSource,
                            supported = capabilities.supports(frameOrigin).toString(),
                            resolved = actualOrigin.name,
                            reason = "active_imagereader_contract_verified"
                        ),
                        com.bncam.core.capture.CapabilityResolutionRecord(
                            capability = "output_policy",
                            requested = requestedOutputPolicy.name,
                            supported = "true",
                            resolved = outputPolicy.name,
                            reason = if (requestedOutputPolicy == outputPolicy) {
                                "as_requested"
                            } else {
                                "source_applicability_resolution"
                            }
                        ),
                        com.bncam.core.capture.CapabilityResolutionRecord(
                            capability = "viewfinder_mode",
                            requested = viewfinderMode.name,
                            supported = (viewfinderMode != ViewfinderMode.VIDEO).toString(),
                            resolved = viewfinderMode.name,
                            reason = when (viewfinderMode) {
                                ViewfinderMode.NIGHT -> "night_transient_multi_frame_policy"
                                ViewfinderMode.PHOTO -> "profile_capture_strategy"
                                ViewfinderMode.PORTRAIT -> "profile_capture_strategy_with_portrait_analysis"
                                ViewfinderMode.VIDEO -> "video_not_routed_to_still_engine"
                            }
                        ),
                        com.bncam.core.capture.CapabilityResolutionRecord(
                            capability = "night_frame_budget",
                            requested = profileNightFrameCount?.toString() ?: "not_applicable",
                            supported = (viewfinderMode == ViewfinderMode.NIGHT).toString(),
                            resolved = nightCapturePlan?.requestedFrames?.toString()
                                ?: profileNightFrameCount?.toString()
                                ?: "not_applicable",
                            reason = nightCapturePlan?.reason ?: "night_policy_not_active"
                        )
                    ),
                    computationalHdrRequested = computationalHdrRequested,
                    computationalHdrRouteEnabled = computationalHdrRouteEnabled,
                    computationalHdrResolutionReason = computationalHdrResolutionReason,
                    requestedFrameCountOverride = nightCapturePlan?.requestedFrames
                )
            )
            val requestedSingleFrameBaseCandidateCount =
                recipe.executionSettings.selection.baseCandidateCount
            val requestedRouteFrameCount = recipe.requestedFrameCount
            traceCaptureRuntime(
                "CAPTURE_RECIPE attemptId=$attemptId schema=${recipe.schemaVersion} " +
                    "profileHash=${recipe.profileVersionHash} requestedFrames=${recipe.requestedFrameCount} " +
                    "effectiveFrames=${recipe.effectiveFrameCount} bufferCapacity=${recipe.bufferCapacity} " +
                    "capacityReason=${recipe.processingFrameResolution.resolutionReason} " +
                    "alignment=${recipe.alignmentMethod.requestedId}->${recipe.alignmentMethod.resolvedId} " +
                    "fusion=${recipe.fusionMethod.requestedId}->${recipe.fusionMethod.resolvedId} " +
                    "hdrRequested=${recipe.computationalHdrRequested} hdrRoute=${recipe.computationalHdrRouteEnabled} " +
                    "hdrReason=${recipe.computationalHdrResolutionReason}"
            )
            val warmBufferRequirement =
                WarmBufferReadinessPolicy.captureRoute(
                    format = activeZslFormat,
                    captureMode = CaptureMode.from(effectiveCaptureStrategy),
                    requestedFrameCount = requestedRouteFrameCount,
                    bufferCapacity = ringBuffer.currentCapacity()
                ).let { req ->
                    if (recipe.computationalHdrRouteEnabled) {
                        // HDR Enhanced needs one valid pre-shutter frame only as a planning
                        // snapshot. Its processing frames are deliberate post-shutter RAW requests.
                        req.copy(requiredCompleteFrames = 1)
                    } else req
                }

            val preleasedAdmissionAnchor = preleasedSingleAnchor ?: preleasedNormalMultiAnchor
            val pinnedAnchorReadinessReason = preleasedAdmissionAnchor?.let { pinned ->
                if (pinned.frame.generationId == pipelineGeneration && pinned.frame.format == activeZslFormat) {
                    pipelineReadinessReason(
                        requestedProfileId = activeProfile.id,
                        requestedFormat = livePreferredFrameSource,
                        cameraId = activeLens.id,
                        requirement = warmBufferRequirement
                    )
                } else {
                    "PINNED_ANCHOR_IDENTITY_INVALID"
                }
            }
            val pinnedAnchorCanOwnAdmission = pinnedAnchorReadinessReason != null &&
                (pinnedAnchorReadinessReason == "READY" ||
                    pinnedAnchorReadinessReason.startsWith("READINESS_") ||
                    pinnedAnchorReadinessReason.startsWith("LEASABLE_WARM_BUFFER_NOT_READY") ||
                    pinnedAnchorReadinessReason.startsWith("WARM_BUFFER_STREAM_STALE"))
            val pipelineReady = if (pinnedAnchorCanOwnAdmission) {
                // Normal Near-ZSL already owns a complete, exact, pre-shutter source frame. Do not
                // make that captured moment wait for later 3A/stream-health observations: those can
                // only describe frames after the user's press and cannot improve the pinned anchor.
                // Physical identity/session/format failures are deliberately not bypassed above.
                pipelineCaptureGateReady = true
                pipelineCaptureGateLastReason =
                    if (pinnedAnchorReadinessReason == "READY") "READY_PINNED_ANCHOR"
                    else "READY_PINNED_ANCHOR_DEGRADED:$pinnedAnchorReadinessReason"
                pipelineCaptureGateWaitMs = 0L
                pipelineCaptureGateResetTriggered = false
                traceCaptureRuntime(
                    "PINNED_SHUTTER_ANCHOR_ADMISSION attemptId=$attemptId " +
                        "route=${if (preleasedSingleAnchor != null) "SINGLE" else "MULTI"} " +
                        "sourceReason=$pinnedAnchorReadinessReason " +
                        "frameVersion=${preleasedAdmissionAnchor?.frameVersion ?: 0L}"
                )
                true
            } else {
                waitForPipelineReadyForCapture(
                    requestedProfileId = activeProfile.id,
                    requestedFormat = livePreferredFrameSource,
                    cameraId = activeLens.id,
                    requirement = warmBufferRequirement,
                    timeoutMs = if (effectiveCaptureStrategy == CaptureStrategy.MULTI_FRAME_ZSL &&
                        !recipe.computationalHdrRouteEnabled && !dedicatedFlashStill) {
                        // If no shutter-time anchor exists, only a frame whose exposure already
                        // ended before the press can still become eligible while its image/metadata
                        // pair finishes arriving. Waiting several seconds cannot create a genuine
                        // Near-ZSL frame and merely turns subsequent taps into duplicate rejects.
                        350L
                    } else {
                        4200L
                    }
                )
            }
            traceCaptureRuntime(
                "PIPELINE_GATE attemptId=$attemptId ready=$pipelineReady " +
                    "reason=$pipelineCaptureGateLastReason waitMs=$pipelineCaptureGateWaitMs " +
                    "requiredFrames=${warmBufferRequirement.requiredCompleteFrames} " +
                    "availableFrames=${ringBuffer.completeFrameCount()} cameraState=${cameraState.value}"
            )
            if (!pipelineReady) {
                finishReason = "pipeline_not_ready:$pipelineCaptureGateLastReason"
                Log.e(
                    tag,
                    "Capture aborted: active pipeline is not ready for requested profile/frame source. " +
                            "requested=$livePreferredFrameSource reason=$pipelineCaptureGateLastReason"
                )
                // A shutter attempt already owns the acquisition slot at this point. Every early
                // return after begin() must terminate that exact attempt or later shutter presses
                // will be rejected as "previous capture still running" even though no work exists.
                _captureContractError.value =
                    "Camera stream was not ready for capture (${pipelineCaptureGateLastReason.substringBefore(' ')})."
                finishCaptureAttempt(attemptId, null, finishReason)
                return@withContext null
            }

            // A gate-triggered physical rebuild invalidates a lease from the previous generation.
            // Release it before runner dispatch; the runner will perform one final atomic selection
            // against the original shutter timestamp instead of consuming stale-generation data.
            preleasedNormalMultiAnchor?.let { pinned ->
                if (pinned.frame.generationId != pipelineGeneration || pinned.frame.format != activeZslFormat) {
                    traceCaptureRuntime(
                        "MULTI_SHUTTER_ANCHOR_INVALIDATED attemptId=$attemptId " +
                            "pinnedGeneration=${pinned.frame.generationId} activeGeneration=$pipelineGeneration " +
                            "pinnedFormat=${formatName(pinned.frame.format)} activeFormat=${formatName(activeZslFormat)}"
                    )
                    pinned.lease.release()
                    preleasedNormalMultiAnchor = null
                }
            }

            preleasedSingleAnchor?.let { pinned ->
                if (pinned.frame.generationId != pipelineGeneration || pinned.frame.format != activeZslFormat) {
                    traceCaptureRuntime(
                        "SINGLE_SHUTTER_ANCHOR_INVALIDATED attemptId=$attemptId " +
                            "pinnedGeneration=${pinned.frame.generationId} activeGeneration=$pipelineGeneration " +
                            "pinnedFormat=${formatName(pinned.frame.format)} activeFormat=${formatName(activeZslFormat)}"
                    )
                    pinned.lease.release()
                    preleasedSingleAnchor = null
                    singleAnchorTemporalClass = "INVALIDATED_BY_PIPELINE_CHANGE"
                }
            }

            if (effectiveCaptureStrategy == CaptureStrategy.SINGLE_FRAME_ZSL &&
                !computationalHdrRouteEnabled &&
                !dedicatedFlashStill &&
                preleasedSingleAnchor == null
            ) {
                val reservation = awaitSingleNearZslAnchor(
                    attemptId = attemptId,
                    userShutterTimestampNs = userShutterTimestampNs,
                    expectedGeneration = pipelineGeneration,
                    expectedFormat = activeZslFormat,
                    coldStartAtUserShutter = healthAtEntry.completeFrames == 0
                )
                if (reservation == null) {
                    // One controlled repeating-request kick is allowed. It does not create a still
                    // request and therefore preserves the warm Near-ZSL architecture. Any selection
                    // exposure update produced by the kick is deferred until this shutter is owned.
                    traceCaptureRuntime(
                        "SINGLE_SHUTTER_ANCHOR_RECOVERY_KICK attemptId=$attemptId " +
                            "completeFrames=${ringBuffer.completeFrameCount()} " +
                            "eventSequence=${ringBuffer.currentEventSequence()}"
                    )
                    backgroundHandler?.post { updatePreviewRepeatingRequest() }
                    val retryReservation = awaitSingleNearZslAnchor(
                        attemptId = attemptId,
                        userShutterTimestampNs = userShutterTimestampNs,
                        expectedGeneration = pipelineGeneration,
                        expectedFormat = activeZslFormat,
                        coldStartAtUserShutter = false
                    )
                    if (retryReservation == null) {
                        finishReason = "single_near_zsl_anchor_unavailable_after_repeating_recovery"
                        _captureContractError.value =
                            "Camera stream did not provide a complete capture frame."
                        traceCaptureRuntime(
                            "SINGLE_SHUTTER_ANCHOR_FAILURE attemptId=$attemptId reason=$finishReason " +
                                "health=${ringBuffer.healthDiagnostics(userShutterTimestampNs)}"
                        )
                        finishCaptureAttempt(attemptId, null, finishReason)
                        return@withContext null
                    }
                    preleasedSingleAnchor = retryReservation.candidate
                    singleAnchorTemporalClass = retryReservation.temporalClass
                    singleAnchorEffectiveShutterTimestampNs = retryReservation.effectiveShutterTimestampNs
                    singleAnchorEffectiveShutterTimestampDomain = retryReservation.effectiveShutterTimestampDomain
                    traceCaptureRuntime(
                        "SINGLE_SHUTTER_ANCHOR_RESERVED attemptId=$attemptId recovery=true " +
                            "temporalClass=$singleAnchorTemporalClass waitMs=${retryReservation.waitMs} " +
                            "physicalAgeMs=${retryReservation.physicalAgeAtUserShutterMs ?: Double.NaN} " +
                            "frameVersion=${retryReservation.candidate.frameVersion}"
                    )
                } else {
                    preleasedSingleAnchor = reservation.candidate
                    singleAnchorTemporalClass = reservation.temporalClass
                    singleAnchorEffectiveShutterTimestampNs = reservation.effectiveShutterTimestampNs
                    singleAnchorEffectiveShutterTimestampDomain = reservation.effectiveShutterTimestampDomain
                    traceCaptureRuntime(
                        "SINGLE_SHUTTER_ANCHOR_RESERVED attemptId=$attemptId recovery=false " +
                            "temporalClass=$singleAnchorTemporalClass waitMs=${reservation.waitMs} " +
                            "physicalAgeMs=${reservation.physicalAgeAtUserShutterMs ?: Double.NaN} " +
                            "frameVersion=${reservation.candidate.frameVersion}"
                    )
                }
            }

            // Explicit-touch autofocus is completed during the live tap transaction. Do not run a
            // second image-space/manual-lens autofocus solver at shutter time: capture must preserve
            // the focus state that the photographer already verified in the viewfinder.

            // Flash/HDR applicability was frozen before route planning so the runner receives one
            // coherent capture contract for this shutter press.
            val shutterTimestampNs: Long
            var shutterTimestampDomain = "UNSET"
            var currentSubmittedControlRequestEpochAtShutter = 0L
            var postShutterStillCaptureUsed = false

            if (dedicatedFlashStill) {
                postShutterStillCaptureUsed = true
                val flashCaptureResult = executeDedicatedFlashCapture(
                    activeProfile = activeProfile,
                    activeLens = activeLens,
                    settingsRepo = settingsRepo,
                    recipe = recipe,
                    livePreferredFrameSource = livePreferredFrameSource,
                    focusCaptureContextAtShutter = focusCaptureContextAtShutter,
                    flashControlPlan = flashControlPlan
                )
                if (flashCaptureResult == null) {
                    finishReason = "dedicated_flash_capture_failed_before_runner"
                    traceCaptureRuntime(
                        "CAPTURE_REJECTED attemptId=$attemptId reason=$finishReason"
                    )
                    finishCaptureAttempt(attemptId, null, finishReason)
                    return@withContext null
                }
                shutterTimestampNs = flashCaptureResult.shutterTimestampNs
                shutterTimestampDomain = flashCaptureResult.shutterTimestampDomain
                currentSubmittedControlRequestEpochAtShutter =
                    flashCaptureResult.controlRequestEpochAtShutter
                flashCaptureResult.captureFailureReason?.let { finishReason = it }
            } else {
                // =======================================================
                // NORMALE ZSL SEQUENCE (flash is off or Auto AE proved that flash is unnecessary)
                // =======================================================
                if (recipe.executionSettings.cameraSoundEnabled && !managerShutdownRequested.get()) {
                    mediaActionSound.play(MediaActionSound.SHUTTER_CLICK)
                }
                if (effectiveCaptureStrategy == CaptureStrategy.SINGLE_FRAME_ZSL &&
                    preleasedSingleAnchor != null
                ) {
                    shutterTimestampNs = singleAnchorEffectiveShutterTimestampNs
                    shutterTimestampDomain = singleAnchorEffectiveShutterTimestampDomain
                } else {
                    shutterTimestampNs = userShutterTimestampNs
                    shutterTimestampDomain = "ELAPSED_REALTIME"
                }
                currentSubmittedControlRequestEpochAtShutter =
                    currentSubmittedControlRequestEpoch()
                traceCaptureRuntime(
                    "SHUTTER_TEMPORAL_AUTHORITY attemptId=$attemptId userShutterNs=$userShutterTimestampNs " +
                        "effectiveShutterNs=$shutterTimestampNs domain=$shutterTimestampDomain " +
                        "singleAnchorTemporalClass=$singleAnchorTemporalClass"
                )
            }

            var hdrBracket: HdrBracketCaptureContext? = null
            if (effectiveCaptureStrategy == CaptureStrategy.HDR_ENHANCED) {
                postShutterStillCaptureUsed = true
            }
            if (recipe.computationalHdrRouteEnabled && effectiveCaptureStrategy != CaptureStrategy.HDR_ENHANCED) {
                hdrBracket = acquireComputationalHdrBracket(
                    settingsRepo = settingsRepo,
                    activeLens = activeLens,
                    recipe = recipe,
                    activeFormat = activeZslFormat,
                    userShutterTimestampNs = userShutterTimestampNs,
                    focusCaptureContext = focusCaptureContextAtShutter
                )
                if (hdrBracket != null) {
                    postShutterStillCaptureUsed = true
                    currentSubmittedControlRequestEpochAtShutter =
                        hdrBracket.anchor.lease.pair.controlRequestEpoch
                } else {
                    // The route stays multi so the immutable recipe remains truthful. The runner
                    // will select one pre-shutter anchor and explicitly execute a non-HDR fallback.
                    postShutterStillCaptureUsed = false
                    traceCaptureRuntime("HDR_BRACKET_FALLBACK anchor_only=true")
                }
            }

            // Router: stuur de opdracht door.
            // Belangrijk: de runner maakt de echte ShotLogger folder aan via startNewShot(...).
            // Daarom schrijven we Injection of tags.txt pas NA de runner.
            // LIVE UPDATE: We negeren de strategy van het oude 'activeProfile' object uit de UI,
            // en gebruiken de actuele stand die vóór RAW_SENSOR acquisition is geladen.
            val executionProfile = activeProfile.copy(captureStrategy = effectiveCaptureStrategy)
            // Flash/still requests can legitimately have a different illuminant result; in that
            // case the selected still frame's exact metadata remains authoritative. Near-ZSL RAW
            // captures use the BnCam stable auto-WB snapshot taken at dispatch.
            val stableAutoWhiteBalanceAtShutter = if (postShutterStillCaptureUsed) {
                null
            } else {
                stableAutoWhiteBalanceSnapshotForActiveCamera()
            }

            // Detached processing belongs to CaptureProcessingQueue and may outlive this Activity.
            // Keep feedback non-owning so a queued RAW job cannot retain a retired BnCameraManager.
            val asyncRenderHealthOwner = java.lang.ref.WeakReference(this@BnCameraManager)

            // SingleFrameRunner's legacy parameter named userShutterTimestampNs is also its hard
            // eligibility boundary. For the one explicitly degraded cold/transport fallback, the
            // selected frame necessarily completed after the physical press, so feed the runner the
            // effective repeating-frame boundary while keeping the real physical press separately
            // and explicitly in the capture policy telemetry below. Normal warm Near-ZSL and the
            // bounded degraded PRE-shutter tier continue to use the real user shutter unchanged.
            val singleRunnerEligibilityTimestampNs =
                if (singleAnchorTemporalClass == "FIRST_VALID_WARM_REPEATING_FRAME") {
                    singleAnchorEffectiveShutterTimestampNs
                } else {
                    userShutterTimestampNs
                }
            val diagnosticsSensorMetadata = if (capturePlan.frameOrigin == FrameOrigin.YUV) {
                null
            } else {
                hdrBracket?.anchor?.lease?.pair?.sensorMetadataSnapshot
                    ?: preleasedSingleAnchor?.lease?.pair?.sensorMetadataSnapshot
                    ?: preleasedNormalMultiAnchor?.lease?.pair?.sensorMetadataSnapshot
            }
            val unifiedRuntimeDiagnostics = buildUnifiedRuntimeDiagnosticsSnapshot(
                capturePlan = capturePlan,
                capabilities = capabilities,
                sensorMetadata = diagnosticsSensorMetadata
            ).compactText()
            val unifiedRuntimeDiagnosticsSuffix =
                ";unifiedRuntimeDiagnostics=$unifiedRuntimeDiagnostics"

            val singleRunnerExposurePolicySummary =
                lastExposurePlanSummary +
                    ";nearZslPhysicalUserShutterTimestampNs=$userShutterTimestampNs" +
                    ";nearZslRunnerEligibilityTimestampNs=$singleRunnerEligibilityTimestampNs" +
                    ";nearZslAnchorTemporalClass=$singleAnchorTemporalClass" +
                    ";nearZslAnchorIsGenuinePreShutter=${
                        singleAnchorTemporalClass.startsWith("GENUINE_PRE_SHUTTER")
                    }" +
                    ";nearZslNoDedicatedStillFallback=${!dedicatedFlashStill}" +
                    unifiedRuntimeDiagnosticsSuffix

            val submissionResult = try {
                withTimeout(60_000L) {
                    when (effectiveCaptureStrategy) {
                        com.bncam.core.engine.CaptureStrategy.SINGLE_FRAME_ZSL -> {
                            val runner =
                                com.bncam.core.runners.SingleFrameRunner(context.applicationContext, cameraManager)
                            runner.execute(
                                plan = capturePlan,
                                recipe = recipe,
                                ringBuffer = ringBuffer,
                                shutterTimestampNs = shutterTimestampNs,
                                shutterTimestampDomain = shutterTimestampDomain,
                                requestedBaseCandidateCount =
                                    requestedSingleFrameBaseCandidateCount,
                                activeProfile = executionProfile,
                                activeLens = activeLens,
                                shotLogger = shotLogger,
                                deviceRotation = deviceRotation,
                                activeZslFormat = activeZslFormat,
                                meteringStyle = currentMeteringStyle,
                                evOffset = currentEvOffset,
                                currentSubmittedControlRequestEpochAtShutter =
                                    currentSubmittedControlRequestEpochAtShutter,
                                aeStateBeforeCapture = lastAeState,
                                meteringPolicySummary = lastMeteringPlanSummary,
                                exposurePolicySummary = singleRunnerExposurePolicySummary,
                                postShutterStillCaptureUsed = postShutterStillCaptureUsed,
                                captureStageListener = captureAttempts.listenerFor(attemptId),
                                onRawProcessingFeedback = { feedback ->
                                    asyncRenderHealthOwner.get()?.let { owner ->
                                        owner.applyRenderHealthFeedback(
                                            nativeStats = feedback.nativeStats,
                                            exposureTimeNs = feedback.exposureTimeNs,
                                            sensitivityIso = feedback.sensitivityIso,
                                            source = "ASYNC_SINGLE_RAW"
                                        )
                                    }
                                },
                                temporaryPreviewPath = temporaryPreviewPath,
                                userShutterTimestampNs = singleRunnerEligibilityTimestampNs,
                                stableAutoWhiteBalance = stableAutoWhiteBalanceAtShutter,
                                focusCaptureContext = focusCaptureContextAtShutter,
                                portraitCaptureContext = portraitCaptureContextAtShutter
                            )
                        }

                        com.bncam.core.engine.CaptureStrategy.MULTI_FRAME_ZSL -> {
                            val runner = com.bncam.core.runners.MultiFrameRunner(context.applicationContext, cameraManager)
                            val multiSubmission = runner.execute(
                                plan = capturePlan,
                                recipe = recipe,
                                ringBuffer = ringBuffer,
                                shutterTimestampNs = shutterTimestampNs,
                                shutterTimestampDomain = shutterTimestampDomain,
                                activeProfile = executionProfile,
                                activeLens = activeLens,
                                shotLogger = shotLogger,
                                deviceRotation = deviceRotation,
                                activeZslFormat = activeZslFormat,
                                meteringStyle = currentMeteringStyle,
                                evOffset = currentEvOffset,
                                currentSubmittedControlRequestEpochAtShutter =
                                    currentSubmittedControlRequestEpochAtShutter,
                                aeStateBeforeCapture = lastAeState,
                                meteringPolicySummary = lastMeteringPlanSummary,
                                exposurePolicySummary = lastExposurePlanSummary + unifiedRuntimeDiagnosticsSuffix,
                                postShutterStillCaptureUsed = postShutterStillCaptureUsed,
                                stableAutoWhiteBalance = stableAutoWhiteBalanceAtShutter,
                                hdrBracket = hdrBracket,
                                preleasedShutterAnchor = preleasedNormalMultiAnchor,
                                captureStageListener = captureAttempts.listenerFor(attemptId),
                                onRawProcessingFeedback = { feedback ->
                                    asyncRenderHealthOwner.get()?.let { owner ->
                                        owner.applyRenderHealthFeedback(
                                            nativeStats = feedback.nativeStats,
                                            exposureTimeNs = feedback.exposureTimeNs,
                                            sensitivityIso = feedback.sensitivityIso,
                                            source = "ASYNC_MULTI_RAW"
                                        )
                                    }
                                },
                                temporaryPreviewPath = temporaryPreviewPath,
                                focusCaptureContext = focusCaptureContextAtShutter,
                                portraitCaptureContext = portraitCaptureContextAtShutter,
                                nightCapturePlan = nightCapturePlan
                            )
                            // MultiFrameRunner now owns/releases the pinned anchor on every return
                            // path, including async submission and explicit rejection.
                            preleasedNormalMultiAnchor = null
                            multiSubmission
                        }

                        com.bncam.core.engine.CaptureStrategy.HDR_ENHANCED -> {
                            val runner = com.bncam.core.runners.HdrEnhancedRunner(context.applicationContext, cameraManager)
                            runner.execute(
                                plan = capturePlan,
                                recipe = recipe,
                                ringBuffer = ringBuffer,
                                shutterTimestampNs = shutterTimestampNs,
                                shutterTimestampDomain = shutterTimestampDomain,
                                activeProfile = executionProfile,
                                activeLens = activeLens,
                                shotLogger = shotLogger,
                                deviceRotation = deviceRotation,
                                activeZslFormat = activeZslFormat,
                                meteringStyle = currentMeteringStyle,
                                evOffset = currentEvOffset,
                                currentSubmittedControlRequestEpochAtShutter = currentSubmittedControlRequestEpochAtShutter,
                                aeStateBeforeCapture = lastAeState,
                                meteringPolicySummary = lastMeteringPlanSummary,
                                exposurePolicySummary = lastExposurePlanSummary + unifiedRuntimeDiagnosticsSuffix,
                                postShutterStillCaptureUsed = postShutterStillCaptureUsed,
                                stableAutoWhiteBalance = stableAutoWhiteBalanceAtShutter,
                                captureStageListener = captureAttempts.listenerFor(attemptId),
                                onRawProcessingFeedback = { feedback ->
                                    asyncRenderHealthOwner.get()?.let { owner ->
                                        owner.applyRenderHealthFeedback(
                                            nativeStats = feedback.nativeStats,
                                            exposureTimeNs = feedback.exposureTimeNs,
                                            sensitivityIso = feedback.sensitivityIso,
                                            source = "ASYNC_HDR_ENHANCED_RAW"
                                        )
                                    }
                                },
                                temporaryPreviewPath = temporaryPreviewPath,
                                focusCaptureContext = focusCaptureContextAtShutter,
                                portraitCaptureContext = portraitCaptureContextAtShutter,
                                acquireDeliberateBurst = {
                                    acquireHdrEnhancedBurst(
                                        settingsRepo = settingsRepo,
                                        activeLens = activeLens,
                                        recipe = recipe,
                                        activeFormat = activeZslFormat,
                                        userShutterTimestampNs = userShutterTimestampNs,
                                        focusCaptureContext = focusCaptureContextAtShutter
                                    )
                                }
                            )
                        }
                    }
                }
            } finally {
                // Ownership is idempotent. MultiFrameRunner normally releases these leases after
                // native processing; this closes the exceptional/cancellation path as well.
                hdrBracket?.close()
            }
            traceCaptureRuntime(
                "RUNNER_RESULT attemptId=$attemptId type=${submissionResult::class.java.simpleName} " +
                    "route=${capturePlan.route.id}"
            )

            // RAW processing is asynchronous for Single, Multi and HDR Enhanced. Each runner
            // reports its own completed native stats through a callback; reading the process-global
            // stats here would race the worker and can feed an older shot into exposure health.
            val rawProcessingFeedbackDeferred =
                (actualOrigin == FrameOrigin.RAW10 || actualOrigin == FrameOrigin.RAW_SENSOR) &&
                    (effectiveCaptureStrategy == CaptureStrategy.SINGLE_FRAME_ZSL ||
                        effectiveCaptureStrategy == CaptureStrategy.MULTI_FRAME_ZSL ||
                        effectiveCaptureStrategy == CaptureStrategy.HDR_ENHANCED)
            if (!rawProcessingFeedbackDeferred) {
                applyRenderHealthFeedback(
                    nativeStats = ImageUtils.lastMasterIspStats(),
                    exposureTimeNs =
                        lastCaptureResult?.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: 0L,
                    sensitivityIso =
                        lastCaptureResult?.get(CaptureResult.SENSOR_SENSITIVITY) ?: 0,
                    source = "SYNCHRONOUS_CAPTURE"
                )
            }

            when (submissionResult) {
                is com.bncam.core.output.CaptureSubmissionResult.Submitted -> {
                    traceCaptureRuntime(
                        "CAPTURE_SUBMITTED attemptId=$attemptId workId=${submissionResult.workId}"
                    )
                    captureAttempts.markSubmitted(attemptId, submissionResult.workId)
                    // The worker may finish before this coroutine receives Submitted. Reconcile
                    // the durable snapshot after installing the attempt/work mapping so a fast
                    // terminal event cannot be lost by the non-replaying SharedFlow.
                    com.bncam.core.output.CaptureProcessingQueue
                        .snapshot(submissionResult.workId)
                        ?.let(::reconcileCaptureWorkSnapshot)
                    finalOutputUri = null
                    finishReason = "submitted_asynchronously"
                    return@withContext null
                }
                is com.bncam.core.output.CaptureSubmissionResult.CompletedSynchronously -> {
                    traceCaptureRuntime(
                        "CAPTURE_COMPLETED_SYNC attemptId=$attemptId uri=${submissionResult.outputUri}"
                    )
                    finalOutputUri = submissionResult.outputUri
                    finishReason = "output_published_synchronously"
                    finishCaptureAttempt(
                        attemptId,
                        finalOutputUri,
                        finishReason,
                        submissionResult.artifacts.publicationResult !=
                            com.bncam.core.output.CapturePublicationResult.FAILURE
                    )
                    return@withContext finalOutputUri
                }
                is com.bncam.core.output.CaptureSubmissionResult.Rejected -> {
                    traceCaptureRuntime(
                        "CAPTURE_REJECTED attemptId=$attemptId reason=${submissionResult.reason}"
                    )
                    finalOutputUri = null
                    finishReason = "submission_rejected:${submissionResult.reason}"
                    _captureContractError.value = when (submissionResult.reason) {
                        "ring_buffer_empty" -> "No shutter-time frame was available from the warm buffer."
                        "anchor_frame_incomplete" -> "The selected shutter frame became incomplete before processing."
                        "processing_queue_full" -> "Previous photos are still processing; the capture queue is full."
                        "queue_submission_rejected" -> "The photo could not be handed to the processing queue."
                        else -> "Capture rejected: ${submissionResult.reason}"
                    }
                    shotLogger.finalizeFailureWithPublicDiagnostics(
                        attemptId = "${activeLens.id}-$userShutterTimestampNs",
                        stage = "CAPTURE_SUBMISSION_REJECTED",
                        exception = IllegalStateException(
                            "Capture submission rejected: ${submissionResult.reason}"
                        )
                    )
                    finishCaptureAttempt(attemptId, null, finishReason)
                    return@withContext null
                }
            }
        } catch (e: Exception) {
            finishReason = "${e.javaClass.simpleName}:${e.message}"
            traceCaptureRuntime(
                "CAPTURE_EXCEPTION attemptId=$attemptId type=${e.javaClass.name} " +
                    "message=${e.message ?: "no_message"}"
            )
            _captureContractError.value = e.message ?: "Capture contract failed."
            Log.e(tag, "Fatale fout in de Capture Router", e)
            // The runner creates the public shot folder only after shutter-time admission. If a
            // synchronous exception occurs after that point but before DiagnosticPayload exists,
            // preserve the real terminal stage instead of leaving the folder permanently at
            // "Waiting for capture diagnostics". The logger itself verifies that this attempt
            // still owns the active folder, so an exception before startNewShot() cannot corrupt
            // diagnostics from an older/newer shutter.
            runCatching {
                shotLogger.finalizeFailureWithPublicDiagnostics(
                    attemptId = "${activeLens.id}-$userShutterTimestampNs",
                    stage = "CAPTURE_ROUTER_EXCEPTION",
                    exception = e
                )
            }.onFailure { diagnosticsFailure ->
                Log.e(tag, "Failed to publish terminal capture diagnostics", diagnosticsFailure)
            }
            finishCaptureAttempt(attemptId, null, finishReason)
            null
        } finally {
            preleasedProvisionalNearZslAnchor?.let { provisional ->
                traceCaptureRuntime(
                    "SHUTTER_ENTRY_ANCHOR_RELEASE_SCOPE attemptId=$attemptId frameVersion=${provisional.frameVersion}"
                )
                provisional.lease.release()
                preleasedProvisionalNearZslAnchor = null
            }
            preleasedSingleAnchor?.let { pinned ->
                traceCaptureRuntime(
                    "SINGLE_SHUTTER_ANCHOR_RELEASE_SCOPE attemptId=$attemptId " +
                        "temporalClass=$singleAnchorTemporalClass frameVersion=${pinned.frameVersion}"
                )
                pinned.lease.release()
                preleasedSingleAnchor = null
            }
            releaseSelectionExposureConstraintAfterCapture(attemptId)
            // Re-apply the live repeating control state only after shutter-time selection authority
            // is unfrozen. This prevents a post-press AE/flicker update from retroactively making
            // the frame that existed at shutter time ineligible.
            backgroundHandler?.post { updatePreviewRepeatingRequest() }
            preleasedNormalMultiAnchor?.let { orphanLease ->
                traceCaptureRuntime(
                    "MULTI_SHUTTER_ANCHOR_RELEASE_SCOPE attemptId=$attemptId " +
                        "frameVersion=${orphanLease.frameVersion}"
                )
                orphanLease.lease.release()
                preleasedNormalMultiAnchor = null
            }
            // Defensive lifecycle invariant: once executeCapture() leaves its synchronous admission
            // scope, this attempt may either have transferred ownership to async work or be terminal.
            // It must never keep the acquisition slot merely because a newly added early-return path
            // forgot explicit finalization.
            if (captureAttempts.ownsAcquisition(attemptId)) {
                val orphanReason = "capture_scope_exited_without_terminal:$finishReason"
                traceCaptureRuntime(
                    "CAPTURE_ORPHAN_FINALIZE attemptId=$attemptId reason=$orphanReason"
                )
                finishCaptureAttempt(attemptId, null, orphanReason)
            }
            if (captureAttempts.snapshot.inFlightImageCount != 0) {
                Log.w(tag, "Capture finalized while ImageReader callbacks are still closing images: ${captureAttempts.snapshot.inFlightImageCount}")
            }
        }
    }

    // ========================================================
    // HARDWARE CONFIG TO NATIVE BRIDGE
    // ========================================================
    private fun pushHardwareConfigToNative(lensId: String) {
        // The frozen persisted colour-profile set must be installed before the first native RAW
        // render. Retry here because this call site already implies the native ISP is available.
        RawCameraColorProfileRepository.ensureSessionProfilesInstalled()
        // Warm native RAM config for preview/session startup. Capture runners still perform
        // their own deterministic push immediately before processing.
        sessionTransitionScope.launch {
            try {
                val settingsRepo = SettingsRepository(context)
                val resolved = settingsRepo.readLensHardwareSettingsSnapshot(lensId)
                val pushed = ImageUtils.updateHardwareConfigNative(resolved)
                Log.i(
                    tag,
                    "Warm lens hardware config push lens=$lensId pushed=$pushed fingerprint=${resolved.fingerprint()}"
                )
            } catch (e: Exception) {
                Log.e(tag, "Failed to warm-push hardware config to native for lens: $lensId", e)
            }
        }
    }

    fun getLiveDiagnosticsSnapshot(): Map<String, Any?> {
        val identity = synchronized(pipelineLock) { activePipelineIdentity }
        val result = lastCaptureResult
        return mapOf(
            "viewfinderStreamSetting" to viewfinderStreamSetting.persistedValue,
            "effectiveViewfinderSource" to effectiveViewfinderSource.name,
            "targetViewfinderSource" to targetViewfinderSource.name,
            "pipelineGeneration" to pipelineGeneration,
            "rawPreviewConfiguredGeneration" to rawPreviewConfiguredGeneration,
            "currentMeteringStyle" to currentMeteringStyle,
            "activeTapAeRegion" to activeTapAeRegion?.toString(),
            "requestedManualIso" to requestedManualIso,
            "requestedManualExposureNs" to requestedManualExposureNs,
            "lastMeteringPlanSummary" to lastMeteringPlanSummary,
            "lastExposurePlanSummary" to lastExposurePlanSummary,
            "measuredSensitivityIso" to result?.get(CaptureResult.SENSOR_SENSITIVITY),
            "measuredExposureTimeNs" to result?.get(CaptureResult.SENSOR_EXPOSURE_TIME),
            "measuredAeMode" to result?.get(CaptureResult.CONTROL_AE_MODE),
            "measuredAeState" to result?.get(CaptureResult.CONTROL_AE_STATE),
            "measuredAeRegions" to result?.get(CaptureResult.CONTROL_AE_REGIONS)?.map { it.toString() },
            "defaultRawMotionTruth" to latestDefaultRawMotion?.summary(),
            "defaultRawExposureRealizationTruth" to latestDefaultRawExposureTruth?.summary(),
            "defaultRawExposureRoute" to latestDefaultRawExposureTruth?.route?.name,
            "defaultRawRequestedExposureNs" to latestDefaultRawExposureTruth?.requestedExposureNs,
            "defaultRawActualExposureNs" to latestDefaultRawExposureTruth?.actualExposureNs,
            "defaultRawRequestedIso" to latestDefaultRawExposureTruth?.requestedIso,
            "defaultRawActualIso" to latestDefaultRawExposureTruth?.actualIso,
            "defaultRawExposureTruthStatus" to latestDefaultRawExposureTruth?.status,
            "defaultRawAllocationReady" to defaultRawAllocationReady,
            "defaultRawPhotometricConverged" to
                (latestDefaultRawPhotometricConvergence?.photometricConverged ?: false),
            "defaultRawPhotometricConvergence" to latestDefaultRawPhotometricConvergence?.summary(),
            "defaultRawTargetLuma" to latestDefaultRawPhotometricConvergence?.targetLuma,
            "defaultRawObservedLuma" to latestDefaultRawPhotometricConvergence?.observedLuma,
            "defaultRawExposureErrorEv" to latestDefaultRawPhotometricConvergence?.exposureErrorEv,
            "defaultRawLastFeedbackCorrectionEv" to defaultRawShutterFallbackPlan?.feedbackCorrectionEv,
            "defaultRawMotionReallocationEv" to defaultRawShutterFallbackPlan?.motionReallocationEv,
            "defaultRawMeteringFreshness" to latestDefaultRawMetering?.freshness?.name,
            "defaultRawMeteringAgeNs" to latestDefaultRawMetering?.ageNs,
            "defaultRawIdealExposureProduct" to latestDefaultRawExposureTarget?.ideal?.idealExposureProduct,
            "defaultRawFinalExposureProduct" to latestDefaultRawExposureTarget?.finalExposureProduct,
            "defaultRawHighlightProtectionEv" to latestDefaultRawExposureTarget?.highlightProtectionEv,
            "defaultRawAllocatedExposureNs" to latestDefaultRawExposureAllocation?.exposureTimeNs,
            "defaultRawAllocatedIso" to latestDefaultRawExposureAllocation?.sensitivityIso,
            "defaultRawAllocationLimit" to latestDefaultRawExposureAllocation?.limitingConstraint,
            "defaultRawFramesSinceExposureRequest" to defaultRawFramesSinceExposureRequest,
            "defaultRawTimeSinceLastMeaningfulSceneChangeNs" to
                latestDefaultRawPhotometricConvergence?.timeSinceLastMeaningfulSceneChangeNs,
            "defaultRawApi36PriorityAllowed" to defaultRawApi36AuthorityTracker.isAllowed(pipelineGeneration),
            "defaultRawApi36PriorityTrusted" to defaultRawApi36AuthorityTracker.isTrusted(pipelineGeneration),
            "defaultRawFrameSelectionExposureConstraint" to
                ringBuffer.selectionExposureConstraintSnapshot().summary(),
            "resolvedAntibandingMode" to activeResolvedAntibandingMode,
            "defaultRawFlickerConstraint" to resolveDefaultRawFlickerConstraint().let { constraint ->
                "frequency=${constraint.frequency};periodNs=${constraint.periodNs ?: "unresolved"};" +
                    "fallback=${constraint.fallbackActive};source=${constraint.source}"
            },
            "defaultRawFlickerStableSnapshot" to latestRawFlickerSnapshot.toString(),
            "measuredSceneFlicker" to result?.get(CaptureResult.STATISTICS_SCENE_FLICKER),
            "rawCadenceReport" to RawPreviewCadenceDiagnostics.latestReport(),
            "rawActivationReport" to com.bncam.core.debug.RawPreviewFirstActivationTrace.latestReport(),
            "displayedViewProvenance" to com.bncam.ui.screens.capture.FocusPeakingView.getProvenanceSummary()
        )
    }
}
