#pragma once

#include "SpectraCoreSnapshot.h"

#include <algorithm>
#include <array>
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <limits>

namespace bncam::spectra::neural {

constexpr std::uint32_t kSpectraNeuralConditioningSchemaVersion = 1;
constexpr std::size_t kBaseSpatialConditioningChannelCount = 14;
constexpr std::size_t kGlobalConditioningValueCount = 18;

// Frozen field order shared with spectra_train.contracts.GLOBAL_CONDITIONING_FIELDS.
// This is a model ABI. Reordering requires a versioned package/model contract.
enum class GlobalConditioningField : std::uint8_t {
    LogExposureSeconds = 0,
    LogAnalogGain = 1,
    LogDigitalGain = 2,
    BitDepthOver32 = 3,
    NoiseModelTrust = 4,
    BlackLevelTrust = 5,
    RemainingLscTrust = 6,
    RowPeriodicity = 7,
    ColumnPeriodicity = 8,
    FixedPattern = 9,
    DsnuLike = 10,
    PrnuLike = 11,
    LowFrequencyResidual = 12,
    LowFrequencyChroma = 13,
    ChannelImbalance = 14,
    SpatialBlackDrift = 15,
    RareReadoutPattern = 16,
    StructuredConfidence = 17
};

constexpr std::size_t globalConditioningIndex(GlobalConditioningField field) noexcept {
    return static_cast<std::size_t>(field);
}

enum class SpatialConditioningChannel : std::uint8_t {
    RawR = 0,
    RawG1 = 1,
    RawG2 = 2,
    RawB = 3,
    LogSigmaR = 4,
    LogSigmaG1 = 5,
    LogSigmaG2 = 6,
    LogSigmaB = 7,
    RemainingLscR = 8,
    RemainingLscG1 = 9,
    RemainingLscG2 = 10,
    RemainingLscB = 11,
    MetadataTrust = 12,
    Headroom = 13
};

constexpr std::size_t conditioningChannelIndex(SpatialConditioningChannel channel) noexcept {
    return static_cast<std::size_t>(channel);
}

enum class RawCaptureDomain : std::uint8_t {
    Unknown = 0,
    Raw10 = 1,
    RawSensor = 2
};

struct NeuralFramePhysicsContext {
    RawCaptureDomain captureDomain = RawCaptureDomain::Unknown;
    double exposureTimeSeconds = 0.0;
    float analogGain = 0.0f;
    float digitalGain = 0.0f;
    std::uint32_t bitDepth = 0;

    bool valid() const noexcept {
        if (captureDomain == RawCaptureDomain::Unknown || !std::isfinite(exposureTimeSeconds) ||
            exposureTimeSeconds <= 0.0 || !std::isfinite(analogGain) || analogGain <= 0.0f ||
            !std::isfinite(digitalGain) || digitalGain <= 0.0f) {
            return false;
        }
        return bitDepth > 0u && bitDepth <= 32u;
    }
};

struct SpectraNeuralConditioningConfig {
    // Numerical floor only; it prevents log(0) and is not a denoise-strength knob.
    float logSigmaFloor = 1.0e-8f;
    // Must be set deliberately by the inference/model contract.
    float headroomSpan = 0.0f;
    // Numerical clipping tolerance in normalized sensor space.
    float clippingEpsilon = 0.0f;

