#pragma once

#include <algorithm>
#include <cmath>

namespace bncam::detail_recovery {

struct PhysicalNoiseEvidence {
    bool available = false;
    float preToneLumaSigma = 0.0f;
    float referenceSignal = 0.10f;
    float shotNoiseFraction = 0.5f;
    float modelConfidence = 0.0f;
};

struct Plan {
    bool enabled = false;
    float authority = 0.0f;
    float radius = 1.0f;
    float detailEmphasis = 0.40f;
    float masking = 0.15f;
    float minimumResidualSnr = 0.85f;
    float minimumGradientSnr = 0.65f;
    float hardHaloLimit = 0.022f;
    float preToneLumaSigma = 0.0f;
    float referenceSignal = 0.10f;
    float shotNoiseFraction = 0.5f;
    float modelConfidence = 0.0f;
    float predictedLumaVarianceGain = 1.0f;
    const char* authoritySource = "DISABLED";
};

inline float finiteOr(float value, float fallback) noexcept {
    return std::isfinite(value) ? value : fallback;
}

/**
 * Phase 11 physical capture-detail owner.
 *
 * Profile Sharpness is deliberately NOT an input. Phase 11 restores a small, bounded amount of
 * capture/optical detail from the physically calibrated scene-linear signal. Creative/perceptual
 * sharpness belongs to Phase 12 and defaults to zero. Without valid physical S/O evidence Phase 11
 * disables rather than guessing an ISO, device or vendor heuristic.
 */
inline Plan resolve(PhysicalNoiseEvidence noise) noexcept {
    noise.preToneLumaSigma = std::max(0.0f, finiteOr(noise.preToneLumaSigma, 0.0f));
    noise.referenceSignal = std::clamp(finiteOr(noise.referenceSignal, 0.10f), 1.0e-4f, 2.0f);
    noise.shotNoiseFraction = std::clamp(finiteOr(noise.shotNoiseFraction, 0.5f), 0.0f, 1.0f);
    noise.modelConfidence = std::clamp(finiteOr(noise.modelConfidence, 0.0f), 0.0f, 1.0f);

    Plan out{};
    out.preToneLumaSigma = noise.preToneLumaSigma;
    out.referenceSignal = noise.referenceSignal;
    out.shotNoiseFraction = noise.shotNoiseFraction;
    out.modelConfidence = noise.modelConfidence;

    if (!noise.available || !(noise.preToneLumaSigma > 0.0f)) {
        out.authoritySource = "PHYSICAL_SO_UNAVAILABLE";
        return out;
    }
    if (noise.modelConfidence < 0.15f) {
        out.authoritySource = "PHYSICAL_SO_CONFIDENCE_TOO_LOW";
        return out;
    }

    // Recover crisp flagship micro-detail calibrated against the physical sensor noise model.
    const float confidenceScale = 0.35f + 0.65f * noise.modelConfidence;
    out.authority = std::clamp(0.32f * confidenceScale, 0.0f, 0.32f);
    out.radius = 1.0f;
    out.detailEmphasis = 0.40f;
    out.masking = 0.35f;
    out.minimumResidualSnr = 1.85f;
    out.minimumGradientSnr = 1.50f;
    out.hardHaloLimit = 0.022f;
    out.predictedLumaVarianceGain = std::clamp(
            1.0f + 0.52f * out.authority * out.authority,
            1.0f,
            1.06f);
    out.enabled = out.authority > 1.0e-4f;
    out.authoritySource = "PHYSICAL_SO_CAPTURE_RECOVERY";
    return out;
}

} // namespace bncam::detail_recovery
