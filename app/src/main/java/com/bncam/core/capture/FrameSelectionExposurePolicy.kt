package com.bncam.core.capture

/**
 * Selection-only exposure realization gate for near-ZSL frames.
 *
 * Phase 1 can either shorten shutter for motion or lengthen shutter to trade ISO for photons. Older
 * complete frames remain intentionally resident in the warm ring. A selectable frame therefore has
 * to realize the current application-owned exposure target closely in both directions: accepting
 * only an upper ceiling would still allow an older short/high-ISO frame after Phase 1 lengthens the
 * shutter for quality.
 */
data class FrameSelectionExposureDecision(
    val eligible: Boolean,
    val requestedExposureTargetNs: Long,
    val toleratedExposureMinNs: Long,
    val toleratedExposureMaxNs: Long,
    val actualExposureNs: Long,
    val deviationNs: Long,
    val reason: String
)

object FrameSelectionExposurePolicy {
    // Matches the existing API-36 realization-truth lower bound while keeping the unsafe/long side
    // tighter. Manual fallback normally realizes the request exactly.
    const val LOWER_RELATIVE_TOLERANCE_DIVISOR: Long = 20L // 5%
    const val UPPER_RELATIVE_TOLERANCE_DIVISOR: Long = 50L // 2%
    const val MIN_ABSOLUTE_TOLERANCE_NS: Long = 100_000L // 0.1 ms

    fun evaluate(
        actualExposureNs: Long,
        requestedExposureTargetNs: Long
    ): FrameSelectionExposureDecision {
        if (requestedExposureTargetNs <= 0L) {
            return FrameSelectionExposureDecision(
                eligible = false,
                requestedExposureTargetNs = requestedExposureTargetNs,
                toleratedExposureMinNs = 0L,
                toleratedExposureMaxNs = 0L,
                actualExposureNs = actualExposureNs,
                deviationNs = 0L,
                reason = "INVALID_REQUESTED_EXPOSURE_TARGET"
            )
        }
        val lowerToleranceNs = maxOf(
            MIN_ABSOLUTE_TOLERANCE_NS,
            requestedExposureTargetNs / LOWER_RELATIVE_TOLERANCE_DIVISOR
        )
        val upperToleranceNs = maxOf(
            MIN_ABSOLUTE_TOLERANCE_NS,
            requestedExposureTargetNs / UPPER_RELATIVE_TOLERANCE_DIVISOR
        )
        val toleratedMinNs = (requestedExposureTargetNs - lowerToleranceNs).coerceAtLeast(1L)
        val toleratedMaxNs = saturatingAdd(requestedExposureTargetNs, upperToleranceNs)
        if (actualExposureNs <= 0L) {
            return FrameSelectionExposureDecision(
                eligible = false,
                requestedExposureTargetNs = requestedExposureTargetNs,
                toleratedExposureMinNs = toleratedMinNs,
                toleratedExposureMaxNs = toleratedMaxNs,
                actualExposureNs = actualExposureNs,
                deviationNs = 0L,
                reason = "UNPROVEN_FRAME_EXPOSURE"
            )
        }
        val reason = when {
            actualExposureNs < toleratedMinNs -> "FRAME_EXPOSURE_BELOW_CURRENT_TARGET"
            actualExposureNs > toleratedMaxNs -> "FRAME_EXPOSURE_ABOVE_CURRENT_TARGET"
            else -> "WITHIN_CURRENT_EXPOSURE_CONTRACT"
        }
        val deviationNs = when {
            actualExposureNs < toleratedMinNs -> toleratedMinNs - actualExposureNs
            actualExposureNs > toleratedMaxNs -> actualExposureNs - toleratedMaxNs
            else -> 0L
        }
        return FrameSelectionExposureDecision(
            eligible = reason == "WITHIN_CURRENT_EXPOSURE_CONTRACT",
            requestedExposureTargetNs = requestedExposureTargetNs,
            toleratedExposureMinNs = toleratedMinNs,
            toleratedExposureMaxNs = toleratedMaxNs,
            actualExposureNs = actualExposureNs,
            deviationNs = deviationNs,
            reason = reason
        )
    }

    private fun saturatingAdd(left: Long, right: Long): Long {
        val nonNegativeRight = right.coerceAtLeast(0L)
        if (left >= Long.MAX_VALUE - nonNegativeRight) return Long.MAX_VALUE
        return left + nonNegativeRight
    }
}