    bool valid() const noexcept {
        return std::isfinite(logSigmaFloor) && logSigmaFloor > 0.0f &&
               std::isfinite(headroomSpan) && headroomSpan > 0.0f && headroomSpan <= 1.0f &&
               std::isfinite(clippingEpsilon) && clippingEpsilon >= 0.0f && clippingEpsilon < 1.0f;
    }
};

struct NormalizedRawSample {
    float value = 0.0f;
    bool valid = false;
};

inline NormalizedRawSample normalizeRawSample(float rawSample, float blackLevel, float whiteLevel) noexcept {
    NormalizedRawSample out{};
    if (!std::isfinite(rawSample) || !std::isfinite(blackLevel) || !std::isfinite(whiteLevel) ||
        whiteLevel <= blackLevel) {
        return out;
    }
    out.value = std::clamp((rawSample - blackLevel) / (whiteLevel - blackLevel), 0.0f, 1.0f);
    out.valid = true;
    return out;
}

inline float varianceAfterGain(float varianceBeforeGain, float gain) noexcept {
    if (!std::isfinite(varianceBeforeGain) || varianceBeforeGain < 0.0f ||
        !std::isfinite(gain) || gain <= 0.0f) {
        return std::numeric_limits<float>::quiet_NaN();
    }
    return varianceBeforeGain * gain * gain;
}

inline float sigmaAfterGain(float sigmaBeforeGain, float gain) noexcept {
    if (!std::isfinite(sigmaBeforeGain) || sigmaBeforeGain < 0.0f ||
        !std::isfinite(gain) || gain <= 0.0f) {
        return std::numeric_limits<float>::quiet_NaN();
    }
    return sigmaBeforeGain * gain;
}

inline float headroomForNormalizedSignal(float normalizedSignal, float headroomSpan) noexcept {
    if (!std::isfinite(normalizedSignal) || !std::isfinite(headroomSpan) || headroomSpan <= 0.0f) {
        return std::numeric_limits<float>::quiet_NaN();
    }
    return std::clamp((1.0f - normalizedSignal) / headroomSpan, 0.0f, 1.0f);
}

inline bool normalizedSampleIsClipped(float normalizedSignal, float clippingEpsilon) noexcept {
    if (!std::isfinite(normalizedSignal) || !std::isfinite(clippingEpsilon) ||
        clippingEpsilon < 0.0f || clippingEpsilon >= 1.0f) {
        return true;
    }
    return normalizedSignal >= (1.0f - clippingEpsilon);
}

struct SpectraNeuralGlobalConditioning {
    std::uint32_t schemaVersion = kSpectraNeuralConditioningSchemaVersion;
    NeuralFramePhysicsContext frame{};
    float conservativeMetadataTrust = 0.0f;
    float noiseModelTrust = 0.0f;
    float blackLevelTrust = 0.0f;
    float remainingLscTrust = 0.0f;
    bool lensShadingAlreadyApplied = false;
    bool remainingLscExpected = false;
    StructuredNoiseEvidence structuredNoise{};

