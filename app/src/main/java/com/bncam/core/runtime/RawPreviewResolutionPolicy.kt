package com.bncam.core.runtime

import kotlin.math.ceil
import kotlin.math.max

/** RAW viewfinder quality tiers. BALANCED remains the shipping/default tier. */
enum class RawPreviewQualityTier(val maxWidth: Int, val maxHeight: Int) {
    BALANCED(1440, 1080),
    /** Profiling prototype only. It is deliberately not selected automatically. */
    SHARP(2048, 1536)
}

/**
 * Product resolution contract for the RAW Vulkan viewfinder.
 *
 * BALANCED preserves the established 1440x1080 ceiling. SHARP is an explicit prototype
 * target for device profiling; callers must opt into it deliberately. Runtime pressure never
 * changes geometry automatically: newest-frame-wins dropping remains the overload policy.
 */
object RawPreviewResolutionPolicy {
    const val QUALITY_MAX_WIDTH = 1440
    const val QUALITY_MAX_HEIGHT = 1080
    const val SHARP_PROTOTYPE_MAX_WIDTH = 2048
    const val SHARP_PROTOTYPE_MAX_HEIGHT = 1536

    fun cfaCellDecimation(
        cropWidth: Int,
        cropHeight: Int,
        tier: RawPreviewQualityTier = RawPreviewQualityTier.BALANCED
    ): Int {
        val safeCropWidth = cropWidth.coerceAtLeast(2)
        val safeCropHeight = cropHeight.coerceAtLeast(2)
        return ceil(
            max(
                safeCropWidth.toFloat() / tier.maxWidth.toFloat(),
                safeCropHeight.toFloat() / tier.maxHeight.toFloat()
            )
        ).toInt().coerceAtLeast(1)
    }

    fun outputDimensions(
        cropWidth: Int,
        cropHeight: Int,
        tier: RawPreviewQualityTier = RawPreviewQualityTier.BALANCED
    ): Pair<Int, Int> {
        val evenWidth = (cropWidth.coerceAtLeast(2) and 0xFFFE).coerceAtLeast(2)
        val evenHeight = (cropHeight.coerceAtLeast(2) and 0xFFFE).coerceAtLeast(2)
        val decimation = cfaCellDecimation(evenWidth, evenHeight, tier)
        return Pair(
            (2 * (evenWidth / (2 * decimation))).coerceAtLeast(2),
            (2 * (evenHeight / (2 * decimation))).coerceAtLeast(2)
        )
    }

    /**
     * Returns the inactive SHARP candidate for diagnostics/profiling planning only.
     * This does not alter renderer allocations or the active tier.
     */
    fun sharpPrototypeDimensions(cropWidth: Int, cropHeight: Int): Pair<Int, Int> =
        outputDimensions(cropWidth, cropHeight, RawPreviewQualityTier.SHARP)
}
