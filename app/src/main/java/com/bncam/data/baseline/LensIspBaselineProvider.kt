package com.bncam.data.baseline

import java.util.concurrent.ConcurrentHashMap

object LensIspBaselineProvider {
    private val baselineCache = ConcurrentHashMap<LensIspBaselineKey, LensIspBaseline>()

    fun getBaseline(key: LensIspBaselineKey): LensIspBaseline {
        return baselineCache.getOrPut(key) {
            LensIspBaseline.createDefault(key)
        }
    }

    fun updateBaseline(baseline: LensIspBaseline) {
        baselineCache[baseline.key] = baseline
    }

    /**
     * Maps a normalized user profile value (-1.00..+1.00 or 0.00..1.00) relative to a specific LensIspBaseline.
     * Profile value 0.00 resolves exactly to baseline.
     */
    fun resolveEffectiveValue(
        profileValue: Float,
        baseline: Float,
        minBound: Float,
        maxBound: Float
    ): Float {
        val clampedUi = profileValue.coerceIn(-1.00f, 1.00f)
        return if (clampedUi >= 0.00f) {
            baseline + clampedUi * (maxBound - baseline)
        } else {
            baseline + clampedUi * (baseline - minBound)
        }
    }
}
