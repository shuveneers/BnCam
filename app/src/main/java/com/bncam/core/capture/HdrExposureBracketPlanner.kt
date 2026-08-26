package com.bncam.core.capture

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Shutter-time exposure plan for computational HDR acquisition.
 *
 * This class never touches pixels. It converts the measured anchor exposure into a bounded
 * Camera2 acquisition plan; alignment/fusion remain native Vulkan work.
 */
enum class HdrBracketRole { HIGHLIGHT, ANCHOR, SHADOW }

enum class HdrExposureControlMode { MANUAL_SENSOR, AE_COMPENSATION }

data class HdrBracketFramePlan(
    val role: HdrBracketRole,
    val targetEvFromAnchor: Float,
    val exposureTimeNs: Long?,
    val sensitivityIso: Int?,
    val aeCompensationIndex: Int?,
    /** Expected exposure product divided by anchor exposure product. */
    val expectedExposureScaleToAnchor: Float
)

data class HdrExposureBracketPlan(
    val enabled: Boolean,
    val controlMode: HdrExposureControlMode?,
    val frames: List<HdrBracketFramePlan>,
    val fallbackReason: String,
    val motionLimited: Boolean
) {
    val anchor: HdrBracketFramePlan?
        get() = frames.firstOrNull { it.role == HdrBracketRole.ANCHOR }
}

data class HdrManualSensorBounds(
    val exposureTimeMinNs: Long,
    val exposureTimeMaxNs: Long,
    val sensitivityIsoMin: Int,
    val sensitivityIsoMax: Int,
    val maxFrameDurationNs: Long
)

data class HdrAeCompensationBounds(
    val minIndex: Int,
    val maxIndex: Int,
    /** Camera2 CONTROL_AE_COMPENSATION_STEP as EV/index. */
    val evPerIndex: Float
)

data class HdrActualBracketValidation(
    val valid: Boolean,
    val reason: String,
    val highlightIntegrationRatio: Float,
    val shadowIntegrationRatio: Float
)

object HdrExposureBracketPlanner {
    private const val HIGHLIGHT_EV = -2.0f
    private const val SHADOW_EV_STILL = 1.5f
    private const val SHADOW_EV_MOTION = 0.75f
    private const val MIN_USEFUL_SEPARATION_EV = 0.45f
    // ISO-only changes cannot recover clipped photocharge or add shadow photons. Require real
    // integration-time separation before calling a bracket computational HDR.
    private const val MAX_HIGHLIGHT_INTEGRATION_RATIO = 0.80f
    private const val MIN_SHADOW_INTEGRATION_RATIO = 1.20f

    fun validateActualBracket(
        anchorExposureTimeNs: Long,
        highlightExposureTimeNs: Long,
        shadowExposureTimeNs: Long,
        highlightExposureScaleToAnchor: Float,
        shadowExposureScaleToAnchor: Float
    ): HdrActualBracketValidation {
        if (anchorExposureTimeNs <= 0L || highlightExposureTimeNs <= 0L || shadowExposureTimeNs <= 0L) {
            return HdrActualBracketValidation(false, "missing_actual_integration_time", 1f, 1f)
        }
        val highlightRatio = highlightExposureTimeNs.toFloat() / anchorExposureTimeNs.toFloat()
        val shadowRatio = shadowExposureTimeNs.toFloat() / anchorExposureTimeNs.toFloat()
        if (!highlightExposureScaleToAnchor.isFinite() || !shadowExposureScaleToAnchor.isFinite()) {
            return HdrActualBracketValidation(false, "non_finite_actual_exposure_scale", highlightRatio, shadowRatio)
        }
        if (highlightExposureScaleToAnchor >= 0.85f || shadowExposureScaleToAnchor <= 1.15f) {
            return HdrActualBracketValidation(false, "actual_exposure_product_separation_insufficient", highlightRatio, shadowRatio)
        }
        if (highlightRatio > MAX_HIGHLIGHT_INTEGRATION_RATIO) {
            return HdrActualBracketValidation(false, "highlight_source_did_not_reduce_integration", highlightRatio, shadowRatio)
        }
        if (shadowRatio < MIN_SHADOW_INTEGRATION_RATIO) {
            return HdrActualBracketValidation(false, "shadow_source_did_not_add_integration", highlightRatio, shadowRatio)
        }
        return HdrActualBracketValidation(true, "none", highlightRatio, shadowRatio)
    }

