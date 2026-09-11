#pragma once

#include "NeuralRawDenoisePolicy.h"
#include "SpectraNeuralConditioning.h"

#include <cstdint>

namespace bncam::spectra::neural {

constexpr std::uint32_t kNeuralRawDenoiseRequestSchemaVersion = 3;
constexpr std::uint32_t kNeuralRawDenoiseResultSchemaVersion = 3;

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
    Fp32 = 2,
    U32 = 3
};

enum class NeuralResourceAccess : std::uint8_t {
    ReadOnly = 0,
    WriteOnly = 1,
    ReadWrite = 2
};

// External producer synchronization is explicit. Resident Vulkan buffers owned
// by BnCam can use None when queue-order + a backend acquire barrier establish
// visibility. An imported AndroidHardwareBuffer must either be known producer-
// complete or provide a borrowed Vulkan semaphore that the backend waits on.
enum class NeuralExternalSyncKind : std::uint8_t {
    None = 0,
    ProducerComplete = 1,
    VulkanSemaphore = 2
};

struct NeuralExternalSync {
    NeuralExternalSyncKind kind = NeuralExternalSyncKind::None;
    std::uint64_t token = 0;

    bool valid() const noexcept {
        if (kind == NeuralExternalSyncKind::VulkanSemaphore) {
            return token != 0u;
        }
        return token == 0u;
    }
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
    NeuralExternalSync externalSync{};

    bool bound() const noexcept {
        return kind != NeuralResourceKind::Unbound && token != 0u;
    }

    bool valid() const noexcept {
        if (!bound() || elementType == NeuralElementType::Unknown || width == 0u || height == 0u ||
            channels == 0u || rowStrideBytes == 0u || !externalSync.valid()) {
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
    // Optional backend-selected GPU staging view. When the primary input is an
    // AndroidHardwareBuffer that cannot be imported with the required Vulkan
    // storage features, the Vulkan backend may consume this BnCam-owned GPU
    // buffer instead. There is deliberately no host/full-frame CPU pointer.
    NeuralResourceView packedNormalizedRawGpuFallback{};
    NeuralResourceView remainingLscMap{};
    NeuralResourceView cleanPackedRawOutput{};
    NeuralResourceView posteriorVarianceOutput{};

    // Future Neural Highlight Reconstruction boundary. The denoiser does not
    // reconstruct clipped signal. When requested it must preserve evidence
    // derived from the ORIGINAL neural input: a packed u32 bit mask (bits 0..3
    // hard-clipped R/G1/G2/B, bits 4..7 near-clipped) and scalar minimum
    // headroom. Partial/full clipping remains inferable without altering RAW.
    bool originalSaturationEvidenceRequested = false;
    NeuralResourceView originalSaturationMaskOutput{};
    NeuralResourceView originalHeadroomEvidenceOutput{};

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
        if (packedNormalizedRawGpuFallback.bound()) {
            if (!packedNormalizedRawGpuFallback.valid() || !isPackedCfa4(packedNormalizedRawGpuFallback) ||
                packedNormalizedRawGpuFallback.access == NeuralResourceAccess::WriteOnly ||
                packedNormalizedRawGpuFallback.kind != NeuralResourceKind::VulkanBuffer) {
                return false;
            }
        }
        if (packedNormalizedRawInput.access == NeuralResourceAccess::WriteOnly ||
            cleanPackedRawOutput.access == NeuralResourceAccess::ReadOnly ||
            posteriorVarianceOutput.access == NeuralResourceAccess::ReadOnly) {
            return false;
        }
        if (packedNormalizedRawInput.kind == NeuralResourceKind::AndroidHardwareBuffer &&
            packedNormalizedRawInput.externalSync.kind == NeuralExternalSyncKind::None) {
            // Direct AHB import must never assume producer completion.
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

        if (originalSaturationEvidenceRequested) {
            if (!originalSaturationMaskOutput.valid() ||
                originalSaturationMaskOutput.width != packedWidth ||
                originalSaturationMaskOutput.height != packedHeight ||
                originalSaturationMaskOutput.channels != 1u ||
                originalSaturationMaskOutput.elementType != NeuralElementType::U32 ||
                originalSaturationMaskOutput.access == NeuralResourceAccess::ReadOnly ||
                !originalHeadroomEvidenceOutput.valid() ||
                originalHeadroomEvidenceOutput.width != packedWidth ||
                originalHeadroomEvidenceOutput.height != packedHeight ||
                originalHeadroomEvidenceOutput.channels != 1u ||
                (originalHeadroomEvidenceOutput.elementType != NeuralElementType::Fp16 &&
                 originalHeadroomEvidenceOutput.elementType != NeuralElementType::Fp32) ||
                originalHeadroomEvidenceOutput.access == NeuralResourceAccess::ReadOnly) {
                return false;
            }
        } else if (originalSaturationMaskOutput.bound() || originalHeadroomEvidenceOutput.bound()) {
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

inline const char* neuralBackendFailureCodeName(NeuralBackendFailureCode code) noexcept {
    switch (code) {
        case NeuralBackendFailureCode::None: return "none";
        case NeuralBackendFailureCode::InvalidRequest: return "invalid_request";
        case NeuralBackendFailureCode::ResourceImportFailed: return "resource_import_failed";
        case NeuralBackendFailureCode::ModelLoadFailed: return "model_load_failed";
        case NeuralBackendFailureCode::DispatchFailed: return "dispatch_failed";
        case NeuralBackendFailureCode::SynchronizationFailed: return "synchronization_failed";
        case NeuralBackendFailureCode::NonFiniteModelOutput: return "non_finite_model_output";
        case NeuralBackendFailureCode::InvalidPosteriorOutput: return "invalid_posterior_output";
        case NeuralBackendFailureCode::InternalError: return "internal_error";
        default: return "unknown";
    }
}

struct NeuralRawDenoiseResult {
    std::uint32_t schemaVersion = kNeuralRawDenoiseResultSchemaVersion;
    NeuralBackendStatus status = NeuralBackendStatus::Bypassed;
    NeuralBackendFailureCode failureCode = NeuralBackendFailureCode::None;
    NeuralBypassReason bypassReason = NeuralBypassReason::None;
    bool cleanRawWritten = false;
    bool posteriorVarianceWritten = false;
    bool boundedResidualDebugWritten = false;
    bool originalSaturationMaskWritten = false;
    bool originalHeadroomEvidenceWritten = false;
    std::uint32_t dispatchedKernelCount = 0u;
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
