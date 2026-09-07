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

// N006GR: compact observation contracts owned by the planner/observer backend.
// These two structs are measurement-only data surfaces. They were previously
// declared in the retired pre-demosaic pixel-backend facade solely for reuse by
// this planner. Keeping them here removes that compile-time dependency without
// restoring any Pass0/Pass1 pixel-mutation API or implementation.
struct SpectraResidentRawStatisticsGpu {
    double residualSquaredSum = 0.0;
    std::uint64_t residualSampleCount = 0;
    double chromaSquaredSum = 0.0;
    std::uint64_t chromaSampleCount = 0;
    float residualEnergy = 0.0f;
    float chromaResidualEnergy = 0.0f;
    std::string method = "NOT_RUN";
};

struct SpectraResidentChromaBandsGpu {
    float fineEnergy = 0.0f;
    float midEnergy = 0.0f;
    float lowEnergy = 0.0f;
    float redFineEnergy = 0.0f;
    float redMidEnergy = 0.0f;
    float redLowEnergy = 0.0f;
    float blueFineEnergy = 0.0f;
    float blueMidEnergy = 0.0f;
    float blueLowEnergy = 0.0f;
    float rowPatternProxy = 0.0f;
    float columnPatternProxy = 0.0f;
    std::uint64_t sampleCount = 0;
    std::uint64_t redSampleCount = 0;
    std::uint64_t blueSampleCount = 0;
    float redBlueSampleBalance = 0.0f;
    float confidence = 0.0f;
    std::string status = "NOT_RUN";
    std::string method = "GPU_RESIDENT_SAMPLED_PRE_DEMOSAIC_PROXY";
};

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
 * It consumes an opaque device-resident mosaic and only exposes compact
 * measurement results to the CPU. N006GR keeps this legacy planner surface
 * temporarily because the exact RAW noise-map observer shares the backend;
 * the mutating Pass0/Pass1 facade is not a dependency anymore.
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
