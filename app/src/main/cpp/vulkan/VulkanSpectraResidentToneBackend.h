#pragma once

#include "VulkanVmaIntegration.h"

#include <vulkan/vulkan.h>

#include <cstddef>
#include <cstdint>
#include <mutex>
#include <string>
#include <vector>

namespace bncam::vulkan {

struct SpectraResidentSceneObserverRequest {
    std::uint32_t frameWidth = 0;
    std::uint32_t frameHeight = 0;
    std::uint32_t targetSampleCount = 50000u;
    // QUALITY DELTA 0008: full-resolution scene-linear opponent cleanup fused
    // into the post-AWB/CCM highlight pass, before scene observation/tone.
    // Disabled is bit-identical and requires no extra full-frame scratch.
    bool preToneChroma444Enabled = false;
    float preToneChroma444Strength = 0.0f;
    // Phase 9 Delta 0063: measured physical-noise and WB+CCM amplification evidence
    // for the resident 16x16 tile chroma model. No ISO/format heuristic is passed.
    float preToneChromaNoisePressure = 0.0f;
    float preToneChromaWbCcmPressure = 0.0f;
    // DELTA 0093: post-WB/CCM physical covariance in the exact Y,(R-Y),(B-Y) basis.
    // Mode 0 uses this to whiten chroma residuals before firm shrinkage; no display-black
    // threshold or device/model-specific constant is required.
    bool preToneChromaCovarianceWhiteningEnabled = false;
    float preToneChromaVarianceY = 0.0f;
    float preToneChromaVarianceC1 = 0.0f;
    float preToneChromaVarianceC2 = 0.0f;
    float preToneChromaCovarianceC1C2 = 0.0f;
    float preToneChromaReferenceSignal = 0.10f;
    float preToneChromaShotNoiseFraction = 0.50f;
    float preToneChromaModelConfidence = 0.0f;
    float preToneChromaFullShrinkSigma = 1.10f;
    float preToneChromaPreserveSigma = 4.50f;
};

struct SpectraResidentSceneObserverResult {
    bool attempted = false;
    bool success = false;
    bool residentInputUsed = false;
    bool persistentBufferReuseHit = false;
    bool persistentBufferReallocated = false;
    // Phase 9 moved RAW highlight reconstruction upstream into AWB+CCM. Retained as
    // compatibility/debug fields; the scene observer no longer mutates highlights and these stay zero.
    bool highlightRecoveryApplied = false;
    std::uint64_t correctedHighlightPixels = 0;
    bool preToneChroma444Applied = false;
    float preToneChroma444Strength = 0.0f;
    bool preToneChromaTileScanApplied = false;
    std::uint64_t preToneChromaTilesScanned = 0u;
    std::uint64_t preToneChromaEligibleTiles = 0u;
    std::uint64_t preToneChromaCorrectedPixels = 0u;
    std::uint64_t preToneChromaDetailProtectedPixels = 0u;
    std::uint64_t preToneChromaStructuredTiles = 0u;
    float preToneChromaMeanNoisePressure = 0.0f;
    float preToneChromaMeanResidualSigma = 0.0f;
    float preToneChromaMaxCorrection = 0.0f;
    bool preToneChromaCovarianceWhiteningApplied = false;
    std::uint64_t preToneChromaCovarianceEvaluatedPixels = 0u;
    std::uint64_t preToneChromaNearBlackPixels = 0u;
    std::uint64_t preToneChromaStrongShrinkPixels = 0u;
    std::uint64_t preToneChromaPreservedEvidencePixels = 0u;
    std::uint64_t preToneChromaCovarianceFallbackPixels = 0u;
    float preToneChromaMeanShrinkAuthority = 0.0f;
    float preToneChromaMaxMahalanobisRadius = 0.0f;
    std::uint32_t sampleStep = 1u;
    std::uint32_t sampleCount = 0u;
    std::vector<float> sampledRgb;  // tightly packed R,G,B triples
    std::vector<float> displayGrid; // 32x24 luma field
    float highlightKernelMs = 0.0f;
    float sampleKernelMs = 0.0f;
    float compactReadbackMs = 0.0f;
    float synchronizationMs = 0.0f;
    float totalMs = 0.0f;
    std::uint64_t compactBytes = 0u;
    std::uint64_t persistentResidentBytes = 0u;
    std::uint64_t persistentAllocationGeneration = 0u;
    std::uint64_t residentSceneGeneration = 0u;
    std::string status = "NOT_RUN";
    std::string failureReason;
};

struct SpectraResidentToneRequest {
    std::uint32_t frameWidth = 0;
    std::uint32_t frameHeight = 0;
    std::uint64_t residentSceneGeneration = 0u;
    float exposureGain = 1.0f;
    // Mode-specific transport slots remain in the 128-byte shader ABI for the scene observer/YUV
    // compatibility path. RAW Phase 5 does not use them for automatic colour or display tone.
    float rawJpegBaseVibrance = 1.0f;
    float shoulderStart = 0.68f;
    float shoulderStrength = 1.0f;
    // Legacy YUV local-tone fields are source-compatible only; RAW uses the FLLF contract below.
    float localToneStrength = 0.0f;
    float localToneSceneKey = 0.125f;
    float localToneMaxLiftEv = 0.25f;
    float localToneMaxCompressEv = 0.15f;
    // Phase 5: primary automatic RAW tone authority in pure scene-referred log2 luminance.
    bool fllfEnabled = false;
    float fllfStrength = 0.0f;
    float fllfSceneKey = 0.150f;
    float fllfMaxLiftEv = 0.18f;
    float fllfMaxCompressEv = 0.20f;
    float fllfEdgeStopEv = 0.62f;
    float fllfRefinement = 0.10f;
    // Physical post-detail luma sigma propagated through LSC -> spatial exposure -> demosaic ->
    // AWB -> CCM -> detail. Vulkan converts it to log2-luma sigma for correction coring.
    float fllfPhysicalNoiseSigmaY = 0.0f;
    std::uint32_t fllfPyramidLevels = 6u;
    // Phase 11: scene-linear capture detail recovery. The physical S/O-derived luma sigma
    // and shot/read-noise mix are resolved by IspCore; all per-pixel significance, edge and
    // halo decisions execute on the resident GPU scene before Phase-5 FLLF/display mapping.
    bool linearDetailEnabled = false;
    float linearDetailAuthority = 0.0f;
    float linearDetailRadius = 1.0f;
    float linearDetailEmphasis = 0.25f;
    float linearDetailMasking = 0.0f;
    float linearDetailMinimumResidualSnr = 1.5f;
    float linearDetailMinimumGradientSnr = 1.0f;
    float linearDetailHardHaloLimit = 0.02f;
    float linearDetailNoiseSigmaY = 0.0f;
    float linearDetailReferenceSignal = 0.10f;
    float linearDetailShotNoiseFraction = 0.5f;
    float linearDetailModelConfidence = 0.0f;
    // Phase 12: profile-owned perceptual/output detail, executed only after the RAW display
    // transform. It is narrow-band and does not own local exposure/contrast (FLLF remains sole owner).
    bool perceptualDetailEnabled = false;
    float perceptualDetailAuthority = 0.0f;
    float perceptualDetailRadius = 0.0f;
    float perceptualDetailEmphasis = 0.0f;
    float perceptualDetailMasking = 0.0f;
    float perceptualDetailNoiseSigmaY = 0.0f;
    float perceptualDetailMinimumResidualSnr = 1.5f;
    float perceptualDetailMinimumGradientSnr = 1.0f;
    float perceptualDetailHardHaloLimit = 0.0f;
    float perceptualDetailModelConfidence = 0.0f;
    bool isRawBayer = true;
    float profileColorSaturation = 0.0f;
    float profileColorContrast = 0.0f;
    float profilePresenceVibrance = 0.0f;
    // Two floats per LUT entry: curvedLuma, safeMidtoneGate. Exactly 4096 entries.
    const float* toneLut = nullptr;
    std::size_t toneLutFloatCount = 0u;
    bool deferFullReadback = false;
    // Ultra HDR gainmap generation is entirely Vulkan/GPU based. The CPU only receives the
    // already quantized, quarter-resolution gainmap artifact and compact scalar metadata.
    bool ultraHdrGainmapRequested = false;
    int outputRotationDegrees = 0;
    bool portraitEffectRequested = false;
    const float* portraitMask = nullptr;
    std::size_t portraitMaskFloatCount = 0u;
    std::uint32_t portraitMaskWidth = 0u;
    std::uint32_t portraitMaskHeight = 0u;
    float portraitTargetLeft = 0.0f;
    float portraitTargetTop = 0.0f;
    float portraitTargetRight = 0.0f;
    float portraitTargetBottom = 0.0f;
    std::uint32_t portraitMaskRotationDegrees = 0u;
};

struct SpectraResidentToneResult {
    bool attempted = false;
    bool success = false;
    bool residentInputUsed = false;
    bool persistentBufferReuseHit = false;
    bool persistentBufferReallocated = false;
    bool fullReadbackDeferred = false;
    bool highlightNeutralizeApplied = false;
    bool localToneRequested = false;
    bool localToneApplied = false;
    std::uint64_t localToneAdjustedPixels = 0u;
    bool fllfRequested = false;
    bool fllfApplied = false;
    std::uint64_t fllfAdjustedPixels = 0u;
    std::uint64_t fllfEdgeProtectedSamples = 0u;
    float fllfMeanAbsCorrectionEv = 0.0f;
    float fllfMaxAbsCorrectionEv = 0.0f;
    // Physical post-detail luma sigma propagated through LSC -> spatial exposure -> demosaic ->
    // AWB -> CCM -> detail. Vulkan converts it to log2-luma sigma for correction coring.
    float fllfPhysicalNoiseSigmaY = 0.0f;
    float fllfPyramidBuildMs = 0.0f;
    float fllfRemapReconstructMs = 0.0f;
    std::uint64_t fllfResidentBytes = 0u;
    bool linearDetailRequested = false;
    bool linearDetailApplied = false;
    std::uint64_t linearDetailEvaluatedPixels = 0u;
    std::uint64_t linearDetailChangedPixels = 0u;
    std::uint64_t linearDetailEdgeSupportedPixels = 0u;
    std::uint64_t linearDetailNoiseRejectedPixels = 0u;
    std::uint64_t linearDetailHaloClampedPixels = 0u;
    float linearDetailMeanAbsCorrection = 0.0f;
    float linearDetailMaxAbsCorrection = 0.0f;
    float linearDetailKernelMs = 0.0f;
    std::uint64_t linearDetailScratchBytes = 0u;
    bool perceptualDetailRequested = false;
    bool perceptualDetailApplied = false;
    std::uint64_t perceptualDetailEvaluatedPixels = 0u;
    std::uint64_t perceptualDetailChangedPixels = 0u;
    std::uint64_t perceptualDetailEdgeSupportedPixels = 0u;
    std::uint64_t perceptualDetailNoiseRejectedPixels = 0u;
    std::uint64_t perceptualDetailHaloClampedPixels = 0u;
    float perceptualDetailMeanAbsCorrection = 0.0f;
    float perceptualDetailMaxAbsCorrection = 0.0f;
    float perceptualDetailKernelMs = 0.0f;
    std::uint64_t perceptualDetailScratchBytes = 0u;
    std::vector<float> outputRgb;
    float lutUploadMs = 0.0f;
    float kernelMs = 0.0f;
    float readbackMs = 0.0f;
    float synchronizationMs = 0.0f;
    float totalMs = 0.0f;
    std::uint64_t persistentResidentBytes = 0u;
    std::uint64_t persistentAllocationGeneration = 0u;
    std::uint64_t residentToneGeneration = 0u;
    bool ultraHdrGainmapRequested = false;
    bool ultraHdrGainmapGenerated = false;
    bool ultraHdrMeaningfulHeadroom = false;
    std::uint32_t ultraHdrGainmapWidth = 0u;
    std::uint32_t ultraHdrGainmapHeight = 0u;
    std::uint32_t ultraHdrGainmapRowStrideBytes = 0u;
    float ultraHdrMinContentBoost = 1.0f;
    float ultraHdrMaxContentBoost = 1.0f;
    float ultraHdrGamma = 1.0f;
    float ultraHdrOffsetSdr = 1.0f / 64.0f;
    float ultraHdrOffsetHdr = 1.0f / 64.0f;
    std::vector<std::uint8_t> ultraHdrGainmapBytes;
    std::string ultraHdrStatus = "NOT_REQUESTED";
    bool portraitEffectRequested = false;
    bool portraitEffectApplied = false;
    std::string portraitStatus = "NOT_REQUESTED";
    std::string status = "NOT_RUN";
    std::string failureReason;
};

/**
 * Milestone 8H-J resident post-colour scene/tone backend.
 *
 * The backend consumes the post-CCM device buffer owned by the resident demosaic backend.
 * Upstream Phase-9-protected RGB, pre-tone 4:4:4 cleanup, compact scene sampling and the
 * complete pointwise tone/vibrance/profile-colour loop execute on Vulkan. Only compact scene samples cross to the CPU before
 * the scalar scene planner. The tone result can remain resident for the next post-demosaic stage.
 */
class VulkanSpectraResidentToneBackend final {
public:
    SpectraResidentSceneObserverResult executeSceneObserverFromResident(
            VkPhysicalDevice physicalDevice,
            VkDevice device,
            VkQueue computeQueue,
            VkCommandPool commandPool,
            VulkanAllocatorOwner& allocatorOwner,
            VkBuffer residentInputBuffer,
            std::uint64_t residentInputBytes,
            const SpectraResidentSceneObserverRequest& request
    ) noexcept;

