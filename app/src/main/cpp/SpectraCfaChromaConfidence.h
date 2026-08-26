#pragma once

#include "SpectraChromaBands.h"

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <string>

namespace bncam::spectra2 {

/**
 * Delta 20: immutable, pre-demosaic CFA chroma evidence contract.
 *
 * This deliberately does not classify a sample as "noise" or grant pixel-mutation authority.
 * It only consolidates evidence SPECTRA already computes independently: sampled R-G/B-G band
 * pressure, noise-model support, G1/G2 agreement, structure protection and the low-frequency
 * field confidence. Later demosaic/conditioning stages can consume the same contract instead of
 * inventing their own incompatible interpretation of those signals.
 */
struct CfaChromaConfidenceSummary {
    std::string status = "UNAVAILABLE";
    std::string authority = "EVIDENCE_ONLY_NO_PIXEL_AUTHORITY";

    float modelSupport = 0.0f;
    float bandMeasurementSupport = 0.0f;
    float redBlueSampleBalance = 0.0f;
    float commonOpponentSupport = 0.0f;

    float fineResidualPressure = 0.0f;
    float midResidualPressure = 0.0f;
    float lowResidualPressure = 0.0f;
    float fineCorrectionConfidence = 0.0f;
    float midCorrectionConfidence = 0.0f;
    float lowCorrectionConfidence = 0.0f;

    // Delta 22: split red/blue confidence is derived from the aggregate band pressure plus
    // measured per-colour energy dominance. This keeps the established aggregate noise-floor
    // contract authoritative while exposing which opponent channel carries the excess.
    float redFineResidualPressure = 0.0f;
    float redMidResidualPressure = 0.0f;
    float redLowResidualPressure = 0.0f;
    float blueFineResidualPressure = 0.0f;
    float blueMidResidualPressure = 0.0f;
    float blueLowResidualPressure = 0.0f;
    float redFineCorrectionConfidence = 0.0f;
    float redMidCorrectionConfidence = 0.0f;
    float redLowCorrectionConfidence = 0.0f;
    float blueFineCorrectionConfidence = 0.0f;
    float blueMidCorrectionConfidence = 0.0f;
    float blueLowCorrectionConfidence = 0.0f;
    float redOpponentCorrectionConfidence = 0.0f;
    float blueOpponentCorrectionConfidence = 0.0f;

    // Delta 37: channel-resolved lens/color-shading can only amplify already-observed
    // opponent risk. It never creates chroma evidence on its own. Multipliers remain
    // exactly 1.0 when R/G or B/G does not show extra channel gain over green.
    bool shadingRiskAvailable = false;
    bool shadingRiskApplied = false;
    float redShadingRiskSupport = 0.0f;
    float blueShadingRiskSupport = 0.0f;
    float redShadingRiskMultiplier = 1.0f;
    float blueShadingRiskMultiplier = 1.0f;

    float structureProtection = 0.0f;
    float tensorConfidenceP50 = 0.0f;
    float edgeProtectedFraction = 0.0f;

    float greenSplitTileConsensus = 0.0f;
    float greenSplitMad = 0.0f;
    float greenSplitResidualAbs = 0.0f;
    std::uint64_t greenSplitTileCount = 0u;

