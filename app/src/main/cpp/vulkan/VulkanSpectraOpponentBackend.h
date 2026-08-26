#pragma once

#include "VulkanVmaIntegration.h"

#include <vulkan/vulkan.h>

#include <cstddef>
#include <cstdint>
#include <mutex>
#include <string>
#include <vector>

namespace bncam::vulkan {

struct SpectraOpponentExecutionRequest {
    const float* rgbData = nullptr;
    std::uint32_t width = 0;
    std::uint32_t height = 0;
    std::size_t rowStrideFloats = 0;
    std::uint64_t generationId = 0;
};

struct SpectraOpponentExecutionResult {
    bool attempted = false;
    bool success = false;
    bool pipelineAvailable = false;
    bool timestampQueryUsed = false;
    std::vector<float> opponentRgba; // Y, R-G, B-G, 1.0
    float inputPackingMs = 0.0f;
    float gpuKernelMs = 0.0f;
    float synchronizationMs = 0.0f;
    float readbackMs = 0.0f;
    float totalMs = 0.0f;
    float transferAndSyncMs = 0.0f;
    std::uint64_t inputBytes = 0;
    std::uint64_t outputBytes = 0;
    std::uint64_t allocatedBytes = 0;
    bool persistentBufferReuseHit = false;
    bool persistentBufferReallocated = false;
    std::uint64_t persistentBufferCapacityBytes = 0;
    std::uint64_t persistentResidentBytes = 0;
    std::uint64_t persistentAllocationGeneration = 0;
    std::string status = "NOT_RUN";
    std::string failureReason;
};

class VulkanSpectraOpponentBackend final {
public:
    VulkanSpectraOpponentBackend() = default;
    ~VulkanSpectraOpponentBackend() = default;

    VulkanSpectraOpponentBackend(const VulkanSpectraOpponentBackend&) = delete;
    VulkanSpectraOpponentBackend& operator=(const VulkanSpectraOpponentBackend&) = delete;

    SpectraOpponentExecutionResult execute(
            VkPhysicalDevice physicalDevice,
            VkDevice device,
            VkQueue computeQueue,
            VkCommandPool commandPool,
            VulkanAllocatorOwner& allocatorOwner,
            const SpectraOpponentExecutionRequest& request
    ) noexcept;

    void destroy(VkDevice device) noexcept;
    bool productionKernelConnected() const noexcept;
    bool pipelineInitialized() const noexcept;

private:
    bool initializeLocked(VkDevice device, std::string& failureReason) noexcept;
    bool ensurePersistentBuffersLocked(
            VmaAllocator allocator,
            std::uint64_t requiredBytes,
            SpectraOpponentExecutionResult& result,
            std::string& failureReason
    ) noexcept;
    void destroyPersistentBuffersLocked() noexcept;
    void destroyLocked(VkDevice device) noexcept;

    mutable std::mutex mutex_;
    bool initialized_ = false;
    VkDescriptorSetLayout descriptorSetLayout_ = VK_NULL_HANDLE;
    VkPipelineLayout pipelineLayout_ = VK_NULL_HANDLE;
    VkShaderModule shaderModule_ = VK_NULL_HANDLE;
    VkPipeline pipeline_ = VK_NULL_HANDLE;
    VkDescriptorPool descriptorPool_ = VK_NULL_HANDLE;
    VkDescriptorSet descriptorSet_ = VK_NULL_HANDLE;
    VmaAllocator persistentAllocator_ = nullptr;
    VkBuffer persistentInputBuffer_ = VK_NULL_HANDLE;
    VmaAllocation persistentInputAllocation_ = nullptr;
    void* persistentInputMapped_ = nullptr;
    VkBuffer persistentOutputBuffer_ = VK_NULL_HANDLE;
    VmaAllocation persistentOutputAllocation_ = nullptr;
    void* persistentOutputMapped_ = nullptr;
    std::uint64_t persistentBufferCapacityBytes_ = 0;
    std::uint64_t persistentAllocationGeneration_ = 0;
};

} // namespace bncam::vulkan