    fun plan(
        baseExposureTimeNs: Long,
        baseSensitivityIso: Int,
        manualBounds: HdrManualSensorBounds?,
        aeBounds: HdrAeCompensationBounds?,
        motionHigh: Boolean
    ): HdrExposureBracketPlan {
        if (baseExposureTimeNs <= 0L || baseSensitivityIso <= 0) {
            return disabled("missing_anchor_exposure")
        }

        if (manualBounds != null && manualBounds.isValid()) {
            val manualPlan = planManual(
                baseExposureTimeNs = baseExposureTimeNs,
                baseSensitivityIso = baseSensitivityIso,
                bounds = manualBounds,
                motionHigh = motionHigh
            )
            if (manualPlan.enabled) return manualPlan
            // A device can advertise MANUAL_SENSOR yet have current exposure/frame-duration
            // bounds that cannot produce a useful bracket. Do not make HDR a silent no-op when
            // the same camera exposes a usable AE compensation range.
            if (aeBounds != null && aeBounds.isValid()) {
                val aePlan = planAeCompensation(aeBounds, motionHigh)
                if (aePlan.enabled) {
                    return aePlan.copy(
                        fallbackReason = "manual_unavailable_for_scene:${manualPlan.fallbackReason};ae_compensation_used"
                    )
                }
                return disabled(
                    "manual_unavailable_for_scene:${manualPlan.fallbackReason};" +
                        "ae_unavailable_for_scene:${aePlan.fallbackReason}"
                )
            }
            return manualPlan
        }

        if (aeBounds != null && aeBounds.isValid()) {
            return planAeCompensation(aeBounds, motionHigh)
        }

        return disabled("no_manual_sensor_or_ae_compensation_capability")
    }

    private fun planManual(
        baseExposureTimeNs: Long,
        baseSensitivityIso: Int,
        bounds: HdrManualSensorBounds,
        motionHigh: Boolean
    ): HdrExposureBracketPlan {
        val safeMaxExposureNs = minOf(bounds.exposureTimeMaxNs, bounds.maxFrameDurationNs)
        val baseTime = baseExposureTimeNs.coerceIn(bounds.exposureTimeMinNs, safeMaxExposureNs)
        val baseIso = baseSensitivityIso.coerceIn(bounds.sensitivityIsoMin, bounds.sensitivityIsoMax)
        val shadowTargetEv = if (motionHigh) SHADOW_EV_MOTION else SHADOW_EV_STILL

        val highlight = resolveManualExposure(
            role = HdrBracketRole.HIGHLIGHT,
            targetEv = HIGHLIGHT_EV,
            baseExposureTimeNs = baseTime,
            baseSensitivityIso = baseIso,
            bounds = bounds,
            motionHigh = motionHigh
        )
        val anchor = HdrBracketFramePlan(
            role = HdrBracketRole.ANCHOR,
            targetEvFromAnchor = 0f,
            exposureTimeNs = baseTime,
            sensitivityIso = baseIso,
            aeCompensationIndex = null,
            expectedExposureScaleToAnchor = 1f
        )
        val shadow = resolveManualExposure(
            role = HdrBracketRole.SHADOW,
            targetEv = shadowTargetEv,
            baseExposureTimeNs = baseTime,
            baseSensitivityIso = baseIso,
            bounds = bounds,
            motionHigh = motionHigh
        )

        val highlightEv = log2(highlight.expectedExposureScaleToAnchor)
        val shadowEv = log2(shadow.expectedExposureScaleToAnchor)
        if (highlightEv > -MIN_USEFUL_SEPARATION_EV || shadowEv < MIN_USEFUL_SEPARATION_EV) {
            return disabled("sensor_bounds_cannot_produce_useful_bracket")
        }
        val integrationValidation = validateActualBracket(
            anchorExposureTimeNs = baseTime,
            highlightExposureTimeNs = highlight.exposureTimeNs ?: baseTime,
            shadowExposureTimeNs = shadow.exposureTimeNs ?: baseTime,
            highlightExposureScaleToAnchor = highlight.expectedExposureScaleToAnchor,
            shadowExposureScaleToAnchor = shadow.expectedExposureScaleToAnchor
        )
        if (!integrationValidation.valid) {
            return disabled("sensor_bounds_${integrationValidation.reason}")
        }
        return HdrExposureBracketPlan(
            enabled = true,
            controlMode = HdrExposureControlMode.MANUAL_SENSOR,
            // Submit anchor first for natural shutter semantics, then recover both extremes.
            frames = listOf(anchor, highlight, shadow),
            fallbackReason = "none",
            motionLimited = motionHigh
        )
    }

