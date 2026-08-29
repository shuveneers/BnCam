#pragma once

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <string>

namespace bncam::validation {

/**
 * Phase 16 reporting-only contract.
 *
 * This helper intentionally owns no image processing, thresholds, tuning, or quality PASS/FAIL
 * decision. It only normalizes already-existing objective telemetry into a compact scorecard so
 * RAW10/RAW_SENSOR validation captures can be compared reproducibly. Visual/reference review
 * remains mandatory for the quality judgements that telemetry cannot prove.
 */
struct ObjectiveValidationInput {
    bool raw10 = false;
    int iso = 0;

    double finalRedClippedPct = 0.0;
    double finalGreenClippedPct = 0.0;
    double finalBlueClippedPct = 0.0;
    double finalMeanR = 0.0;
    double finalMeanG = 0.0;
    double finalMeanB = 0.0;

    double measuredVarianceY = 0.0;
    double measuredVarianceRG = 0.0;
    double measuredVarianceBG = 0.0;
    std::uint64_t measuredResidualSampleCount = 0u;

    double modeledVarianceY = 0.0;
    double modeledVarianceRG = 0.0;
    double modeledVarianceBG = 0.0;
    double modeledConfidence = 0.0;

    double visibleInputVarianceRG = 0.0;
    double visibleInputVarianceBG = 0.0;
    double visibleOutputVarianceRG = 0.0;
    double visibleOutputVarianceBG = 0.0;
    std::uint64_t visibleInputResidualSampleCount = 0u;
    std::uint64_t visibleOutputResidualSampleCount = 0u;
    double visibleChangedPixelFraction = 0.0;
    double visibleMeanAcceptance = 0.0;
    double visibleMeanColourShift = 0.0;
    double visibleMaximumColourShift = 0.0;
    double visibleEdgePreservationScore = 1.0;
    double visibleOversmoothingScore = 0.0;
    std::uint64_t visibleProcessedPixelCount = 0u;
    std::uint64_t visibleCandidatePixelCount = 0u;
    std::uint64_t visibleChangedPixelCount = 0u;
    std::string visibleExecutionStatus;
    std::string visibleStatisticsMethod;

    bool chromaCloudClassificationReady = false;
    double chromaCloudRiskEvidence = 0.0;
    std::string chromaCloudRiskStatus;

    std::uint64_t linearDetailEvaluatedPixels = 0u;
    std::uint64_t linearDetailEdgeSupportedPixels = 0u;
    std::uint64_t linearDetailNoiseRejectedPixels = 0u;
    std::uint64_t linearDetailHaloClampedPixels = 0u;
    std::uint64_t perceptualDetailEvaluatedPixels = 0u;
    std::uint64_t perceptualDetailEdgeSupportedPixels = 0u;
    std::uint64_t perceptualDetailNoiseRejectedPixels = 0u;
    std::uint64_t perceptualDetailHaloClampedPixels = 0u;

    double awbConfidence = 0.0;
    double awbNeutralSupport = 0.0;
    double awbMixedLightScore = 0.0;
    double awbPriorDisagreement = 0.0;
    bool awbMixedIllumination = false;

    bool demosaicFallback = false;
    double demosaicMs = 0.0;
    double rawIspMs = 0.0;
    double sceneMedianSignal = 0.0;
    double sceneP90Gradient = 0.0;
    double sceneEdgeFraction = 0.0;
    double sceneLowSignalFraction = 0.0;

    bool residentPublication = false;
    std::string finalOutputStatsSource;
};

struct ObjectiveValidationSummary {
    std::string route = "RAW_SENSOR";
    std::string isoBand = "UNKNOWN";
    bool coreTelemetryReady = false;
    bool residualMeasurementReady = false;
    bool visibleChromaMeasurementReady = false;
    bool visibleChromaVarianceComparisonReady = false;
    bool visibleChromaSigmaRatioAvailable = false;
    bool falseColorRiskTelemetryReady = false;
    bool colourDriftProxyReady = false;
    std::string telemetryStatus = "UNINITIALIZED";

    double finalClipMaxPct = 0.0;
    double measuredFinalLumaSigma = 0.0;
    double measuredFinalChromaSigma = 0.0;
    double modeledFinalLumaSigma = 0.0;
    double modeledFinalChromaSigma = 0.0;
    double visibleChromaSigmaRatio = 0.0;
    double falseColorRiskEvidence = 0.0;
    double linearDetailEdgeSupportFraction = 0.0;
    double linearDetailNoiseRejectedFraction = 0.0;
    double linearDetailHaloClampFraction = 0.0;
    double perceptualDetailEdgeSupportFraction = 0.0;
    double perceptualDetailNoiseRejectedFraction = 0.0;
    double perceptualDetailHaloClampFraction = 0.0;

