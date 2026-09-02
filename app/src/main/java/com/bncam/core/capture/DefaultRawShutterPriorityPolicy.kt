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
    val lensStabilityNs: Long? = null
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
            (sceneMotionNs != null && sceneMotionNs > 0L)
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
     * Exposure may recover more slowly after a motion event, but a newly tighter motion ceiling is
     * obeyed immediately. This gives the controller fast safety attack / slow quality release and
     * prevents alternating motion estimates from pumping the live exposure brighter/darker.
     */
    private const val MAX_RELEASE_STEP_EV = 0.25
    private val MAX_RELEASE_RATIO = 2.0.pow(MAX_RELEASE_STEP_EV)

    /**
     * Flicker quantization is a preference, not permission to introduce a large exposure step.
     * A floor from e.g. 14.9 ms to 10 ms is ~0.58 EV and was visibly pumping AE. We only snap when
     * the flicker-safe value is already close to the physically selected shutter.
     */
    private const val MAX_FLICKER_SNAP_EV = 0.25
    private val MIN_FLICKER_SNAP_RATIO = 2.0.pow(-MAX_FLICKER_SNAP_EV)

    fun resolve(
        measuredIso: Int?,
        measuredExposureNs: Long?,
        bounds: ExposureBounds,
        ceilings: RawShutterSafetyCeilings,
        flickerConstraint: RawFlickerConstraint = RawFlickerConstraint()
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
        val targetProduct = referenceIso.toDouble() * referenceExposure.toDouble()

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

        // Never delay a safety-motivated shutter reduction. On recovery toward a longer shutter,
        // however, limit each closed-loop Camera2 step to 0.25 EV so AE has time to realize ISO.
        val releaseLimitedExposure = if (physicallyDesiredExposure > referenceExposure) {
            min(
                physicallyDesiredExposure,
                (referenceExposure.toDouble() * MAX_RELEASE_RATIO).toLong().coerceAtLeast(referenceExposure + 1L)
            )
        } else {
            physicallyDesiredExposure
        }.coerceIn(bounds.minExposureNs, bounds.maxExposureNs)
        val releaseLimited = releaseLimitedExposure != physicallyDesiredExposure

        val flickerCandidate = flickerConstraint.constrainExposureNs(
            releaseLimitedExposure,
            bounds.minExposureNs,
            bounds.maxExposureNs
        )
        val flickerRatio = if (releaseLimitedExposure > 0L) {
            flickerCandidate.toDouble() / releaseLimitedExposure.toDouble()
        } else 1.0
        val flickerSnapAccepted = flickerCandidate != releaseLimitedExposure &&
            flickerRatio >= MIN_FLICKER_SNAP_RATIO
        val targetExposure = if (flickerSnapAccepted) flickerCandidate else releaseLimitedExposure

        val expectedIsoUnclamped = ceil(targetProduct / targetExposure.toDouble()).toInt()
        val expectedIso = expectedIsoUnclamped.coerceIn(bounds.minIso, bounds.maxIso)

        val limitingConstraint = when {
            releaseLimited -> "AE_TRANSITION_RELEASE_RATE"
            flickerSnapAccepted -> "FLICKER_${flickerConstraint.frequency.name}"
            exposureAtMinIso <= safeCeiling -> "MIN_ISO"
            else -> strictest.first
        }
        val reason = when {
            expectedIsoUnclamped > bounds.maxIso -> "iso_max_limits_target_sensitivity"
            releaseLimited -> "gradual_longer_shutter_release_preserves_ae_realization"
            flickerCandidate != releaseLimitedExposure && !flickerSnapAccepted ->
                "flicker_snap_skipped_to_avoid_large_exposure_step"
            flickerSnapAccepted -> "nearby_flicker_safe_shutter_preserves_reference_sensitivity"
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
