package com.bncam.core.capture

import kotlin.math.log2
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.roundToLong

data class ProfileExposurePriorityPlan(
    val autoExposure: Boolean,
    val ready: Boolean,
    val mode: CaptureExposurePriorityMode,
    val exposureTimeNs: Long?,
    val sensitivityIso: Int?,
    val frameDurationNs: Long?,
    /** Fixed shutter for SHUTTER_PRIORITY, fixed ISO for ISO_PRIORITY, null for adaptive Shot Bias Auto. */
    val fixedPriorityValue: Double?,
    val requestedExposureProduct: Double?,
    val achievedExposureProduct: Double?,
    val limitingConstraint: String,
    val reason: String
) {
    fun summary(): String =
        "mode=${mode.persistedValue};auto=$autoExposure;ready=$ready;exposureNs=${exposureTimeNs ?: "auto"};" +
            "iso=${sensitivityIso ?: "auto"};fixed=${fixedPriorityValue ?: "none"};" +
            "limit=$limitingConstraint;reason=$reason"
}

/**
 * Profile V3 Shot Bias acquisition planner.
 *
 * One fresh Camera2 AE sample is used only as a metering baseline. The user can then keep Auto,
 * choose a fixed ISO / fixed shutter family, bound the maximum per-frame exposure time and add a
 * physical Capture EV bias. Old multiplier-priority profile values are deliberately not executed.
 */
