package com.bncam.core.capture

import kotlin.math.abs
import kotlin.math.ln

/**
 * Selection-only exposure realization gate for near-ZSL frames.
 *
 * Shutter remains the motion-safety authority. ISO is evaluated together with shutter as the
 * realized sensor exposure product so a warm frame from a neighbouring request epoch may bridge a
 * repeating-request transition only when it still represents the current photographic exposure.
 * This keeps the warm-buffer continuity introduced by DELTA 0154 without allowing stale ISO to
 * turn a shutter-valid frame several EV too bright or too dark.
 */
data class FrameSelectionExposureDecision(
    val eligible: Boolean,
    val requestedExposureTargetNs: Long,
    val toleratedExposureMinNs: Long,
    val toleratedExposureMaxNs: Long,
    val actualExposureNs: Long,
    val deviationNs: Long,
    val reason: String,
    val actualIso: Int? = null,
    val requestedIso: Int? = null,
    val exposureProductRatio: Double? = null,
    val exposureErrorEv: Double? = null,
    val allowedExposureErrorEv: Double? = null
)

object FrameSelectionExposurePolicy {
    const val LOWER_RELATIVE_TOLERANCE_DIVISOR: Long = 20L // strict: -5%
    const val UPPER_RELATIVE_TOLERANCE_DIVISOR: Long = 50L // strict: +2%
    const val MIN_ABSOLUTE_TOLERANCE_NS: Long = 100_000L // 0.1 ms

    /** Exact/current-request frames must realize total sensor exposure within ±0.20 EV. */
    const val STRICT_PRODUCT_ERROR_EV: Double = 0.20

    /** A neighbouring warm frame gets only 0.05 EV extra room while the request transition lands. */
    const val TRANSITION_PRODUCT_ERROR_EV: Double = 0.25

    // A neighbouring repeating-request step remains selectable while the warm ring refills.
    // These bounds are deliberately far tighter than a full 1 EV step and prevent the 10/20 ms
    // flicker bucket transition from emptying all candidates at once.
    private const val TRANSITION_MIN_NUMERATOR: Long = 2L
    private const val TRANSITION_MIN_DENOMINATOR: Long = 3L // >= 0.667x target
    private const val TRANSITION_MAX_NUMERATOR: Long = 3L
    private const val TRANSITION_MAX_DENOMINATOR: Long = 2L // <= 1.5x target

