#pragma once

#include <algorithm>
#include <cmath>

namespace bncam::spectra2 {

struct PostDemosaicResidualNrPlan {
    bool active = false;
    bool physicalBaselineActive = false;
    bool spectraEnhancementActive = false;
    bool residualCovarianceAuthoritative = false;
    bool duplicatePhysicalSigmaPrevented = false;
    float inputLumaSigma = 0.0f;
    float inputChromaSigma = 0.0f;
    float modelConfidence = 0.0f;
    float baselineLumaFraction = 0.0f;
    float baselineChromaFraction = 0.0f;
    float spectraLumaFraction = 0.0f;
    float spectraChromaFraction = 0.0f;
    float lumaFraction = 0.0f;
    float chromaFraction = 0.0f;
    float lumaSigma = 0.0f;
    float chromaSigma = 0.0f;
    const char* authoritySource = "UNAVAILABLE";
};

/**
 * FASE 14 / SPECTRA V2 residual-only post-tone cleanup.
 *
 * The input sigma values are no longer the original Camera2 S/O sensor sigma. They must be the
 * residual sigma propagated through the actual production path up to the late resident filter:
 *
 *   CFA physical/SPECTRA cleanup -> demosaic -> AWB -> CCM -> Phase 11 -> tone.
 *
 * The boundary is deliberately frozen before optional Phase 12 perceptual/output detail, so
 * intentional profile sharpness cannot be reclassified as sensor residual noise.
 *
 * That distinction is the ownership boundary which prevents double denoise. The physical
 * baseline is always present when the propagated model is trustworthy; SPECTRA On may spend a
 * small amount of additional authority only on the residual that is still predicted to exist.
 * Dynamic ISO / character controls therefore never manufacture a second physical noise floor.
 */
inline PostDemosaicResidualNrPlan resolvePostDemosaicResidualNr(
        float residualLumaSigma,
        float residualChromaSigma,
        float modelConfidence,
        float profileStrength,
        float profileLuma,
        float profileChroma,
        float downstreamLumaAuthority,
        float downstreamChromaAuthority,
        bool physicalNoiseModelAvailable,
        bool spectraContextFusionActive) noexcept {
    PostDemosaicResidualNrPlan plan{};

    const bool validResidual =
            std::isfinite(residualLumaSigma) && residualLumaSigma > 0.0f &&
            std::isfinite(residualChromaSigma) && residualChromaSigma > 0.0f;
    const float confidence = std::clamp(
            std::isfinite(modelConfidence) ? modelConfidence : 0.0f,
            0.0f,
            1.0f);
    if (!physicalNoiseModelAvailable || !validResidual || confidence < 0.08f) {
        plan.authoritySource = physicalNoiseModelAvailable
                ? "PROPAGATED_RESIDUAL_UNAVAILABLE"
                : "PHYSICAL_SO_UNAVAILABLE";
        return plan;
    }

    plan.active = true;
    plan.physicalBaselineActive = true;
    plan.residualCovarianceAuthoritative = true;
    plan.duplicatePhysicalSigmaPrevented = true;
    plan.inputLumaSigma = residualLumaSigma;
    plan.inputChromaSigma = residualChromaSigma;
    plan.modelConfidence = confidence;

    // Confidence gates how much of the *predicted residual* the late baseline may own. These
    // fractions are intentionally below one: the resident edge/structure gates still need room
    // to preserve texture, and earlier physical stages have already removed part of the noise.
    const float confidenceScale = 0.72f + 0.28f * confidence;
    plan.baselineLumaFraction = std::clamp(0.50f * confidenceScale, 0.34f, 0.50f);
    plan.baselineChromaFraction = std::clamp(0.78f * confidenceScale, 0.54f, 0.78f);

    if (spectraContextFusionActive) {
        // Strength=0 is the calibrated neutral SPECTRA master authority. It is therefore a
        // multiplicative neutral point (1.0), not "no enhancement". Luma/chroma character
        // controls shape only the optional residual increment.
        const float overall = std::exp2(
                std::clamp(std::isfinite(profileStrength) ? profileStrength : 0.0f,
                           -1.0f, 1.0f) * 0.20f);
        const float lumaCharacter = std::exp2(
                std::clamp(std::isfinite(profileLuma) ? profileLuma : 0.0f,
                           -1.0f, 1.0f) * 0.22f);
        const float chromaCharacter = std::exp2(
                std::clamp(std::isfinite(profileChroma) ? profileChroma : 0.0f,
                           -1.0f, 1.0f) * 0.22f);
        const float lumaHeadroom = std::clamp(
                std::isfinite(downstreamLumaAuthority) ? downstreamLumaAuthority : 0.0f,
                0.0f,
                1.0f);
        const float chromaHeadroom = std::clamp(
                std::isfinite(downstreamChromaAuthority) ? downstreamChromaAuthority : 0.0f,
                0.0f,
                1.0f);

        plan.spectraEnhancementActive = true;
        plan.spectraLumaFraction = std::clamp(
                0.10f * overall * lumaCharacter * lumaHeadroom,
                0.0f,
                0.14f);
        plan.spectraChromaFraction = std::clamp(
                0.16f * overall * chromaCharacter * chromaHeadroom,
                0.0f,
                0.22f);
        plan.authoritySource = "PROPAGATED_RESIDUAL_PHYSICAL_BASELINE_PLUS_SPECTRA";
    } else {
        plan.authoritySource = "PROPAGATED_RESIDUAL_PHYSICAL_BASELINE";
    }

    plan.lumaFraction = std::clamp(
            plan.baselineLumaFraction + plan.spectraLumaFraction,
            0.0f,
            0.64f);
    plan.chromaFraction = std::clamp(
            plan.baselineChromaFraction + plan.spectraChromaFraction,
            0.0f,
            0.96f);
    plan.lumaSigma = std::clamp(plan.inputLumaSigma * plan.lumaFraction, 0.0f, 0.15f);
    plan.chromaSigma = std::clamp(plan.inputChromaSigma * plan.chromaFraction, 0.0f, 0.35f);
    return plan;
}

}  // namespace bncam::spectra2
