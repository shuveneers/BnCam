#pragma once

#include "NativeRenderQualityConfig.h"

#include <android/hardware_buffer.h>
#include <cstddef>
#include <cstdint>

struct RawPreviewParameters {
    int sourceFormat = 0;
    int cfaPattern = 0;
    int demosaicMode = 3; // DemosaicMode bridge value: 0 Auto, 1 Malvar, 2 AMAZE, 3 RCD.
    float blackLevels[4] = {0.0f, 0.0f, 0.0f, 0.0f};
    int whiteLevel = 1;
    int captureSensitivityIso = 100;
    std::int64_t captureExposureTimeNs = 0;
    float focusDetailPriority = 1.0f;
    int rotationDegrees = 0;
    int sourceWidth = 0;
    int sourceHeight = 0;
    int sourceRowStrideBytes = 0;
    int sourcePixelStrideBytes = 0;
    int sourceCropLeft = 0;
    int sourceCropTop = 0;
    int sourceCropWidth = 0;
    int sourceCropHeight = 0;
    int maxWidth = 960;
    int maxHeight = 720;
    int frameSlotIndex = 0;
    NativeRenderQualityConfig quality;
};

struct RawPreviewResult {
    bool success = false;
    int width = 0;
    int height = 0;
    int renderMicroseconds = 0;
    int cfaCellDecimation = 1;
    bool vulkanStagesUsed = false;
    bool directHostInputUsed = false;
    bool directHardwareBufferInputUsed = false;
    bool gpuResidentOutputUsed = false;
    float normalizedRawMin = 0.0f;
    float normalizedRawMax = 0.0f;
    float outputRgbMin = 0.0f;
    float outputRgbMax = 0.0f;
    float outputRgbMean = 0.0f;
    float targetExposureGain = 1.0f;
    float appliedExposureGain = 1.0f;
    float sceneMidtone = 0.0f;
    float sceneMidtoneTarget = 0.155f;
    float gtmShoulderStart = 0.72f;
    float gtmShoulderStrength = 0.90f;
    float gtmBlackAnchor = 0.0065f;
    float gtmLowerMidLift = 0.0f;
    float gtmContrastStrength = 0.10f;
    float gtmDynamicRangePressure = 0.0f;
    float ltmStrength = 0.05f;
    float ltmMaxLiftEv = 0.18f;
    float ltmMaxCompressEv = 0.08f;
    std::uint64_t commonHighlightScalePixels = 0;
    std::uint32_t linearLumaHistogram[256] = {};
    std::uint32_t displayLumaHistogram[16] = {};
    std::uint32_t displayLumaHistogram64[64] = {};
    std::uint32_t displayRHistogram64[64] = {};
    std::uint32_t displayGHistogram64[64] = {};
    std::uint32_t displayBHistogram64[64] = {};
    std::uint32_t rawNearClipSampleCount = 0;
    std::uint32_t rawSampleCount = 0;
    std::uint32_t displayRClipSampleCount = 0;
    std::uint32_t displayGClipSampleCount = 0;
    std::uint32_t displayBClipSampleCount = 0;
    std::uint32_t displayShadowSampleCount = 0;
    std::uint32_t displayHighlightSampleCount = 0;
    std::uint32_t displaySampleCount = 0;
    float displayHighlightX = -1.0f;
    float displayHighlightY = -1.0f;
    int analysisNv21Width = 0;
    int analysisNv21Height = 0;
    std::uint32_t inputAhbFormat = 0;
    std::uint64_t inputAhbUsage = 0;
    std::uint32_t inputInteropStatus = 0;
    int rawUnpackMicroseconds = 0;
    int demosaicMicroseconds = 0;
    int colorMicroseconds = 0;
    int tonePackMicroseconds = 0;
    int backendMutexWaitMicroseconds = 0;
    bool backendInitializationPerformed = false;
    int backendInitializationMicroseconds = 0;
    int spirvLookupMicroseconds = 0;
    int descriptorLayoutMicroseconds = 0;
    int pipelineLayoutMicroseconds = 0;
    int shaderModuleMicroseconds = 0;
    int pipelineCacheMutexWaitMicroseconds = 0;
    bool pipelineCachePresent = false;
    int computePipelineMicroseconds = 0;
    int imageSpirvLookupMicroseconds = 0;
    int imageShaderModuleMicroseconds = 0;
    int imageComputePipelineMicroseconds = 0;
    int descriptorCommandResourcesMicroseconds = 0;
    int inputAhbProbeMicroseconds = 0;
    int outputAhbImportMicroseconds = 0;
    int commandRecordMicroseconds = 0;
    int queueMutexWaitMicroseconds = 0;
    int queueSubmitCallMicroseconds = 0;
    int fenceWaitMicroseconds = 0;
};

RawPreviewResult renderRawPreviewRgba(
        AHardwareBuffer* buffer,
        const RawPreviewParameters& parameters,
        AHardwareBuffer* outputHardwareBuffer,
        std::uint8_t* outputRgba,
        std::size_t outputCapacityBytes,
        std::uint8_t* analysisNv21,
        std::size_t analysisNv21CapacityBytes
);
