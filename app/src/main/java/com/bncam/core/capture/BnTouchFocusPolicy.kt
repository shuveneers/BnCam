package com.bncam.core.capture

import android.graphics.Rect
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.params.MeteringRectangle
import kotlin.math.max

/**
 * BnCam exact-touch Camera2 autofocus mapping policy.
 *
 * The important contract is deliberately simple:
 * - the tap coordinate itself is authoritative; there is no saliency/object relocation;
 * - geometry comes from the selected physical sensor when one backs the active route;
 * - the focus window is a stable sensor-relative window (roughly one eighth per axis);
 * - request sequencing is owned by BnCameraManager, not by an image-space focus solver.
 *
 * The result rectangle is expressed in the Camera2 active-array domain. SENSOR_INFO_PIXEL_ARRAY_SIZE
 * is retained as the stable physical-geometry reference, while the final request rectangle is
 * scaled into SENSOR_INFO_ACTIVE_ARRAY_SIZE so it remains valid for Camera2 implementations that
 * enforce the documented metering-region coordinate domain strictly.
 */
object BnTouchFocusPolicy {
    private const val REGION_FRACTION = 1f / 8f

    data class MappedRegion(
        val requestedNormPoint: NormalizedPoint,
        val sensorNormPoint: NormalizedPoint,
        val meteringRectangle: MeteringRectangle,
        val mappedSensorRect: Rect,
        val requestBounds: Rect,
        val pixelArrayBounds: Rect,
        val visibleSensorBounds: Rect,
        val coordinateSpaceLabel: String
    )

    fun map(
        normPoint: NormalizedPoint,
        characteristics: CameraCharacteristics,
        previewStreamWidth: Int,
        previewStreamHeight: Int,
        displayRotationDegrees: Int = 0,
        weight: Int = MeteringRectangle.METERING_WEIGHT_MAX - 1
    ): MappedRegion? {
        val pixelArraySize = characteristics.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
            ?: return null
        if (pixelArraySize.width <= 0 || pixelArraySize.height <= 0) return null

        val pixelBounds = Rect(0, 0, pixelArraySize.width, pixelArraySize.height)
        val requestBounds = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
            ?.let(::Rect)
            ?: Rect(pixelBounds)
        if (requestBounds.width() <= 1 || requestBounds.height() <= 1) return null

        var x = normPoint.x.coerceIn(0f, 1f)
        var y = normPoint.y.coerceIn(0f, 1f)
        val isFront = characteristics.get(CameraCharacteristics.LENS_FACING) ==
            CameraCharacteristics.LENS_FACING_FRONT
        if (isFront) x = 1f - x

        val sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
        val relativeRotation = (sensorOrientation - displayRotationDegrees + 360) % 360
        val sensorPoint = when (relativeRotation) {
            90 -> NormalizedPoint(y, 1f - x)
            180 -> NormalizedPoint(1f - x, 1f - y)
            270 -> NormalizedPoint(1f - y, x)
            else -> NormalizedPoint(x, y)
        }.bounded()

        // The exact-touch autofocus path uses a physical-sensor-relative window and
        // does not perform semantic target relocation. Keep that behavior while accounting for a
        // preview aspect crop around the same immutable tap center.
        val visibleBounds = centerCropToStreamAspect(requestBounds, previewStreamWidth, previewStreamHeight)
        val centerX = visibleBounds.left + (sensorPoint.x * visibleBounds.width()).toInt()
        val centerY = visibleBounds.top + (sensorPoint.y * visibleBounds.height()).toInt()

        val regionWidth = max(2, (visibleBounds.width() * REGION_FRACTION).toInt())
        val regionHeight = max(2, (visibleBounds.height() * REGION_FRACTION).toInt())
        val rect = centeredRectClamped(centerX, centerY, regionWidth, regionHeight, visibleBounds)

        return MappedRegion(
            requestedNormPoint = normPoint.bounded(),
            sensorNormPoint = sensorPoint,
            meteringRectangle = MeteringRectangle(
                rect,
                weight.coerceIn(1, MeteringRectangle.METERING_WEIGHT_MAX)
            ),
            mappedSensorRect = rect,
            requestBounds = Rect(requestBounds),
            pixelArrayBounds = pixelBounds,
            visibleSensorBounds = Rect(visibleBounds),
            coordinateSpaceLabel = buildString {
                append("exactTouchPolicy=true ")
                append("pixelArray=$pixelBounds activeArray=$requestBounds visible=$visibleBounds ")
                append("sensorOrientation=$sensorOrientation displayRotation=$displayRotationDegrees front=$isFront ")
                append("regionFraction=$REGION_FRACTION")
            }
        )
    }

    private fun centeredRectClamped(
        centerX: Int,
        centerY: Int,
        width: Int,
        height: Int,
        bounds: Rect
    ): Rect {
        val safeWidth = width.coerceIn(2, bounds.width())
        val safeHeight = height.coerceIn(2, bounds.height())
        var left = centerX - safeWidth / 2
        var top = centerY - safeHeight / 2
        left = left.coerceIn(bounds.left, bounds.right - safeWidth)
        top = top.coerceIn(bounds.top, bounds.bottom - safeHeight)
        return Rect(left, top, left + safeWidth, top + safeHeight)
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