    bool valid() const noexcept {
        return schemaVersion == kSpectraNeuralConditioningSchemaVersion && frame.valid() &&
               finiteUnit(conservativeMetadataTrust) && finiteUnit(noiseModelTrust) &&
               finiteUnit(blackLevelTrust) && finiteUnit(remainingLscTrust) &&
               structuredNoise.valid();
    }
};

inline SpectraNeuralGlobalConditioning buildGlobalConditioning(
        const SpectraCoreSnapshot& snapshot,
        const NeuralFramePhysicsContext& frame) noexcept {
    SpectraNeuralGlobalConditioning out{};
    out.frame = frame;
    out.conservativeMetadataTrust = snapshot.metadataTrust.conservativeConditioningTrust();
    out.noiseModelTrust = snapshot.noise.trust;
    out.blackLevelTrust = snapshot.blackResidual.trust;
    out.remainingLscTrust = snapshot.remainingLsc.mapTrust;
    out.lensShadingAlreadyApplied = snapshot.remainingLsc.lensShadingAlreadyApplied;
    out.remainingLscExpected = snapshot.remainingLsc.remainingCorrectionExpected;
    out.structuredNoise = snapshot.structuredNoise;
    return out;
}

// Exact C++ representation of the frozen 18-value training/runtime global vector.
// Formula and order must remain identical to spectra_train.contracts.physics_global_vector.
inline std::array<float, kGlobalConditioningValueCount> encodeGlobalConditioning(
        const SpectraNeuralGlobalConditioning& global) noexcept {
    std::array<float, kGlobalConditioningValueCount> values{};
    values[globalConditioningIndex(GlobalConditioningField::LogExposureSeconds)] =
            static_cast<float>(std::log(std::max(global.frame.exposureTimeSeconds, 1.0e-12)));
    values[globalConditioningIndex(GlobalConditioningField::LogAnalogGain)] =
            std::log(std::max(global.frame.analogGain, 1.0e-8f));
    values[globalConditioningIndex(GlobalConditioningField::LogDigitalGain)] =
            std::log(std::max(global.frame.digitalGain, 1.0e-8f));
    values[globalConditioningIndex(GlobalConditioningField::BitDepthOver32)] =
            static_cast<float>(global.frame.bitDepth) / 32.0f;
    values[globalConditioningIndex(GlobalConditioningField::NoiseModelTrust)] =
            global.noiseModelTrust;
    values[globalConditioningIndex(GlobalConditioningField::BlackLevelTrust)] =
            global.blackLevelTrust;
    values[globalConditioningIndex(GlobalConditioningField::RemainingLscTrust)] =
            global.remainingLscTrust;
    values[globalConditioningIndex(GlobalConditioningField::RowPeriodicity)] =
            global.structuredNoise.rowPeriodicity;
    values[globalConditioningIndex(GlobalConditioningField::ColumnPeriodicity)] =
            global.structuredNoise.columnPeriodicity;
    values[globalConditioningIndex(GlobalConditioningField::FixedPattern)] =
            global.structuredNoise.fixedPattern;
    values[globalConditioningIndex(GlobalConditioningField::DsnuLike)] =
            global.structuredNoise.dsnuLike;
    values[globalConditioningIndex(GlobalConditioningField::PrnuLike)] =
            global.structuredNoise.prnuLike;
    values[globalConditioningIndex(GlobalConditioningField::LowFrequencyResidual)] =
            global.structuredNoise.lowFrequencyResidual;
    values[globalConditioningIndex(GlobalConditioningField::LowFrequencyChroma)] =
            global.structuredNoise.lowFrequencyChroma;
    values[globalConditioningIndex(GlobalConditioningField::ChannelImbalance)] =
            global.structuredNoise.channelImbalance;
    values[globalConditioningIndex(GlobalConditioningField::SpatialBlackDrift)] =
            global.structuredNoise.spatialBlackDrift;
    values[globalConditioningIndex(GlobalConditioningField::RareReadoutPattern)] =
            global.structuredNoise.rareReadoutPattern;
    values[globalConditioningIndex(GlobalConditioningField::StructuredConfidence)] =
            global.structuredNoise.confidence;
    return values;
}

struct SpatialConditioningCell {
    std::array<float, kBaseSpatialConditioningChannelCount> values{};
    std::array<std::uint8_t, 4> clipped{{0u, 0u, 0u, 0u}};
    bool valid = false;
};

inline float conservativeSpatialTrust(const SpectraCoreSnapshot& snapshot) noexcept {
    float trust = std::min(snapshot.noise.trust, snapshot.metadataTrust.conservativeConditioningTrust());
    trust = std::min(trust, snapshot.blackResidual.trust);
    if (snapshot.remainingLsc.remainingCorrectionExpected || snapshot.remainingLsc.hasSpatialGainMap) {
        trust = std::min(trust, snapshot.remainingLsc.mapTrust);
    }
    return std::clamp(trust, 0.0f, 1.0f);
}

// Builds the base 14-channel conditioning vector for one packed Bayer cell.
// normalizedRaw is measured scene signal and is copied, never altered. When no
// remaining LSC map exists, LSC condition is exact identity (1.0) by contract.
inline SpatialConditioningCell buildSpatialConditioningCell(
        const SpectraCoreSnapshot& snapshot,
        const std::array<float, 4>& normalizedRaw,
        const std::array<float, 4>& sampledRemainingLscGain,
        const SpectraNeuralConditioningConfig& config) noexcept {
    SpatialConditioningCell out{};
    if (!snapshot.neuralConditioningReady() || !config.valid()) {
        return out;
    }

    float minimumHeadroom = 1.0f;
    for (std::size_t i = 0; i < 4; ++i) {
        const float x = normalizedRaw[i];
        if (!std::isfinite(x) || x < 0.0f || x > 1.0f) {
            return out;
        }

        const auto channel = static_cast<CanonicalCfaChannel>(i);
        const float sigma = snapshot.noise.sigma(channel, x);
        if (!std::isfinite(sigma) || sigma < 0.0f) {
            return out;
        }

        const float gain = snapshot.remainingLsc.hasSpatialGainMap ? sampledRemainingLscGain[i] : 1.0f;
        if (!std::isfinite(gain) || gain <= 0.0f) {
            return out;
        }

        out.values[i] = x;
        out.values[4u + i] = std::log(std::max(sigma, config.logSigmaFloor));
        out.values[8u + i] = gain;
        out.clipped[i] = normalizedSampleIsClipped(x, config.clippingEpsilon) ? 1u : 0u;

        const float headroom = headroomForNormalizedSignal(x, config.headroomSpan);
        if (!std::isfinite(headroom)) {
            return out;
        }
        minimumHeadroom = std::min(minimumHeadroom, headroom);
    }

    out.values[conditioningChannelIndex(SpatialConditioningChannel::MetadataTrust)] =
        conservativeSpatialTrust(snapshot);
    out.values[conditioningChannelIndex(SpatialConditioningChannel::Headroom)] = minimumHeadroom;
    out.valid = true;
    return out;
}

} // namespace bncam::spectra::neural
