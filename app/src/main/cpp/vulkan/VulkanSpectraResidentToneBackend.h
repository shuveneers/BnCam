#pragma once

#include "VulkanVmaIntegration.h"

#include <vulkan/vulkan.h>

#include <cstddef>
#include <cstdint>
#include <mutex>
#include <string>
#include <vector>

namespace bncam::vulkan {

struct SpectraResidentSceneObserverRequest {
    std::uint32_t frameWidth = 0;
    std::uint32_t frameHeight = 0;
    std::uint32_t targetSampleCount = 50000u;
    // QUALITY DELTA 0008: full-resolution scene-linear opponent cleanup fused
    // into the post-AWB/CCM highlight pass, before scene observation/tone.
    // Disabled is bit-identical and requires no extra full-frame scratch.
    bool preToneChroma444Enabled = false;
    float preToneChroma444Strength = 0.0f;
};

struct SpectraResidentSceneObserverResult {
    bool attempted = false;
    bool success = false;
    bool residentInputUsed = false;
    bool persistentBufferReuseHit = false;
    bool persistentBufferReallocated = false;
    bool highlightRecoveryApplied = false;
    std::uint64_t correctedHighlightPixels = 0;
    bool preToneChroma444Applied = false;
    float preToneChroma444Strength = 0.0f;
    std::uint32_t sampleStep = 1u;
    std::uint32_t sampleCount = 0u;
    std::vector<float> sampledRgb;  // tightly packed R,G,B triples
    std::vector<float> displayGrid; // 32x24 luma field
    float highlightKernelMs = 0.0f;
    float sampleKernelMs = 0.0f;
    float compactReadbackMs = 0.0f;
    float synchronizationMs = 0.0f;
    float totalMs = 0.0f;
    std::uint64_t compactBytes = 0u;
    std::uint64_t persistentResidentBytes = 0u;
    std::uint64_t persistentAllocationGeneration = 0u;
    std::uint64_t residentSceneGeneration = 0u;
    std::string status = "NOT_RUN";
    std::string failureReason;
};

struct SpectraResidentToneRequest {
    std::uint32_t frameWidth = 0;
    std::uint32_t frameHeight = 0;
    std::uint64_t residentSceneGeneration = 0u;
    float exposureGain = 1.0f;
    float rawJpegBaseVibrance = 1.0f;
    float shoulderStart = 0.68f;
    float shoulderStrength = 1.0f;
    // Automatic local-tone policy. Spatial analysis/application remains Vulkan-resident.
    float localToneStrength = 0.0f;
    float localToneSceneKey = 0.125f;
    float localToneMaxLiftEv = 0.25f;
    float localToneMaxCompressEv = 0.15f;
    bool isRawBayer = true;
    float profileColorSaturation = 0.0f;
    float profileColorContrast = 0.0f;
    float profilePresenceVibrance = 0.0f;
    // Two floats per LUT entry: curvedLuma, safeMidtoneGate. Exactly 4096 entries.
    const float* toneLut = nullptr;
    std::size_t toneLutFloatCount = 0u;
    bool deferFullReadback = false;
    // Ultra HDR gainmap generation is entirely Vulkan/GPU based. The CPU only receives the
    // already quantized, quarter-resolution gainmap artifact and compact scalar metadata.
    bool ultraHdrGainmapRequested = false;
    int outputRotationDegrees = 0;
    bool portraitEffectRequested = false;
    const float* portraitMask = nullptr;
    std::size_t portraitMaskFloatCount = 0u;
    std::uint32_t portraitMaskWidth = 0u;
    std::uint32_t portraitMaskHeight = 0u;
    float portraitTargetLeft = 0.0f;
    float portraitTargetTop = 0.0f;
    float portraitTargetRight = 0.0f;
    float portraitTargetBottom = 0.0f;
    std::uint32_t portraitMaskRotationDegrees = 0u;
};

struct SpectraResidentToneResult {
    bool attempted = false;
    bool success = false;
    bool residentInputUsed = false;
    bool persistentBufferReuseHit = false;
    bool persistentBufferReallocated = false;
    bool fullReadbackDeferred = false;
    bool highlightNeutralizeApplied = false;
    bool localToneRequested = false;
    bool localToneApplied = false;
    std::uint64_t localToneAdjustedPixels = 0u;
    std::vector<float> outputRgb;
    float lutUploadMs = 0.0f;
    float kernelMs = 0.0f;
    float readbackMs = 0.0f;
    float synchronizationMs = 0.0f;
    float totalMs = 0.0f;
    std::uint64_t persistentResidentBytes = 0u;
    std::uint64_t persistentAllocationGeneration = 0u;
    std::uint64_t residentToneGeneration = 0u;
    bool ultraHdrGainmapRequested = false;
    bool ultraHdrGainmapGenerated = false;
    bool ultraHdrMeaningfulHeadroom = false;
    std::uint32_t ultraHdrGainmapWidth = 0u;
    std::uint32_t ultraHdrGainmapHeight = 0u;
    std::uint32_t ultraHdrGainmapRowStrideBytes = 0u;
    float ultraHdrMinContentBoost = 1.0f;
    float ultraHdrMaxContentBoost = 1.0f;
    float ultraHdrGamma = 1.0f;
    float ultraHdrOffsetSdr = 1.0f / 64.0f;
    float ultraHdrOffsetHdr = 1.0f / 64.0f;
    std::vector<std::uint8_t> ultraHdrGainmapBytes;
    std::string ultraHdrStatus = "NOT_REQUESTED";
    bool portraitEffectRequested = false;
    bool portraitEffectApplied = false;
    std::string portraitStatus = "NOT_REQUESTED";
    std::string status = "NOT_RUN";
    std::string failureReason;
};

/**
 * Milestone 8H-J resident post-colour scene/tone backend.
 *
 * The backend consumes the post-CCM device buffer owned by the resident demosaic backend.
 * Local highlight recovery, compact scene sampling and the complete pointwise tone/vibrance/
 * profile-colour loop execute on Vulkan. Only compact scene samples cross to the CPU before
 * the exposure governor. The tone result can remain resident for the next post-demosaic stage.
 */
class VulkanSpectraResidentToneBackend final {
public:
    SpectraResidentSceneObserverResult executeSceneObserverFromResident(
            VkPhysicalDevice physicalDevice,
            VkDevice device,
            VkQueue computeQueue,
            VkCommandPool commandPool,
            VulkanAllocatorOwner& allocatorOwner,
            VkBuffer residentInputBuffer,
            std::uint64_t residentInputBytes,
            const SpectraResidentSceneObserverRequest& request
    ) noexcept;

