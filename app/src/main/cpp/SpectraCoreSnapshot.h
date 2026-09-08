#pragma once

#include "RawCfaContract.h"

#include <algorithm>
#include <array>
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <limits>

namespace bncam::spectra::neural {

constexpr std::uint32_t kSpectraCoreSnapshotSchemaVersion = 1;
constexpr std::size_t kCanonicalCfaChannelCount = 4;

enum class CanonicalCfaChannel : std::uint8_t {
    R = 0,
    G1 = 1,
    G2 = 2,
    B = 3
};

constexpr std::size_t channelIndex(CanonicalCfaChannel channel) noexcept {
    return static_cast<std::size_t>(channel);
}

struct CanonicalSampleOffset {
    std::uint8_t x = 0;
    std::uint8_t y = 0;
};

// Canonical packed order is always [R, G1, G2, B].
// G1 is the green sample sharing R's row; G2 shares R's column.
// sourceOffsets therefore describe where each canonical channel lives in the
// resolved 2x2 Bayer cell without assuming RGGB sensor metadata.
struct CanonicalBayerPackContract {
    int sensorArrangement = bncam::raw::CFA_UNSUPPORTED;
    int effectiveBayerPattern = bncam::raw::CFA_UNSUPPORTED;
    int cfaOffsetX = 0;
    int cfaOffsetY = 0;
    std::array<CanonicalSampleOffset, kCanonicalCfaChannelCount> sourceOffsets{};
    bool standardBayer = false;

    bool valid() const noexcept {
        if (!standardBayer || !bncam::raw::isStandardBayerArrangement(effectiveBayerPattern)) {
            return false;
        }
        std::array<bool, 4> seen{{false, false, false, false}};
        for (const auto& offset : sourceOffsets) {
            if (offset.x > 1 || offset.y > 1) {
                return false;
            }
            const std::size_t slot = static_cast<std::size_t>(offset.y) * 2u + offset.x;
            if (seen[slot]) {
                return false;
            }
            seen[slot] = true;
        }
        return true;
    }

    bool supportsExtent(std::uint32_t width, std::uint32_t height) const noexcept {
        // Phase-1 contract is deliberately strict: never invent or pad an
        // incomplete Bayer cell at a neural tile/image edge.
        return valid() && width >= 2u && height >= 2u && (width % 2u) == 0u && (height % 2u) == 0u;
    }

    std::uint32_t packedWidth(std::uint32_t width) const noexcept {
        return width / 2u;
    }

    std::uint32_t packedHeight(std::uint32_t height) const noexcept {
        return height / 2u;
    }
};

inline CanonicalBayerPackContract resolveCanonicalBayerPack(
        int sensorArrangement,
        int cfaOffsetX,
        int cfaOffsetY) noexcept {
    CanonicalBayerPackContract out{};
    out.sensorArrangement = sensorArrangement;
    out.cfaOffsetX = cfaOffsetX;
    out.cfaOffsetY = cfaOffsetY;

    const auto cfa = bncam::raw::resolveCfaContract(sensorArrangement, cfaOffsetX, cfaOffsetY);
    if (cfa.kind != bncam::raw::RawCfaContractKind::STANDARD_BAYER ||
        !bncam::raw::isStandardBayerArrangement(cfa.effectiveBayerPattern)) {
        return out;
    }

    out.standardBayer = true;
    out.effectiveBayerPattern = cfa.effectiveBayerPattern;
    switch (cfa.effectiveBayerPattern) {
        case bncam::raw::CFA_RGGB:
            out.sourceOffsets = {{{0, 0}, {1, 0}, {0, 1}, {1, 1}}};
            break;
        case bncam::raw::CFA_GRBG:
            out.sourceOffsets = {{{1, 0}, {0, 0}, {1, 1}, {0, 1}}};
            break;
        case bncam::raw::CFA_GBRG:
            out.sourceOffsets = {{{0, 1}, {1, 1}, {0, 0}, {1, 0}}};
            break;
        case bncam::raw::CFA_BGGR:
            out.sourceOffsets = {{{1, 1}, {0, 1}, {1, 0}, {0, 0}}};
            break;
        default:
            out.standardBayer = false;
            break;
    }
    return out;
}

inline bool finiteUnit(float value) noexcept {
    return std::isfinite(value) && value >= 0.0f && value <= 1.0f;
}

struct ShotReadNoiseModel {
    std::array<float, 4> shotS{{0.0f, 0.0f, 0.0f, 0.0f}};
    std::array<float, 4> readO{{0.0f, 0.0f, 0.0f, 0.0f}};
    float trust = 0.0f;

