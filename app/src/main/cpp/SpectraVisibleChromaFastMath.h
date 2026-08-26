#pragma once

#include <algorithm>
#include <array>
#include <cmath>
#include <cstddef>
#include <limits>

namespace bncam::spectra2 {

/**
 * Milestone 8H visible-chroma hot-loop math.
 *
 * The production 5x5 covariance-guided filter evaluates two Gaussian weights for
 * every neighbour. At full sensor resolution that means hundreds of millions of
 * libm exp() calls per capture. This bounded lookup preserves the original
 * exp(-0.5*x) function with linear interpolation while moving all exp() work to
 * one process-wide initialization.
 */
inline constexpr std::size_t kVisibleGaussianWeightLutIntervals = 16384u;
inline constexpr float kVisibleGaussianWeightMaximumSquaredDistance = 32.0f;
inline constexpr float kVisibleGaussianWeightLutMaximumAbsoluteError = 2.0e-7f;

inline const std::array<float, kVisibleGaussianWeightLutIntervals + 1u>&
visibleGaussianWeightLut() {
    static const std::array<float, kVisibleGaussianWeightLutIntervals + 1u> values = [] {
        std::array<float, kVisibleGaussianWeightLutIntervals + 1u> table{};
        constexpr float step = kVisibleGaussianWeightMaximumSquaredDistance /
                static_cast<float>(kVisibleGaussianWeightLutIntervals);
        for (std::size_t index = 0; index <= kVisibleGaussianWeightLutIntervals; ++index) {
            const float squaredDistance = static_cast<float>(index) * step;
            table[index] = std::exp(-0.5f * squaredDistance);
        }
        return table;
    }();
    return values;
}

inline float fastVisibleGaussianWeight(float squaredDistance) noexcept {
    if (!std::isfinite(squaredDistance)) return 0.0f;
    if (squaredDistance <= 0.0f) return 1.0f;
    if (squaredDistance >= kVisibleGaussianWeightMaximumSquaredDistance) {
        // exp(-16) is already below 1.13e-7, far below the 0.035 support gate.
        return 0.0f;
    }

    constexpr float scale = static_cast<float>(kVisibleGaussianWeightLutIntervals) /
            kVisibleGaussianWeightMaximumSquaredDistance;
    const float position = squaredDistance * scale;
    const std::size_t lower = static_cast<std::size_t>(position);
    const float fraction = position - static_cast<float>(lower);
    const auto& table = visibleGaussianWeightLut();
    return table[lower] + (table[lower + 1u] - table[lower]) * fraction;
}

struct VisibleNeighbourOffset {
    int dx = 0;
    int dy = 0;
    float spatialWeight = 0.0f;
};

/** Center sample is intentionally omitted and hoisted into the accumulator. */
inline constexpr std::array<VisibleNeighbourOffset, 24u> kVisibleNeighbourOffsets{{
        {-2, -2, 0.08f}, {-1, -2, 0.16f}, {0, -2, 0.22f}, {1, -2, 0.16f}, {2, -2, 0.08f},
        {-2, -1, 0.16f}, {-1, -1, 0.38f}, {0, -1, 0.62f}, {1, -1, 0.38f}, {2, -1, 0.16f},
        {-2,  0, 0.22f}, {-1,  0, 0.62f},                         {1,  0, 0.62f}, {2,  0, 0.22f},
        {-2,  1, 0.16f}, {-1,  1, 0.38f}, {0,  1, 0.62f}, {1,  1, 0.38f}, {2,  1, 0.16f},
        {-2,  2, 0.08f}, {-1,  2, 0.16f}, {0,  2, 0.22f}, {1,  2, 0.16f}, {2,  2, 0.08f}
}};

} // namespace bncam::spectra2
