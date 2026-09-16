#pragma once

#include "VulkanMultiFrameContracts.h"
#include "VulkanResourceContracts.h"
#include "VulkanVmaIntegration.h"

#include <vulkan/vulkan.h>

#include <cstdint>
#include <map>
#include <mutex>
#include <string>
#include <vector>

namespace bncam::vulkan {

enum class DemosaicMethod {
    BILINEAR,
    MALVAR_2004,
    MENON_2007,
    AUTO
};

struct DemosaicResolutionDiagnostics {
    std::string requestedMethod;
    std::string resolvedMethod;
    std::string executedMethod;
    std::string resolutionReason;
};

struct CanonicalRawContract {
    std::uint32_t width = 0;
    std::uint32_t height = 0;
    std::uint32_t rowStrideBytes = 0; // Tightly packed: width * 2
    std::uint64_t totalBytes = 0;     // width * height * 2
    std::string bayerPattern;          // e.g. "RGGB"
    std::uint16_t whiteLevel = 1023;
    std::uint16_t blackLevel = 64;
};

struct ComputeStageDiagnostics {
    std::string stageId;              // "RAW10_UNPACK", "CROP_ROTATE_OUTPUT_CONVERT", etc.
    std::string shaderVersion;         // "SPIR-V 1.0 (GLSL 450)"
    std::string pipelineIdentity;
    std::string inputResourceIdentity;
    std::string outputResourceIdentity;
    std::uint64_t inputBytes = 0;
    std::uint64_t outputBytes = 0;
    std::string dispatchDimensions;   // e.g. "(256, 192, 1)"
    std::string submissionIdentity;
    std::uint64_t gpuStartTimestamp = 0;
    std::uint64_t gpuEndTimestamp = 0;
    std::uint64_t gpuTimeNs = 0;
    std::uint64_t readbackBytes = 0;
    bool fallback = false;
    std::string failureReason;
    bool success = false;
    DemosaicResolutionDiagnostics demosaicResolution;
    VulkanMotionVector motionVector;
    std::string lumaSource = "FUSED";
    std::string chromaSource = "ANCHOR";
    bool phoneAssistanceSensorsEnabled = false;
    float sensorContributionWeight = 0.0f;
    std::string finalOutputFormat = "BGR8_UNORM";
};

struct ComputeExecutionResult {
    bool success = false;
    ComputeStageDiagnostics diagnostics;
    VkBuffer canonicalBuffer = VK_NULL_HANDLE;
    VmaAllocation canonicalAllocation = nullptr;
    std::uint64_t canonicalSizeBytes = 0;
    void* readbackMemoryPtr = nullptr;
    std::string failureReason;
};

struct RawStagePushConstants {
    std::uint32_t width;
    std::uint32_t height;
    std::uint32_t inputRowStrideBytes;
    std::uint32_t outputRowStrideBytes;
};

struct RawLinearizationPushConstants {
    std::uint32_t width;
    std::uint32_t height;
    std::uint32_t cfaPattern;
    std::uint32_t lsWidth;
    std::uint32_t lsHeight;
    float whiteLevel;
    float blackLevels[4];
    float wbGains[4];
};

struct RawColorTransformPushConstants {
    std::uint32_t width;
    std::uint32_t height;
    float colorMatrix[9];
};

struct RawPhaseCorrelationPushConstants {
    std::uint32_t width;
    std::uint32_t height;
    std::uint32_t maxShift;
    std::uint32_t cfaPattern;
};

struct RawRobustMeanFusionPushConstants {
    std::uint32_t width;
    std::uint32_t height;
    int dx;
    int dy;
    float robustWeight;
    std::uint32_t isFirstSupport;
};

struct YuvWeightedAverageFusionPushConstants {
    std::uint32_t width;
    std::uint32_t height;
    int dx;
    int dy;
    float weight;
    std::uint32_t isFirstSupport;
};

struct IspContrastVibrancePushConstants {
    std::uint32_t width;
    std::uint32_t height;
    float contrast;
    float vibrance;
    float sensorWeight;
};

struct IspSharpeningPushConstants {
    std::uint32_t width;
    std::uint32_t height;
    float sharpeningAmount;
    float edgeThreshold;
    float detailAmount;
    float detailRadius;
    float detailValue;
    float detailMasking;
    std::uint32_t spectraNoiseActive;
};

struct IspCropRotateOutputPushConstants {
    std::uint32_t inputWidth;
    std::uint32_t inputHeight;
    std::uint32_t cropX;
    std::uint32_t cropY;
    std::uint32_t cropWidth;
    std::uint32_t cropHeight;
    std::uint32_t outputWidth;
    std::uint32_t outputHeight;
    std::uint32_t rotation;
};

class VulkanComputePipelineManager {
public:
    VulkanComputePipelineManager() = default;
    ~VulkanComputePipelineManager();

