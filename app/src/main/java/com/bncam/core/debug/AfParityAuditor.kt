package com.bncam.core.debug

import android.graphics.Rect
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.MeteringRectangle
import android.os.Build
import android.util.Log
import com.bncam.core.quality.FocusMetrics
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Global Autofocus Ownership & Sensor Parity Auditor for BnCam.
 *
 * Audits AF capability classification, actuator ownership transitions, request provenance,
 * logical vs physical AF result parity, tap transactions, explicit manual focus writes into
 * auto-AF requests, and focus confidence correlation across all sensors without modifying production AF behavior.
 */
object AfParityAuditor {
    private const val TAG = "AfParityAudit"

    private val resultAuditTriggered = AtomicBoolean(false)
    private var lastResultFrameNumber: Long = -1L

    @Volatile
    private var currentAfOwner: String = "INITIAL_UNSET"

    fun resetAuditState() {
        resultAuditTriggered.set(false)
        lastResultFrameNumber = -1L
        currentAfOwner = "INITIAL_UNSET"
    }

    fun formatValue(value: Any?): String {
        if (value == null) return "null"
        return when (value) {
            is ByteArray -> value.joinToString(prefix = "[", postfix = "]")
            is ShortArray -> value.joinToString(prefix = "[", postfix = "]")
            is IntArray -> value.joinToString(prefix = "[", postfix = "]")
            is LongArray -> value.joinToString(prefix = "[", postfix = "]")
            is FloatArray -> value.joinToString(prefix = "[", postfix = "]")
            is DoubleArray -> value.joinToString(prefix = "[", postfix = "]")
            is BooleanArray -> value.joinToString(prefix = "[", postfix = "]")
            is Array<*> -> value.joinToString(prefix = "[", postfix = "]") { formatValue(it) }
            else -> value.toString()
        }
    }

    private fun afModeName(mode: Int?): String = when (mode) {
        CaptureRequest.CONTROL_AF_MODE_OFF -> "OFF(0)"
        CaptureRequest.CONTROL_AF_MODE_AUTO -> "AUTO(1)"
        CaptureRequest.CONTROL_AF_MODE_MACRO -> "MACRO(2)"
        CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO -> "CONTINUOUS_VIDEO(3)"
        CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE -> "CONTINUOUS_PICTURE(4)"
        CaptureRequest.CONTROL_AF_MODE_EDOF -> "EDOF(5)"
        null -> "NULL"
        else -> "VENDOR($mode)"
    }

    private fun afStateName(state: Int?): String = when (state) {
        CaptureResult.CONTROL_AF_STATE_INACTIVE -> "INACTIVE(0)"
        CaptureResult.CONTROL_AF_STATE_PASSIVE_SCAN -> "PASSIVE_SCAN(1)"
        CaptureResult.CONTROL_AF_STATE_PASSIVE_FOCUSED -> "PASSIVE_FOCUSED(2)"
        CaptureResult.CONTROL_AF_STATE_ACTIVE_SCAN -> "ACTIVE_SCAN(3)"
        CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED -> "FOCUSED_LOCKED(4)"
        CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED -> "NOT_FOCUSED_LOCKED(5)"
        CaptureResult.CONTROL_AF_STATE_PASSIVE_UNFOCUSED -> "PASSIVE_UNFOCUSED(6)"
        null -> "NULL"
        else -> "VENDOR($state)"
    }

    private fun afTriggerName(trigger: Int?): String = when (trigger) {
        CaptureRequest.CONTROL_AF_TRIGGER_IDLE -> "IDLE(0)"
        CaptureRequest.CONTROL_AF_TRIGGER_START -> "START(1)"
        CaptureRequest.CONTROL_AF_TRIGGER_CANCEL -> "CANCEL(2)"
        null -> "NULL"
        else -> "UNKNOWN($trigger)"
    }

