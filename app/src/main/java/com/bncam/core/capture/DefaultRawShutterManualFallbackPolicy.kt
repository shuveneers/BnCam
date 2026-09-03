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
            "exposureNs=$exposureTimeNs;iso=$sensitivityIso;frameDurationNs=$frameDurationNs;" +
            "targetLuma=$targetLuma;observedLuma=${observedLuma ?: "unavailable"};" +
            "correctionEv=$lastCorrectionEv;limit=$limitingConstraint;reason=$reason"
}

object DefaultRawShutterManualFallbackPolicy {
    private const val NORMAL_FEEDBACK_GAIN = 0.45f
    private const val NORMAL_MAX_STEP_EV = 0.25f
    private const val NORMAL_DEADBAND_EV = 0.025f
    private const val FLICKER_FEEDBACK_GAIN = 0.22f
    private const val FLICKER_MAX_STEP_EV = 0.10f
    private const val FLICKER_DEADBAND_EV = 0.05f

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
            reason = "fresh_camera2_ae_reference",
            previousExposureNs = null
        )
    }

    /**
     * Deliberately slow luminance feedback. Flicker-safe shutter is held whenever ISO can satisfy
     * the corrected exposure product; only motion or an ISO bound is allowed to move the shutter.
     * This stops a light-phase fluctuation from becoming a self-exciting shutter/AE oscillation.
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
                safeExposureCeilingNs, bounds, flickerConstraint, "invalid_luma_feedback_hold",
                previous.exposureTimeNs
            )
        }

        val errorEv = log2(
            (previous.targetLuma.toDouble() / observedLuma.toDouble()).coerceIn(0.25, 4.0)
        ).toFloat()
        val flickerResolved = flickerConstraint.frequency != RawFlickerFrequency.NONE
        val feedbackGain = if (flickerResolved) FLICKER_FEEDBACK_GAIN else NORMAL_FEEDBACK_GAIN
        val maxStepEv = if (flickerResolved) FLICKER_MAX_STEP_EV else NORMAL_MAX_STEP_EV
        val deadbandEv = if (flickerResolved) FLICKER_DEADBAND_EV else NORMAL_DEADBAND_EV
        var correctionEv = if (kotlin.math.abs(errorEv) < deadbandEv) {
            0f
        } else {
            (feedbackGain * errorEv).coerceIn(-maxStepEv, maxStepEv)
        }

        val clip = rawNearClipFraction?.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0f
        if (clip >= 0.005f) {
            val clipPressure = ((clip - 0.005f) / 0.045f).coerceIn(0f, 1f)
            val requiredReductionEv = -(0.10f + 0.15f * clipPressure)
            correctionEv = min(correctionEv, requiredReductionEv)
        }

        val nextProduct = previous.exposureProduct * 2.0.pow(correctionEv.toDouble())
        return reallocate(
            nextProduct,
            previous.targetLuma,
            observedLuma,
            correctionEv,
            safeExposureCeilingNs,
            bounds,
            flickerConstraint,
            if (correctionEv == 0f) "linear_luma_hold" else "linear_luma_feedback_iso_first",
            previous.exposureTimeNs
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
        "motion_ceiling_reallocation",
        previous.exposureTimeNs
    )

    private fun reallocate(
        exposureProduct: Double,
        targetLuma: Float,
        observedLuma: Float?,
        correctionEv: Float,
        safeExposureCeilingNs: Long,
        bounds: ExposureBounds,
        flickerConstraint: RawFlickerConstraint,
        reason: String,
        previousExposureNs: Long?
    ): DefaultRawManualFallbackPlan {
        val ceiling = safeExposureCeilingNs.coerceIn(bounds.minExposureNs, bounds.maxExposureNs)
        val exposureAtMinIso = ceil(exposureProduct / bounds.minIso.toDouble()).toLong()
            .coerceIn(bounds.minExposureNs, bounds.maxExposureNs)
        val unconstrainedExposure = min(ceiling, exposureAtMinIso)
            .coerceIn(bounds.minExposureNs, bounds.maxExposureNs)

        // In a resolved flicker environment, keep an already-safe shutter and let ISO carry small
        // brightness corrections. Shutter only moves when motion tightens the ceiling or the ISO
        // range can no longer represent the requested exposure product.
        val heldExposure = previousExposureNs?.takeIf { previous ->
            previous in bounds.minExposureNs..ceiling &&
                flickerConstraint.frequency != RawFlickerFrequency.NONE &&
                flickerConstraint.isExposureAligned(previous) &&
                ceil(exposureProduct / previous.toDouble()).toInt() in bounds.minIso..bounds.maxIso
        }
        val exposure = heldExposure ?: flickerConstraint.constrainExposureNs(
            unconstrainedExposure,
            bounds.minExposureNs,
            bounds.maxExposureNs
        )
        val flickerAdjusted = exposure != unconstrainedExposure || heldExposure != null
        val requestedIso = ceil(exposureProduct / exposure.toDouble()).toInt()
        val iso = requestedIso.coerceIn(bounds.minIso, bounds.maxIso)
        val limitingConstraint = when {
            requestedIso > bounds.maxIso -> "MAX_ISO"
            flickerConstraint.frequency != RawFlickerFrequency.NONE &&
                flickerConstraint.isExposureAligned(exposure) ->
                "FLICKER_${flickerConstraint.frequency.name}"
            exposureAtMinIso <= ceiling -> "MIN_ISO"
            else -> "MOTION_OR_STREAM_CEILING"
        }
        val frameDuration = flickerConstraint.stableFrameDurationNs(
            exposureNs = exposure,
            minFrameDurationNs = max(exposure, bounds.minExposureNs)
        )
        return DefaultRawManualFallbackPlan(
            exposureProduct = exposureProduct.coerceAtLeast(1.0),
            exposureTimeNs = exposure,
            sensitivityIso = iso,
            frameDurationNs = frameDuration,
            targetLuma = targetLuma,
            observedLuma = observedLuma,
            lastCorrectionEv = correctionEv,
            limitingConstraint = limitingConstraint,
            reason = when {
                heldExposure != null && correctionEv != 0f -> "$reason;flicker_shutter_held_iso_adjusted"
                flickerAdjusted -> "$reason;flicker_locked"
                else -> reason
            }
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
