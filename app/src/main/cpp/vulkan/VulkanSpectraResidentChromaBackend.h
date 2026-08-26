#pragma once

#include "VulkanVmaIntegration.h"
#include "VulkanSpectraResidentPreDemosaicBackend.h"

#include <vulkan/vulkan.h>

#include <array>
#include <cstddef>
#include <cstdint>
#include <mutex>
#include <string>
#include <vector>

namespace bncam::vulkan {

/**
 * Milestone 8H-C GPU-primary Pass 2 request.
 *
 * The full-resolution fine + mid chroma candidate remains on the GPU. Only the
 * compact No-Regret tile statistics are read by the CPU; the full-frame blend
 * and final output stay in Vulkan until one final readback.
 */
struct SpectraResidentPass2Request {
    const float* mosaicData = nullptr;
    // Opaque 8H-G generation resolved by VulkanRuntime; no VkBuffer leaves the runtime.
    std::uint64_t residentInputGeneration = 0;
    // Keep the Pass-2 mosaic resident for Pass-3 planning/finalize instead of
    // materializing the full frame on the host. Compact telemetry still returns.
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

/**
 * Milestone 8H-C GPU-primary Pass 3 request.
 *
 * The CPU still estimates the compact row/column and low-frequency fields in
 * this milestone; all full-frame application and No-Regret blending is Vulkan.
 */
struct SpectraResidentPass3Request {
    const float* mosaicData = nullptr;
    // Opaque Pass-2 generation resolved inside VulkanRuntime.
    std::uint64_t residentInputGeneration = 0;
    // Keep Pass-3 final mosaic on-device for direct demosaic handoff.
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
            VkPhysicalDevice physicalDevice,
            VkDevice device,
            VkQueue computeQueue,
            VkCommandPool commandPool,
            VulkanAllocatorOwner& allocatorOwner,
            const SpectraResidentPass2Request& request
    ) noexcept;

    // Runtime-internal resident handoff from Pass 1. Vulkan handles never cross IspCore/JNI.
    SpectraResidentChromaResult executePass2FromResident(
            VkPhysicalDevice physicalDevice,
            VkDevice device,
            VkQueue computeQueue,
            VkCommandPool commandPool,
            VulkanAllocatorOwner& allocatorOwner,
            VkBuffer residentInputBuffer,
            std::uint64_t residentInputBytes,
            const SpectraResidentPass2Request& request
    ) noexcept;

    SpectraResidentChromaResult executePass3(
            VkPhysicalDevice physicalDevice,
            VkDevice device,
            VkQueue computeQueue,
            VkCommandPool commandPool,
            VulkanAllocatorOwner& allocatorOwner,
            const SpectraResidentPass3Request& request
    ) noexcept;

    SpectraResidentChromaResult executePass3FromResident(
            VkPhysicalDevice physicalDevice,
            VkDevice device,
            VkQueue computeQueue,
            VkCommandPool commandPool,
            VulkanAllocatorOwner& allocatorOwner,
            VkBuffer residentInputBuffer,
            std::uint64_t residentInputBytes,
            const SpectraResidentPass3Request& request
    ) noexcept;

    bool resolveResidentOutput(
            std::uint64_t generation,
            VkBuffer& buffer,
            std::uint64_t& bytes,
            std::uint32_t& width,
            std::uint32_t& height
    ) const noexcept;

    /** Explicit fail-safe materialization only; normal resident success must not call this. */
    bool readbackResidentOutput(
            std::uint64_t generation,
            std::vector<float>& output
    ) noexcept;

    void destroy(VkDevice device) noexcept;
    bool productionKernelConnected() const noexcept;
    bool pipelineInitialized() const noexcept;

private:
    struct PersistentBuffer {
        VkBuffer buffer = VK_NULL_HANDLE;
        VmaAllocation allocation = nullptr;
        void* mapped = nullptr;
        std::uint64_t capacityBytes = 0;
    };

    bool initializeLocked(VkDevice device, VkCommandPool commandPool,
                          std::string& failureReason) noexcept;
    bool ensureBufferLocked(VmaAllocator allocator, std::uint64_t bytes,
                            std::uint32_t hostAccess, PersistentBuffer& buffer,
                            bool& reallocated, std::string& failureReason) noexcept;
    void updateDescriptorSetLocked(VkDevice device, VkBuffer inputOverride = VK_NULL_HANDLE) noexcept;
    SpectraResidentChromaResult executePass2Internal(
            VkPhysicalDevice physicalDevice, VkDevice device, VkQueue computeQueue, VkCommandPool commandPool,
            VulkanAllocatorOwner& allocatorOwner, VkBuffer residentInputBuffer,
            std::uint64_t residentInputBytes, const SpectraResidentPass2Request& request
    ) noexcept;
    SpectraResidentChromaResult executePass3Internal(
            VkPhysicalDevice physicalDevice, VkDevice device, VkQueue computeQueue, VkCommandPool commandPool,
            VulkanAllocatorOwner& allocatorOwner, VkBuffer residentInputBuffer,
            std::uint64_t residentInputBytes, const SpectraResidentPass3Request& request
    ) noexcept;
    void destroyBuffersLocked() noexcept;
    void destroyLocked(VkDevice device) noexcept;

    mutable std::mutex mutex_;
    bool initialized_ = false;
    VkDevice initializedDevice_ = VK_NULL_HANDLE;
    VkCommandPool initializedCommandPool_ = VK_NULL_HANDLE;
    VkDescriptorSetLayout descriptorSetLayout_ = VK_NULL_HANDLE;
    VkPipelineLayout pipelineLayout_ = VK_NULL_HANDLE;
    VkShaderModule shaderModule_ = VK_NULL_HANDLE;
    VkPipeline pipeline_ = VK_NULL_HANDLE;
    VkDescriptorPool descriptorPool_ = VK_NULL_HANDLE;
    VkDescriptorSet descriptorSet_ = VK_NULL_HANDLE;
    VkCommandBuffer commandBuffer_ = VK_NULL_HANDLE;
    VkFence fence_ = VK_NULL_HANDLE;
    VkQueryPool queryPool_ = VK_NULL_HANDLE;
    VmaAllocator allocator_ = nullptr;
    PersistentBuffer input_;
    PersistentBuffer candidate_;
    PersistentBuffer scratch_;
    PersistentBuffer lensShading_;
    PersistentBuffer auxiliary_;
    PersistentBuffer tileStatistics_;
    PersistentBuffer acceptance_;
    PersistentBuffer telemetry_;
    [[maybe_unused]] std::uint64_t allocationGeneration_ = 0;
    std::uint64_t lensShadingGenerationId_ = 0;
    std::uint64_t lensShadingGenerationBytes_ = 0;
    std::uint64_t residentOutputGeneration_ = 0;
    std::uint64_t residentOutputBytes_ = 0;
    std::uint32_t residentOutputWidth_ = 0;
    std::uint32_t residentOutputHeight_ = 0;
    bool descriptorBindingsInitialized_ = false;
};

} // namespace bncam::vulkan
