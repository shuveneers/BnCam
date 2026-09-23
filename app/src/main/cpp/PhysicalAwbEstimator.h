#pragma once

#include <algorithm>
#include <array>
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <limits>
#include <string>
#include <utility>
#include <vector>

namespace bncam::awb {

constexpr int kAwbTileColumns = 16;
constexpr int kAwbTileRows = 12;
constexpr int kAwbTileCount = kAwbTileColumns * kAwbTileRows;
constexpr double kAwbEpsilon = 1.0e-8;

struct LinearOpponentSample {
    // Linear scene luma plus opponent coordinates before WB/CCM.
    double luma = 0.0;
    double redMinusGreen = 0.0;
    double blueMinusGreen = 0.0;
    double structure = 0.0;
    int tileIndex = 0;
};

struct Estimate {
    std::array<double, 3> priorGainsRgb{1.0, 1.0, 1.0};
    std::array<double, 3> dataGainsRgb{1.0, 1.0, 1.0};
    std::array<double, 3> finalGainsRgb{1.0, 1.0, 1.0};

    std::size_t candidateSampleCount = 0;
    std::size_t exposureValidSampleCount = 0;
    std::size_t acceptedSampleCount = 0;
    std::size_t validTileCount = 0;

    double darkFloor = 0.012;
    double highlightCeiling = 0.86;
    double neutralSupport = 0.0;
    double acceptedExposureFraction = 0.0;
    double relativeNeutralEvidence = 0.0;
    double tileSupport = 0.0;
    double sampleSupport = 0.0;
    double mixedLightScore = 0.0;
    double priorDisagreement = 0.0;
    double confidence = 0.0;
    double dataAuthority = 0.0;
    double logGainMadR = 0.0;
    double logGainMadB = 0.0;
    double logGainSpanR = 0.0;
    double logGainSpanB = 0.0;

    double darkRejectedFraction = 0.0;
    double highlightRejectedFraction = 0.0;
    double invalidRejectedFraction = 0.0;
    double chromaticRejectedFraction = 0.0;

