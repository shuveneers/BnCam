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

enum class SpectraGpuDemosaicAlgorithm : std::uint32_t {
    BILINEAR = 0u,
    MALVAR_2004 = 1u,
    MENON_2007 = 2u,
    NEURAL_JDD = 3u,
    RCD_INSPIRED = NEURAL_JDD, // Legacy ABI alias only; no RCD product route.
    AMAZE = 4u,
    AMAZE_INSPIRED = AMAZE, // Legacy source alias only; product identity is AMaZE.
    AUTO_HYBRID = 5u,
};

struct SpectraResidentDemosaicRequest {
    const float* mosaicData = nullptr;
    // Opaque generation owned by the resident pre-demosaic/chroma graph.
    std::uint64_t residentMosaicGeneration = 0;
    std::uint32_t frameWidth = 0;
    std::uint32_t frameHeight = 0;
    std::size_t rowStrideFloats = 0;
    std::uint32_t cfaPattern = 0;
    SpectraGpuDemosaicAlgorithm algorithm = SpectraGpuDemosaicAlgorithm::MALVAR_2004;

    // Immutable pre-demosaic CFA evidence. Pure Malvar 2004 ignores these values;
    // AMaZE may consume them. Neural JDD uses CFA phase/pattern plus physical noise context.
    // Sampled sensels remain the network input authority.
    float cfaEvidenceAvailable = 0.0f;
    float cfaCommonOpponentSupport = 0.0f;
    float cfaStructureProtection = 0.0f;
    float cfaFineCorrectionConfidence = 0.0f;
    float cfaMidCorrectionConfidence = 0.0f;
    float cfaLowCorrectionConfidence = 0.0f;
    float cfaRedOpponentCorrectionConfidence = 0.0f;
    float cfaBlueOpponentCorrectionConfidence = 0.0f;

    // Phase 5 / Delta 0031: compact physical residual-noise context. These values
    // describe the already-denoised pre-demosaic CFA state; they do not grant
    // denoise authority by themselves.
    float noiseSigmaY = 0.0f;
    float noiseSigmaChroma = 0.0f;
    float noisePressure = 0.0f;

    // Delta 0048: scene-level soft priors. AUTO_HYBRID combines them with local
    // structure/Nyquist/chroma/noise evidence; they are never hard route selectors.
    float autoMalvarPrior = 1.0f / 3.0f;
    float autoNeuralJddPrior = 1.0f / 3.0f;
    float autoAmazePrior = 1.0f / 3.0f;
};

struct SpectraResidentDemosaicResult {
    bool attempted = false;
    bool success = false;
    bool pipelineAvailable = false;
    bool timestampQueryUsed = false;
    bool persistentBufferReuseHit = false;
    bool persistentBufferReallocated = false;
    bool gpuUsedForOutput = false;
    bool cpuFallbackRequired = false;
    bool residentInputUsed = false;
    bool fullReadbackDeferred = false;
    std::vector<float> outputRgb;
    // Compact GPU-generated flat-region residual candidates. Eight floats per sample:
    // hpY, hpRG, hpBG, structure, tileIndex, (validFlag + centerY), centerRG, centerBG.
    // Slot 5 is 0 for invalid samples and 1+linearLuma for valid samples, preserving the
    // compact record size. The final opponent-colour values expose a coarse chroma field without a full RGB
    // readback + CPU scan at the demosaic/colour observability boundaries.
    std::vector<float> residualCandidates;
    std::uint32_t residualSampleStride = 0;
    std::uint32_t residualSampleColumns = 0;
    std::uint32_t residualSampleRows = 0;
    float inputPackingMs = 0.0f;
    float uploadMs = 0.0f;
    float kernelMs = 0.0f;
    float amazeGreenPassMs = 0.0f;
    float amazeReconstructPassMs = 0.0f;
    float autoHybridGuidePassMs = 0.0f;
    float autoHybridBlendPassMs = 0.0f;
    float residualKernelMs = 0.0f;
    float readbackMs = 0.0f;
    float synchronizationMs = 0.0f;
    float totalMs = 0.0f;
    std::uint64_t inputBytes = 0;
    std::uint64_t outputBytes = 0;
    std::uint64_t persistentResidentBytes = 0;
    std::uint64_t persistentAllocationGeneration = 0;
    std::uint64_t residentDemosaicGeneration = 0;
    std::string status = "NOT_RUN";
    std::string failureReason;
};

