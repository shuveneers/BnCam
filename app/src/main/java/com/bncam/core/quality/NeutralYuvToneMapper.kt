package com.bncam.core.quality

import kotlin.math.roundToInt

/** Testable mirror of the native neutral YUV luma contract. RAW rendering never uses this. */
object NeutralYuvToneMapper {
    const val TONE_MODE = "NEUTRAL_YUV_LUMA"

    fun map(input: Float): Float {
        val x = input.coerceIn(0f, 1f)
        var value = 0.008f + 0.984f * x
        if (x < 0.25f) {
            val t = 1f - x / 0.25f
            value += 0.012f * t * t
        }
        if (x > 0.82f) {
            val t = (x - 0.82f) / 0.18f
            value -= 0.025f * t * t
        }
        return value.coerceIn(0f, 1f)
    }

    fun buildLut(): IntArray {
        var previous = 0
        return IntArray(256) { index ->
            val mapped = (map(index / 255f) * 255f).roundToInt().coerceAtLeast(previous)
            previous = mapped
            mapped
        }
    }

    fun toneModeDescription(): String = "$TONE_MODE;rawToneDefaultsUsed=false;lumaOnly=true"
}
