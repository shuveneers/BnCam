#pragma once

#include <algorithm>
#include <array>
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <limits>
#include <numeric>
#include <string>
#include <vector>

namespace spectra_temporal {

inline double clampFinite(double value, double lower, double upper, double fallback = 0.0) {
    if (!std::isfinite(value)) return std::clamp(fallback, lower, upper);
    return std::clamp(value, lower, upper);
}

inline double smoothstep01(double value) {
    const double t = clampFinite(value, 0.0, 1.0);
    return t * t * (3.0 - 2.0 * t);
}

inline double percentile(std::vector<double> values, double quantile) {
    if (values.empty()) return 0.0;
    values.erase(
            std::remove_if(values.begin(), values.end(), [](double value) {
                return !std::isfinite(value);
            }),
            values.end()
    );
    if (values.empty()) return 0.0;
    std::sort(values.begin(), values.end());
    const double q = std::clamp(quantile, 0.0, 1.0);
    const double position = q * static_cast<double>(values.size() - 1u);
    const size_t lower = static_cast<size_t>(std::floor(position));
    const size_t upper = static_cast<size_t>(std::ceil(position));
    if (lower == upper) return values[lower];
    const double fraction = position - static_cast<double>(lower);
    return values[lower] * (1.0 - fraction) + values[upper] * fraction;
}

/**
 * Continuous, coarse static-probability field. Values are stored per cell and sampled
 * bilinearly so local fusion authority cannot create hard tile boundaries.
 */
struct StaticProbabilityField {
    int imageWidth = 0;
    int imageHeight = 0;
    int cellSize = 48;
    int columns = 0;
    int rows = 0;
    std::vector<float> values;

    bool valid() const {
        return imageWidth > 0 && imageHeight > 0 && cellSize > 0 && columns > 0 && rows > 0 &&
                values.size() == static_cast<size_t>(columns * rows);
    }

    float sample(int x, int y) const {
        if (!valid()) return 0.0f;
        const double gx = (static_cast<double>(std::clamp(x, 0, imageWidth - 1)) + 0.5) /
                static_cast<double>(cellSize) - 0.5;
        const double gy = (static_cast<double>(std::clamp(y, 0, imageHeight - 1)) + 0.5) /
                static_cast<double>(cellSize) - 0.5;
        const int x0 = std::clamp(static_cast<int>(std::floor(gx)), 0, columns - 1);
        const int y0 = std::clamp(static_cast<int>(std::floor(gy)), 0, rows - 1);
        const int x1 = std::min(columns - 1, x0 + 1);
        const int y1 = std::min(rows - 1, y0 + 1);
        const double tx = std::clamp(gx - static_cast<double>(x0), 0.0, 1.0);
        const double ty = std::clamp(gy - static_cast<double>(y0), 0.0, 1.0);
        const auto at = [&](int cx, int cy) -> double {
            return clampFinite(
                    static_cast<double>(values[static_cast<size_t>(cy * columns + cx)]),
                    0.0,
                    1.0
            );
        };
        const double top = at(x0, y0) * (1.0 - tx) + at(x1, y0) * tx;
        const double bottom = at(x0, y1) * (1.0 - tx) + at(x1, y1) * tx;
        return static_cast<float>(std::clamp(top * (1.0 - ty) + bottom * ty, 0.0, 1.0));
    }
};

/**
 * Normalized-innovation gate used by Temporal Observer 2.0. z is already normalized by
 * sqrt(var(anchor)+var(support)); therefore the function is independent of bit depth.
 */
inline float staticProbability(
        double z,
        double textureConfidence,
        double clippingConfidence,
        double forwardBackwardConsistency,
        double cfaPhaseConfidence = 1.0
) {
    const double safeZ = std::abs(std::isfinite(z) ? z : 1.0e6);
    // Full authority below ~1.5 sigma, smooth decay through 4 sigma, effectively zero above 6.
    const double innovation = std::exp(-0.5 * std::pow(safeZ / 2.35, 2.0));
    const double confidence = smoothstep01(textureConfidence) *
            smoothstep01(clippingConfidence) *
            smoothstep01(forwardBackwardConsistency) *
            smoothstep01(cfaPhaseConfidence);
    return static_cast<float>(std::clamp(innovation * confidence, 0.0, 1.0));
}

inline float forwardBackwardConsistency(
        double forwardShiftX,
        double forwardShiftY,
        double reverseShiftX,
        double reverseShiftY,
        double forwardResponse,
        double reverseResponse,
        double closureSigmaPixels = 0.85
) {
    if (!std::isfinite(forwardShiftX) || !std::isfinite(forwardShiftY) ||
        !std::isfinite(reverseShiftX) || !std::isfinite(reverseShiftY) ||
        !std::isfinite(forwardResponse) || !std::isfinite(reverseResponse)) {
        return 0.0f;
    }
    const double closureError = std::hypot(
            forwardShiftX + reverseShiftX,
            forwardShiftY + reverseShiftY
    );
    // Default remains sub-pixel strict for callers with continuous alignment. RAW Bayer GPU
    // alignment may explicitly supply a wider sigma because its CFA-safe translation search is
    // quantized in 2-pixel steps; a one-quantum closure mismatch is not equivalent to motion.
    const double safeSigma = std::clamp(closureSigmaPixels, 0.25, 8.0);
    const double geometric = std::exp(-0.5 * std::pow(closureError / safeSigma, 2.0));
    const double response = smoothstep01(
            (std::min(forwardResponse, reverseResponse) - 0.025) / 0.225
    );
    return static_cast<float>(std::clamp(geometric * response, 0.0, 1.0));
}

// FASE 6: capture-local S/O fitting and fit-stability learning were removed.
// Temporal fusion may observe residual statistics, but physical S/O is frozen upstream.

inline double correlationAwareVarianceScale(double independentScale, double correlation) {
    const double q = clampFinite(independentScale, 0.0, 1.0, 1.0);
    const double rho = clampFinite(correlation, 0.0, 0.95, 0.0);
    return std::clamp(q + rho * (1.0 - q), q, 1.0);
}

inline double effectiveFrameCount(double varianceScale) {
    return 1.0 / std::max(0.02, clampFinite(varianceScale, 0.02, 1.0, 1.0));
}

inline float localFusionAuthority(
        float staticProbabilityValue,
        float alignmentConfidence,
        float modelConfidence,
        float motionConfidence
) {
    const double staticAuthority = std::pow(
            clampFinite(staticProbabilityValue, 0.0, 1.0),
            1.35
    );
    const double confidence = smoothstep01(alignmentConfidence) *
            smoothstep01(modelConfidence) *
            smoothstep01(motionConfidence);
    return static_cast<float>(std::clamp(staticAuthority * confidence, 0.0, 1.0));
}

} // namespace spectra_temporal
