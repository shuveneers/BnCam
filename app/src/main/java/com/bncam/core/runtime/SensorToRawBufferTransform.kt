package com.bncam.core.runtime

import android.graphics.Rect
import android.hardware.camera2.CameraCharacteristics
import android.util.Size

/**
 * Explicit coordinate mapping between physical sensor space (PixelArray, ActiveArray)
 * and acquired HAL RAW buffer space (0..bufferWidth-1, 0..bufferHeight-1).
 */
data class SensorToRawBufferTransform(
    val pixelArrayRect: Rect,
    val preCorrectionActiveRect: Rect,
    val activeArrayRect: Rect,
    val bufferWidth: Int,
    val bufferHeight: Int,
    val isBufferAlreadyActiveArray: Boolean,
    val isBufferFullPixelArray: Boolean,
    val bufferActiveRect: Rect,
    val bufferCropLeft: Int,
    val bufferCropTop: Int,
    val bufferCropWidth: Int,
    val bufferCropHeight: Int,
    val cfaOffsetX: Int,
    val cfaOffsetY: Int
) {
    companion object {
        fun create(
            characteristics: CameraCharacteristics,
            bufferWidth: Int,
            bufferHeight: Int
        ): SensorToRawBufferTransform {
            val pixelArraySize = characteristics.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
                ?: Size(bufferWidth, bufferHeight)
            val pixelArray = Rect(0, 0, pixelArraySize.width, pixelArraySize.height)

            val preCorrectionRect = characteristics.get(CameraCharacteristics.SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE)
                ?: pixelArray

            val activeArray = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
                ?: Rect(0, 0, bufferWidth, bufferHeight)

            // RAW buffers are defined in the pre-correction sensor coordinate system.
            // SENSOR_INFO_ACTIVE_ARRAY_SIZE is the post-distortion-correction rectangle and must
            // not be used as the RAW payload crop.
            val rawActiveRect = preCorrectionRect
            val rawActiveWidth = rawActiveRect.width()
            val rawActiveHeight = rawActiveRect.height()

            // Camera2 Image buffers use their declared stream width/height directly; display
            // rotation is a presentation concern and must never be folded into RAW sensor-space
            // geometry matching. Accept only exact dimensions here.
            val isAlreadyActive = bufferWidth == rawActiveWidth && bufferHeight == rawActiveHeight

            val isFullPixelArray = bufferWidth == pixelArray.width() && bufferHeight == pixelArray.height()

            val (bufCropL, bufCropT, bufCropW, bufCropH) = when {
                isAlreadyActive -> {
                    // HAL delivered pre-cropped active array. Buffer origin (0,0) is active array origin.
                    Tuple4(0, 0, bufferWidth, bufferHeight)
                }
                isFullPixelArray -> {
                    // A full-pixel-array RAW buffer may contain optical-black / calibration pixels.
                    // Crop to the pre-correction active rectangle; this is the RAW coordinate
                    // contract used by Camera2/DNG metadata.
                    val cropL = rawActiveRect.left.coerceIn(0, (bufferWidth - 2).coerceAtLeast(0))
                    val cropT = rawActiveRect.top.coerceIn(0, (bufferHeight - 2).coerceAtLeast(0))
                    val cropW = rawActiveWidth.coerceIn(2, bufferWidth - cropL)
                    val cropH = rawActiveHeight.coerceIn(2, bufferHeight - cropT)
                    Tuple4(cropL, cropT, cropW, cropH)
                }
                else -> {
                    // Camera2 may expose a RAW stream that is already cropped to the
                    // pre-correction active payload even when it is not byte-for-byte equal to
                    // the full pixel-array dimensions. Never apply absolute sensor offsets to a
                    // custom-sized buffer unless its dimensions prove that the full pixel array
                    // is present.
                    Tuple4(0, 0, bufferWidth, bufferHeight)
                }
            }

            val bufActiveRect = Rect(bufCropL, bufCropT, bufCropL + bufCropW, bufCropT + bufCropH)

            // CFA phase at the local RAW payload origin. For an already-cropped RAW stream the
            // local (0,0) maps to the pre-correction active-array origin in sensor coordinates.
            val cfaX = (rawActiveRect.left and 1)
            val cfaY = (rawActiveRect.top and 1)

            return SensorToRawBufferTransform(
                pixelArrayRect = pixelArray,
                preCorrectionActiveRect = preCorrectionRect,
                activeArrayRect = activeArray,
                bufferWidth = bufferWidth,
                bufferHeight = bufferHeight,
                isBufferAlreadyActiveArray = isAlreadyActive,
                isBufferFullPixelArray = isFullPixelArray,
                bufferActiveRect = bufActiveRect,
                bufferCropLeft = bufCropL,
                bufferCropTop = bufCropT,
                bufferCropWidth = bufCropW,
                bufferCropHeight = bufCropH,
                cfaOffsetX = if (isAlreadyActive) cfaX else bufCropL and 1,
                cfaOffsetY = if (isAlreadyActive) cfaY else bufCropT and 1
            )
        }
    }

    private data class Tuple4<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)
}
