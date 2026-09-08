#pragma once

#include "NeuralRawDenoisePolicy.h"
#include "SpectraNeuralConditioning.h"

#include <cstdint>

namespace bncam::spectra::neural {

constexpr std::uint32_t kNeuralRawDenoiseRequestSchemaVersion = 1;
constexpr std::uint32_t kNeuralRawDenoiseResultSchemaVersion = 1;

enum class NeuralResourceKind : std::uint8_t {
    Unbound = 0,
    VulkanBuffer = 1,
    VulkanImage = 2,
    AndroidHardwareBuffer = 3,
    BackendOpaqueGpu = 4
};

enum class NeuralElementType : std::uint8_t {
    Unknown = 0,
    Fp16 = 1,
    Fp32 = 2
};

enum class NeuralResourceAccess : std::uint8_t {
    ReadOnly = 0,
    WriteOnly = 1,
    ReadWrite = 2
};

// Backend-neutral GPU/external-resource descriptor. The token is interpreted
// only by the selected backend; this ABI deliberately exposes no CPU image
// pointer and therefore does not create a hidden full-frame CPU fallback path.
struct NeuralResourceView {
    NeuralResourceKind kind = NeuralResourceKind::Unbound;
    NeuralElementType elementType = NeuralElementType::Unknown;
    NeuralResourceAccess access = NeuralResourceAccess::ReadOnly;
    std::uint64_t token = 0;
    std::uint64_t byteOffset = 0;
    std::uint32_t width = 0;
    std::uint32_t height = 0;
    std::uint32_t channels = 0;
    std::uint32_t rowStrideBytes = 0;

    bool bound() const noexcept {
        return kind != NeuralResourceKind::Unbound && token != 0u;
    }

    bool valid() const noexcept {
        if (!bound() || elementType == NeuralElementType::Unknown || width == 0u || height == 0u ||
            channels == 0u || rowStrideBytes == 0u) {
            return false;
        }
        return true;
    }
};

struct NeuralRawDenoiseRequest {
    std::uint32_t schemaVersion = kNeuralRawDenoiseRequestSchemaVersion;
    SpectraCoreSnapshot core{};
    NeuralFramePhysicsContext framePhysics{};
    SpectraNeuralConditioningConfig conditioningConfig{};
    NeuralDenoiseControls controls{};

    NeuralResourceView packedNormalizedRawInput{};
    NeuralResourceView remainingLscMap{};
    NeuralResourceView cleanPackedRawOutput{};
    NeuralResourceView posteriorVarianceOutput{};

    bool residualDebugRequested = false;
    NeuralResourceView boundedResidualDebugOutput{};

    bool validResourceShape() const noexcept {
        if (!core.neuralConditioningReady() || !framePhysics.valid() || !conditioningConfig.valid() ||
            !controls.valid() || !packedNormalizedRawInput.valid() || !cleanPackedRawOutput.valid() ||
            !posteriorVarianceOutput.valid()) {
            return false;
        }

        const std::uint32_t packedWidth = core.cfa.packedWidth(core.rawWidth);
        const std::uint32_t packedHeight = core.cfa.packedHeight(core.rawHeight);
        const auto isPackedCfa4 = [packedWidth, packedHeight](const NeuralResourceView& view) noexcept {
            return view.width == packedWidth && view.height == packedHeight && view.channels == 4u;
        };
        if (!isPackedCfa4(packedNormalizedRawInput) || !isPackedCfa4(cleanPackedRawOutput) ||
            !isPackedCfa4(posteriorVarianceOutput)) {
            return false;
        }
        if (packedNormalizedRawInput.access == NeuralResourceAccess::WriteOnly ||
            cleanPackedRawOutput.access == NeuralResourceAccess::ReadOnly ||
            posteriorVarianceOutput.access == NeuralResourceAccess::ReadOnly) {
            return false;
        }

        if (core.remainingLsc.hasSpatialGainMap) {
            if (!remainingLscMap.valid() || remainingLscMap.width != core.remainingLsc.mapWidth ||
                remainingLscMap.height != core.remainingLsc.mapHeight ||
                remainingLscMap.channels != core.remainingLsc.mapChannels ||
                remainingLscMap.access == NeuralResourceAccess::WriteOnly) {
                return false;
            }
        } else if (remainingLscMap.bound()) {
            // Do not accept an undeclared second LSC source.
            return false;
        }

        if (residualDebugRequested) {
            if (!boundedResidualDebugOutput.valid() || !isPackedCfa4(boundedResidualDebugOutput) ||
                boundedResidualDebugOutput.access == NeuralResourceAccess::ReadOnly) {
                return false;
            }
        }
        return true;
    }
};

enum class NeuralBackendStatus : std::uint8_t {
    Completed = 0,
    Bypassed = 1,
    Failed = 2
};

enum class NeuralBackendFailureCode : std::uint16_t {
    None = 0,
    InvalidRequest,
    ResourceImportFailed,
    ModelLoadFailed,
    DispatchFailed,
    SynchronizationFailed,
    NonFiniteModelOutput,
    InvalidPosteriorOutput,
    InternalError
};

struct NeuralRawDenoiseResult {
    std::uint32_t schemaVersion = kNeuralRawDenoiseResultSchemaVersion;
    NeuralBackendStatus status = NeuralBackendStatus::Bypassed;
    NeuralBackendFailureCode failureCode = NeuralBackendFailureCode::None;
    NeuralBypassReason bypassReason = NeuralBypassReason::None;
    bool cleanRawWritten = false;
    bool posteriorVarianceWritten = false;
    bool boundedResidualDebugWritten = false;
};

enum class NeuralPublicationSource : std::uint8_t {
    OriginalInput = 0,
    NeuralOutput = 1
};

// Fail closed: only a complete neural result can become the RAW published to
// downstream LSC/demosaic. Every other state is exact input identity.
inline NeuralPublicationSource selectNeuralPublicationSource(
        const NeuralInvocationDecision& decision,
        const NeuralRawDenoiseResult& result) noexcept {
    if (!decision.runInference) {
        return NeuralPublicationSource::OriginalInput;
    }
    if (result.schemaVersion != kNeuralRawDenoiseResultSchemaVersion ||
        result.status != NeuralBackendStatus::Completed ||
        !result.cleanRawWritten || !result.posteriorVarianceWritten) {
        return NeuralPublicationSource::OriginalInput;
    }
    return NeuralPublicationSource::NeuralOutput;
}

class INeuralRawDenoiseBackend {
public:
    virtual ~INeuralRawDenoiseBackend() = default;
    virtual const char* backendName() const noexcept = 0;
    virtual bool available() const noexcept = 0;
    virtual NeuralRawDenoiseResult run(const NeuralRawDenoiseRequest& request) noexcept = 0;
};

} // namespace bncam::spectra::neural
