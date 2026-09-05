package com.bncam.core.capture

import kotlin.math.ceil
import kotlin.math.min
import kotlin.math.pow

/**
 * Default RAW single-frame acquisition planner.
 *
 * Camera2 AE supplies the scene exposure product (exposure time × sensitivity). BnCam then
 * decomposes that product photon-first: use the longest physically safe integration time and leave
 * ISO/gain to satisfy only the remainder. A shorter shutter is therefore justified by measured
 * motion, stream timing, highlight protection or a sensor bound; it is not used merely to preserve
 * the Camera2 bootstrap shutter.
 *
 * This class is deliberately independent of Android Camera2 request types. Capability detection,
 * request application and motion estimation remain separate owners.
 */
data class RawShutterSafetyCeilings(
    /** Maximum exposure admitted by measured camera motion. Null means no camera-motion evidence. */
    val cameraMotionNs: Long? = null,
    /** Maximum exposure admitted by measured subject/scene motion. Null means no scene-motion evidence. */
    val sceneMotionNs: Long? = null,
    /**
     * Legacy/diagnostic AE cadence period. This is not a physical maximum exposure: under manual
     * sensor control the frame duration is allowed to lengthen when a longer shutter is required.
     */
    val streamCadenceNs: Long? = null,
    /** Conservative lens/FOV fallback used only when measured motion authority is unavailable. */
    val lensStabilityNs: Long? = null,
    /** Allow the lens/FOV prior to satisfy readiness when real-time motion evidence is unavailable. */
    val allowLensStabilityFallback: Boolean = false
) {
    private fun hasMeasuredMotionEvidence(): Boolean =
        (cameraMotionNs != null && cameraMotionNs > 0L) ||
            (sceneMotionNs != null && sceneMotionNs > 0L)

    fun validValues(bounds: ExposureBounds): List<Pair<String, Long>> = buildList {
        fun addIfValid(name: String, value: Long?) {
            if (value != null && value > 0L) {
                add(name to value.coerceIn(bounds.minExposureNs, bounds.maxExposureNs))
            }
        }

        val measuredMotionAvailable = hasMeasuredMotionEvidence()
        addIfValid("CAMERA_MOTION", cameraMotionNs)
        addIfValid("SCENE_MOTION", sceneMotionNs)

        // Do not turn CONTROL_AE_TARGET_FPS_RANGE.lower into a shutter ceiling. It is an AE cadence
        // preference, not a physical sensor exposure limit, and the default production path owns
        // SENSOR_FRAME_DURATION when AE is off. Exposure is therefore allowed to lower low-light
        // cadence when photon collection requires it. The value remains in this data class for
        // diagnostics/source compatibility.

        // A focal-length rule is only a fallback prior. Once actual frame-to-frame motion exists,
        // measured motion is stronger evidence than an uncalibrated handheld heuristic.
        if (!measuredMotionAvailable && allowLensStabilityFallback) {
            addIfValid("LENS_STABILITY_FALLBACK", lensStabilityNs)
        }
    }

    fun hasMotionEvidence(): Boolean =
        hasMeasuredMotionEvidence() ||
            (allowLensStabilityFallback && lensStabilityNs != null && lensStabilityNs > 0L)
}

data class DefaultRawShutterPriorityPlan(
    val ready: Boolean,
    val referenceAeIso: Int?,
    val referenceAeExposureNs: Long?,
    val referenceExposureProduct: Double?,
    val targetExposureNs: Long?,
    /** Diagnostic/manual-fallback expectation. API-36 AE priority still owns realized ISO. */
    val expectedIso: Int?,
    val safeExposureCeilingNs: Long?,
    val limitingConstraint: String,
    val reason: String
) {
    fun summary(): String = buildString {
        append("strategy=DEFAULT_RAW_SHUTTER_PRIORITY")
        append(";ready=$ready")
        append(";referenceAeIso=${referenceAeIso ?: "unavailable"}")
        append(";referenceAeExposureNs=${referenceAeExposureNs ?: "unavailable"}")
        append(";targetExposureNs=${targetExposureNs ?: "unavailable"}")
        append(";expectedIso=${expectedIso ?: "unavailable"}")
        append(";safeExposureCeilingNs=${safeExposureCeilingNs ?: "unavailable"}")
        append(";limit=$limitingConstraint")
        append(";reason=$reason")
    }
}

