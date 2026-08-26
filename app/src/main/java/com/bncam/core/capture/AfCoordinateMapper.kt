package com.bncam.core.capture

import android.graphics.Rect
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.MeteringRectangle
import android.os.Build
import kotlin.math.max
import kotlin.math.min

/**
 * Maps a normalized point from the visible camera preview into the exact Camera2 metering coordinate
 * space for the active logical/physical request.
 *
 * Dynamically derives coordinate domain from SENSOR_INFO_ACTIVE_ARRAY_SIZE,
 * SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE, DISTORTION_CORRECTION_MODE,
 * CONTROL_ZOOM_RATIO, SCALER_CROP_REGION, and stream aspect ratio crop.
 */
object AfCoordinateMapper {
    private const val DEFAULT_REGION_SIZE_PERCENT = 0.08f

    data class MappedAfRegionResult(
        val requestedNormPoint: NormalizedPoint,
        val mappedNormPoint: NormalizedPoint,
        val mappedSensorRect: Rect,
        val meteringRectangle: MeteringRectangle,
        val coordinateBounds: Rect,
        val requestCropBounds: Rect,
        val visibleStreamBounds: Rect,
        val maxAfRegions: Int,
        val isAfRegionSupported: Boolean,
        val coordinateSpaceLabel: String
    )

    fun mapNormalizedPreviewPoint(
        normPoint: NormalizedPoint,
        characteristics: CameraCharacteristics,
        currentCropRegion: Rect?,
        previewStreamWidth: Int,
        previewStreamHeight: Int,
        distortionCorrectionMode: Int? = null,
        zoomRatio: Float? = null,
        displayRotationDegrees: Int = 0,
        regionSizePct: Float = DEFAULT_REGION_SIZE_PERCENT,
        weight: Int = MeteringRectangle.METERING_WEIGHT_MAX
    ): MappedAfRegionResult? {
        val maxAfRegions = characteristics.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF) ?: 0
        val isAfRegionSupported = maxAfRegions > 0

        val activeArray = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
            ?: return null
        val preCorrectionArray = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            characteristics.get(CameraCharacteristics.SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE)
        } else null

        // 1. Determine active coordinate bounds domain based on distortion correction mode
        val coordinateBounds = if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
            distortionCorrectionMode == CaptureRequest.DISTORTION_CORRECTION_MODE_OFF &&
            preCorrectionArray != null
        ) {
            Rect(preCorrectionArray)
        } else {
            Rect(activeArray)
        }

        // 2. Crop bounds (SCALER_CROP_REGION)
        val requestCrop = (currentCropRegion?.let(::Rect) ?: Rect(coordinateBounds)).apply {
            if (!intersect(coordinateBounds)) set(coordinateBounds)
        }

        // 3. Aspect-ratio correction to visible preview stream bounds
        val visibleBounds = centerCropToStreamAspect(
            requestCrop,
            previewStreamWidth,
            previewStreamHeight
        )

        // 4. Orientation & display rotation mapping
        var x = normPoint.x.coerceIn(0f, 1f)
        var y = normPoint.y.coerceIn(0f, 1f)
        val sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
        val isFront = characteristics.get(CameraCharacteristics.LENS_FACING) ==
            CameraCharacteristics.LENS_FACING_FRONT
        if (isFront) x = 1f - x

        val relativeRotation = (sensorOrientation - displayRotationDegrees + 360) % 360
        val mapped = when (relativeRotation) {
            90 -> NormalizedPoint(y, 1f - x)
            180 -> NormalizedPoint(1f - x, 1f - y)
            270 -> NormalizedPoint(1f - y, x)
            else -> NormalizedPoint(x, y)
        }.bounded()

        // 5. Map normalized point into visible bounds
        val centerX = visibleBounds.left + (mapped.x * visibleBounds.width()).toInt()
        val centerY = visibleBounds.top + (mapped.y * visibleBounds.height()).toInt()

        // 6. Compact sensor-relative target ROI
        val baseSize = min(visibleBounds.width(), visibleBounds.height())
        val diameter = (baseSize * regionSizePct.coerceIn(0.02f, 0.30f)).toInt().coerceAtLeast(2)
        val halfX = max(1, diameter / 2)
        val halfY = max(1, diameter / 2)

        val left = (centerX - halfX).coerceIn(visibleBounds.left, max(visibleBounds.left, visibleBounds.right - 2))
        val top = (centerY - halfY).coerceIn(visibleBounds.top, max(visibleBounds.top, visibleBounds.bottom - 2))
        val right = (centerX + halfX).coerceIn(left + 1, visibleBounds.right)
        val bottom = (centerY + halfY).coerceIn(top + 1, visibleBounds.bottom)
        val rect = Rect(left, top, right, bottom)

        val label = "bounds=$coordinateBounds crop=$requestCrop visible=$visibleBounds " +
            "sensorOrientation=$sensorOrientation displayRotation=$displayRotationDegrees front=$isFront " +
            "maxAfRegions=$maxAfRegions zoomRatio=${zoomRatio ?: 1f} distortionMode=${distortionCorrectionMode ?: "none"}"

        return MappedAfRegionResult(
            requestedNormPoint = normPoint.bounded(),
            mappedNormPoint = mapped,
            mappedSensorRect = rect,
            meteringRectangle = MeteringRectangle(rect, weight.coerceIn(1, MeteringRectangle.METERING_WEIGHT_MAX)),
            coordinateBounds = coordinateBounds,
            requestCropBounds = requestCrop,
            visibleStreamBounds = visibleBounds,
            maxAfRegions = maxAfRegions,
            isAfRegionSupported = isAfRegionSupported,
            coordinateSpaceLabel = label
        )
    }

    private fun centerCropToStreamAspect(bounds: Rect, streamWidth: Int, streamHeight: Int): Rect {
        if (streamWidth <= 0 || streamHeight <= 0 || bounds.width() <= 0 || bounds.height() <= 0) {
            return Rect(bounds)
        }
        val targetAspect = streamWidth.toFloat() / streamHeight.toFloat()
        val boundsAspect = bounds.width().toFloat() / bounds.height().toFloat()
        if (kotlin.math.abs(targetAspect - boundsAspect) < 0.002f) return Rect(bounds)

        return if (boundsAspect > targetAspect) {
            val width = (bounds.height() * targetAspect).toInt().coerceIn(1, bounds.width())
            val left = bounds.centerX() - width / 2
            Rect(left, bounds.top, left + width, bounds.bottom)
        } else {
            val height = (bounds.width() / targetAspect).toInt().coerceIn(1, bounds.height())
            val top = bounds.centerY() - height / 2
            Rect(bounds.left, top, bounds.right, top + height)
        }
    }
}
