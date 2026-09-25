#pragma once

#include "SpectraNeuralAdaptiveAuthority.h"

#include <array>
#include <cmath>
#include <cstddef>
#include <cstdint>

namespace bncam::spectra::neural {

// Phase-6 recovery telemetry layout emitted by neural_mosaic_bridge.comp mode 3.
// One workgroup covers up to 32x32 packed Bayer samples and writes fifteen vec4 sums.
// CPU control code reduces only this compact buffer; full-frame RAW never leaves GPU residency.
constexpr std::uint32_t kNeuralEffectSummaryVec4PerGroup = 15u;
constexpr float kNeuralEffectHighSnrThreshold = kNeuralAdaptiveIdentitySnr;

struct SpectraNeuralEffectTelemetry {
    bool ready = false;
    std::uint64_t packedSampleCount = 0u;

    std::array<float, 4> meanCorrectionCfa{{0.0f, 0.0f, 0.0f, 0.0f}};
    std::array<float, 4> meanAbsCorrectionCfa{{0.0f, 0.0f, 0.0f, 0.0f}};
    std::array<float, 4> rmsCorrectionCfa{{0.0f, 0.0f, 0.0f, 0.0f}};
    std::array<float, 4> rmsCorrectionSigmaCfa{{0.0f, 0.0f, 0.0f, 0.0f}};
    std::array<float, 4> fractionAboveHalfSigmaCfa{{0.0f, 0.0f, 0.0f, 0.0f}};
    std::array<float, 4> fractionAboveOneSigmaCfa{{0.0f, 0.0f, 0.0f, 0.0f}};
    std::array<float, 4> fractionAboveTwoSigmaCfa{{0.0f, 0.0f, 0.0f, 0.0f}};
    std::array<float, 4> posteriorToInputVarianceRatioCfa{{0.0f, 0.0f, 0.0f, 0.0f}};
    std::array<float, 4> posteriorMeanVarianceCfa{{0.0f, 0.0f, 0.0f, 0.0f}};

    // Orthonormal sensor-domain residual basis: common mode, R-B, (R+B)-(G1+G2), G1-G2.
    std::array<float, 4> residualBasisRms{{0.0f, 0.0f, 0.0f, 0.0f}};
    std::array<float, 4> residualBasisRmsSigma{{0.0f, 0.0f, 0.0f, 0.0f}};

    // These high-SNR fields are observational; the shared identity threshold also gates Phase-9 writeback.
    std::array<float, 4> highSnrRmsCorrectionSigmaCfa{{0.0f, 0.0f, 0.0f, 0.0f}};
    std::array<float, 4> highSnrSampleFractionCfa{{0.0f, 0.0f, 0.0f, 0.0f}};

