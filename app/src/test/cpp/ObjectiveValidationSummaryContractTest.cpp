#include "../../main/cpp/ObjectiveValidationSummary.h"

#include <cmath>
#include <cstdlib>
#include <iostream>
#include <limits>

namespace {

void require(bool condition, const char* message) {
    if (!condition) {
        std::cerr << "FAIL: " << message << '\n';
        std::exit(1);
    }
}

bool near(double a, double b, double epsilon = 1.0e-12) {
    return std::abs(a - b) <= epsilon;
}

} // namespace

int main() {
    require(std::string(bncam::validation::objectiveValidationIsoBand(0)) == "UNKNOWN",
            "ISO 0 must remain unknown");
    require(std::string(bncam::validation::objectiveValidationIsoBand(400)) == "LOW",
            "ISO 400 boundary must be LOW");
    require(std::string(bncam::validation::objectiveValidationIsoBand(401)) == "MEDIUM",
            "ISO 401 boundary must be MEDIUM");
    require(std::string(bncam::validation::objectiveValidationIsoBand(1600)) == "MEDIUM",
            "ISO 1600 boundary must be MEDIUM");
    require(std::string(bncam::validation::objectiveValidationIsoBand(1601)) == "HIGH",
            "ISO 1601 boundary must be HIGH");

    bncam::validation::ObjectiveValidationInput input{};
    input.raw10 = true;
    input.iso = 800;
    input.finalRedClippedPct = 0.10;
    input.finalGreenClippedPct = 0.05;
    input.finalBlueClippedPct = 0.20;
    input.finalMeanR = 0.31;
    input.finalMeanG = 0.29;
    input.finalMeanB = 0.27;
    input.measuredVarianceY = 0.0004;
    input.measuredVarianceRG = 0.0009;
    input.measuredVarianceBG = 0.0016;
    input.measuredResidualSampleCount = 4096u;
    input.modeledVarianceY = 0.0001;
    input.modeledVarianceRG = 0.0004;
    input.modeledVarianceBG = 0.0004;
    input.modeledConfidence = 0.8;
    input.visibleInputVarianceRG = 0.0016;
    input.visibleInputVarianceBG = 0.0016;
    input.visibleOutputVarianceRG = 0.0004;
    input.visibleOutputVarianceBG = 0.0004;
    input.visibleInputResidualSampleCount = 4096u;
    input.visibleOutputResidualSampleCount = 4096u;
    input.visibleChangedPixelFraction = 0.48;
    input.visibleMeanAcceptance = 0.36;
    input.visibleMeanColourShift = 0.0008;
    input.visibleMaximumColourShift = 0.033;
    input.visibleEdgePreservationScore = 0.992;
    input.visibleOversmoothingScore = 0.012;
    input.visibleProcessedPixelCount = 49152u;
    input.visibleCandidatePixelCount = 20543u;
    input.visibleChangedPixelCount = 23609u;
    input.visibleExecutionStatus = "SPECTRA_CONTEXT_FUSION_GPU_RESIDENT_VISIBLE_CHROMA_APPLIED";
    input.visibleStatisticsMethod = "GPU_16X16_STRATIFIED_COUNTS_AND_Q12_SAMPLES_NO_CPU_FULL_FRAME_MEASUREMENT";
    input.chromaCloudClassificationReady = true;
    input.chromaCloudRiskEvidence = 0.69;
    input.chromaCloudRiskStatus = "SUPPORTED_COHERENT_CHROMA_CLOUD_RISK";
    input.linearDetailEvaluatedPixels = 1000u;
    input.linearDetailEdgeSupportedPixels = 250u;
    input.linearDetailNoiseRejectedPixels = 100u;
    input.linearDetailHaloClampedPixels = 20u;
    input.perceptualDetailEvaluatedPixels = 200u;
    input.perceptualDetailEdgeSupportedPixels = 50u;
    input.perceptualDetailNoiseRejectedPixels = 20u;
    input.perceptualDetailHaloClampedPixels = 5u;
    input.demosaicMs = 60.0;
    input.rawIspMs = 1800.0;
    input.residentPublication = true;
    input.finalOutputStatsSource = "RESIDENT_PUBLICATION_COPY_FUSED_EXACT";

    const auto summary = bncam::validation::summarizeObjectiveValidation(input);
    require(summary.route == "RAW10", "route must preserve RAW10");
    require(summary.isoBand == "MEDIUM", "ISO 800 must be MEDIUM");
    require(summary.coreTelemetryReady, "complete core telemetry must be ready");
    require(summary.residualMeasurementReady, "final residual measurement must be ready");
    require(summary.visibleChromaMeasurementReady, "visible-chroma execution measurement must be ready");
    require(summary.visibleChromaVarianceComparisonReady,
            "full pre/post variance comparison must be ready when residual samples exist");
    require(summary.visibleChromaSigmaRatioAvailable,
            "sigma ratio must be explicitly available with full variance comparison");
    require(summary.falseColorRiskTelemetryReady, "false-color risk telemetry must be ready");
    require(summary.colourDriftProxyReady, "colour-drift proxy must be ready");
    require(summary.telemetryStatus == "OBJECTIVE_TELEMETRY_READY_IMAGE_REVIEW_REQUIRED",
            "ready telemetry must still require image review");
    require(near(summary.finalClipMaxPct, 0.20), "maximum clipping must be exact");
    require(near(summary.measuredFinalLumaSigma, 0.02), "luma sigma must be sqrt(variance)");
    require(near(summary.measuredFinalChromaSigma, std::sqrt(0.00125)),
            "chroma sigma must use mean opponent variance");
    require(near(summary.modeledFinalLumaSigma, 0.01), "modeled luma sigma mismatch");
    require(near(summary.modeledFinalChromaSigma, 0.02), "modeled chroma sigma mismatch");
    require(near(summary.visibleChromaSigmaRatio, 0.5),
            "visible chroma sigma ratio must be output/input");
    require(near(summary.linearDetailEdgeSupportFraction, 0.25), "linear edge fraction mismatch");
    require(near(summary.linearDetailNoiseRejectedFraction, 0.10), "linear reject fraction mismatch");
    require(near(summary.linearDetailHaloClampFraction, 0.02), "linear halo fraction mismatch");
    require(near(summary.perceptualDetailEdgeSupportFraction, 0.25), "perceptual edge fraction mismatch");
    require(near(summary.perceptualDetailNoiseRejectedFraction, 0.10), "perceptual reject fraction mismatch");
    require(near(summary.perceptualDetailHaloClampFraction, 0.025), "perceptual halo fraction mismatch");
    require(summary.imageReviewRequired && summary.colorAccuracyReferenceRequired &&
                    summary.hueStabilityReferenceRequired && summary.nearNyquistReferenceRequired,
            "objective telemetry must never replace image/reference review");

    // Current resident production truth: compact GPU counts/Q12 telemetry is available while
    // pre/post full residual-variance sample counts intentionally remain zero. This must not be
    // mislabeled as missing visible-chroma execution telemetry.
    auto residentCompact = input;
    residentCompact.visibleInputResidualSampleCount = 0u;
    residentCompact.visibleOutputResidualSampleCount = 0u;
    residentCompact.visibleOutputVarianceRG = 0.0;
    residentCompact.visibleOutputVarianceBG = 0.0;
    const auto residentCompactSummary =
            bncam::validation::summarizeObjectiveValidation(residentCompact);
    require(residentCompactSummary.visibleChromaMeasurementReady,
            "resident compact GPU visible-chroma telemetry must be ready");
    require(!residentCompactSummary.visibleChromaVarianceComparisonReady,
            "missing pre/post residual samples must keep variance comparison unavailable");
    require(!residentCompactSummary.visibleChromaSigmaRatioAvailable,
            "unavailable variance comparison must not fabricate a sigma ratio");
    require(residentCompactSummary.visibleChromaSigmaRatio == 0.0,
            "unavailable sigma ratio must remain the explicit zero sentinel");
    require(residentCompactSummary.falseColorRiskTelemetryReady,
            "existing demosaic chroma-cloud risk evidence must remain available");
    require(residentCompactSummary.telemetryStatus ==
                    "OBJECTIVE_TELEMETRY_READY_VISIBLE_CHROMA_VARIANCE_COMPARISON_UNAVAILABLE_IMAGE_REVIEW_REQUIRED",
            "resident compact telemetry status must describe the missing variance comparison exactly");

    auto invalid = input;
    invalid.finalMeanG = std::numeric_limits<double>::quiet_NaN();
    invalid.measuredResidualSampleCount = 0u;
    const auto invalidSummary = bncam::validation::summarizeObjectiveValidation(invalid);
    require(!invalidSummary.coreTelemetryReady, "NaN final stats must reject core readiness");
    require(!invalidSummary.residualMeasurementReady, "missing residual samples must remain unavailable");
    require(invalidSummary.telemetryStatus == "CORE_TELEMETRY_INCOMPLETE",
            "invalid telemetry must not be mislabeled ready");

    std::cout << "PASS route=" << summary.route
              << " isoBand=" << summary.isoBand
              << " measuredLumaSigma=" << summary.measuredFinalLumaSigma
              << " visibleChromaSigmaRatio=" << summary.visibleChromaSigmaRatio
              << " imageReviewRequired=" << (summary.imageReviewRequired ? "true" : "false")
              << '\n';
    return 0;
}
