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
    float baselineFraction = 0.0f;
    float targetSigma = 0.0f;
};

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

    // Keep authority proportional to the propagated physical residual. This preserves
    // scale covariance: if an upstream linear gain doubles both signal and predicted
    // noise sigma, the denoise significance envelope doubles with it.
    //
    // The resident shader remains the local structure owner. Raising the physical
    // baseline from the old 0.34..0.50 range to 0.45..0.64 gives flat/noisy regions
    // materially more cleanup while retaining the existing edge/texture gates.
    const float confidenceScale = 0.70f + 0.30f * confidence;
    plan.baselineFraction = std::clamp(
            0.64f * confidenceScale,
            0.45f,
            0.64f);
    plan.targetSigma = std::clamp(
            sigma * plan.baselineFraction,
            0.0f,
            0.15f);
    return plan;
}

}  // namespace bncam::luma_nr
