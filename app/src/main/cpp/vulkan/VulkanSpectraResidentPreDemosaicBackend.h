#pragma once

#include "VulkanVmaIntegration.h"

#include <vulkan/vulkan.h>

#include <array>
#include <cstddef>
#include <cstdint>
#include <string>
#include <vector>

namespace bncam::vulkan {

// N006E temporary compile facade.
// The classical Pass0/Pass1 resident pixel implementation and shader are physically removed.
// This type surface is retained for exactly one compile gate so VulkanRuntime can be purged
// independently in N006F. Every execution entrypoint is fail-closed and cannot mutate pixels.

struct SpectraPass1BeforeTile {
    std::array<float, 4> meanSignal{0.0f, 0.0f, 0.0f, 0.0f};
    std::array<float, 4> predictedRawVarianceByChannel{0.0f, 0.0f, 0.0f, 0.0f};
    float predictedSpatialResidualVariance = 0.0f;
    float predictedChromaResidualVariance = 0.0f;
    float residualEnergy = 0.0f;
    float chromaResidualEnergy = 0.0f;
    float structureEnergy = 0.0f;
    float confidence = 0.0f;
    bool valid = false;
};

struct SpectraResidentPreDemosaicRequest {
    const float* mosaicData = nullptr;
    std::uint32_t frameWidth = 0;
    std::uint32_t frameHeight = 0;
    std::size_t rowStrideFloats = 0;
    std::uint32_t cfaPattern = 0;

    bool pass0Only = false;
    std::array<float, 4> pass0ChannelBias{0.0f, 0.0f, 0.0f, 0.0f};
    std::uint64_t residentInputGeneration = 0u;

    VkBuffer externalResidentInputBuffer = VK_NULL_HANDLE;
    std::uint64_t externalResidentInputBytes = 0u;

    std::array<float, 4> effectiveS{0.0f, 0.0f, 0.0f, 0.0f};
    std::array<float, 4> effectiveO{0.0f, 0.0f, 0.0f, 0.0f};
    float blendStrength = 0.0f;
    float maxPixelShift = 0.0f;
    float maxLinearShift = 0.028f;
    bool physicalBaselineMode = false;
    float isoAuthority = 0.0f;
    float greenS = 0.0f;
    float greenO = 0.0f;

    const float* tensorCells = nullptr;
    std::uint32_t tensorColumns = 0;
    std::uint32_t tensorRows = 0;
    std::uint32_t tensorStep = 16;
    std::uint64_t tensorGenerationId = 0;

    const float* lensShadingMap = nullptr;
    std::uint32_t lensShadingColumns = 0;
    std::uint32_t lensShadingRows = 0;
    std::uint64_t lensShadingGenerationId = 0;

