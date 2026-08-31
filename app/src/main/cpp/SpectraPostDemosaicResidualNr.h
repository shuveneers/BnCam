#pragma once

#include "PhysicalLumaDenoisePolicy.h"

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
 * Residual-only late cleanup.
 *
 * Phase-6 ownership:
 * - the physical luminance baseline is resolved by PhysicalLumaDenoisePolicy;
 * - this wrapper only combines that independent baseline with optional SPECTRA residual
 *   enhancement and the pre-existing chroma residual contract;
 * - the input sigma values are propagated residuals, never reconstructed from ISO.
 *
 * The input sigma values must represent the actual production path up to this resident
 * filter:
 *
 *   CFA cleanup -> Phase-5 spatial exposure -> demosaic -> AWB -> CCM -> detail/tone.
 *
 * That propagated covariance is the boundary which prevents double denoise. In particular,
 * positive spatial exposure amplification is already present in residualLumaSigma; this
 * function must not multiply it a second time.
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

    const bncam::luma_nr::ResidualLumaPlan physicalLuma =
            bncam::luma_nr::resolveResidualLumaPlan(
                    residualLumaSigma,
                    confidence,
                    physicalNoiseModelAvailable);
    if (!physicalLuma.active) {
        plan.authoritySource = "PROPAGATED_LUMA_RESIDUAL_UNAVAILABLE";
        return plan;
    }

    plan.active = true;
    plan.physicalBaselineActive = true;
    plan.residualCovarianceAuthoritative = true;
    plan.duplicatePhysicalSigmaPrevented = true;
    plan.inputLumaSigma = residualLumaSigma;
    plan.inputChromaSigma = residualChromaSigma;
    plan.modelConfidence = confidence;

    // Luma baseline ownership is external and independent from SPECTRA.
    plan.baselineLumaFraction = physicalLuma.baselineFraction;

    // Chroma is deliberately unchanged in Phase 6.
    const float confidenceScale = 0.72f + 0.28f * confidence;
    plan.baselineChromaFraction = std::clamp(
            0.78f * confidenceScale,
            0.54f,
            0.78f);

    if (spectraContextFusionActive) {
        // Strength=0 remains the calibrated neutral SPECTRA master authority.
        // These terms are optional residual increments only; they do not determine
        // whether the physical luma baseline exists.
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
        plan.authoritySource =
                "PROPAGATED_RESIDUAL_PHYSICAL_BASELINE_PLUS_SPECTRA";
    } else {
        plan.authoritySource = "PROPAGATED_RESIDUAL_PHYSICAL_BASELINE";
    }

    plan.lumaFraction = std::clamp(
            plan.baselineLumaFraction + plan.spectraLumaFraction,
            0.0f,
            0.78f);
    plan.chromaFraction = std::clamp(
            plan.baselineChromaFraction + plan.spectraChromaFraction,
            0.0f,
            0.96f);

    // Use the externally resolved physical target for the baseline, then add only the
    // optional SPECTRA residual increment. This keeps the baseline invariant to SPECTRA.
    const float spectraLumaSigma =
            plan.inputLumaSigma * plan.spectraLumaFraction;
    plan.lumaSigma = std::clamp(
            physicalLuma.targetSigma + spectraLumaSigma,
            0.0f,
            0.15f);
    plan.chromaSigma = std::clamp(
            plan.inputChromaSigma * plan.chromaFraction,
            0.0f,
            0.35f);
    return plan;
}

}  // namespace bncam::spectra2
