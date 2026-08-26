#pragma once

#include <algorithm>
#include <cmath>

namespace bncam::raw_defect {

inline float predictedSigma(float signal, float slopeS, float offsetO) noexcept {
    if (!std::isfinite(signal) || !std::isfinite(slopeS) || !std::isfinite(offsetO) ||
        slopeS < 0.0f || offsetO < 0.0f) {
        return 0.0f;
    }
    const float variance = slopeS * std::clamp(signal, 0.0f, 1.0f) + offsetO;
    return std::sqrt(std::max(0.0f, variance));
}

inline float preScanThreshold(
        float baseThreshold,
        float neighbourMin,
        float neighbourMax,
        float signal,
        float slopeS,
        float offsetO,
        bool noiseModelValid) noexcept {
    const float spread = std::max(0.006f, neighbourMax - neighbourMin);
    const float noiseFloor = noiseModelValid
            ? 5.0f * predictedSigma(signal, slopeS, offsetO)
            : 0.0f;
    return std::max({baseThreshold, 3.0f * spread, noiseFloor});
}

inline float finalThreshold(
        float baseThreshold,
        float robustSpread,
        float signal,
        float slopeS,
        float offsetO,
        bool noiseModelValid) noexcept {
    const float spread = std::max(0.006f, robustSpread);
    const float noiseFloor = noiseModelValid
            ? 6.0f * predictedSigma(signal, slopeS, offsetO)
            : 0.0f;
    return std::max({baseThreshold, 4.0f * spread, noiseFloor});
}

} // namespace bncam::raw_defect
