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

    // Phase 6: topology-gated post-demosaic opponent cleanup. This is not generic NR: it
    // may only shrink physically significant isolated/zipper/overshoot chroma residuals.
    // Green/luma reconstruction is never mutated and the route is disabled without physical sigma.
    bool phase6ResidualChromaEnabled = false;
    float phase6MaximumBlend = 0.92f;
    float phase6MaximumCorrection = 0.12f;

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
    bool phase6ResidualChromaRequested = false;
    bool phase6ResidualChromaUsedForOutput = false;
    float phase6ClassifyPassMs = 0.0f;
    float phase6CorrectPassMs = 0.0f;
    std::uint64_t phase6ProcessedPixels = 0u;
    std::uint64_t phase6CandidatePixels = 0u;
    std::uint64_t phase6IsolatedOutlierPixels = 0u;
    std::uint64_t phase6ZipperPixels = 0u;
    std::uint64_t phase6EdgeProtectedPixels = 0u;
    std::uint64_t phase6SaturatedDetailProtectedPixels = 0u;
    double phase6MeanAbsCorrectionRG = 0.0;
    double phase6MeanAbsCorrectionBG = 0.0;
    float phase6MaximumAbsoluteCorrection = 0.0f;
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
    // Mandatory developed-RAW baseline chroma stabilization, independent of SPECTRA.
    // The CPU policy derives these values from physical S/O covariance, the compact residual
    // observer and downstream WB/CCM amplification. The shader still owns local detail gates.
    std::array<float, 2> preWbOpponentCleanupBlend{0.0f, 0.0f}; // R-G, B-G; each <= 0.60
    float baselineChromaSigmaRg = 0.0f;
    float baselineChromaSigmaBg = 0.0f;
    float baselineLumaSigma = 0.0f;
    float baselineNoiseConfidence = 0.0f;
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
    // Phase 2 calibrated camera characterization. The LUT is accepted only after IspCore has
    // resolved a trusted profile and its paired DNG ForwardMatrix transform. Table storage is
    // canonical dense value-hue-saturation order, three floats per entry.
    bool calibratedHueSatMapEnabled = false;
    std::uint32_t hueSatHueDivisions = 0u;
    std::uint32_t hueSatSaturationDivisions = 0u;
    std::uint32_t hueSatValueDivisions = 0u;
    std::uint32_t hueSatEncoding = 0u; // DNG: 0 linear, 1 sRGB value encoding.
    const float* hueSatData1 = nullptr;
    std::size_t hueSatData1FloatCount = 0u;
    const float* hueSatData2 = nullptr;
    std::size_t hueSatData2FloatCount = 0u;
    float hueSatWeightFirst = 1.0f;
    float hueSatWeightSecond = 0.0f;
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
    // Baseline RAW chroma cleanup telemetry. Green/luma is never filtered by this owner.
    bool baselineChromaCleanupApplied = false;
    double baselineMeanAbsCorrectionRG = 0.0;
    double baselineMeanAbsCorrectionBG = 0.0;
    double baselineAffectedPixelFraction = 0.0;
    bool calibratedHueSatMapRequested = false;
    bool calibratedHueSatMapApplied = false;
    std::uint64_t calibratedHueSatMapAppliedPixels = 0u;
    std::uint64_t calibratedHueSatMapProfileBytes = 0u;
    float calibratedHueSatMapWeightFirst = 1.0f;
    float calibratedHueSatMapWeightSecond = 0.0f;
    std::uint64_t residentColorGeneration = 0;
    std::vector<float> outputRgb;
    std::vector<float> residualCandidates;
    std::uint32_t residualSampleStride = 0;
    std::uint32_t residualSampleColumns = 0;
    std::uint32_t residualSampleRows = 0;
    std::array<double, 3> rawMean{0.0, 0.0, 0.0};
    std::array<double, 3> wbMean{0.0, 0.0, 0.0};
    std::array<double, 3> ccmMean{0.0, 0.0, 0.0};
    // Phase 9 compact classification/protection telemetry from the same resident AWB+CCM pass.
    std::uint64_t phase9SensorClipCandidatePixels = 0u;
    std::uint64_t phase9SingleChannelSensorClipPixels = 0u;
    std::uint64_t phase9MultiChannelSensorClipPixels = 0u;
    std::uint64_t phase9WbAboveUnityWithoutSensorClipPixels = 0u;
    std::uint64_t phase9CcmNegativeExcursionPixels = 0u;
    std::uint64_t phase9ColorConfidenceAppliedPixels = 0u;
    std::uint64_t phase9GamutCompressedPixels = 0u;
    std::uint64_t phase9LegacyMagentaRiskPixels = 0u;
    std::uint64_t phase9ProtectedMagentaRiskPixels = 0u;
    std::uint64_t phase9SceneLinearOverUnityPixels = 0u;
    std::uint64_t phase9FullySensorClippedPixels = 0u;
    std::uint64_t phase9PartialColorConfidencePixels = 0u;
    // Source-domain clipping provenance recovered from the RawFinalize 2x2 Bayer-cell map.
    bool phase9SourceRawConfidenceMapUsed = false;
    std::uint64_t phase9SourceRawConfidenceMapBytes = 0u;
    std::uint64_t phase9SourceRawConfidenceCandidatePixels = 0u;
    std::uint64_t phase9SourceRawZeroConfidencePixels = 0u;
    std::uint64_t phase9SourceRawPartialConfidencePixels = 0u;
    std::uint64_t phase9SourceRawDemosaicDisagreementPixels = 0u;
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

    /** Prepare persistent single-frame still buffers without processing pixels. */
    bool prepareWorkingSet(
            VkDevice device,
            VkCommandPool commandPool,
            VulkanAllocatorOwner& allocatorOwner,
            std::uint32_t frameWidth,
            std::uint32_t frameHeight,
            std::string& failureReason
    ) noexcept;

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
    // Reused by mutually exclusive roles: CPU RGB upload for typed fallback, AMaZE/Auto-Hybrid
    // guide scratch, or Phase-6 vec2-per-pixel residual correction scratch (RG/BG only).
    PersistentBuffer rgbUpload_;
    // Twelve float sums per 16x16 workgroup: raw/WB/CCM means plus cloud-correction telemetry.
    PersistentBuffer colorStatistics_;
    // Phase 9 + calibrated camera-profile compact counters only; no full-frame evidence readback.
    // Words [12..15] verify source-RAW confidence. Words [16..19] are reserved for the
    // trusted DNG HueSatMap application contract.
    PersistentBuffer colorTelemetry_;
    // D124: owned half-resolution confidence map copied from the appended RawFinalize
    // transport tail during the demosaic submission. It survives independently of the
    // RawFinalize ping-pong buffer until this exact demosaic generation reaches AWB+CCM.
    PersistentBuffer sourceClipConfidence_;
    // Delta 46: compact 16x12 vec4 map: correctionRG, correctionBG, valid, reserved.
    PersistentBuffer cloudCorrectionMap_;
    // Phase 2: persistent host-uploaded trusted DNG HueSatMap profile. Header (16 floats),
    // followed by dense data1 and optional data2. No generated/synthetic LUT is accepted.
    PersistentBuffer hueSatProfile_;
    // Six floats per sampled residual point; host-visible because the CPU only performs the
    // compact percentile/tile reduction, never another full-frame RGB scan.
    PersistentBuffer residualCandidates_;
    std::uint64_t residentDemosaicGeneration_ = 0;
    std::uint32_t residentWidth_ = 0;
    std::uint32_t residentHeight_ = 0;
    bool residentDemosaicValid_ = false;
    bool sourceClipConfidenceValid_ = false;
    std::uint64_t sourceClipConfidenceDemosaicGeneration_ = 0u;
    std::uint32_t sourceClipConfidenceWidth_ = 0u;
    std::uint32_t sourceClipConfidenceHeight_ = 0u;
    std::uint64_t residentColorGeneration_ = 0;
    bool residentColorValid_ = false;
    std::uint64_t allocationGeneration_ = 0;
    bool descriptorBindingsInitialized_ = false;
};

} // namespace bncam::vulkan
