#pragma once

#include <algorithm>
#include <cmath>

namespace bncam::spectra2 {

struct PostDemosaicResidualNrPlan {
    float lumaSigma = 0.0f;
    float chromaSigma = 0.0f;
    float lumaFraction = 0.0f;
    float chromaFraction = 0.0f;
};

/**
 * Residual-only post-demosaic cleanup for SPECTRA Context Fusion.
 *
 * Dynamic ISO already changes the pre-demosaic Context Fusion authority. It must not be
 * applied again as the legacy exponential luma/chroma user scale after demosaic, otherwise
 * Natural at Dynamic ISO 0.40 becomes roughly 1.87x luma / 3.03x chroma spatial NR and
 * softens real structure instead of removing the sensor-domain residual at its source.
 *
 * This stage is deliberately weaker than the physical Off baseline on luma: SPECTRA On has
 * already performed CFA-domain denoise. Chroma retains a little more residual authority, but
 * remains bounded so the late RGB stage is cleanup, not the primary denoiser.
 */
inline PostDemosaicResidualNrPlan resolvePostDemosaicResidualNr(
        float physicalLumaSigma,
        float physicalChromaSigma,
        float calibrationFactor,
        float profileStrength,
        float profileLuma,
        float profileChroma,
        bool spectraContextFusionActive) {
    PostDemosaicResidualNrPlan plan{};
    if (!spectraContextFusionActive ||
        !std::isfinite(physicalLumaSigma) || !std::isfinite(physicalChromaSigma) ||
        physicalLumaSigma <= 0.0f || physicalChromaSigma <= 0.0f) {
        return plan;
    }

    const float calibration = std::isfinite(calibrationFactor)
            ? std::clamp(calibrationFactor, 0.35f, 2.50f)
            : 1.0f;
    const float overall = std::exp2(std::clamp(profileStrength, -1.0f, 1.0f) * 0.35f);
    const float lumaCharacter = std::exp2(std::clamp(profileLuma, -1.0f, 1.0f) * 0.45f);
    const float chromaCharacter = std::exp2(std::clamp(profileChroma, -1.0f, 1.0f) * 0.45f);

    // QUALITY DELTA 0014: CFA-domain SPECTRA plus the common pre-tone Wiener stage
    // already own primary luma cleanup. This post-demosaic pass is residual-only.
    // Keep the SPECTRA Luma control connected, but prevent it from becoming a second
    // broad luma denoiser that trades texture for smoothness.
    plan.lumaFraction = std::clamp(0.38f * overall * lumaCharacter, 0.20f, 0.75f);
    plan.chromaFraction = std::clamp(0.85f * overall * chromaCharacter, 0.55f, 1.25f);
    plan.lumaSigma = std::clamp(
            physicalLumaSigma * calibration * plan.lumaFraction,
            0.0f,
            0.18f);
    plan.chromaSigma = std::clamp(
            physicalChromaSigma * calibration * plan.chromaFraction,
            0.0f,
            0.40f);
    return plan;
}

}  // namespace bncam::spectra2
