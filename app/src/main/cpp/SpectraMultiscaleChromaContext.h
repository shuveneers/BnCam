#pragma once

#include <algorithm>
#include <cmath>

#include "SpectraNoiseProfileUncertainty.h"

namespace bncam::spectra2 {

struct MultiscaleChromaContextDecision {
    float fineStructureEvidence = 0.0f;
    float coarseStructureEvidence = 0.0f;
    float coherentStructureEvidence = 0.0f;
    float stochasticFineEvidence = 0.0f;
    // Same semantics as the existing Pass-2 structureWeight:
    // 1 = correction may proceed, 0 = protect scene structure.
    float structureWeight = 1.0f;
};

inline float chromaContextWeightSingleScale(
        float observedGradient,
        float expectedNoiseGradient,
        float sensitivity) {
    const float observed = std::max(
            0.0f, std::isfinite(observedGradient) ? observedGradient : 0.0f);
    const float floorValue = std::max(
            1.0e-5f,
            std::isfinite(expectedNoiseGradient) ? expectedNoiseGradient : 0.0f);
    const float boundedSensitivity = std::max(
            0.25f, std::isfinite(sensitivity) ? sensitivity : 1.0f);
    const float excess = std::max(0.0f, observed - 1.12f * floorValue);
    const float normalizedExcess = excess / std::max(0.0015f, 1.35f * floorValue);
    return std::clamp(
            std::exp(-boundedSensitivity * normalizedExcess),
            0.0f, 1.0f);
}

// SPECTRA Context Fusion chroma policy.
//
// A single noisy green/CFA gradient must not be enough to protect a colour
// residual. Real scene structure tends to persist at a broader support while
// random CFA noise decorrelates. Fine and coarse gradients are independently
// normalized by their predicted sensor-noise floor, then fused as evidence.
// This is an analytic clean-room policy: no external model code/weights or
// constants are embedded.
inline MultiscaleChromaContextDecision resolveMultiscaleChromaContext(
        float fineObservedGradient,
        float fineExpectedNoiseGradient,
        float coarseObservedGradient,
        float coarseExpectedNoiseGradient,
        float sensitivity,
        float modelConfidence = 1.0f) {
    MultiscaleChromaContextDecision out{};
    const float uncertaintyEnvelope = resolveNoiseProfileUncertaintyEnvelope(modelConfidence);
    const float fineWeight = chromaContextWeightSingleScale(
            fineObservedGradient, fineExpectedNoiseGradient * uncertaintyEnvelope, sensitivity);
    const float coarseWeight = chromaContextWeightSingleScale(
            coarseObservedGradient,
            coarseExpectedNoiseGradient * uncertaintyEnvelope,
            std::max(0.25f, sensitivity * 0.86f));

    out.fineStructureEvidence = 1.0f - fineWeight;
    out.coarseStructureEvidence = 1.0f - coarseWeight;

    const float geometricAgreement = std::sqrt(std::max(
            0.0f,
            out.fineStructureEvidence * out.coarseStructureEvidence));
    out.coherentStructureEvidence = std::clamp(
            std::max(
                    std::min(out.fineStructureEvidence, out.coarseStructureEvidence),
                    0.84f * geometricAgreement),
            0.0f, 1.0f);

    out.stochasticFineEvidence = std::clamp(
            out.fineStructureEvidence * (1.0f - out.coarseStructureEvidence),
            0.0f, 1.0f);

    // Coherent cross-scale structure is protected strongly. Coarse-only
    // evidence gets a modest guard, while fine-only evidence explicitly
    // releases the correction instead of treating random chroma speckle as an edge.
    const float protection = std::clamp(
            0.94f * out.coherentStructureEvidence +
            0.16f * out.coarseStructureEvidence -
            0.48f * out.stochasticFineEvidence,
            0.0f, 1.0f);
    out.structureWeight = 1.0f - protection;
    return out;
}

}  // namespace bncam::spectra2
