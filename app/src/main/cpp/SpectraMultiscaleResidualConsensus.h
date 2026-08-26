#pragma once

#include <algorithm>
#include <cmath>

#include "SpectraNoiseProfileUncertainty.h"

namespace bncam::spectra2 {

struct MultiscaleResidualConsensusDecision {
    float contextAgreement = 0.0f;
    float centerOutlierEvidence = 0.0f;
    float persistentStructureProtection = 0.0f;
    float midWeight = 0.7f;
    float coarseWeight = 0.3f;
    float contextMix = 0.0f;
};

inline float residualConsensusSmoothstep(float edge0, float edge1, float value) {
    if (!std::isfinite(value) || !(edge1 > edge0)) return 0.0f;
    const float t = std::clamp((value - edge0) / (edge1 - edge0), 0.0f, 1.0f);
    return t * t * (3.0f - 2.0f * t);
}

// SPECTRA Context Fusion: analytic coarse-to-fine residual consensus.
//
// The local Wiener estimator remains the fine-scale owner. This policy only
// releases additional authority when two wider same-CFA estimates agree with
// each other while the centre remains a statistically plausible noise outlier.
// Persistent multiscale structure suppresses that wider correction. All inputs
// are expressed in approximately unit-noise VST coordinates, so this policy is
// conditioned by the physical Poisson-Gaussian sensor model rather than raw
// gradient magnitude.
//
// This deliberately adopts the useful principle behind modern CFA-domain
// denoisers -- broad context decides whether high-frequency energy is signal or
// chance -- without embedding external model code or pretending to be a trained
// neural prior. The existing No-Regret solver remains the final hard residual and
// detail-retention boundary.
inline MultiscaleResidualConsensusDecision resolveMultiscaleResidualConsensus(
        float centerToFineResidualZ,
        float midCoarseDisagreementZ,
        float persistentStructureZ,
        float flatContext,
        float combinedNoisePressure,
        float modelConfidence) {
    MultiscaleResidualConsensusDecision out{};

    const float confidence = std::clamp(
            std::isfinite(modelConfidence) ? modelConfidence : 0.0f,
            0.0f, 1.0f);
    const float uncertaintyEnvelope = resolveNoiseProfileUncertaintyEnvelope(confidence);
    const float centerResidual = std::max(
            0.0f,
            std::isfinite(centerToFineResidualZ) ? centerToFineResidualZ : 0.0f) /
            uncertaintyEnvelope;
    const float disagreement = std::max(
            0.0f,
            std::isfinite(midCoarseDisagreementZ) ? midCoarseDisagreementZ : 8.0f) /
            uncertaintyEnvelope;
    const float persistentStructure = std::max(
            0.0f,
            std::isfinite(persistentStructureZ) ? persistentStructureZ : 8.0f) /
            uncertaintyEnvelope;
    const float flat = std::clamp(
            std::isfinite(flatContext) ? flatContext : 0.0f,
            0.0f, 1.0f);
    const float noise = std::clamp(
            std::isfinite(combinedNoisePressure) ? combinedNoisePressure : 0.0f,
            0.0f, 1.0f);

    // Wider estimates must agree before they are allowed to steer the fine
    // solution. This is the central anti-blur invariant: a gradient or real
    // low-frequency transition usually makes the two contextual estimates
    // disagree, while stochastic centre noise does not.
    out.contextAgreement = 1.0f - residualConsensusSmoothstep(
            0.70f, 2.35f, disagreement);

    // A clean centre needs no extra contextual correction. Authority rises only
    // once the centre deviates from its already noise-aware fine estimate.
    out.centerOutlierEvidence = residualConsensusSmoothstep(
            0.55f, 2.65f, centerResidual);

    out.persistentStructureProtection = residualConsensusSmoothstep(
            1.55f, 4.15f, persistentStructure);

    // At higher physical noise pressure a little more of the coarse estimate is
    // useful, but the mid scale always owns the majority to preserve locality.
    out.coarseWeight = std::clamp(0.22f + 0.20f * noise, 0.22f, 0.42f);
    out.midWeight = 1.0f - out.coarseWeight;

    const float contextualNeed = (0.18f + 0.44f * noise) *
            out.contextAgreement *
            out.centerOutlierEvidence *
            (0.22f + 0.78f * flat);
    const float structureGuard = 1.0f - 0.92f * out.persistentStructureProtection;

    // Deliberately capped: this stage strengthens the existing candidate but
    // never becomes a free-standing blur. No-Regret and the per-pixel linear
    // shift guard still apply after this mix.
    out.contextMix = std::clamp(
            contextualNeed * std::max(0.0f, structureGuard),
            0.0f, 0.62f);
    return out;
}

}  // namespace bncam::spectra2
