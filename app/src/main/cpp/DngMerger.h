#pragma once

#include <jni.h>
#include <android/hardware_buffer.h>
#include <cstdint>
#include <cstddef>
#include <array>
#include <string>
#include <vector>
#include "NativeRenderQualityConfig.h" // NIEUW: Importeer de hardware overrides

struct DngMergeStats {
    std::string route = "unknown";
    int framesReceived = 0;
    int framesUsedForDng = 0;
    int framesDecoded = 0;
    int supportAccepted = 0;
    int supportRejected = 0;
    int supportRejectedCanonicalization = 0;
    int supportRejectedAlignment = 0;
    int supportRejectedForwardBackward = 0;
    int supportRejectedSpectraConsensus = 0;
    int supportRejectedZeroWeightedContribution = 0;
    int supportRejectedOther = 0;
    int framesMerged = 0;
    bool mergeAttempted = false;
    bool mergeOutputCreated = false;
    bool anchorOnly = true;
    uint32_t width = 0;
    uint32_t height = 0;
    uint32_t rowStrideBytes = 0;
    uint32_t pixelStrideBytes = 0;
    int cfaPattern = 0;
    int whiteLevel = 0;
    int inputNativeWhiteLevel = 0;
    int payloadWhiteLevel = 0;
    std::string inputNativeBlackLevels = "unknown";
    std::string payloadBlackLevels = "unknown";
    float payloadScaleFactor = 1.0f;
    uint16_t raw16Min = 0;
    uint16_t raw16P01 = 0;
    uint16_t raw16P50 = 0;
    uint16_t raw16P95 = 0;
    uint16_t raw16P99 = 0;
    uint16_t raw16Max = 0;
    uint16_t sampledRaw16Min = 0;
    uint16_t sampledRaw16P01 = 0;
    uint16_t sampledRaw16P50 = 0;
    uint16_t sampledRaw16P95 = 0;
    uint16_t sampledRaw16P99 = 0;
    uint16_t sampledRaw16Max = 0;
    uint16_t fullRaw16Min = 0;
    uint16_t fullRaw16Max = 0;
    uint64_t fullRaw16SaturatedCount = 0;
    double fullRaw16SaturatedPct = 0.0;
    std::string raw16FullStatsSource = "NOT_RECORDED";
    uint16_t unpackedMin = 0;
    uint16_t unpackedP01 = 0;
    uint16_t unpackedP50 = 0;
    uint16_t unpackedP99 = 0;
    uint16_t unpackedMax = 0;
    uint32_t raw10ExpectedRowStride = 0;
    uint32_t raw10ActualRowStride = 0;
    bool raw10StrideMatchesExpected = false;
    uint32_t raw10PaddingBytesPerRow = 0;
    uint32_t raw10MalformedRowCount = 0;
    uint32_t rawSensorMinimumRowBytes = 0;
    uint32_t rawSensorActualRowStride = 0;
    uint32_t rawSensorPixelStride = 0;
    uint32_t rawSensorPaddingBytesPerRow = 0;
    bool sourceStrideValid = false;
    bool sourcePixelStrideValid = false;
    std::string sampleScaleContract = "unknown";
    bool sampleTransformApplied = false;
    std::string supportFrameProvenance = "none";
    int maxFramesCap = 0;
    int maxShiftPixels = 0;
    float alignmentStrictness = 0.0f;
    double avgAppliedShiftX = 0.0;
    double avgAppliedShiftY = 0.0;
    double avgPhaseResponse = 0.0;
    double minPhaseResponse = 0.0;
    double maxPhaseResponse = 0.0;
    size_t raw16Bytes = 0;
    uint64_t peakWorkingSetEstimateBytes = 0;
    double anchorUnpackMs = 0.0;
    double supportUnpackMs = 0.0;
    double alignmentMs = 0.0;
    double mergeAccumulatorMs = 0.0;
    double mergeNormalizeMs = 0.0;
    double outputArrayMs = 0.0;
    double totalNativeDngMergeMs = 0.0;
    size_t nativeRaw16OutstandingBuffersAtReturn = 0;

    // GPU-migration residency telemetry. A CPU byte staging copy is a transfer boundary, not
    // a CPU pixel operation; cpuFullFrameRawUnpack is true only when the reference unpacker ran.
    std::string inputImportPath = "NOT_ATTEMPTED";
    std::string rawUnpackBackend = "NOT_ATTEMPTED";
    bool cpuFullFrameRawUnpack = false;
    bool cpuFallbackUsed = false;
    std::string cpuFallbackReason = "none";
    int rawUnpackVulkanFrames = 0;
    int rawUnpackCpuFallbackFrames = 0;
    uint64_t fullFrameCpuUploadBytes = 0;
    uint64_t fullFrameGpuReadbackBytes = 0;
    double rawUnpackGpuMs = 0.0;
    double rawUnpackTransferMs = 0.0;
    double rawUnpackReadbackMs = 0.0;

