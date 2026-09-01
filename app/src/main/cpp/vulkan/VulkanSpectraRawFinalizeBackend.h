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
 * Milestone 8H-I: JPEG-only RAW finalize stage between SPECTRA Pass 3 and demosaic.
 *
 * Defect correction, green-plane balancing and lens-shading application are pixel-scale
 * operations and therefore stay on Vulkan. The CPU only reduces compact paired green samples
 * to prove a spatially consistent G-even/G-odd response split before a bounded correction is
 * allowed. A successful resident path never materializes the full Bayer mosaic between Pass 3
 * and demosaic.
 */
struct SpectraRawFinalizeRequest {
    const float* mosaicData = nullptr;
    std::uint64_t residentInputGeneration = 0;
    bool deferFullFrameReadback = true;
    std::uint32_t frameWidth = 0;
    std::uint32_t frameHeight = 0;
    std::size_t rowStrideFloats = 0;
    std::uint32_t effectiveCfaPattern = 0;
    std::uint32_t sensorCfaPattern = 0;
    std::int32_t cfaOffsetX = 0;
    std::int32_t cfaOffsetY = 0;
    bool isRaw10 = false;
    bool noiseModelValid = false;
    // Canonical BnCam sensor-noise order [R, Gr, Gb, B] in normalized RAW units.
    std::array<float, 4> effectiveS{0.0f, 0.0f, 0.0f, 0.0f};
    std::array<float, 4> effectiveO{0.0f, 0.0f, 0.0f, 0.0f};

    const float* lensShadingMap = nullptr;
    std::uint32_t lensShadingColumns = 0;
    std::uint32_t lensShadingRows = 0;
    std::uint64_t lensShadingGenerationId = 0;

    // FASE 5 recovery: one signed, scene-relative exposure owner in the finalized RAW domain.
    // Normal production evidence, resolution and scalar CFA application all remain Vulkan-resident.
    // The C++ policy is failure/reference/test only.
    bool adaptiveExposureEnabled = false;
};

struct SpectraRawFinalizeResult {
    bool attempted = false;
    bool success = false;
    bool pipelineAvailable = false;
    // True only after a successful queue submission whose bounded fence wait did
    // not complete. The runtime must keep the submission counted as in-flight.
    bool submissionMayRemainInFlight = false;
    bool residentInputUsed = false;
    bool fullFrameReadbackDeferred = false;
    bool persistentBufferReuseHit = false;
    bool persistentBufferReallocated = false;
    bool greenSplitApplied = false;
    bool lensShadingApplied = false;
    bool timestampQueryUsed = false;
    bool autoSceneMetricsReady = false;
    bool adaptiveExposureRequested = false;
    bool adaptiveExposureApplied = false;
    bool adaptiveExposurePhysicalNoiseModel = false;

    float greenEvenMedian = 0.0f;
    float greenOddMedian = 0.0f;
    float greenEvenScale = 1.0f;
    float greenOddScale = 1.0f;
    std::uint64_t greenSampleCountEven = 0;
    std::uint64_t greenSampleCountOdd = 0;
    std::uint64_t greenPairSampleCount = 0;
    float greenSplitRelativeMedian = 0.0f;
    float greenSplitRelativeMad = 0.0f;
    float greenSplitSignConsensus = 0.0f;
    std::string greenSplitReason = "not_run";
    std::uint64_t autoSceneSampleCount = 0;
    float autoSceneMedianSignal = 0.0f;
    float autoSceneMeanGradient = 0.0f;
    float autoSceneP90Gradient = 0.0f;
    float autoSceneEdgeFraction = 0.0f;
    float autoSceneCoherentEdgeFraction = 0.0f;
    float autoSceneLowSignalFraction = 0.0f;
    std::uint64_t sourceSaturatedPixelCount = 0;
    // Phase 9 source-domain highlight provenance. One float per 2x2 Bayer cell is
    // appended to the resident output buffer and never materialized on the CPU path.
    bool sourceClipConfidenceMapReady = false;
    std::uint64_t sourceClipConfidenceMapBytes = 0;
    std::uint64_t defectCorrectedPixelCount = 0;
    std::uint64_t lensCorrectedPixelCount = 0;
    std::uint64_t overRangePixelCount = 0;
    float lensMaximumGain = 1.0f;

    float exposureSceneP10 = 0.0f;
    float exposureSceneP25 = 0.0f;
    float exposureSceneP50 = 0.0f;
    float exposureSceneP75 = 0.0f;
    float exposureSceneP90 = 0.0f;
    float exposureSceneP95 = 0.0f;
    float exposureSceneP99 = 0.0f;
    float exposureMeasuredSceneDrEv = 0.0f;
    float exposureLowerNeutralBoundaryEv = 0.0f;
    float exposureUpperNeutralBoundaryEv = 0.0f;
    float exposureSpatialAuthority = 0.0f;
    float exposureMinEv = 0.0f;
    float exposureP10Ev = 0.0f;
    float exposureP50Ev = 0.0f;
    float exposureP90Ev = 0.0f;
    float exposureMaxEv = 0.0f;
    float exposureMeanPositiveEv = 0.0f;
    float exposureMeanNegativeEv = 0.0f;
    float exposurePositiveFraction = 0.0f;
    float exposureNeutralFraction = 1.0f;
    float exposureNegativeFraction = 0.0f;
    float exposureMeanGainSquared = 1.0f;
    float exposureP90PositiveGain = 1.0f;
    std::string adaptiveExposureStatus = "NOT_RUN";

