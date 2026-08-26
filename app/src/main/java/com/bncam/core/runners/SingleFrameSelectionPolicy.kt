package com.bncam.core.runners

import android.hardware.camera2.CaptureResult

internal enum class SingleFrameSelectionBias {
    BALANCED,
    RECENCY,
    SHARPNESS,
    MOTION,
    EXPOSURE
}

internal data class SingleFrameSelectionSettings(
    val configuredBaseBias: String,
    val configuredFrameBias: String,
    val configuredAcceptAll: Boolean,
    val configuredAlignableOnly: Boolean,
    val configuredPreferRecent: Boolean,
    val effectiveBias: SingleFrameSelectionBias,
    val effectiveBiasSource: String,
    val baseBiasApplied: Boolean,
    val frameBiasApplied: Boolean,
    val acceptAllStatus: String =
        "DEPRECATED_SINGLE_FRAME_EXACTLY_ONE_ANCHOR",
    val alignableOnlyStatus: String =
        "DEPRECATED_SINGLE_FRAME_NO_SUPPORT_FRAME_ALIGNMENT"
) {
    val effectiveBiasLabel: String get() = effectiveBias.name
}

internal object SingleFrameSelectionSettingsResolver {
    private data class ParsedBias(
        val bias: SingleFrameSelectionBias,
        val explicit: Boolean,
        val recognized: Boolean
    )

    private fun parse(value: String): ParsedBias {
        val normalized = value.trim().lowercase()
        if (
            normalized.isBlank() ||
            normalized == "auto" ||
            normalized.contains("overall") ||
            normalized.contains("balanced")
        ) {
            return ParsedBias(
                bias = SingleFrameSelectionBias.BALANCED,
                explicit = normalized.isNotBlank() && normalized != "auto",
                recognized = true
            )
        }
        return when {
            normalized.contains("recent") || normalized.contains("latest") ->
                ParsedBias(SingleFrameSelectionBias.RECENCY, true, true)
            normalized.contains("sharp") || normalized.contains("detail") ->
                ParsedBias(SingleFrameSelectionBias.SHARPNESS, true, true)
            normalized.contains("motion") || normalized.contains("stable") ->
                ParsedBias(SingleFrameSelectionBias.MOTION, true, true)
            normalized.contains("exposure") || normalized == "ev" ||
                    normalized.contains("highlight") ->
                ParsedBias(SingleFrameSelectionBias.EXPOSURE, true, true)
            else ->
                ParsedBias(SingleFrameSelectionBias.BALANCED, false, false)
        }
    }

    fun resolve(
        baseBias: String,
        frameBias: String,
        acceptAll: Boolean,
        alignableOnly: Boolean,
        preferRecent: Boolean
    ): SingleFrameSelectionSettings {
        val parsedFrame = parse(frameBias)
        val parsedBase = parse(baseBias)
        val effectiveBias: SingleFrameSelectionBias
        val source: String
        val frameApplied: Boolean
        val baseApplied: Boolean
        when {
            preferRecent -> {
                effectiveBias = SingleFrameSelectionBias.RECENCY
                source = "selection_prefer_recent"
                frameApplied = false
                baseApplied = false
            }
            parsedFrame.explicit && parsedFrame.recognized -> {
                effectiveBias = parsedFrame.bias
                source = "selection_frame_bias"
                frameApplied = true
                baseApplied = false
            }
            parsedBase.recognized -> {
                effectiveBias = parsedBase.bias
                source = "base_bias"
                frameApplied = false
                baseApplied = true
            }
            else -> {
                effectiveBias = SingleFrameSelectionBias.BALANCED
                source = "safe_balanced_fallback_unrecognized_settings"
                frameApplied = false
                baseApplied = false
            }
        }
        return SingleFrameSelectionSettings(
            configuredBaseBias = baseBias,
            configuredFrameBias = frameBias,
            configuredAcceptAll = acceptAll,
            configuredAlignableOnly = alignableOnly,
            configuredPreferRecent = preferRecent,
            effectiveBias = effectiveBias,
            effectiveBiasSource = source,
            baseBiasApplied = baseApplied,
            frameBiasApplied = frameApplied
        )
    }
}

