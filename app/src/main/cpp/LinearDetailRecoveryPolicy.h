#pragma once

#include <algorithm>
#include <cmath>

namespace bncam::detail_recovery {

struct Controls {
    float amount = 0.40f;
    float radius = 1.00f;
    float detail = 0.25f;
    float masking = 0.00f;
};

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
    float detailEmphasis = 0.25f;
    float masking = 0.0f;
    float minimumResidualSnr = 1.5f;
    float minimumGradientSnr = 1.0f;
    float hardHaloLimit = 0.02f;
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
 * Phase 11 capture-detail owner.
 *
 * The policy intentionally requires a physical Camera2/manual S/O model.  When physical noise
 * evidence is unavailable, capture deconvolution is disabled rather than guessing an ISO/device
 * heuristic.  Amount remains creative authority; radius/detail/masking only shape a bounded
 * one-step Van-Cittert inverse-PSF proposal.  Per-pixel SNR/edge/halo decisions stay on Vulkan.
 */
inline Plan resolve(Controls controls, PhysicalNoiseEvidence noise) noexcept {
    controls.amount = std::clamp(finiteOr(controls.amount, 0.40f), 0.0f, 1.0f);
    controls.radius = std::clamp(finiteOr(controls.radius, 1.00f), 0.50f, 3.00f);
    controls.detail = std::clamp(finiteOr(controls.detail, 0.25f), 0.0f, 1.0f);
    controls.masking = std::clamp(finiteOr(controls.masking, 0.00f), 0.0f, 1.0f);
    noise.preToneLumaSigma = std::max(0.0f, finiteOr(noise.preToneLumaSigma, 0.0f));
    noise.referenceSignal = std::clamp(finiteOr(noise.referenceSignal, 0.10f), 1.0e-4f, 2.0f);
    noise.shotNoiseFraction = std::clamp(finiteOr(noise.shotNoiseFraction, 0.5f), 0.0f, 1.0f);
    noise.modelConfidence = std::clamp(finiteOr(noise.modelConfidence, 0.0f), 0.0f, 1.0f);

    Plan out{};
    out.radius = controls.radius;
    out.detailEmphasis = controls.detail;
    out.masking = controls.masking;
    out.preToneLumaSigma = noise.preToneLumaSigma;
    out.referenceSignal = noise.referenceSignal;
    out.shotNoiseFraction = noise.shotNoiseFraction;
    out.modelConfidence = noise.modelConfidence;

    if (controls.amount <= 1.0e-4f) {
        out.authoritySource = "PROFILE_AMOUNT_ZERO";
        return out;
    }
    if (!noise.available || !(noise.preToneLumaSigma > 0.0f)) {
        out.authoritySource = "PHYSICAL_SO_UNAVAILABLE";
        return out;
    }
    if (noise.modelConfidence < 0.15f) {
        out.authoritySource = "PHYSICAL_SO_CONFIDENCE_TOO_LOW";
        return out;
    }

    // Keep default capture recovery deliberately moderate. Fine-detail intent may increase the
    // inverse-PSF step, while a high masking value reserves it for high-confidence edges.
    const float confidenceScale = 0.35f + 0.65f * noise.modelConfidence;
    const float detailScale = 0.82f + 0.32f * controls.detail;
    out.authority = std::clamp(controls.amount * 0.62f * detailScale * confidenceScale, 0.0f, 0.62f);
    out.minimumResidualSnr = 1.35f + 2.15f * controls.masking;
    out.minimumGradientSnr = 0.85f + 1.65f * controls.masking;
    out.hardHaloLimit = std::clamp(
            (0.014f + 0.030f * controls.amount) * (1.0f - 0.45f * controls.masking),
            0.008f,
            0.040f);

    // Conservative white-noise upper bound for a local inverse-PSF step. Actual Vulkan authority
    // is normally lower because SNR/edge/halo gates suppress flat/noisy pixels.
    out.predictedLumaVarianceGain = std::clamp(
            1.0f + 0.52f * out.authority * out.authority,
            1.0f,
            1.20f);
    out.enabled = out.authority > 1.0e-4f;
    out.authoritySource = "PHYSICAL_SO_SCENE_LINEAR_VAN_CITTERT";
    return out;
}

} // namespace bncam::detail_recovery