    // RAW multi-frame GPU-residency telemetry. These fields describe the authoritative
    // production path; CPU reference alignment/fusion is allowed only after explicit Vulkan failure.
    std::string alignmentBackend = "NOT_ATTEMPTED";
    std::string fusionBackend = "NOT_ATTEMPTED";
    bool cpuAlignment = false;
    bool cpuFusion = false;
    bool cpuFullFrameSupportMaterialization = false;
    bool residentFusedRawProduced = false;
    uint64_t compactGpuReadbackBytes = 0;
    double rawAlignmentGpuMs = 0.0;
    double rawFusionGpuMs = 0.0;
    double rawMultiFrameObserverGpuMs = 0.0;
    double rawMultiFrameGpuSynchronizationMs = 0.0;
    double rawMultiFrameFinalReadbackMs = 0.0;

    // Physical temporal S/O availability is independent from optional SPECTRA adaptation.
    bool temporalNoiseModelEnabled = false;
    bool spectraAdaptiveCalibrationEnabled = false;
    std::string temporalNoiseModelAuthority = "DISABLED";

    // SPECTRA capture-integrated temporal observation and fusion weighting.
    bool spectraEnabled = false;
    float spectraModelConfidence = 0.0f;
    float spectraNoisePressure = 0.0f;
    float spectraTemporalAuthority = 0.0f;
    float spectraTemporalCorrelation = 0.0f;
    float spectraIndependentNoiseFraction = 1.0f;
    int spectraAcceptedFramePairs = 0;
    int spectraRejectedFramePairs = 0;
    float spectraAlignmentConfidence = 0.0f;
    float spectraMotionConfidence = 0.0f;
    int spectraObserverSamples = 0;
    std::array<double, 4> spectraObservedVariance{0.0, 0.0, 0.0, 0.0};
    std::array<double, 4> spectraPredictedVariance{0.0, 0.0, 0.0, 0.0};
    std::array<double, 4> spectraAdaptationScale{1.0, 1.0, 1.0, 1.0};
    std::array<double, 4> spectraSAdaptationScale{1.0, 1.0, 1.0, 1.0};
    std::array<double, 4> spectraOAdaptationScale{1.0, 1.0, 1.0, 1.0};
    std::array<double, 4> spectraRegressionConfidence{0.0, 0.0, 0.0, 0.0};
    std::array<double, 4> spectraSignalSpan{0.0, 0.0, 0.0, 0.0};
    std::array<int, 4> spectraRegressionBins{0, 0, 0, 0};
    double spectraObserverConfidence = 0.0;
    double spectraAverageSupportWeight = 0.0;
    double spectraFusionVarianceScale = 1.0;
    double spectraFusionVarianceP10 = 1.0;
    double spectraFusionVarianceP50 = 1.0;
    double spectraFusionVarianceP90 = 1.0;
    double spectraEffectiveFrameCount = 1.0;
    double spectraEffectiveFrameCountP10 = 1.0;
    double spectraEffectiveFrameCountP50 = 1.0;
    double spectraEffectiveFrameCountP90 = 1.0;
    double spectraPersistentPatternFraction = 0.0;
    double spectraForwardBackwardConsistency = 0.0;
    double spectraForwardBackwardConsistencyMin = 0.0;
    int spectraForwardBackwardRejectedPairs = 0;
    double spectraRepeatedSupportConfidence = 0.0;
    double spectraStaticProbabilityMean = 0.0;
    double spectraStaticProbabilityP10 = 0.0;
    double spectraStaticProbabilityP50 = 0.0;
    double spectraStaticProbabilityP90 = 0.0;
    double spectraNormalizedInnovationMean = 0.0;
    double spectraNormalizedInnovationVariance = 0.0;
    double spectraNormalizedInnovationP90 = 0.0;
    double spectraNormalizedInnovationLagCorrelation = 0.0;
    double spectraHeavyTailFraction = 0.0;
    double spectraLocalFusionFallbackFraction = 1.0;
    uint64_t spectraHighConfidenceStaticSamples = 0;
    uint64_t spectraClippingRejectedSamples = 0;
    uint64_t spectraTextureRejectedSamples = 0;
    uint64_t spectraLocalMotionRejectedSamples = 0;
    int spectraStaticMapColumns = 0;
    int spectraStaticMapRows = 0;
    int spectraStaticMapCellSize = 0;
    int spectraWlsSelectedChannels = 0;
    int spectraHuberSelectedChannels = 0;
    double spectraFitStabilityConfidence = 0.0;
    double spectraFitStabilityMin = 0.0;
    std::array<double, 4> spectraFitStabilityByChannel{0.0, 0.0, 0.0, 0.0};
    std::array<double, 4> spectraFitPhysicalScore{0.0, 0.0, 0.0, 0.0};
    std::array<double, 4> spectraFitInnovationMean{0.0, 0.0, 0.0, 0.0};
    std::array<double, 4> spectraFitInnovationVariance{0.0, 0.0, 0.0, 0.0};
    std::array<double, 4> spectraFitResidualCorrelation{0.0, 0.0, 0.0, 0.0};
    std::array<double, 4> spectraFitHeavyTailFraction{0.0, 0.0, 0.0, 0.0};
    std::array<int, 4> spectraFitEstimator{0, 0, 0, 0};
    double spectraForwardBackwardAlignmentMs = 0.0;
    double spectraTemporalObserverMs = 0.0;
    int spectraTemporalVulkanAttemptedPairs = 0;
    int spectraTemporalVulkanSucceededPairs = 0;
    int spectraTemporalVulkanCpuFallbackPairs = 0;
    int spectraTemporalVulkanPersistentReusePairs = 0;
    int spectraTemporalVulkanReallocatedPairs = 0;
    double spectraTemporalVulkanInputPackingMs = 0.0;
    double spectraTemporalVulkanCoarseKernelMs = 0.0;
    double spectraTemporalVulkanCoarseReductionMs = 0.0;
    double spectraTemporalVulkanObserverKernelMs = 0.0;
    double spectraTemporalVulkanStaticFieldKernelMs = 0.0;
    double spectraTemporalVulkanCompactReductionAndFitMs = 0.0;
    double spectraTemporalVulkanSynchronizationMs = 0.0;
    double spectraTemporalVulkanReadbackMs = 0.0;
    double spectraTemporalVulkanTotalMs = 0.0;
    uint64_t spectraTemporalVulkanInputBytes = 0;
    uint64_t spectraTemporalVulkanCompactReadbackBytes = 0;
    uint64_t spectraTemporalVulkanPersistentResidentBytes = 0;
    std::string spectraTemporalVulkanStatus = "NOT_ATTEMPTED";
    std::string spectraTemporalVulkanFailureReason = "none";
    double spectraStaticConsensusMs = 0.0;
    double spectraFitConsensusMs = 0.0;
    double spectraCorrelationAnalysisMs = 0.0;
    bool spectraObserverOnly = false;

