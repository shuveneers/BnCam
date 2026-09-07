#pragma once

#include "VulkanVmaIntegration.h"
#include "VulkanSpectraResidentPreDemosaicBackend.h"

#include <vulkan/vulkan.h>

#include <array>
#include <cstddef>
#include <cstdint>
#include <string>
#include <vector>

namespace bncam::vulkan {

// N006E temporary compile facade.
// The classical Pass2/Pass3 resident chroma/banding pixel implementation and shader
// are physically removed. This type surface is retained only until N006F removes
// the obsolete VulkanRuntime API. Every execution entrypoint is fail-closed.

struct SpectraResidentPass2Request {
    const float* mosaicData = nullptr;
    std::uint64_t residentInputGeneration = 0;
    bool deferFullFrameReadback = false;
    std::uint32_t frameWidth = 0;
    std::uint32_t frameHeight = 0;
    std::size_t rowStrideFloats = 0;
    std::uint32_t cfaPattern = 0;

    std::array<float, 4> effectiveS{0.0f, 0.0f, 0.0f, 0.0f};
    std::array<float, 4> effectiveO{0.0f, 0.0f, 0.0f, 0.0f};
    float blendStrength = 0.0f;
    float isoAuthority = 0.0f;
    float modelConfidence = 0.0f;

    bool fineEnabled = false;
    float fineAuthorityScale = 0.0f;
    float fineMaximumCorrectionScale = 0.0f;
    float fineModelConfidence = 0.0f;
    float fineVisibleChromaAmplification = 1.0f;
    float fineRedAuthorityMultiplier = 1.0f;
    float fineBlueAuthorityMultiplier = 1.0f;

    bool midEnabled = false;
    float midAuthorityScale = 0.0f;
    float midMaximumCorrectionScale = 0.0f;
    float midModelConfidence = 0.0f;
    float midVisibleChromaAmplification = 1.0f;
    float midRedAuthorityMultiplier = 1.0f;
    float midBlueAuthorityMultiplier = 1.0f;

    const float* lensShadingMap = nullptr;
    std::uint32_t lensShadingColumns = 0;
    std::uint32_t lensShadingRows = 0;
    std::uint64_t lensShadingGenerationId = 0;

    std::uint32_t noRegretGridCols = 0;
    std::uint32_t noRegretGridRows = 0;
    std::uint32_t noRegretTileWidth = 0;
    std::uint32_t noRegretTileHeight = 0;
    std::vector<SpectraPass1BeforeTile> beforeTiles;
    float targetFloorScale = 1.0f;
    float minimumResidualRatio = 0.70f;
    float detailRetentionFloor = 0.95f;
    float combinedNoisePressure = 0.0f;
};

struct SpectraResidentPass3Request {
    const float* mosaicData = nullptr;
    std::uint64_t residentInputGeneration = 0;
    bool deferFullFrameReadback = false;
    std::uint32_t frameWidth = 0;
    std::uint32_t frameHeight = 0;
    std::size_t rowStrideFloats = 0;
    std::uint32_t cfaPattern = 0;

    std::array<float, 4> effectiveS{0.0f, 0.0f, 0.0f, 0.0f};
    std::array<float, 4> effectiveO{0.0f, 0.0f, 0.0f, 0.0f};

    bool applyRowBanding = false;
    bool applyColumnBanding = false;
    bool applyLowFrequencyChroma = false;
    float bandingAuthority = 0.0f;
    float chromaAuthority = 0.0f;
    float lowFrequencyConfidence = 0.0f;
    float lowAuthorityScale = 0.0f;
    float lowMaximumCorrectionScale = 0.0f;
    float lowModelConfidence = 0.0f;
    float lowVisibleChromaAmplification = 1.0f;
    float lowRedAuthorityMultiplier = 1.0f;
    float lowBlueAuthorityMultiplier = 1.0f;

    std::array<std::vector<float>, 4> rowProfileByChannel;
    std::array<std::vector<float>, 4> columnProfileByChannel;
    std::vector<float> lowFreqRGrid;
    std::vector<float> lowFreqBGrid;
    std::vector<float> lowFreqRConfidenceGrid;
    std::vector<float> lowFreqBConfidenceGrid;
    std::uint32_t chromaGridCols = 0;
    std::uint32_t chromaGridRows = 0;