    fun evaluate(
        actualExposureNs: Long,
        requestedExposureTargetNs: Long,
        actualIso: Int? = null,
        requestedIso: Int? = null
    ): FrameSelectionExposureDecision {
        if (requestedExposureTargetNs <= 0L) {
            return FrameSelectionExposureDecision(
                eligible = false,
                requestedExposureTargetNs = requestedExposureTargetNs,
                toleratedExposureMinNs = 0L,
                toleratedExposureMaxNs = 0L,
                actualExposureNs = actualExposureNs,
                deviationNs = 0L,
                reason = "INVALID_REQUESTED_EXPOSURE_TARGET",
                actualIso = actualIso,
                requestedIso = requestedIso
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
        val strictMinNs = (requestedExposureTargetNs - lowerToleranceNs).coerceAtLeast(1L)
        val strictMaxNs = saturatingAdd(requestedExposureTargetNs, upperToleranceNs)
        if (actualExposureNs <= 0L) {
            return FrameSelectionExposureDecision(
                eligible = false,
                requestedExposureTargetNs = requestedExposureTargetNs,
                toleratedExposureMinNs = strictMinNs,
                toleratedExposureMaxNs = strictMaxNs,
                actualExposureNs = actualExposureNs,
                deviationNs = 0L,
                reason = "UNPROVEN_FRAME_EXPOSURE",
                actualIso = actualIso,
                requestedIso = requestedIso
            )
        }

        val transitionMinNs = multiplyDivideSaturating(
            requestedExposureTargetNs,
            TRANSITION_MIN_NUMERATOR,
            TRANSITION_MIN_DENOMINATOR
        ).coerceAtLeast(1L)
        val transitionMaxNs = multiplyDivideSaturating(
            requestedExposureTargetNs,
            TRANSITION_MAX_NUMERATOR,
            TRANSITION_MAX_DENOMINATOR
        )

        val shutterReason = when {
            actualExposureNs in strictMinNs..strictMaxNs -> "WITHIN_CURRENT_EXPOSURE_CONTRACT"
            actualExposureNs in transitionMinNs..transitionMaxNs -> "TRANSITIONAL_WARM_FRAME"
            actualExposureNs < transitionMinNs -> "FRAME_EXPOSURE_TOO_SHORT_FOR_CURRENT_TRANSITION"
            else -> "FRAME_EXPOSURE_TOO_LONG_FOR_CURRENT_TRANSITION"
        }
        val deviationNs = when {
            actualExposureNs < strictMinNs -> strictMinNs - actualExposureNs
            actualExposureNs > strictMaxNs -> actualExposureNs - strictMaxNs
            else -> 0L
        }
        val shutterEligible = shutterReason == "WITHIN_CURRENT_EXPOSURE_CONTRACT" ||
            shutterReason == "TRANSITIONAL_WARM_FRAME"
        if (!shutterEligible || requestedIso == null) {
            return FrameSelectionExposureDecision(
                eligible = shutterEligible,
                requestedExposureTargetNs = requestedExposureTargetNs,
                toleratedExposureMinNs = strictMinNs,
                toleratedExposureMaxNs = strictMaxNs,
                actualExposureNs = actualExposureNs,
                deviationNs = deviationNs,
                reason = shutterReason,
                actualIso = actualIso,
                requestedIso = requestedIso
            )
        }

        if (requestedIso <= 0) {
            return FrameSelectionExposureDecision(
                eligible = false,
                requestedExposureTargetNs = requestedExposureTargetNs,
                toleratedExposureMinNs = strictMinNs,
                toleratedExposureMaxNs = strictMaxNs,
                actualExposureNs = actualExposureNs,
                deviationNs = deviationNs,
                reason = "INVALID_REQUESTED_ISO_TARGET",
                actualIso = actualIso,
                requestedIso = requestedIso
            )
        }
        if (actualIso == null || actualIso <= 0) {
            return FrameSelectionExposureDecision(
                eligible = false,
                requestedExposureTargetNs = requestedExposureTargetNs,
                toleratedExposureMinNs = strictMinNs,
                toleratedExposureMaxNs = strictMaxNs,
                actualExposureNs = actualExposureNs,
                deviationNs = deviationNs,
                reason = "UNPROVEN_FRAME_ISO",
                actualIso = actualIso,
                requestedIso = requestedIso
            )
        }

        // Evaluate the physical exposure product without multiplying Long*Int, avoiding overflow.
        val exposureProductRatio =
            (actualExposureNs.toDouble() / requestedExposureTargetNs.toDouble()) *
                (actualIso.toDouble() / requestedIso.toDouble())
        val exposureErrorEv = if (exposureProductRatio > 0.0 && exposureProductRatio.isFinite()) {
            ln(exposureProductRatio) / LN_2
        } else {
            Double.NaN
        }
        val allowedErrorEv = if (shutterReason == "WITHIN_CURRENT_EXPOSURE_CONTRACT") {
            STRICT_PRODUCT_ERROR_EV
        } else {
            TRANSITION_PRODUCT_ERROR_EV
        }
        val productEligible = exposureErrorEv.isFinite() && abs(exposureErrorEv) <= allowedErrorEv
        val reason = when {
            productEligible && shutterReason == "WITHIN_CURRENT_EXPOSURE_CONTRACT" ->
                "WITHIN_CURRENT_EXPOSURE_PRODUCT_CONTRACT"
            productEligible -> "TRANSITIONAL_WARM_FRAME_EXPOSURE_COMPENSATED"
            !exposureErrorEv.isFinite() -> "INVALID_FRAME_EXPOSURE_PRODUCT"
            exposureErrorEv < -allowedErrorEv -> "FRAME_EXPOSURE_PRODUCT_TOO_LOW"
            else -> "FRAME_EXPOSURE_PRODUCT_TOO_HIGH"
        }
        return FrameSelectionExposureDecision(
            eligible = productEligible,
            requestedExposureTargetNs = requestedExposureTargetNs,
            toleratedExposureMinNs = strictMinNs,
            toleratedExposureMaxNs = strictMaxNs,
            actualExposureNs = actualExposureNs,
            deviationNs = deviationNs,
            reason = reason,
            actualIso = actualIso,
            requestedIso = requestedIso,
            exposureProductRatio = exposureProductRatio,
            exposureErrorEv = exposureErrorEv.takeIf { it.isFinite() },
            allowedExposureErrorEv = allowedErrorEv
        )
    }

    private fun multiplyDivideSaturating(value: Long, numerator: Long, denominator: Long): Long {
        if (value <= 0L || numerator <= 0L || denominator <= 0L) return 0L
        if (value > Long.MAX_VALUE / numerator) return Long.MAX_VALUE / denominator
        return (value * numerator) / denominator
    }

    private fun saturatingAdd(left: Long, right: Long): Long {
        val nonNegativeRight = right.coerceAtLeast(0L)
        if (left >= Long.MAX_VALUE - nonNegativeRight) return Long.MAX_VALUE
        return left + nonNegativeRight
    }

    private const val LN_2: Double = 0.6931471805599453
}
