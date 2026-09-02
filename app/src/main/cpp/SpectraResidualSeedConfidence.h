#pragma once

#include <algorithm>
#include <cmath>

namespace bncam::spectra2 {

struct ResidualSeedConfidencePlan {
    bool sufficientChannels = false;
    bool varianceReady = false;
    bool physicalFallbackActive = false;
    double channelCoverage = 0.0;
    double confidence = 0.0;
    const char* status = "PARTIAL_CHANNEL_FALLBACK";
    const char* method = "CAMERA2_SO_SCALED_BY_MEASURED_RESIDUAL_REDUCTION_LENS_SHADING_FALLBACK";
};

inline bool residualSeedVarianceReady(
        double redVariance,
        double greenVariance,
        double blueVariance) noexcept {
    return std::isfinite(redVariance) && redVariance > 0.0 &&
           std::isfinite(greenVariance) && greenVariance > 0.0 &&
           std::isfinite(blueVariance) && blueVariance > 0.0;
}

/**
 * Resolve confidence for the propagated post-CFA physical covariance seed.
 *
 * There are two legitimate seed routes:
 * 1. final visible per-channel variance provenance (lens-shading aware), or
 * 2. a direct Camera2 S/O signal-model fallback built from capture-local channel means.
 *
 * The second route is important for the resident SPECTRA-Off physical baseline. That route can
 * intentionally skip diagnostic provenance while still having four valid Camera2 S/O channel
 * variances and a validated SingleFrameRawDenoise physical model. Diagnostic provenance absence
 * must not turn a numerically valid physical covariance into zero-confidence data.
 *
 * The fallback is fail-closed: it can recover physical confidence only when all RGB variance
 * components are finite/positive, at least three CFA noise channels contributed, the physical
 * single-frame baseline is active, and both the capture signal model and baseline model have
 * independently reached their normal 0.25 confidence floor.
 */
inline ResidualSeedConfidencePlan resolveResidualSeedConfidence(
        bool finalVisibleVarianceReady,
        int validNoiseChannels,
        double provenanceConfidence,
        bool physicalRawDenoiseActive,
        double physicalBaselineConfidence,
        double signalModelConfidence,
        double redVariance,
        double greenVariance,
        double blueVariance) noexcept {
    ResidualSeedConfidencePlan plan{};

    const int channels = std::clamp(validNoiseChannels, 0, 4);
    const double provenance = std::clamp(
            std::isfinite(provenanceConfidence) ? provenanceConfidence : 0.0,
            0.0,
            1.0);
    const double physical = std::clamp(
            std::isfinite(physicalBaselineConfidence) ? physicalBaselineConfidence : 0.0,
            0.0,
            1.0);
    const double signal = std::clamp(
            std::isfinite(signalModelConfidence) ? signalModelConfidence : 0.0,
            0.0,
            1.0);

    plan.varianceReady = residualSeedVarianceReady(
            redVariance, greenVariance, blueVariance);
    plan.sufficientChannels = finalVisibleVarianceReady || channels >= 3;
    plan.channelCoverage = finalVisibleVarianceReady
            ? 1.0
            : static_cast<double>(channels) / 4.0;

    if (!plan.sufficientChannels || !plan.varianceReady) {
        plan.channelCoverage = plan.sufficientChannels ? plan.channelCoverage : 0.0;
        plan.confidence = 0.0;
        plan.status = !plan.sufficientChannels
                ? "PARTIAL_CHANNEL_FALLBACK"
                : "INVALID_RGB_VARIANCE_FALLBACK";
        return plan;
    }

    if (finalVisibleVarianceReady) {
        const double validatedPhysical = physicalRawDenoiseActive
                ? std::min(physical, signal)
                : 0.0;
        plan.confidence = std::clamp(
                std::max(provenance, validatedPhysical) * 0.90,
                0.0,
                1.0);
        plan.status = "PROPAGATION_SEED_READY";
        plan.method = "CAMERA2_SO_LENS_SHADING_FIELD_SCALED_BY_MEASURED_RESIDUAL_REDUCTION";
        return plan;
    }

    const bool validatedPhysicalFallback =
            physicalRawDenoiseActive &&
            channels >= 3 &&
            physical >= 0.25 &&
            signal >= 0.25;
    plan.physicalFallbackActive = validatedPhysicalFallback;
    const double validatedPhysical = validatedPhysicalFallback
            ? std::min(physical, signal)
            : 0.0;
    const double seedConfidence = std::max(provenance, validatedPhysical);
    plan.confidence = std::clamp(
            seedConfidence * plan.channelCoverage * 0.70,
            0.0,
            1.0);
    plan.status = validatedPhysicalFallback
            ? "PROPAGATION_SEED_PHYSICAL_SO_FALLBACK_READY"
            : "PROPAGATION_SEED_LENS_SHADING_FALLBACK";
    plan.method = validatedPhysicalFallback
            ? "CAMERA2_SO_SIGNAL_MODEL_SCALED_BY_MEASURED_RESIDUAL_REDUCTION_PHYSICAL_FALLBACK"
            : "CAMERA2_SO_SCALED_BY_MEASURED_RESIDUAL_REDUCTION_LENS_SHADING_FALLBACK";
    return plan;
}

}  // namespace bncam::spectra2
