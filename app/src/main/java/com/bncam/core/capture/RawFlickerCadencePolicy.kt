package com.bncam.core.capture

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

/**
 * Keeps mains-flicker authority separate from acquisition cadence authority.
 *
 * Anti-banding remains a Camera2 exposure concern. Acquisition cadence is selected from the
 * camera-advertised AE FPS ranges and the already-selected stream-duration contract. A resolved
 * 50/60 Hz frequency therefore cannot force a 25/30 FPS request.
 */
object RawFlickerCadencePolicy {
    fun resolve(
        availableRanges: List<FlickerFpsRange>,
        sustainableUpperFps: Int?,
        frequency: RawFlickerFrequency
    ): RawFlickerCadencePlan {
        val cadencePlan = SensorStreamCadencePolicy.resolveFromSustainableUpperFps(
            availableRanges = availableRanges,
            sustainableUpperFps = sustainableUpperFps
        )

        return RawFlickerCadencePlan(
            selected = cadencePlan.selected,
            targetFps = cadencePlan.effectiveUpperFps,
            exactPhaseCadence = false,
            streamContractFallback = cadencePlan.strategy.contains("MISMATCH"),
            strategy = "ANTIBANDING_${frequency.name}_SEPARATE;${cadencePlan.strategy}"
        )
    }
}
