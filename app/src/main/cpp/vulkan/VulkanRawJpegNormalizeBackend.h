#pragma once

#include "VulkanVmaIntegration.h"

#include <vulkan/vulkan.h>

#include <array>
#include <cstdint>
#include <mutex>
#include <string>

namespace bncam::vulkan {

// Phase 13 resident equivalent of RawDomain::normalizeRawForJpeg().
// Input is canonical, tightly packed RAW16 code values already owned by Vulkan.
struct RawJpegNormalizeRequest {
    VkBuffer canonicalRawBuffer = VK_NULL_HANDLE;
    std::uint64_t canonicalRawBytes = 0u;
    std::uint32_t inputWidth = 0u;
    std::uint32_t inputHeight = 0u;
    std::uint32_t cropLeft = 0u;
    std::uint32_t cropTop = 0u;
    std::uint32_t width = 0u;
    std::uint32_t height = 0u;
    std::uint32_t cfaOffsetX = 0u;
    std::uint32_t cfaOffsetY = 0u;
    float whiteLevel = 1.0f;
    std::array<float, 4> blackLevels{0.0f, 0.0f, 0.0f, 0.0f};
    std::uint64_t generationId = 0u;
};

struct RawJpegNormalizeResult {
    bool attempted = false;
    bool success = false;
    bool submissionMayRemainInFlight = false;
    bool residentOutputProduced = false;
    std::uint64_t residentOutputGeneration = 0u;
    std::uint64_t fullFrameCpuUploadBytes = 0u;
    std::uint64_t fullFrameGpuReadbackBytes = 0u;
    float gpuKernelWallMs = 0.0f;
    float gpuSynchronizationMs = 0.0f;
    std::uint32_t commandBufferAllocations = 0u;
    std::uint32_t commandBufferResets = 0u;
    std::uint32_t fenceCreations = 0u;
    std::uint32_t fenceResets = 0u;
    std::uint32_t queueSubmissions = 0u;
    std::uint32_t descriptorUpdates = 0u;
    std::uint32_t descriptorBindingsReused = 0u;
    bool reusedSubmissionResources = false;
    std::string backend = "NOT_ATTEMPTED";
    std::string failureReason = "none";
};

class VulkanRawJpegNormalizeBackend final {
public:
    RawJpegNormalizeResult execute(
            VkDevice device,
            VkQueue computeQueue,
            VkCommandPool commandPool,
            VulkanAllocatorOwner& allocatorOwner,
            const RawJpegNormalizeRequest& request) noexcept;

    bool resolveResidentOutput(
            std::uint64_t generation,
            VkBuffer& buffer,
            std::uint64_t& bytes,
            std::uint32_t& width,
            std::uint32_t& height) const noexcept;

    void destroy(VkDevice device) noexcept;

private:
    struct PersistentBuffer {
        VkBuffer buffer = VK_NULL_HANDLE;
        VmaAllocation allocation = nullptr;
        std::uint64_t capacityBytes = 0u;
    };

    bool initializeLocked(VkDevice device, std::string& failureReason) noexcept;
    bool ensureBufferLocked(
            VmaAllocator allocator,
            std::uint64_t bytes,
            PersistentBuffer& target,
            std::string& failureReason) noexcept;
    void destroyBufferLocked(PersistentBuffer& buffer) noexcept;
    bool ensureSubmissionResourcesLocked(
            VkDevice device,
            VkCommandPool commandPool,
            bool& created,
            std::string& failureReason) noexcept;
    void destroySubmissionResourcesLocked(VkDevice device) noexcept;
    void destroyLocked(VkDevice device) noexcept;

    mutable std::mutex mutex_;
    VkDevice initializedDevice_ = VK_NULL_HANDLE;
    VmaAllocator allocator_ = nullptr;
    bool initialized_ = false;
    VkDescriptorSetLayout descriptorSetLayout_ = VK_NULL_HANDLE;
    VkPipelineLayout pipelineLayout_ = VK_NULL_HANDLE;
    VkShaderModule shaderModule_ = VK_NULL_HANDLE;
    VkPipeline pipeline_ = VK_NULL_HANDLE;
    VkDescriptorPool descriptorPool_ = VK_NULL_HANDLE;
    VkDescriptorSet descriptorSet_ = VK_NULL_HANDLE;

    VkCommandPool submissionCommandPool_ = VK_NULL_HANDLE;
    VkCommandBuffer reusableCommandBuffer_ = VK_NULL_HANDLE;
    VkFence reusableFence_ = VK_NULL_HANDLE;
    bool submissionResourcesUnsafe_ = false;

    VkBuffer boundInputBuffer_ = VK_NULL_HANDLE;
    VkDeviceSize boundInputRange_ = 0u;
    VkBuffer boundOutputBuffer_ = VK_NULL_HANDLE;
    VkDeviceSize boundOutputRange_ = 0u;

    PersistentBuffer output_{};
    std::uint64_t residentOutputGeneration_ = 0u;
    std::uint64_t residentOutputBytes_ = 0u;
    std::uint32_t residentOutputWidth_ = 0u;
    std::uint32_t residentOutputHeight_ = 0u;
};

} // namespace bncam::vulkan
