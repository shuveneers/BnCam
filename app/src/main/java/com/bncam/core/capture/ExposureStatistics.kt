package com.bncam.core.capture

import kotlin.math.max
import kotlin.math.pow

/**
 * Display-domain RGB/luma statistics used by the live histogram and diagnostics.
 * Standard AE metering is owned by Camera2 spatial regions; these statistics no longer drive a
 * competing BnCam EV controller. RAW routes may still carry sensor-domain clipping diagnostics.
 */
data class ExposureStatistics(
    val source: String,
    val lumaHistogram64: IntArray,
    val redHistogram64: IntArray,
    val greenHistogram64: IntArray,
    val blueHistogram64: IntArray,
    val sampleCount: Int,
    val shadowFraction: Float,
    val highlightFraction: Float,
    val redClipFraction: Float,
    val greenClipFraction: Float,
    val blueClipFraction: Float,
    val rawNearClipFraction: Float?,
    val linearLumaHistogram256: IntArray? = null,
    val highlightPoint: NormalizedPoint? = null
) {
    init {
        require(lumaHistogram64.size == 64)
        require(redHistogram64.size == 64)
        require(greenHistogram64.size == 64)
        require(blueHistogram64.size == 64)
        require(linearLumaHistogram256 == null || linearLumaHistogram256.size == 256)
    }

    fun lumaPercentile(fraction: Float): Float = histogramPercentile(lumaHistogram64, fraction)

    /**
     * Roughly linear luminance proxy for exposure-control feedback. RAW preview provides a true
     * scene-linear histogram; YUV falls back to inverse-display-gamma on its luma percentile.
     * This is intentionally independent from the creative GTM/LTM/profile tone controls.
     */
    fun exposureControllerLuma(fraction: Float = 0.50f): Float {
        val linear = linearLumaHistogram256
        if (linear != null && linear.sumOf { it.toLong() } > 0L) {
            return histogramPercentile(linear, fraction)
        }
        val display = lumaPercentile(fraction).coerceIn(0f, 1f)
        return display.toDouble().pow(2.2).toFloat().coerceIn(0f, 1f)
    }

    fun lumaMean(): Float {
        val total = lumaHistogram64.sumOf { it.toLong() }
        if (total <= 0L) return 0f
        var weighted = 0.0
        lumaHistogram64.forEachIndexed { index, count ->
            val centre = (index + 0.5) / 64.0
            weighted += centre * count.toDouble()
        }
        return (weighted / total).toFloat().coerceIn(0f, 1f)
    }

    val maximumDisplayClipFraction: Float
        get() = max(redClipFraction, max(greenClipFraction, blueClipFraction))

    val highDynamicRangeRisk: Boolean
        get() = shadowFraction >= 0.15f && (highlightFraction >= 0.04f || maximumDisplayClipFraction >= 0.01f)

    fun normalizedHistogram(): LiveRgbHistogram {
        val maxCount = maxOf(
            lumaHistogram64.maxOrNull() ?: 0,
            redHistogram64.maxOrNull() ?: 0,
            greenHistogram64.maxOrNull() ?: 0,
            blueHistogram64.maxOrNull() ?: 0
        ).coerceAtLeast(1)
        fun normalize(values: IntArray): List<Float> = values.map { it.coerceAtLeast(0).toFloat() / maxCount }
        return LiveRgbHistogram(
            luma = normalize(lumaHistogram64),
            red = normalize(redHistogram64),
            green = normalize(greenHistogram64),
            blue = normalize(blueHistogram64)
        )
    }

    companion object {
        fun histogramPercentile(histogram: IntArray, fraction: Float): Float {
            if (histogram.isEmpty()) return 0f
            val total = histogram.sumOf { it.toLong() }
            if (total <= 0L) return 0f
            val target = ((total - 1L) * fraction.coerceIn(0f, 1f)).toLong()
            var cumulative = 0L
            histogram.forEachIndexed { index, count ->
                cumulative += count.coerceAtLeast(0).toLong()
                if (cumulative > target) {
                    return ((index + 0.5f) / histogram.size.toFloat()).coerceIn(0f, 1f)
                }
            }
            return 1f
        }
    }
}

data class LiveRgbHistogram(
    val luma: List<Float>,
    val red: List<Float>,
    val green: List<Float>,
    val blue: List<Float>
) {
    companion object {
        fun empty(bins: Int = 64) = LiveRgbHistogram(
            luma = List(bins) { 0f },
            red = List(bins) { 0f },
            green = List(bins) { 0f },
            blue = List(bins) { 0f }
        )
    }
}