    bool valid() const noexcept {
        if (!finiteUnit(trust)) {
            return false;
        }
        for (std::size_t i = 0; i < 4; ++i) {
            if (!std::isfinite(shotS[i]) || !std::isfinite(readO[i]) || shotS[i] < 0.0f || readO[i] < 0.0f) {
                return false;
            }
        }
        return true;
    }

    float variance(CanonicalCfaChannel channel, float normalizedSignal) const noexcept {
        const std::size_t i = channelIndex(channel);
        if (i >= 4 || !valid() || !std::isfinite(normalizedSignal)) {
            return std::numeric_limits<float>::quiet_NaN();
        }
        const float x = std::clamp(normalizedSignal, 0.0f, 1.0f);
        return std::max(shotS[i] * x + readO[i], 0.0f);
    }

    float sigma(CanonicalCfaChannel channel, float normalizedSignal) const noexcept {
        const float v = variance(channel, normalizedSignal);
        return std::isfinite(v) ? std::sqrt(v) : v;
    }
};

struct MetadataTrustState {
    float noiseProfile = 0.0f;
    float blackLevel = 0.0f;
    float whiteLevel = 0.0f;
    float lensShading = 0.0f;
    float exposureGain = 0.0f;
    float sensorDomain = 0.0f;

    bool valid() const noexcept {
        return finiteUnit(noiseProfile) && finiteUnit(blackLevel) && finiteUnit(whiteLevel) &&
               finiteUnit(lensShading) && finiteUnit(exposureGain) && finiteUnit(sensorDomain);
    }

    float conservativeConditioningTrust() const noexcept {
        if (!valid()) {
            return 0.0f;
        }
        return std::min({noiseProfile, blackLevel, whiteLevel, lensShading, exposureGain, sensorDomain});
    }
};

struct BlackLevelResidualState {
    std::array<float, 4> residualNormalized{{0.0f, 0.0f, 0.0f, 0.0f}};
    float maxAbsResidualNormalized = 0.0f;
    float trust = 0.0f;
    bool dynamicBlackPriorUsed = false;

    bool valid() const noexcept {
        if (!std::isfinite(maxAbsResidualNormalized) || maxAbsResidualNormalized < 0.0f ||
            maxAbsResidualNormalized > 1.0f || !finiteUnit(trust)) {
            return false;
        }
        for (float value : residualNormalized) {
            if (!std::isfinite(value) || std::abs(value) > maxAbsResidualNormalized) {
                return false;
            }
        }
        return true;
    }
};

struct RemainingLensShadingState {
    bool lensShadingAlreadyApplied = false;
    bool remainingCorrectionExpected = false;
    bool hasSpatialGainMap = false;
    std::uint32_t mapWidth = 0;
    std::uint32_t mapHeight = 0;
    std::uint32_t mapChannels = 0;
    float minGain = 1.0f;
    float medianGain = 1.0f;
    float maxGain = 1.0f;
    float mapTrust = 0.0f;

