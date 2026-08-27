#pragma once

#include <algorithm>
#include <array>
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <string>
#include <vector>

#include "SpectraNoisePropagation.h"

namespace bncam::spectra2 {

struct ResidualObservation {
    std::string stage = "UNKNOWN";
    std::string method = "UNINITIALIZED";
    std::string status = "UNAVAILABLE";
    double confidence = 0.0;
    double filterEnergyGain = 1.25;

    double varianceY = 0.0;
    double varianceRG = 0.0;
    double varianceBG = 0.0;
    double covarianceRgBg = 0.0;

    double robustVarianceY = 0.0;
    double robustVarianceRG = 0.0;
    double robustVarianceBG = 0.0;
    double robustCovarianceRgBg = 0.0;

    double varianceYP10 = 0.0;
    double varianceYP50 = 0.0;
    double varianceYP90 = 0.0;
    double varianceRGP10 = 0.0;
    double varianceRGP50 = 0.0;
    double varianceRGP90 = 0.0;
    double varianceBGP10 = 0.0;
    double varianceBGP50 = 0.0;
    double varianceBGP90 = 0.0;

    // Coarse opponent-colour field statistics from the same flat-region samples. These are
    // observability metrics, not a claim that low-frequency scene colour is sensor noise.
    double lowFrequencyMedianRG = 0.0;
    double lowFrequencyMedianBG = 0.0;
    double lowFrequencyChromaFieldEnergyP50 = 0.0;
    double lowFrequencyChromaFieldEnergyP90 = 0.0;
    double lowFrequencyChromaNeighbourEnergyP50 = 0.0;
    double lowFrequencyChromaNeighbourEnergyP90 = 0.0;
    std::size_t lowFrequencyChromaValidTileCount = 0;
    std::size_t lowFrequencyChromaNeighbourPairCount = 0;

    // Delta 42: retain the already-computed 16x12 opponent-colour field instead of
    // discarding it after aggregate statistics are produced. Values are centered on the
    // robust global opponent median so a later cloud model cannot silently alter global WB.
    static constexpr int kLowFrequencyChromaGridColumns = 16;
    static constexpr int kLowFrequencyChromaGridRows = 12;
    static constexpr std::size_t kLowFrequencyChromaGridSize =
            static_cast<std::size_t>(kLowFrequencyChromaGridColumns * kLowFrequencyChromaGridRows);
    std::array<float, kLowFrequencyChromaGridSize> lowFrequencyChromaFieldRG{};
    std::array<float, kLowFrequencyChromaGridSize> lowFrequencyChromaFieldBG{};
    // Mean linear luma of the same accepted flat-region samples. This is retained separately
    // from the opponent field so a later cloud corrector can distinguish deep shadow clouds
    // from bright, legitimately coloured scene regions without another full-frame scan.
    std::array<float, kLowFrequencyChromaGridSize> lowFrequencyMeanLuma{};
    std::array<std::uint8_t, kLowFrequencyChromaGridSize> lowFrequencyChromaFieldValid{};
    double lowFrequencyLumaP10 = 0.0;
    double lowFrequencyLumaP50 = 0.0;
    double lowFrequencyLumaP90 = 0.0;

