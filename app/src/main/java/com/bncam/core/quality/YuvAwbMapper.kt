package com.bncam.core.quality

/**
 * Converts an absolute RAW white-balance target into a relative post-HAL YUV compensation.
 * Camera YUV already contains Camera2 AWB, so only the delta to the requested live target is used.
 */
object YuvAwbMapper {
    fun resolve(baseCameraGains: FloatArray?, targetGains: FloatArray?): FloatArray {
        val base = baseCameraGains
        val target = targetGains
        if (base == null || target == null || base.size < 4 || target.size < 4) return identity()
        if (!base.take(4).all { it.isFinite() && it > 0f } || !target.take(4).all { it.isFinite() && it > 0f }) return identity()
        val baseGreen = ((base[1] + base[2]) * 0.5f).coerceAtLeast(1.0e-4f)
        val targetGreen = ((target[1] + target[2]) * 0.5f).coerceAtLeast(1.0e-4f)
        val redRatio = ((target[0] / targetGreen) / (base[0] / baseGreen)).coerceIn(MIN_GAIN, MAX_GAIN)
        val blueRatio = ((target[3] / targetGreen) / (base[3] / baseGreen)).coerceIn(MIN_GAIN, MAX_GAIN)
        return floatArrayOf(redRatio, 1.0f, blueRatio)
    }
    private fun identity() = floatArrayOf(1f, 1f, 1f)
    private const val MIN_GAIN = 0.50f
    private const val MAX_GAIN = 2.00f
}