    private fun resolveManualExposure(
        role: HdrBracketRole,
        targetEv: Float,
        baseExposureTimeNs: Long,
        baseSensitivityIso: Int,
        bounds: HdrManualSensorBounds,
        motionHigh: Boolean
    ): HdrBracketFramePlan {
        val scale = 2.0.pow(targetEv.toDouble())
        val minTime = bounds.exposureTimeMinNs
        var maxTime = minOf(bounds.exposureTimeMaxNs, bounds.maxFrameDurationNs)
        if (motionHigh && role == HdrBracketRole.SHADOW) {
            // Preserve subject registration. A moving scene gets less positive-EV authority rather
            // than silently accepting a very long exposure that the ghost mask must discard.
            maxTime = minOf(maxTime, (baseExposureTimeNs * 1.5).toLong().coerceAtLeast(minTime))
        }

        val targetProduct = baseExposureTimeNs.toDouble() * baseSensitivityIso.toDouble() * scale
        val timeNs: Long
        val iso: Int
        if (targetEv < 0f) {
            // Highlight source: shorten integration first. This captures real highlight headroom;
            // lowering only analogue gain would not recover already saturated photocharge.
            timeNs = (baseExposureTimeNs * scale).toLong().coerceIn(minTime, maxTime)
            iso = (targetProduct / timeNs.toDouble()).roundToInt()
                .coerceIn(bounds.sensitivityIsoMin, bounds.sensitivityIsoMax)
        } else {
            // Shadow source: increase photon integration first. ISO is used only for residual
            // product matching after the safe exposure-time limit is reached.
            timeNs = (baseExposureTimeNs * scale).toLong().coerceIn(minTime, maxTime)
            iso = (targetProduct / timeNs.toDouble()).roundToInt()
                .coerceIn(bounds.sensitivityIsoMin, bounds.sensitivityIsoMax)
        }
        val actualScale = (
            timeNs.toDouble() * iso.toDouble() /
                (baseExposureTimeNs.toDouble() * baseSensitivityIso.toDouble())
            ).toFloat().coerceAtLeast(1.0e-4f)
        return HdrBracketFramePlan(
            role = role,
            targetEvFromAnchor = targetEv,
            exposureTimeNs = timeNs,
            sensitivityIso = iso,
            aeCompensationIndex = null,
            expectedExposureScaleToAnchor = actualScale
        )
    }

    private fun planAeCompensation(
        bounds: HdrAeCompensationBounds,
        motionHigh: Boolean
    ): HdrExposureBracketPlan {
        val shadowTarget = if (motionHigh) SHADOW_EV_MOTION else SHADOW_EV_STILL
        fun quantize(ev: Float): Pair<Int, Float> {
            val index = (ev / bounds.evPerIndex).roundToInt().coerceIn(bounds.minIndex, bounds.maxIndex)
            return index to (index * bounds.evPerIndex)
        }
        val (highlightIndex, highlightEv) = quantize(HIGHLIGHT_EV)
        val (shadowIndex, shadowEv) = quantize(shadowTarget)
        if (highlightEv > -MIN_USEFUL_SEPARATION_EV || shadowEv < MIN_USEFUL_SEPARATION_EV) {
            return disabled("ae_compensation_range_cannot_produce_useful_bracket")
        }
        fun frame(role: HdrBracketRole, index: Int, ev: Float) = HdrBracketFramePlan(
            role = role,
            targetEvFromAnchor = ev,
            exposureTimeNs = null,
            sensitivityIso = null,
            aeCompensationIndex = index,
            expectedExposureScaleToAnchor = 2.0.pow(ev.toDouble()).toFloat()
        )
        return HdrExposureBracketPlan(
            enabled = true,
            controlMode = HdrExposureControlMode.AE_COMPENSATION,
            frames = listOf(
                frame(HdrBracketRole.ANCHOR, 0.coerceIn(bounds.minIndex, bounds.maxIndex), 0f),
                frame(HdrBracketRole.HIGHLIGHT, highlightIndex, highlightEv),
                frame(HdrBracketRole.SHADOW, shadowIndex, shadowEv)
            ),
            fallbackReason = "none",
            motionLimited = motionHigh
        )
    }

    private fun HdrManualSensorBounds.isValid(): Boolean =
        exposureTimeMinNs > 0L && exposureTimeMaxNs >= exposureTimeMinNs &&
            sensitivityIsoMin > 0 && sensitivityIsoMax >= sensitivityIsoMin && maxFrameDurationNs > 0L

    private fun HdrAeCompensationBounds.isValid(): Boolean =
        minIndex <= 0 && maxIndex >= 0 && evPerIndex.isFinite() && evPerIndex > 0f

    private fun disabled(reason: String) = HdrExposureBracketPlan(
        enabled = false,
        controlMode = null,
        frames = emptyList(),
        fallbackReason = reason,
        motionLimited = false
    )

    private fun log2(value: Float): Float =
        (ln(value.coerceAtLeast(1.0e-6f).toDouble()) / ln(2.0)).toFloat()
}
