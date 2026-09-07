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

/**
 * Read-only compact RAW noise observation.
 *
 * This contract intentionally contains no denoise strength, filter plan, pixel-correction
 * request or publication surface. The observer consumes a resident normalized Bayer mosaic
 * and returns per-CFA signal statistics only.
 */
struct SpectraNoiseMapTileGpu {
    std::array<float, 4> signalSum{0.0f, 0.0f, 0.0f, 0.0f};
    std::array<std::uint32_t, 4> sampleCount{0u, 0u, 0u, 0u};
    std::array<float, 4> signalMin{0.0f, 0.0f, 0.0f, 0.0f};
    std::array<float, 4> signalMax{0.0f, 0.0f, 0.0f, 0.0f};
};

struct SpectraNoiseMapPlannerRequest {
    std::uint64_t residentInputGeneration = 0u;
    std::uint32_t frameWidth = 0u;
    std::uint32_t frameHeight = 0u;
    std::uint32_t cfaPattern = 0u;
    std::uint32_t gridWidth = 0u;
    std::uint32_t gridHeight = 0u;
};

struct SpectraNoiseMapPlannerResult {
    bool attempted = false;
    bool success = false;
    bool pipelineAvailable = false;
    bool residentInputUsed = false;
    bool persistentBufferReuseHit = false;
    bool persistentBufferReallocated = false;
    std::vector<SpectraNoiseMapTileGpu> tiles;
    float kernelMs = 0.0f;
    float synchronizationMs = 0.0f;
    float readbackMs = 0.0f;
    float totalMs = 0.0f;
    std::uint64_t compactBytes = 0u;
    std::uint64_t persistentResidentBytes = 0u;
    std::uint64_t persistentAllocationGeneration = 0u;
    std::string method = "GPU_EXACT_TILE_SIGNAL_REDUCTION";
    std::string status = "NOT_RUN";
    std::string failureReason;
};

/**
 * N006H: observer-only backend.
 *
 * The filename/class name is retained for ABI/build continuity with VulkanRuntime, but all
 * historical Pass-3 planning, row/column profiles, R-G/B-G low-field planning, residual-band
 * estimation and denoise ownership have been physically removed. The sole production operation
 * is exact compact per-CFA RAW signal observation.
 */
class VulkanSpectraPass3PlannerBackend final {
public:
    VulkanSpectraPass3PlannerBackend() = default;
    ~VulkanSpectraPass3PlannerBackend() = default;
    VulkanSpectraPass3PlannerBackend(const VulkanSpectraPass3PlannerBackend&) = delete;
    VulkanSpectraPass3PlannerBackend& operator=(const VulkanSpectraPass3PlannerBackend&) = delete;

    SpectraNoiseMapPlannerResult executeNoiseMapFromResident(
            VkPhysicalDevice physicalDevice,
            VkDevice device,
            VkQueue computeQueue,
            VkCommandPool commandPool,
            VulkanAllocatorOwner& allocatorOwner,
            VkBuffer residentInputBuffer,
            std::uint64_t residentInputBytes,
            const SpectraNoiseMapPlannerRequest& request
    ) noexcept;

    void destroy(VkDevice device) noexcept;
    bool productionKernelConnected() const noexcept;
    bool pipelineInitialized() const noexcept;

private:
    struct PersistentBuffer {
        VkBuffer buffer = VK_NULL_HANDLE;
        VmaAllocation allocation = nullptr;
        void* mapped = nullptr;
        std::uint64_t capacityBytes = 0u;
    };

    bool initializeLocked(
            VkDevice device,
            VkCommandPool commandPool,
            std::string& failureReason
    ) noexcept;

    bool ensureOutputLocked(
            VmaAllocator allocator,
            std::uint64_t bytes,
            bool& reallocated,
            std::string& failureReason
    ) noexcept;

    void updateDescriptorSetLocked(
            VkDevice device,
            VkBuffer residentInput
    ) noexcept;

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
    PersistentBuffer output_;
    std::uint64_t allocationGeneration_ = 0u;
};

} // namespace bncam::vulkan
