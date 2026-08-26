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

struct InnovationDiagnostics {
    double mean = 0.0;
    double variance = 0.0;
    double lagCorrelation = 0.0;
    double heavyTailFraction = 1.0;
    uint64_t samples = 0;
};

struct TemporalFitPoint {
    double signal = 0.0;
    double variance = 0.0;
    double weight = 0.0;
};

enum class TemporalFitEstimator {
    NONE = 0,
    WEIGHTED_LEAST_SQUARES = 1,
    HUBER_IRLS = 2
};

inline const char* estimatorName(TemporalFitEstimator estimator) {
    switch (estimator) {
        case TemporalFitEstimator::WEIGHTED_LEAST_SQUARES: return "WEIGHTED_LEAST_SQUARES";
        case TemporalFitEstimator::HUBER_IRLS: return "HUBER_IRLS";
        default: return "NONE";
    }
}

struct TemporalFitMetrics {
    double weightedRmseRatio = 1.0;
    double inlierFraction = 0.0;
    double signalSpan = 0.0;
    int populatedBins = 0;
    double innovationMean = 0.0;
    double innovationVariance = 0.0;
    double innovationLagCorrelation = 0.0;
    double heavyTailFraction = 1.0;
    double physicalScore = 0.0;
};

struct TemporalFitResult {
    bool valid = false;
    TemporalFitEstimator estimator = TemporalFitEstimator::NONE;
    double slope = 0.0;
    double offset = 0.0;
    double confidence = 0.0;
    TemporalFitMetrics metrics{};
};

inline bool solveWeightedAffine(
        const std::vector<TemporalFitPoint>& points,
        const std::vector<double>& multipliers,
        double& slope,
        double& offset
) {
    if (points.size() < 2u || multipliers.size() != points.size()) return false;
    double sw = 0.0;
    double sx = 0.0;
    double sy = 0.0;
    double sxx = 0.0;
    double sxy = 0.0;
    for (size_t i = 0; i < points.size(); ++i) {
        const TemporalFitPoint& point = points[i];
        if (!std::isfinite(point.signal) || !std::isfinite(point.variance) ||
            !std::isfinite(point.weight) || point.weight <= 0.0 || point.variance < 0.0) {
            continue;
        }
        const double weight = point.weight * std::clamp(multipliers[i], 0.01, 1.0);
        sw += weight;
        sx += weight * point.signal;
        sy += weight * point.variance;
        sxx += weight * point.signal * point.signal;
        sxy += weight * point.signal * point.variance;
    }
    const double denominator = sw * sxx - sx * sx;
    if (sw <= 1.0e-12 || denominator <= 1.0e-12) return false;
    slope = std::max(0.0, (sw * sxy - sx * sy) / denominator);
    offset = std::max(0.0, (sy - slope * sx) / sw);
    return std::isfinite(slope) && std::isfinite(offset);
}

inline TemporalFitMetrics evaluateFit(
        const std::vector<TemporalFitPoint>& points,
        const std::vector<double>& robustWeights,
        double slope,
        double offset,
        const InnovationDiagnostics& innovation
) {
    TemporalFitMetrics metrics{};
    if (points.empty()) return metrics;
    double residualEnergy = 0.0;
    double observationEnergy = 0.0;
    double totalWeight = 0.0;
    double inlierWeight = 0.0;
    double minSignal = std::numeric_limits<double>::infinity();
    double maxSignal = -std::numeric_limits<double>::infinity();
    for (size_t i = 0; i < points.size(); ++i) {
        const TemporalFitPoint& point = points[i];
        const double robust = i < robustWeights.size()
                ? std::clamp(robustWeights[i], 0.0, 1.0)
                : 1.0;
        const double weight = std::max(0.0, point.weight) * std::max(0.01, robust);
        const double fitted = std::max(1.0e-12, slope * point.signal + offset);
        const double residual = point.variance - fitted;
        residualEnergy += weight * residual * residual;
        observationEnergy += weight * point.variance * point.variance;
        totalWeight += std::max(0.0, point.weight);
        inlierWeight += std::max(0.0, point.weight) * robust;
        minSignal = std::min(minSignal, point.signal);
        maxSignal = std::max(maxSignal, point.signal);
    }
    metrics.weightedRmseRatio = observationEnergy > 1.0e-20
            ? std::sqrt(residualEnergy / observationEnergy)
            : 1.0;
    metrics.inlierFraction = totalWeight > 1.0e-12
            ? std::clamp(inlierWeight / totalWeight, 0.0, 1.0)
            : 0.0;
    metrics.signalSpan = std::isfinite(minSignal) && std::isfinite(maxSignal)
            ? std::max(0.0, maxSignal - minSignal)
            : 0.0;
    metrics.populatedBins = static_cast<int>(points.size());
    metrics.innovationMean = innovation.mean;
    metrics.innovationVariance = innovation.variance;
    metrics.innovationLagCorrelation = innovation.lagCorrelation;
    metrics.heavyTailFraction = innovation.heavyTailFraction;

    const double fitQuality = std::clamp(1.0 - metrics.weightedRmseRatio, 0.0, 1.0);
    const double meanScore = std::exp(-0.5 * std::pow(metrics.innovationMean / 0.20, 2.0));
    const double varianceScore = std::exp(-0.5 * std::pow((metrics.innovationVariance - 1.0) / 0.45, 2.0));
    const double correlationScore = std::exp(-std::abs(metrics.innovationLagCorrelation) / 0.18);
    const double tailScore = std::exp(-metrics.heavyTailFraction / 0.08);
    const double spanScore = smoothstep01(metrics.signalSpan / 0.20);
    const double binScore = smoothstep01(static_cast<double>(metrics.populatedBins) / 6.0);
    metrics.physicalScore = std::clamp(
            fitQuality * metrics.inlierFraction * meanScore * varianceScore *
                    correlationScore * tailScore * spanScore * binScore,
            0.0,
            1.0
    );
    return metrics;
}

