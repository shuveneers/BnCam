#pragma once

#include "SpectraNoiseProfileUncertainty.h"

#include <algorithm>
#include <cmath>

namespace bncam::spectra2 {

struct MultiscaleChromaResidualConsensusDecision {
    bool supported = false;
    float contextualResidual = 0.0f;
    float scaleAgreement = 0.0f;
    float stochasticOutlierEvidence = 0.0f;
    float chromaStructureEvidence = 0.0f;
    float contextMix = 0.0f;
};

inline float spectraSmoothstep(float lo, float hi, float x) {
    if (!(hi > lo)) return x >= hi ? 1.0f : 0.0f;
    const float t = std::clamp((x - lo) / (hi - lo), 0.0f, 1.0f);
    return t * t * (3.0f - 2.0f * t);
}

// Analytic counterpart of Ansel's coarse-context principle for SPECTRA's
// pre-demosaic opponent domain. It does not invent image content: it only lets
// robust mid/coarse same-CFA residual estimates steer the existing local target
// when multiple scales agree that the center is a stochastic outlier.
inline MultiscaleChromaResidualConsensusDecision resolveMultiscaleChromaResidualConsensus(
        float centerResidual,
        float fineTargetResidual,
        float midResidual,
        float coarseResidual,
        float localResidualSpread,
        float noiseSigma,
        float structureWeight,
        float modelConfidence) {
    MultiscaleChromaResidualConsensusDecision out{};
    if (!std::isfinite(centerResidual) || !std::isfinite(fineTargetResidual) ||
        !std::isfinite(midResidual) || !std::isfinite(coarseResidual) ||
        !std::isfinite(localResidualSpread) || !std::isfinite(noiseSigma) ||
        noiseSigma <= 0.0f) {
        return out;
    }

    const float structure = std::clamp(
            std::isfinite(structureWeight) ? structureWeight : 0.0f,
            0.0f, 1.0f);
    if (structure < 0.12f) return out;

    const float uncertainty = resolveNoiseProfileUncertaintyEnvelope(modelConfidence);
    const float sigma = std::max(1.0e-7f, noiseSigma * uncertainty);
    out.contextualResidual = 0.62f * midResidual + 0.38f * coarseResidual;

    const float agreementZ = std::abs(midResidual - coarseResidual) / sigma;
    out.scaleAgreement = 1.0f - spectraSmoothstep(1.15f, 3.20f, agreementZ);

    const float outlierZ = std::abs(centerResidual - out.contextualResidual) / sigma;
    out.stochasticOutlierEvidence = spectraSmoothstep(0.65f, 2.35f, outlierZ);

    const float spreadZ = std::max(0.0f, localResidualSpread) / sigma;
    out.chromaStructureEvidence = spectraSmoothstep(2.80f, 5.80f, spreadZ);

    // Fine target remains the primary estimator. Wider scales can contribute at
    // most 58%, and only in flat/noise-like regions with cross-scale agreement.
    const float flatAuthority = std::sqrt(structure);
    out.contextMix = std::clamp(
            0.58f * out.scaleAgreement * out.stochasticOutlierEvidence *
                    (1.0f - out.chromaStructureEvidence) * flatAuthority,
            0.0f, 0.58f);
    out.supported = out.contextMix >= 0.01f;
    return out;
}

}  // namespace bncam::spectra2
