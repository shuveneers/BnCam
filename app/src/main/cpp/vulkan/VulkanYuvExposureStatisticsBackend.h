#pragma once

#include "VulkanVmaIntegration.h"

#include <vulkan/vulkan.h>

#include <array>
#include <cstddef>
#include <cstdint>
#include <mutex>
#include <string>

namespace bncam::vulkan {

constexpr std::size_t kYuvExposureStatisticsWords = 265u;

struct YuvExposureStatisticsRequest {
    const std::uint32_t* packedSamples = nullptr;
    std::size_t sampleCount = 0u;
    std::uint32_t sampleWidth = 0u;
    std::uint32_t sampleHeight = 0u;
};

struct YuvExposureStatisticsResult {
    bool attempted = false;
    bool success = false;
    bool submissionMayRemainInFlight = false;
    std::array<std::uint32_t, kYuvExposureStatisticsWords> statistics{};
    float inputUploadMs = 0.0f;
    float gpuExecutionWallMs = 0.0f;
    float gpuSynchronizationMs = 0.0f;
    float readbackMs = 0.0f;
    std::string backend = "NOT_ATTEMPTED";
    std::string failureReason = "none";
};

class VulkanYuvExposureStatisticsBackend final {
public:
    YuvExposureStatisticsResult execute(
            VkDevice device,
            VkQueue computeQueue,
            VkCommandPool commandPool,
            VulkanAllocatorOwner& allocatorOwner,
            const YuvExposureStatisticsRequest& request) noexcept;

    void destroy(VkDevice device) noexcept;

private:
    struct PersistentBuffer {
        VkBuffer buffer = VK_NULL_HANDLE;
        VmaAllocation allocation = nullptr;
        void* mapped = nullptr;
        std::uint64_t capacityBytes = 0u;
    };

    bool initializeLocked(VkDevice device, std::string& failureReason) noexcept;
    bool ensureHostBufferLocked(
            VmaAllocator allocator,
            std::uint64_t bytes,
            PersistentBuffer& target,
            std::string& failureReason) noexcept;
    void destroyBufferLocked(PersistentBuffer& buffer) noexcept;
    void destroyLocked(VkDevice device) noexcept;

    std::mutex mutex_;
    VkDevice initializedDevice_ = VK_NULL_HANDLE;
    VmaAllocator allocator_ = nullptr;
    bool initialized_ = false;
    VkDescriptorSetLayout descriptorSetLayout_ = VK_NULL_HANDLE;
    VkPipelineLayout pipelineLayout_ = VK_NULL_HANDLE;
    VkShaderModule shaderModule_ = VK_NULL_HANDLE;
    VkPipeline pipeline_ = VK_NULL_HANDLE;
    VkDescriptorPool descriptorPool_ = VK_NULL_HANDLE;
    VkDescriptorSet descriptorSet_ = VK_NULL_HANDLE;
    PersistentBuffer input_{};
    PersistentBuffer statistics_{};
};

} // namespace bncam::vulkan
