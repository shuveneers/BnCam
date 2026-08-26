#pragma once

#include <algorithm>
#include <cmath>

#include "SpectraNoiseProfileUncertainty.h"

namespace bncam::spectra2 {

struct MultiscaleContextDecision {
    float coherentStructureZ = 0.0f;
    float stochasticFineEvidence = 0.0f;
    float edgeProtection = 0.0f;
    float flatContext = 1.0f;
    float denoiseAuthorityScale = 1.0f;
    float broadMixScale = 1.0f;
};

inline float multiscaleSmoothstep(float edge0, float edge1, float value) {
    if (!std::isfinite(value) || !(edge1 > edge0)) return 0.0f;
    const float t = std::clamp((value - edge0) / (edge1 - edge0), 0.0f, 1.0f);
    return t * t * (3.0f - 2.0f * t);
}

// SPECTRA Context Fusion multiscale policy.
//
// fineStructureZ and coarseStructureZ are structure magnitudes expressed in units
// of the physically predicted sensor-noise standard deviation. A real scene edge
// normally persists across scales; stochastic sensor noise is far less coherent.
// This is deliberately analytic and deterministic: it borrows the useful
// multiscale-conditioning principle from modern CFA-domain denoisers without
// embedding external model code or weights.
inline MultiscaleContextDecision resolveMultiscaleContext(
        float fineStructureZ,
        float coarseStructureZ,
        float combinedNoisePressure,
        float modelConfidence) {
    MultiscaleContextDecision out{};
    const float confidence = std::clamp(
            std::isfinite(modelConfidence) ? modelConfidence : 0.0f,
            0.0f, 1.0f);
    const float uncertaintyEnvelope = resolveNoiseProfileUncertaintyEnvelope(confidence);
    const float fine = std::max(0.0f, std::isfinite(fineStructureZ) ? fineStructureZ : 0.0f) /
            uncertaintyEnvelope;
    const float coarse = std::max(0.0f, std::isfinite(coarseStructureZ) ? coarseStructureZ : 0.0f) /
            uncertaintyEnvelope;
    const float noise = std::clamp(
            std::isfinite(combinedNoisePressure) ? combinedNoisePressure : 0.0f,
            0.0f, 1.0f);

    // Geometric agreement makes protection depend on persistence across scales,
    // not on one noisy high-frequency excursion.
    const float crossScaleAgreement = std::sqrt(std::max(0.0f, fine * coarse));
    out.coherentStructureZ = std::max(
            std::min(fine, coarse),
            0.78f * crossScaleAgreement);

    // Fine-only energy is useful evidence that the apparent detail may be random
    // sensor variation rather than persistent scene structure.
    out.stochasticFineEvidence = std::max(0.0f, fine - 0.72f * coarse);
    const float stochasticRelease = multiscaleSmoothstep(
            1.15f, 3.25f, out.stochasticFineEvidence) *
            (1.0f - multiscaleSmoothstep(1.45f, 3.40f, coarse));

    const float coherentProtection = multiscaleSmoothstep(
            1.55f, 4.20f, out.coherentStructureZ);
    out.edgeProtection = std::clamp(
            coherentProtection * (0.72f + 0.28f * confidence),
            0.0f, 1.0f);

    const float flatMetric = std::max(out.coherentStructureZ, 0.82f * coarse);
    out.flatContext = 1.0f - multiscaleSmoothstep(1.30f, 3.20f, flatMetric);

    // In flat/noisy regions allow the existing estimator to use more of its
    // candidate; on coherent structure pull authority down. No-Regret remains the
    // final hard residual/detail boundary after this policy.
    out.denoiseAuthorityScale = std::clamp(
            0.90f +
            0.30f * out.flatContext * (0.55f + 0.45f * noise) +
            0.16f * stochasticRelease * (0.50f + 0.50f * noise) -
            0.62f * out.edgeProtection,
            0.22f, 1.32f);

    // Broad/contextual support is most useful when physical noise pressure is high
    // and cross-scale evidence says the region is flat. It never exceeds a modest
    // multiplier because the actual broad correction is separately bounded.
    out.broadMixScale = std::clamp(
            0.82f + 0.42f * out.flatContext * (0.45f + 0.55f * noise) +
            0.14f * stochasticRelease,
            0.72f, 1.28f);
    return out;
}

}  // namespace bncam::spectra2
