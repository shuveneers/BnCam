package com.bncam.core.capture

import kotlin.math.ceil
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Fast manual exposure fallback used only when Camera2 hybrid AE cannot be trusted.
 *
 * Camera2 AE still supplies the photometric target. Once BnCam must own shutter + ISO, this
 * controller attacks large scene changes quickly, then damps the final settle. Flicker protection
 * constrains the shutter raster only; it must never throttle ISO/exposure response to multi-second
 * convergence.
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
    val reason: String,
    /** Last scene-luminance correction. Preserved across motion-only reallocations. */
    val feedbackCorrectionEv: Float = lastCorrectionEv,
    /** Shutter-allocation delta caused by motion/flicker/bounds, not a brightness correction. */
    val motionReallocationEv: Float = 0f
) {
    fun summary(): String =
        "fallback=MANUAL_LINEAR_LUMA_FEEDBACK;exposureProduct=$exposureProduct;" +
            "exposureNs=$exposureTimeNs;iso=$sensitivityIso;frameDurationNs=$frameDurationNs;" +
            "targetLuma=$targetLuma;observedLuma=${observedLuma ?: "unavailable"};" +
            // Keep correctionEv for backward-compatible debug parsers, but make its meaning explicit.
            "correctionEv=$feedbackCorrectionEv;feedbackCorrectionEv=$feedbackCorrectionEv;" +
            "motionReallocationEv=$motionReallocationEv;" +
            "limit=$limitingConstraint;reason=$reason"
}

object DefaultRawShutterManualFallbackPolicy {
    private const val LARGE_ERROR_EV = 1.0f
    private const val VERY_LARGE_ERROR_EV = 2.0f
    private const val MEDIUM_ERROR_EV = 0.35f

    private const val VERY_LARGE_GAIN = 0.80f
    private const val VERY_LARGE_MAX_STEP_EV = 2.50f
    private const val LARGE_GAIN = 0.72f
    private const val LARGE_MAX_STEP_EV = 1.50f
    private const val MEDIUM_GAIN = 0.58f
    private const val MEDIUM_MAX_STEP_EV = 0.75f
    private const val SMALL_GAIN = 0.35f
    private const val SMALL_MAX_STEP_EV = 0.18f

    private const val NORMAL_DEADBAND_EV = 0.04f
    private const val FLICKER_DEADBAND_EV = 0.06f

    private const val LARGE_UPDATE_INTERVAL_NS = 50_000_000L
    private const val MEDIUM_UPDATE_INTERVAL_NS = 100_000_000L
    private const val SMALL_UPDATE_INTERVAL_NS = 180_000_000L

    private const val CLIP_ATTACK_THRESHOLD = 0.005f
    private const val SEVERE_CLIP_THRESHOLD = 0.05f

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

    /** Scene-linear exposure error. Positive means BnCam must add exposure. */
    fun exposureErrorEv(targetLuma: Float, observedLuma: Float): Float? {
        if (!targetLuma.isFinite() || targetLuma <= 0f ||
            !observedLuma.isFinite() || observedLuma <= 0f
        ) return null
        return log2(
            (targetLuma.toDouble() / observedLuma.toDouble()).coerceIn(1.0 / 32.0, 32.0)
        ).toFloat()
    }

    /**
     * Statistics already arrive at a bounded preview cadence. This interval is only a stale-frame
     * guard; unlike the old 250/500 ms wall-clock throttle it does not dominate AE response.
     */
    fun recommendedUpdateIntervalNs(
        previous: DefaultRawManualFallbackPlan,
        observedLuma: Float,
        rawNearClipFraction: Float?
    ): Long {
        val error = exposureErrorEv(previous.targetLuma, observedLuma)?.let { kotlin.math.abs(it) } ?: 0f
        val clip = rawNearClipFraction?.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0f
        return when {
            clip >= CLIP_ATTACK_THRESHOLD || error >= LARGE_ERROR_EV -> LARGE_UPDATE_INTERVAL_NS
            error >= MEDIUM_ERROR_EV -> MEDIUM_UPDATE_INTERVAL_NS
            else -> SMALL_UPDATE_INTERVAL_NS
        }
    }

