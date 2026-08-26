#pragma once

#include "SpectraChromaBands.h"

#include <algorithm>
#include <cmath>

#include "SpectraNoiseProfileUncertainty.h"
#include <string>

namespace bncam::spectra2 {

/**
 * Runtime decision for one band-limited chroma coefficient.
 *
 * The coefficient is measured in the pre-demosaic R-Gref/B-Gref domain. The
 * decision only returns a bounded correction fraction; the caller remains
 * responsible for sensor-domain maximum-shift limits and No-Regret gating.
 */
struct ChromaBandKernelDecision {
    ChromaBandKind kind = ChromaBandKind::Fine;
    bool supported = false;
    std::string status = "UNINITIALIZED";
    float coefficient = 0.0f;
    float noiseSigma = 0.0f;
    float effectiveNoiseSigma = 0.0f;
    float normalizedMagnitude = 0.0f;
    float retainedFraction = 1.0f;
    float shrinkage = 0.0f;
    float correction = 0.0f;
    float structureWeight = 0.0f;
    float shadowWeight = 0.0f;
    float visiblePressure = 1.0f;
};

inline float finiteUnit(float value) {
    return std::isfinite(value) ? std::clamp(value, 0.0f, 1.0f) : 0.0f;
}

inline float finitePositive(float value, float fallback = 0.0f) {
    return std::isfinite(value) && value > 0.0f ? value : fallback;
}

struct MidBandCoefficient {
    float localRadius2 = 0.0f;
    float broadRadius4To6 = 0.0f;
    float coefficient = 0.0f;
};

/**
 * Explainable à-trous-style mid-band coefficient. The local estimate includes
 * the centre residual and radius-2 same-colour support; the broad estimate
 * blends radius-4 and radius-6 support. A constant chroma field therefore
 * produces exactly zero coefficient.
 */
inline MidBandCoefficient resolveMidBandCoefficient(
        float centerResidual,
        float radius2CrossMean,
        float radius4CrossMean,
        float radius6CrossMean
) {
    MidBandCoefficient result{};
    if (!std::isfinite(centerResidual) || !std::isfinite(radius2CrossMean) ||
        !std::isfinite(radius4CrossMean) || !std::isfinite(radius6CrossMean)) {
        return result;
    }
    result.localRadius2 = (centerResidual + 2.0f * radius2CrossMean) / 3.0f;
    const float broad4 = (centerResidual + 4.0f * radius4CrossMean) / 5.0f;
    const float broad6 = (centerResidual + 4.0f * radius6CrossMean) / 5.0f;
    result.broadRadius4To6 = 0.40f * broad4 + 0.60f * broad6;
    result.coefficient = result.localRadius2 - result.broadRadius4To6;
    return result;
}

/**
 * Noise-aware Wiener-style shrinkage for the fine and mid bands.
 *
 * Small coefficients that are compatible with the predicted noise variance are
 * attenuated. Large coefficients retain progressively more energy, which protects
 * real colour structure. A strong isolated fine-band impulse may receive extra
 * shrinkage only when the luminance/green structure weight is high.
 */
inline ChromaBandKernelDecision resolveBandKernelDecision(
        ChromaBandKind kind,
        float coefficient,
        float noiseSigma,
        float planAuthority,
        float modelConfidence,
        float structureWeight,
        float shadowWeight,
        float visibleChromaAmplification,
        float impulseScore = 0.0f
) {
    ChromaBandKernelDecision decision{};
    decision.kind = kind;
    decision.coefficient = std::isfinite(coefficient) ? coefficient : 0.0f;
    decision.noiseSigma = finitePositive(noiseSigma);
    decision.structureWeight = finiteUnit(structureWeight);
    decision.shadowWeight = finiteUnit(shadowWeight);
    decision.visiblePressure = std::isfinite(visibleChromaAmplification)
            ? std::clamp(visibleChromaAmplification, 0.50f, 4.0f)
            : 1.0f;

    const float confidence = finiteUnit(modelConfidence);
    const float authority = confidence >= 0.10f
            ? finiteUnit(planAuthority) * resolveNoiseProfileAuthorityConfidence(confidence)
            : 0.0f;
    if (kind == ChromaBandKind::Low) {
        decision.status = "LOW_BAND_REQUIRES_FIELD_DECISION";
        return decision;
    }
    if (decision.noiseSigma <= 0.0f || authority <= 0.0f) {
        decision.status = "INVALID_NOISE_OR_AUTHORITY";
        return decision;
    }
    if (decision.structureWeight < 0.015f || decision.shadowWeight <= 0.0f) {
        decision.status = "STRUCTURE_OR_EXPOSURE_GUARD";
        return decision;
    }

    // A local coefficient has approximately full per-pixel chroma noise, while
    // the wider mid-band coefficient averages several samples and therefore has
    // a lower effective random-noise sigma.
    const float bandSigmaScale = kind == ChromaBandKind::Fine ? 1.00f : 0.58f;
    decision.effectiveNoiseSigma = std::max(1.0e-7f, decision.noiseSigma * bandSigmaScale);
    decision.normalizedMagnitude = std::abs(decision.coefficient) /
            decision.effectiveNoiseSigma;

    const float coefficientVariance = decision.coefficient * decision.coefficient;
    const float noiseVariance = decision.effectiveNoiseSigma * decision.effectiveNoiseSigma;
    const float signalVariance = std::max(0.0f, coefficientVariance - noiseVariance);
    decision.retainedFraction = coefficientVariance > 1.0e-14f
            ? std::clamp(signalVariance / (coefficientVariance + 1.0e-14f), 0.0f, 1.0f)
            : 0.0f;
    float baseShrinkage = 1.0f - decision.retainedFraction;

    if (kind == ChromaBandKind::Fine && std::isfinite(impulseScore)) {
        if (impulseScore >= 2.75f) {
            baseShrinkage = std::max(baseShrinkage, 0.72f);
        } else if (impulseScore >= 1.75f) {
            baseShrinkage = std::max(baseShrinkage, 0.46f);
        }
    }

    const float visibleBoost = std::clamp(
            0.90f + 0.10f * std::max(0.0f, decision.visiblePressure - 1.0f),
            0.90f,
            1.15f
    );
    const float bandCeiling = kind == ChromaBandKind::Fine ? 0.92f : 0.78f;
    decision.shrinkage = std::clamp(
            baseShrinkage * authority * decision.structureWeight *
                    decision.shadowWeight * visibleBoost,
            0.0f,
            bandCeiling
    );
    decision.correction = -decision.coefficient * decision.shrinkage;
    decision.supported = std::abs(decision.correction) >= 1.0e-9f;
    decision.status = decision.supported
            ? (kind == ChromaBandKind::Fine
                    ? "FINE_WIENER_SHRINKAGE_SUPPORTED"
                    : "MID_ATROUS_SHRINKAGE_SUPPORTED")
            : "COEFFICIENT_RETAINED";
    return decision;
}

/**
 * Continuous low-frequency field decision. The field is already scene-trend
 * rejected and spatially interpolated by Pass 3. Only coherent magnitude above
 * a conservative model-derived floor receives correction.
 */
inline ChromaBandKernelDecision resolveLowBandFieldDecision(
        float fieldCoefficient,
        float noiseSigma,
        float planAuthority,
        float modelConfidence,
        float structureWeight,
        float spatialConfidence,
        float visibleChromaAmplification
) {
    ChromaBandKernelDecision decision{};
    decision.kind = ChromaBandKind::Low;
    decision.coefficient = std::isfinite(fieldCoefficient) ? fieldCoefficient : 0.0f;
    decision.noiseSigma = finitePositive(noiseSigma);
    decision.structureWeight = finiteUnit(structureWeight);
    decision.shadowWeight = finiteUnit(spatialConfidence);
    decision.visiblePressure = std::isfinite(visibleChromaAmplification)
            ? std::clamp(visibleChromaAmplification, 0.50f, 4.0f)
            : 1.0f;

    const float confidence = finiteUnit(modelConfidence);
    if (decision.noiseSigma <= 0.0f || finiteUnit(planAuthority) <= 0.0f || confidence < 0.10f) {
        decision.status = "INVALID_NOISE_OR_AUTHORITY";
        return decision;
    }
    if (decision.structureWeight < 0.03f || decision.shadowWeight < 0.15f) {
        decision.status = "STRUCTURE_OR_FIELD_CONFIDENCE_GUARD";
        return decision;
    }

    // Confidence is evidence quality, not a second hidden denoise-strength slider.
    // Once the field and model pass their hard guards, retain a bounded soft
    // confidence factor. This mirrors SPECTRA's visible-chroma uncertainty
    // semantics and prevents medium-confidence coherent cloud fields from being
    // linearly attenuated twice before No-Regret gets a chance to arbitrate.
    const float modelAuthority = resolveNoiseProfileAuthorityConfidence(confidence);
    const float fieldAuthority = 0.55f + 0.45f * std::sqrt(decision.shadowWeight);
    const float authority = finiteUnit(planAuthority) * modelAuthority * fieldAuthority;

    decision.effectiveNoiseSigma = std::max(1.0e-7f, decision.noiseSigma * 0.30f);
    const float visibleRelief = std::clamp(
            1.0f - 0.12f * std::max(0.0f, decision.visiblePressure - 1.0f),
            0.72f,
            1.0f
    );
    const float activationFloor = decision.effectiveNoiseSigma * 0.85f * visibleRelief;
    decision.normalizedMagnitude = std::abs(decision.coefficient) /
            decision.effectiveNoiseSigma;
    const float supportedMagnitude = std::max(
            0.0f,
            std::abs(decision.coefficient) - activationFloor
    );
    if (supportedMagnitude <= 0.0f) {
        decision.status = "LOW_FIELD_BELOW_COHERENT_FLOOR";
        return decision;
    }

    const float signedSupported = std::copysign(supportedMagnitude, decision.coefficient);
    const float visibleBoost = std::clamp(
            0.88f + 0.10f * std::max(0.0f, decision.visiblePressure - 1.0f),
            0.88f,
            1.10f
    );
    decision.shrinkage = std::clamp(
            authority * decision.structureWeight * visibleBoost,
            0.0f,
            0.68f
    );
    decision.correction = -signedSupported * decision.shrinkage;
    decision.retainedFraction = std::abs(decision.coefficient) > 1.0e-12f
            ? std::clamp(
                    1.0f - std::abs(decision.correction) /
                            std::abs(decision.coefficient),
                    0.0f,
                    1.0f
            )
            : 1.0f;
    decision.supported = std::abs(decision.correction) >= 1.0e-9f;
    decision.status = decision.supported
            ? "LOW_CONTINUOUS_FIELD_SOFT_THRESHOLD_SUPPORTED"
            : "LOW_FIELD_RETAINED";
    return decision;
}

} // namespace bncam::spectra2
