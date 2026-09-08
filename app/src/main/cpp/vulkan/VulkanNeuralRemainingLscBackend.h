#pragma once

#include "VulkanVmaIntegration.h"

#include <vulkan/vulkan.h>

#include <cstdint>
#include <mutex>
#include <string>
#include <vector>

namespace bncam::vulkan::neural {

/**
 * Phase 5 post-neural physical stage.
 *
 * This backend owns exactly one operation: the remaining Camera2 lens-shading
 * correction that intentionally stayed out of the pre-neural hard sensor stage.
 * It also transports the immutable source-clipping confidence tail produced by
 * the pre-neural RawFinalize and derives compact Auto-demosaic scene metrics
 * from the true post-LSC Bayer image. It is not a denoiser and has no CPU pixel path.
 */
struct NeuralRemainingLscRequest {
    VkBuffer bayerInput = VK_NULL_HANDLE;
    std::uint64_t bayerInputBytes = 0u;

    // Immutable pre-neural RawFinalize transport. The compact source-clip tail
    // starts immediately after width*height FP32 Bayer samples.
    VkBuffer sourceClipTransport = VK_NULL_HANDLE;
    std::uint64_t sourceClipTransportBytes = 0u;

    std::uint32_t frameWidth = 0u;
    std::uint32_t frameHeight = 0u;
    std::uint32_t sensorCfaPattern = 0u;
    std::int32_t cfaOffsetX = 0;
    std::int32_t cfaOffsetY = 0;

    const float* lensShadingMap = nullptr;
    std::uint32_t lensShadingColumns = 0u;
    std::uint32_t lensShadingRows = 0u;
    std::uint64_t lensShadingGenerationId = 0u;

    bool collectAutoSceneMetrics = true;
};

struct NeuralRemainingLscResult {
    bool attempted = false;
    bool success = false;
    bool pipelineAvailable = false;
    bool submissionMayRemainInFlight = false;
    bool lensShadingApplied = false;
    bool sourceClipConfidenceMapPreserved = false;
    bool autoSceneMetricsReady = false;
    bool persistentBufferReuseHit = false;
    bool persistentBufferReallocated = false;

    std::uint64_t lensCorrectedPixelCount = 0u;
    std::uint64_t overRangePixelCount = 0u;
    float lensMaximumGain = 1.0f;

    std::uint64_t autoSceneSampleCount = 0u;
    float autoSceneMedianSignal = 0.0f;
    float autoSceneMeanGradient = 0.0f;
    float autoSceneP90Gradient = 0.0f;
    float autoSceneEdgeFraction = 0.0f;
    float autoSceneCoherentEdgeFraction = 0.0f;
    float autoSceneLowSignalFraction = 0.0f;

    float lensMapUploadMs = 0.0f;
    float lscKernelMs = 0.0f;
    float autoSceneKernelMs = 0.0f;
    float autoSceneReductionCpuMs = 0.0f;
    float synchronizationMs = 0.0f;
    float totalMs = 0.0f;

    std::uint64_t imageBytes = 0u;
    std::uint64_t sourceClipConfidenceMapBytes = 0u;
    std::uint64_t residentTransportBytes = 0u;
    std::uint64_t persistentResidentBytes = 0u;
    std::uint64_t persistentAllocationGeneration = 0u;
    std::uint64_t residentOutputGeneration = 0u;

    std::string status = "NOT_RUN";
    std::string failureReason;
};

class VulkanNeuralRemainingLscBackend final {
public:
    VulkanNeuralRemainingLscBackend() = default;
    ~VulkanNeuralRemainingLscBackend() = default;
    VulkanNeuralRemainingLscBackend(const VulkanNeuralRemainingLscBackend&) = delete;
    VulkanNeuralRemainingLscBackend& operator=(const VulkanNeuralRemainingLscBackend&) = delete;

    NeuralRemainingLscResult execute(
            VkPhysicalDevice physicalDevice,
            VkDevice device,
            VkQueue computeQueue,
            VkCommandPool commandPool,
            VulkanAllocatorOwner& allocatorOwner,
            const NeuralRemainingLscRequest& request) noexcept;

    bool resolveResidentOutput(
            std::uint64_t generation,
            VkBuffer& buffer,
            std::uint64_t& bytes,
            std::uint32_t& width,
            std::uint32_t& height) const noexcept;

    void destroy(VkDevice device) noexcept;
    bool productionKernelConnected() const noexcept;
    bool pipelineInitialized() const noexcept;

private:
    struct Buffer {
        VkBuffer buffer = VK_NULL_HANDLE;
        VmaAllocation allocation = nullptr;
        void* mapped = nullptr;
        std::uint64_t capacityBytes = 0u;
    };

    bool initializeLocked(VkDevice device, VkCommandPool commandPool,
                          std::string& failureReason) noexcept;
    bool ensureBufferLocked(VmaAllocator allocator, std::uint64_t bytes,
                            std::uint32_t hostAccess, Buffer& buffer,
                            bool& reallocated, std::string& failureReason) noexcept;
    bool ensureLensMapLocked(VmaAllocator allocator, const NeuralRemainingLscRequest& request,
                             bool& reallocated, float& uploadMs,
                             std::string& failureReason) noexcept;
    void updateDescriptorSetLocked(VkDevice device, VkBuffer source) noexcept;
    void destroyLocked(VkDevice device) noexcept;
    void freeBufferLocked(Buffer& buffer) noexcept;

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

    Buffer output_{};
    Buffer lensMap_{};
    Buffer autoSamples_{};
    Buffer telemetry_{};

    std::uint64_t lensMapGenerationId_ = 0u;
    std::uint32_t lensMapColumns_ = 0u;
    std::uint32_t lensMapRows_ = 0u;
    std::uint64_t allocationGeneration_ = 0u;
    std::uint64_t generationCounter_ = 0u;
    std::uint64_t residentOutputGeneration_ = 0u;
    std::uint64_t residentOutputBytes_ = 0u;
    std::uint32_t residentOutputWidth_ = 0u;
    std::uint32_t residentOutputHeight_ = 0u;
};

} // namespace bncam::vulkan::neural
