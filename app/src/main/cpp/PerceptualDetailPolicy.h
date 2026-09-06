#pragma once

#include <algorithm>
#include <cmath>

namespace bncam::perceptual_detail {

struct Controls {
    float amount = 0.0f;
    float radius = 0.0f;
    float detail = 0.0f;
    float masking = 0.0f;
};

struct NoiseEvidence {
    bool physicalModelAvailable = false;
    float displayLumaSigma = 0.0f;
    float modelConfidence = 0.0f;
};

struct Plan {
    bool enabled = false;
    float authority = 0.0f;
    float radius = 0.0f;
    float detail = 0.0f;
    float masking = 0.0f;
    float displayLumaSigma = 0.0f;
    float minimumResidualSnr = 1.5f;
    float minimumGradientSnr = 1.0f;
    float hardHaloLimit = 0.0f;
    float modelConfidence = 0.0f;
    float predictedLumaVarianceGain = 1.0f;
    const char* authoritySource = "DISABLED";
};

inline float finiteOr(float value, float fallback) noexcept {
    return std::isfinite(value) ? value : fallback;
}

/**
 * Phase 12 profile-owned perceptual detail/output sharpening.
 *
 * This is the standalone signed Global Sharpness stage. It does not build or remap a local-tone
 * base, so FLLF remains the sole local exposure/contrast owner. Amount alone controls the effect:
 * negative softens, zero is exact identity, positive sharpens. Radius/Detail/Masking are separate
 * controls and are intentionally ignored here. Physical noise, when available, is only an internal
 * safety hint and never a prerequisite for the user-requested effect.
 */
inline Plan resolve(Controls controls, NoiseEvidence noise) noexcept {
    controls.amount = std::clamp(finiteOr(controls.amount, 0.0f), -1.0f, 1.0f);
    noise.displayLumaSigma = std::max(0.0f, finiteOr(noise.displayLumaSigma, 0.0f));

    Plan out{};
    if (std::abs(controls.amount) <= 1.0e-4f) {
        out.authoritySource = "PROFILE_GLOBAL_SHARPNESS_ZERO";
        return out;
    }

    // Global Sharpness is intentionally standalone. Radius, Detail and Masking belong to
    // separate controls/phases and must never multiply, gate or disable this signed control.
    // Physical display-domain sigma is useful as an internal noise guard when available, but
    // missing metadata may not disable the user-requested effect.
    out.authority = controls.amount;
    out.radius = 1.0f;
    out.detail = 0.0f;
    out.masking = 0.0f;
    out.displayLumaSigma = noise.displayLumaSigma > 1.0e-7f
            ? noise.displayLumaSigma
            : (1.0f / 255.0f);
    out.minimumResidualSnr = 0.90f;
    out.minimumGradientSnr = 0.75f;
    out.hardHaloLimit = controls.amount > 0.0f
            ? (0.055f + 0.085f * controls.amount)
            : 0.0f;
    out.modelConfidence = 1.0f;

    const float positive = std::max(0.0f, controls.amount);
    out.predictedLumaVarianceGain = std::clamp(
            1.0f + 0.85f * positive * positive,
            1.0f,
            1.85f);
    out.enabled = true;
    out.authoritySource = controls.amount < 0.0f
            ? "PROFILE_GLOBAL_SHARPNESS_SOFTEN"
            : "PROFILE_GLOBAL_SHARPNESS_SHARPEN";
    return out;
}

} // namespace bncam::perceptual_detail