    VulkanComputePipelineManager(const VulkanComputePipelineManager&) = delete;
    VulkanComputePipelineManager& operator=(const VulkanComputePipelineManager&) = delete;

    bool initializePipelines(VkDevice device);
    void destroyPipelines(VkDevice device);

    ComputeExecutionResult executeRaw10Unpack(
        VkDevice device,
        VulkanAllocatorOwner& allocatorOwner,
        VkQueue computeQueue,
        VkCommandPool commandPool,
        VkBuffer inputBuffer,
        std::uint64_t inputBytes,
        std::uint32_t width,
        std::uint32_t height,
        std::uint32_t inputRowStrideBytes,
        std::uint64_t generationId
    );

    ComputeExecutionResult executeRaw16Canonicalize(
        VkDevice device,
        VulkanAllocatorOwner& allocatorOwner,
        VkQueue computeQueue,
        VkCommandPool commandPool,
        VkBuffer inputBuffer,
        std::uint64_t inputBytes,
        std::uint32_t width,
        std::uint32_t height,
        std::uint32_t inputRowStrideBytes,
        std::uint64_t generationId
    );

    ComputeExecutionResult executeRawLinearization(
        VkDevice device,
        VulkanAllocatorOwner& allocatorOwner,
        VkQueue computeQueue,
        VkCommandPool commandPool,
        VkBuffer canonicalInputBuffer,
        std::uint32_t width,
        std::uint32_t height,
        std::uint32_t cfaPattern,
        float whiteLevel,
        const float blackLevels[4],
        const float wbGains[4],
        std::uint64_t generationId
    );

    ComputeExecutionResult executeRawDemosaic(
        VkDevice device,
        VulkanAllocatorOwner& allocatorOwner,
        VkQueue computeQueue,
        VkCommandPool commandPool,
        VkBuffer linearRawInputBuffer,
        std::uint32_t width,
        std::uint32_t height,
        std::uint32_t cfaPattern,
        DemosaicMethod method,
        std::uint64_t generationId
    );

    ComputeExecutionResult executeRawColorTransform(
        VkDevice device,
        VulkanAllocatorOwner& allocatorOwner,
        VkQueue computeQueue,
        VkCommandPool commandPool,
        VkBuffer linearRgbInputBuffer,
        std::uint32_t width,
        std::uint32_t height,
        const float colorMatrix3x3[9],
        std::uint64_t generationId
    );

    ComputeExecutionResult executeRawPhaseCorrelation(
        VkDevice device,
        VulkanAllocatorOwner& allocatorOwner,
        VkQueue computeQueue,
        VkCommandPool commandPool,
        VkBuffer anchorRawBuffer,
        VkBuffer supportRawBuffer,
        std::uint32_t width,
        std::uint32_t height,
        std::uint32_t maxShift,
        std::uint32_t cfaPattern,
        std::uint64_t generationId
    );

    ComputeExecutionResult executeRawRobustMeanFusion(
        VkDevice device,
        VulkanAllocatorOwner& allocatorOwner,
        VkQueue computeQueue,
        VkCommandPool commandPool,
        VkBuffer anchorRawBuffer,
        VkBuffer supportRawBuffer,
        std::uint32_t width,
        std::uint32_t height,
        int dx,
        int dy,
        float robustWeight,
        bool isFirstSupport,
        std::uint64_t generationId
    );

    ComputeExecutionResult executeYuvWeightedAverageFusion(
        VkDevice device,
        VulkanAllocatorOwner& allocatorOwner,
        VkQueue computeQueue,
        VkCommandPool commandPool,
        VkBuffer anchorYuvBuffer,
        VkBuffer supportYuvBuffer,
        std::uint32_t width,
        std::uint32_t height,
        int dx,
        int dy,
        float weight,
        bool isFirstSupport,
        std::uint64_t generationId
    );

    ComputeExecutionResult executeIspContrastVibrance(
        VkDevice device,
        VulkanAllocatorOwner& allocatorOwner,
        VkQueue computeQueue,
        VkCommandPool commandPool,
        VkBuffer inputRgbBuffer,
        std::uint32_t width,
        std::uint32_t height,
        float contrast,
        float vibrance,
        bool phoneAssistanceSensorsEnabled,
        std::uint64_t generationId
    );

