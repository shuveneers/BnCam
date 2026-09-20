#pragma once

#include "VulkanVmaIntegration.h"

#include <android/hardware_buffer.h>
#include <vulkan/vulkan.h>

#include <array>
#include <chrono>
#include <cstddef>
#include <cstdint>
#include <mutex>
#include <string>

namespace bncam::vulkan {

constexpr std::uint32_t RAW_PREVIEW_FRAMES_IN_FLIGHT = 3u;
constexpr std::uint32_t RAW_PREVIEW_AWB_GRID_COLUMNS = 64u;
constexpr std::uint32_t RAW_PREVIEW_AWB_GRID_ROWS = 48u;
constexpr std::uint32_t RAW_PREVIEW_AWB_SAMPLE_COUNT =
        RAW_PREVIEW_AWB_GRID_COLUMNS * RAW_PREVIEW_AWB_GRID_ROWS;

struct RawPreviewAwbSample {
    float luma = 0.0f;
    float redMinusGreen = 0.0f;
    float blueMinusGreen = 0.0f;
    float structure = 0.0f;
    std::uint32_t tileIndex = 0u;
    bool valid = false;
};

struct RawPreviewGpuRequest {
    // Preferred zero-copy input candidate. Import is enabled only when the actual
    // AHardwareBuffer is byte-addressable as a Vulkan storage buffer.
    AHardwareBuffer* inputHardwareBuffer = nullptr;
    // Optional already-mapped bytes used only by explicit callers/reference paths. Normal
    // production fallback maps the AHardwareBuffer inside the backend for one staging copy.
    const std::uint8_t* rawData = nullptr;
    std::uint32_t sourceWidth = 0;
    std::uint32_t sourceHeight = 0;
    std::uint32_t sourceCropLeft = 0;
    std::uint32_t sourceCropTop = 0;
    std::uint32_t sourceCropWidth = 0;
    std::uint32_t sourceCropHeight = 0;
    std::uint32_t sourceRowStrideBytes = 0;
    std::uint32_t sourcePixelStrideBytes = 0;
    std::uint32_t sourceFormat = 0;
    std::uint32_t previewWidth = 0;
    std::uint32_t previewHeight = 0;
    std::uint32_t cfaCellDecimation = 1;
    std::uint32_t cfaPattern = 0;
    std::uint32_t demosaicMode = 3;
    std::array<float, 4> blackLevels{0.0f, 0.0f, 0.0f, 0.0f};
    float whiteLevel = 1.0f;
    std::uint32_t captureSensitivityIso = 100;
    float captureExposureTimeMs = 0.0f;
    float physicalGreenNoiseS = 0.0f;
    float physicalGreenNoiseO = 0.0f;
    float physicalNoiseConfidence = 0.0f;
    float focusDetailPriority = 1.0f;
    // Camera2 RggbChannelVector green-even / green-odd gain ratio. This is applied in the
    // Bayer domain before demosaic; wbRgb then carries only the RGB-normalized remainder.
    float greenEvenOddRatio = 1.0f;
    std::array<float, 3> wbRgb{1.0f, 1.0f, 1.0f};
    std::array<float, 9> colorMatrix{1.0f, 0.0f, 0.0f,
                                     0.0f, 1.0f, 0.0f,
                                     0.0f, 0.0f, 1.0f};
    // Same trusted DNG ProfileHueSatMap contract as the resident capture colour backend. The
    // paired ForwardMatrix is already carried by colorMatrix; the HSM is optional augmentation.
    bool calibratedHueSatMapEnabled = false;
    std::uint32_t hueSatHueDivisions = 0u;
    std::uint32_t hueSatSaturationDivisions = 0u;
    std::uint32_t hueSatValueDivisions = 0u;
    std::uint32_t hueSatEncoding = 0u;
    const float* hueSatData1 = nullptr;
    std::size_t hueSatData1FloatCount = 0u;
    const float* hueSatData2 = nullptr;
    std::size_t hueSatData2FloatCount = 0u;
    float hueSatWeightFirst = 1.0f;
    float hueSatWeightSecond = 0.0f;
    float profileSaturation = 0.0f;
    float profileContrast = 0.0f;
    float profileVibrance = 0.0f;
    // DELTA 0209A: explicit CPU-side Color Management request lanes. These do not extend
    // the fixed 128-byte Vulkan push-constant ABI; the backend may pack them at the GPU boundary.
    float profilePop = 0.0f;
    float profileColorRecovery = 0.0f;
    float profileToneExposure = 0.0f;
    float profileToneHighlights = 0.0f;
    float profileToneShadows = 0.0f;
    float profileToneWhites = 0.0f;
    float profileToneBlacks = 0.0f;
    float profileToneContrast = 0.0f;
    float profileLocalToneBias = 0.0f;
    float profileDetailAmount = 0.40f;
    float profileDetailRadius = 1.00f;
    float profileDetailDetail = 0.25f;
    float profileDetailMasking = 0.00f;
    float profileNrLuminance = 0.0f;
    float profileNrLuminanceDetail = 0.5f;
    float profileNrLuminanceContrast = 0.0f;
    float profileNrColor = 0.0f;
    float profileNrColorDetail = 0.5f;
    float profileNrColorSmoothness = 0.5f;
    const float* toneLut = nullptr;
    std::uint32_t toneLutSize = 0;
    // Optional GPU-resident display target. When non-null the backend imports this RGBA_8888
    // AHardwareBuffer as a Vulkan storage image and skips full-frame host readback.
    AHardwareBuffer* outputHardwareBuffer = nullptr;
    std::uint8_t* outputRgba = nullptr;
    std::size_t outputCapacityBytes = 0;
    // Optional compact 4x-decimated luma plane for QR/tracking. Heavy RGB->luma work stays on GPU.
    std::uint8_t* analysisNv21 = nullptr;
    std::size_t analysisNv21CapacityBytes = 0;
    // Phase 11A: CPU statistics/readback is a low-cadence sidecar. Display submissions may skip
    // the statistics copy while the shader keeps all image-domain work on the GPU.
    bool analysisReadbackRequested = true;
    std::uint32_t frameSlotIndex = 0;
};

struct RawPreviewGpuResult {
    bool attempted = false;
    bool success = false;
    bool persistentBufferReuseHit = false;
    bool directHostInputUsed = false;
    bool directHardwareBufferInputUsed = false;
    bool gpuResidentOutputUsed = false;
    // Async preview protocol: execute() only submits. pollCompletion() resolves the exact slot
    // without a host-side fence wait. success=true is reserved for completed/presentable output.
    bool submitted = false;
    bool completionPending = false;
    bool analysisReadbackPerformed = false;
    std::uint64_t submissionId = 0u;
    std::uint32_t inputAhbFormat = 0;
    std::uint64_t inputAhbUsage = 0;
    // 0=not probed, 1=direct imported, 2=AHB contract not byte-addressable,
    // 3=required Vulkan interop unavailable, 4=import failed, 5=CPU byte staging.
    std::uint32_t inputInteropStatus = 0;
    float inputPackingMs = 0.0f;
    float kernelMs = 0.0f;
    float readbackMs = 0.0f;
    float synchronizationMs = 0.0f;
    float totalMs = 0.0f;
    // Debug-only first-activation timing fields. They are populated without changing execution.
    float backendMutexWaitMs = 0.0f;
    bool backendInitializationPerformed = false;
    float backendInitializationMs = 0.0f;
    float spirvLookupMs = 0.0f;
    float descriptorLayoutMs = 0.0f;
    float pipelineLayoutMs = 0.0f;
    float shaderModuleMs = 0.0f;
    float pipelineCacheMutexWaitMs = 0.0f;
    bool pipelineCachePresent = false;
    float computePipelineMs = 0.0f;
    float imageSpirvLookupMs = 0.0f;
    float imageShaderModuleMs = 0.0f;
    float imageComputePipelineMs = 0.0f;
    float descriptorCommandResourcesMs = 0.0f;
    float inputAhbProbeMs = 0.0f;
    float outputAhbImportMs = 0.0f;
    float commandRecordMs = 0.0f;
    float queueMutexWaitMs = 0.0f;
    float queueSubmitCallMs = 0.0f;
    float fenceWaitMs = 0.0f;
    float targetExposureGain = 1.0f;
    float exposureGain = 1.0f;
    float previewRequestedEv = 0.0f;
    float previewHighlightLimitedEv = 0.0f;
    float previewAppliedEv = 0.0f;
    float previewSceneKey = 0.148f;
    float previewHighlightHeadroomEv = 0.0f;
    float previewSceneRangeEv = 0.0f;
    float sceneMidtone = 0.0f;
    float sceneMidtoneTarget = 0.148f;
    float gtmShoulderStart = 0.72f;
    float gtmShoulderStrength = 0.90f;
    float gtmHighlightPressure = 0.0f;
    float gtmP95CompressionEv = 0.0f;
    float gtmP99CompressionEv = 0.0f;
    float gtmDynamicRangePressure = 0.0f;
    float ltmStrength = 0.05f;
    float ltmMaxLiftEv = 0.18f;
    float ltmMaxCompressEv = 0.08f;
    float normalizedRawMin = 0.0f;
    float normalizedRawMax = 0.0f;
    std::uint32_t commonHighlightScalePixels = 0;
    std::array<std::uint32_t, 256> linearLumaHistogram{};
    std::array<std::uint32_t, 16> displayLumaHistogram{};
    std::array<std::uint32_t, 64> displayLumaHistogram64{};
    std::array<std::uint32_t, 64> displayRHistogram64{};
    std::array<std::uint32_t, 64> displayGHistogram64{};
    std::array<std::uint32_t, 64> displayBHistogram64{};
    std::uint32_t rawNearClipSampleCount = 0;
    std::uint32_t rawSampleCount = 0;
    std::uint32_t rawTrueSaturatedSampleCount = 0;
    std::uint32_t rawRClipSampleCount = 0;
    std::uint32_t rawGClipSampleCount = 0;
    std::uint32_t rawBClipSampleCount = 0;
    std::uint32_t highlightReconstructedSampleCount = 0;
    std::uint32_t postWbClipSampleCount = 0;
    std::uint32_t postCcmClipSampleCount = 0;
    std::uint32_t displayRClipSampleCount = 0;
    std::uint32_t displayGClipSampleCount = 0;
    std::uint32_t displayBClipSampleCount = 0;
    std::uint32_t displayShadowSampleCount = 0;
    std::uint32_t displayHighlightSampleCount = 0;
    std::uint32_t displaySampleCount = 0;
    float displayHighlightX = -1.0f;
    float displayHighlightY = -1.0f;
    // FASE 5 compact signed-exposure diagnostics; no full-frame readback.
    std::uint32_t exposureTileCount = 0u;
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
    std::uint32_t analysisNv21Width = 0;
    std::uint32_t analysisNv21Height = 0;
    std::array<RawPreviewAwbSample, RAW_PREVIEW_AWB_SAMPLE_COUNT> awbSamples{};
    std::uint32_t awbSampleCount = 0u;
    std::uint32_t activeSlotIndex = 0;
    bool droppedBusy = false;
    std::string failureReason;
};

/**
 * High-performance GPU-resident RAW viewfinder backend with 3 frames in flight.
 * Computes demosaic, WB/CCM, exposure smoothing, tone mapping and sRGB encoding directly
 * on Vulkan compute. GPU-resident output avoids the former full-frame host readback; only the
 * compact statistics block is synchronized back to the preview worker.
 */
class VulkanRawPreviewBackend final {
public:
    /** Prepare immutable RAW-preview Vulkan resources without requiring a frame/AHardwareBuffer. */
    bool prepare(VkDevice device, VkCommandPool commandPool) noexcept;