    float lowFrequencyFieldSupport = 0.0f;
};

inline float cfaFiniteUnit(float value) {
    return std::isfinite(value) ? std::clamp(value, 0.0f, 1.0f) : 0.0f;
}

inline float finiteNonNegativeCfa(float value) {
    return std::isfinite(value) ? std::max(0.0f, value) : 0.0f;
}

inline float splitResidualPressure(
        float aggregatePressure, float channelEnergy, float peerEnergy
) {
    const float channel = finiteNonNegativeCfa(channelEnergy);
    const float peer = finiteNonNegativeCfa(peerEnergy);
    const float pairMean = 0.5f * (channel + peer);
    if (pairMean <= 1.0e-12f) return 0.0f;
    // Relative dominance is bounded so one weak/contaminated colour sample cannot acquire
    // disproportionate authority from an otherwise valid aggregate band decision.
    const float relativeDominance = std::clamp(channel / pairMean, 0.25f, 1.75f);
    return std::clamp(cfaFiniteUnit(aggregatePressure) * relativeDominance, 0.0f, 1.0f);
}

struct ChannelShadingRisk {
    bool available = false;
    float redSupport = 0.0f;
    float blueSupport = 0.0f;
    float redMultiplier = 1.0f;
    float blueMultiplier = 1.0f;
};

inline float positiveChannelGainStops(float channelToGreenRatio) {
    if (!std::isfinite(channelToGreenRatio) || channelToGreenRatio <= 1.0f) return 0.0f;
    return std::clamp(std::log2(channelToGreenRatio), 0.0f, 1.0f);
}

/**
 * Delta 37: convert the Delta-36 channel-shading audit into bounded risk multipliers.
 *
 * Only positive channel gain relative to green is considered a noise-amplification risk.
 * A spatial outer-minus-centre rise can strengthen that risk, but can never create it when
 * the P90 channel-to-green gain is not above unity. Maximum extra evidence is +15%.
 */
inline ChannelShadingRisk resolveChannelShadingRisk(
        float redToGreenP90,
        float blueToGreenP90,
        float outerMinusCenterRedToGreen,
        float outerMinusCenterBlueToGreen
) {
    ChannelShadingRisk risk{};
    const auto channelSupport = [](float ratioP90, float outerMinusCenter) -> float {
        const float positiveStops = positiveChannelGainStops(ratioP90);
        if (positiveStops <= 0.0f) return 0.0f;
        const float gainSupport = std::clamp(positiveStops / 0.50f, 0.0f, 1.0f);
        const float spatialSupport = std::clamp(
                std::isfinite(outerMinusCenter)
                        ? std::max(0.0f, outerMinusCenter) / 0.20f
                        : 0.0f,
                0.0f,
                1.0f
        );
        return std::clamp(0.75f * gainSupport + 0.25f * spatialSupport, 0.0f, 1.0f);
    };

    risk.redSupport = channelSupport(redToGreenP90, outerMinusCenterRedToGreen);
    risk.blueSupport = channelSupport(blueToGreenP90, outerMinusCenterBlueToGreen);
    risk.redMultiplier = 1.0f + 0.15f * risk.redSupport;
    risk.blueMultiplier = 1.0f + 0.15f * risk.blueSupport;
    risk.available = risk.redSupport > 0.0f || risk.blueSupport > 0.0f;
    return risk;
}

inline float applyChannelShadingRiskToEvidence(float evidence, float multiplier) {
    const float base = cfaFiniteUnit(evidence);
    if (base <= 0.0f) return 0.0f;
    const float boundedMultiplier = std::clamp(
            std::isfinite(multiplier) ? multiplier : 1.0f,
            1.0f,
            1.15f
    );
    return std::clamp(base * boundedMultiplier, 0.0f, 1.0f);
}

inline void applyChannelShadingRisk(
        CfaChromaConfidenceSummary& summary,
        const ChannelShadingRisk& risk
) {
    summary.shadingRiskAvailable = risk.available;
    summary.redShadingRiskSupport = cfaFiniteUnit(risk.redSupport);
    summary.blueShadingRiskSupport = cfaFiniteUnit(risk.blueSupport);
    summary.redShadingRiskMultiplier = std::clamp(risk.redMultiplier, 1.0f, 1.15f);
    summary.blueShadingRiskMultiplier = std::clamp(risk.blueMultiplier, 1.0f, 1.15f);

    const float redBefore = summary.redOpponentCorrectionConfidence;
    const float blueBefore = summary.blueOpponentCorrectionConfidence;

    summary.redFineCorrectionConfidence = applyChannelShadingRiskToEvidence(
            summary.redFineCorrectionConfidence, summary.redShadingRiskMultiplier);
    summary.redMidCorrectionConfidence = applyChannelShadingRiskToEvidence(
            summary.redMidCorrectionConfidence, summary.redShadingRiskMultiplier);
    summary.redLowCorrectionConfidence = applyChannelShadingRiskToEvidence(
            summary.redLowCorrectionConfidence, summary.redShadingRiskMultiplier);
    summary.blueFineCorrectionConfidence = applyChannelShadingRiskToEvidence(
            summary.blueFineCorrectionConfidence, summary.blueShadingRiskMultiplier);
    summary.blueMidCorrectionConfidence = applyChannelShadingRiskToEvidence(
            summary.blueMidCorrectionConfidence, summary.blueShadingRiskMultiplier);
    summary.blueLowCorrectionConfidence = applyChannelShadingRiskToEvidence(
            summary.blueLowCorrectionConfidence, summary.blueShadingRiskMultiplier);

    summary.redOpponentCorrectionConfidence = std::max({
            summary.redFineCorrectionConfidence,
            summary.redMidCorrectionConfidence,
            summary.redLowCorrectionConfidence
    });
    summary.blueOpponentCorrectionConfidence = std::max({
            summary.blueFineCorrectionConfidence,
            summary.blueMidCorrectionConfidence,
            summary.blueLowCorrectionConfidence
    });
    summary.shadingRiskApplied =
            summary.redOpponentCorrectionConfidence > redBefore + 1.0e-6f ||
            summary.blueOpponentCorrectionConfidence > blueBefore + 1.0e-6f;
}

/**
 * Delta 23: bounded red/blue authority redistribution for Pass 2.
 *
 * The established aggregate band plan remains authoritative. This helper only redistributes a
 * small amount of that authority between the R-G and B-G opponents when split evidence is strong
 * enough to support the distinction. The pair is centred on 1.0 so it cannot increase both
 * opponents together, and weak/ambiguous evidence stays exactly neutral.
 */
struct OpponentAuthorityPair {
    bool active = false;
    float redMultiplier = 1.0f;
    float blueMultiplier = 1.0f;
    float modulation = 0.0f;
};

inline OpponentAuthorityPair resolveOpponentAuthorityPair(
        float redCorrectionConfidence,
        float blueCorrectionConfidence,
        float maximumModulation = 0.12f
) {
    OpponentAuthorityPair pair{};
    const float red = cfaFiniteUnit(redCorrectionConfidence);
    const float blue = cfaFiniteUnit(blueCorrectionConfidence);
    const float total = red + blue;
    const float maxMod = std::clamp(
            std::isfinite(maximumModulation) ? maximumModulation : 0.0f,
            0.0f,
            0.20f
    );
    if (total < 0.10f || maxMod <= 0.0f) return pair;

    const float contrast = std::clamp((red - blue) / total, -1.0f, 1.0f);
    const float evidenceSupport = std::max(red, blue);
    pair.modulation = std::clamp(maxMod * contrast * evidenceSupport, -maxMod, maxMod);
    if (std::abs(pair.modulation) < 0.0025f) {
        pair.modulation = 0.0f;
        return pair;
    }
    pair.redMultiplier = 1.0f + pair.modulation;
    pair.blueMultiplier = 1.0f - pair.modulation;
    pair.active = true;
    return pair;
}

inline CfaChromaConfidenceSummary buildCfaChromaConfidenceSummary(
        int spectraMode,
        float signalModelConfidence,
        const ChromaBandEnergySnapshot& measuredBands,
        const ChromaBandPlan& finePlan,
        const ChromaBandPlan& midPlan,
        const ChromaBandPlan& lowPlan,
        float lowFrequencyFieldConfidence,
        float tensorConfidenceP50,
        float edgeProtectedFraction,
        float greenSplitTileConsensus,
        float greenSplitMad,
        float greenSplitResidual,
        std::uint64_t greenSplitTileCount
) {
    CfaChromaConfidenceSummary summary{};
    if (spectraMode == 0) {
        summary.status = "SPECTRA_OFF_EVIDENCE_NOT_ACTIVE";
        return summary;
    }

    summary.modelSupport = cfaFiniteUnit(signalModelConfidence);
    summary.bandMeasurementSupport = cfaFiniteUnit(measuredBands.confidence);
    summary.redBlueSampleBalance = cfaFiniteUnit(measuredBands.redBlueSampleBalance);
    summary.commonOpponentSupport = std::sqrt(std::max(
            0.0f,
            summary.modelSupport * summary.bandMeasurementSupport *
                    summary.redBlueSampleBalance
    ));

    summary.fineResidualPressure = cfaFiniteUnit(finePlan.requiredReductionFraction);
    summary.midResidualPressure = cfaFiniteUnit(midPlan.requiredReductionFraction);
    summary.lowResidualPressure = cfaFiniteUnit(lowPlan.requiredReductionFraction);
    summary.fineCorrectionConfidence =
            summary.commonOpponentSupport * summary.fineResidualPressure;
    summary.midCorrectionConfidence =
            summary.commonOpponentSupport * summary.midResidualPressure;
    summary.lowFrequencyFieldSupport = cfaFiniteUnit(lowFrequencyFieldConfidence);
    summary.lowCorrectionConfidence =
            summary.commonOpponentSupport * summary.lowResidualPressure *
                    summary.lowFrequencyFieldSupport;

    summary.redFineResidualPressure = splitResidualPressure(
            summary.fineResidualPressure, measuredBands.redFineEnergy, measuredBands.blueFineEnergy);
    summary.redMidResidualPressure = splitResidualPressure(
            summary.midResidualPressure, measuredBands.redMidEnergy, measuredBands.blueMidEnergy);
    summary.redLowResidualPressure = splitResidualPressure(
            summary.lowResidualPressure, measuredBands.redLowEnergy, measuredBands.blueLowEnergy);
    summary.blueFineResidualPressure = splitResidualPressure(
            summary.fineResidualPressure, measuredBands.blueFineEnergy, measuredBands.redFineEnergy);
    summary.blueMidResidualPressure = splitResidualPressure(
            summary.midResidualPressure, measuredBands.blueMidEnergy, measuredBands.redMidEnergy);
    summary.blueLowResidualPressure = splitResidualPressure(
            summary.lowResidualPressure, measuredBands.blueLowEnergy, measuredBands.redLowEnergy);

    summary.redFineCorrectionConfidence =
            summary.commonOpponentSupport * summary.redFineResidualPressure;
    summary.redMidCorrectionConfidence =
            summary.commonOpponentSupport * summary.redMidResidualPressure;
    summary.redLowCorrectionConfidence = summary.commonOpponentSupport *
            summary.redLowResidualPressure * summary.lowFrequencyFieldSupport;
    summary.blueFineCorrectionConfidence =
            summary.commonOpponentSupport * summary.blueFineResidualPressure;
    summary.blueMidCorrectionConfidence =
            summary.commonOpponentSupport * summary.blueMidResidualPressure;
    summary.blueLowCorrectionConfidence = summary.commonOpponentSupport *
            summary.blueLowResidualPressure * summary.lowFrequencyFieldSupport;
    summary.redOpponentCorrectionConfidence = std::max({
            summary.redFineCorrectionConfidence,
            summary.redMidCorrectionConfidence,
            summary.redLowCorrectionConfidence
    });
    summary.blueOpponentCorrectionConfidence = std::max({
            summary.blueFineCorrectionConfidence,
            summary.blueMidCorrectionConfidence,
            summary.blueLowCorrectionConfidence
    });

    summary.tensorConfidenceP50 = cfaFiniteUnit(tensorConfidenceP50);
    summary.edgeProtectedFraction = cfaFiniteUnit(edgeProtectedFraction);
    summary.structureProtection = std::max(
            summary.tensorConfidenceP50,
            summary.edgeProtectedFraction
    );

    summary.greenSplitTileConsensus = cfaFiniteUnit(greenSplitTileConsensus);
    summary.greenSplitMad = finiteNonNegativeCfa(greenSplitMad);
    summary.greenSplitResidualAbs = finiteNonNegativeCfa(std::abs(greenSplitResidual));
    summary.greenSplitTileCount = greenSplitTileCount;

    if (summary.commonOpponentSupport < 0.20f) {
        summary.status = "LOW_COMMON_OPPONENT_SUPPORT_EVIDENCE_ONLY";
    } else if (summary.redBlueSampleBalance < 0.75f) {
        summary.status = "UNBALANCED_RB_SUPPORT_EVIDENCE_ONLY";
    } else {
        summary.status = "READY_EVIDENCE_ONLY_NO_PIXEL_AUTHORITY";
    }
    return summary;
}

} // namespace bncam::spectra2
