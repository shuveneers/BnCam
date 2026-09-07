#pragma once

#include <algorithm>
#include <array>
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <string>

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
    // robust global opponent median so read-only spatial chroma evidence cannot be mistaken for global WB.
    static constexpr int kLowFrequencyChromaGridColumns = 16;
    static constexpr int kLowFrequencyChromaGridRows = 12;
    static constexpr std::size_t kLowFrequencyChromaGridSize =
            static_cast<std::size_t>(kLowFrequencyChromaGridColumns * kLowFrequencyChromaGridRows);
    std::array<float, kLowFrequencyChromaGridSize> lowFrequencyChromaFieldRG{};
    std::array<float, kLowFrequencyChromaGridSize> lowFrequencyChromaFieldBG{};
    // Mean linear luma of the same accepted flat-region samples. This stays as read-only
    // context for diagnostics / future neural conditioning without another full-frame scan.
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