    ComputeExecutionResult executeIspSharpening(
        VkDevice device,
        VulkanAllocatorOwner& allocatorOwner,
        VkQueue computeQueue,
        VkCommandPool commandPool,
        VkBuffer inputRgbBuffer,
        std::uint32_t width,
        std::uint32_t height,
        float sharpeningAmount,
        float edgeThreshold,
        float detailAmount = 0.40f,
        float detailRadius = 1.00f,
        float detailValue = 0.25f,
        float detailMasking = 0.00f,
        std::uint32_t spectraNoiseActive = 1u,
        std::uint64_t generationId = 0
    );

    ComputeExecutionResult executeIspCropRotateOutput(
        VkDevice device,
        VulkanAllocatorOwner& allocatorOwner,
        VkQueue computeQueue,
        VkCommandPool commandPool,
        VkBuffer inputRgbBuffer,
        std::uint32_t inputWidth,
        std::uint32_t inputHeight,
        std::uint32_t cropX,
        std::uint32_t cropY,
        std::uint32_t cropWidth,
        std::uint32_t cropHeight,
        std::uint32_t rotation,
        std::uint64_t generationId
    );

    bool isInitialized() const noexcept { return initialized_; }
    std::uint32_t getPipelineCreationCount() const noexcept { return pipelineCreationCount_; }
    std::uint32_t getPipelineReuseCount() const noexcept { return pipelineReuseCount_; }

private:
    std::mutex mutex_;
    bool initialized_ = false;
    std::uint32_t pipelineCreationCount_ = 0;
    std::uint32_t pipelineReuseCount_ = 0;

    VkDescriptorSetLayout descriptorSetLayout_ = VK_NULL_HANDLE;
    VkPipelineLayout pipelineLayout_ = VK_NULL_HANDLE;

    VkShaderModule raw10ShaderModule_ = VK_NULL_HANDLE;
    VkPipeline raw10Pipeline_ = VK_NULL_HANDLE;

    VkShaderModule raw16ShaderModule_ = VK_NULL_HANDLE;
    VkPipeline raw16Pipeline_ = VK_NULL_HANDLE;

    VkShaderModule linearizationShaderModule_ = VK_NULL_HANDLE;
    VkPipeline linearizationPipeline_ = VK_NULL_HANDLE;

    VkShaderModule bilinearDemosaicShaderModule_ = VK_NULL_HANDLE;
    VkPipeline bilinearDemosaicPipeline_ = VK_NULL_HANDLE;

    VkShaderModule malvarDemosaicShaderModule_ = VK_NULL_HANDLE;
    VkPipeline malvarDemosaicPipeline_ = VK_NULL_HANDLE;

    VkShaderModule menonDemosaicShaderModule_ = VK_NULL_HANDLE;
    VkPipeline menonDemosaicPipeline_ = VK_NULL_HANDLE;

    VkShaderModule colorTransformShaderModule_ = VK_NULL_HANDLE;
    VkPipeline colorTransformPipeline_ = VK_NULL_HANDLE;

    VkShaderModule phaseCorrelationShaderModule_ = VK_NULL_HANDLE;
    VkPipeline phaseCorrelationPipeline_ = VK_NULL_HANDLE;

    VkShaderModule robustMeanFusionShaderModule_ = VK_NULL_HANDLE;
    VkPipeline robustMeanFusionPipeline_ = VK_NULL_HANDLE;

    VkShaderModule yuvWeightedAverageFusionShaderModule_ = VK_NULL_HANDLE;
    VkPipeline yuvWeightedAverageFusionPipeline_ = VK_NULL_HANDLE;

    VkShaderModule ispContrastVibranceShaderModule_ = VK_NULL_HANDLE;
    VkPipeline ispContrastVibrancePipeline_ = VK_NULL_HANDLE;

    VkShaderModule ispSharpeningShaderModule_ = VK_NULL_HANDLE;
    VkPipeline ispSharpeningPipeline_ = VK_NULL_HANDLE;

    VkShaderModule ispCropRotateOutputShaderModule_ = VK_NULL_HANDLE;
    VkPipeline ispCropRotateOutputPipeline_ = VK_NULL_HANDLE;

    VkDescriptorPool descriptorPool_ = VK_NULL_HANDLE;
};

}  // namespace bncam::vulkan