inline TemporalFitResult fitTemporalNoiseModel(
        const std::vector<TemporalFitPoint>& points,
        const InnovationDiagnostics& innovation
) {
    TemporalFitResult best{};
    if (points.size() < 3u) return best;

    std::vector<double> unitWeights(points.size(), 1.0);
    double wlsSlope = 0.0;
    double wlsOffset = 0.0;
    if (!solveWeightedAffine(points, unitWeights, wlsSlope, wlsOffset)) return best;
    TemporalFitResult wls{};
    wls.valid = true;
    wls.estimator = TemporalFitEstimator::WEIGHTED_LEAST_SQUARES;
    wls.slope = wlsSlope;
    wls.offset = wlsOffset;
    wls.metrics = evaluateFit(points, unitWeights, wlsSlope, wlsOffset, innovation);
    wls.confidence = wls.metrics.physicalScore;

    std::vector<double> huberWeights(points.size(), 1.0);
    double huberSlope = wlsSlope;
    double huberOffset = wlsOffset;
    bool huberValid = true;
    for (int iteration = 0; iteration < 4; ++iteration) {
        std::vector<double> residuals;
        residuals.reserve(points.size());
        for (const TemporalFitPoint& point : points) {
            residuals.push_back(std::abs(
                    point.variance - (huberSlope * point.signal + huberOffset)
            ));
        }
        const double median = percentile(residuals, 0.50);
        const double robustScale = std::max(
                1.0e-12,
                1.4826 * median + 0.01 * std::max(huberOffset, 1.0e-12)
        );
        const double limit = 1.5 * robustScale;
        for (size_t i = 0; i < points.size(); ++i) {
            const double residual = std::abs(
                    points[i].variance - (huberSlope * points[i].signal + huberOffset)
            );
            huberWeights[i] = residual <= limit
                    ? 1.0
                    : std::clamp(limit / std::max(residual, 1.0e-12), 0.03, 1.0);
        }
        huberValid = solveWeightedAffine(points, huberWeights, huberSlope, huberOffset);
        if (!huberValid) break;
    }

    TemporalFitResult huber{};
    if (huberValid) {
        huber.valid = true;
        huber.estimator = TemporalFitEstimator::HUBER_IRLS;
        huber.slope = huberSlope;
        huber.offset = huberOffset;
        huber.metrics = evaluateFit(points, huberWeights, huberSlope, huberOffset, innovation);
        huber.confidence = huber.metrics.physicalScore;
    }

    // Prefer the robust model only when it is physically at least as plausible. This keeps
    // clean, well-spread fixtures on ordinary WLS while allowing outlier-contaminated bins
    // to select Huber deterministically.
    if (huber.valid && huber.confidence >= wls.confidence * 0.98 &&
        huber.metrics.inlierFraction >= 0.55) {
        best = huber;
    } else {
        best = wls;
    }
    best.confidence = std::clamp(best.confidence, 0.0, 1.0);
    return best;
}

inline double fitStability(
        double currentSScale,
        double currentOScale,
        double previousSScale,
        double previousOScale
) {
    const double currentS = clampFinite(currentSScale, 0.75, 1.25, 1.0);
    const double currentO = clampFinite(currentOScale, 0.75, 1.25, 1.0);
    const double previousS = clampFinite(previousSScale, 0.75, 1.25, 1.0);
    const double previousO = clampFinite(previousOScale, 0.75, 1.25, 1.0);
    const double logDistance = 0.5 * (
            std::abs(std::log(currentS / previousS)) +
            std::abs(std::log(currentO / previousO))
    );
    return std::clamp(std::exp(-logDistance / 0.10), 0.0, 1.0);
}

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
