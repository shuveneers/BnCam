package com.bncam.core.capture

import kotlin.math.ceil
import kotlin.math.min

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
     * Plan from one fresh, stable Camera2 AE sample.
     *
     * The target sensitivity is preserved whenever sensor limits permit:
     *     targetExposure × targetIso ~= referenceExposure × referenceIso
     *
     * Exposure is made as long as the independently-established safe ceiling permits. If the
     * sensor minimum ISO is reached first, exposure is shortened rather than intentionally
     * overexposing the scene.
     *
     * A measured-motion ceiling is mandatory for activation. A reciprocal-rule/lens fallback may
     * constrain a plan, but cannot by itself declare a scene safe for a longer shutter.
     */
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

        // At min ISO, this is the longest shutter that preserves the AE target sensitivity.
        val exposureAtMinIso = ceil(targetProduct / bounds.minIso.toDouble())
            .toLong()
            .coerceIn(bounds.minExposureNs, bounds.maxExposureNs)

        val unconstrainedTargetExposure = min(safeCeiling, exposureAtMinIso)
            .coerceIn(bounds.minExposureNs, bounds.maxExposureNs)
        val targetExposure = flickerConstraint.constrainExposureNs(
            unconstrainedTargetExposure,
            bounds.minExposureNs,
            bounds.maxExposureNs
        )
        val flickerAdjusted = targetExposure != unconstrainedTargetExposure
        val expectedIsoUnclamped = ceil(targetProduct / targetExposure.toDouble()).toInt()
        val expectedIso = expectedIsoUnclamped.coerceIn(bounds.minIso, bounds.maxIso)

        val limitingConstraint = when {
            flickerAdjusted -> "FLICKER_${flickerConstraint.frequency.name}"
            exposureAtMinIso <= safeCeiling -> "MIN_ISO"
            else -> strictest.first
        }
        val reason = when {
            expectedIsoUnclamped > bounds.maxIso -> "iso_max_limits_target_sensitivity"
            flickerAdjusted -> "flicker_safe_longest_shutter_preserves_reference_sensitivity"
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