    // These remain true by design. Phase 16 explicitly requires actual-image/reference review.
    bool imageReviewRequired = true;
    bool colorAccuracyReferenceRequired = true;
    bool hueStabilityReferenceRequired = true;
    bool nearNyquistReferenceRequired = true;
};

inline bool validationFiniteNonNegative(double value) noexcept {
    return std::isfinite(value) && value >= 0.0;
}

inline double validationSigma(double variance) noexcept {
    return validationFiniteNonNegative(variance) ? std::sqrt(variance) : 0.0;
}

inline double validationFraction(std::uint64_t numerator, std::uint64_t denominator) noexcept {
    if (denominator == 0u) return 0.0;
    return std::clamp(
            static_cast<double>(numerator) / static_cast<double>(denominator),
            0.0,
            1.0);
}

inline const char* objectiveValidationIsoBand(int iso) noexcept {
    if (iso <= 0) return "UNKNOWN";
    if (iso <= 400) return "LOW";
    if (iso <= 1600) return "MEDIUM";
    return "HIGH";
}

inline ObjectiveValidationSummary summarizeObjectiveValidation(
        const ObjectiveValidationInput& input) noexcept {
    ObjectiveValidationSummary out{};
    out.route = input.raw10 ? "RAW10" : "RAW_SENSOR";
    out.isoBand = objectiveValidationIsoBand(input.iso);

    const bool finalOutputFinite =
            validationFiniteNonNegative(input.finalRedClippedPct) &&
            validationFiniteNonNegative(input.finalGreenClippedPct) &&
            validationFiniteNonNegative(input.finalBlueClippedPct) &&
            validationFiniteNonNegative(input.finalMeanR) && input.finalMeanR <= 1.0 &&
            validationFiniteNonNegative(input.finalMeanG) && input.finalMeanG <= 1.0 &&
            validationFiniteNonNegative(input.finalMeanB) && input.finalMeanB <= 1.0;
    const bool timingFinite = validationFiniteNonNegative(input.demosaicMs) &&
            validationFiniteNonNegative(input.rawIspMs);
    const bool exactFinalStats =
            input.finalOutputStatsSource == "RESIDENT_PUBLICATION_COPY_FUSED_EXACT" ||
            input.finalOutputStatsSource == "RESIDENT_BGR8_RESCAN_EXACT_FAILSAFE" ||
            input.finalOutputStatsSource == "CPU_QUANTIZATION_FUSED_EXACT";
    out.coreTelemetryReady = input.iso > 0 && finalOutputFinite && timingFinite && exactFinalStats;

    out.finalClipMaxPct = std::max({
            validationFiniteNonNegative(input.finalRedClippedPct) ? input.finalRedClippedPct : 0.0,
            validationFiniteNonNegative(input.finalGreenClippedPct) ? input.finalGreenClippedPct : 0.0,
            validationFiniteNonNegative(input.finalBlueClippedPct) ? input.finalBlueClippedPct : 0.0});

    out.residualMeasurementReady = input.measuredResidualSampleCount >= 2048u &&
            validationFiniteNonNegative(input.measuredVarianceY) &&
            validationFiniteNonNegative(input.measuredVarianceRG) &&
            validationFiniteNonNegative(input.measuredVarianceBG);
    if (out.residualMeasurementReady) {
        out.measuredFinalLumaSigma = validationSigma(input.measuredVarianceY);
        out.measuredFinalChromaSigma = std::sqrt(std::max(
                0.0, 0.5 * (input.measuredVarianceRG + input.measuredVarianceBG)));
    }

    out.modeledFinalLumaSigma = validationSigma(input.modeledVarianceY);
    if (validationFiniteNonNegative(input.modeledVarianceRG) &&
        validationFiniteNonNegative(input.modeledVarianceBG)) {
        out.modeledFinalChromaSigma = std::sqrt(std::max(
                0.0, 0.5 * (input.modeledVarianceRG + input.modeledVarianceBG)));
    }

    // The production resident visible-chroma path reports compact GPU statistics without
    // materializing a second full-frame pre/post residual image on CPU. Execution observability
    // and variance-comparison observability are therefore separate truths.
    const bool visibleFractionsFinite =
            validationFiniteNonNegative(input.visibleChangedPixelFraction) &&
            input.visibleChangedPixelFraction <= 1.0 &&
            validationFiniteNonNegative(input.visibleMeanAcceptance) &&
            input.visibleMeanAcceptance <= 1.0 &&
            validationFiniteNonNegative(input.visibleMeanColourShift) &&
            validationFiniteNonNegative(input.visibleMaximumColourShift) &&
            validationFiniteNonNegative(input.visibleEdgePreservationScore) &&
            input.visibleEdgePreservationScore <= 1.0 &&
            validationFiniteNonNegative(input.visibleOversmoothingScore) &&
            input.visibleOversmoothingScore <= 1.0;
    const bool visibleCountsCoherent =
            input.visibleProcessedPixelCount >= 2048u &&
            input.visibleCandidatePixelCount <= input.visibleProcessedPixelCount &&
            input.visibleChangedPixelCount <= input.visibleProcessedPixelCount;
    const bool visibleExecutionReported =
            !input.visibleExecutionStatus.empty() &&
            input.visibleExecutionStatus != "NOT_RUN" &&
            !input.visibleStatisticsMethod.empty() &&
            input.visibleStatisticsMethod != "NOT_RUN";
    out.visibleChromaMeasurementReady =
            visibleFractionsFinite && visibleCountsCoherent && visibleExecutionReported;
    out.colourDriftProxyReady = out.visibleChromaMeasurementReady;

    out.visibleChromaVarianceComparisonReady =
            input.visibleInputResidualSampleCount >= 2048u &&
            input.visibleOutputResidualSampleCount >= 2048u &&
            validationFiniteNonNegative(input.visibleInputVarianceRG) &&
            validationFiniteNonNegative(input.visibleInputVarianceBG) &&
            validationFiniteNonNegative(input.visibleOutputVarianceRG) &&
            validationFiniteNonNegative(input.visibleOutputVarianceBG);
    if (out.visibleChromaVarianceComparisonReady) {
        const double inputSigma = std::sqrt(std::max(
                0.0, 0.5 * (input.visibleInputVarianceRG + input.visibleInputVarianceBG)));
        const double outputSigma = std::sqrt(std::max(
                0.0, 0.5 * (input.visibleOutputVarianceRG + input.visibleOutputVarianceBG)));
        if (inputSigma > 1.0e-12) {
            out.visibleChromaSigmaRatio = outputSigma / inputSigma;
            out.visibleChromaSigmaRatioAvailable = true;
        }
    }

    out.falseColorRiskTelemetryReady =
            input.chromaCloudClassificationReady &&
            validationFiniteNonNegative(input.chromaCloudRiskEvidence) &&
            input.chromaCloudRiskEvidence <= 1.0 &&
            !input.chromaCloudRiskStatus.empty();
    if (out.falseColorRiskTelemetryReady) {
        out.falseColorRiskEvidence = input.chromaCloudRiskEvidence;
    }

    out.linearDetailEdgeSupportFraction = validationFraction(
            input.linearDetailEdgeSupportedPixels, input.linearDetailEvaluatedPixels);
    out.linearDetailNoiseRejectedFraction = validationFraction(
            input.linearDetailNoiseRejectedPixels, input.linearDetailEvaluatedPixels);
    out.linearDetailHaloClampFraction = validationFraction(
            input.linearDetailHaloClampedPixels, input.linearDetailEvaluatedPixels);
    out.perceptualDetailEdgeSupportFraction = validationFraction(
            input.perceptualDetailEdgeSupportedPixels, input.perceptualDetailEvaluatedPixels);
    out.perceptualDetailNoiseRejectedFraction = validationFraction(
            input.perceptualDetailNoiseRejectedPixels, input.perceptualDetailEvaluatedPixels);
    out.perceptualDetailHaloClampFraction = validationFraction(
            input.perceptualDetailHaloClampedPixels, input.perceptualDetailEvaluatedPixels);

    if (!out.coreTelemetryReady) {
        out.telemetryStatus = "CORE_TELEMETRY_INCOMPLETE";
    } else if (!out.residualMeasurementReady) {
        out.telemetryStatus = "CORE_READY_RESIDUAL_MEASUREMENT_UNAVAILABLE";
    } else if (!out.visibleChromaMeasurementReady) {
        out.telemetryStatus = "CORE_AND_FINAL_RESIDUAL_READY_VISIBLE_CHROMA_EXECUTION_TELEMETRY_UNAVAILABLE";
    } else if (!out.falseColorRiskTelemetryReady) {
        out.telemetryStatus = "CORE_RESIDUAL_VISIBLE_READY_FALSE_COLOR_RISK_TELEMETRY_UNAVAILABLE";
    } else if (!out.visibleChromaVarianceComparisonReady) {
        out.telemetryStatus =
                "OBJECTIVE_TELEMETRY_READY_VISIBLE_CHROMA_VARIANCE_COMPARISON_UNAVAILABLE_IMAGE_REVIEW_REQUIRED";
    } else {
        out.telemetryStatus = "OBJECTIVE_TELEMETRY_READY_IMAGE_REVIEW_REQUIRED";
    }
    return out;
}

} // namespace bncam::validation
