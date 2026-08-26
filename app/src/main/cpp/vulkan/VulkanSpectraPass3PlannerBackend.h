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

struct SpectraPass3PlannerRequest {
    std::uint64_t residentInputGeneration = 0;
    std::uint32_t frameWidth = 0;
    std::uint32_t frameHeight = 0;
    std::uint32_t cfaPattern = 0;
    float textureGate = 0.0f;
    std::uint32_t chromaGridCols = 24;
    std::uint32_t chromaGridRows = 18;
};

struct SpectraPass3PlannerResult {
    bool attempted = false;
    bool success = false;
    bool pipelineAvailable = false;
    bool residentInputUsed = false;
    bool persistentBufferReuseHit = false;
    bool persistentBufferReallocated = false;
    std::array<std::vector<float>, 4> rowProfileByChannel;
    std::array<std::vector<float>, 4> columnProfileByChannel;
    std::vector<float> rawRGrid;
    std::vector<float> rawBGrid;
    std::vector<std::uint8_t> validRGrid;
    std::vector<std::uint8_t> validBGrid;
    SpectraResidentRawStatisticsGpu rawStatistics{};
    SpectraResidentChromaBandsGpu chromaBands{};
    float kernelMs = 0.0f;
    float synchronizationMs = 0.0f;
    float readbackMs = 0.0f;
    float totalMs = 0.0f;
    std::uint64_t compactBytes = 0;
    std::uint64_t persistentResidentBytes = 0;
    std::uint64_t persistentAllocationGeneration = 0;
    std::string samplingMethod = "GPU_STRATIFIED_ROBUST_MEDIAN";
    std::string status = "NOT_RUN";
    std::string failureReason;
};


// Phase 13 exact compact noise-map observation from resident normalized RAW.
// Each tile returns per-CFA signal sums/counts plus the exact tile signal range;
// effective S/O variance is resolved on CPU from this compact grid.
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
 * Milestone 8H-H compact Pass-3 planner.
 *
 * It consumes the opaque device-resident Pass-2 mosaic. Only row/column
 * profiles and the low-frequency 24x18 field cross to the CPU. This removes
 * the multi-second full-frame CPU Pass-3 scan while retaining the established
 * CPU low-field smoothing/activation rules and GPU No-Regret application.
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
    SpectraPass3PlannerResult executeFromResident(
            VkPhysicalDevice physicalDevice,
            VkDevice device,
            VkQueue computeQueue,
            VkCommandPool commandPool,
            VulkanAllocatorOwner& allocatorOwner,
            VkBuffer residentInputBuffer,
            std::uint64_t residentInputBytes,
            const SpectraPass3PlannerRequest& request
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
    bool ensureOutputLocked(VmaAllocator allocator, std::uint64_t bytes,
                            bool& reallocated, std::string& failureReason) noexcept;
    void updateDescriptorSetLocked(VkDevice device, VkBuffer residentInput) noexcept;
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
    std::uint64_t allocationGeneration_ = 0;
};

} // namespace bncam::vulkan
