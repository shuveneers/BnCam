package com.bncam.core.quality

/**
 * Converts an absolute profile RAW white-balance target into a post-HAL YUV RGB
 * compensation. Camera YUV already contains Camera2 AWB, so the profile target
 * must be expressed relative to the captured Camera2 gains to avoid double AWB.
 */
object ProfileYuvAwbMapper {
    fun resolve(
        baseCameraGains: FloatArray?,
        effectiveProfileGains: FloatArray?
    ): FloatArray {
        val base = baseCameraGains
        val effective = effectiveProfileGains
        if (base == null || effective == null || base.size < 4 || effective.size < 4) {
            return identity()
        }
        if (!base.take(4).all { it.isFinite() && it > 0f } ||
            !effective.take(4).all { it.isFinite() && it > 0f }
        ) {
            return identity()
        }

        val baseGreen = ((base[1] + base[2]) * 0.5f).coerceAtLeast(1.0e-4f)
        val effectiveGreen = ((effective[1] + effective[2]) * 0.5f).coerceAtLeast(1.0e-4f)
        val redRatio = ((effective[0] / effectiveGreen) / (base[0] / baseGreen))
            .coerceIn(MIN_GAIN, MAX_GAIN)
        val blueRatio = ((effective[3] / effectiveGreen) / (base[3] / baseGreen))
            .coerceIn(MIN_GAIN, MAX_GAIN)
        return floatArrayOf(redRatio, 1.0f, blueRatio)
    }

    private fun identity(): FloatArray = floatArrayOf(1f, 1f, 1f)

    private const val MIN_GAIN = 0.50f
    private const val MAX_GAIN = 2.00f
}