    private fun lensStateName(state: Int?): String = when (state) {
        CaptureResult.LENS_STATE_STATIONARY -> "STATIONARY(0)"
        CaptureResult.LENS_STATE_MOVING -> "MOVING(1)"
        null -> "NULL"
        else -> "UNKNOWN($state)"
    }

    /**
     * Audit Step 1 — Route AF Capability & Classification Audit.
     * Evaluates whether current route is direct physical, logical, or logical->physical,
     * and whether sensor supports AF or is fixed-focus. Fixed-focus sensors are classified
     * and excluded from AF testing.
     */
    fun auditRouteAfCapability(
        cameraManager: CameraManager,
        logicalCameraId: String,
        physicalCameraId: String?
    ) {
        runCatching {
            resetAuditState()
            val targetId = physicalCameraId ?: logicalCameraId
            val chars = cameraManager.getCameraCharacteristics(targetId)

            val afModes = chars.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)?.toList().orEmpty()
            val minFocusDistance = chars.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
            val maxAfRegions = chars.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF) ?: 0
            val isAfSupported = afModes.any { it != CameraCharacteristics.CONTROL_AF_MODE_OFF } && minFocusDistance > 0f

            val routeType = when {
                physicalCameraId != null -> "LOGICAL_TO_PHYSICAL ($logicalCameraId -> $physicalCameraId)"
                cameraManager.cameraIdList.contains(logicalCameraId) -> "DIRECT_PHYSICAL_OR_LOGICAL ($logicalCameraId)"
                else -> "HIDDEN_PHYSICAL ($logicalCameraId)"
            }

