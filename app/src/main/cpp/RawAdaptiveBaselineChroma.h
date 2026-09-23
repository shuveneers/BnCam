#pragma once

#include <algorithm>
#include <cmath>
#include <string>

namespace bncam::raw_baseline {

/**
 * Sensor-role agnostic baseline chroma plan for developed RAW.
 *
 * This is not a user/SPECTRA denoise control. It consumes the already-frozen physical S/O
 * covariance plus the compact post-demosaic residual observer and downstream WB/CCM amplification.
 * It never identifies a lens role and never grants luma filtering authority.
 */
struct AdaptiveChromaDirectionInput {
    double predictedVariance = 0.0;
    double measuredVariance = 0.0;
    double downstreamOpponentGain = 1.0;
};

struct AdaptiveChromaPlanInput {
    bool physicalNoiseModelAvailable = false;
    bool residualMeasurementAvailable = false;
    double modelConfidence = 0.0;
    double predictedLumaVariance = 0.0;
    AdaptiveChromaDirectionInput redGreen{};
    AdaptiveChromaDirectionInput blueGreen{};
};

struct AdaptiveChromaPlan {
    bool ready = false;
    float blendRedGreen = 0.0f;
    float blendBlueGreen = 0.0f;
    float sigmaRedGreen = 0.0f;
    float sigmaBlueGreen = 0.0f;
    float sigmaLuma = 0.0f;
    float confidence = 0.0f;
    float riskRedGreen = 0.0f;
    float riskBlueGreen = 0.0f;
    float measuredToPredictedRmsRedGreen = -1.0f;
    float measuredToPredictedRmsBlueGreen = -1.0f;
    const char* reason = "NO_PHYSICAL_NOISE_MODEL";
};

inline double smoothstep(double edge0, double edge1, double value) noexcept {
    if (!(edge1 > edge0) || !std::isfinite(value)) return 0.0;
    const double u = std::clamp((value - edge0) / (edge1 - edge0), 0.0, 1.0);
    return u * u * (3.0 - 2.0 * u);
}

inline double safeSigma(double variance) noexcept {
    return std::sqrt(std::max(0.0, std::isfinite(variance) ? variance : 0.0));
}

inline double safeRmsRatio(double measuredVariance, double predictedVariance) noexcept {
    if (!(predictedVariance > 1.0e-14) || !std::isfinite(measuredVariance) ||
        !std::isfinite(predictedVariance) || measuredVariance < 0.0) {
        return -1.0;
    }
    return std::sqrt(std::max(0.0, measuredVariance) / predictedVariance);
}

inline double measurementAgreement(double rmsRatio) noexcept {
    if (!(rmsRatio > 0.0) || !std::isfinite(rmsRatio)) return 0.82;
    const double logDistance = std::abs(std::log2(std::clamp(rmsRatio, 1.0 / 16.0, 16.0)));
    // A compact residual scan is allowed to calibrate the physical prediction, but scene
    // structure must never be mistaken for permission to blur. Large disagreement therefore
    // lowers authority instead of increasing it.
    return 1.0 - 0.65 * smoothstep(0.65, 1.70, logDistance);
}

inline double calibratedPreWbSigma(
        const AdaptiveChromaDirectionInput& in,
        bool measurementAvailable) noexcept {
    const double predicted = safeSigma(in.predictedVariance);
    if (!(predicted > 0.0)) return 0.0;
    if (!measurementAvailable) return predicted;
    const double ratio = safeRmsRatio(in.measuredVariance, in.predictedVariance);
    if (!(ratio > 0.0)) return predicted;
    // The observer may contain real scene chroma. It can trim or modestly raise the physical
    // estimate, but cannot turn arbitrary texture into a giant denoise sigma.
    return predicted * std::clamp(ratio, 0.72, 1.55);
}

inline float directionBlend(
        double preWbSigma,
        double downstreamGain,
        double confidence,
        double agreement,
        float* riskOut) noexcept {
    const double gain = std::clamp(
            std::isfinite(downstreamGain) ? downstreamGain : 1.0,
            0.25,
            8.0);
    const double visibleSigma = preWbSigma * gain;

    // Below roughly 1.8% scene-linear opponent RMS the baseline stays effectively transparent.
    // No lens identity participates: a clean main sensor naturally falls below this pressure,
    // while a noisy/strongly-amplified RAW route gains authority continuously.
    const double visibleNoisePressure = smoothstep(0.018, 0.075, visibleSigma);
    const double amplificationPressure = smoothstep(1.15, 2.80, gain);
    const double risk = std::clamp(
            0.82 * visibleNoisePressure + 0.18 * amplificationPressure,
            0.0,
            1.0);
    if (riskOut != nullptr) *riskOut = static_cast<float>(risk);

    // Maximum global authority is intentionally bounded. The shader adds independent local
    // green-structure, chroma-edge and residual-SNR gates before any pixel is altered.
    const double blend = 0.60 * risk * std::clamp(confidence, 0.0, 1.0) *
            std::clamp(agreement, 0.25, 1.0);
    return static_cast<float>(std::clamp(blend, 0.0, 0.60));
}

inline AdaptiveChromaPlan resolveAdaptiveChromaPlan(
        const AdaptiveChromaPlanInput& input) noexcept {
    AdaptiveChromaPlan out{};
    if (!input.physicalNoiseModelAvailable) {
        out.reason = "NO_PHYSICAL_NOISE_MODEL";
        return out;
    }

    const double confidence = smoothstep(0.10, 0.65, input.modelConfidence);
    out.confidence = static_cast<float>(confidence);
    if (!(confidence > 0.0)) {
        out.reason = "PHYSICAL_NOISE_CONFIDENCE_TOO_LOW";
        return out;
    }

    const double ratioRg = input.residualMeasurementAvailable
            ? safeRmsRatio(input.redGreen.measuredVariance, input.redGreen.predictedVariance)
            : -1.0;
    const double ratioBg = input.residualMeasurementAvailable
            ? safeRmsRatio(input.blueGreen.measuredVariance, input.blueGreen.predictedVariance)
            : -1.0;
    out.measuredToPredictedRmsRedGreen = static_cast<float>(ratioRg);
    out.measuredToPredictedRmsBlueGreen = static_cast<float>(ratioBg);

    out.sigmaRedGreen = static_cast<float>(calibratedPreWbSigma(
            input.redGreen, input.residualMeasurementAvailable));
    out.sigmaBlueGreen = static_cast<float>(calibratedPreWbSigma(
            input.blueGreen, input.residualMeasurementAvailable));
    out.sigmaLuma = static_cast<float>(safeSigma(input.predictedLumaVariance));

    const double agreementRg = measurementAgreement(ratioRg);
    const double agreementBg = measurementAgreement(ratioBg);
    out.blendRedGreen = directionBlend(
            out.sigmaRedGreen,
            input.redGreen.downstreamOpponentGain,
            confidence,
            agreementRg,
            &out.riskRedGreen);
    out.blendBlueGreen = directionBlend(
            out.sigmaBlueGreen,
            input.blueGreen.downstreamOpponentGain,
            confidence,
            agreementBg,
            &out.riskBlueGreen);

    out.ready = (out.blendRedGreen > 1.0e-4f || out.blendBlueGreen > 1.0e-4f) &&
            (out.sigmaRedGreen > 1.0e-7f || out.sigmaBlueGreen > 1.0e-7f);
    out.reason = out.ready
            ? "PHYSICAL_SO_PLUS_RESIDUAL_SCAN_PLUS_WB_CCM_AMPLIFICATION"
            : "NO_VISIBLE_CHROMA_PRESSURE";
    return out;
}

} // namespace bncam::raw_baseline
