#pragma once

#include <algorithm>
#include <cmath>

namespace bncam::luma_nr {

/**
 * Physical single-frame luminance baseline in the resident RGB residual domain.
 *
 * The input sigma must already be propagated through every linear/non-linear stage that
 * precedes this filter: lens shading, BnCam spatial exposure, demosaic, AWB, CCM, linear
 * detail and tone. This policy therefore never reconstructs noise from ISO, RAW container
 * type, or rendered brightness.
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

    // Phase 4: baseline authority depends on the propagated physical residual itself.
    // Work in sigma stops so the mapping stays smooth across the useful RAW range.
    constexpr float kLowResidualSigma = 0.0025f;
    const float sigmaStops = std::max(
            0.0f,
            std::log2(std::max(sigma, kLowResidualSigma) / kLowResidualSigma));
    constexpr float kHighResidualStops = 3.32192809489f; // log2(0.025 / 0.0025)
    plan.residualNoisePressure = residualLumaSmoothstep(
            0.0f, kHighResidualStops, sigmaStops);

    // Model confidence alone is not evidence that a clean residual needs smoothing. Keep a
    // very small baseline at pressure zero and open authority monotonically with propagated
    // residual noise. The resident shader makes the final local noise-vs-structure decision.
    const float confidenceScale = 0.72f + 0.28f * confidence;
    const float pressureFraction = 0.18f + 0.76f * plan.residualNoisePressure;
    plan.baselineFraction = std::clamp(
            pressureFraction * confidenceScale,
            0.15f,
            0.85f);

    plan.targetSigma = std::clamp(
            sigma * plan.baselineFraction,
            0.0f,
            0.15f);
    return plan;
}

}  // namespace bncam::luma_nr