    SpectraResidentToneResult executeTone(
            VkPhysicalDevice physicalDevice,
            VkDevice device,
            VkQueue computeQueue,
            VkCommandPool commandPool,
            VulkanAllocatorOwner& allocatorOwner,
            const SpectraResidentToneRequest& request
    ) noexcept;

    bool resolveResidentOutput(
            std::uint64_t generation,
            VkBuffer& buffer,
            std::uint64_t& bytes,
            std::uint32_t& width,
            std::uint32_t& height
    ) const noexcept;

    /** Failure-only materialization of an already computed resident tone generation. */
    bool readbackResidentOutput(
            VkDevice device,
            VkQueue computeQueue,
            VulkanAllocatorOwner& allocatorOwner,
            std::uint64_t generation,
            std::vector<float>& outputRgb,
            std::string& failureReason
    ) noexcept;

    void destroy(VkDevice device) noexcept;
    bool productionKernelConnected() const noexcept;

private:
    struct PersistentBuffer {
        VkBuffer buffer = VK_NULL_HANDLE;
        VmaAllocation allocation = nullptr;
        void* mapped = nullptr;
        std::uint64_t capacityBytes = 0u;
    };

    bool initializeLocked(VkDevice device, VkCommandPool commandPool,
                          std::string& failureReason) noexcept;
    bool ensureBufferLocked(VmaAllocator allocator, std::uint64_t bytes,
                            std::uint32_t hostAccess, PersistentBuffer& buffer,
                            bool& reallocated, std::string& failureReason) noexcept;
    void updateDescriptorsLocked(VkDevice device, VkBuffer inputOverride) noexcept;
    void destroyBuffersLocked() noexcept;
    void destroyLocked(VkDevice device) noexcept;

    mutable std::mutex mutex_;
    bool initialized_ = false;
    VkDevice initializedDevice_ = VK_NULL_HANDLE;
    VkCommandPool initializedCommandPool_ = VK_NULL_HANDLE;
    VmaAllocator allocator_ = nullptr;
    VkDescriptorSetLayout descriptorSetLayout_ = VK_NULL_HANDLE;
    VkPipelineLayout pipelineLayout_ = VK_NULL_HANDLE;
    VkShaderModule shaderModule_ = VK_NULL_HANDLE;
    VkPipeline pipeline_ = VK_NULL_HANDLE;
    VkDescriptorPool descriptorPool_ = VK_NULL_HANDLE;
    VkDescriptorSet descriptorSet_ = VK_NULL_HANDLE;
    VkCommandBuffer commandBuffer_ = VK_NULL_HANDLE;
    VkFence fence_ = VK_NULL_HANDLE;
    VkQueryPool queryPool_ = VK_NULL_HANDLE;
    PersistentBuffer workingRgb_;
    PersistentBuffer compact_;
    PersistentBuffer toneLut_;
    PersistentBuffer readback_;
    PersistentBuffer telemetry_;
    PersistentBuffer ultraHdrLuma_;
    PersistentBuffer ultraHdrGainLog_;
    PersistentBuffer ultraHdrGainmapPacked_;
    PersistentBuffer portraitMask_;
    PersistentBuffer portraitBlurRgb_;
    // Quarter-resolution edge-aware scene-luma base used by the Vulkan local-tone stage.
    PersistentBuffer localToneBase_;
    [[maybe_unused]] std::uint64_t allocationGeneration_ = 0u;
    [[maybe_unused]] std::uint64_t residentSceneGeneration_ = 0u;
    std::uint64_t residentToneGeneration_ = 0u;
    std::uint32_t residentWidth_ = 0u;
    std::uint32_t residentHeight_ = 0u;
    bool residentSceneValid_ = false;
    bool residentToneValid_ = false;
};

} // namespace bncam::vulkan