object DefaultRawShutterPriorityPolicy {
    fun resolve(
        measuredIso: Int?,
        measuredExposureNs: Long?,
        bounds: ExposureBounds,
        ceilings: RawShutterSafetyCeilings,
        flickerConstraint: RawFlickerConstraint = RawFlickerConstraint(),
        rawNearClipFraction: Float? = null
    ): DefaultRawShutterPriorityPlan {
        if (bounds.minIso <= 0 || bounds.maxIso < bounds.minIso ||
            bounds.minExposureNs <= 0L || bounds.maxExposureNs < bounds.minExposureNs
        ) {
            return unavailable("INVALID_SENSOR_BOUNDS")
        }
        if (measuredIso == null || measuredIso <= 0 ||
            measuredExposureNs == null || measuredExposureNs <= 0L
        ) {
            return unavailable("FRESH_AE_BASELINE_UNAVAILABLE")
        }
        if (!ceilings.hasMotionEvidence()) {
            return DefaultRawShutterPriorityPlan(
                ready = false,
                referenceAeIso = measuredIso,
                referenceAeExposureNs = measuredExposureNs,
                referenceExposureProduct = measuredIso.toDouble() * measuredExposureNs.toDouble(),
                targetExposureNs = null,
                expectedIso = null,
                safeExposureCeilingNs = null,
                limitingConstraint = "MOTION_EVIDENCE_REQUIRED",
                reason = "measured_motion_or_lens_fallback_unavailable"
            )
        }

        val referenceIso = measuredIso.coerceIn(bounds.minIso, bounds.maxIso)
        val referenceExposure = measuredExposureNs.coerceIn(bounds.minExposureNs, bounds.maxExposureNs)
        val baseProduct = referenceIso.toDouble() * referenceExposure.toDouble()
        val targetProduct = if (rawNearClipFraction != null && rawNearClipFraction > 0.005f) {
            val clipPenaltyEv = ((rawNearClipFraction - 0.005f) * 10f).coerceIn(0.1f, 2.0f)
            baseProduct / 2.0.pow(clipPenaltyEv.toDouble())
        } else {
            baseProduct
        }

        val constraints = ceilings.validValues(bounds)
        if (constraints.isEmpty()) {
            return DefaultRawShutterPriorityPlan(
                ready = false,
                referenceAeIso = referenceIso,
                referenceAeExposureNs = referenceExposure,
                referenceExposureProduct = targetProduct,
                targetExposureNs = null,
                expectedIso = null,
                safeExposureCeilingNs = null,
                limitingConstraint = "NO_VALID_EXPOSURE_CEILING",
                reason = "no_valid_safety_ceiling"
            )
        }

        val strictest = constraints.minBy { it.second }
        val safeCeiling = strictest.second.coerceIn(bounds.minExposureNs, bounds.maxExposureNs)
        val exposureAtMinIso = ceil(targetProduct / bounds.minIso.toDouble())
            .toLong()
            .coerceIn(bounds.minExposureNs, bounds.maxExposureNs)
        val physicallyDesiredExposure = min(safeCeiling, exposureAtMinIso)
            .coerceIn(bounds.minExposureNs, bounds.maxExposureNs)

        // Do not rate-limit a quality-seeking shutter against the immutable Camera2 bootstrap
        // exposure. That can pin every recalculation to the same first 0.25-EV / one-period step.
        // Motion already has its own fast-attack/slow-release filter. The exposure allocator should
        // therefore realize the best currently-safe integration time immediately.
        val flickerResolved = flickerConstraint.frequency != RawFlickerFrequency.NONE
        val targetExposure = if (flickerResolved) {
            flickerConstraint.constrainExposureNs(
                candidateNs = physicallyDesiredExposure,
                minExposureNs = bounds.minExposureNs,
                maxExposureNs = safeCeiling
            )
        } else {
            physicallyDesiredExposure
        }.coerceIn(bounds.minExposureNs, safeCeiling)

        val expectedIsoUnclamped = ceil(targetProduct / targetExposure.toDouble()).toInt()
        val expectedIso = expectedIsoUnclamped.coerceIn(bounds.minIso, bounds.maxIso)

        val completeFlickerPeriodFits = flickerConstraint.periodNs?.let { targetExposure >= it } ?: false
        val flickerAdjusted = flickerResolved && targetExposure != physicallyDesiredExposure
        val limitingConstraint = when {
            flickerResolved && !completeFlickerPeriodFits ->
                "FLICKER_UNAVOIDABLE_SHORT_EXPOSURE"
            exposureAtMinIso <= safeCeiling -> "MIN_ISO"
            flickerResolved && flickerConstraint.isExposureAligned(targetExposure) ->
                "FLICKER_${flickerConstraint.frequency.name};${strictest.first}"
            else -> strictest.first
        }
        val reason = when {
            flickerResolved && !completeFlickerPeriodFits ->
                "motion_ceiling_shorter_than_one_light_period_flicker_cannot_be_fully_cancelled"
            expectedIsoUnclamped > bounds.maxIso ->
                "max_iso_still_required_after_longest_safe_shutter"
            exposureAtMinIso <= safeCeiling ->
                "minimum_iso_reached_with_photon_first_shutter"
            flickerAdjusted ->
                "photon_first_longest_flicker_safe_shutter_minimizes_gain"
            else ->
                "photon_first_longest_safe_shutter_minimizes_gain"
        }

        return DefaultRawShutterPriorityPlan(
            ready = true,
            referenceAeIso = referenceIso,
            referenceAeExposureNs = referenceExposure,
            referenceExposureProduct = targetProduct,
            targetExposureNs = targetExposure,
            expectedIso = expectedIso,
            safeExposureCeilingNs = safeCeiling,
            limitingConstraint = limitingConstraint,
            reason = reason
        )
    }

    private fun unavailable(reason: String) = DefaultRawShutterPriorityPlan(
        ready = false,
        referenceAeIso = null,
        referenceAeExposureNs = null,
        referenceExposureProduct = null,
        targetExposureNs = null,
        expectedIso = null,
        safeExposureCeilingNs = null,
        limitingConstraint = reason,
        reason = reason.lowercase()
    )
}
