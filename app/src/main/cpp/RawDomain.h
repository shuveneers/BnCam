#pragma once

#include <array>
#include <cstddef>
#include <cstdint>
#include <string>

#include <opencv2/core.hpp>

#include "RawCfaContract.h"

enum class RawSourceFormat {
    RAW10 = 0,
    RAW_SENSOR = 1,
    RAW16_MASTER = 2
};

enum class RawStorageAlignment {
    RIGHT_JUSTIFIED = 0,
    LEFT_JUSTIFIED = 1,
    PACKED = 2
};

enum class RawSampleTransform {
    IDENTITY_NATIVE_TO_PAYLOAD = 0,
    RAW10_PACKED_TO_BLACK_ANCHORED_PAYLOAD = 1,
    RAW_SENSOR_RIGHT_JUSTIFIED_TO_PAYLOAD = 2,
    RAW16_MASTER_IDENTITY = 3
};

struct RawDomainInfo {
    RawSourceFormat sourceFormat = RawSourceFormat::RAW_SENSOR;
    int width = 0;
    int height = 0;

    // Source-buffer layout is diagnostic after the dense Master RAW16 has been built.
    // masterRowStrideBytes describes the pointer passed to normalizeRawForJpeg().
    size_t sourceRowStrideBytes = 0;
    size_t sourcePixelStrideBytes = 0;
    size_t masterRowStrideBytes = 0;
    size_t masterPixelStrideBytes = sizeof(uint16_t);

    // Android CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT at sensor origin.
    // Never coerce an unsupported/non-Bayer arrangement to RGGB.
    int sensorCfaPattern = bncam::raw::CFA_UNSUPPORTED;
    int cfaOffsetX = 0;
    int cfaOffsetY = 0;
    int effectiveCfaPattern = bncam::raw::CFA_UNSUPPORTED;
    bncam::raw::RawCfaContract cfaContract{};

    std::array<int, 4> activeArray{0, 0, 0, 0};
    std::array<int, 4> cropRegion{0, 0, 0, 0};
    std::array<int, 4> preCorrectionArray{0, 0, 0, 0};

    int sensorInfoWhiteLevel = 0;
    int sensorDynamicWhiteLevel = 0;
    bool hasDynamicWhiteLevel = false;
    std::array<float, 4> sensorBlackLevelPattern{0.0f, 0.0f, 0.0f, 0.0f};
    std::array<float, 4> sensorDynamicBlackLevel{0.0f, 0.0f, 0.0f, 0.0f};
    bool hasDynamicBlackLevel = false;
    std::string chosenBlackLevelSource = "missing/fallback_noop_0";
    std::string chosenWhiteLevelSource = "missing/fallback";

    int sourceBitDepth = 0;
    int nativeBitDepth = 0;
    int effectiveSourceRange = 0;
    int nativeWhiteLevel = 0;
    int payloadWhiteLevel = 0;
    std::array<float, 4> nativeBlackLevels{0.0f, 0.0f, 0.0f, 0.0f};
    std::array<float, 4> payloadBlackLevels{0.0f, 0.0f, 0.0f, 0.0f};
    RawStorageAlignment sourceStorageAlignment = RawStorageAlignment::RIGHT_JUSTIFIED;
    RawStorageAlignment masterStorageAlignment = RawStorageAlignment::RIGHT_JUSTIFIED;
    RawSampleTransform sampleTransform = RawSampleTransform::IDENTITY_NATIVE_TO_PAYLOAD;
    bool dynamicBlackLevelUsed = false;
    bool dynamicWhiteLevelUsed = false;
    bool staticBlackLevelUsed = false;
    bool staticWhiteLevelUsed = false;
    bool manualOverrideUsed = false;
    std::string lensShadingState = "UNKNOWN";
    std::string physicalCameraId = "not_reported";
    std::string lensId = "unknown";
    std::string validationWarnings = "none";

