package com.bncam.core.runtime

import kotlin.math.ceil
import kotlin.math.max

/**
 * Product resolution contract for the RAW Vulkan viewfinder.
 *
 * The fixed quality target is high enough to avoid visible upscaling softness on modern
 * viewfinders while retaining bounded GPU cost. A 4096x3072 Bayer producer resolves to
 * 1364x1024 at 3x CFA-cell decimation. Runtime pressure remains newest-frame-wins dropping;
 * geometry never degrades adaptively.
 */
object RawPreviewResolutionPolicy {
    const val QUALITY_MAX_WIDTH = 1440
    const val QUALITY_MAX_HEIGHT = 1080

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
