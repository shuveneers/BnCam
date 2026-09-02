package com.bncam.core.capture

import kotlin.math.ceil
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Continuous manual fallback for cameras that cannot keep Camera2 AE enabled while prioritizing
 * an application-selected exposure time.
 *
 * Camera2 AE first supplies the brightness target. Once BnCam must use AE OFF for shutter
 * authority, scene-linear preview feedback maintains that same target without inventing a second
 * auto-exposure objective.
 */
data class DefaultRawManualFallbackPlan(
    val exposureProduct: Double,
    val exposureTimeNs: Long,
    val sensitivityIso: Int,
    val frameDurationNs: Long,
    val targetLuma: Float,
    val observedLuma: Float?,
    val lastCorrectionEv: Float,
    val limitingConstraint: String,
    val reason: String
) {
    fun summary(): String =
        "fallback=MANUAL_LINEAR_LUMA_FEEDBACK;exposureProduct=$exposureProduct;" +
            "exposureNs=$exposureTimeNs;iso=$sensitivityIso;targetLuma=$targetLuma;" +
            "observedLuma=${observedLuma ?: "unavailable"};correctionEv=$lastCorrectionEv;" +
            "limit=$limitingConstraint;reason=$reason"
}

object DefaultRawShutterManualFallbackPolicy {
    fun initial(
        referenceExposureProduct: Double,
        targetLuma: Float,
        safeExposureCeilingNs: Long,
        bounds: ExposureBounds,
        flickerConstraint: RawFlickerConstraint = RawFlickerConstraint()
    ): DefaultRawManualFallbackPlan? {
        if (!valid(referenceExposureProduct, targetLuma, safeExposureCeilingNs, bounds)) return null
        return reallocate(
            exposureProduct = referenceExposureProduct,
            targetLuma = targetLuma,
            observedLuma = null,
            correctionEv = 0f,
            safeExposureCeilingNs = safeExposureCeilingNs,
            bounds = bounds,
            flickerConstraint = flickerConstraint,
            reason = "fresh_camera2_ae_reference"
        )
    }

    /**
     * Bounded proportional feedback in EV space.
     *
     * A 0.45 loop gain damps frame-to-frame oscillation, while no single update may move more than
     * 0.25 EV. RAW near-clip evidence is a one-way safety gate: it can force a reduction but may
     * never request additional exposure.
     */
    fun adapt(
        previous: DefaultRawManualFallbackPlan,
        observedLuma: Float,
        rawNearClipFraction: Float?,
        safeExposureCeilingNs: Long,
        bounds: ExposureBounds,
        flickerConstraint: RawFlickerConstraint = RawFlickerConstraint()
    ): DefaultRawManualFallbackPlan {
        if (!observedLuma.isFinite() || observedLuma <= 0f ||
            !previous.targetLuma.isFinite() || previous.targetLuma <= 0f
        ) {
            return reallocate(
                previous.exposureProduct, previous.targetLuma, null, 0f,
                safeExposureCeilingNs, bounds, flickerConstraint, "invalid_luma_feedback_hold"
            )
        }

        val errorEv = log2(
            (previous.targetLuma.toDouble() / observedLuma.toDouble()).coerceIn(0.25, 4.0)
        ).toFloat()
        var correctionEv = (0.45f * errorEv).coerceIn(-0.25f, 0.25f)

        val clip = rawNearClipFraction?.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0f
        if (clip >= 0.005f) {
            val clipPressure = ((clip - 0.005f) / 0.045f).coerceIn(0f, 1f)
            val requiredReductionEv = -(0.10f + 0.15f * clipPressure)
            correctionEv = min(correctionEv, requiredReductionEv)
        }
        if (kotlin.math.abs(correctionEv) < 0.025f) correctionEv = 0f

        val nextProduct = previous.exposureProduct * 2.0.pow(correctionEv.toDouble())
        return reallocate(
            nextProduct,
            previous.targetLuma,
            observedLuma,
            correctionEv,
            safeExposureCeilingNs,
            bounds,
            flickerConstraint,
            if (correctionEv == 0f) "linear_luma_hold" else "linear_luma_feedback"
        )
    }

    fun reallocateForMotion(
        previous: DefaultRawManualFallbackPlan,
        safeExposureCeilingNs: Long,
        bounds: ExposureBounds,
        flickerConstraint: RawFlickerConstraint = RawFlickerConstraint()
    ): DefaultRawManualFallbackPlan = reallocate(
        previous.exposureProduct,
        previous.targetLuma,
        previous.observedLuma,
        0f,
        safeExposureCeilingNs,
        bounds,
        flickerConstraint,
        "motion_ceiling_reallocation"
    )

    private fun reallocate(
        exposureProduct: Double,
        targetLuma: Float,
        observedLuma: Float?,
        correctionEv: Float,
        safeExposureCeilingNs: Long,
        bounds: ExposureBounds,
        flickerConstraint: RawFlickerConstraint,
        reason: String
    ): DefaultRawManualFallbackPlan {
        val ceiling = safeExposureCeilingNs.coerceIn(bounds.minExposureNs, bounds.maxExposureNs)
        val exposureAtMinIso = ceil(exposureProduct / bounds.minIso.toDouble()).toLong()
            .coerceIn(bounds.minExposureNs, bounds.maxExposureNs)
        val unconstrainedExposure = min(ceiling, exposureAtMinIso)
            .coerceIn(bounds.minExposureNs, bounds.maxExposureNs)
        val exposure = flickerConstraint.constrainExposureNs(
            unconstrainedExposure,
            bounds.minExposureNs,
            bounds.maxExposureNs
        )
        val flickerAdjusted = exposure != unconstrainedExposure
        val requestedIso = ceil(exposureProduct / exposure.toDouble()).toInt()
        val iso = requestedIso.coerceIn(bounds.minIso, bounds.maxIso)
        val limitingConstraint = when {
            requestedIso > bounds.maxIso -> "MAX_ISO"
            flickerAdjusted -> "FLICKER_${flickerConstraint.frequency.name}"
            exposureAtMinIso <= ceiling -> "MIN_ISO"
            else -> "MOTION_OR_STREAM_CEILING"
        }
        return DefaultRawManualFallbackPlan(
            exposureProduct = exposureProduct.coerceAtLeast(1.0),
            exposureTimeNs = exposure,
            sensitivityIso = iso,
            frameDurationNs = max(exposure, bounds.minExposureNs),
            targetLuma = targetLuma,
            observedLuma = observedLuma,
            lastCorrectionEv = correctionEv,
            limitingConstraint = limitingConstraint,
            reason = reason
        )
    }

    private fun valid(
        product: Double,
        targetLuma: Float,
        ceiling: Long,
        bounds: ExposureBounds
    ): Boolean = product.isFinite() && product > 0.0 && targetLuma.isFinite() && targetLuma > 0f &&
        ceiling > 0L && bounds.minIso > 0 && bounds.maxIso >= bounds.minIso &&
        bounds.minExposureNs > 0L && bounds.maxExposureNs >= bounds.minExposureNs
}
