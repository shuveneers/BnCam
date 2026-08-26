package com.bncam.core.runtime

import android.graphics.ImageFormat
import android.media.Image
import android.util.Log

/**
 * Normalized row and plane buffer layout for RAW10, RAW_SENSOR and YUV.
 * Enforces stride alignment and prevents reading padded memory as image payload.
 */
data class RawBufferLayout(
    val format: Int,
    val width: Int,
    val height: Int,
    val rowStrideBytes: Int,
    val pixelStrideBytes: Int,
    val packedRowBytes: Int,
    val isValid: Boolean,
    val validationError: String? = null
) {
    companion object {
        private const val TAG = "RawBufferLayout"

        fun fromImage(image: Image): RawBufferLayout {
            val fmt = image.format
            val w = image.width
            val h = image.height
            val planes = image.planes
            if (planes.isEmpty()) {
                return RawBufferLayout(fmt, w, h, 0, 0, 0, false, "Image planes are empty")
            }

            val rowStride = planes[0].rowStride
            val pixelStride = planes[0].pixelStride

            val packedRow = when (fmt) {
                ImageFormat.RAW10 -> (w * 10 + 7) / 8
                ImageFormat.RAW_SENSOR -> w * 2
                ImageFormat.YUV_420_888 -> w
                else -> w * pixelStride
            }

            var isValid = true
            var errorMsg: String? = null

            if (rowStride < packedRow) {
                isValid = false
                errorMsg = "rowStride ($rowStride) < packedRowBytes ($packedRow) for format $fmt (${w}x${h})"
                Log.e(TAG, "DEBUG ASSERTION FAILED: $errorMsg")
            }

            return RawBufferLayout(
                format = fmt,
                width = w,
                height = h,
                rowStrideBytes = rowStride,
                pixelStrideBytes = pixelStride,
                packedRowBytes = packedRow,
                isValid = isValid,
                validationError = errorMsg
            )
        }

        fun createNormalized(
            format: Int,
            width: Int,
            height: Int,
            rowStrideBytes: Int,
            pixelStrideBytes: Int = 1
        ): RawBufferLayout {
            val packedRow = when (format) {
                ImageFormat.RAW10 -> (width * 10 + 7) / 8
                ImageFormat.RAW_SENSOR -> width * 2
                ImageFormat.YUV_420_888 -> width
                else -> width * pixelStrideBytes
            }

            val valid = rowStrideBytes >= packedRow
            val err = if (!valid) "rowStride ($rowStrideBytes) < packedRow ($packedRow)" else null

            return RawBufferLayout(
                format = format,
                width = width,
                height = height,
                rowStrideBytes = rowStrideBytes.coerceAtLeast(packedRow),
                pixelStrideBytes = pixelStrideBytes,
                packedRowBytes = packedRow,
                isValid = valid,
                validationError = err
            )
        }
    }
}