    // Phase 9: direct observability of the sensor-independent authority evidence.
    std::array<float, 4> meanInputSigmaCfa{{0.0f, 0.0f, 0.0f, 0.0f}};
    std::array<float, 4> meanAdaptiveNoiseEvidenceCfa{{0.0f, 0.0f, 0.0f, 0.0f}};
};


inline bool reducePosteriorMeanFromNeuralEffectSummary(
        const float* values,
        std::size_t workgroupCount,
        std::uint64_t packedSampleCount,
        std::array<float, 4>& posteriorMeanVarianceCfa) noexcept {
    posteriorMeanVarianceCfa = {{0.0f, 0.0f, 0.0f, 0.0f}};
    if (values == nullptr || workgroupCount == 0u || packedSampleCount == 0u) {
        return false;
    }
    constexpr std::size_t kLanes = 4u;
    constexpr std::size_t kStride = kNeuralEffectSummaryVec4PerGroup * kLanes;
    constexpr std::size_t kPosteriorSlot = 12u;
    std::array<double, 4> sums{{0.0, 0.0, 0.0, 0.0}};
    for (std::size_t group = 0u; group < workgroupCount; ++group) {
        const std::size_t base = group * kStride + kPosteriorSlot * kLanes;
        for (std::size_t lane = 0u; lane < kLanes; ++lane) {
            const float value = values[base + lane];
            if (!std::isfinite(value) || value < 0.0f) {
                posteriorMeanVarianceCfa = {{0.0f, 0.0f, 0.0f, 0.0f}};
                return false;
            }
            sums[lane] += static_cast<double>(value);
        }
    }
    const double n = static_cast<double>(packedSampleCount);
    for (std::size_t lane = 0u; lane < kLanes; ++lane) {
        const double mean = sums[lane] / n;
        if (!std::isfinite(mean) || mean < 0.0) {
            posteriorMeanVarianceCfa = {{0.0f, 0.0f, 0.0f, 0.0f}};
            return false;
        }
        posteriorMeanVarianceCfa[lane] = static_cast<float>(mean);
    }
    return true;
}

inline SpectraNeuralEffectTelemetry reduceNeuralEffectSummary(
        const float* values,
        std::size_t workgroupCount,
        std::uint64_t packedSampleCount) noexcept {
    SpectraNeuralEffectTelemetry out{};
    out.packedSampleCount = packedSampleCount;
    if (values == nullptr || workgroupCount == 0u || packedSampleCount == 0u) {
        return out;
    }

    constexpr std::size_t kLanes = 4u;
    constexpr std::size_t kStride = kNeuralEffectSummaryVec4PerGroup * kLanes;
    std::array<std::array<double, 4>, kNeuralEffectSummaryVec4PerGroup> sums{};
    for (std::size_t group = 0u; group < workgroupCount; ++group) {
        const std::size_t base = group * kStride;
        for (std::size_t slot = 0u; slot < kNeuralEffectSummaryVec4PerGroup; ++slot) {
            for (std::size_t lane = 0u; lane < kLanes; ++lane) {
                const float value = values[base + slot * kLanes + lane];
                if (!std::isfinite(value)) {
                    return SpectraNeuralEffectTelemetry{};
                }
                sums[slot][lane] += static_cast<double>(value);
            }
        }
    }

    const double n = static_cast<double>(packedSampleCount);
    for (std::size_t c = 0u; c < 4u; ++c) {
        const double sumDelta = sums[0][c];
        const double sumAbs = sums[1][c];
        const double sumSq = sums[2][c];
        const double sumSigmaSq = sums[3][c];
        const double halfCount = sums[4][c];
        const double oneCount = sums[5][c];
        const double twoCount = sums[6][c];
        const double posteriorRatio = sums[7][c];
        const double highSnrSigmaSq = sums[10][c];
        const double highSnrCount = sums[11][c];
        const double posteriorSum = sums[12][c];
        const double inputSigmaSum = sums[13][c];
        const double noiseEvidenceSum = sums[14][c];

        if (sumAbs < 0.0 || sumSq < 0.0 || sumSigmaSq < 0.0 ||
            halfCount < 0.0 || oneCount < 0.0 || twoCount < 0.0 ||
            posteriorRatio < 0.0 || highSnrSigmaSq < 0.0 || highSnrCount < 0.0 ||
            posteriorSum < 0.0 || inputSigmaSum < 0.0 || noiseEvidenceSum < 0.0 ||
            halfCount > n + 0.5 || oneCount > n + 0.5 || twoCount > n + 0.5 ||
            highSnrCount > n + 0.5 || noiseEvidenceSum > n + 0.5) {
            return SpectraNeuralEffectTelemetry{};
        }

        out.meanCorrectionCfa[c] = static_cast<float>(sumDelta / n);
        out.meanAbsCorrectionCfa[c] = static_cast<float>(sumAbs / n);
        out.rmsCorrectionCfa[c] = static_cast<float>(std::sqrt(sumSq / n));
        out.rmsCorrectionSigmaCfa[c] = static_cast<float>(std::sqrt(sumSigmaSq / n));
        out.fractionAboveHalfSigmaCfa[c] = static_cast<float>(halfCount / n);
        out.fractionAboveOneSigmaCfa[c] = static_cast<float>(oneCount / n);
        out.fractionAboveTwoSigmaCfa[c] = static_cast<float>(twoCount / n);
        out.posteriorToInputVarianceRatioCfa[c] = static_cast<float>(posteriorRatio / n);
        out.posteriorMeanVarianceCfa[c] = static_cast<float>(posteriorSum / n);
        out.highSnrSampleFractionCfa[c] = static_cast<float>(highSnrCount / n);
        out.meanInputSigmaCfa[c] = static_cast<float>(inputSigmaSum / n);
        out.meanAdaptiveNoiseEvidenceCfa[c] = static_cast<float>(noiseEvidenceSum / n);
        out.highSnrRmsCorrectionSigmaCfa[c] = highSnrCount > 0.0
                ? static_cast<float>(std::sqrt(highSnrSigmaSq / highSnrCount))
                : 0.0f;
    }

    for (std::size_t component = 0u; component < 4u; ++component) {
        const double rawSq = sums[8][component];
        const double sigmaSq = sums[9][component];
        if (rawSq < 0.0 || sigmaSq < 0.0) {
            return SpectraNeuralEffectTelemetry{};
        }
        out.residualBasisRms[component] = static_cast<float>(std::sqrt(rawSq / n));
        out.residualBasisRmsSigma[component] = static_cast<float>(std::sqrt(sigmaSq / n));
    }

    out.ready = true;
    return out;
}

} // namespace bncam::spectra::neural
