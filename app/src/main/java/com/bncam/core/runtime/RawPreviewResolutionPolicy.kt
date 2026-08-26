package com.bncam.core.runtime

import kotlin.math.ceil
import kotlin.math.max

/**
 * Product resolution contract for the RAW Vulkan viewfinder.
 *
 * Fixed RAW viewfinder geometry. A 4096x3072 Bayer producer uses the proven 4x CFA-cell
 * decimation path (1024x768). Runtime pressure is still handled by newest-frame-wins dropping;
 * there is no adaptive resolution or quality degradation.
 */
object RawPreviewResolutionPolicy {
    const val QUALITY_MAX_WIDTH = 1024
    const val QUALITY_MAX_HEIGHT = 768

    fun cfaCellDecimation(
        cropWidth: Int,
        cropHeight: Int
    ): Int {
        val safeCropWidth = cropWidth.coerceAtLeast(2)
        val safeCropHeight = cropHeight.coerceAtLeast(2)
        val safeMaxWidth = QUALITY_MAX_WIDTH
        val safeMaxHeight = QUALITY_MAX_HEIGHT
        return ceil(
            max(
                safeCropWidth.toFloat() / safeMaxWidth.toFloat(),
                safeCropHeight.toFloat() / safeMaxHeight.toFloat()
            )
        ).toInt().coerceAtLeast(1)
    }

    fun outputDimensions(
        cropWidth: Int,
        cropHeight: Int
    ): Pair<Int, Int> {
        val evenWidth = (cropWidth.coerceAtLeast(2) and 0xFFFE).coerceAtLeast(2)
        val evenHeight = (cropHeight.coerceAtLeast(2) and 0xFFFE).coerceAtLeast(2)
        val decimation = cfaCellDecimation(evenWidth, evenHeight)
        return Pair(
            (2 * (evenWidth / (2 * decimation))).coerceAtLeast(2),
            (2 * (evenHeight / (2 * decimation))).coerceAtLeast(2)
        )
    }
}