    RawPreviewGpuResult execute(
            VkPhysicalDevice physicalDevice,
            VkDevice device,
            VkQueue computeQueue,
            std::uint32_t computeQueueFamilyIndex,
            bool foreignQueueFamilyEnabled,
            VkCommandPool commandPool,
            VulkanAllocatorOwner& allocatorOwner,
            std::mutex* queueSubmissionMutex,
            const RawPreviewGpuRequest& request) noexcept;

    /** Non-blocking completion probe for one previously submitted preview slot. */
    RawPreviewGpuResult pollCompletion(
            VkPhysicalDevice physicalDevice,
            VkDevice device,
            VulkanAllocatorOwner& allocatorOwner,
            std::uint32_t frameSlotIndex,
            std::uint64_t submissionId) noexcept;

    /** Bounded teardown-only drain for submitted preview slots. Never used by the display path. */
    bool waitForIdle(VkDevice device, std::uint64_t timeoutNs) noexcept;

    void destroy(VkDevice device) noexcept;

private:
    struct PersistentBuffer {
        VkBuffer buffer = VK_NULL_HANDLE;
        VmaAllocation allocation = nullptr;
        void* mapped = nullptr;
        std::uint64_t capacityBytes = 0;
        VkMemoryPropertyFlags memoryProperties = 0u;
    };


