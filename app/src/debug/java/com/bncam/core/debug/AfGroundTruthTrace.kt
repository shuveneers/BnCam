package com.bncam.core.debug

import android.content.Context
import android.graphics.Rect
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.MeteringRectangle
import android.os.SystemClock
import android.util.Range
import android.util.Size
import com.bncam.core.capture.CameraRequestTag
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.ArrayDeque
import java.util.IdentityHashMap
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.hypot

/**
 * Debug-source-set-only Camera2 AF transaction tracer routed through unified diagnostics.
 *
 * It deliberately observes production builders and results without choosing a mode, region,
 * trigger, focus distance, crop, zoom value, or request lifetime.
 */
object AfGroundTruthTrace {
    private const val PRE_TAP_NS = 500_000_000L
    private const val POST_TAP_NS = 2_000_000_000L
    private const val RESULT_BUFFER_NS = 750_000_000L

    private data class ActiveTransaction(
        val id: String,
        val tapMonotonicNs: Long,
        val endMonotonicNs: Long,
        val uiTap: AfGroundTruthUiTap
    )

    private data class PassiveObservation(
        val id: String,
        val endMonotonicNs: Long
    )

    private data class BufferedResult(
        val monotonicNs: Long,
        val fields: Map<String, Any?>
    )

    private data class PendingWriter(
        val monotonicNs: Long,
        val transactionId: String,
        val sourceFunction: String,
        val reason: String,
        val snapshot: Map<String, Any?>
    )

