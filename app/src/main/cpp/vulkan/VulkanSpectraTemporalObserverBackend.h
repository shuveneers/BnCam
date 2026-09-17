#pragma once

#include "VulkanVmaIntegration.h"
#include "../SpectraTemporalFusion.h"

#include <vulkan/vulkan.h>

#include <array>
#include <cstddef>
#include <cstdint>
#include <mutex>
#include <string>
#include <vector>

namespace bncam::vulkan {

struct SpectraTemporalObserverRequest {
    const float* anchorData = nullptr;
    std::uint32_t anchorWidth = 0;
    std::uint32_t anchorHeight = 0;
    std::size_t anchorRowStrideFloats = 0;

    const std::uint16_t* supportData = nullptr;
    std::uint32_t supportWidth = 0;
    std::uint32_t supportHeight = 0;
    std::size_t supportRowStrideU16 = 0;

    // Optional GPU-resident canonical RAW16 inputs. When both handles are provided, the
    // observer consumes the exact packed-u16 buffers produced by the RAW capture backend and
    // performs no full-frame CPU packing/upload. Width/height still describe those buffers.
    VkBuffer residentAnchorRaw16Buffer = VK_NULL_HANDLE;
    VkBuffer residentSupportRaw16Buffer = VK_NULL_HANDLE;

    int applyDx = 0;
    int applyDy = 0;
    float strictness = 0.0f;
    float alignmentConfidence = 0.0f;
    float forwardBackwardConsistency = 0.0f;
    int whiteLevel = 0;
    std::array<int, 4> blackLevels{0, 0, 0, 0};
    int cfaPattern = 0;

    std::array<double, 4> effectiveS{0.0, 0.0, 0.0, 0.0};
    std::array<double, 4> effectiveO{0.0, 0.0, 0.0, 0.0};
    float modelConfidence = 0.0f;
    float temporalAuthority = 0.0f;
};

struct SpectraTemporalObserverResult {
    bool attempted = false;
    bool success = false;          // GPU computation completed; valid may still be false by model gates.
    bool submissionMayRemainInFlight = false;
    bool valid = false;
    bool pipelineAvailable = false;
    bool timestampQueryUsed = false;
    bool persistentBufferReuseHit = false;
    bool persistentBufferReallocated = false;
    bool cpuFullFrameObserverAvoided = false;
    bool residentRawInputsUsed = false;
    std::uint64_t fullFrameCpuUploadBytes = 0;

    std::array<double, 4> observedVariance{0.0, 0.0, 0.0, 0.0};
    std::array<double, 4> predictedVariance{0.0, 0.0, 0.0, 0.0};
    std::array<double, 4> temporalCorrelation{0.0, 0.0, 0.0, 0.0};
    std::array<double, 4> persistentPatternFraction{0.0, 0.0, 0.0, 0.0};
    std::array<double, 4> signalSpan{0.0, 0.0, 0.0, 0.0};
    std::array<int, 4> populatedSignalBins{0, 0, 0, 0};
    std::array<std::uint64_t, 4> samples{0, 0, 0, 0};

    spectra_temporal::StaticProbabilityField staticProbabilityField{};
    double observerConfidence = 0.0;
    float alignmentConfidence = 0.0f;
    float motionConfidence = 0.0f;
    float forwardBackwardConsistency = 0.0f;
    float meanTemporalCorrelation = 0.0f;
    float persistentPatternMean = 0.0f;
    float independentNoiseFraction = 1.0f;
    float supportWeight = 0.25f;
    double staticProbabilityMean = 0.0;
    double staticProbabilityP10 = 0.0;
    double staticProbabilityP50 = 0.0;
    double staticProbabilityP90 = 0.0;
    double normalizedInnovationMean = 0.0;
    double normalizedInnovationVariance = 0.0;
    double normalizedInnovationP90 = 0.0;
    double normalizedInnovationLagCorrelation = 0.0;
    double heavyTailFraction = 0.0;
    std::uint64_t highConfidenceStaticSamples = 0;
    std::uint64_t clippingRejectedSamples = 0;
    std::uint64_t textureRejectedSamples = 0;
    std::uint64_t localMotionRejectedSamples = 0;

    float inputPackingMs = 0.0f;
    float coarseKernelMs = 0.0f;
    float coarseReductionMs = 0.0f;
    float observerKernelMs = 0.0f;
    float staticFieldKernelMs = 0.0f;
    float compactReductionAndFitMs = 0.0f; // Legacy trace field name; FASE 6 performs reduction only.
    float synchronizationMs = 0.0f;
    float readbackMs = 0.0f;
    float totalMs = 0.0f;
    std::uint64_t inputBytes = 0;
    std::uint64_t compactReadbackBytes = 0;
    std::uint64_t persistentResidentBytes = 0;
    std::uint64_t persistentAllocationGeneration = 0;
    std::string status = "NOT_RUN";
    std::string failureReason;
};

class VulkanSpectraTemporalObserverBackend final {
public:
    VulkanSpectraTemporalObserverBackend() = default;
    ~VulkanSpectraTemporalObserverBackend() = default;
    VulkanSpectraTemporalObserverBackend(const VulkanSpectraTemporalObserverBackend&) = delete;
    VulkanSpectraTemporalObserverBackend& operator=(const VulkanSpectraTemporalObserverBackend&) = delete;

    SpectraTemporalObserverResult execute(
            VkPhysicalDevice physicalDevice,
            VkDevice device,
            VkQueue computeQueue,
            VkCommandPool commandPool,
            VulkanAllocatorOwner& allocatorOwner,
            const SpectraTemporalObserverRequest& request
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
                            std::uint32_t hostAccess, PersistentBuffer& buffer,
                            bool& reallocated, std::string& failureReason) noexcept;
    void updateDescriptorSetLocked(
            VkDevice device,
            VkBuffer anchorBuffer,
            VkBuffer supportBuffer) noexcept;
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
    VkDescriptorSet descriptorSet_ = VK_NULL_HANDLE;
    VkCommandBuffer commandBuffer_ = VK_NULL_HANDLE;
    VkFence fence_ = VK_NULL_HANDLE;
    VkQueryPool queryPool_ = VK_NULL_HANDLE;
    VmaAllocator allocator_ = nullptr;

    PersistentBuffer anchor_;
    PersistentBuffer supportPacked_;
    PersistentBuffer params_;
    PersistentBuffer coarsePartials_;
    PersistentBuffer observerPartials_;
    PersistentBuffer staticCells_;
    PersistentBuffer histogram_;

    [[maybe_unused]] std::uint64_t allocationGeneration_ = 0;
    bool descriptorBindingsInitialized_ = false;
};

} // namespace bncam::vulkan