    struct ImportedInputBuffer {
        AHardwareBuffer* hardwareBuffer = nullptr;
        VkBuffer buffer = VK_NULL_HANDLE;
        VkDeviceMemory memory = VK_NULL_HANDLE;
        VkDeviceSize size = 0u;
    };

    struct ImportedOutputImage {
        AHardwareBuffer* hardwareBuffer = nullptr;
        VkImage image = VK_NULL_HANDLE;
        VkDeviceMemory memory = VK_NULL_HANDLE;
        VkImageView view = VK_NULL_HANDLE;
        VkFormat format = VK_FORMAT_UNDEFINED;
        std::uint32_t width = 0;
        std::uint32_t height = 0;
        bool initializedForShaderWrite = false;
    };

    struct FrameSlot {
        VkCommandBuffer commandBuffer = VK_NULL_HANDLE;
        VkFence fence = VK_NULL_HANDLE;
        VkDescriptorSet descriptorSet = VK_NULL_HANDLE;
        VkDescriptorSet imageDescriptorSet = VK_NULL_HANDLE;
        // Every mutable resource is slot-local. A second preview submission may therefore execute
        // while an older slot is still owned by the GPU without buffer/descriptor aliasing.
        PersistentBuffer inputStaging;
        PersistentBuffer deviceInput;
        PersistentBuffer toneLutBuffer;
        PersistentBuffer deviceStatistics;
        PersistentBuffer localToneBase;
        PersistentBuffer hueSatProfile;
        PersistentBuffer deviceOutput;
        PersistentBuffer outputReadback;
        ImportedInputBuffer importedInput;
        ImportedOutputImage importedOutput;
        bool fenceSubmitted = false;
        AHardwareBuffer* boundHardwareBuffer = nullptr;

