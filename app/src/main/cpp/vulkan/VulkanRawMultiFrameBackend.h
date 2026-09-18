#pragma once

#include "VulkanRawCaptureBackend.h"
#include "VulkanSpectraTemporalObserverBackend.h"
#include "VulkanVmaIntegration.h"

#include <android/hardware_buffer.h>
#include <vulkan/vulkan.h>

#include <array>
#include <cstdint>
#include <mutex>
#include <string>
#include <vector>

namespace bncam::vulkan {

struct RawMultiFrameSpectraConfig {
    bool enabled = false;
    std::array<double, 4> effectiveS{0.0, 0.0, 0.0, 0.0};
    std::array<double, 4> effectiveO{0.0, 0.0, 0.0, 0.0};
    float confidence = 0.0f;
    float noisePressure = 0.0f;
    float temporalAuthority = 0.0f;
};

struct RawMultiFrameRequest {
    std::vector<AHardwareBuffer*> frames;
    RawCaptureSourceFormat sourceFormat = RawCaptureSourceFormat::RAW10;
    std::uint32_t cropLeft = 0u;
    std::uint32_t cropTop = 0u;
    std::uint32_t cropWidth = 0u;
    std::uint32_t cropHeight = 0u;
    std::uint32_t nativeWhite = 1u;
    std::uint32_t payloadWhite = 1u;
    // Developed-only normalization authority for temporal SPECTRA/fusion weighting.
    // Physical canonicalization/DNG continues to use payloadWhite/payloadBlack.
    std::uint32_t developedWhite = 1u;
    std::array<std::uint32_t, 4> nativeBlack{0u, 0u, 0u, 0u};
    std::array<std::uint32_t, 4> payloadBlack{0u, 0u, 0u, 0u};
    std::array<std::uint32_t, 4> developedBlack{0u, 0u, 0u, 0u};
    std::uint32_t cfaPattern = 0u;
    std::uint32_t maxShiftPixels = 1u;
    float alignmentStrictness = 0.8f;
    bool fuseSupportFrames = true;
    // Per input frame exposure product relative to the anchor exposure product. The vector
    // follows [frames] ordering; values are sanitized by the host. Empty means normal
    // equal-exposure fusion. HDR remains entirely GPU-resident once canonicalized.
    std::vector<float> exposureScaleToAnchor;
    bool computationalHdr = false;
    RawMultiFrameSpectraConfig spectra{};
    std::uint64_t generationId = 0u;
};

struct RawMultiFrameSupportResult {
    bool canonicalized = false;
    bool alignmentAccepted = false;
    bool acceptedForFusion = false;
    int applyDx = 0;
    int applyDy = 0;
    double estimatedShiftX = 0.0;
    double estimatedShiftY = 0.0;
    double phaseResponse = 0.0;
    float forwardBackwardConsistency = 0.0f;
    double reverseEstimatedShiftX = 0.0;
    double reverseEstimatedShiftY = 0.0;
    double reversePhaseResponse = 0.0;
    double forwardBackwardClosureErrorPixels = 0.0;
    float repeatedSupportConfidence = 0.0f;
    SpectraTemporalObserverResult spectraObservation{};
    std::string rejectReason = "none";
    float canonicalizeMs = 0.0f;
    float alignmentGpuMs = 0.0f;
    float fusionGpuMs = 0.0f;
    float exposureScaleToAnchor = 1.0f;
    bool hdrHighlightAuthority = false;
    bool hdrShadowAuthority = false;
    bool hdrTemporalMainAuthority = false;
    std::uint64_t fusionContributedPixels = 0u;
};

struct RawMultiFrameResult {
    bool attempted = false;
    bool success = false;
    bool submissionMayRemainInFlight = false;
    bool cpuAlignment = false;
    bool cpuFusion = false;
    bool cpuFullFrameSupportMaterialization = false;
    bool residentFusedRawProduced = false;
    bool residentOutputProduced = false;
    bool finalReadbackPerformed = false;
    std::uint32_t width = 0u;
    std::uint32_t height = 0u;
    std::uint32_t anchorSourceRowStrideBytes = 0u;
    std::uint32_t anchorSourcePixelStrideBytes = 0u;
    int framesDecoded = 0;
    int supportAccepted = 0;
    int supportRejected = 0;
    std::uint64_t fullFrameCpuUploadBytes = 0u;
    std::uint64_t fullFrameGpuReadbackBytes = 0u;
    std::uint64_t compactGpuReadbackBytes = 0u;
    std::uint64_t residentOutputGeneration = 0u;
    float canonicalizeGpuMs = 0.0f;
    float alignmentGpuMs = 0.0f;
    float fusionGpuMs = 0.0f;
    float observerGpuMs = 0.0f;
    float gpuSynchronizationMs = 0.0f;
    float finalReadbackMs = 0.0f;
    float totalMs = 0.0f;
    // Phase 9 structural submission telemetry. Counts describe this execute() call;
    // resource creation is amortized across backend lifetime instead of per GPU pass.
    std::uint32_t commandBufferAllocations = 0u;
    std::uint32_t commandBufferResets = 0u;
    std::uint32_t fenceCreations = 0u;
    std::uint32_t fenceResets = 0u;
    std::uint32_t queueSubmissions = 0u;
    bool reusedSubmissionResources = false;
    std::uint32_t fusionDescriptorSetUpdates = 0u;
    std::uint32_t fusionDescriptorSetUpdateSkips = 0u;
    double spectraFusionVarianceScale = 1.0;
    double spectraFusionVarianceP10 = 1.0;
    double spectraFusionVarianceP50 = 1.0;
    double spectraFusionVarianceP90 = 1.0;
    double spectraEffectiveFrameCount = 1.0;
    double spectraEffectiveFrameCountP10 = 1.0;
    double spectraEffectiveFrameCountP50 = 1.0;
    double spectraEffectiveFrameCountP90 = 1.0;
    double spectraLocalFusionFallbackFraction = 1.0;
    std::uint16_t finalRawMin = 0u;
    std::uint16_t finalRawMax = 0u;
    std::uint64_t finalRawSaturatedCount = 0u;
    double finalRawSaturatedPct = 0.0;
    std::string inputImportPath = "NOT_ATTEMPTED";
    std::string rawUnpackBackend = "NOT_ATTEMPTED";
    std::string alignmentBackend = "NOT_ATTEMPTED";
    std::string fusionBackend = "NOT_ATTEMPTED";
    std::string failureReason = "none";
    std::vector<RawMultiFrameSupportResult> supports;
    std::vector<std::uint16_t> outputRaw16;
};

class VulkanRawMultiFrameBackend final {
public:
    VulkanRawMultiFrameBackend() = default;
    ~VulkanRawMultiFrameBackend() = default;
    VulkanRawMultiFrameBackend(const VulkanRawMultiFrameBackend&) = delete;
    VulkanRawMultiFrameBackend& operator=(const VulkanRawMultiFrameBackend&) = delete;