internal data class SingleFrameSelectionWeights(
    val freshness: Double,
    val sharpness: Double,
    val motion: Double,
    val exposure: Double,
    val clipping: Double,
    val ae: Double,
    val awb: Double,
    val focus: Double,
    val alignability: Double
) {
    init {
        val sum = freshness + sharpness + motion + exposure + clipping +
                ae + awb + focus + alignability
        require(kotlin.math.abs(sum - 1.0) < 0.000001) {
            "Single-frame selection weights must total 1.0, got $sum"
        }
    }
}

internal object SingleFrameSelectionWeightPolicy {
    fun resolve(
        bias: SingleFrameSelectionBias,
        coldStart: Boolean
    ): SingleFrameSelectionWeights {
        if (coldStart) {
            return when (bias) {
                SingleFrameSelectionBias.BALANCED ->
                    SingleFrameSelectionWeights(0.11, 0.40, 0.06, 0.04, 0.04, 0.03, 0.02, 0.30, 0.00)
                SingleFrameSelectionBias.RECENCY ->
                    SingleFrameSelectionWeights(0.39, 0.24, 0.10, 0.03, 0.04, 0.03, 0.02, 0.15, 0.00)
                SingleFrameSelectionBias.SHARPNESS ->
                    SingleFrameSelectionWeights(0.12, 0.51, 0.08, 0.03, 0.04, 0.03, 0.02, 0.17, 0.00)
                SingleFrameSelectionBias.MOTION ->
                    SingleFrameSelectionWeights(0.16, 0.25, 0.29, 0.03, 0.04, 0.03, 0.02, 0.18, 0.00)
                SingleFrameSelectionBias.EXPOSURE ->
                    SingleFrameSelectionWeights(0.15, 0.25, 0.08, 0.16, 0.14, 0.04, 0.03, 0.15, 0.00)
            }
        }
        return when (bias) {
            SingleFrameSelectionBias.BALANCED ->
                SingleFrameSelectionWeights(0.12, 0.35, 0.15, 0.07, 0.09, 0.08, 0.08, 0.06, 0.00)
            SingleFrameSelectionBias.RECENCY ->
                SingleFrameSelectionWeights(0.52, 0.12, 0.12, 0.05, 0.06, 0.05, 0.03, 0.05, 0.00)
            SingleFrameSelectionBias.SHARPNESS ->
                SingleFrameSelectionWeights(0.25, 0.35, 0.10, 0.06, 0.08, 0.06, 0.04, 0.06, 0.00)
            SingleFrameSelectionBias.MOTION ->
                SingleFrameSelectionWeights(0.27, 0.12, 0.33, 0.05, 0.07, 0.06, 0.04, 0.06, 0.00)
            SingleFrameSelectionBias.EXPOSURE ->
                SingleFrameSelectionWeights(0.25, 0.13, 0.09, 0.19, 0.17, 0.07, 0.05, 0.05, 0.00)
        }
    }
}

internal data class FrameMotionScore(
    val score: Double,
    val exposureDurationScore: Double,
    val oisBenefit: Double,
    val lensStateFactor: Double,
    val afStateFactor: Double,
    val model: String = "EXPOSURE_OIS_LENS_AF_V1"
) {
    fun diagnosticSummary(): String =
        "model=$model;score=$score;exposureDurationScore=$exposureDurationScore;" +
                "oisBenefit=$oisBenefit;lensStateFactor=$lensStateFactor;" +
                "afStateFactor=$afStateFactor"
}

