#pragma once

#include <algorithm>
#include <cmath>

namespace bncam::raw_noise {

/**
 * P2 physical spatial noise-domain contract.
 *
 * Camera2 SENSOR_NOISE_PROFILE describes variance in the normalized sensor/Bayer
 * domain: V(x) = S*x + O. BnCam performs physical pre-demosaic denoise before
 * lens-shading multiplication, so that stage must use this unscaled variance.
 *
 * Downstream visible-noise prediction occurs after multiplicative lens shading.
 * For y = g*x, Var(y) = g^2 Var(x). This helper intentionally keeps the two
 * domains separate to prevent double application of lens gain.
 */
inline double sensorVariance(
        double signal,
        double slopeS,
        double offsetO) noexcept {
    if (!std::isfinite(signal) || !std::isfinite(slopeS) || !std::isfinite(offsetO) ||
        slopeS < 0.0 || offsetO < 0.0) {
        return 0.0;
    }
    return std::max(0.0, slopeS * std::clamp(signal, 0.0, 1.0) + offsetO);
}

inline double visibleVarianceAfterMultiplicativeGain(
        double sensorVarianceValue,
        double gain) noexcept {
    if (!std::isfinite(sensorVarianceValue) || sensorVarianceValue < 0.0 ||
        !std::isfinite(gain) || gain <= 0.0) {
        return std::max(0.0, std::isfinite(sensorVarianceValue) ? sensorVarianceValue : 0.0);
    }
    return sensorVarianceValue * gain * gain;
}

inline double visibleSigmaAfterMultiplicativeGain(
        double sensorVarianceValue,
        double gain) noexcept {
    return std::sqrt(std::max(
        0.0,
        visibleVarianceAfterMultiplicativeGain(sensorVarianceValue, gain)));
}

} // namespace bncam::raw_noise