    // A Master RAW16 container is not necessarily a 16-bit signal domain. These fields make
    // the conversion explicit. RAW10 currently remains right-justified (scale 1, shift 0).
    float masterStorageScale = 1.0f;
    int masterStorageLeftShift = 0;
    std::string masterStorageContract = "RIGHT_JUSTIFIED_NATIVE_CODE_VALUES";
    float effectiveWhiteLevelInMasterUnits = 1.0f;
    std::array<float, 4> effectiveBlackLevelPatternInMasterUnits{0.0f, 0.0f, 0.0f, 0.0f};

    bool debugDumpsEnabled = false;
    std::string debugDumpDirectory;
};

struct RawChannelDistribution {
    float min = 0.0f;
    float p0_1 = 0.0f;
    float p1 = 0.0f;
    float median = 0.0f;
    float p99 = 0.0f;
    float max = 0.0f;
};

struct RawNormalizationChannelStats {
    RawChannelDistribution beforeBlack;
    RawChannelDistribution afterBlack;
    RawChannelDistribution normalizedBeforeClamp;
    RawChannelDistribution normalizedFinal;
    double belowZeroPct = 0.0;
    double aboveWhitePct = 0.0;
    uint64_t pixelCount = 0;
};

struct RawNormalizationDiagnostics {
    bool valid = false;
    std::string failureReason = "not_run";
    std::array<RawNormalizationChannelStats, 4> channels;
    uint64_t sampledPixels = 0;
    bool negativeValuesTrackedBeforeClamp = false;
    bool clampAppliedAfterStats = false;
};

struct LinearFloatRaw {
    cv::Mat mosaic; // CV_32FC1, black-subtracted and normalized to [0, 1].
    RawDomainInfo info;
    RawNormalizationDiagnostics diagnostics;
};

// Phase 13 compact-planning view. It aliases the read-only canonical RAW16 publication
// buffer and normalizes only explicitly requested samples. It must never be used as a
// replacement full-frame CPU ISP path. The per-sample math is identical to
// normalizeRawForJpeg() and raw_jpeg_normalize.comp.
struct RawNormalizedSampleView {
    const uint16_t* masterRaw16 = nullptr;
    RawDomainInfo info{};
    size_t rowStrideBytes = 0u;
    // DELTA 0218: immutable per-CFA normalization scalars. These are derived once from
    // RawDomainInfo so compact planning samples do not repeat a floating-point divide.
    std::array<float, 4> sampleBlack{0.0f, 0.0f, 0.0f, 0.0f};
    std::array<float, 4> sampleInverseRange{1.0f, 1.0f, 1.0f, 1.0f};
    bool valid = false;
    std::string failureReason = "not_initialized";

    float sample(int x, int y) const noexcept;
};

RawNormalizedSampleView makeRawNormalizedSampleView(
        const uint16_t* masterRaw16,
        const RawDomainInfo& info
) noexcept;

// Shared RAW-domain entry point for every RAW10/RAW_SENSOR JPEG. It never mutates masterRaw16.
LinearFloatRaw normalizeRawForJpeg(
        const uint16_t* masterRaw16,
        const RawDomainInfo& info
);

bool rawCfaIsStandardBayer(int cfaPattern) noexcept;
int effectiveCfaPatternAtOrigin(int sensorCfaPattern, int cfaOffsetX, int cfaOffsetY);
const char* rawCfaPatternName(int cfaPattern);
const char* rawCfaContractKindName(bncam::raw::RawCfaContractKind kind);
const char* rawSourceFormatName(RawSourceFormat sourceFormat);
const char* rawStorageAlignmentName(RawStorageAlignment alignment);
const char* rawSampleTransformName(RawSampleTransform transform);

std::string formatRawNormalizationDebug(
        const LinearFloatRaw& workingRaw,
        const std::array<float, 4>& awbGains,
        const std::array<float, 9>& colorMatrix,
        float renderGain,
        float luminanceP50,
        float luminanceP75,
        float luminanceP95,
        float luminanceP99,
        const std::string& demosaicMode,
        const std::string& toneCurveMode,
        const std::string& gammaMode
);

bool dumpNormalizedMosaicPreview(const LinearFloatRaw& workingRaw);
