package com.bncam.core.capture

import kotlin.math.abs

data class FlickerFpsRange(val lower: Int, val upper: Int) {
    val fixed: Boolean get() = lower == upper
    val width: Int get() = (upper - lower).coerceAtLeast(0)
}

data class RawFlickerCadencePlan(
    val selected: FlickerFpsRange?,
    val targetFps: Int?,
    val exactPhaseCadence: Boolean,
    val streamContractFallback: Boolean,
    val strategy: String
)

/** Chooses a stable advertised frame cadence once 50/60 Hz authority is resolved. */
object RawFlickerCadencePolicy {
    fun resolve(
        availableRanges: List<FlickerFpsRange>,
        sustainableUpperFps: Int?,
        frequency: RawFlickerFrequency
    ): RawFlickerCadencePlan {
        if (frequency == RawFlickerFrequency.NONE) {
            return RawFlickerCadencePlan(null, null, false, false, "FLICKER_UNRESOLVED_KEEP_EXISTING_FPS_POLICY")
        }
        val valid = availableRanges.filter { it.lower > 0 && it.upper >= it.lower }
        if (valid.isEmpty()) {
            return RawFlickerCadencePlan(null, null, false, false, "NO_ADVERTISED_AE_FPS_RANGE")
        }

        val sustainable = sustainableUpperFps?.takeIf { it > 0 }
        val compatible = sustainable?.let { cap -> valid.filter { it.upper <= cap } }.orEmpty()
        val pool = if (sustainable == null || compatible.isNotEmpty()) {
            if (sustainable == null) valid else compatible
        } else {
            // Same fail-closed behaviour as the existing stream policy: when static metadata is
            // contradictory, take the least aggressive advertised range rather than over-driving.
            valid.filter { it.upper == valid.minOf { range -> range.upper } }
        }
        val contractFallback = sustainable != null && compatible.isEmpty()
        val target = if (frequency == RawFlickerFrequency.HZ_50) 25 else 30
        val lightRate = frequency.lightPeriodsPerSecond?.toInt() ?: target
        fun harmonic(fps: Int): Boolean = fps > 0 && lightRate % fps == 0

        val fixed = pool.filter { it.fixed }
        val selected = fixed.firstOrNull { it.upper == target }
            ?: fixed.filter { harmonic(it.upper) && it.upper <= target }.maxByOrNull { it.upper }
            ?: fixed.filter { harmonic(it.upper) && it.upper > target }.minByOrNull { it.upper }
            ?: fixed.minWithOrNull(compareBy<FlickerFpsRange> { abs(it.upper - target) }.thenBy { it.upper })
            ?: pool.minWithOrNull(
                compareBy<FlickerFpsRange> { if (target in it.lower..it.upper) 0 else 1 }
                    .thenBy { it.width }
                    .thenBy { abs(it.upper - target) }
                    .thenBy { it.upper }
            )

        val exact = selected?.fixed == true && harmonic(selected.upper)
        val strategy = buildString {
            append("FLICKER_${frequency.name}_STABLE_CADENCE")
            if (selected?.fixed == true) append("_FIXED") else append("_NARROWEST_AVAILABLE")
            if (exact) append("_PHASE_RASTER")
            if (contractFallback) append("_STREAM_CONTRACT_FALLBACK")
        }
        return RawFlickerCadencePlan(selected, target, exact, contractFallback, strategy)
    }
}
