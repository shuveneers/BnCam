#pragma once

#include <algorithm>
#include <cmath>

namespace bncam::raw_black {

/**
 * A residual CFA pedestal is different from the Camera2 black-level baseline.
 *
 * Camera2 dynamic/static black metadata is subtracted first. This policy only
 * decides whether a small, spatially coherent additive residue remains in the
 * already-normalised RAW mosaic. The current correction target is the common
 * green pedestal (Gr and Gb together); it never invents an R/B scene correction.
 */
struct ResidualGreenPedestalInput {
    float lowerTailGreenExcessCode = 0.0f;
    float tileMedianGreenExcessCode = 0.0f;
    float tileMadCode = 0.0f;
    float tileConsensus = 0.0f;
    float lowerTailRedBlueMismatchCode = 0.0f;
    float predictedGreenSigmaCode = 1.0f;
    float whiteLevelCode = 1.0f;
    int strictTileCount = 0;
};

struct ResidualGreenPedestalDecision {
    bool apply = false;
    float correctionCode = 0.0f; // Positive amount to subtract from Gr and Gb.
    float confidence = 0.0f;
    const char* reason = "insufficient_evidence";
};

inline ResidualGreenPedestalDecision resolveResidualGreenPedestal(
        const ResidualGreenPedestalInput& input) noexcept {
    ResidualGreenPedestalDecision out{};

    if (input.strictTileCount < 12) {
        out.reason = "insufficient_strict_dark_tiles";
        return out;
    }
    if (!std::isfinite(input.lowerTailGreenExcessCode) ||
        !std::isfinite(input.tileMedianGreenExcessCode) ||
        !std::isfinite(input.tileMadCode) ||
        !std::isfinite(input.tileConsensus) ||
        !std::isfinite(input.lowerTailRedBlueMismatchCode) ||
        !std::isfinite(input.predictedGreenSigmaCode) ||
        !std::isfinite(input.whiteLevelCode)) {
        out.reason = "non_finite_statistics";
        return out;
    }

    const float sigma = std::max(0.50f, input.predictedGreenSigmaCode);
    const float activation = std::max(1.50f, 2.25f * sigma);
    if (input.lowerTailGreenExcessCode <= activation ||
        input.tileMedianGreenExcessCode <= activation) {
        out.reason = "residual_not_above_noise";
        return out;
    }

    if (input.tileConsensus < 0.85f) {
        out.reason = "spatial_consensus_too_low";
        return out;
    }

    // The lower-tail estimate is the physical amount estimator; the independent
    // per-tile median is a scene-content guard. They must agree before mutation.
    const float agreementScale = std::max(
            input.lowerTailGreenExcessCode,
            input.tileMedianGreenExcessCode);
    const float agreementLimit = std::max(2.0f, 0.40f * agreementScale);
    if (std::abs(input.lowerTailGreenExcessCode - input.tileMedianGreenExcessCode) > agreementLimit) {
        out.reason = "lower_tail_tile_estimates_disagree";
        return out;
    }

    const float robustResidual = std::min(
            input.lowerTailGreenExcessCode,
            input.tileMedianGreenExcessCode);
    const float madLimit = std::max(1.50f, 0.35f * robustResidual);
    if (input.tileMadCode > madLimit) {
        out.reason = "residual_spatial_mad_too_high";
        return out;
    }

    // A neutral black pedestal should not require choosing between R and B.
    // If their lower tails are far apart, the dark support is likely coloured
    // scene content rather than an additive CFA offset.
    const float rbMismatchLimit = std::max(2.0f, 0.50f * robustResidual);
    if (input.lowerTailRedBlueMismatchCode > rbMismatchLimit) {
        out.reason = "red_blue_lower_tail_not_neutral";
        return out;
    }

    const float white = std::max(1.0f, input.whiteLevelCode);
    // A 5% full-scale hard ceiling prevents a bad scene estimate from becoming
    // a large exposure change. Under a valid estimate the correction is the
    // measured additive residue, not a saturation or colour-look adjustment.
    const float hardCapCode = std::max(4.0f, 0.05f * white);
    out.correctionCode = std::clamp(robustResidual, 0.0f, hardCapCode);
    if (out.correctionCode <= 0.0f) {
        out.reason = "zero_correction_after_bounds";
        return out;
    }

    const float noiseEvidence = std::clamp(
            (robustResidual - activation) / std::max(activation, 1.0f), 0.0f, 1.0f);
    const float consensusEvidence = std::clamp((input.tileConsensus - 0.85f) / 0.15f, 0.0f, 1.0f);
    const float madEvidence = std::clamp(1.0f - input.tileMadCode / std::max(madLimit, 1.0e-6f), 0.0f, 1.0f);
    const float agreementEvidence = std::clamp(
            1.0f - std::abs(input.lowerTailGreenExcessCode - input.tileMedianGreenExcessCode) /
                    std::max(agreementLimit, 1.0e-6f),
            0.0f, 1.0f);
    out.confidence = std::clamp(
            0.35f * noiseEvidence + 0.30f * consensusEvidence +
            0.20f * madEvidence + 0.15f * agreementEvidence,
            0.0f, 1.0f);
    out.apply = true;
    out.reason = "confidence_gated_common_green_residual";
    return out;
}

} // namespace bncam::raw_black
