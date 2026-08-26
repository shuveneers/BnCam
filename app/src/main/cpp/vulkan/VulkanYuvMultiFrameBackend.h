#pragma once

#include "VulkanVmaIntegration.h"

#include <android/hardware_buffer.h>
#include <vulkan/vulkan.h>

#include <cstdint>
#include <mutex>
#include <string>
#include <vector>

namespace bncam::vulkan {

struct YuvAlignmentShift {
    bool accepted = false;
    float dx = 0.0f;
    float dy = 0.0f;
    float score = -1.0f;
    std::string rejectReason = "none";
};

struct YuvMultiFrameAlignmentRequest {
    std::vector<AHardwareBuffer*> frames; // anchor first, then supports
    std::uint32_t maxShiftPixels = 12u;
    std::uint32_t sampleStep = 16u;
    float minimumCorrelation = 0.08f;
    bool fuseLuma = false;
    // Per request.frames ordering, anchor first. Values are exposure product / anchor product.
    std::vector<float> exposureScaleToAnchor;
    bool computationalHdr = false;
    // Phase 13: keep the fused Y plane device-resident for direct Phase-12 ISP handoff.
    bool deferFullFrameReadback = false;
    std::uint64_t generationId = 0u;
};

struct YuvMultiFrameAlignmentResult {
    bool attempted = false;
    bool success = false;
    bool submissionMayRemainInFlight = false;
    std::uint32_t width = 0u;
    std::uint32_t height = 0u;
    std::uint32_t anchorRowStrideBytes = 0u;
    std::uint32_t anchorPixelStrideBytes = 0u;
    int supportAccepted = 0;
    int supportRejected = 0;
    std::uint64_t fullFrameCpuUploadBytes = 0u;
    std::uint64_t compactGpuReadbackBytes = 0u;
    std::uint64_t fullFrameGpuReadbackBytes = 0u;
    bool residentFusedLumaProduced = false;
    bool fullFrameReadbackDeferred = false;
    std::uint64_t residentOutputGeneration = 0u;
    float inputCopyMs = 0.0f;
    float gpuAlignmentMs = 0.0f;
    float gpuFusionMs = 0.0f;
    float gpuSynchronizationMs = 0.0f;
    // Phase 9 structural submission telemetry. Counts describe this align() call;
    // resource creation is amortized across backend lifetime instead of per GPU pass.
    std::uint32_t commandBufferAllocations = 0u;
    std::uint32_t commandBufferResets = 0u;
    std::uint32_t fenceCreations = 0u;
    std::uint32_t fenceResets = 0u;
    std::uint32_t queueSubmissions = 0u;
    bool reusedSubmissionResources = false;
    std::uint32_t descriptorSetUpdates = 0u;
    std::uint32_t descriptorWrites = 0u;
    std::uint32_t combinedSupportUploadAlignmentSubmissions = 0u;
    std::string alignmentBackend = "NOT_ATTEMPTED";
    std::string fusionBackend = "NOT_ATTEMPTED";
    std::string failureReason = "none";
    std::vector<YuvAlignmentShift> shifts;
    std::vector<std::uint8_t> outputLuma;
};

class VulkanYuvMultiFrameBackend final {
public:
    YuvMultiFrameAlignmentResult align(
            VkPhysicalDevice physicalDevice,
            VkDevice device,
            VkQueue computeQueue,
            VkCommandPool commandPool,
            VulkanAllocatorOwner& allocatorOwner,
            const YuvMultiFrameAlignmentRequest& request) noexcept;

    void destroy(VkDevice device) noexcept;

    // Runtime-internal handoff. The generation is valid only while this backend still owns
    // the matching fused buffer; callers must treat a mismatch as an explicit GPU failure.
    bool resolveResidentOutput(
            std::uint64_t generation,
            VkBuffer& buffer,
            std::uint64_t& bytes,
            std::uint32_t& width,
            std::uint32_t& height) const noexcept;

    bool resolveResidentHdrAuthority(
            std::uint64_t generation,
            VkBuffer& accumulatorBuffer,
            VkBuffer& weightBuffer,
            std::uint64_t& bytes,
            std::uint32_t& width,
            std::uint32_t& height) const noexcept;

private:
    struct PersistentBuffer {
        VkBuffer buffer = VK_NULL_HANDLE;
        VmaAllocation allocation = nullptr;
        void* mapped = nullptr;
        std::uint64_t capacityBytes = 0u;
    };

    struct PipelineBundle {
        VkDescriptorSetLayout descriptorSetLayout = VK_NULL_HANDLE;
        VkPipelineLayout pipelineLayout = VK_NULL_HANDLE;
        VkShaderModule shaderModule = VK_NULL_HANDLE;
        VkPipeline pipeline = VK_NULL_HANDLE;
        VkDescriptorPool descriptorPool = VK_NULL_HANDLE;
        VkDescriptorSet descriptorSet = VK_NULL_HANDLE;
    };

    bool initializeLocked(VkDevice device, std::string& failureReason) noexcept;
    bool ensureBufferLocked(VmaAllocator allocator, std::uint64_t bytes, bool hostVisible,
                            PersistentBuffer& buffer, std::string& failureReason) noexcept;
    void destroyBufferLocked(PersistentBuffer& buffer) noexcept;
    void destroyPipelineLocked(VkDevice device, PipelineBundle& pipeline) noexcept;
    bool ensureSubmissionResourcesLocked(
            VkDevice device, VkCommandPool commandPool, bool& created,
            std::string& failureReason) noexcept;
    void destroySubmissionResourcesLocked(VkDevice device) noexcept;
    void destroyLocked(VkDevice device) noexcept;

    mutable std::mutex mutex_;
    VkDevice initializedDevice_ = VK_NULL_HANDLE;
    VmaAllocator allocator_ = nullptr;
    bool initialized_ = false;
    VkCommandPool submissionCommandPool_ = VK_NULL_HANDLE;
    VkCommandBuffer reusableCommandBuffer_ = VK_NULL_HANDLE;
    VkFence reusableFence_ = VK_NULL_HANDLE;
    bool submissionResourcesUnsafe_ = false;
    PipelineBundle alignmentPipeline_{};
    PipelineBundle fusionPipeline_{};
    PersistentBuffer staging_{};
    PersistentBuffer anchor_{};
    PersistentBuffer support_{};
    PersistentBuffer scores_{};
    PersistentBuffer scoreReadback_{};
    PersistentBuffer accumulator_{};
    PersistentBuffer weight_{};
    PersistentBuffer fusedLuma_{};
    PersistentBuffer lumaReadback_{};
    std::uint64_t residentOutputGeneration_ = 0u;
    std::uint64_t residentOutputBytes_ = 0u;
    std::uint32_t residentOutputWidth_ = 0u;
    std::uint32_t residentOutputHeight_ = 0u;
    std::uint64_t residentHdrGeneration_ = 0u;
    std::uint64_t residentHdrBytes_ = 0u;
    std::uint32_t residentHdrWidth_ = 0u;
    std::uint32_t residentHdrHeight_ = 0u;
};

} // namespace bncam::vulkan