    RawMultiFrameResult execute(
            VkPhysicalDevice physicalDevice,
            VkDevice device,
            VkQueue computeQueue,
            VkCommandPool commandPool,
            VulkanAllocatorOwner& allocatorOwner,
            std::mutex* queueSubmissionMutex,
            VulkanSpectraTemporalObserverBackend& temporalObserverBackend,
            const RawMultiFrameRequest& request) noexcept;

    void destroy(VkDevice device) noexcept;

    // Phase 13: resolve only while the runtime owns the Vulkan submission transaction.
    // The returned buffer remains backend-owned and is invalidated by the next execute/destroy.
    bool resolveResidentOutput(
            std::uint64_t generation,
            VkBuffer& buffer,
            std::uint64_t& bytes,
            std::uint32_t& width,
            std::uint32_t& height) const noexcept;

private:
    struct PersistentBuffer {
        VkBuffer buffer = VK_NULL_HANDLE;
        VmaAllocation allocation = nullptr;
        void* mapped = nullptr;
        std::uint64_t capacityBytes = 0u;
    };

    struct PipelineBundle {
        VkDescriptorSetLayout descriptorSetLayout = VK_NULL_HANDLE;
        VkPipelineLayout pipelineLayout = VK_NULL_HANDLE;
        VkShaderModule shaderModule = VK_NULL_HANDLE;
        VkPipeline pipeline = VK_NULL_HANDLE;
        VkDescriptorPool descriptorPool = VK_NULL_HANDLE;
        VkDescriptorSet descriptorSet = VK_NULL_HANDLE;
        std::uint32_t bindingCount = 0u;
    };

    bool initializeLocked(VkDevice device, std::string& failureReason) noexcept;
    bool ensureBufferLocked(VmaAllocator allocator, std::uint64_t bytes, bool hostVisible,
                            PersistentBuffer& buffer, std::string& failureReason) noexcept;
    void destroyBufferLocked(PersistentBuffer& buffer) noexcept;
    void destroyPipelineLocked(VkDevice device, PipelineBundle& pipeline) noexcept;
    bool ensureSubmissionResourcesLocked(
            VkDevice device, VkCommandPool commandPool, bool& created,
            std::string& failureReason) noexcept;
    void destroySubmissionResourcesLocked(VkDevice device) noexcept;
    void destroyLocked(VkDevice device) noexcept;

    mutable std::mutex mutex_;
    VkDevice initializedDevice_ = VK_NULL_HANDLE;
    VmaAllocator allocator_ = nullptr;
    bool initialized_ = false;

    VkCommandPool submissionCommandPool_ = VK_NULL_HANDLE;
    VkCommandBuffer reusableCommandBuffer_ = VK_NULL_HANDLE;
    VkFence reusableFence_ = VK_NULL_HANDLE;
    bool submissionResourcesUnsafe_ = false;

    PipelineBundle canonicalizePipeline_{};
    PipelineBundle alignmentPipeline_{};
    PipelineBundle fusionPipeline_{};

    PersistentBuffer staging_{};
    PersistentBuffer anchor_{};
    PersistentBuffer support_{};
    PersistentBuffer accum_{};
    PersistentBuffer weight_{};
    PersistentBuffer weightSq_{};
    PersistentBuffer correlation_{};
    PersistentBuffer staticField_{};
    PersistentBuffer fusionParams_{};
    PersistentBuffer finalRaw_{};
    PersistentBuffer fusionStats_{};
    PersistentBuffer finalStats_{};
    PersistentBuffer alignmentScores_{};
    PersistentBuffer alignmentResult_{};
    PersistentBuffer readback_{};

    std::uint64_t residentOutputGeneration_ = 0u;
    std::uint64_t residentOutputBytes_ = 0u;
    std::uint32_t residentOutputWidth_ = 0u;
    std::uint32_t residentOutputHeight_ = 0u;
};

} // namespace bncam::vulkan