    double structureThreshold = 0.0;
    double flatSampleFraction = 0.0;
    double textureContamination = 1.0;
    std::size_t candidateSampleCount = 0;
    std::size_t acceptedSampleCount = 0;
    std::size_t validTileCount = 0;
    std::size_t totalTileCount = 0;
};


struct ChromaCloudCorrectionPlan {
    bool ready = false;
    std::string status = "UNAVAILABLE";
    float riskEvidence = 0.0f;
    float redAuthority = 0.0f;
    float blueAuthority = 0.0f;
    float redWbGainPressure = 0.0f;
    float blueWbGainPressure = 0.0f;
    float redCcmGainPressure = 0.0f;
    float blueCcmGainPressure = 0.0f;
    float maxAbsoluteCorrection = 0.0f;
    float correctionRms = 0.0f;
    float correctionP90 = 0.0f;
    std::size_t validTileCount = 0;
    // Delta 135: local authority can increase only in already-supported deep-shadow tiles.
    // This is telemetry for the plan itself; it does not change the global WB/color balance.
    std::size_t shadowBoostedTileCount = 0;
    float meanShadowAuthorityBoost = 1.0f;
    float maxShadowAuthorityBoost = 1.0f;
    std::array<float, ResidualObservation::kLowFrequencyChromaGridSize> correctionRG{};
    std::array<float, ResidualObservation::kLowFrequencyChromaGridSize> correctionBG{};
    std::array<std::uint8_t, ResidualObservation::kLowFrequencyChromaGridSize> valid{};
};

inline float robustMedianSmall(std::vector<float> values) {
    if (values.empty()) return 0.0f;
    const std::size_t middle = values.size() / 2u;
    std::nth_element(values.begin(), values.begin() + static_cast<std::ptrdiff_t>(middle), values.end());
    float median = values[middle];
    if ((values.size() & 1u) == 0u && middle > 0u) {
        const auto lower = std::max_element(values.begin(), values.begin() + static_cast<std::ptrdiff_t>(middle));
        median = 0.5f * (median + *lower);
    }
    return std::isfinite(median) ? median : 0.0f;
}

inline ChromaCloudCorrectionPlan buildChromaCloudCorrectionPlan(
        const ResidualObservation& observation,
        float cloudRiskEvidence,
        float redLowSupport,
        float blueLowSupport,
        float structureProtection,
        float measuredPostChromaRms,
        float upcomingWbGainR,
        float upcomingWbGainB,
        float ccmOnlyOpponentGainR,
        float ccmOnlyOpponentGainB
) {
    ChromaCloudCorrectionPlan plan{};
    plan.riskEvidence = std::clamp(
            std::isfinite(cloudRiskEvidence) ? cloudRiskEvidence : 0.0f, 0.0f, 1.0f);
    const float redSupport = std::clamp(
            std::isfinite(redLowSupport) ? redLowSupport : 0.0f, 0.0f, 1.0f);
    const float blueSupport = std::clamp(
            std::isfinite(blueLowSupport) ? blueLowSupport : 0.0f, 0.0f, 1.0f);
    const float structureRelief = 1.0f - 0.50f * std::clamp(
            std::isfinite(structureProtection) ? structureProtection : 1.0f, 0.0f, 1.0f);
    const float residualRms = std::max(
            0.0f, std::isfinite(measuredPostChromaRms) ? measuredPostChromaRms : 0.0f);

    if (observation.lowFrequencyChromaValidTileCount < 6u) {
        plan.status = "INSUFFICIENT_VALID_CHROMA_FIELD_TILES";
        return plan;
    }
    if (plan.riskEvidence < 0.10f || std::max(redSupport, blueSupport) <= 1.0e-6f) {
        plan.status = "NO_SUPPORTED_CHROMA_CLOUD_CORRECTION_REQUEST";
        return plan;
    }

    // Delta 49: anticipate only amplification above unity in the upcoming WB. WB itself remains
    // colorimetrically untouched; the pre-WB cloud plan becomes slightly more assertive instead.
    const auto wbGainPressure = [](float gain) -> float {
        const float safeGain = std::max(1.0f, std::isfinite(gain) ? gain : 1.0f);
        const float u = std::clamp((safeGain - 1.05f) / (2.25f - 1.05f), 0.0f, 1.0f);
        return u * u * (3.0f - 2.0f * u);
    };
    plan.redWbGainPressure = wbGainPressure(upcomingWbGainR);
    plan.blueWbGainPressure = wbGainPressure(upcomingWbGainB);
    const auto ccmGainPressure = [](float gain) -> float {
        const float safeGain = std::max(1.0f, std::isfinite(gain) ? gain : 1.0f);
        const float u = std::clamp((safeGain - 1.05f) / (1.60f - 1.05f), 0.0f, 1.0f);
        return u * u * (3.0f - 2.0f * u);
    };
    plan.redCcmGainPressure = ccmGainPressure(ccmOnlyOpponentGainR);
    plan.blueCcmGainPressure = ccmGainPressure(ccmOnlyOpponentGainB);
    const float redDownstreamAuthorityBoost =
            1.0f + 0.15f * plan.redWbGainPressure + 0.10f * plan.redCcmGainPressure;
    const float blueDownstreamAuthorityBoost =
            1.0f + 0.15f * plan.blueWbGainPressure + 0.10f * plan.blueCcmGainPressure;
    // Delta 0062: device evidence shows the supported 1x coherent cloud survives while
    // downstream WB/CCM predicts ~7-9x visibility amplification. Increase only this already
    // evidenced low-frequency owner; local demosaic topology remains Phase-6-owned.
    plan.redAuthority = std::clamp(
            0.24f * plan.riskEvidence * redSupport * structureRelief * redDownstreamAuthorityBoost,
            0.0f, 0.26f);
    plan.blueAuthority = std::clamp(
            0.24f * plan.riskEvidence * blueSupport * structureRelief * blueDownstreamAuthorityBoost,
            0.0f, 0.26f);
    // Never propose a tile correction larger than half of the measured residual RMS, with
    // an absolute normalized-linear ceiling as an additional fail-safe.
    plan.maxAbsoluteCorrection = std::min(0.010f, 0.50f * residualRms);
    if (!(plan.maxAbsoluteCorrection > 0.0f)) {
        plan.status = "NO_MEASURED_RESIDUAL_BUDGET_FOR_CLOUD_CORRECTION";
        return plan;
    }

    constexpr int cols = ResidualObservation::kLowFrequencyChromaGridColumns;
    constexpr int rows = ResidualObservation::kLowFrequencyChromaGridRows;
    std::array<float, ResidualObservation::kLowFrequencyChromaGridSize> smoothRg{};
    std::array<float, ResidualObservation::kLowFrequencyChromaGridSize> smoothBg{};
    std::array<std::uint8_t, ResidualObservation::kLowFrequencyChromaGridSize> smoothValid{};
    for (int y = 0; y < rows; ++y) {
        for (int x = 0; x < cols; ++x) {
            const std::size_t index = static_cast<std::size_t>(y * cols + x);
            if (observation.lowFrequencyChromaFieldValid[index] == 0u) continue;
            float sumRg = 0.0f;
            float sumBg = 0.0f;
            float weightSum = 0.0f;
            for (int dy = -1; dy <= 1; ++dy) {
                for (int dx = -1; dx <= 1; ++dx) {
                    const int nx = x + dx;
                    const int ny = y + dy;
                    if (nx < 0 || nx >= cols || ny < 0 || ny >= rows) continue;
                    const std::size_t neighbour = static_cast<std::size_t>(ny * cols + nx);
                    if (observation.lowFrequencyChromaFieldValid[neighbour] == 0u) continue;
                    const float weight = (dx == 0 && dy == 0) ? 2.0f : 1.0f;
                    sumRg += weight * observation.lowFrequencyChromaFieldRG[neighbour];
                    sumBg += weight * observation.lowFrequencyChromaFieldBG[neighbour];
                    weightSum += weight;
                }
            }
            if (weightSum < 3.0f) continue;
            smoothRg[index] = sumRg / weightSum;
            smoothBg[index] = sumBg / weightSum;
            smoothValid[index] = 1u;
        }
    }

    // Re-center the smoothed proposal robustly. This is a second guard against accidental
    // global WB movement caused by sparse/irregular valid-tile support.
    std::vector<float> validRg;
    std::vector<float> validBg;
    validRg.reserve(observation.lowFrequencyChromaValidTileCount);
    validBg.reserve(observation.lowFrequencyChromaValidTileCount);
    for (std::size_t i = 0; i < smoothValid.size(); ++i) {
        if (smoothValid[i] == 0u) continue;
        validRg.push_back(smoothRg[i]);
        validBg.push_back(smoothBg[i]);
    }
    const float medianRg = robustMedianSmall(validRg);
    const float medianBg = robustMedianSmall(validBg);

    std::vector<float> magnitudes;
    double sumSquared = 0.0;
    for (std::size_t i = 0; i < smoothValid.size(); ++i) {
        if (smoothValid[i] == 0u) continue;
        const float centeredRg = smoothRg[i] - medianRg;
        const float centeredBg = smoothBg[i] - medianBg;

        // Delta 135: cloud correction is allowed to become modestly more assertive only in
        // genuinely dark tiles that already passed the existing cloud-risk, CFA-support and
        // flat-field gates above.  Midtones/highlights keep the exact pre-Delta-135 authority.
        // No authority is created when the global plan authority is zero.
        const float tileLuma = std::clamp(
                std::isfinite(observation.lowFrequencyMeanLuma[i])
                        ? observation.lowFrequencyMeanLuma[i]
                        : 1.0f,
                0.0f,
                1.0f);
        const float shadowT = std::clamp((0.18f - tileLuma) / (0.18f - 0.055f), 0.0f, 1.0f);
        const float shadowGate = shadowT * shadowT * (3.0f - 2.0f * shadowT);
        const float shadowAuthorityBoost = 1.0f + 0.25f * shadowGate;
        const float localRedAuthority = std::min(0.30f, plan.redAuthority * shadowAuthorityBoost);
        const float localBlueAuthority = std::min(0.30f, plan.blueAuthority * shadowAuthorityBoost);
        if (shadowAuthorityBoost > 1.0001f &&
                (localRedAuthority > plan.redAuthority + 1.0e-7f ||
                 localBlueAuthority > plan.blueAuthority + 1.0e-7f)) {
            ++plan.shadowBoostedTileCount;
            plan.meanShadowAuthorityBoost += shadowAuthorityBoost;
            plan.maxShadowAuthorityBoost = std::max(
                    plan.maxShadowAuthorityBoost, shadowAuthorityBoost);
        }

        const float correctionRg = std::clamp(
                -centeredRg * localRedAuthority,
                -plan.maxAbsoluteCorrection,
                plan.maxAbsoluteCorrection);
        const float correctionBg = std::clamp(
                -centeredBg * localBlueAuthority,
                -plan.maxAbsoluteCorrection,
                plan.maxAbsoluteCorrection);
        plan.correctionRG[i] = correctionRg;
        plan.correctionBG[i] = correctionBg;
        plan.valid[i] = 1u;
        ++plan.validTileCount;
        const float magnitude = std::sqrt(0.5f * (
                correctionRg * correctionRg + correctionBg * correctionBg));
        magnitudes.push_back(magnitude);
        sumSquared += static_cast<double>(magnitude) * static_cast<double>(magnitude);
    }

    if (plan.validTileCount < 6u) {
        plan = ChromaCloudCorrectionPlan{};
        plan.status = "INSUFFICIENT_SMOOTH_CLOUD_FIELD_SUPPORT";
        return plan;
    }
    plan.correctionRms = static_cast<float>(std::sqrt(
            sumSquared / static_cast<double>(plan.validTileCount)));
    if (plan.shadowBoostedTileCount > 0u) {
        // The accumulator starts at 1.0 so telemetry remains neutral when no tile is boosted.
        plan.meanShadowAuthorityBoost = (plan.meanShadowAuthorityBoost - 1.0f) /
                static_cast<float>(plan.shadowBoostedTileCount);
    } else {
        plan.meanShadowAuthorityBoost = 1.0f;
        plan.maxShadowAuthorityBoost = 1.0f;
    }
    std::sort(magnitudes.begin(), magnitudes.end());
    const std::size_t p90Index = std::min(
            magnitudes.size() - 1u,
            static_cast<std::size_t>(std::floor(0.90 * static_cast<double>(magnitudes.size() - 1u))));
    plan.correctionP90 = magnitudes[p90Index];
    plan.ready = plan.correctionP90 > 1.0e-7f;
    plan.status = plan.ready ? "CLOUD_CORRECTION_PLAN_READY_FOR_PRE_WB_APPLICATION" : "NEGLIGIBLE_CLOUD_CORRECTION_PLAN";
    return plan;
}

struct CalibrationComparison {
    std::string stage = "UNKNOWN";
    std::string status = "UNAVAILABLE";
    bool ready = false;
    double confidence = 0.0;
    double measuredToPredictedY = 0.0;
    double measuredToPredictedRG = 0.0;
    double measuredToPredictedBG = 0.0;
    double measuredToPredictedChroma = 0.0;
    double absoluteLog2ErrorY = 0.0;
    double absoluteLog2ErrorRG = 0.0;
    double absoluteLog2ErrorBG = 0.0;
};

inline double finiteNonNegative(double value) {
    return std::isfinite(value) ? std::max(0.0, value) : 0.0;
}

inline double safeVarianceRatio(double measured, double predicted) {
    const double safeMeasured = finiteNonNegative(measured);
    const double safePredicted = finiteNonNegative(predicted);
    if (safePredicted <= kEpsilon) return 0.0;
    return safeMeasured / safePredicted;
}

inline double absoluteLog2RatioError(double ratio) {
    if (!std::isfinite(ratio) || ratio <= kEpsilon) return 0.0;
    return std::abs(std::log2(ratio));
}

inline CalibrationComparison comparePredictionToObservation(
        const NoiseState& predicted,
        const ResidualObservation& observed
) {
    CalibrationComparison comparison{};
    comparison.stage = observed.stage;

    const bool observationReady = observed.status ==
            "OBSERVATION_READY_NO_AUTO_CALIBRATION";
    const bool predictionReady = predicted.status == "PROPAGATED" ||
            predicted.status == "FALLBACK_PROPAGATED" ||
            predicted.status == "AVAILABLE";
    const bool enoughSupport = observed.acceptedSampleCount >= 2048u &&
            observed.validTileCount >= 6u;

    const double observedY = observed.robustVarianceY > 0.0
            ? observed.robustVarianceY
            : observed.varianceY;
    const double observedRG = observed.robustVarianceRG > 0.0
            ? observed.robustVarianceRG
            : observed.varianceRG;
    const double observedBG = observed.robustVarianceBG > 0.0
            ? observed.robustVarianceBG
            : observed.varianceBG;

    comparison.measuredToPredictedY = safeVarianceRatio(observedY, predicted.varianceY);
    comparison.measuredToPredictedRG = safeVarianceRatio(observedRG, predicted.varianceRG);
    comparison.measuredToPredictedBG = safeVarianceRatio(observedBG, predicted.varianceBG);
    comparison.measuredToPredictedChroma = safeVarianceRatio(
            observedRG + observedBG,
            predicted.varianceRG + predicted.varianceBG
    );
    comparison.absoluteLog2ErrorY = absoluteLog2RatioError(
            comparison.measuredToPredictedY
    );
    comparison.absoluteLog2ErrorRG = absoluteLog2RatioError(
            comparison.measuredToPredictedRG
    );
    comparison.absoluteLog2ErrorBG = absoluteLog2RatioError(
            comparison.measuredToPredictedBG
    );

    const bool exactStageMatch = observed.stage == predicted.stage;
    const bool recognizedStageBoundary =
            predicted.stage == "POST_COLOUR_MATRIX_RGB" &&
            observed.stage == "POST_COLOUR_MATRIX_RGB_AFTER_NONNEGATIVE_CLAMP";
    const bool comparableStage = exactStageMatch || recognizedStageBoundary;
    comparison.ready = observationReady && predictionReady && enoughSupport &&
            comparableStage && predicted.confidence > 0.0 &&
            comparison.measuredToPredictedChroma > 0.0;
    comparison.confidence = comparison.ready
            ? std::clamp(
                    std::min(predicted.confidence, observed.confidence) *
                            (exactStageMatch ? 1.0 : 0.75),
                    0.0,
                    1.0
            )
            : 0.0;

    if (!predictionReady) {
        comparison.status = "PREDICTION_UNAVAILABLE";
    } else if (!observationReady) {
        comparison.status = observed.status;
    } else if (!enoughSupport) {
        comparison.status = "INSUFFICIENT_FLAT_SUPPORT";
    } else if (!comparableStage) {
        comparison.status = "STAGE_MISMATCH";
    } else if (predicted.confidence <= 0.0) {
        comparison.status = "PREDICTION_CONFIDENCE_ZERO";
    } else if (!comparison.ready) {
        comparison.status = "INVALID_CALIBRATION_RATIO";
    } else if (recognizedStageBoundary) {
        comparison.status =
                "READY_OBSERVATION_ONLY_EXPLICIT_STAGE_BOUNDARY_NO_COEFFICIENT_UPDATE";
    } else {
        comparison.status = "READY_OBSERVATION_ONLY_NO_COEFFICIENT_UPDATE";
    }
    return comparison;
}

} // namespace bncam::spectra2