    bool valid() const noexcept {
        if (!finiteUnit(mapTrust) || !std::isfinite(minGain) || !std::isfinite(medianGain) ||
            !std::isfinite(maxGain) || minGain <= 0.0f || medianGain <= 0.0f || maxGain <= 0.0f ||
            minGain > medianGain || medianGain > maxGain) {
            return false;
        }
        if (!hasSpatialGainMap) {
            return mapWidth == 0 && mapHeight == 0 && mapChannels == 0;
        }
        return mapWidth > 0 && mapHeight > 0 && (mapChannels == 1 || mapChannels == 4);
    }

    bool hasRequiredCondition() const noexcept {
        return !remainingCorrectionExpected || hasSpatialGainMap;
    }
};

struct StructuredNoiseEvidence {
    float rowPeriodicity = 0.0f;
    float columnPeriodicity = 0.0f;
    float fixedPattern = 0.0f;
    float dsnuLike = 0.0f;
    float prnuLike = 0.0f;
    float lowFrequencyResidual = 0.0f;
    float lowFrequencyChroma = 0.0f;
    float channelImbalance = 0.0f;
    float spatialBlackDrift = 0.0f;
    float rareReadoutPattern = 0.0f;
    float confidence = 0.0f;
    float dominantRowPeriodPixels = 0.0f;
    float dominantColumnPeriodPixels = 0.0f;

    bool valid() const noexcept {
        const std::array<float, 11> normalized{{
            rowPeriodicity, columnPeriodicity, fixedPattern, dsnuLike, prnuLike,
            lowFrequencyResidual, lowFrequencyChroma, channelImbalance,
            spatialBlackDrift, rareReadoutPattern, confidence
        }};
        for (float value : normalized) {
            if (!finiteUnit(value)) {
                return false;
            }
        }
        return std::isfinite(dominantRowPeriodPixels) && dominantRowPeriodPixels >= 0.0f &&
               std::isfinite(dominantColumnPeriodPixels) && dominantColumnPeriodPixels >= 0.0f;
    }
};

// Immutable-by-contract value snapshot for SPECTRA Neural consumers. The core
// snapshot carries evidence and calibration only; it owns no image buffer and
// exposes no pixel-mutation operation.
struct SpectraCoreSnapshot {
    std::uint32_t schemaVersion = kSpectraCoreSnapshotSchemaVersion;
    std::uint64_t frameId = 0;
    std::uint32_t rawWidth = 0;
    std::uint32_t rawHeight = 0;
    CanonicalBayerPackContract cfa{};

    // Raw-code levels are retained for traceability/normalization setup. Neural
    // spatial input itself is normalized sensor-domain RAW.
    std::array<float, 4> blackLevelRaw{{0.0f, 0.0f, 0.0f, 0.0f}};
    std::array<float, 4> whiteLevelRaw{{1.0f, 1.0f, 1.0f, 1.0f}};
    bool rawLevelsValid = false;

    ShotReadNoiseModel noise{};
    MetadataTrustState metadataTrust{};
    BlackLevelResidualState blackResidual{};
    RemainingLensShadingState remainingLsc{};
    StructuredNoiseEvidence structuredNoise{};

    bool structurallyValid() const noexcept {
        if (schemaVersion != kSpectraCoreSnapshotSchemaVersion || !cfa.supportsExtent(rawWidth, rawHeight) ||
            !noise.valid() || !metadataTrust.valid() || !blackResidual.valid() ||
            !remainingLsc.valid() || !structuredNoise.valid()) {
            return false;
        }
        if (!rawLevelsValid) {
            return false;
        }
        for (std::size_t i = 0; i < 4; ++i) {
            if (!std::isfinite(blackLevelRaw[i]) || !std::isfinite(whiteLevelRaw[i]) ||
                whiteLevelRaw[i] <= blackLevelRaw[i]) {
                return false;
            }
        }
        return true;
    }

    bool neuralConditioningReady() const noexcept {
        return structurallyValid() && remainingLsc.hasRequiredCondition();
    }
};

} // namespace bncam::spectra::neural
