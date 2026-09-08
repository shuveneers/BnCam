#pragma once

#include "NeuralRawDenoiseBackend.h"

#include <array>
#include <cstddef>
#include <cstdint>

namespace bncam::spectra::neural {

constexpr std::uint32_t kSpectraNeuralTelemetrySchemaVersion = 1;

enum class NeuralBackendKind : std::uint8_t {
    Unknown = 0,
    Vulkan = 1,
    FutureNpu = 2,
    OfflineReference = 3
};

enum class PosteriorCalibrationState : std::uint8_t {
    Unknown = 0,
    Uncalibrated = 1,
    Calibrated = 2,
    Rejected = 3
};

enum class ModelIntegrityStatus : std::uint8_t {
    Unknown = 0,
    Verified = 1,
    Missing = 2,
    HashMismatch = 3,
    SchemaMismatch = 4,
    Corrupt = 5
};

struct NeuralTelemetryPercentiles {
    float p10 = 0.0f;
    float p50 = 0.0f;
    float p90 = 0.0f;
};

struct SpectraNeuralInputTelemetry {
    std::array<float, 4> shotS{{0.0f, 0.0f, 0.0f, 0.0f}};
    std::array<float, 4> readO{{0.0f, 0.0f, 0.0f, 0.0f}};

    MetadataTrustState metadataTrust{};
    float conservativeMetadataTrust = 0.0f;
    float blackLevelTrust = 0.0f;

    float remainingLscMin = 1.0f;
    float remainingLscMedian = 1.0f;
    float remainingLscMax = 1.0f;

    NeuralTelemetryPercentiles sigma{};
    NeuralTelemetryPercentiles snr{};
};

struct SpectraNeuralModelTelemetry {
    // Fixed storage prevents telemetry ownership from depending on heap/string
    // lifetime. Producers must NUL-terminate when writing textual identities.
    std::array<char, 48> modelName{};
    std::array<char, 32> modelVersion{};
    std::array<std::uint8_t, 32> modelSha256{};

    NeuralBackendKind backend = NeuralBackendKind::Unknown;
    NeuralElementType precision = NeuralElementType::Unknown;
    std::uint32_t tileCount = 0;
    std::uint64_t inferenceTimeMicros = 0;
    std::uint64_t peakAllocationBytes = 0;

    NeuralDenoiseControls controls{};

    float correctionRmsSigma = 0.0f;
    float correctionP95Sigma = 0.0f;
    float correctionP99Sigma = 0.0f;
    float fractionAbove1Sigma = 0.0f;
    float fractionAbove2Sigma = 0.0f;
    float fractionAbove3Sigma = 0.0f;
    float fractionAtHardLimit = 0.0f;
};

struct SpectraNeuralBiasTelemetry {
    // Mean applied correction in normalized RAW units, canonical R/G1/G2/B.
    std::array<float, 4> meanCorrection{{0.0f, 0.0f, 0.0f, 0.0f}};
};

struct SpectraNeuralPosteriorTelemetry {
    NeuralTelemetryPercentiles posteriorToInputVarianceRatio{};
    float fractionPosteriorAboveInput = 0.0f;
    PosteriorCalibrationState calibration = PosteriorCalibrationState::Unknown;
};

struct SpectraNeuralSafetyTelemetry {
    std::uint64_t clippedSampleCount = 0;
    std::uint64_t clippedSamplesTouched = 0;
    float oodScore = 0.0f;
    NeuralBypassReason bypassReason = NeuralBypassReason::None;
    ModelIntegrityStatus modelIntegrity = ModelIntegrityStatus::Unknown;
    NeuralBackendStatus backendStatus = NeuralBackendStatus::Bypassed;
    NeuralBackendFailureCode backendFailure = NeuralBackendFailureCode::None;
};

// One capture-level telemetry value object. It observes the neural stage; it
// has no image resource and no mechanism to change denoise authority.
struct SpectraNeuralTelemetry {
    std::uint32_t schemaVersion = kSpectraNeuralTelemetrySchemaVersion;
    std::uint64_t frameId = 0;
    bool recordComplete = false;

    SpectraNeuralInputTelemetry input{};
    SpectraNeuralModelTelemetry neural{};
    SpectraNeuralBiasTelemetry bias{};
    SpectraNeuralPosteriorTelemetry posterior{};
    SpectraNeuralSafetyTelemetry safety{};
};

inline SpectraNeuralInputTelemetry makeInputTelemetry(const SpectraCoreSnapshot& snapshot) noexcept {
    SpectraNeuralInputTelemetry out{};
    out.shotS = snapshot.noise.shotS;
    out.readO = snapshot.noise.readO;
    out.metadataTrust = snapshot.metadataTrust;
    out.conservativeMetadataTrust = snapshot.metadataTrust.conservativeConditioningTrust();
    out.blackLevelTrust = snapshot.blackResidual.trust;
    out.remainingLscMin = snapshot.remainingLsc.minGain;
    out.remainingLscMedian = snapshot.remainingLsc.medianGain;
    out.remainingLscMax = snapshot.remainingLsc.maxGain;
    return out;
}

inline SpectraNeuralSafetyTelemetry makeBypassSafetyTelemetry(
        NeuralBypassReason reason,
        ModelIntegrityStatus integrity = ModelIntegrityStatus::Unknown) noexcept {
    SpectraNeuralSafetyTelemetry out{};
    out.bypassReason = reason;
    out.modelIntegrity = integrity;
    out.backendStatus = NeuralBackendStatus::Bypassed;
    return out;
}

} // namespace bncam::spectra::neural
