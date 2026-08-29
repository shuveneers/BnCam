#pragma once

#include "VulkanVmaIntegration.h"

#include <vulkan/vulkan.h>

#include <array>
#include <cstddef>
#include <cstdint>
#include <mutex>
#include <string>
#include <vector>

namespace bncam::vulkan {

struct SpectraResidentPostDemosaicRequest {
    const float* rgbData = nullptr;
    std::uint32_t frameWidth = 0;
    std::uint32_t frameHeight = 0;
    std::size_t rowStrideFloats = 0;
    std::uint32_t inputOriginY = 0;
    std::uint32_t inputRows = 0;
    std::uint32_t intermediateOriginY = 0;
    std::uint32_t intermediateRows = 0;
    std::uint32_t outputOriginY = 0;
    std::uint32_t outputRows = 0;
    const float* spatialSigma = nullptr;
    std::uint32_t gridWidth = 0;
    std::uint32_t gridHeight = 0;
    float meanSpatialSigma = 0.0f;

    float appliedLumaSigma = 0.0f;
    float lumaRangeThresholdMean = 0.0f;
    float chromaRangeThresholdMean = 0.0f;
    float outerRingAuthority = 0.0f;
    bool spectraNoiseActive = false;
    bool visibleChromaEnabled = false;
    float profileNrLuminance = 0.0f;
    float profileNrLuminanceDetail = 0.5f;
    float profileNrLuminanceContrast = 0.0f;
    float profileNrColor = 0.0f;
    float profileNrColorDetail = 0.5f;
    float profileNrColorSmoothness = 0.5f;
    float chromaNrStrength = 0.0f;
    float chromaUserScale = 1.0f;
    float downstreamChromaAuthority = 1.0f;
    float noiseModelMultiplier = 1.0f;
    float configuredDynamicIsoCoeff = 0.0f;
    float downstreamLumaAuthority = 1.0f;
    float profileSpectraLuma = 0.0f;
    float profileSpectraDetail = 0.0f;

    // Lightroom Detail controls are evaluated in mode 1 after spatial NR. They are
    // multiplexed into mode-0-only push slots before the mode-1 dispatch so the
    // Vulkan 128-byte minimum push-constant contract is not expanded.
    float profileDetailAmount = 0.40f;
    float profileDetailRadius = 1.00f;
    float profileDetailDetail = 0.25f;
    float profileDetailMasking = 0.00f;

    float visibleSigmaY = 0.0f;
    float visibleAuthority = 0.0f;
    float visibleMaximumCorrection = 0.0f;
    // Phase 13: when visible chroma is disabled, a positive amount requests the preserved
    // legacy SPECTRA-off edge-aware sharpening in the resident publication domain.
    float legacySharpenAmount = 0.0f;
    float inverse00 = 0.0f;
    float inverse01 = 0.0f;
    float inverse11 = 0.0f;
    std::uint64_t generationId = 0;

    // FASE 15: production publication is packed BGR8/sRGB. The false value is retained only
    // as an explicit typed FP32 fallback/debug path; it is never selected implicitly.
    bool packedBgr8Publication = true;

    // 8H-K: optional device-resident RGB input owned by an upstream Vulkan stage.
    // When non-null, rgbData is ignored and no full-frame CPU upload is performed.
    VkBuffer residentInputBuffer = VK_NULL_HANDLE;
    std::uint64_t residentInputBytes = 0u;
};

struct SpectraResidentPostDemosaicResult {
    bool attempted = false;
    bool success = false;
    bool pipelineAvailable = false;
    bool timestampQueryUsed = false;
    bool persistentBufferReuseHit = false;
    bool persistentBufferReallocated = false;
    // Legacy typed fallback storage. Production FASE-15 publication uses outputMappedPointer.
    std::vector<float> outputRgb;
    const void* outputMappedPointer = nullptr;
    std::array<std::uint32_t, 16> spatialCounters{};
    std::array<std::uint32_t, 16> visibleCounters{};
    float inputPackingMs = 0.0f;
    float spatialKernelMs = 0.0f;
    float visibleKernelMs = 0.0f;
    float gpuKernelMs = 0.0f;
    float synchronizationMs = 0.0f;
    float readbackMs = 0.0f;
    float totalMs = 0.0f;
    float transferAndSyncMs = 0.0f;
    std::uint64_t inputBytes = 0;
    bool residentInputUsed = false;
    bool legacySharpenApplied = false;
    bool outputSrgbEncoded = false;
    // FASE 15 publication contract. outputBytes includes row padding when packed BGR8 is used.
    bool packedBgr8Published = false;
    bool floatOutputFallback = false;
    std::uint64_t outputRowStrideBytes = 0u;
    std::uint64_t intermediateBytes = 0;
    std::uint64_t outputBytes = 0;
    std::uint64_t spatialMapBytes = 0;
    std::uint64_t persistentResidentBytes = 0;
    std::uint64_t persistentAllocationGeneration = 0;
    std::string status = "NOT_RUN";
    std::string failureReason;
};

class VulkanSpectraResidentPostDemosaicBackend final {
public:
    VulkanSpectraResidentPostDemosaicBackend() = default;
    ~VulkanSpectraResidentPostDemosaicBackend() = default;

    VulkanSpectraResidentPostDemosaicBackend(
            const VulkanSpectraResidentPostDemosaicBackend&) = delete;
    VulkanSpectraResidentPostDemosaicBackend& operator=(
            const VulkanSpectraResidentPostDemosaicBackend&) = delete;

    SpectraResidentPostDemosaicResult execute(
            VkPhysicalDevice physicalDevice,
            VkDevice device,
            VkQueue computeQueue,
            VkCommandPool commandPool,
            VulkanAllocatorOwner& allocatorOwner,
            const SpectraResidentPostDemosaicRequest& request
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
                            std::uint32_t hostAccess,
                            PersistentBuffer& buffer, bool& reallocated,
                            std::string& failureReason) noexcept;
    void updateDescriptorSetsLocked(
            VkDevice device,
            VkBuffer inputOverride = VK_NULL_HANDLE,
            std::uint64_t inputOverrideBytes = 0u
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
    std::array<VkDescriptorSet, 2> descriptorSets_{VK_NULL_HANDLE, VK_NULL_HANDLE};
    VkCommandBuffer commandBuffer_ = VK_NULL_HANDLE;
    VkFence fence_ = VK_NULL_HANDLE;
    VkQueryPool queryPool_ = VK_NULL_HANDLE;
    VmaAllocator allocator_ = nullptr;
    PersistentBuffer input_;
    PersistentBuffer intermediate_;
    PersistentBuffer output_;
    PersistentBuffer spatialMap_;
    PersistentBuffer spatialTelemetry_;
    PersistentBuffer visibleTelemetry_;
    [[maybe_unused]] std::uint64_t allocationGeneration_ = 0;
    std::uint64_t spatialGenerationId_ = 0;
    std::uint64_t spatialGenerationBytes_ = 0;
    bool descriptorBindingsInitialized_ = false;
};

} // namespace bncam::vulkan