object ProfileExposurePriorityPlanner {
    fun initialPlan(
        preferences: CaptureExposurePreferences,
        measuredIso: Int?,
        measuredExposureNs: Long?,
        bounds: ExposureBounds,
        additionalEvBias: Float = 0f
    ): ProfileExposurePriorityPlan {
        val prefs = preferences.sanitized()
        val cappedBounds = bounds.copy(
            maxExposureNs = prefs.maxFrameExposure.resolveNs(bounds.maxExposureNs)
                .coerceIn(bounds.minExposureNs, bounds.maxExposureNs)
        )

        if (!prefs.requiresAeBaseline() && additionalEvBias.isFinite() && kotlin.math.abs(additionalEvBias) < 0.005f) {
            return autoPlan(ready = true, reason = "camera_ae_shot_bias_auto")
        }
        if (measuredIso == null || measuredIso <= 0 || measuredExposureNs == null || measuredExposureNs <= 0L) {
            return autoPlan(ready = false, reason = "shot_bias_requires_fresh_measured_ae_baseline")
        }

        val baselineIso = measuredIso.coerceIn(cappedBounds.minIso, cappedBounds.maxIso)
        val baselineExposureSensor = measuredExposureNs.coerceIn(bounds.minExposureNs, bounds.maxExposureNs)
        val liveEv = additionalEvBias.takeIf { it.isFinite() }?.coerceIn(-2f, 2f) ?: 0f
        val totalEvBias = (prefs.captureEvBias + liveEv).coerceIn(-4f, 4f)
        val requestedProduct = baselineIso.toDouble() * baselineExposureSensor.toDouble() * 2.0.pow(totalEvBias.toDouble())
        val choice = prefs.shotBiasExposure

        val fixedIso = when {
            choice.useSensorMaxIso -> cappedBounds.maxIso
            choice.fixedIso != null -> choice.fixedIso.coerceIn(cappedBounds.minIso, cappedBounds.maxIso)
            else -> null
        }
        if (fixedIso != null) {
            val exposureUnclamped = (requestedProduct / fixedIso.toDouble()).roundToLong()
            val exposure = exposureUnclamped.coerceIn(cappedBounds.minExposureNs, cappedBounds.maxExposureNs)
            return buildManualPlan(
                mode = CaptureExposurePriorityMode.ISO_PRIORITY,
                exposureTimeNs = exposure,
                sensitivityIso = fixedIso,
                fixedPriorityValue = fixedIso.toDouble(),
                requestedProduct = requestedProduct,
                bounds = cappedBounds,
                primaryLimit = when {
                    exposure != exposureUnclamped -> "MAX_FRAME_EXPOSURE_OR_SENSOR_LIMIT"
                    choice.fixedIso != null && choice.fixedIso != fixedIso -> "ISO_SENSOR_LIMIT"
                    else -> "NONE"
                },
                reason = "shot_bias_fixed_iso:${choice.persistedValue}"
            )
        }

        val fixedTime = when {
            choice.useEffectiveMaxTime -> cappedBounds.maxExposureNs
            choice.fixedExposureNs != null -> choice.fixedExposureNs.coerceIn(cappedBounds.minExposureNs, cappedBounds.maxExposureNs)
            else -> null
        }
        if (fixedTime != null) {
            val isoUnclamped = (requestedProduct / fixedTime.toDouble()).roundToInt()
            val iso = isoUnclamped.coerceIn(cappedBounds.minIso, cappedBounds.maxIso)
            return buildManualPlan(
                mode = CaptureExposurePriorityMode.SHUTTER_PRIORITY,
                exposureTimeNs = fixedTime,
                sensitivityIso = iso,
                fixedPriorityValue = fixedTime.toDouble(),
                requestedProduct = requestedProduct,
                bounds = cappedBounds,
                primaryLimit = when {
                    iso != isoUnclamped -> "ISO_SENSOR_LIMIT"
                    choice.fixedExposureNs != null && choice.fixedExposureNs != fixedTime -> "MAX_FRAME_EXPOSURE_OR_SENSOR_LIMIT"
                    else -> "NONE"
                },
                reason = "shot_bias_fixed_time:${choice.persistedValue}"
            )
        }

        // Auto + a non-default EV/ceiling: retain the measured ISO first, vary shutter, then let ISO
        // absorb the remainder only when the per-frame ceiling is reached.
        val preferredExposureUnclamped = (baselineExposureSensor.toDouble() * 2.0.pow(totalEvBias.toDouble())).roundToLong()
        val exposure = preferredExposureUnclamped.coerceIn(cappedBounds.minExposureNs, cappedBounds.maxExposureNs)
        val isoUnclamped = (requestedProduct / exposure.toDouble()).roundToInt()
        val iso = isoUnclamped.coerceIn(cappedBounds.minIso, cappedBounds.maxIso)
        return buildManualPlan(
            mode = CaptureExposurePriorityMode.BALANCED,
            exposureTimeNs = exposure,
            sensitivityIso = iso,
            fixedPriorityValue = null,
            requestedProduct = requestedProduct,
            bounds = cappedBounds,
            primaryLimit = when {
                exposure != preferredExposureUnclamped -> "MAX_FRAME_EXPOSURE_OR_SENSOR_LIMIT"
                iso != isoUnclamped -> "ISO_SENSOR_LIMIT"
                else -> "NONE"
            },
            reason = "shot_bias_auto_with_ev_or_frame_ceiling"
        )
    }