    /**
     * Fast-attack / damped-settle exposure feedback.
     *
     * Large luminance errors receive most of the required EV correction immediately. Once close to
     * target, proportional gain and a deadband suppress pumping. In a resolved flicker environment
     * the aligned shutter is held whenever ISO alone can realize the corrected exposure product.
     */
    fun adapt(
        previous: DefaultRawManualFallbackPlan,
        observedLuma: Float,
        rawNearClipFraction: Float?,
        safeExposureCeilingNs: Long,
        bounds: ExposureBounds,
        flickerConstraint: RawFlickerConstraint = RawFlickerConstraint()
    ): DefaultRawManualFallbackPlan {
        val controllerErrorEv = exposureErrorEv(previous.targetLuma, observedLuma)
            ?: return reallocate(
                previous.exposureProduct, previous.targetLuma, null, 0f,
                safeExposureCeilingNs, bounds, flickerConstraint, "invalid_luma_feedback_hold",
                previous.exposureTimeNs
            )
        val metering = DefaultRawMeteringSnapshot(
            pipelineGeneration = -1,
            source = "MANUAL_FALLBACK",
            controllerLuma = observedLuma,
            rawNearClipFraction = rawNearClipFraction,
            sampleCount = 1,
            measurementElapsedRealtimeNs = 0L,
            ageNs = 0L,
            freshness = DefaultRawMeteringFreshness.FRESH,
            valid = true,
            reason = "manual_fallback_fresh_statistic"
        )
        val desiredTarget = DefaultRawExposureTargetModel.resolve(
            targetLuma = previous.targetLuma,
            metering = metering,
            currentExposureProduct = previous.exposureProduct,
            minimumExposureProduct = bounds.minIso.toDouble() * bounds.minExposureNs.toDouble(),
            maximumExposureProduct = bounds.maxIso.toDouble() *
                safeExposureCeilingNs.coerceIn(bounds.minExposureNs, bounds.maxExposureNs).toDouble()
        ) ?: return reallocate(
            previous.exposureProduct, previous.targetLuma, observedLuma, 0f,
            safeExposureCeilingNs, bounds, flickerConstraint, "invalid_exposure_target_hold",
            previous.exposureTimeNs
        )

        val requestedDeltaEv = log2(
            desiredTarget.finalExposureProduct / previous.exposureProduct.coerceAtLeast(1.0)
        ).toFloat()
        val absErrorEv = kotlin.math.abs(controllerErrorEv)
        val deadbandEv = if (flickerConstraint.frequency != RawFlickerFrequency.NONE) {
            FLICKER_DEADBAND_EV
        } else {
            NORMAL_DEADBAND_EV
        }

        val correctionEv = if (kotlin.math.abs(requestedDeltaEv) < deadbandEv &&
            desiredTarget.highlightProtectionEv <= 0f
        ) {
            0f
        } else {
            val (gain, maxStep) = when {
                absErrorEv >= VERY_LARGE_ERROR_EV -> VERY_LARGE_GAIN to VERY_LARGE_MAX_STEP_EV
                absErrorEv >= LARGE_ERROR_EV -> LARGE_GAIN to LARGE_MAX_STEP_EV
                absErrorEv >= MEDIUM_ERROR_EV -> MEDIUM_GAIN to MEDIUM_MAX_STEP_EV
                else -> SMALL_GAIN to SMALL_MAX_STEP_EV
            }
            (gain * requestedDeltaEv).coerceIn(-maxStep, maxStep)
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
            when {
                correctionEv == 0f -> "ideal_final_target_hold"
                desiredTarget.highlightProtectionEv > 0f -> "ideal_final_target_highlight_attack"
                absErrorEv >= LARGE_ERROR_EV -> "ideal_final_target_fast_attack_iso_first"
                else -> "ideal_final_target_damped_settle_iso_first"
            },
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
        previous.feedbackCorrectionEv,
        safeExposureCeilingNs,
        bounds,
        flickerConstraint,
        "motion_ceiling_reallocation",
        previous.exposureTimeNs,
        motionReallocation = true
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
        previousExposureNs: Long?,
        motionReallocation: Boolean = false
    ): DefaultRawManualFallbackPlan {
        val allocation = DefaultRawExposureAllocator.allocate(
            exposureProduct = exposureProduct,
            safeExposureCeilingNs = safeExposureCeilingNs,
            bounds = bounds,
            flickerConstraint = flickerConstraint,
            previousExposureNs = previousExposureNs,
            preferHeldFlickerShutter = true
        ) ?: return DefaultRawManualFallbackPlan(
            exposureProduct = exposureProduct.coerceAtLeast(1.0),
            exposureTimeNs = bounds.minExposureNs.coerceAtLeast(1L),
            sensitivityIso = bounds.minIso.coerceAtLeast(1),
            frameDurationNs = bounds.minExposureNs.coerceAtLeast(1L),
            targetLuma = targetLuma,
            observedLuma = observedLuma,
            lastCorrectionEv = 0f,
            limitingConstraint = "INVALID_ALLOCATION",
            reason = "$reason;central_allocator_rejected_input",
            feedbackCorrectionEv = 0f,
            motionReallocationEv = 0f
        )

        val motionReallocationEv = if (motionReallocation &&
            previousExposureNs != null && previousExposureNs > 0L && allocation.exposureTimeNs > 0L
        ) {
            log2(allocation.exposureTimeNs.toDouble() / previousExposureNs.toDouble()).toFloat()
        } else {
            0f
        }
        return DefaultRawManualFallbackPlan(
            exposureProduct = allocation.boundedExposureProduct,
            exposureTimeNs = allocation.exposureTimeNs,
            sensitivityIso = allocation.sensitivityIso,
            frameDurationNs = allocation.frameDurationNs,
            targetLuma = targetLuma,
            observedLuma = observedLuma,
            lastCorrectionEv = correctionEv,
            limitingConstraint = allocation.limitingConstraint,
            reason = "$reason;central_allocator:${allocation.reason}",
            feedbackCorrectionEv = correctionEv,
            motionReallocationEv = motionReallocationEv
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