    // NIEUW: Metadata payload voor Kotlin/DngWriter
    std::string hardwareConfigMetadata = "none";
    std::string failureReason = "none";
};

jobject mergeRaw10DngToRaw16(
        JNIEnv *env,
        const std::vector<AHardwareBuffer*>& hwBuffers,
        jint cfaPattern,
        jint whiteLevel,
        const std::vector<int32_t>& blackLevels,
        jint maxFramesCap,
        jint maxShiftPixels,
        jfloat alignmentStrictness,
        jint spectraMode,
        bool temporalNoiseModelEnabled,
        bool spectraAdaptiveCalibrationEnabled,
        const std::vector<double>& spectraEffectiveS,
        const std::vector<double>& spectraEffectiveO,
        jfloat spectraModelConfidence,
        bool fuseSupportFrames,
        const std::vector<float>& exposureScaleToAnchor,
        bool computationalHdr,
        int sourceCropLeft,
        int sourceCropTop,
        int sourceCropWidth,
        int sourceCropHeight,
        DngMergeStats* stats
);

jobject mergeRawSensorDngToRaw16(
        JNIEnv *env,
        const std::vector<AHardwareBuffer*>& hwBuffers,
        jint cfaPattern,
        jint whiteLevel,
        const std::vector<int32_t>& blackLevels,
        jint maxFramesCap,
        jint maxShiftPixels,
        jfloat alignmentStrictness,
        jint spectraMode,
        bool temporalNoiseModelEnabled,
        bool spectraAdaptiveCalibrationEnabled,
        const std::vector<double>& spectraEffectiveS,
        const std::vector<double>& spectraEffectiveO,
        jfloat spectraModelConfidence,
        bool fuseSupportFrames,
        const std::vector<float>& exposureScaleToAnchor,
        bool computationalHdr,
        int sourceCropLeft,
        int sourceCropTop,
        int sourceCropWidth,
        int sourceCropHeight,
        DngMergeStats* stats
);

std::string formatDngMergeStats(const DngMergeStats& stats);

/** Releases a RAW16 allocation created by mergeRaw*DngToRaw16. Returns false for unknown/already-released pointers. */
bool releaseNativeRaw16Allocation(void* address);

/** Phase 13 opaque Phase-9 resident generation associated with this publication payload, or 0. */
std::uint64_t nativeRaw16ResidentGeneration(void* address);
std::uint64_t nativeRaw16MultiFrameResidentGeneration(void* address);

/** Diagnostic count of native RAW16 owners that have not yet been released. */
size_t nativeRaw16OutstandingAllocationCount();