    SpectraResidentToneResult executeTone(
            VkPhysicalDevice physicalDevice,
            VkDevice device,
            VkQueue computeQueue,
            VkCommandPool commandPool,
            VulkanAllocatorOwner& allocatorOwner,
            const SpectraResidentToneRequest& request
    ) noexcept;

    bool resolveResidentOutput(
            std::uint64_t generation,
            VkBuffer& buffer,
            std::uint64_t& bytes,
            std::uint32_t& width,
            std::uint32_t& height
    ) const noexcept;

    /** Failure-only materialization of an already computed resident tone generation. */
    bool readbackResidentOutput(
            VkDevice device,
            VkQueue computeQueue,
            VulkanAllocatorOwner& allocatorOwner,
            std::uint64_t generation,
            std::vector<float>& outputRgb,
            std::string& failureReason
    ) noexcept;

    void destroy(VkDevice device) noexcept;
    bool productionKernelConnected() const noexcept;

private:
    struct PersistentBuffer {
        VkBuffer buffer = VK_NULL_HANDLE;
        VmaAllocation allocation = nullptr;
        void* mapped = nullptr;
        std::uint64_t capacityBytes = 0u;
    };

    bool initializeLocked(VkDevice device, VkCommandPool commandPool,
                          std::string& failureReason) noexcept;
    bool ensureBufferLocked(VmaAllocator allocator, std::uint64_t bytes,
                            std::uint32_t hostAccess, PersistentBuffer& buffer,
                            bool& reallocated, std::string& failureReason) noexcept;
    void updateDescriptorsLocked(VkDevice device, VkBuffer inputOverride) noexcept;
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
    PersistentBuffer workingRgb_;
    PersistentBuffer compact_;
    PersistentBuffer toneLut_;
    PersistentBuffer readback_;
    PersistentBuffer telemetry_;
    PersistentBuffer ultraHdrLuma_;
    PersistentBuffer ultraHdrGainLog_;
    PersistentBuffer ultraHdrGainmapPacked_;
    PersistentBuffer portraitMask_;
    PersistentBuffer portraitBlurRgb_;
    // Legacy quarter-resolution YUV local-tone base. Not used by RAW Phase 5.
    PersistentBuffer localToneBase_;
    // Phase 5 FLLF: packed half-resolution Gaussian pyramid and reconstructed log-luma tone
    // correction pyramid. Two scalar buffers keep memory bounded and avoid full-resolution RGB
    // intermediates.
    PersistentBuffer fllfGaussian_;
    PersistentBuffer fllfCorrection_;
    [[maybe_unused]] std::uint64_t allocationGeneration_ = 0u;
    [[maybe_unused]] std::uint64_t residentSceneGeneration_ = 0u;
    std::uint64_t residentToneGeneration_ = 0u;
    std::uint32_t residentWidth_ = 0u;
    std::uint32_t residentHeight_ = 0u;
    bool residentSceneValid_ = false;
    bool residentToneValid_ = false;
};

} // namespace bncam::vulkan
