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
    bool chromaResidualAvailable = false;
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
 * Phase-4 ownership:
 * - the physical luminance baseline is resolved by PhysicalLumaDenoisePolicy;
 * - this wrapper combines that independent baseline with optional SPECTRA residual
 *   enhancement and the existing chroma residual contract;
 * - input sigma values are propagated residuals, never reconstructed from ISO or from the
 *   original Camera2 S/O model.
 *
 * The input covariance represents the actual production path up to this resident filter:
 *
 *   CFA cleanup -> lens shading/spatial exposure -> demosaic -> AWB -> CCM -> detail -> tone.
 *
 * That propagated covariance is the boundary which prevents double denoise. Luma validity is
 * intentionally independent from chroma validity: an unavailable chroma residual must not
 * disable a trustworthy propagated physical luminance baseline.
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

    const bool validLumaResidual =
            std::isfinite(residualLumaSigma) && residualLumaSigma > 0.0f;
    const bool validChromaResidual =
            std::isfinite(residualChromaSigma) && residualChromaSigma > 0.0f;
    const float confidence = std::clamp(
            std::isfinite(modelConfidence) ? modelConfidence : 0.0f,
            0.0f,
            1.0f);
    if (!physicalNoiseModelAvailable || !validLumaResidual || confidence < 0.08f) {
        plan.authoritySource = physicalNoiseModelAvailable
                ? "PROPAGATED_LUMA_RESIDUAL_UNAVAILABLE"
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
    plan.chromaResidualAvailable = validChromaResidual;
    plan.inputLumaSigma = residualLumaSigma;
    plan.inputChromaSigma = validChromaResidual ? residualChromaSigma : 0.0f;
    plan.modelConfidence = confidence;

    plan.baselineLumaFraction = physicalLuma.baselineFraction;

    // Phase 4 changes luminance ownership only. Keep the established propagated-chroma
    // residual envelope independent from the luma refinement.
    const float confidenceScale = 0.72f + 0.28f * confidence;
    plan.baselineChromaFraction = validChromaResidual
            ? std::clamp(0.78f * confidenceScale, 0.54f, 0.78f)
            : 0.0f;

    if (spectraContextFusionActive) {
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
        plan.spectraChromaFraction = validChromaResidual
                ? std::clamp(
                        0.16f * overall * chromaCharacter * chromaHeadroom,
                        0.0f,
                        0.22f)
                : 0.0f;
        plan.authoritySource = validChromaResidual
                ? "PROPAGATED_RESIDUAL_PHYSICAL_BASELINE_PLUS_SPECTRA"
                : "PROPAGATED_LUMA_RESIDUAL_PHYSICAL_BASELINE_PLUS_SPECTRA_CHROMA_UNAVAILABLE";
    } else {
        plan.authoritySource = validChromaResidual
                ? "PROPAGATED_RESIDUAL_PHYSICAL_BASELINE"
                : "PROPAGATED_LUMA_RESIDUAL_PHYSICAL_BASELINE_CHROMA_UNAVAILABLE";
    }

    plan.lumaFraction = std::clamp(
            plan.baselineLumaFraction + plan.spectraLumaFraction,
            0.0f,
            0.78f);
    plan.chromaFraction = validChromaResidual
            ? std::clamp(
                    plan.baselineChromaFraction + plan.spectraChromaFraction,
                    0.0f,
                    0.96f)
            : 0.0f;

    // Use the independently resolved physical luma target, then add only the optional
    // SPECTRA residual increment. This keeps the physical baseline invariant to SPECTRA.
    const float spectraLumaSigma =
            plan.inputLumaSigma * plan.spectraLumaFraction;
    plan.lumaSigma = std::clamp(
            physicalLuma.targetSigma + spectraLumaSigma,
            0.0f,
            0.15f);
    plan.chromaSigma = validChromaResidual
            ? std::clamp(
                    plan.inputChromaSigma * plan.chromaFraction,
                    0.0f,
                    0.35f)
            : 0.0f;
    return plan;
}

}  // namespace bncam::spectra2