            DeviceTelemetryLogger.logEvent(
                "AF_AUDIT_ROUTE_CAPABILITY",
                "routeType=$routeType logical=$logicalCameraId physical=${physicalCameraId ?: "none"} " +
                    "targetId=$targetId isAfSupported=$isAfSupported minFocusDistanceDiopters=$minFocusDistance " +
                    "maxAfRegions=$maxAfRegions availableAfModes=${afModes.map { afModeName(it) }} " +
                    "note=${if (!isAfSupported) "FIXED_FOCUS_SENSOR: Excluded from AF state machine" else "AF_CAPABLE_SENSOR"}"
            )
        }.onFailure { t ->
            Log.w(TAG, "Route AF capability audit failed", t)
        }
    }

    /**
     * Audit Actuator Ownership Transition.
     * Tracks subsystem transitions: Continuous AF, Tap AF, Focus Lock, MicroRefiner, Predictive AF, Watchdog Restore.
     */
    @Synchronized
    fun logOwnershipTransition(
        newOwner: String,
        requestedAction: String,
        afState: Int?,
        lensState: Int?,
        focusDistance: Float?,
        timestampNs: Long
    ) {
        val previousOwner = currentAfOwner
        currentAfOwner = newOwner

        DeviceTelemetryLogger.logEvent(
            "AF_AUDIT_OWNERSHIP_TRANSITION",
            "newOwner=$newOwner previousOwner=$previousOwner requestedAction=$requestedAction " +
                "afState=${afStateName(afState)} lensState=${lensStateName(lensState)} " +
                "focusDistance=$focusDistance timestampNs=$timestampNs"
        )
    }

    /**
     * Audit AF Request Provenance & Explicit Manual Distance Write Detection.
     * Explicitly distinguishes:
     * - Value merely present/read in request/result
     * - Value EXPLICITLY written by BnCam into an automatic-AF (AF_MODE != OFF) request.
     */
    fun auditAfRequest(
        logicalCameraId: String,
        physicalCameraId: String?,
        request: CaptureRequest,
        reasonTag: String,
        wasFocusDistanceExplicitlySetByBnCam: Boolean = false
    ) {
        runCatching {
            val afMode = request.get(CaptureRequest.CONTROL_AF_MODE)
            val afTrigger = request.get(CaptureRequest.CONTROL_AF_TRIGGER)
            val afRegions = request.get(CaptureRequest.CONTROL_AF_REGIONS)
            val focusDistance = request.get(CaptureRequest.LENS_FOCUS_DISTANCE)
            val cropRegion = request.get(CaptureRequest.SCALER_CROP_REGION)

            val regionsStr = afRegions?.joinToString(", ") { r ->
                "rect=[${r.rect.left},${r.rect.top},${r.rect.right},${r.rect.bottom}] weight=${r.meteringWeight}"
            } ?: "null/none"

            // Explicit manual leakage check: AF_MODE != OFF AND BnCam explicitly set LENS_FOCUS_DISTANCE into request
            val isExplicitManualLeakage = afMode != CaptureRequest.CONTROL_AF_MODE_OFF &&
                wasFocusDistanceExplicitlySetByBnCam &&
                focusDistance != null && focusDistance > 0f

            DeviceTelemetryLogger.logEvent(
                "AF_AUDIT_REQUEST",
                "reason=$reasonTag activeOwner=$currentAfOwner logical=$logicalCameraId physical=${physicalCameraId ?: "none"} " +
                    "afMode=${afModeName(afMode)} afTrigger=${afTriggerName(afTrigger)} " +
                    "focusDistance=$focusDistance explicitlySetByBnCam=$wasFocusDistanceExplicitlySetByBnCam " +
                    "afRegions=[$regionsStr] crop=$cropRegion " +
                    "explicitManualLeakageDetected=$isExplicitManualLeakage"
            )

            if (isExplicitManualLeakage) {
                DeviceTelemetryLogger.logEvent(
                    "AF_AUDIT_MANUAL_LEAKAGE_WARNING",
                    "MANUAL_LEAKAGE: BnCam explicitly wrote LENS_FOCUS_DISTANCE=$focusDistance into a request with automatic AF mode ${afModeName(afMode)} (reason=$reasonTag, owner=$currentAfOwner)"
                )
            }
        }.onFailure { t ->
            Log.w(TAG, "AF request audit failed", t)
        }
    }

    /**
     * Audit Tap Transaction & Coordinate Mapping.
     */
    fun auditTapTransaction(
        logicalCameraId: String,
        physicalCameraId: String?,
        touchPctX: Float,
        touchPctY: Float,
        mappedRegion: MeteringRectangle,
        cropBounds: Rect,
        coordinateSpaceLabel: String
    ) {
        runCatching {
            logOwnershipTransition(
                newOwner = "TAP_AF",
                requestedAction = "TAP_TRIGGER_START",
                afState = null,
                lensState = null,
                focusDistance = null,
                timestampNs = System.nanoTime()
            )

            DeviceTelemetryLogger.logEvent(
                "AF_AUDIT_TAP_TRANSACTION",
                "logical=$logicalCameraId physical=${physicalCameraId ?: "none"} " +
                    "touch=[$touchPctX, $touchPctY] mappedRect=[${mappedRegion.rect.left},${mappedRegion.rect.top},${mappedRegion.rect.right},${mappedRegion.rect.bottom}] " +
                    "weight=${mappedRegion.meteringWeight} crop=$cropBounds info={$coordinateSpaceLabel}"
            )
        }.onFailure { t ->
            Log.w(TAG, "Tap transaction audit failed", t)
        }
    }

    /**
     * Audit Logical vs Physical CaptureResult AF Parity & Focus Confidence Correlation.
     */
    fun auditCaptureResultAfParity(
        logicalCameraId: String,
        physicalCameraId: String?,
        result: TotalCaptureResult,
        metrics: FocusMetrics? = null
    ) {
        runCatching {
            val frameNo = result.frameNumber
            if (!resultAuditTriggered.compareAndSet(false, true) && (frameNo - lastResultFrameNumber) < 120) {
                return
            }
            lastResultFrameNumber = frameNo

            val logAfMode = result.get(CaptureResult.CONTROL_AF_MODE)
            val logAfState = result.get(CaptureResult.CONTROL_AF_STATE)
            val logAfRegions = result.get(CaptureResult.CONTROL_AF_REGIONS)
            val logLensState = result.get(CaptureResult.LENS_STATE)
            val logLensDistance = result.get(CaptureResult.LENS_FOCUS_DISTANCE)
            val logTimestamp = result.get(CaptureResult.SENSOR_TIMESTAMP) ?: 0L

            val logRegionsStr = logAfRegions?.joinToString(", ") { r ->
                "rect=[${r.rect.left},${r.rect.top},${r.rect.right},${r.rect.bottom}] weight=${r.meteringWeight}"
            } ?: "null"

            // Physical result comparison if physicalCameraId is active
            var physAfMode: Int? = null
            var physAfState: Int? = null
            var physLensState: Int? = null
            var physLensDistance: Float? = null
            var physTimestamp: Long? = null
            var hasPhysicalDiscrepancy = false

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && physicalCameraId != null) {
                val physResult = result.physicalCameraResults[physicalCameraId]
                if (physResult != null) {
                    physAfMode = physResult.get(CaptureResult.CONTROL_AF_MODE)
                    physAfState = physResult.get(CaptureResult.CONTROL_AF_STATE)
                    physLensState = physResult.get(CaptureResult.LENS_STATE)
                    physLensDistance = physResult.get(CaptureResult.LENS_FOCUS_DISTANCE)
                    physTimestamp = physResult.get(CaptureResult.SENSOR_TIMESTAMP)

                    hasPhysicalDiscrepancy = logAfMode != physAfMode ||
                        logAfState != physAfState ||
                        logLensState != physLensState ||
                        (logLensDistance != null && physLensDistance != null && Math.abs(logLensDistance - physLensDistance) > 0.05f)
                }
            }

            // Image Focus Confidence Correlation Check
            val isCameraFocused = logAfState == CaptureResult.CONTROL_AF_STATE_PASSIVE_FOCUSED ||
                logAfState == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED
            val isFalseConfidence = isCameraFocused && metrics != null && metrics.confidenceState == com.bncam.core.quality.FocusConfidenceState.CONFIDENT_SOFT

            DeviceTelemetryLogger.logEvent(
                "AF_AUDIT_RESULT_PARITY",
                "frame=$frameNo activeOwner=$currentAfOwner logical=$logicalCameraId physical=${physicalCameraId ?: "none"} " +
                    "logicalAF={mode=${afModeName(logAfMode)} state=${afStateName(logAfState)} lensState=${lensStateName(logLensState)} diopters=$logLensDistance timestamp=$logTimestamp regions=[$logRegionsStr]} " +
                    (if (physicalCameraId != null) "physicalAF={mode=${afModeName(physAfMode)} state=${afStateName(physAfState)} lensState=${lensStateName(physLensState)} diopters=$physLensDistance timestamp=$physTimestamp} " else "") +
                    "physicalDiscrepancy=$hasPhysicalDiscrepancy " +
                    (if (metrics != null) "imageMetrics={score=${metrics.focusScore} confidence=${metrics.focusConfidence} state=${metrics.confidenceState} subjectRoi=${metrics.subjectRoiUsed}} " else "") +
                    "falseFocusConfidenceDetected=$isFalseConfidence"
            )

            if (isFalseConfidence) {
                DeviceTelemetryLogger.logEvent(
                    "AF_AUDIT_FALSE_CONFIDENCE_WARNING",
                    "frame=$frameNo Camera2 reported focused state ${afStateName(logAfState)} but image metrics evaluated as CONFIDENT_SOFT (score=${metrics?.focusScore}, confidence=${metrics?.focusConfidence}). Lens diopters=$logLensDistance"
                )
            }
        }.onFailure { t ->
            Log.w(TAG, "AF CaptureResult parity audit failed", t)
        }
    }
}