    std::uint32_t noRegretGridCols = 0;
    std::uint32_t noRegretGridRows = 0;
    std::uint32_t noRegretTileWidth = 0;
    std::uint32_t noRegretTileHeight = 0;
    std::vector<SpectraPass1BeforeTile> beforeTiles;
    float targetFloorScale = 1.0f;
    float minimumResidualRatio = 0.70f;
    float detailRetentionFloor = 0.95f;
    float combinedNoisePressure = 0.0f;
    float modelConfidence = 0.0f;
    bool deferFullFrameReadback = false;
};

struct SpectraResidentRawStatisticsGpu {
    double residualSquaredSum = 0.0;
    std::uint64_t residualSampleCount = 0;
    double chromaSquaredSum = 0.0;
    std::uint64_t chromaSampleCount = 0;
    float residualEnergy = 0.0f;
    float chromaResidualEnergy = 0.0f;
    std::string method = "NOT_RUN";
};

struct SpectraResidentChromaBandsGpu {
    float fineEnergy = 0.0f;
    float midEnergy = 0.0f;
    float lowEnergy = 0.0f;
    float redFineEnergy = 0.0f;
    float redMidEnergy = 0.0f;
    float redLowEnergy = 0.0f;
    float blueFineEnergy = 0.0f;
    float blueMidEnergy = 0.0f;
    float blueLowEnergy = 0.0f;
    float rowPatternProxy = 0.0f;
    float columnPatternProxy = 0.0f;
    std::uint64_t sampleCount = 0;
    std::uint64_t redSampleCount = 0;
    std::uint64_t blueSampleCount = 0;
    float redBlueSampleBalance = 0.0f;
    float confidence = 0.0f;
    std::string status = "NOT_RUN";
    std::string method = "GPU_RESIDENT_SAMPLED_PRE_DEMOSAIC_PROXY";
};

struct SpectraPass1GpuTelemetry {
    std::uint64_t evaluatedPixelCount = 0;
    std::uint64_t changedPixelCount = 0;
    std::uint64_t edgeProtectedPixelCount = 0;
    std::uint64_t validTensorPixelCount = 0;
    std::uint64_t confidentTensorPixelCount = 0;
    std::uint64_t fallbackPixelCount = 0;
    std::uint64_t directionalChangedPixelCount = 0;
    std::uint64_t crossEdgeProtectedSampleCount = 0;
    std::uint64_t alongStructureSupportedSampleCount = 0;
    std::uint64_t contextFlatPixelCount = 0;
    std::uint64_t contextStructureProtectedPixelCount = 0;
    std::uint64_t contextBoostedPixelCount = 0;
    std::uint64_t profiledMultibandPixelCount = 0;
    std::uint64_t profiledHeavyFineShrinkPixelCount = 0;
    std::uint64_t coherentDetailRestitutionPixelCount = 0;
    std::uint64_t profiledPatchConsensusPixelCount = 0;
    std::uint64_t profiledStrongPatchConsensusPixelCount = 0;
    std::uint64_t profiledPatchPosteriorCleanPixelCount = 0;
    std::uint64_t profiledPatchGradientProtectedPixelCount = 0;
    std::array<std::uint64_t, 4> orientationHistogram{0, 0, 0, 0};
    std::array<std::uint64_t, 32> confidenceHistogram{};
    std::array<std::uint64_t, 32> coherenceHistogram{};
    float maximumLinearCorrection = 0.0f;
};

struct SpectraPass1NoRegretGpuResult {
    int totalTiles = 0;
    int evaluatedTiles = 0;
    int invalidTiles = 0;
    int acceptedTiles = 0;
    int partiallyAcceptedTiles = 0;
    int rejectedTiles = 0;
    int rejectedOversmooth = 0;
    int rejectedDetailLoss = 0;
    int rejectedMeanDrift = 0;
    int rejectedNoImprovement = 0;
    float meanAcceptance = 0.0f;
    float acceptanceP10 = 0.0f;
    float acceptanceP50 = 0.0f;
    float acceptanceP90 = 0.0f;
    float attenuatedPixelFraction = 0.0f;
    float meanRiskImprovement = 0.0f;
    float meanColourShift = 0.0f;
    float maxColourShift = 0.0f;
    float edgePreservationScore = 1.0f;
    float oversmoothingScore = 0.0f;
};

struct SpectraResidentPreDemosaicResult {
    bool attempted = false;
    bool success = false;
    bool pipelineAvailable = false;
    bool timestampQueryUsed = false;
    bool persistentBufferReuseHit = false;
    bool persistentBufferReallocated = false;
    bool gpuNoRegretBlendUsed = false;
    bool cpuFullFrameCandidateReadbackAvoided = false;
    bool outputRemainsResidentUntilFinalReadback = false;
    bool fullFrameReadbackDeferred = false;
    bool pass0Only = false;
    bool residentInputConsumed = false;
    bool externalResidentInputConsumed = false;
    std::uint64_t residentOutputGeneration = 0;
    std::uint64_t residentOutputBytes = 0;
    std::vector<float> outputMosaic;
    std::vector<SpectraPass1BeforeTile> finalTiles;
    SpectraResidentRawStatisticsGpu rawStatistics{};
    SpectraResidentChromaBandsGpu chromaBands{};
    SpectraPass1GpuTelemetry telemetry{};
    SpectraPass1NoRegretGpuResult noRegret{};
    float inputPackingMs = 0.0f;
    float tensorUploadMs = 0.0f;
    float pass0KernelMs = 0.0f;
    float pass1KernelMs = 0.0f;
    float tileStatisticsKernelMs = 0.0f;
    float noRegretCpuDecisionMs = 0.0f;
    float noRegretBlendKernelMs = 0.0f;
    float compactObservationKernelMs = 0.0f;
    float compactObservationReadbackMs = 0.0f;
    float gpuKernelMs = 0.0f;
    float synchronizationMs = 0.0f;
    float readbackMs = 0.0f;
    float transferAndSyncMs = 0.0f;
    float totalMs = 0.0f;
    std::uint64_t inputBytes = 0;
    std::uint64_t candidateBytes = 0;
    std::uint64_t outputBytes = 0;
    std::uint64_t tensorBytes = 0;
    std::uint64_t lensShadingBytes = 0;
    std::uint64_t tileStatisticsBytes = 0;
    std::uint64_t acceptanceBytes = 0;
    std::uint64_t persistentResidentBytes = 0;
    std::uint64_t persistentAllocationGeneration = 0;
    std::string status = "NOT_RUN";
    std::string failureReason;
};

class VulkanSpectraResidentPreDemosaicBackend final {
public:
    VulkanSpectraResidentPreDemosaicBackend() = default;
    ~VulkanSpectraResidentPreDemosaicBackend() = default;

    VulkanSpectraResidentPreDemosaicBackend(
            const VulkanSpectraResidentPreDemosaicBackend&) = delete;
    VulkanSpectraResidentPreDemosaicBackend& operator=(
            const VulkanSpectraResidentPreDemosaicBackend&) = delete;

    SpectraResidentPreDemosaicResult executePass1(
            VkPhysicalDevice,
            VkDevice,
            VkQueue,
            VkCommandPool,
            VulkanAllocatorOwner&,
            const SpectraResidentPreDemosaicRequest& request
    ) noexcept {
        SpectraResidentPreDemosaicResult result{};
        result.attempted = true;
        result.pass0Only = request.pass0Only;
        result.pipelineAvailable = false;
        result.status = "RETIRED_N006E_CLASSICAL_PRE_DEMOSAIC_KERNEL";
        result.failureReason = "CLASSICAL_PRE_DEMOSAIC_PIXEL_KERNEL_REMOVED";
        return result;
    }

    void destroy(VkDevice) noexcept {}

    bool productionKernelConnected() const noexcept { return false; }
    bool pipelineInitialized() const noexcept { return false; }

    bool resolveResidentOutput(
            std::uint64_t,
            VkBuffer& buffer,
            std::uint64_t& bytes,
            std::uint32_t& width,
            std::uint32_t& height
    ) const noexcept {
        buffer = VK_NULL_HANDLE;
        bytes = 0u;
        width = 0u;
        height = 0u;
        return false;
    }

    bool readbackResidentOutput(
            VkDevice,
            VkQueue,
            std::uint64_t,
            std::vector<float>& output
    ) noexcept {
        output.clear();
        return false;
    }
};

} // namespace bncam::vulkan