        std::uint64_t submissionId = 0u;
        bool gpuResidentOutput = false;
        bool analysisReadbackRequested = false;
        bool compactAnalysisRequested = false;
        std::uint64_t rgbaBytes = 0u;
        std::uint64_t statisticsBytes = 0u;
        std::uint64_t readbackBytes = 0u;
        std::uint32_t previewWidth = 0u;
        std::uint32_t previewHeight = 0u;
        std::uint32_t analysisWidth = 0u;
        std::uint32_t analysisHeight = 0u;
        std::uint8_t* outputRgba = nullptr;
        std::uint8_t* analysisNv21 = nullptr;
        std::size_t outputCapacityBytes = 0u;
        std::size_t analysisNv21CapacityBytes = 0u;
        float profileToneExposure = 0.0f;
        std::chrono::steady_clock::time_point submittedAt{};
        RawPreviewGpuResult submittedDiagnostics{};
    };

    bool initializeLocked(VkDevice device, VkCommandPool commandPool,
                          RawPreviewGpuResult& diagnostics,
                          std::string& failureReason,
                          bool useSharedPipelineCache) noexcept;
    bool ensureLegacyPipelineLocked(VkDevice device, RawPreviewGpuResult& diagnostics,
                                    std::string& failureReason,
                                    bool useSharedPipelineCache) noexcept;
    bool ensureBufferLocked(VmaAllocator allocator, std::uint64_t bytes,
                            std::uint32_t hostAccess, PersistentBuffer& buffer,
                            bool& reallocated, std::string& failureReason) noexcept;
    bool tryImportInputBufferLocked(VkPhysicalDevice physicalDevice, VkDevice device,
                                    bool foreignQueueFamilyEnabled, FrameSlot& slot,
                                    AHardwareBuffer* inputBuffer, VkDeviceSize requiredBytes,
                                    RawPreviewGpuResult& result, std::string& failureReason) noexcept;
    void destroyImportedInputLocked(VkDevice device, ImportedInputBuffer& input) noexcept;
    bool ensureImportedOutputLocked(VkPhysicalDevice physicalDevice, VkDevice device,
                                    FrameSlot& slot, AHardwareBuffer* outputBuffer,
                                    std::uint32_t expectedWidth, std::uint32_t expectedHeight,
                                    std::string& failureReason) noexcept;
    void destroyImportedOutputLocked(VkDevice device, ImportedOutputImage& output) noexcept;
    void destroyLocked(VkDevice device) noexcept;

    std::mutex mutex_;
    bool initialized_ = false;
    VkDevice initializedDevice_ = VK_NULL_HANDLE;
    VkCommandPool initializedCommandPool_ = VK_NULL_HANDLE;
    VkDescriptorSetLayout descriptorSetLayout_ = VK_NULL_HANDLE;
    VkDescriptorSetLayout imageDescriptorSetLayout_ = VK_NULL_HANDLE;
    VkPipelineLayout pipelineLayout_ = VK_NULL_HANDLE;
    VkPipelineLayout imagePipelineLayout_ = VK_NULL_HANDLE;
    VkShaderModule shaderModule_ = VK_NULL_HANDLE;
    VkShaderModule imageShaderModule_ = VK_NULL_HANDLE;
    VkPipeline pipeline_ = VK_NULL_HANDLE;
    VkPipeline imagePipeline_ = VK_NULL_HANDLE;
    VkDescriptorPool descriptorPool_ = VK_NULL_HANDLE;
    VkQueryPool queryPool_ = VK_NULL_HANDLE;
    // Phase 11C: one tiny GPU-resident scalar shared across submissions. The compute queue is
    // ordered and a shader-write -> shader-read barrier protects it, so temporal exposure smoothing
    // never depends on the 10 Hz CPU analysis sidecar. Full-frame mutable resources remain slot-local.
    PersistentBuffer previewExposureState_;
    VmaAllocator allocator_ = nullptr;
    std::uint32_t currentFrameSlot_ = 0u;
    std::array<FrameSlot, RAW_PREVIEW_FRAMES_IN_FLIGHT> slots_;
    std::uint64_t nextSubmissionId_ = 1u;
    RawPreviewGpuResult lastAnalysisResult_{};
    bool hasLastAnalysisResult_ = false;
};

}  // namespace bncam::vulkan