    float inputPackingMs = 0.0f;
    float lensMapUploadMs = 0.0f;
    float greenSamplingKernelMs = 0.0f;
    float greenReductionCpuMs = 0.0f;
    float finalizeKernelMs = 0.0f;
    float exposureReductionCpuMs = 0.0f;
    float exposureApplyKernelMs = 0.0f;
    float synchronizationMs = 0.0f;
    float readbackMs = 0.0f;
    float totalMs = 0.0f;

    std::uint64_t inputBytes = 0;
    std::uint64_t outputBytes = 0;
    std::uint64_t compactGreenBytes = 0;
    std::uint64_t persistentResidentBytes = 0;
    std::uint64_t persistentAllocationGeneration = 0;
    std::uint64_t residentOutputGeneration = 0;
    std::vector<float> outputMosaic;
    std::string status = "NOT_RUN";
    std::string failureReason;
};

class VulkanSpectraRawFinalizeBackend final {
public:
    VulkanSpectraRawFinalizeBackend() = default;
    ~VulkanSpectraRawFinalizeBackend() = default;

    VulkanSpectraRawFinalizeBackend(const VulkanSpectraRawFinalizeBackend&) = delete;
    VulkanSpectraRawFinalizeBackend& operator=(const VulkanSpectraRawFinalizeBackend&) = delete;

    SpectraRawFinalizeResult execute(
            VkPhysicalDevice physicalDevice,
            VkDevice device,
            VkQueue computeQueue,
            VkCommandPool commandPool,
            VulkanAllocatorOwner& allocatorOwner,
            const SpectraRawFinalizeRequest& request
    ) noexcept;

    SpectraRawFinalizeResult executeFromResident(
            VkPhysicalDevice physicalDevice,
            VkDevice device,
            VkQueue computeQueue,
            VkCommandPool commandPool,
            VulkanAllocatorOwner& allocatorOwner,
            VkBuffer residentInputBuffer,
            std::uint64_t residentInputBytes,
            const SpectraRawFinalizeRequest& request
    ) noexcept;

    bool resolveResidentOutput(
            std::uint64_t generation,
            VkBuffer& buffer,
            std::uint64_t& bytes,
            std::uint32_t& width,
            std::uint32_t& height
    ) const noexcept;

    /**
     * Failure-recovery only: materialize the exact resident RAW-finalize generation.
     * Normal Vulkan success must hand this generation directly to resident demosaic.
     */
    bool readbackResidentOutput(
            VkDevice device,
            VkQueue computeQueue,
            std::uint64_t generation,
            std::vector<float>& output
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

    SpectraRawFinalizeResult executeInternal(
            VkPhysicalDevice physicalDevice,
            VkDevice device,
            VkQueue computeQueue,
            VkCommandPool commandPool,
            VulkanAllocatorOwner& allocatorOwner,
            VkBuffer residentInputBuffer,
            std::uint64_t residentInputBytes,
            const SpectraRawFinalizeRequest& request
    ) noexcept;

    bool initializeLocked(VkDevice device, VkCommandPool commandPool,
                          std::string& failureReason) noexcept;
    bool ensureBufferLocked(VmaAllocator allocator, std::uint64_t bytes,
                            std::uint32_t hostAccess, PersistentBuffer& buffer,
                            bool& reallocated, std::string& failureReason) noexcept;
    bool ensureLensMapLocked(VmaAllocator allocator, const SpectraRawFinalizeRequest& request,
                             bool& reallocated, float& uploadMs,
                             std::string& failureReason) noexcept;
    void updateDescriptorSetLocked(VkDevice device, VkBuffer inputOverride) noexcept;
    void destroyBuffersLocked() noexcept;
    void destroyLocked(VkDevice device) noexcept;

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

    PersistentBuffer inputStaging_{};
    PersistentBuffer output_{};
    PersistentBuffer readback_{};
    PersistentBuffer greenSamples_{};
    PersistentBuffer telemetry_{};
    PersistentBuffer lensMap_{};

    std::uint64_t lensMapGenerationId_ = 0;
    std::uint32_t lensMapColumns_ = 0;
    std::uint32_t lensMapRows_ = 0;
    std::uint64_t allocationGeneration_ = 0;
    std::uint64_t residentOutputGeneration_ = 0;
    std::uint64_t residentOutputBytes_ = 0;
    std::uint32_t residentOutputWidth_ = 0;
    std::uint32_t residentOutputHeight_ = 0;
};

} // namespace bncam::vulkan
