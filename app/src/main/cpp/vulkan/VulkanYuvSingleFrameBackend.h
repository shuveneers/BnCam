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

struct YuvSingleFrameIspRequest {
    const std::uint8_t* nv21 = nullptr;
    std::size_t nv21Bytes = 0u;
    // Phase 13 opaque residency token. Runtime resolves this under the submission lock.
    std::uint64_t residentLumaGeneration = 0u;
    // Runtime-internal Phase-13 handoff. When set, Y comes from this resident device buffer
    // and only the anchor NV21 chroma plane is staged from CPU memory.
    VkBuffer residentLumaBuffer = VK_NULL_HANDLE;
    std::uint64_t residentLumaBytes = 0u;
    // Optional Computational-HDR authority produced by VulkanYuvMultiFrameBackend.
    // These buffers remain GPU-resident; the single-frame ISP derives the Ultra HDR
    // gainmap directly on Vulkan and never reads HDR pixels on the CPU.
    bool ultraHdrGainmapRequested = false;
    VkBuffer residentHdrAccumulatorBuffer = VK_NULL_HANDLE;
    VkBuffer residentHdrWeightBuffer = VK_NULL_HANDLE;
    std::uint64_t residentHdrBytes = 0u;
    std::uint32_t width = 0u;
    std::uint32_t height = 0u;
    std::uint32_t rotationDegrees = 0u;
    std::array<std::uint32_t, 256> toneLut{};
    float wbRed = 1.0f;
    float wbGreen = 1.0f;
    float wbBlue = 1.0f;
    float saturation = 0.0f;
    float contrast = 0.0f;
    float vibrance = 0.0f;
    // Profile-owned output detail is true identity by default. FASE 11/12 RAW detail
    // ownership must never leak an old hidden sharpening default into the YUV route.
    float profileDetailAmount = 0.0f;
    float profileDetailRadius = 0.0f;
    float profileDetailDetail = 0.0f;
    float profileDetailMasking = 0.0f;
    // Creative profile NR ceilings only. FASE 13 derives the baseline from measured
    // residual Y/U/V evidence on GPU; capture ISO is not a physical YUV noise model.
    float profileNrLumaBlend = 0.0f;
    float profileNrLumaProtection = 0.65f;
    float profileNrChromaBlend = 0.0f;
    float profileNrChromaProtection = 0.60f;
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

struct YuvSingleFrameIspResult {
    bool attempted = false;
    bool success = false;
    bool submissionMayRemainInFlight = false;
    std::uint32_t outputWidth = 0u;
    std::uint32_t outputHeight = 0u;
    std::vector<std::uint8_t> bgr24;
    std::uint64_t fullFrameCpuUploadBytes = 0u;
    std::uint64_t fullFrameGpuReadbackBytes = 0u;
    bool residentLumaConsumed = false;
    std::uint64_t residentLumaGeneration = 0u;
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
    // FASE 13: compact GPU residual-noise model for the vendor-processed YUV frame.
    bool yuvResidualNoiseModelAvailable = false;
    std::uint32_t yuvResidualLumaSamples = 0u;
    std::uint32_t yuvResidualChromaSamples = 0u;
    float yuvResidualSigmaY = 0.0f;
    float yuvResidualSigmaU = 0.0f;
    float yuvResidualSigmaV = 0.0f;
    float yuvResidualModelConfidence = 0.0f;
    float yuvResidualLumaAuthority = 0.0f;
    float yuvResidualChromaAuthority = 0.0f;
    std::string yuvColorContract = "CAMERA2_JFIF_REC601_FULL_RANGE_TO_SRGB";
    bool portraitEffectRequested = false;
    bool portraitEffectApplied = false;
    std::string portraitStatus = "NOT_REQUESTED";
    float inputUploadMs = 0.0f;
    float gpuExecutionWallMs = 0.0f;
    float gpuSynchronizationMs = 0.0f;
    float publicationReadbackMs = 0.0f;
    std::string backend = "NOT_ATTEMPTED";
    std::string failureReason = "none";
};

class VulkanYuvSingleFrameBackend final {
public:
    /**
     * Pipeline-only cold-start preparation. This creates the immutable Vulkan pipeline/descriptor
     * objects without allocating full-resolution capture buffers and without submitting GPU work.
     * Safe to call asynchronously before the first YUV capture.
     */
    bool prepare(VkDevice device) noexcept;

    YuvSingleFrameIspResult execute(
            VkDevice device,
            VkQueue computeQueue,
            VkCommandPool commandPool,
            VulkanAllocatorOwner& allocatorOwner,
            const YuvSingleFrameIspRequest& request) noexcept;

    void destroy(VkDevice device) noexcept;

private:
    struct PersistentBuffer {
        VkBuffer buffer = VK_NULL_HANDLE;
        VmaAllocation allocation = nullptr;
        void* mapped = nullptr;
        std::uint64_t capacityBytes = 0u;
    };

    bool initializeLocked(VkDevice device, std::string& failureReason, bool usePublishedCache = true) noexcept;
    bool ensureBufferLocked(
            VmaAllocator allocator,
            std::uint64_t bytes,
            bool hostVisible,
            PersistentBuffer& target,
            std::string& failureReason) noexcept;
    void destroyBufferLocked(PersistentBuffer& buffer) noexcept;
    void destroyLocked(VkDevice device) noexcept;

    std::mutex mutex_;
    VkDevice initializedDevice_ = VK_NULL_HANDLE;
    VmaAllocator allocator_ = nullptr;
    bool initialized_ = false;
    VkDescriptorSetLayout descriptorSetLayout_ = VK_NULL_HANDLE;
    VkPipelineLayout pipelineLayout_ = VK_NULL_HANDLE;
    VkShaderModule shaderModule_ = VK_NULL_HANDLE;
    VkPipeline pipeline_ = VK_NULL_HANDLE;
    VkDescriptorPool descriptorPool_ = VK_NULL_HANDLE;
    VkDescriptorSet descriptorSet_ = VK_NULL_HANDLE;
    PersistentBuffer inputStaging_{};
    PersistentBuffer inputDevice_{};
    PersistentBuffer toneStaging_{};
    PersistentBuffer toneDevice_{};
    PersistentBuffer outputDevice_{};
    PersistentBuffer outputReadback_{};
    PersistentBuffer gainLogDevice_{};
    PersistentBuffer gainmapDevice_{};
    PersistentBuffer gainmapReadback_{};
    PersistentBuffer ultraHdrTelemetry_{};
    PersistentBuffer portraitMask_{};
};

} // namespace bncam::vulkan