    std::uint32_t noRegretGridCols = 0;
    std::uint32_t noRegretGridRows = 0;
    std::uint32_t noRegretTileWidth = 0;
    std::uint32_t noRegretTileHeight = 0;
    std::vector<SpectraPass1BeforeTile> beforeTiles;
    float targetFloorScale = 1.0f;
    float minimumResidualRatio = 0.70f;
    float detailRetentionFloor = 0.95f;
    float combinedNoisePressure = 0.0f;
    float modelConfidence = 0.0f;
};

struct SpectraResidentChromaTelemetry {
    std::uint64_t fineCandidatePixelCount = 0;
    std::uint64_t fineChangedPixelCount = 0;
    std::uint64_t fineStructureRejectedPixelCount = 0;
    float fineMaximumCorrection = 0.0f;
    std::uint64_t midCandidatePixelCount = 0;
    std::uint64_t midChangedPixelCount = 0;
    std::uint64_t midStructureRejectedPixelCount = 0;
    float midMaximumCorrection = 0.0f;
    std::uint64_t profiledStrongFineBlendQuadCount = 0;
    std::uint64_t profiledColorEdgeProtectedQuadCount = 0;
    std::uint64_t profiledHeavyFineCleanQuadCount = 0;
    std::uint64_t pass3ChangedPixelCount = 0;
    std::uint64_t pass3LowCandidatePixelCount = 0;
    std::uint64_t pass3LowStructureRejectedPixelCount = 0;
    std::uint64_t pass3LowChromaChangedPixelCount = 0;
    float pass3MaximumLowChromaShift = 0.0f;
};

struct SpectraResidentChromaResult {
    bool attempted = false;
    bool success = false;
    bool pipelineAvailable = false;
    bool timestampQueryUsed = false;
    bool persistentBufferReuseHit = false;
    bool persistentBufferReallocated = false;
    bool gpuNoRegretBlendUsed = false;
    bool cpuFullFrameCandidateReadbackAvoided = false;
    bool residentInputUsed = false;
    bool fullFrameReadbackDeferred = false;
    std::uint64_t residentOutputGeneration = 0;
    std::uint64_t residentOutputBytes = 0;
    std::vector<float> outputMosaic;
    SpectraResidentChromaTelemetry telemetry{};
    SpectraPass1NoRegretGpuResult noRegret{};
    float inputPackingMs = 0.0f;
    float auxiliaryUploadMs = 0.0f;
    float fineKernelMs = 0.0f;
    float midKernelMs = 0.0f;
    float pass3KernelMs = 0.0f;
    float tileStatisticsKernelMs = 0.0f;
    float noRegretCpuDecisionMs = 0.0f;
    float noRegretBlendKernelMs = 0.0f;
    float gpuKernelMs = 0.0f;
    float synchronizationMs = 0.0f;
    float readbackMs = 0.0f;
    float transferAndSyncMs = 0.0f;
    float totalMs = 0.0f;
    std::uint64_t inputBytes = 0;
    std::uint64_t auxiliaryBytes = 0;
    std::uint64_t tileStatisticsBytes = 0;
    std::uint64_t acceptanceBytes = 0;
    std::uint64_t persistentResidentBytes = 0;
    std::uint64_t persistentAllocationGeneration = 0;
    std::string status = "NOT_RUN";
    std::string failureReason;
};

class VulkanSpectraResidentChromaBackend final {
public:
    VulkanSpectraResidentChromaBackend() = default;
    ~VulkanSpectraResidentChromaBackend() = default;

    VulkanSpectraResidentChromaBackend(const VulkanSpectraResidentChromaBackend&) = delete;
    VulkanSpectraResidentChromaBackend& operator=(const VulkanSpectraResidentChromaBackend&) = delete;

    SpectraResidentChromaResult executePass2(
            VkPhysicalDevice, VkDevice, VkQueue, VkCommandPool,
            VulkanAllocatorOwner&, const SpectraResidentPass2Request&
    ) noexcept {
        return retired();
    }

    SpectraResidentChromaResult executePass2FromResident(
            VkPhysicalDevice, VkDevice, VkQueue, VkCommandPool,
            VulkanAllocatorOwner&, VkBuffer, std::uint64_t,
            const SpectraResidentPass2Request&
    ) noexcept {
        return retired();
    }

    SpectraResidentChromaResult executePass3(
            VkPhysicalDevice, VkDevice, VkQueue, VkCommandPool,
            VulkanAllocatorOwner&, const SpectraResidentPass3Request&
    ) noexcept {
        return retired();
    }

    SpectraResidentChromaResult executePass3FromResident(
            VkPhysicalDevice, VkDevice, VkQueue, VkCommandPool,
            VulkanAllocatorOwner&, VkBuffer, std::uint64_t,
            const SpectraResidentPass3Request&
    ) noexcept {
        return retired();
    }

    bool resolveResidentOutput(
            std::uint64_t,
            VkBuffer& buffer,
            std::uint64_t& bytes,
            std::uint32_t& width,
            std::uint32_t& height
    ) const noexcept {
        buffer = VK_NULL_HANDLE;
        bytes = 0u;
        width = 0u;
        height = 0u;
        return false;
    }

    bool readbackResidentOutput(
            std::uint64_t,
            std::vector<float>& output
    ) noexcept {
        output.clear();
        return false;
    }

    void destroy(VkDevice) noexcept {}
    bool productionKernelConnected() const noexcept { return false; }
    bool pipelineInitialized() const noexcept { return false; }

private:
    static SpectraResidentChromaResult retired() noexcept {
        SpectraResidentChromaResult result{};
        result.attempted = true;
        result.pipelineAvailable = false;
        result.status = "RETIRED_N006E_CLASSICAL_CHROMA_KERNEL";
        result.failureReason = "CLASSICAL_CHROMA_PASS2_PASS3_PIXEL_KERNEL_REMOVED";
        return result;
    }
};

} // namespace bncam::vulkan
