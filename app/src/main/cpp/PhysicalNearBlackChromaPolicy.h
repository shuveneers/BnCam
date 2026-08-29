#pragma once

#include <algorithm>
#include <array>
#include <cmath>

namespace bncam::near_black_chroma {

struct Inputs {
    bool isRawBayer = false;
    bool physicalNoiseModelAvailable = false;
    bool preToneChromaBaselineEnabled = false;
    float preToneChromaBaselineStrength = 0.0f;
    std::array<double, 9> rgbCovariance{};
    float referenceSignal = 0.10f;
    float shotNoiseFraction = 0.50f;
    float modelConfidence = 0.0f;
};

struct Plan {
    bool enabled = false;
    bool covarianceValid = false;
    float varianceY = 0.0f;
    float varianceC1 = 0.0f;
    float varianceC2 = 0.0f;
    float covarianceC1C2 = 0.0f;
    float referenceSignal = 0.10f;
    float shotNoiseFraction = 0.50f;
    float modelConfidence = 0.0f;
    float baselineStrength = 0.0f;
    // Firm-shrink thresholds in whitened chroma-sigma units. Values below the first
    // threshold are statistically noise-like; values above the second are preserved.
    float fullShrinkSigma = 1.10f;
    float preserveSigma = 4.50f;
    // Near-black is defined by physical luma SNR, never an absolute display value.
    float nearBlackFullSnr = 3.0f;
    float nearBlackReleaseSnr = 12.0f;
    float maximumNearBlackAuthority = 0.985f;
};

inline double quadraticForm(
        const std::array<double, 9>& covariance,
        const std::array<double, 3>& a,
        const std::array<double, 3>& b) noexcept {
    double out = 0.0;
    for (int row = 0; row < 3; ++row) {
        for (int col = 0; col < 3; ++col) {
            out += a[static_cast<std::size_t>(row)] *
                    covariance[static_cast<std::size_t>(row * 3 + col)] *
                    b[static_cast<std::size_t>(col)];
        }
    }
    return out;
}

inline Plan resolve(const Inputs& input) noexcept {
    Plan plan{};
    plan.referenceSignal = std::clamp(
            std::isfinite(input.referenceSignal) ? input.referenceSignal : 0.10f,
            1.0e-4f, 2.0f);
    plan.shotNoiseFraction = std::clamp(
            std::isfinite(input.shotNoiseFraction) ? input.shotNoiseFraction : 0.50f,
            0.0f, 1.0f);
    plan.modelConfidence = std::clamp(
            std::isfinite(input.modelConfidence) ? input.modelConfidence : 0.0f,
            0.0f, 1.0f);
    plan.baselineStrength = std::clamp(
            std::isfinite(input.preToneChromaBaselineStrength)
                    ? input.preToneChromaBaselineStrength : 0.0f,
            0.0f, 0.94f);

    if (!input.isRawBayer || !input.physicalNoiseModelAvailable ||
        !input.preToneChromaBaselineEnabled || plan.baselineStrength <= 1.0e-4f ||
        plan.modelConfidence < 0.10f) {
        return plan;
    }

    constexpr std::array<double, 3> kY{0.2126, 0.7152, 0.0722};
    // C1 = R-Y, C2 = B-Y. This is the same luma/chrominance basis used by the
    // resident pre-tone filter, so the propagated RGB covariance is whitened in the
    // exact coordinates where shrinkage occurs.
    constexpr std::array<double, 3> kC1{0.7874, -0.7152, -0.0722};
    constexpr std::array<double, 3> kC2{-0.2126, -0.7152, 0.9278};

    const double varY = quadraticForm(input.rgbCovariance, kY, kY);
    const double varC1 = quadraticForm(input.rgbCovariance, kC1, kC1);
    const double varC2 = quadraticForm(input.rgbCovariance, kC2, kC2);
    double covC1C2 = quadraticForm(input.rgbCovariance, kC1, kC2);

    if (!std::isfinite(varY) || !std::isfinite(varC1) || !std::isfinite(varC2) ||
        !std::isfinite(covC1C2) || varY <= 1.0e-14 || varC1 <= 1.0e-14 ||
        varC2 <= 1.0e-14) {
        return plan;
    }

    const double maximumCovariance = 0.98 * std::sqrt(varC1 * varC2);
    covC1C2 = std::clamp(covC1C2, -maximumCovariance, maximumCovariance);
    const double determinant = varC1 * varC2 - covC1C2 * covC1C2;
    if (!std::isfinite(determinant) || determinant <= 1.0e-24) {
        return plan;
    }

    plan.varianceY = static_cast<float>(varY);
    plan.varianceC1 = static_cast<float>(varC1);
    plan.varianceC2 = static_cast<float>(varC2);
    plan.covarianceC1C2 = static_cast<float>(covC1C2);
    plan.covarianceValid = true;
    plan.enabled = true;
    return plan;
}

inline float varianceScaleForSignal(const Plan& plan, float luma) noexcept {
    const float safeLuma = std::max(0.0f, std::isfinite(luma) ? luma : 0.0f);
    const float signalRatio = std::clamp(
            safeLuma / std::max(plan.referenceSignal, 1.0e-4f), 0.0f, 8.0f);
    return std::max(
            1.0e-4f,
            (1.0f - plan.shotNoiseFraction) + plan.shotNoiseFraction * signalRatio);
}

inline float nearBlackPressure(const Plan& plan, float luma) noexcept {
    const float scale = varianceScaleForSignal(plan, luma);
    const float sigmaY = std::sqrt(std::max(1.0e-14f, plan.varianceY * scale));
    const float snr = std::max(0.0f, luma) / std::max(1.0e-7f, sigmaY);
    const float t = std::clamp(
            (snr - plan.nearBlackFullSnr) /
                    std::max(1.0e-4f, plan.nearBlackReleaseSnr - plan.nearBlackFullSnr),
            0.0f, 1.0f);
    const float smooth = t * t * (3.0f - 2.0f * t);
    return 1.0f - smooth;
}

inline float mahalanobisRadius(const Plan& plan, float luma, float c1, float c2) noexcept {
    const float scale = varianceScaleForSignal(plan, luma);
    const double v1 = std::max(1.0e-14, static_cast<double>(plan.varianceC1) * scale);
    const double v2 = std::max(1.0e-14, static_cast<double>(plan.varianceC2) * scale);
    const double c = std::clamp(
            static_cast<double>(plan.covarianceC1C2) * scale,
            -0.98 * std::sqrt(v1 * v2),
            0.98 * std::sqrt(v1 * v2));
    const double determinant = std::max(1.0e-24, v1 * v2 - c * c);
    const double x = static_cast<double>(c1);
    const double y = static_cast<double>(c2);
    const double q2 = (x * x * v2 - 2.0 * x * y * c + y * y * v1) / determinant;
    if (!std::isfinite(q2)) return 1.0e6f;
    return static_cast<float>(std::sqrt(std::max(0.0, q2)));
}

inline float residualKeep(const Plan& plan, float whitenedRadius) noexcept {
    const float low = plan.fullShrinkSigma;
    const float high = std::max(low + 0.25f, plan.preserveSigma);
    const float t = std::clamp((whitenedRadius - low) / (high - low), 0.0f, 1.0f);
    return t * t * (3.0f - 2.0f * t);
}

}  // namespace bncam::near_black_chroma
