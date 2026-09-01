#pragma once

#include <algorithm>
#include <cmath>

namespace bncam::luma_nr {

/**
 * Physical single-frame luminance baseline in the resident RGB residual domain.
 *
 * The input sigma must already be propagated through every linear/non-linear stage that
 * precedes this filter. In the current RAW route that includes the Phase-5 positive spatial
 * exposure gain contract. This policy therefore never reconstructs noise from ISO, RAW
 * container type, or rendered brightness.
 */
struct ResidualLumaPlan {
    bool active = false;
    float inputResidualSigma = 0.0f;
    float modelConfidence = 0.0f;
    float residualNoisePressure = 0.0f;
    float baselineFraction = 0.0f;
    float targetSigma = 0.0f;
};

inline float residualLumaSmoothstep(float edge0, float edge1, float value) noexcept {
    if (!std::isfinite(value) || !(edge1 > edge0)) {
        return 0.0f;
    }
    const float t = std::clamp((value - edge0) / (edge1 - edge0), 0.0f, 1.0f);
    return t * t * (3.0f - 2.0f * t);
}

inline ResidualLumaPlan resolveResidualLumaPlan(
        float propagatedResidualSigma,
        float modelConfidence,
        bool physicalNoiseModelAvailable) noexcept {
    ResidualLumaPlan plan{};

    const float sigma = std::isfinite(propagatedResidualSigma)
            ? propagatedResidualSigma
            : 0.0f;
    const float confidence = std::clamp(
            std::isfinite(modelConfidence) ? modelConfidence : 0.0f,
            0.0f, 1.0f);

    if (!physicalNoiseModelAvailable || !(sigma > 0.0f) || confidence < 0.08f) {
        return plan;
    }

    plan.active = true;
    plan.inputResidualSigma = sigma;
    plan.modelConfidence = confidence;

    // Phase 6: make baseline authority depend on the propagated physical residual itself.
    // The former confidence-only 0.45..0.64 fraction treated a bright/high-SNR residual and a
    // dark/noisy residual almost identically. Work in sigma stops so the policy remains scale
    // smooth across the useful RAW range and continues to respond correctly after Phase-5
    // positive spatial exposure propagation.
    constexpr float kLowResidualSigma = 0.0025f;
    const float sigmaStops = std::max(
            0.0f,
            std::log2(std::max(sigma, kLowResidualSigma) / kLowResidualSigma));
    constexpr float kHighResidualStops = 3.32192809489f; // log2(0.025 / 0.0025)
    plan.residualNoisePressure = residualLumaSmoothstep(
            0.0f, kHighResidualStops, sigmaStops);

    // Phase 6 late-envelope hardening: a trustworthy model is not, by itself, evidence that a
    // clean propagated residual needs material smoothing. Keep only a very small baseline at
    // pressure zero, then open authority monotonically as the propagated S/O residual rises.
    // This mirrors the pre-demosaic pressure envelope while preserving the late pass as the
    // owner of exposure-amplified residual noise.
    const float confidenceScale = 0.72f + 0.28f * confidence;
    const float pressureFraction = 0.08f + 0.70f * plan.residualNoisePressure;
    plan.baselineFraction = std::clamp(
            pressureFraction * confidenceScale,
            0.05f,
            0.78f);

    // The resident Vulkan shader remains the local structure/texture owner. targetSigma is only
    // the physically justified residual envelope supplied to that spatially adaptive filter.
    plan.targetSigma = std::clamp(
            sigma * plan.baselineFraction,
            0.0f,
            0.15f);
    return plan;
}

}  // namespace bncam::luma_nr
