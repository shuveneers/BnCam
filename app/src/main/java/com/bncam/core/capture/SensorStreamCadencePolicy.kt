package com.bncam.core.capture

/** Minimum frame-duration truth for one output participating in the active Camera2 session. */
data class StreamCadenceOutput(
    val name: String,
    val minFrameDurationNs: Long
)

data class SensorStreamCadencePlan(
    val selected: FlickerFpsRange?,
    val sustainableUpperFps: Int?,
    val effectiveUpperFps: Int?,
    val limitingOutput: String?,
    val limitingMinFrameDurationNs: Long?,
    val strategy: String
)

/**
 * Resolves acquisition cadence from Camera2-advertised AE ranges and the already-selected outputs.
 *
 * This policy never selects a camera, sensor mode, stream format, stream size, crop or zoom. Those
 * are geometry/session decisions made before cadence is resolved. Cadence may only choose among the
 * AE FPS ranges that the active camera advertises, while the active output minimum-frame-duration
 * contract defines the physically achievable upper cadence.
 */
object SensorStreamCadencePolicy {
    private const val NANOS_PER_SECOND = 1_000_000_000.0

    fun resolve(
        availableRanges: List<FlickerFpsRange>,
        outputs: List<StreamCadenceOutput>,
        preferAdaptiveLower: Boolean = false
    ): SensorStreamCadencePlan {
        val limiting = outputs
            .asSequence()
            .filter { it.minFrameDurationNs > 0L }
            .maxByOrNull { it.minFrameDurationNs }

        val sustainableUpperFps = limiting?.let {
            ((NANOS_PER_SECOND / it.minFrameDurationNs.toDouble()) + 0.5)
                .toInt()
                .coerceAtLeast(1)
        }

        return resolveFromSustainableUpperFps(
            availableRanges = availableRanges,
            sustainableUpperFps = sustainableUpperFps,
            limitingOutput = limiting?.name,
            limitingMinFrameDurationNs = limiting?.minFrameDurationNs,
            preferAdaptiveLower = preferAdaptiveLower
        )
    }

    fun resolveFromSustainableUpperFps(
        availableRanges: List<FlickerFpsRange>,
        sustainableUpperFps: Int?,
        limitingOutput: String? = null,
        limitingMinFrameDurationNs: Long? = null,
        preferAdaptiveLower: Boolean = false
    ): SensorStreamCadencePlan {
        val validRanges = availableRanges
            .filter { it.lower > 0 && it.upper >= it.lower }
            .distinct()

        if (validRanges.isEmpty()) {
            return SensorStreamCadencePlan(
                selected = null,
                sustainableUpperFps = sustainableUpperFps?.takeIf { it > 0 },
                effectiveUpperFps = null,
                limitingOutput = limitingOutput,
                limitingMinFrameDurationNs = limitingMinFrameDurationNs,
                strategy = "NO_ADVERTISED_AE_FPS_RANGE"
            )
        }

        val sustainable = sustainableUpperFps?.takeIf { it > 0 }
            ?: return SensorStreamCadencePlan(
                selected = null,
                sustainableUpperFps = null,
                effectiveUpperFps = null,
                limitingOutput = limitingOutput,
                limitingMinFrameDurationNs = limitingMinFrameDurationNs,
                strategy = "STREAM_DURATION_CONTRACT_UNAVAILABLE_KEEP_EXISTING_FPS_FALLBACK"
            )

        data class Candidate(
            val range: FlickerFpsRange,
            val effectiveUpper: Int,
            val fullyWithinContract: Boolean
        )

        val intersecting = validRanges.mapNotNull { range ->
            if (range.lower > sustainable) return@mapNotNull null
            Candidate(
                range = range,
                effectiveUpper = minOf(range.upper, sustainable),
                fullyWithinContract = range.upper <= sustainable
            )
        }

        val selectedCandidate = intersecting.maxWithOrNull(
            compareBy<Candidate> { it.effectiveUpper }
                // If two ranges lead to the same physical cadence, prefer the one completely inside
                // the stream contract. Example: at a 30 FPS ceiling choose [30,30], not [30,60].
                .thenBy { if (it.fullyWithinContract) 1 else 0 }
                // When adaptive lower is preferred (e.g. for low-light photography headroom),
                // prefer an adaptive range that can fall back toward 15-20 FPS instead of forcing a fixed framerate.
                .thenBy {
                    if (preferAdaptiveLower && !it.range.fixed && it.range.lower <= 20) 1 else 0
                }
                // Above 30 FPS, prefer an adaptive range that can fall back toward 30 when exposure
                // genuinely requires it instead of forcing a fixed high-FPS request.
                .thenBy {
                    if (it.effectiveUpper > 30 && !it.range.fixed && it.range.lower <= 30) 1 else 0
                }
                .thenBy { it.range.lower }
        ) ?: validRanges
            .minWithOrNull(compareBy<FlickerFpsRange> { it.lower }.thenBy { it.upper })
            ?.let { Candidate(it, minOf(it.upper, sustainable), false) }

        val strategy = when {
            selectedCandidate == null -> "NO_USABLE_ADVERTISED_AE_FPS_RANGE"
            selectedCandidate.fullyWithinContract -> "HIGHEST_SUSTAINABLE_ADVERTISED_SENSOR_CADENCE"
            selectedCandidate.range.lower <= sustainable -> "HIGHEST_INTERSECTING_ADVERTISED_SENSOR_CADENCE"
            else -> "ADVERTISED_RANGE_STREAM_CONTRACT_MISMATCH_FAIL_CLOSED"
        }

        return SensorStreamCadencePlan(
            selected = selectedCandidate?.range,
            sustainableUpperFps = sustainable,
            effectiveUpperFps = selectedCandidate?.effectiveUpper,
            limitingOutput = limitingOutput,
            limitingMinFrameDurationNs = limitingMinFrameDurationNs,
            strategy = strategy
        )
    }
}