    bool priorValid = false;
    bool dataReady = false;
    bool mixedIllumination = false;
    std::string method = "PHYSICAL_AWB_GPU_COMPACT_GRAY_WORLD_V1";
    std::string status = "NOT_EVALUATED";
};

inline std::vector<LinearOpponentSample> decodeGpuResidualCandidates(
        const std::vector<float>& packed
) {
    std::vector<LinearOpponentSample> samples;
    samples.reserve(packed.size() / 8u);
    for (std::size_t base = 0; base + 7u < packed.size(); base += 8u) {
        const float encodedValidAndLuma = packed[base + 5u];
        if (!std::isfinite(encodedValidAndLuma) || encodedValidAndLuma < 1.0f) continue;
        const double luma = static_cast<double>(encodedValidAndLuma - 1.0f);
        const double structure = static_cast<double>(packed[base + 3u]);
        const double rg = static_cast<double>(packed[base + 6u]);
        const double bg = static_cast<double>(packed[base + 7u]);
        const int tileIndex = static_cast<int>(std::lround(packed[base + 4u]));
        if (!std::isfinite(luma) || !std::isfinite(structure) ||
            !std::isfinite(rg) || !std::isfinite(bg) ||
            tileIndex < 0 || tileIndex >= kAwbTileCount) continue;
        samples.push_back({luma, rg, bg, structure, tileIndex});
    }
    return samples;
}

namespace detail {

inline bool finite(double value) {
    return std::isfinite(value);
}

inline double smoothstep(double edge0, double edge1, double value) {
    if (!(edge1 > edge0) || !finite(value)) return value >= edge1 ? 1.0 : 0.0;
    const double u = std::clamp((value - edge0) / (edge1 - edge0), 0.0, 1.0);
    return u * u * (3.0 - 2.0 * u);
}

inline std::array<double, 3> normalizePrior(std::array<double, 3> gains, bool& valid) {
    valid = finite(gains[0]) && finite(gains[1]) && finite(gains[2]) &&
            gains[0] > 0.05 && gains[1] > 0.05 && gains[2] > 0.05;
    if (!valid) return {1.0, 1.0, 1.0};
    const double green = std::max(1.0e-4, gains[1]);
    gains[0] = std::clamp(gains[0] / green, 0.35, 3.50);
    gains[1] = 1.0;
    gains[2] = std::clamp(gains[2] / green, 0.35, 3.50);
    return gains;
}

inline bool reconstructRgb(const LinearOpponentSample& sample, std::array<double, 3>& rgb) {
    // Y = 0.2126 R + 0.7152 G + 0.0722 B; R=G+RG and B=G+BG.
    const double green = sample.luma - 0.2126 * sample.redMinusGreen -
            0.0722 * sample.blueMinusGreen;
    const double red = green + sample.redMinusGreen;
    const double blue = green + sample.blueMinusGreen;
    if (!finite(red) || !finite(green) || !finite(blue) ||
        red <= kAwbEpsilon || green <= kAwbEpsilon || blue <= kAwbEpsilon ||
        red > 1.25 || green > 1.25 || blue > 1.25) {
        return false;
    }
    rgb = {red, green, blue};
    return true;
}

struct WeightedValue {
    double value = 0.0;
    double weight = 0.0;
};

inline double weightedPercentile(std::vector<WeightedValue> values, double fraction) {
    values.erase(std::remove_if(values.begin(), values.end(), [](const WeightedValue& item) {
        return !finite(item.value) || !finite(item.weight) || item.weight <= 0.0;
    }), values.end());
    if (values.empty()) return 0.0;
    std::sort(values.begin(), values.end(), [](const WeightedValue& a, const WeightedValue& b) {
        return a.value < b.value;
    });
    double totalWeight = 0.0;
    for (const auto& item : values) totalWeight += item.weight;
    if (!(totalWeight > 0.0)) return values[values.size() / 2u].value;
    const double target = std::clamp(fraction, 0.0, 1.0) * totalWeight;
    double running = 0.0;
    for (const auto& item : values) {
        running += item.weight;
        if (running >= target) return item.value;
    }
    return values.back().value;
}

struct TileAccumulator {
    double weight = 0.0;
    double sumLogR = 0.0;
    double sumLogB = 0.0;
    std::size_t accepted = 0;
};

}  // namespace detail

inline Estimate resolve(
        const std::vector<LinearOpponentSample>& samples,
        std::array<double, 3> camera2PriorGainsRgb,
        double physicalLumaSigma
) {
    Estimate out{};
    out.candidateSampleCount = samples.size();
    out.priorGainsRgb = detail::normalizePrior(camera2PriorGainsRgb, out.priorValid);
    out.finalGainsRgb = out.priorGainsRgb;
    out.dataGainsRgb = out.priorGainsRgb;

    const double finiteSigma = detail::finite(physicalLumaSigma)
            ? std::max(0.0, physicalLumaSigma) : 0.0;
    out.darkFloor = std::clamp(std::max(0.012, 3.5 * finiteSigma), 0.012, 0.080);
    out.highlightCeiling = 0.86;

    if (samples.size() < 256u) {
        out.status = out.priorValid
                ? "INSUFFICIENT_COMPACT_SAMPLES_CAMERA2_PRIOR"
                : "INSUFFICIENT_COMPACT_SAMPLES_IDENTITY";
        return out;
    }

    std::array<detail::TileAccumulator, kAwbTileCount> tiles{};
    double neutralWeightSum = 0.0;
    double exposureWeightBase = 0.0;
    std::size_t darkRejected = 0u;
    std::size_t highlightRejected = 0u;
    std::size_t invalidRejected = 0u;
    std::size_t chromaticRejected = 0u;

    for (const auto& sample : samples) {
        if (!detail::finite(sample.luma) || !detail::finite(sample.redMinusGreen) ||
            !detail::finite(sample.blueMinusGreen) || !detail::finite(sample.structure) ||
            sample.tileIndex < 0 || sample.tileIndex >= kAwbTileCount) {
            invalidRejected++;
            continue;
        }
        if (sample.luma <= out.darkFloor) {
            darkRejected++;
            continue;
        }
        if (sample.luma >= out.highlightCeiling) {
            highlightRejected++;
            continue;
        }

        std::array<double, 3> rgb{};
        if (!detail::reconstructRgb(sample, rgb)) {
            invalidRejected++;
            continue;
        }
        out.exposureValidSampleCount++;

        const double priorR = rgb[0] * out.priorGainsRgb[0];
        const double priorG = rgb[1];
        const double priorB = rgb[2] * out.priorGainsRgb[2];
        const double logPriorRg = std::log(std::max(kAwbEpsilon, priorR) /
                std::max(kAwbEpsilon, priorG));
        const double logPriorBg = std::log(std::max(kAwbEpsilon, priorB) /
                std::max(kAwbEpsilon, priorG));
        const double priorChroma = std::sqrt(0.5 * (
                logPriorRg * logPriorRg + logPriorBg * logPriorBg));
        const double rawLogRg = std::log(std::max(kAwbEpsilon, rgb[0]) /
                std::max(kAwbEpsilon, rgb[1]));
        const double rawLogBg = std::log(std::max(kAwbEpsilon, rgb[2]) /
                std::max(kAwbEpsilon, rgb[1]));
        const double rawChroma = std::sqrt(0.5 * (
                rawLogRg * rawLogRg + rawLogBg * rawLogBg));

        const double channelMax = std::max({rgb[0], rgb[1], rgb[2]});
        const double channelMin = std::min({rgb[0], rgb[1], rgb[2]});
        const double saturation = (channelMax - channelMin) /
                std::max(kAwbEpsilon, channelMax);

        // Neutrality is measured in the Camera2-prior-balanced domain. This makes metadata an
        // anchor for candidate selection without forcing its gains to be the final answer.
        const double priorNeutralWeight = 1.0 - detail::smoothstep(0.16, 0.68, priorChroma);
        // A bad Camera2 prior must not be self-sealing. A broader prior-independent gray-world
        // gate retains limited authority when the raw chroma itself is still plausible for an
        // illuminant cast. Strongly saturated objects remain rejected by both raw chroma and
        // channel-saturation gates.
        const double grayWorldNeutralWeight = 1.0 - detail::smoothstep(0.28, 0.92, rawChroma);
        const double neutralWeight = std::max(priorNeutralWeight, 0.45 * grayWorldNeutralWeight);
        const double saturationWeight = 1.0 - detail::smoothstep(0.35, 0.82, saturation);
        const double structureWeight = 1.0 - detail::smoothstep(0.008, 0.060,
                std::max(0.0, sample.structure));
        const double lowerLumaWeight = detail::smoothstep(
                out.darkFloor, std::min(0.20, out.darkFloor + 0.075), sample.luma);
        const double upperLumaWeight = 1.0 - detail::smoothstep(
                0.70, out.highlightCeiling, sample.luma);
        const double lumaWeight = std::clamp(lowerLumaWeight * upperLumaWeight, 0.0, 1.0);
        exposureWeightBase += lumaWeight * structureWeight;
        neutralWeightSum += neutralWeight * saturationWeight * lumaWeight * structureWeight;

        const double weight = neutralWeight * saturationWeight * structureWeight * lumaWeight;
        if (!(weight >= 0.025)) {
            chromaticRejected++;
            continue;
        }

        const double logGainR = std::clamp(
                std::log(std::max(kAwbEpsilon, rgb[1]) / std::max(kAwbEpsilon, rgb[0])),
                -std::log(3.5), std::log(3.5));
        const double logGainB = std::clamp(
                std::log(std::max(kAwbEpsilon, rgb[1]) / std::max(kAwbEpsilon, rgb[2])),
                -std::log(3.5), std::log(3.5));

        auto& tile = tiles[static_cast<std::size_t>(sample.tileIndex)];
        tile.weight += weight;
        tile.sumLogR += weight * logGainR;
        tile.sumLogB += weight * logGainB;
        tile.accepted++;
        out.acceptedSampleCount++;
    }

    const double denominator = static_cast<double>(std::max<std::size_t>(1u, samples.size()));
    out.darkRejectedFraction = static_cast<double>(darkRejected) / denominator;
    out.highlightRejectedFraction = static_cast<double>(highlightRejected) / denominator;
    out.invalidRejectedFraction = static_cast<double>(invalidRejected) / denominator;
    out.chromaticRejectedFraction = static_cast<double>(chromaticRejected) / denominator;
    out.neutralSupport = exposureWeightBase > kAwbEpsilon
            ? std::clamp(neutralWeightSum / exposureWeightBase, 0.0, 1.0)
            : 0.0;

    std::vector<detail::WeightedValue> tileR;
    std::vector<detail::WeightedValue> tileB;
    tileR.reserve(kAwbTileCount);
    tileB.reserve(kAwbTileCount);
    for (const auto& tile : tiles) {
        if (tile.accepted < 8u || tile.weight < 1.0) continue;
        // Cap one tile's leverage so a large neutral wall cannot fully determine the scene WB.
        const double robustTileWeight = std::min(tile.weight, 64.0);
        tileR.push_back({tile.sumLogR / tile.weight, robustTileWeight});
        tileB.push_back({tile.sumLogB / tile.weight, robustTileWeight});
    }
    out.validTileCount = std::min(tileR.size(), tileB.size());
    out.sampleSupport = detail::smoothstep(384.0, 6000.0,
            static_cast<double>(out.acceptedSampleCount));
    out.tileSupport = detail::smoothstep(5.0, 48.0,
            static_cast<double>(out.validTileCount));

    if (out.acceptedSampleCount < 192u || out.validTileCount < 4u) {
        out.status = out.priorValid
                ? "INSUFFICIENT_NEUTRAL_SUPPORT_CAMERA2_PRIOR"
                : "INSUFFICIENT_NEUTRAL_SUPPORT_IDENTITY";
        return out;
    }

    const double dataLogR = detail::weightedPercentile(tileR, 0.50);
    const double dataLogB = detail::weightedPercentile(tileB, 0.50);
    std::vector<detail::WeightedValue> madR;
    std::vector<detail::WeightedValue> madB;
    madR.reserve(tileR.size());
    madB.reserve(tileB.size());
    for (const auto& item : tileR) madR.push_back({std::abs(item.value - dataLogR), item.weight});
    for (const auto& item : tileB) madB.push_back({std::abs(item.value - dataLogB), item.weight});
    out.logGainMadR = detail::weightedPercentile(madR, 0.50);
    out.logGainMadB = detail::weightedPercentile(madB, 0.50);
    out.logGainSpanR = detail::weightedPercentile(tileR, 0.90) -
            detail::weightedPercentile(tileR, 0.10);
    out.logGainSpanB = detail::weightedPercentile(tileB, 0.90) -
            detail::weightedPercentile(tileB, 0.10);

    const double robustDispersion = std::hypot(out.logGainMadR, out.logGainMadB);
    const double spanDispersion = 0.5 * std::hypot(out.logGainSpanR, out.logGainSpanB);
    out.mixedLightScore = std::clamp(
            0.35 * detail::smoothstep(0.055, 0.24, robustDispersion) +
            0.65 * detail::smoothstep(0.18, 0.70, spanDispersion),
            0.0, 1.0);
    out.mixedIllumination = out.mixedLightScore >= 0.40;

    out.dataGainsRgb = {
            std::clamp(std::exp(dataLogR), 0.35, 3.50),
            1.0,
            std::clamp(std::exp(dataLogB), 0.35, 3.50)
    };
    const double priorLogR = std::log(std::max(kAwbEpsilon, out.priorGainsRgb[0]));
    const double priorLogB = std::log(std::max(kAwbEpsilon, out.priorGainsRgb[2]));
    out.priorDisagreement = std::hypot(dataLogR - priorLogR, dataLogB - priorLogB);

    // Sensor-adaptive evidence: do not use an absolute neutralSupport cliff. The previous
    // smoothstep(0.10, 0.48, ...) made a sensor with 63k coherent accepted samples and 176 tiles
    // collapse to exactly zero confidence solely because its scene-normalized neutral support was
    // 0.092. Instead compare neutral evidence against the same frame's chromatic rejection and
    // require that a meaningful fraction of exposure-valid samples actually survived all gates.
    out.acceptedExposureFraction = out.exposureValidSampleCount > 0u
            ? std::clamp(
                    static_cast<double>(out.acceptedSampleCount) /
                            static_cast<double>(out.exposureValidSampleCount),
                    0.0, 1.0)
            : 0.0;
    out.relativeNeutralEvidence = std::clamp(
            out.neutralSupport /
                    std::max(kAwbEpsilon, out.neutralSupport + out.chromaticRejectedFraction),
            0.0, 1.0);
    const double neutralConfidence = std::sqrt(std::max(
            0.0, out.relativeNeutralEvidence * out.acceptedExposureFraction));
    const double mixedPenalty = 1.0 - 0.78 * out.mixedLightScore;
    const double disagreementPenalty = 1.0 - 0.30 * detail::smoothstep(
            0.30, 0.95, out.priorDisagreement);
    const double supportConfidence = std::sqrt(std::max(
            0.0, out.sampleSupport * out.tileSupport));
    out.confidence = std::clamp(
            supportConfidence * neutralConfidence * mixedPenalty * disagreementPenalty,
            0.0, 1.0);
    out.dataAuthority = std::clamp(0.88 * out.confidence, 0.0,
            out.mixedIllumination ? 0.48 : 0.88);

    const double finalLogR = priorLogR + out.dataAuthority * (dataLogR - priorLogR);
    const double finalLogB = priorLogB + out.dataAuthority * (dataLogB - priorLogB);
    out.finalGainsRgb = {
            std::clamp(std::exp(finalLogR), 0.35, 3.50),
            1.0,
            std::clamp(std::exp(finalLogB), 0.35, 3.50)
    };
    out.dataReady = true;
    out.status = out.mixedIllumination
            ? "READY_MIXED_LIGHT_CAMERA2_ANCHORED"
            : "READY_CONFIDENCE_WEIGHTED_CAMERA2_ANCHORED";
    return out;
}

}  // namespace bncam::awb