struct SpectraResidentColorTransformRequest {
    // CPU RGB is only required when demosaic did not run in this backend (for example the
    // temporary Menon fallback). A matching resident generation consumes deviceOutput_ directly.
    const float* rgbData = nullptr;
    std::uint32_t frameWidth = 0;
    std::uint32_t frameHeight = 0;
    std::size_t rowStrideFloats = 0;
    std::uint64_t residentDemosaicGeneration = 0;
    std::array<float, 3> wbRgb{1.0f, 1.0f, 1.0f};
    // Delta 35: bounded linear-RGB opponent stabilization fused immediately before WB.
    // Values are global maximum blends; the shader applies additional local structure/color-edge gates.
    std::array<float, 2> preWbOpponentCleanupBlend{0.0f, 0.0f}; // R-G, B-G; each <= 0.12
    // Optional coarse cloud-correction map consumed by the resident pre-WB shader. Pointers
    // remain valid for the synchronous executeAwbCcm() call and refer to a median-centred
    // 16x12 opponent field.
    const float* preWbCloudCorrectionRG = nullptr;
    const float* preWbCloudCorrectionBG = nullptr;
    const std::uint8_t* preWbCloudCorrectionValid = nullptr;
    std::uint32_t preWbCloudGridColumns = 0u;
    std::uint32_t preWbCloudGridRows = 0u;
    std::size_t preWbCloudValidTileCount = 0u;
    float preWbCloudMaxAbsoluteCorrection = 0.0f;
    bool preWbCloudCorrectionReady = false;
    std::array<float, 9> colorMatrix{1.0f, 0.0f, 0.0f,
                                     0.0f, 1.0f, 0.0f,
                                     0.0f, 0.0f, 1.0f};
    // 8H-J keeps post-CCM RGB resident for highlight/scene/tone Vulkan processing.
    bool deferFullReadback = false;
};

struct SpectraResidentColorTransformResult {
    bool attempted = false;
    bool success = false;
    bool residentInputUsed = false;
    bool cpuRgbUploadUsed = false;
    bool persistentBufferReuseHit = false;
    bool persistentBufferReallocated = false;
    bool fullReadbackDeferred = false;
    // Coarse cloud-correction transport/application telemetry.
    bool cloudCorrectionMapUploaded = false;
    bool cloudCorrectionApplied = false;
    std::uint64_t cloudCorrectionMapBytes = 0u;
    double cloudMeanAbsCorrectionRG = 0.0;
    double cloudMeanAbsCorrectionBG = 0.0;
    double cloudAffectedPixelFraction = 0.0;
    std::uint64_t residentColorGeneration = 0;
    std::vector<float> outputRgb;
    std::vector<float> residualCandidates;
    std::uint32_t residualSampleStride = 0;
    std::uint32_t residualSampleColumns = 0;
    std::uint32_t residualSampleRows = 0;
    std::array<double, 3> rawMean{0.0, 0.0, 0.0};
    std::array<double, 3> wbMean{0.0, 0.0, 0.0};
    std::array<double, 3> ccmMean{0.0, 0.0, 0.0};
    float inputPackingMs = 0.0f;
    float kernelMs = 0.0f;
    float residualKernelMs = 0.0f;
    float readbackMs = 0.0f;
    float synchronizationMs = 0.0f;
    float compactStatisticsReductionMs = 0.0f;
    float totalMs = 0.0f;
    std::uint64_t compactStatisticsBytes = 0;
    std::uint64_t persistentResidentBytes = 0;
    std::uint64_t persistentAllocationGeneration = 0;
    std::string status = "NOT_RUN";
    std::string failureReason;
};

