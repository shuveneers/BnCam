package com.bncam.core.capture

import kotlin.math.ceil
import kotlin.math.min
import kotlin.math.pow

/**
 * Default RAW single-frame acquisition planner.
 *
 * Camera2 AE provides the target sensitivity (exposure time × gain). BnCam then decomposes that
 * target into the longest exposure that is still admitted by independently-derived safety
 * ceilings, leaving ISO/gain to satisfy only the remaining exposure requirement.
 *
 * This class is deliberately independent of Android Camera2 request types. Capability detection,
 * request application and motion estimation remain separate owners.
 */
data class RawShutterSafetyCeilings(
    /** Maximum exposure admitted by measured camera motion. Null means no camera-motion evidence. */
    val cameraMotionNs: Long? = null,
    /** Maximum exposure admitted by measured subject/scene motion. Null means no scene-motion evidence. */
    val sceneMotionNs: Long? = null,
    /** Maximum exposure admitted by viewfinder / warm-buffer cadence. */
    val streamCadenceNs: Long? = null,
    /** Optional conservative lens/stabilisation fallback, never a substitute for measured motion. */
    val lensStabilityNs: Long? = null,
    /** When enabled, lens stability ceiling can satisfy readiness when real-time motion evidence is calm/missing. */
    val allowLensStabilityFallback: Boolean = false
) {
    fun validValues(bounds: ExposureBounds): List<Pair<String, Long>> = buildList {
        fun addIfValid(name: String, value: Long?) {
            if (value != null && value > 0L) {
                add(name to value.coerceIn(bounds.minExposureNs, bounds.maxExposureNs))
            }
        }
        addIfValid("CAMERA_MOTION", cameraMotionNs)
        addIfValid("SCENE_MOTION", sceneMotionNs)
        addIfValid("STREAM_CADENCE", streamCadenceNs)
        addIfValid("LENS_STABILITY", lensStabilityNs)
    }

    fun hasMotionEvidence(): Boolean =
        (cameraMotionNs != null && cameraMotionNs > 0L) ||
            (sceneMotionNs != null && sceneMotionNs > 0L) ||
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
    /**
     * Outside a resolved flicker environment, longer-shutter recovery keeps the existing 0.25 EV
     * attack/release behaviour. Under 50/60 Hz authority, release is instead one exact light-period
     * step at a time so every intermediate shutter remains flicker-safe.
     */
    private const val MAX_RELEASE_STEP_EV = 0.25
    private val MAX_RELEASE_RATIO = 2.0.pow(MAX_RELEASE_STEP_EV)

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
                reason = "measured_motion_ceiling_unavailable"
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

        val flickerResolved = flickerConstraint.frequency != RawFlickerFrequency.NONE
        val targetExposure: Long
        val releaseLimited: Boolean
        val flickerAdjusted: Boolean

        if (flickerResolved) {
            // A tighter motion ceiling attacks immediately. A longer quality-seeking shutter is
            // released by at most one complete light period per update. ISO absorbs the exposure-
            // product difference, so aligning 14.9 ms -> 10 ms does not become a brightness pulse.
            val flickerTarget = when {
                physicallyDesiredExposure < referenceExposure ->
                    flickerConstraint.constrainExposureNs(
                        physicallyDesiredExposure,
                        bounds.minExposureNs,
                        bounds.maxExposureNs
                    )
                physicallyDesiredExposure > referenceExposure &&
                    flickerConstraint.isExposureAligned(referenceExposure) ->
                    flickerConstraint.nextLongerAlignedExposureNs(
                        currentNs = referenceExposure,
                        desiredNs = physicallyDesiredExposure,
                        minExposureNs = bounds.minExposureNs,
                        maxExposureNs = bounds.maxExposureNs
                    )
                physicallyDesiredExposure > referenceExposure ->
                    // Enter the nearest complete-period shutter around the Camera2 AE baseline.
                    // Unlike a blind floor this can use 20 ms for an 18 ms 50 Hz baseline when the
                    // physical ceiling permits it, minimizing the ISO compensation step.
                    flickerConstraint.closestAlignedExposureNs(
                        candidateNs = referenceExposure,
                        maxAllowedNs = physicallyDesiredExposure,
                        minExposureNs = bounds.minExposureNs,
                        maxExposureNs = bounds.maxExposureNs
                    )
                else ->
                    flickerConstraint.constrainExposureNs(
                        physicallyDesiredExposure,
                        bounds.minExposureNs,
                        bounds.maxExposureNs
                    )
            }
            targetExposure = flickerTarget.coerceAtMost(safeCeiling).coerceAtLeast(bounds.minExposureNs)
            releaseLimited = physicallyDesiredExposure > referenceExposure &&
                targetExposure < physicallyDesiredExposure
            flickerAdjusted = targetExposure != physicallyDesiredExposure ||
                flickerConstraint.isExposureAligned(targetExposure)
        } else {
            val releaseLimitedExposure = if (physicallyDesiredExposure > referenceExposure) {
                min(
                    physicallyDesiredExposure,
                    (referenceExposure.toDouble() * MAX_RELEASE_RATIO).toLong()
                        .coerceAtLeast(referenceExposure + 1L)
                )
            } else {
                physicallyDesiredExposure
            }.coerceIn(bounds.minExposureNs, bounds.maxExposureNs)
            targetExposure = releaseLimitedExposure
            releaseLimited = releaseLimitedExposure != physicallyDesiredExposure
            flickerAdjusted = false
        }

        val expectedIsoUnclamped = ceil(targetProduct / targetExposure.toDouble()).toInt()
        val expectedIso = expectedIsoUnclamped.coerceIn(bounds.minIso, bounds.maxIso)

        val completeFlickerPeriodFits = flickerConstraint.periodNs?.let { targetExposure >= it } ?: false
        val limitingConstraint = when {
            flickerResolved && !completeFlickerPeriodFits ->
                "FLICKER_UNAVOIDABLE_SHORT_EXPOSURE"
            flickerResolved && flickerConstraint.isExposureAligned(targetExposure) ->
                "FLICKER_${flickerConstraint.frequency.name}"
            releaseLimited -> "AE_TRANSITION_RELEASE_RATE"
            exposureAtMinIso <= safeCeiling -> "MIN_ISO"
            else -> strictest.first
        }
        val reason = when {
            flickerResolved && !completeFlickerPeriodFits ->
                "motion_ceiling_shorter_than_one_light_period_flicker_cannot_be_fully_cancelled"
            expectedIsoUnclamped > bounds.maxIso -> "iso_max_limits_flicker_safe_target_sensitivity"
            flickerResolved && releaseLimited ->
                "flicker_locked_slow_release_iso_preserves_exposure_product"
            flickerResolved && flickerAdjusted ->
                "strict_flicker_safe_shutter_iso_preserves_exposure_product"
            releaseLimited -> "gradual_longer_shutter_release_preserves_ae_realization"
            exposureAtMinIso <= safeCeiling -> "minimum_iso_reached_before_motion_ceiling"
            else -> "longest_safe_shutter_preserves_reference_sensitivity"
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
