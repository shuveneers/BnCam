#pragma once

#include "VulkanVmaIntegration.h"

#include <vulkan/vulkan.h>

#include <array>
#include <cstddef>
#include <cstdint>
#include <string>
#include <vector>

namespace bncam::vulkan {

// N006ER temporary compile facade.
// The classical resident post-demosaic spatial/chroma implementation and shader are retired.
// This surface remains only until N006F removes the obsolete VulkanRuntime API. Every execution
// entrypoint is fail-closed and cannot publish or mutate image pixels.

struct SpectraResidentPostDemosaicStripRange {
    std::uint32_t inputOriginY = 0;
    std::uint32_t inputRows = 0;
    std::uint32_t intermediateOriginY = 0;
    std::uint32_t intermediateRows = 0;
    std::uint32_t outputOriginY = 0;
    std::uint32_t outputRows = 0;
};

struct SpectraResidentPostDemosaicRequest {
    const float* rgbData = nullptr;
    std::uint32_t frameWidth = 0;
    std::uint32_t frameHeight = 0;
    std::size_t rowStrideFloats = 0;
    std::uint32_t inputOriginY = 0;
    std::uint32_t inputRows = 0;
    std::uint32_t intermediateOriginY = 0;
    std::uint32_t intermediateRows = 0;
    std::uint32_t outputOriginY = 0;
    std::uint32_t outputRows = 0;
    const float* spatialSigma = nullptr;
    std::uint32_t gridWidth = 0;
    std::uint32_t gridHeight = 0;
    float meanSpatialSigma = 0.0f;

    float appliedLumaSigma = 0.0f;
    float lumaRangeThresholdMean = 0.0f;
    float chromaRangeThresholdMean = 0.0f;
    float outerRingAuthority = 0.0f;
    bool spectraNoiseActive = false;
    bool visibleChromaEnabled = false;
    float profileNrLuminance = 0.0f;
    float profileNrLuminanceDetail = 0.5f;
    float profileNrLuminanceContrast = 0.0f;
    float profileNrColor = 0.0f;
    float profileNrColorDetail = 0.5f;
    float profileNrColorSmoothness = 0.5f;
    float chromaNrStrength = 0.0f;
    float chromaUserScale = 1.0f;
    float downstreamChromaAuthority = 1.0f;
    float inputResidualLumaSigma = 0.0f;
    float downstreamLumaAuthority = 1.0f;
    float profileSpectraLuma = 0.0f;
    float profileSpectraDetail = 0.0f;

    float profileDetailAmount = 0.40f;
    float profileDetailRadius = 1.00f;
    float profileDetailDetail = 0.25f;
    float profileDetailMasking = 0.00f;

    float visibleSigmaY = 0.0f;
    float visibleAuthority = 0.0f;
    float visibleMaximumCorrection = 0.0f;
    float legacySharpenAmount = 0.0f;
    float inverse00 = 0.0f;
    float inverse01 = 0.0f;
    float inverse11 = 0.0f;
    std::uint64_t generationId = 0;

    bool packedBgr8Publication = true;
    const SpectraResidentPostDemosaicStripRange* batchStrips = nullptr;
    std::uint32_t batchStripCount = 0u;

    VkBuffer residentInputBuffer = VK_NULL_HANDLE;
    std::uint64_t residentInputBytes = 0u;
};

struct SpectraResidentPostDemosaicResult {
    bool attempted = false;
    bool success = false;
    bool pipelineAvailable = false;
    bool timestampQueryUsed = false;
    bool persistentBufferReuseHit = false;
    bool persistentBufferReallocated = false;
    bool batchedExecution = false;
    std::uint32_t batchStripCount = 1u;
    std::uint32_t queueSubmitCount = 0u;
    std::uint32_t fenceWaitCount = 0u;
    std::vector<float> outputRgb;
    const void* outputMappedPointer = nullptr;
    std::array<std::uint32_t, 16> spatialCounters{};
    std::array<std::uint32_t, 16> visibleCounters{};
    float inputPackingMs = 0.0f;
    float spatialKernelMs = 0.0f;
    float visibleKernelMs = 0.0f;
    float gpuKernelMs = 0.0f;
    float synchronizationMs = 0.0f;
    float readbackMs = 0.0f;
    float totalMs = 0.0f;
    float transferAndSyncMs = 0.0f;
    std::uint64_t inputBytes = 0;
    bool residentInputUsed = false;
    bool legacySharpenApplied = false;
    bool outputSrgbEncoded = false;
    bool packedBgr8Published = false;
    bool floatOutputFallback = false;
    std::uint64_t outputRowStrideBytes = 0u;
    std::uint64_t intermediateBytes = 0;
    std::uint64_t outputBytes = 0;
    std::uint64_t spatialMapBytes = 0;
    std::uint64_t persistentResidentBytes = 0;
    std::uint64_t persistentAllocationGeneration = 0;
    std::string status = "NOT_RUN";
    std::string failureReason;
};

class VulkanSpectraResidentPostDemosaicBackend final {
public:
    VulkanSpectraResidentPostDemosaicBackend() = default;
    ~VulkanSpectraResidentPostDemosaicBackend() = default;

    VulkanSpectraResidentPostDemosaicBackend(
            const VulkanSpectraResidentPostDemosaicBackend&) = delete;
    VulkanSpectraResidentPostDemosaicBackend& operator=(
            const VulkanSpectraResidentPostDemosaicBackend&) = delete;

    SpectraResidentPostDemosaicResult execute(
            VkPhysicalDevice,
            VkDevice,
            VkQueue,
            VkCommandPool,
            VulkanAllocatorOwner&,
            const SpectraResidentPostDemosaicRequest& request
    ) noexcept {
        SpectraResidentPostDemosaicResult result{};
        result.attempted = true;
        result.pipelineAvailable = false;
        result.batchedExecution = request.batchStrips != nullptr && request.batchStripCount > 0u;
        result.batchStripCount = request.batchStripCount > 0u ? request.batchStripCount : 1u;
        result.status = "RETIRED_N006ER_CLASSICAL_POST_DEMOSAIC_KERNEL";
        result.failureReason = "CLASSICAL_POST_DEMOSAIC_PIXEL_KERNEL_REMOVED";
        return result;
    }

    void destroy(VkDevice) noexcept {}
    bool productionKernelConnected() const noexcept { return false; }
    bool pipelineInitialized() const noexcept { return false; }
};

} // namespace bncam::vulkan