/**
 * Milestone 8H-E/F resident demosaic + colour backend.
 *
 * Malvar, Neural JDD, AMaZE and Auto Hybrid are GPU-primary. Menon/Bilinear remain reference-only legacy paths. A successful demosaic keeps its RGB in deviceOutput_; AWB+CCM
 * can consume that generation directly, transform in-place, reduce compact colour statistics,
 * and perform one final RGB readback without a duplicate CPU colour pass.
 */
class VulkanSpectraResidentDemosaicBackend final {
public:
    VulkanSpectraResidentDemosaicBackend() = default;
    ~VulkanSpectraResidentDemosaicBackend() = default;

    VulkanSpectraResidentDemosaicBackend(const VulkanSpectraResidentDemosaicBackend&) = delete;
    VulkanSpectraResidentDemosaicBackend& operator=(const VulkanSpectraResidentDemosaicBackend&) = delete;

    SpectraResidentDemosaicResult execute(
            VkPhysicalDevice physicalDevice,
            VkDevice device,
            VkQueue computeQueue,
            VkCommandPool commandPool,
            VulkanAllocatorOwner& allocatorOwner,
            const SpectraResidentDemosaicRequest& request
    ) noexcept;

    SpectraResidentDemosaicResult executeFromResidentMosaic(
            VkPhysicalDevice physicalDevice,
            VkDevice device,
            VkQueue computeQueue,
            VkCommandPool commandPool,
            VulkanAllocatorOwner& allocatorOwner,
            VkBuffer residentInputBuffer,
            std::uint64_t residentInputBytes,
            const SpectraResidentDemosaicRequest& request
    ) noexcept;

    SpectraResidentColorTransformResult executeAwbCcm(
            VkPhysicalDevice physicalDevice,
            VkDevice device,
            VkQueue computeQueue,
            VkCommandPool commandPool,
            VulkanAllocatorOwner& allocatorOwner,
            const SpectraResidentColorTransformRequest& request
    ) noexcept;

    bool resolveResidentColorOutput(
            std::uint64_t generation,
            VkBuffer& buffer,
            std::uint64_t& bytes,
            std::uint32_t& width,
            std::uint32_t& height
    ) const noexcept;

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
    void updateDescriptorSetLocked(VkDevice device, VkBuffer inputOverride = VK_NULL_HANDLE,
                                   VkBuffer scratchOverride = VK_NULL_HANDLE) noexcept;
    SpectraResidentDemosaicResult executeInternal(
            VkPhysicalDevice physicalDevice,
            VkDevice device,
            VkQueue computeQueue,
            VkCommandPool commandPool,
            VulkanAllocatorOwner& allocatorOwner,
            VkBuffer residentInputBuffer,
            std::uint64_t residentInputBytes,
            const SpectraResidentDemosaicRequest& request
    ) noexcept;
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
    PersistentBuffer inputStaging_;
    PersistentBuffer outputReadback_;
    PersistentBuffer deviceInput_;
    PersistentBuffer deviceOutput_;
    // Reused by mutually exclusive roles: CPU RGB upload for typed fallback, AMaZE resident
    // guide scratch, or Auto-Hybrid guide scratch (green, Nyquist score, local structure).
    PersistentBuffer rgbUpload_;
    // Twelve float sums per 16x16 workgroup: raw/WB/CCM means plus cloud-correction telemetry.
    PersistentBuffer colorStatistics_;
    // Delta 46: compact 16x12 vec4 map: correctionRG, correctionBG, valid, reserved.
    PersistentBuffer cloudCorrectionMap_;
    // Six floats per sampled residual point; host-visible because the CPU only performs the
    // compact percentile/tile reduction, never another full-frame RGB scan.
    PersistentBuffer residualCandidates_;
    std::uint64_t residentDemosaicGeneration_ = 0;
    std::uint32_t residentWidth_ = 0;
    std::uint32_t residentHeight_ = 0;
    bool residentDemosaicValid_ = false;
    std::uint64_t residentColorGeneration_ = 0;
    bool residentColorValid_ = false;
    std::uint64_t allocationGeneration_ = 0;
    bool descriptorBindingsInitialized_ = false;
};

} // namespace bncam::vulkan
