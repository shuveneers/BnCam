#pragma once

#include <algorithm>
#include <array>
#include <cmath>
#include <cstddef>
#include <string>

#include "SpectraNoisePropagation.h"

namespace bncam::spectra2 {

struct DownstreamSharpenPlan {
    bool spectraAware = false;
    bool enabled = false;
    bool localContrastEnabled = false;
    std::string status = "UNINITIALIZED";
    std::string method = "MILESTONE_7_NOISE_AWARE_LUMA_UNSHARP";
    std::string residualSource = "UNAVAILABLE";
    double modelConfidence = 0.0;
    double predictedSigmaY = 0.0;
    double measuredSigmaY = 0.0;
    double sigmaY = 0.0;
    double downstreamLumaAuthority = 1.0;
    double detailProtection = 0.0;
    double globalAuthority = 1.0;
    double baseAmount = 0.0;
    double maximumAmount = 0.0;
    double localContrastAuthority = 0.0;
    double minimumEdgeSnr = 2.5;
    double fullEdgeSnr = 7.0;
    double haloProtection = 0.0;
    double maximumPredictedVarianceGain = 1.08;
};

struct DownstreamPixelDecision {
    double edgeSnr = 0.0;
    double edgeConfidence = 0.0;
    double textureConfidence = 0.0;
    double haloGuard = 0.0;
    double localAuthority = 0.0;
    double localContrastAuthority = 0.0;
    bool noiseRejected = false;
    bool haloProtected = false;
};

inline double smoothstepDownstream(double edge0, double edge1, double value) {
    if (!(edge1 > edge0) || !std::isfinite(value)) return 0.0;
    const double x = std::clamp((value - edge0) / (edge1 - edge0), 0.0, 1.0);
    return x * x * (3.0 - 2.0 * x);
}

inline double safeSigma(double variance) {
    return std::sqrt(std::max(0.0, std::isfinite(variance) ? variance : 0.0));
}

inline double unsharpVarianceGain(double amount,
                                  double blurCenterWeight = 0.22,
                                  double blurKernelEnergy = 0.12) {
    const double a = std::max(0.0, std::isfinite(amount) ? amount : 0.0);
    const double center = std::clamp(blurCenterWeight, 0.0, 1.0);
    const double energy = std::clamp(blurKernelEnergy, 0.0, 1.0);
    const double gain = (1.0 + a) * (1.0 + a) + a * a * energy -
            2.0 * a * (1.0 + a) * center;
    return std::max(1.0, std::isfinite(gain) ? gain : 1.0);
}

inline double maximumAmountForVarianceGain(double requestedAmount, double maximumGain) {
    const double requested = std::clamp(
            std::isfinite(requestedAmount) ? requestedAmount : 0.0,
            0.0,
            0.30
    );
    const double limit = std::clamp(
            std::isfinite(maximumGain) ? maximumGain : 1.0,
            1.0,
            1.30
    );
    if (unsharpVarianceGain(requested) <= limit) return requested;
    double low = 0.0;
    double high = requested;
    for (int iteration = 0; iteration < 32; ++iteration) {
        const double middle = 0.5 * (low + high);
        if (unsharpVarianceGain(middle) <= limit) low = middle;
        else high = middle;
    }
    return low;
}

inline DownstreamSharpenPlan resolveDownstreamSharpenPlan(
        bool spectraActive,
        const NoiseState& residual,
        double measuredVarianceY,
        double downstreamLumaAuthority,
        double detailProtection,
        double baseAmount
) {
    DownstreamSharpenPlan plan{};
    plan.spectraAware = spectraActive;
    plan.baseAmount = std::clamp(
            std::isfinite(baseAmount) ? baseAmount : 0.0,
            0.0,
            0.30
    );
    plan.detailProtection = std::clamp(
            std::isfinite(detailProtection) ? detailProtection : 0.0,
            -1.0,
            1.0
    );
    plan.downstreamLumaAuthority = std::clamp(
            std::isfinite(downstreamLumaAuthority) ? downstreamLumaAuthority : 1.0,
            0.0,
            1.0
    );
    plan.modelConfidence = std::clamp(
            std::isfinite(residual.confidence) ? residual.confidence : 0.0,
            0.0,
            1.0
    );
    plan.predictedSigmaY = safeSigma(residual.varianceY);
    plan.measuredSigmaY = safeSigma(measuredVarianceY);

    if (!spectraActive) {
        plan.status = "LEGACY_SHARPENING_PRESERVED_SPECTRA_OFF";
        plan.residualSource = "LEGACY_ISO_EXPOSURE_POLICY";
        plan.enabled = plan.baseAmount > 1.0e-6;
        plan.maximumAmount = plan.baseAmount;
        plan.globalAuthority = 1.0;
        plan.localContrastAuthority = 0.0;
        plan.maximumPredictedVarianceGain = unsharpVarianceGain(plan.maximumAmount);
        return plan;
    }

    const bool predictedReady = plan.predictedSigmaY > 1.0e-7 && plan.modelConfidence >= 0.20;
    const bool measuredReady = plan.measuredSigmaY > 1.0e-7;
    if (predictedReady && measuredReady) {
        // The flat-region measurement contains scene texture and quantisation. Use it only as a
        // bounded upper-support term rather than replacing the propagated physical model.
        plan.sigmaY = std::max(
                plan.predictedSigmaY,
                std::min(plan.measuredSigmaY, plan.predictedSigmaY * 2.5 + 0.0015)
        );
        plan.residualSource = "PROPAGATED_PLUS_BOUNDED_PRE_SHARPEN_MEASUREMENT";
    } else if (predictedReady) {
        plan.sigmaY = plan.predictedSigmaY;
        plan.residualSource = "PROPAGATED_POST_VISIBLE_CHROMA_VARIANCE";
    } else if (measuredReady) {
        plan.sigmaY = plan.measuredSigmaY;
        plan.modelConfidence = std::max(plan.modelConfidence, 0.45);
        plan.residualSource = "MEASURED_PRE_SHARPEN_FALLBACK";
    } else {
        plan.sigmaY = 0.012;
        plan.modelConfidence = 0.25;
        plan.residualSource = "CONSERVATIVE_SIGMA_FALLBACK";
    }

    const double noisePressure = smoothstepDownstream(0.0035, 0.028, plan.sigmaY);
    const double confidenceAuthority = 0.45 + 0.55 * plan.modelConfidence;
    // downstreamLumaAuthority describes how much residual cleanup authority remains. It must
    // therefore suppress sharpening when high, not be mistaken for sharpening permission.
    const double residualCleanlinessAuthority = std::clamp(
            1.15 - 0.80 * plan.downstreamLumaAuthority,
            0.30,
            0.90
    );
    const double detailPolicy = plan.detailProtection >= 0.0
            ? (1.0 - 0.18 * plan.detailProtection)
            : (1.0 + 0.12 * -plan.detailProtection);
    plan.globalAuthority = std::clamp(
            (1.0 - 0.72 * noisePressure) * confidenceAuthority * residualCleanlinessAuthority * detailPolicy,
            0.12,
            1.0
    );

    // Permit only a small theoretical noise-variance increase in noisy regions. True edges may
    // receive the full bounded amount through the local SNR gate, while flat noise is rejected.
    plan.maximumPredictedVarianceGain = 1.025 + 0.055 * (1.0 - noisePressure);
    const double requested = plan.baseAmount * plan.globalAuthority;
    plan.maximumAmount = maximumAmountForVarianceGain(
            requested,
            plan.maximumPredictedVarianceGain
    );
    plan.minimumEdgeSnr = 2.6 + 2.4 * noisePressure + 0.4 * std::max(0.0, plan.detailProtection);
    plan.fullEdgeSnr = plan.minimumEdgeSnr + 4.0 + 1.5 * noisePressure;
    plan.haloProtection = std::clamp(0.55 + 0.35 * noisePressure +
            0.10 * std::max(0.0, plan.detailProtection), 0.0, 1.0);
    plan.localContrastAuthority = std::clamp(
            0.032 * (1.0 - noisePressure) * plan.modelConfidence *
                    plan.downstreamLumaAuthority,
            0.0,
            0.032
    );
    plan.localContrastEnabled = plan.localContrastAuthority >= 0.004 && plan.sigmaY <= 0.018;
    plan.enabled = plan.maximumAmount >= 0.002;
    plan.status = plan.enabled
            ? "MILESTONE_7_NOISE_AWARE_SHARPEN_ENABLED"
            : "MILESTONE_7_RESIDUAL_NOISE_SUPPRESSED_SHARPEN";
    return plan;
}

inline DownstreamPixelDecision resolveDownstreamPixelDecision(
        const DownstreamSharpenPlan& plan,
        double detail,
        double macroDetail,
        double luma
) {
    DownstreamPixelDecision decision{};
    const double sigma = std::max(plan.sigmaY, 1.0 / 1020.0);
    const double absoluteDetail = std::abs(std::isfinite(detail) ? detail : 0.0);
    decision.edgeSnr = absoluteDetail / sigma;
    decision.edgeConfidence = smoothstepDownstream(
            plan.minimumEdgeSnr,
            plan.fullEdgeSnr,
            decision.edgeSnr
    );
    decision.textureConfidence = 1.0 - smoothstepDownstream(0.045, 0.115, absoluteDetail);
    const double highlightGuard = 1.0 - smoothstepDownstream(0.72, 0.94, luma);
    const double shadowGuard = smoothstepDownstream(0.025, 0.12, luma);
    decision.haloGuard = (1.0 - smoothstepDownstream(
            0.035 + 0.020 * (1.0 - plan.haloProtection),
            0.090 + 0.020 * (1.0 - plan.haloProtection),
            absoluteDetail
    ));
    decision.localAuthority = std::clamp(
            plan.maximumAmount * decision.edgeConfidence * decision.textureConfidence *
                    decision.haloGuard * highlightGuard * shadowGuard,
            0.0,
            plan.maximumAmount
    );

    const double macroSnr = std::abs(std::isfinite(macroDetail) ? macroDetail : 0.0) /
            std::max(2.2 * sigma, 1.0 / 510.0);
    const double macroConfidence = smoothstepDownstream(2.8, 6.5, macroSnr);
    decision.localContrastAuthority = plan.localContrastEnabled
            ? std::clamp(
                    plan.localContrastAuthority * macroConfidence * decision.haloGuard *
                            highlightGuard * shadowGuard,
                    0.0,
                    plan.localContrastAuthority
            )
            : 0.0;
    decision.noiseRejected = decision.edgeSnr < plan.minimumEdgeSnr;
    decision.haloProtected = decision.haloGuard < 0.35;
    return decision;
}

inline NoiseState propagateQuantization8Bit(
        const NoiseState& input,
        std::string stage = "POST_QUANTIZATION_8BIT"
) {
    Covariance3 covariance = input.covariance;
    constexpr double quantizationVariance = 1.0 / (12.0 * 255.0 * 255.0);
    covariance.at(0, 0) += quantizationVariance;
    covariance.at(1, 1) += quantizationVariance;
    covariance.at(2, 2) += quantizationVariance;
    return makeState(
            std::move(stage),
            "UNIFORM_8BIT_QUANTIZATION_VARIANCE_ADDED",
            "PROPAGATED",
            input.confidence * 0.98,
            covariance
    );
}

inline NoiseState propagateNoiseAwareSharpen(
        const NoiseState& input,
        double measuredVarianceGainY,
        double predictedVarianceGainY,
        bool measuredReady,
        std::string stage = "FINAL_JPEG_PRE_ENCODE"
) {
    const double predicted = std::clamp(
            std::isfinite(predictedVarianceGainY) ? predictedVarianceGainY : 1.0,
            1.0,
            1.30
    );
    const double measured = std::clamp(
            std::isfinite(measuredVarianceGainY) ? measuredVarianceGainY : predicted,
            1.0,
            1.30
    );
    // Spatial residual measurement can include scene differences. Blend it with the analytical
    // unsharp bound rather than treating one image as exact calibration.
    const double varianceGain = measuredReady
            ? std::clamp(0.65 * measured + 0.35 * predicted, 1.0, 1.30)
            : predicted;
    return propagateOpponentGains(
            input,
            std::sqrt(varianceGain),
            1.0,
            1.0,
            std::move(stage),
            measuredReady
                    ? "MEASURED_AND_ANALYTICAL_NOISE_AWARE_LUMA_SHARPEN_GAIN"
                    : "ANALYTICAL_BOUNDED_NOISE_AWARE_LUMA_SHARPEN_GAIN",
            measuredReady ? 0.94 : 0.82
    );
}

} // namespace bncam::spectra2
