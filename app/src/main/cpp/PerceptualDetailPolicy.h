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
 * This stage is deliberately narrow-band and pointwise-authority only: it does not build or
 * remap a local-tone base, so FLLF remains the sole local exposure/contrast owner. Profile Amount
 * is the master switch and defaults to zero. Physical propagated luma noise is mandatory; without
 * it the stage bypasses rather than sharpening an unqualified signal.
 */
inline Plan resolve(Controls controls, NoiseEvidence noise) noexcept {
    controls.amount = std::clamp(finiteOr(controls.amount, 0.0f), 0.0f, 1.0f);
    controls.radius = std::clamp(finiteOr(controls.radius, 0.0f), 0.0f, 3.0f);
    controls.detail = std::clamp(finiteOr(controls.detail, 0.0f), 0.0f, 1.0f);
    controls.masking = std::clamp(finiteOr(controls.masking, 0.0f), 0.0f, 1.0f);
    noise.displayLumaSigma = std::max(0.0f, finiteOr(noise.displayLumaSigma, 0.0f));
    noise.modelConfidence = std::clamp(finiteOr(noise.modelConfidence, 0.0f), 0.0f, 1.0f);

    Plan out{};
    out.detail = controls.detail;
    out.masking = controls.masking;
    out.displayLumaSigma = noise.displayLumaSigma;
    out.modelConfidence = noise.modelConfidence;

    if (controls.amount <= 1.0e-4f) {
        out.authoritySource = "PROFILE_SHARPNESS_ZERO";
        return out;
    }
    if (!noise.physicalModelAvailable || !(noise.displayLumaSigma > 0.0f)) {
        out.authoritySource = "PHYSICAL_PROPAGATED_NOISE_UNAVAILABLE";
        return out;
    }
    if (noise.modelConfidence < 0.15f) {
        out.authoritySource = "PHYSICAL_NOISE_CONFIDENCE_TOO_LOW";
        return out;
    }

    // Radius=0 is a valid neutral stored value. Once Amount is deliberately raised, resolve a
    // compact perceptual kernel internally without changing the stored profile value.
    out.radius = controls.radius > 1.0e-4f ? std::clamp(controls.radius, 0.50f, 3.0f) : 0.85f;
    const float confidenceScale = 0.40f + 0.60f * noise.modelConfidence;
    out.authority = std::clamp(controls.amount * 0.46f * confidenceScale, 0.0f, 0.46f);
    out.minimumResidualSnr = 1.45f + 2.30f * controls.masking;
    out.minimumGradientSnr = 0.90f + 1.70f * controls.masking;
    out.hardHaloLimit = std::clamp(
            (0.006f + 0.014f * controls.amount) * (1.0f - 0.45f * controls.masking),
            0.004f,
            0.018f);
    out.predictedLumaVarianceGain = std::clamp(
            1.0f + 0.42f * out.authority * out.authority,
            1.0f,
            1.10f);
    out.enabled = out.authority > 1.0e-4f;
    out.authoritySource = "PROFILE_PERCEPTUAL_DETAIL_PHYSICAL_NOISE_GATED";
    return out;
}

} // namespace bncam::perceptual_detail