    private val stateLock = Any()
    private val transactionCounter = AtomicLong(0L)
    private val traceExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "AfGroundTruthTrace").apply { isDaemon = true }
    }
    private val bufferedResults = ArrayDeque<BufferedResult>()
    private val pendingWriters = IdentityHashMap<CaptureRequest.Builder, MutableList<PendingWriter>>()
    private val mappingContexts = LinkedHashMap<String, AfGroundTruthMappingContext>()

    @Volatile private var initialized = false
    @Volatile private var nextTransactionId: String? = null
    @Volatile private var activeTransaction: ActiveTransaction? = null
    @Volatile private var passiveObservation: PassiveObservation? = null

    private val mutableOverlay = MutableStateFlow(AfGroundTruthOverlayState())
    val overlay: StateFlow<AfGroundTruthOverlayState> = mutableOverlay

    fun initialize(context: Context) {
        if (initialized) return
        synchronized(stateLock) {
            if (!initialized) {
                DiagnosticsAggregator.initialize(context.applicationContext)
                initialized = true
            }
        }
    }

    fun clear(context: Context) {
        initialize(context)
        synchronized(stateLock) {
            bufferedResults.clear()
            pendingWriters.clear()
            mappingContexts.clear()
            nextTransactionId = null
            activeTransaction = null
            passiveObservation = null
            mutableOverlay.value = AfGroundTruthOverlayState()
        }
        DiagnosticsAggregator.record(
            stream = DiagnosticsAggregator.Stream.CAMERA,
            scope = "SESSION",
            section = "AF GROUND TRUTH",
            content = "traceStateReset=true"
        )
    }

    fun setNextTransactionId(transactionId: String) {
        val sanitized = transactionId.trim().replace(Regex("[^A-Za-z0-9_.:-]"), "_")
        if (sanitized.isNotEmpty()) nextTransactionId = sanitized
    }

    fun beginPassiveAeObservation(transactionId: String, durationMs: Long) {
        val id = transactionId.trim().replace(Regex("[^A-Za-z0-9_.:-]"), "_")
            .ifBlank { "AE_PASSIVE_${transactionCounter.incrementAndGet()}" }
        val now = SystemClock.elapsedRealtimeNanos()
        val boundedDurationMs = durationMs.coerceIn(1_000L, 15_000L)
        synchronized(stateLock) {
            passiveObservation = PassiveObservation(
                id = id,
                endMonotonicNs = now + boundedDurationMs * 1_000_000L
            )
        }
        writeRecord(
            transactionId = id,
            event = "passive_ae_observation_started",
            monotonicNs = now,
            fields = linkedMapOf("durationMs" to boundedDurationMs)
        )
    }

    fun recordCameraRoute(
        stage: String,
        selectedCameraId: String,
        directOpenResult: String,
        chosenRoute: String,
        logicalCameraId: String,
        physicalChildCameraId: String?,
        openedCameraDeviceId: String?,
        detail: String? = null
    ) {
        writeRecord(
            transactionId = "CAMERA_ROUTE_$selectedCameraId",
            event = "camera_route",
            fields = linkedMapOf(
                "stage" to stage,
                "selectedCameraId" to selectedCameraId,
                "directOpenResult" to directOpenResult,
                "chosenRoute" to chosenRoute,
                "logicalCameraId" to logicalCameraId,
                "physicalChildCameraId" to physicalChildCameraId,
                "openedCameraDeviceId" to openedCameraDeviceId,
                "detail" to detail
            )
        )
    }

    fun beginTap(tap: AfGroundTruthUiTap): String {
        val now = SystemClock.elapsedRealtimeNanos()
        val id = synchronized(stateLock) {
            val requested = nextTransactionId
            nextTransactionId = null
            val resolved = requested ?: String.format(
                Locale.US,
                "BNCAM_TAP_%04d",
                transactionCounter.incrementAndGet()
            )
            activeTransaction = ActiveTransaction(
                id = resolved,
                tapMonotonicNs = now,
                endMonotonicNs = now + POST_TAP_NS,
                uiTap = tap
            )
            resolved
        }

        writeRecord(
            transactionId = id,
            event = "user_tap",
            monotonicNs = now,
            fields = linkedMapOf(
                "inputSource" to tap.inputSource,
                "rawTouch" to mapOf("x" to tap.rawTouchX, "y" to tap.rawTouchY),
                "viewSize" to mapOf("width" to tap.viewWidth, "height" to tap.viewHeight),
                "displayNormalized" to mapOf(
                    "x" to tap.displayNormalizedX,
                    "y" to tap.displayNormalizedY
                ),
                "mapperInputNormalized" to mapOf(
                    "x" to tap.mapperInputNormalizedX,
                    "y" to tap.mapperInputNormalizedY
                ),
                "displayRotationDegrees" to tap.displayRotationDegrees,
                "previewOrientationCorrectionDegrees" to tap.previewOrientationCorrectionDegrees,
                "previewMirrored" to tap.previewMirrored
            )
        )

        val preTap = synchronized(stateLock) {
            pruneBufferedResultsLocked(now)
            bufferedResults.filter { it.monotonicNs >= now - PRE_TAP_NS }.toList()
        }
        preTap.forEach { buffered ->
            writeRecord(
                transactionId = id,
                event = "capture_result",
                monotonicNs = buffered.monotonicNs,
                fields = buffered.fields + ("transactionPhase" to "PRE_TAP_500MS")
            )
        }
        return id
    }

    fun recordTapMapping(
        mapping: AfGroundTruthMappingContext,
        selectedLensId: String,
        openedCameraDeviceId: String,
        logicalCameraId: String,
        activePhysicalCameraId: String?,
        geometryCameraId: String,
        requestedPhysicalCameraId: String?,
        previewOutputPhysicalCameraId: String?,
        imageReaderOutputPhysicalCameraId: String?,
        standalonePhysicalCameraListed: Boolean?,
        activePipeline: Map<String, Any?>,
        logicalCharacteristics: CameraCharacteristics,
        physicalCharacteristics: CameraCharacteristics?
    ) {
        synchronized(stateLock) {
            mappingContexts[mapping.transactionId] = mapping
            while (mappingContexts.size > 16) {
                mappingContexts.remove(mappingContexts.keys.first())
            }
        }

        val inversePoint = sensorNormalizedToDisplayNormalized(
            sensorX = mapping.mapperSensorNormalizedX,
            sensorY = mapping.mapperSensorNormalizedY,
            mapping = mapping
        )
        val inverseScreenX = inversePoint.first * mapping.uiTap.viewWidth
        val inverseScreenY = inversePoint.second * mapping.uiTap.viewHeight
        val roundTripError = hypot(
            (inverseScreenX - mapping.uiTap.rawTouchX).toDouble(),
            (inverseScreenY - mapping.uiTap.rawTouchY).toDouble()
        )

        writeRecord(
            transactionId = mapping.transactionId,
            event = "tap_mapping_and_route",
            fields = linkedMapOf(
                "openedCameraDeviceId" to openedCameraDeviceId,
                "logicalCameraId" to logicalCameraId,
                "requestedPhysicalCameraId" to requestedPhysicalCameraId,
                "activePhysicalCameraId" to activePhysicalCameraId,
                "selectedBnCamLensId" to selectedLensId,
                "geometryCameraId" to geometryCameraId,
                "previewOutputPhysicalCameraId" to previewOutputPhysicalCameraId,
                "imageReaderOutputPhysicalCameraId" to imageReaderOutputPhysicalCameraId,
                "standalonePhysicalCameraListed" to standalonePhysicalCameraListed,
                "activePipelineIdentity" to activePipeline,
                "previewSurfaceDimensions" to mapOf(
                    "width" to mapping.previewSurfaceWidth,
                    "height" to mapping.previewSurfaceHeight
                ),
                "analysisOrViewfinderBufferDimensions" to mapOf(
                    "width" to mapping.analysisBufferWidth,
                    "height" to mapping.analysisBufferHeight
                ),
                "currentZoomRatio" to mapping.requestedZoomRatio,
                "scalerCropRegion" to mapping.requestedCropRegion,
                "logicalCharacteristics" to characteristicSnapshot(logicalCharacteristics),
                "activePhysicalCharacteristics" to physicalCharacteristics?.let(::characteristicSnapshot),
                "transform" to linkedMapOf(
                    "screen" to mapOf(
                        "xPx" to mapping.uiTap.rawTouchX,
                        "yPx" to mapping.uiTap.rawTouchY,
                        "widthPx" to mapping.uiTap.viewWidth,
                        "heightPx" to mapping.uiTap.viewHeight
                    ),
                    "viewfinderDisplayNormalized" to mapOf(
                        "x" to mapping.uiTap.displayNormalizedX,
                        "y" to mapping.uiTap.displayNormalizedY
                    ),
                    "viewfinderMapperInputNormalized" to mapOf(
                        "x" to mapping.uiTap.mapperInputNormalizedX,
                        "y" to mapping.uiTap.mapperInputNormalizedY
                    ),
                    "buffer" to mapOf(
                        "xPx" to mapping.uiTap.mapperInputNormalizedX * mapping.previewSurfaceWidth,
                        "yPx" to mapping.uiTap.mapperInputNormalizedY * mapping.previewSurfaceHeight,
                        "widthPx" to mapping.previewSurfaceWidth,
                        "heightPx" to mapping.previewSurfaceHeight
                    ),
                    "sensorNormalized" to mapOf(
                        "x" to mapping.mapperSensorNormalizedX,
                        "y" to mapping.mapperSensorNormalizedY
                    ),
                    "sensorVisibleBounds" to mapping.sensorVisibleBounds,
                    "sensorPointPx" to mapOf(
                        "x" to mapping.sensorVisibleBounds.left +
                            mapping.mapperSensorNormalizedX * mapping.sensorVisibleBounds.width(),
                        "y" to mapping.sensorVisibleBounds.top +
                            mapping.mapperSensorNormalizedY * mapping.sensorVisibleBounds.height()
                    ),
                    "displayRotationDegreesActual" to mapping.uiTap.displayRotationDegrees,
                    "displayRotationDegreesUsedByMapper" to mapping.mappingDisplayRotationDegrees,
                    "sensorOrientationDegrees" to mapping.sensorOrientationDegrees,
                    "lensFacingFront" to mapping.lensFacingFront,
                    "previewMirrored" to mapping.uiTap.previewMirrored,
                    "previewOrientationCorrectionDegrees" to
                        mapping.uiTap.previewOrientationCorrectionDegrees,
                    "inverseSensorToScreen" to mapOf(
                        "xPx" to inverseScreenX,
                        "yPx" to inverseScreenY
                    ),
                    "roundTripErrorPixels" to roundTripError
                )
            )
        )
    }

    fun recordWriter(builder: CaptureRequest.Builder, sourceFunction: String, reason: String) {
        val now = SystemClock.elapsedRealtimeNanos()
        val transaction = activeTransactionAt(now) ?: return
        val writer = PendingWriter(
            monotonicNs = now,
            transactionId = transaction.id,
            sourceFunction = sourceFunction,
            reason = reason,
            snapshot = requestSnapshot(builder)
        )
        synchronized(stateLock) {
            pendingWriters.getOrPut(builder) { mutableListOf() }.add(writer)
        }
    }

    fun recordSubmission(
        builder: CaptureRequest.Builder,
        request: CaptureRequest,
        requestSequenceNumber: Int,
        submissionType: String,
        reason: String
    ) {
        val now = SystemClock.elapsedRealtimeNanos()
        val transaction = activeTransactionAt(now) ?: return
        val writers = synchronized(stateLock) { pendingWriters.remove(builder).orEmpty() }
        writers.filter { it.transactionId == transaction.id }.forEach { writer ->
            writeRecord(
                transactionId = writer.transactionId,
                event = "request_writer",
                monotonicNs = writer.monotonicNs,
                fields = linkedMapOf(
                    "sourceFunction" to writer.sourceFunction,
                    "reason" to writer.reason,
                    "requestSequenceNumber" to requestSequenceNumber,
                    "submissionType" to submissionType,
                    "request" to writer.snapshot
                )
            )
        }

        val snapshot = requestSnapshot(request)
        writeRecord(
            transactionId = transaction.id,
            event = "request_submission",
            monotonicNs = now,
            fields = linkedMapOf(
                "reason" to reason,
                "submissionType" to submissionType,
                "requestSequenceNumber" to requestSequenceNumber,
                "request" to snapshot
            )
        )

        if (reason.startsWith("TOUCH_AF_") && reason != "TOUCH_AF_CANCEL") {
            val actualRegion = request.get(CaptureRequest.CONTROL_AF_REGIONS)?.firstOrNull()?.rect
            val mapping = synchronized(stateLock) { mappingContexts[transaction.id] }
            if (actualRegion != null && mapping != null) {
                publishSubmittedRegionOverlay(
                    transactionId = transaction.id,
                    mapping = mapping,
                    actualRegion = actualRegion,
                    requestSequenceNumber = requestSequenceNumber,
                    reason = reason
                )
            }
        }
    }

    fun recordCaptureResult(
        logicalCameraId: String,
        activePhysicalCameraId: String?,
        request: CaptureRequest,
        result: TotalCaptureResult
    ) {
        val now = SystemClock.elapsedRealtimeNanos()
        val physical = linkedMapOf<String, Any?>()
        result.physicalCameraResults.forEach { (cameraId, child) ->
            physical[cameraId] = captureResultSnapshot(child)
        }
        val fields = linkedMapOf<String, Any?>(
            "logicalCameraId" to logicalCameraId,
            "activePhysicalCameraId" to activePhysicalCameraId,
            "frameNumber" to result.frameNumber,
            "sensorTimestamp" to result.get(CaptureResult.SENSOR_TIMESTAMP),
            "requestProvenance" to (request.tag as? CameraRequestTag)?.let { tag ->
                linkedMapOf(
                    "pipelineGeneration" to tag.pipelineGeneration,
                    "controlRequestEpoch" to tag.controlRequestEpoch,
                    "submissionType" to tag.snapshot.submissionType.name,
                    "submissionReason" to tag.snapshot.submissionReason,
                    "submittedElapsedRealtimeNs" to tag.snapshot.submittedElapsedRealtimeNs,
                    "meteringPolicySummary" to tag.snapshot.meteringPolicySummary,
                    "exposurePolicySummary" to tag.snapshot.exposurePolicySummary
                )
            },
            "requestEcho" to requestSnapshot(request),
            "logicalResult" to captureResultSnapshot(result),
            "physicalCameraResults" to physical
        )

        var completedPassive: PassiveObservation? = null
        val observations = synchronized(stateLock) {
            bufferedResults.addLast(BufferedResult(now, fields))
            pruneBufferedResultsLocked(now)
            val activeTap = activeTransaction?.takeIf { now <= it.endMonotonicNs }
            val activePassive = passiveObservation?.takeIf { now <= it.endMonotonicNs }
            if (activePassive == null && passiveObservation != null) {
                completedPassive = passiveObservation
                passiveObservation = null
            }
            activeTap to activePassive
        }
        observations.first?.let { active ->
            writeRecord(
                transactionId = active.id,
                event = "capture_result",
                monotonicNs = now,
                fields = fields + ("transactionPhase" to "POST_TAP_2000MS")
            )
        }
        observations.second?.let { passive ->
            writeRecord(
                transactionId = passive.id,
                event = "capture_result",
                monotonicNs = now,
                fields = fields + ("transactionPhase" to "PASSIVE_AE")
            )
        }
        completedPassive?.let { passive ->
            writeRecord(
                transactionId = passive.id,
                event = "passive_ae_observation_completed",
                monotonicNs = now,
                fields = emptyMap()
            )
        }
    }

    private fun publishSubmittedRegionOverlay(
        transactionId: String,
        mapping: AfGroundTruthMappingContext,
        actualRegion: Rect,
        requestSequenceNumber: Int,
        reason: String
    ) {
        val corners = listOf(
            sensorPixelToScreen(actualRegion.left.toFloat(), actualRegion.top.toFloat(), mapping),
            sensorPixelToScreen(actualRegion.right.toFloat(), actualRegion.top.toFloat(), mapping),
            sensorPixelToScreen(actualRegion.left.toFloat(), actualRegion.bottom.toFloat(), mapping),
            sensorPixelToScreen(actualRegion.right.toFloat(), actualRegion.bottom.toFloat(), mapping)
        )
        val left = corners.minOf { it.first }
        val top = corners.minOf { it.second }
        val right = corners.maxOf { it.first }
        val bottom = corners.maxOf { it.second }
        val center = sensorPixelToScreen(
            actualRegion.exactCenterX(),
            actualRegion.exactCenterY(),
            mapping
        )
        val redBlueError = hypot(
            (center.first - mapping.uiTap.rawTouchX).toDouble(),
            (center.second - mapping.uiTap.rawTouchY).toDouble()
        )
        mutableOverlay.value = AfGroundTruthOverlayState(
            visible = true,
            transactionId = transactionId,
            touchX = mapping.uiTap.rawTouchX,
            touchY = mapping.uiTap.rawTouchY,
            regionLeft = left,
            regionTop = top,
            regionRight = right,
            regionBottom = bottom,
            regionCenterX = center.first,
            regionCenterY = center.second,
            requestSequenceNumber = requestSequenceNumber,
            submissionReason = reason
        )
        writeRecord(
            transactionId = transactionId,
            event = "submitted_region_overlay_projection",
            fields = linkedMapOf(
                "proofSource" to "ACTUAL_BUILT_CAPTURE_REQUEST",
                "requestSequenceNumber" to requestSequenceNumber,
                "submissionReason" to reason,
                "actualSubmittedAfRegionSensor" to actualRegion,
                "projectedRegionScreen" to mapOf(
                    "left" to left,
                    "top" to top,
                    "right" to right,
                    "bottom" to bottom
                ),
                "redTouchScreen" to mapOf(
                    "x" to mapping.uiTap.rawTouchX,
                    "y" to mapping.uiTap.rawTouchY
                ),
                "blueRegionCenterScreen" to mapOf("x" to center.first, "y" to center.second),
                "redBlueCenterErrorPixels" to redBlueError
            )
        )
    }

    private fun sensorPixelToScreen(
        sensorX: Float,
        sensorY: Float,
        mapping: AfGroundTruthMappingContext
    ): Pair<Float, Float> {
        val bounds = mapping.sensorVisibleBounds
        val sensorNormX = (sensorX - bounds.left) / bounds.width().coerceAtLeast(1).toFloat()
        val sensorNormY = (sensorY - bounds.top) / bounds.height().coerceAtLeast(1).toFloat()
        val displayNorm = sensorNormalizedToDisplayNormalized(sensorNormX, sensorNormY, mapping)
        return (displayNorm.first * mapping.uiTap.viewWidth) to
            (displayNorm.second * mapping.uiTap.viewHeight)
    }

    private fun sensorNormalizedToDisplayNormalized(
        sensorX: Float,
        sensorY: Float,
        mapping: AfGroundTruthMappingContext
    ): Pair<Float, Float> {
        val rotation = (
            mapping.sensorOrientationDegrees - mapping.mappingDisplayRotationDegrees + 360
            ) % 360
        var x: Float
        var y: Float
        when (rotation) {
            90 -> {
                x = 1f - sensorY
                y = sensorX
            }
            180 -> {
                x = 1f - sensorX
                y = 1f - sensorY
            }
            270 -> {
                x = sensorY
                y = 1f - sensorX
            }
            else -> {
                x = sensorX
                y = sensorY
            }
        }
        if (mapping.lensFacingFront) x = 1f - x
        return rotateNormalizedForDisplay(
            x,
            y,
            mapping.uiTap.previewOrientationCorrectionDegrees
        )
    }

    private fun rotateNormalizedForDisplay(x: Float, y: Float, clockwiseDegrees: Int): Pair<Float, Float> {
        val normalized = ((clockwiseDegrees % 360) + 360) % 360
        return when (normalized) {
            90 -> (1f - y) to x
            180 -> (1f - x) to (1f - y)
            270 -> y to (1f - x)
            else -> x to y
        }
    }

    private fun activeTransactionAt(monotonicNs: Long): ActiveTransaction? = synchronized(stateLock) {
        activeTransaction?.takeIf { monotonicNs <= it.endMonotonicNs }
    }

    private fun pruneBufferedResultsLocked(now: Long) {
        while (bufferedResults.isNotEmpty() &&
            now - bufferedResults.first().monotonicNs > RESULT_BUFFER_NS
        ) {
            bufferedResults.removeFirst()
        }
    }

    private fun characteristicSnapshot(chars: CameraCharacteristics): Map<String, Any?> = linkedMapOf(
        "SENSOR_INFO_PIXEL_ARRAY_SIZE" to chars.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE),
        "SENSOR_INFO_ACTIVE_ARRAY_SIZE" to chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE),
        "SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE" to
            chars.get(CameraCharacteristics.SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE),
        "SENSOR_ORIENTATION" to chars.get(CameraCharacteristics.SENSOR_ORIENTATION),
        "LENS_FACING" to chars.get(CameraCharacteristics.LENS_FACING),
        "LENS_INFO_AVAILABLE_FOCAL_LENGTHS" to
            chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS),
        "LENS_INFO_MINIMUM_FOCUS_DISTANCE" to
            chars.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE),
        "LENS_INFO_FOCUS_DISTANCE_CALIBRATION" to
            chars.get(CameraCharacteristics.LENS_INFO_FOCUS_DISTANCE_CALIBRATION),
        "CONTROL_AF_AVAILABLE_MODES" to chars.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES),
        "CONTROL_MAX_REGIONS_AF" to chars.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF),
        "CONTROL_ZOOM_RATIO_RANGE" to chars.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE),
        "SCALER_AVAILABLE_MAX_DIGITAL_ZOOM" to
            chars.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM)
    )

    private fun requestSnapshot(builder: CaptureRequest.Builder): Map<String, Any?> = linkedMapOf(
        "controlMode" to builder.get(CaptureRequest.CONTROL_MODE),
        "aeMode" to builder.get(CaptureRequest.CONTROL_AE_MODE),
        "aeLock" to builder.get(CaptureRequest.CONTROL_AE_LOCK),
        "aeState" to null,
        "aeExposureCompensation" to builder.get(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION),
        "aeAntibandingMode" to builder.get(CaptureRequest.CONTROL_AE_ANTIBANDING_MODE),
        "aeTargetFpsRange" to builder.get(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE),
        "awbMode" to builder.get(CaptureRequest.CONTROL_AWB_MODE),
        "awbLock" to builder.get(CaptureRequest.CONTROL_AWB_LOCK),
        "colorCorrectionMode" to builder.get(CaptureRequest.COLOR_CORRECTION_MODE),
        "colorCorrectionGains" to builder.get(CaptureRequest.COLOR_CORRECTION_GAINS),
        "colorCorrectionTransform" to builder.get(CaptureRequest.COLOR_CORRECTION_TRANSFORM),
        "tonemapMode" to builder.get(CaptureRequest.TONEMAP_MODE),
        "afMode" to builder.get(CaptureRequest.CONTROL_AF_MODE),
        "afTrigger" to builder.get(CaptureRequest.CONTROL_AF_TRIGGER),
        "afRegions" to builder.get(CaptureRequest.CONTROL_AF_REGIONS),
        "aeRegions" to builder.get(CaptureRequest.CONTROL_AE_REGIONS),
        "cropRegion" to builder.get(CaptureRequest.SCALER_CROP_REGION),
        "zoomRatio" to builder.get(CaptureRequest.CONTROL_ZOOM_RATIO),
        "lensFocusDistance" to builder.get(CaptureRequest.LENS_FOCUS_DISTANCE),
        "sensorExposureTimeNs" to builder.get(CaptureRequest.SENSOR_EXPOSURE_TIME),
        "sensorSensitivityIso" to builder.get(CaptureRequest.SENSOR_SENSITIVITY),
        "sensorFrameDurationNs" to builder.get(CaptureRequest.SENSOR_FRAME_DURATION)
    )

    private fun requestSnapshot(request: CaptureRequest): Map<String, Any?> = linkedMapOf(
        "controlMode" to request.get(CaptureRequest.CONTROL_MODE),
        "aeMode" to request.get(CaptureRequest.CONTROL_AE_MODE),
        "aeLock" to request.get(CaptureRequest.CONTROL_AE_LOCK),
        "aeState" to null,
        "aeExposureCompensation" to request.get(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION),
        "aeAntibandingMode" to request.get(CaptureRequest.CONTROL_AE_ANTIBANDING_MODE),
        "aeTargetFpsRange" to request.get(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE),
        "awbMode" to request.get(CaptureRequest.CONTROL_AWB_MODE),
        "awbLock" to request.get(CaptureRequest.CONTROL_AWB_LOCK),
        "colorCorrectionMode" to request.get(CaptureRequest.COLOR_CORRECTION_MODE),
        "colorCorrectionGains" to request.get(CaptureRequest.COLOR_CORRECTION_GAINS),
        "colorCorrectionTransform" to request.get(CaptureRequest.COLOR_CORRECTION_TRANSFORM),
        "tonemapMode" to request.get(CaptureRequest.TONEMAP_MODE),
        "afMode" to request.get(CaptureRequest.CONTROL_AF_MODE),
        "afTrigger" to request.get(CaptureRequest.CONTROL_AF_TRIGGER),
        "afRegions" to request.get(CaptureRequest.CONTROL_AF_REGIONS),
        "aeRegions" to request.get(CaptureRequest.CONTROL_AE_REGIONS),
        "cropRegion" to request.get(CaptureRequest.SCALER_CROP_REGION),
        "zoomRatio" to request.get(CaptureRequest.CONTROL_ZOOM_RATIO),
        "lensFocusDistance" to request.get(CaptureRequest.LENS_FOCUS_DISTANCE),
        "sensorExposureTimeNs" to request.get(CaptureRequest.SENSOR_EXPOSURE_TIME),
        "sensorSensitivityIso" to request.get(CaptureRequest.SENSOR_SENSITIVITY),
        "sensorFrameDurationNs" to request.get(CaptureRequest.SENSOR_FRAME_DURATION)
    )

    private fun captureResultSnapshot(result: CaptureResult): Map<String, Any?> = linkedMapOf(
        "sensorTimestamp" to result.get(CaptureResult.SENSOR_TIMESTAMP),
        "aeMode" to result.get(CaptureResult.CONTROL_AE_MODE),
        "aeLock" to result.get(CaptureResult.CONTROL_AE_LOCK),
        "aeState" to result.get(CaptureResult.CONTROL_AE_STATE),
        "aeExposureCompensation" to result.get(CaptureResult.CONTROL_AE_EXPOSURE_COMPENSATION),
        "aeRegions" to result.get(CaptureResult.CONTROL_AE_REGIONS),
        "aeAntibandingMode" to result.get(CaptureResult.CONTROL_AE_ANTIBANDING_MODE),
        "aeTargetFpsRange" to result.get(CaptureResult.CONTROL_AE_TARGET_FPS_RANGE),
        "awbMode" to result.get(CaptureResult.CONTROL_AWB_MODE),
        "awbLock" to result.get(CaptureResult.CONTROL_AWB_LOCK),
        "awbState" to result.get(CaptureResult.CONTROL_AWB_STATE),
        "colorCorrectionMode" to result.get(CaptureResult.COLOR_CORRECTION_MODE),
        "colorCorrectionGains" to result.get(CaptureResult.COLOR_CORRECTION_GAINS),
        "colorCorrectionTransform" to result.get(CaptureResult.COLOR_CORRECTION_TRANSFORM),
        "postRawSensitivityBoost" to result.get(CaptureResult.CONTROL_POST_RAW_SENSITIVITY_BOOST),
        "tonemapMode" to result.get(CaptureResult.TONEMAP_MODE),
        "lensOpticalStabilizationMode" to result.get(CaptureResult.LENS_OPTICAL_STABILIZATION_MODE),
        "videoStabilizationMode" to result.get(CaptureResult.CONTROL_VIDEO_STABILIZATION_MODE),
        "sceneFlicker" to result.get(CaptureResult.STATISTICS_SCENE_FLICKER),
        "sensorExposureTimeNs" to result.get(CaptureResult.SENSOR_EXPOSURE_TIME),
        "sensorSensitivityIso" to result.get(CaptureResult.SENSOR_SENSITIVITY),
        "sensorFrameDurationNs" to result.get(CaptureResult.SENSOR_FRAME_DURATION),
        "afState" to result.get(CaptureResult.CONTROL_AF_STATE),
        "afMode" to result.get(CaptureResult.CONTROL_AF_MODE),
        "afRegions" to result.get(CaptureResult.CONTROL_AF_REGIONS),
        "lensFocusDistance" to result.get(CaptureResult.LENS_FOCUS_DISTANCE),
        "lensState" to result.get(CaptureResult.LENS_STATE),
        "cropRegion" to result.get(CaptureResult.SCALER_CROP_REGION),
        "zoomRatio" to result.get(CaptureResult.CONTROL_ZOOM_RATIO)
    )

    private fun writeRecord(
        transactionId: String,
        event: String,
        monotonicNs: Long = SystemClock.elapsedRealtimeNanos(),
        fields: Map<String, Any?>
    ) {
        if (!initialized) return
        val threadName = Thread.currentThread().name
        val record = JSONObject()
        record.put("schemaVersion", 1)
        record.put("monotonicTimestampNs", monotonicNs)
        record.put("transactionId", transactionId)
        record.put("event", event)
        record.put("thread", threadName)
        fields.forEach { (key, value) -> record.put(key, jsonValue(value)) }
        val line = record.toString()
        traceExecutor.execute {
            DiagnosticsAggregator.record(
                stream = DiagnosticsAggregator.Stream.CAMERA,
                scope = "AF #$transactionId",
                section = "AF GROUND TRUTH / $event",
                content = line
            )
        }
    }

    private fun jsonValue(value: Any?): Any = when (value) {
        null -> JSONObject.NULL
        is JSONObject, is JSONArray, is String, is Number, is Boolean -> value
        is Rect -> JSONObject()
            .put("left", value.left)
            .put("top", value.top)
            .put("right", value.right)
            .put("bottom", value.bottom)
            .put("width", value.width())
            .put("height", value.height())
        is Size -> JSONObject().put("width", value.width).put("height", value.height)
        is Range<*> -> JSONObject().put("lower", jsonValue(value.lower)).put("upper", jsonValue(value.upper))
        is MeteringRectangle -> JSONObject()
            .put("rect", jsonValue(value.rect))
            .put("weight", value.meteringWeight)
        is Map<*, *> -> JSONObject().also { objectValue ->
            value.forEach { (key, nested) -> objectValue.put(key.toString(), jsonValue(nested)) }
        }
        is Iterable<*> -> JSONArray().also { array -> value.forEach { array.put(jsonValue(it)) } }
        is Array<*> -> JSONArray().also { array -> value.forEach { array.put(jsonValue(it)) } }
        is IntArray -> JSONArray().also { array -> value.forEach { array.put(it) } }
        is LongArray -> JSONArray().also { array -> value.forEach { array.put(it) } }
        is FloatArray -> JSONArray().also { array -> value.forEach { array.put(it.toDouble()) } }
        is DoubleArray -> JSONArray().also { array -> value.forEach { array.put(it) } }
        is BooleanArray -> JSONArray().also { array -> value.forEach { array.put(it) } }
        else -> value.toString()
    }
}