    fun adaptToLuma(
        previous: ProfileExposurePriorityPlan,
        observedLuma: Float,
        targetLuma: Float,
        clippingFraction: Float,
        bounds: ExposureBounds
    ): ProfileExposurePriorityPlan {
        if (previous.autoExposure || !previous.ready || previous.exposureTimeNs == null || previous.sensitivityIso == null) return previous
        val currentLuma = observedLuma.takeIf { it.isFinite() }?.coerceIn(0.005f, 1.0f) ?: return previous
        val desiredLuma = targetLuma.takeIf { it.isFinite() }?.coerceIn(0.02f, 0.90f) ?: return previous
        val rawCorrectionEv = log2(desiredLuma.toDouble() / currentLuma.toDouble())
        var correctionEv = (rawCorrectionEv * 0.35).coerceIn(-0.50, 0.50)
        val clip = clippingFraction.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0f
        if (clip > 0.010f) correctionEv = minOf(correctionEv, -0.20 - (clip.coerceAtMost(0.08f) * 3.0))

        val currentProduct = previous.exposureTimeNs.toDouble() * previous.sensitivityIso.toDouble()
        val requestedProduct = currentProduct * 2.0.pow(correctionEv)
        return when (previous.mode) {
            CaptureExposurePriorityMode.SHUTTER_PRIORITY -> {
                val fixedExposure = (previous.fixedPriorityValue ?: previous.exposureTimeNs.toDouble())
                    .roundToLong().coerceIn(bounds.minExposureNs, bounds.maxExposureNs)
                val isoUnclamped = (requestedProduct / fixedExposure.toDouble()).roundToInt()
                val iso = isoUnclamped.coerceIn(bounds.minIso, bounds.maxIso)
                buildManualPlan(previous.mode, fixedExposure, iso, fixedExposure.toDouble(), requestedProduct, bounds,
                    if (iso != isoUnclamped) "ISO_LIMIT" else "NONE", "shot_bias_fixed_time_live_luma_adaptation")
            }
            CaptureExposurePriorityMode.ISO_PRIORITY -> {
                val fixedIso = (previous.fixedPriorityValue ?: previous.sensitivityIso.toDouble())
                    .roundToInt().coerceIn(bounds.minIso, bounds.maxIso)
                val exposureUnclamped = (requestedProduct / fixedIso.toDouble()).roundToLong()
                val exposure = exposureUnclamped.coerceIn(bounds.minExposureNs, bounds.maxExposureNs)
                buildManualPlan(previous.mode, exposure, fixedIso, fixedIso.toDouble(), requestedProduct, bounds,
                    if (exposure != exposureUnclamped) "MAX_FRAME_EXPOSURE_OR_SENSOR_LIMIT" else "NONE", "shot_bias_fixed_iso_live_luma_adaptation")
            }
            CaptureExposurePriorityMode.BALANCED -> {
                val exposureUnclamped = (previous.exposureTimeNs.toDouble() * 2.0.pow(correctionEv)).roundToLong()
                val exposure = exposureUnclamped.coerceIn(bounds.minExposureNs, bounds.maxExposureNs)
                val isoUnclamped = (requestedProduct / exposure.toDouble()).roundToInt()
                val iso = isoUnclamped.coerceIn(bounds.minIso, bounds.maxIso)
                buildManualPlan(previous.mode, exposure, iso, null, requestedProduct, bounds,
                    when {
                        exposure != exposureUnclamped -> "MAX_FRAME_EXPOSURE_OR_SENSOR_LIMIT"
                        iso != isoUnclamped -> "ISO_LIMIT"
                        else -> "NONE"
                    }, "shot_bias_auto_live_luma_adaptation")
            }
        }
    }

    private fun autoPlan(ready: Boolean, reason: String) = ProfileExposurePriorityPlan(
        autoExposure = true,
        ready = ready,
        mode = CaptureExposurePriorityMode.BALANCED,
        exposureTimeNs = null,
        sensitivityIso = null,
        frameDurationNs = null,
        fixedPriorityValue = null,
        requestedExposureProduct = null,
        achievedExposureProduct = null,
        limitingConstraint = if (ready) "NONE" else "AWAITING_AE_BASELINE",
        reason = reason
    )

    private fun buildManualPlan(
        mode: CaptureExposurePriorityMode,
        exposureTimeNs: Long,
        sensitivityIso: Int,
        fixedPriorityValue: Double?,
        requestedProduct: Double,
        bounds: ExposureBounds,
        primaryLimit: String,
        reason: String
    ): ProfileExposurePriorityPlan {
        val exposure = exposureTimeNs.coerceIn(bounds.minExposureNs, bounds.maxExposureNs)
        val iso = sensitivityIso.coerceIn(bounds.minIso, bounds.maxIso)
        val achieved = exposure.toDouble() * iso.toDouble()
        return ProfileExposurePriorityPlan(
            autoExposure = false,
            ready = true,
            mode = mode,
            exposureTimeNs = exposure,
            sensitivityIso = iso,
            frameDurationNs = max(exposure, bounds.minExposureNs),
            fixedPriorityValue = fixedPriorityValue,
            requestedExposureProduct = requestedProduct,
            achievedExposureProduct = achieved,
            limitingConstraint = primaryLimit,
            reason = reason
        )
    }
}