internal object FrameMotionScorer {
    fun score(
        exposureTimeNs: Long,
        oisMode: Int?,
        lensState: Int?,
        afState: Int?
    ): FrameMotionScore {
        val exposureMs = exposureTimeNs.coerceAtLeast(0L) / 1_000_000.0
        val exposureScore = when {
            exposureTimeNs <= 0L -> 0.50
            exposureMs <= 8.0 -> 1.00
            exposureMs <= 16.0 -> 1.00 - ((exposureMs - 8.0) / 8.0) * 0.10
            exposureMs <= 33.3 -> 0.90 - ((exposureMs - 16.0) / 17.3) * 0.25
            exposureMs <= 66.0 -> 0.65 - ((exposureMs - 33.3) / 32.7) * 0.30
            exposureMs <= 100.0 -> 0.35 - ((exposureMs - 66.0) / 34.0) * 0.20
            else -> 0.10
        }.coerceIn(0.10, 1.0)

        val oisBenefit =
            if (oisMode == CaptureResult.LENS_OPTICAL_STABILIZATION_MODE_ON) {
                (1.0 - exposureScore) * 0.30
            } else {
                0.0
            }
        val lensStateFactor =
            if (lensState == CaptureResult.LENS_STATE_MOVING) 0.55 else 1.0
        val afStateFactor = when (afState) {
            CaptureResult.CONTROL_AF_STATE_ACTIVE_SCAN,
            CaptureResult.CONTROL_AF_STATE_PASSIVE_SCAN -> 0.65
            CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED,
            CaptureResult.CONTROL_AF_STATE_PASSIVE_UNFOCUSED -> 0.85
            else -> 1.0
        }
        val score =
            ((exposureScore + oisBenefit) * lensStateFactor * afStateFactor)
                .coerceIn(0.0, 1.0)
        return FrameMotionScore(
            score = score,
            exposureDurationScore = exposureScore,
            oisBenefit = oisBenefit,
            lensStateFactor = lensStateFactor,
            afStateFactor = afStateFactor
        )
    }
}

internal object TrackedSubjectSelectionPolicy {
    /**
     * While the shutter was pressed during reliable Focus Track, ROI focus confidence should
     * outweigh full-frame sharpness. Preserve the total weight exactly by moving part of the
     * global sharpness budget into the focus budget.
     */
    fun adjustWeights(
        base: SingleFrameSelectionWeights,
        reliableTrackedSubject: Boolean
    ): SingleFrameSelectionWeights {
        if (!reliableTrackedSubject) return base
        val retainedSharpness = base.sharpness * 0.45
        val transferred = base.sharpness - retainedSharpness
        return base.copy(
            sharpness = retainedSharpness,
            focus = base.focus + transferred
        )
    }

    fun focusScore(
        afStateScore: Double,
        roiFocusConfidence: Double,
        trackedRoiEvidence: Boolean,
        reliableTrackedSubject: Boolean
    ): Double {
        if (!reliableTrackedSubject) {
            return (afStateScore * 0.4 + roiFocusConfidence * 0.6).coerceIn(-1.0, 1.0)
        }
        if (!trackedRoiEvidence) return -0.65
        return (afStateScore * 0.20 + roiFocusConfidence * 0.80).coerceIn(-1.0, 1.0)
    }
}

internal data class TrackedSubjectAnchorEvidence(
    val index: Int,
    val trackingOwned: Boolean,
    val focusEvaluated: Boolean,
    val hasAfRegion: Boolean,
    val focusConfidence: Double,
    val ageMs: Double,
    val lensMoving: Boolean
)

internal object TrackedSubjectAnchorPolicy {
    fun selectIndex(
        candidates: List<TrackedSubjectAnchorEvidence>,
        enabled: Boolean,
        freshnessWindowMs: Double = 350.0
    ): Int? {
        if (!enabled) return null
        val eligible = candidates.filter {
            it.trackingOwned && it.focusEvaluated && it.hasAfRegion &&
                it.ageMs >= 0.0 && it.ageMs <= freshnessWindowMs
        }
        return eligible.maxByOrNull { evidence ->
            val focus = evidence.focusConfidence.coerceIn(0.0, 1.0)
            val freshness = (1.0 - evidence.ageMs / freshnessWindowMs).coerceIn(0.0, 1.0)
            val lensStability = if (evidence.lensMoving) 0.55 else 1.0
            (focus * 0.72) + (freshness * 0.18) + (lensStability * 0.10)
        }?.index
    }
}
