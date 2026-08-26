#include "RawDomain.h"
#include "RawCfaLevelMapping.h"

#include <algorithm>
#include <array>
#include <cmath>
#include <iomanip>
#include <limits>
#include <sstream>
#include <vector>

#include <android/log.h>
#include <opencv2/imgcodecs.hpp>

#define RAW_DOMAIN_LOG_TAG "BnCam_RawDomain"
#define RAW_DOMAIN_LOGI(...) __android_log_print(ANDROID_LOG_INFO, RAW_DOMAIN_LOG_TAG, __VA_ARGS__)
#define RAW_DOMAIN_LOGE(...) __android_log_print(ANDROID_LOG_ERROR, RAW_DOMAIN_LOG_TAG, __VA_ARGS__)

namespace {

constexpr int CFA_RGGB = 0;
constexpr int CFA_GRBG = 1;
constexpr int CFA_GBRG = 2;
constexpr int CFA_BGGR = 3;

int safeCfaPattern(int pattern) {
    return pattern >= CFA_RGGB && pattern <= CFA_BGGR ? pattern : CFA_RGGB;
}

// Plane order is R, Gr, Gb, B. Black-level patterns themselves remain positional [00,10,01,11].
int cfaPlaneAt(int pattern, int x, int y) {
    return bncam::raw::canonicalPlaneAtMosaicSite(pattern, x, y);
}

int patternFromPlanes(const std::array<int, 4>& planes) {
    const auto color = [](int plane) { return plane == 1 || plane == 2 ? 1 : plane; };
    const std::array<int, 4> colors{{
            color(planes[0]), color(planes[1]), color(planes[2]), color(planes[3])
    }};
    static const std::array<std::array<int, 4>, 4> patterns{{
            {{0, 1, 1, 3}}, // RGGB
            {{1, 0, 3, 1}}, // GRBG
            {{1, 3, 0, 1}}, // GBRG
            {{3, 1, 1, 0}}  // BGGR
    }};
    for (int pattern = 0; pattern < 4; ++pattern) {
        if (patterns[static_cast<size_t>(pattern)] == colors) return pattern;
    }
    return CFA_RGGB;
}

RawChannelDistribution distribution(std::vector<float>& values) {
    RawChannelDistribution out{};
    if (values.empty()) return out;
    std::sort(values.begin(), values.end());
    const size_t n = values.size();
    const auto at = [&](double percentile) -> float {
        const size_t index = std::min(n - 1u, static_cast<size_t>(std::floor(percentile * static_cast<double>(n - 1u))));
        return values[index];
    };
    out.min = values.front();
    out.p0_1 = at(0.001);
    out.p1 = at(0.01);
    out.median = at(0.50);
    out.p99 = at(0.99);
    out.max = values.back();
    return out;
}

std::string array4(const std::array<float, 4>& values, int precision = 3) {
    std::ostringstream out;
    out << std::fixed << std::setprecision(precision)
        << values[0] << "," << values[1] << "," << values[2] << "," << values[3];
    return out.str();
}

std::string array9(const std::array<float, 9>& values) {
    std::ostringstream out;
    out << std::fixed << std::setprecision(5);
    for (size_t i = 0; i < values.size(); ++i) {
        if (i > 0) out << ",";
        out << values[i];
    }
    return out.str();
}

std::string intArray4(const std::array<int, 4>& values) {
    std::ostringstream out;
    out << values[0] << "," << values[1] << "," << values[2] << "," << values[3];
    return out.str();
}

std::string distributionText(const RawChannelDistribution& stats) {
    std::ostringstream out;
    out << std::fixed << std::setprecision(5)
        << "min=" << stats.min
        << ",p0.1=" << stats.p0_1
        << ",p1=" << stats.p1
        << ",median=" << stats.median
        << ",p99=" << stats.p99
        << ",max=" << stats.max;
    return out.str();
}

std::string joinPath(const std::string& directory, const std::string& filename) {
    if (directory.empty()) return filename;
    const char last = directory.back();
    return directory + ((last == '/' || last == '\\') ? "" : "/") + filename;
}

} // namespace

int effectiveCfaPatternAtOrigin(int sensorCfaPattern, int cfaOffsetX, int cfaOffsetY) {
    std::array<int, 4> planes{};
    planes[0] = cfaPlaneAt(sensorCfaPattern, cfaOffsetX, cfaOffsetY);
    planes[1] = cfaPlaneAt(sensorCfaPattern, cfaOffsetX + 1, cfaOffsetY);
    planes[2] = cfaPlaneAt(sensorCfaPattern, cfaOffsetX, cfaOffsetY + 1);
    planes[3] = cfaPlaneAt(sensorCfaPattern, cfaOffsetX + 1, cfaOffsetY + 1);
    return patternFromPlanes(planes);
}

const char* rawCfaPatternName(int cfaPattern) {
    switch (safeCfaPattern(cfaPattern)) {
        case CFA_RGGB: return "RGGB";
        case CFA_GRBG: return "GRBG";
        case CFA_GBRG: return "GBRG";
        case CFA_BGGR: return "BGGR";
        default: return "UNKNOWN";
    }
}

const char* rawSourceFormatName(RawSourceFormat sourceFormat) {
    switch (sourceFormat) {
        case RawSourceFormat::RAW10: return "RAW10";
        case RawSourceFormat::RAW_SENSOR: return "RAW_SENSOR";
        case RawSourceFormat::RAW16_MASTER: return "RAW16_MASTER";
        default: return "UNKNOWN";
    }
}

const char* rawStorageAlignmentName(RawStorageAlignment alignment) {
    switch (alignment) {
        case RawStorageAlignment::RIGHT_JUSTIFIED: return "RIGHT_JUSTIFIED";
        case RawStorageAlignment::LEFT_JUSTIFIED: return "LEFT_JUSTIFIED";
        case RawStorageAlignment::PACKED: return "PACKED";
        default: return "UNKNOWN";
    }
}

const char* rawSampleTransformName(RawSampleTransform transform) {
    switch (transform) {
        case RawSampleTransform::IDENTITY_NATIVE_TO_PAYLOAD: return "IDENTITY_NATIVE_TO_PAYLOAD";
        case RawSampleTransform::RAW10_PACKED_TO_BLACK_ANCHORED_PAYLOAD: return "RAW10_PACKED_TO_BLACK_ANCHORED_PAYLOAD";
        case RawSampleTransform::RAW_SENSOR_RIGHT_JUSTIFIED_TO_PAYLOAD: return "RAW_SENSOR_RIGHT_JUSTIFIED_TO_PAYLOAD";
        case RawSampleTransform::RAW16_MASTER_IDENTITY: return "RAW16_MASTER_IDENTITY";
        default: return "UNKNOWN";
    }
}

float RawNormalizedSampleView::sample(int x, int y) const noexcept {
    if (!valid || masterRaw16 == nullptr || x < 0 || y < 0 || x >= info.width || y >= info.height) {
        return std::numeric_limits<float>::quiet_NaN();
    }
    const auto* sourceRow = reinterpret_cast<const uint16_t*>(
            reinterpret_cast<const uint8_t*>(masterRaw16) +
            static_cast<size_t>(y) * rowStrideBytes);
    const int blackIndex = (((y + info.cfaOffsetY) & 1) << 1) |
            ((x + info.cfaOffsetX) & 1);
    const float black = info.effectiveBlackLevelPatternInMasterUnits[
            static_cast<size_t>(blackIndex)];
    const float white = info.effectiveWhiteLevelInMasterUnits;
    const float inverseRange = 1.0f / std::max(1.0f, white - black);
    return std::clamp(
            (static_cast<float>(sourceRow[x]) - black) * inverseRange,
            0.0f, 1.0f);
}

RawNormalizedSampleView makeRawNormalizedSampleView(
        const uint16_t* masterRaw16,
        const RawDomainInfo& requestedInfo
) noexcept {
    RawNormalizedSampleView view{};
    view.masterRaw16 = masterRaw16;
    view.info = requestedInfo;
    view.info.sensorCfaPattern = safeCfaPattern(view.info.sensorCfaPattern);
    view.info.effectiveCfaPattern = effectiveCfaPatternAtOrigin(
            view.info.sensorCfaPattern, view.info.cfaOffsetX, view.info.cfaOffsetY);

    if (masterRaw16 == nullptr || view.info.width <= 0 || view.info.height <= 0) {
        view.failureReason = "invalid_master_pointer_or_dimensions";
        return view;
    }
    const size_t minimumStride = static_cast<size_t>(view.info.width) * sizeof(uint16_t);
    view.rowStrideBytes = view.info.masterRowStrideBytes > 0u
            ? view.info.masterRowStrideBytes : minimumStride;
    if (view.rowStrideBytes < minimumStride) {
        view.failureReason = "master_row_stride_smaller_than_width";
        return view;
    }

    const float white = view.info.effectiveWhiteLevelInMasterUnits;
    if (!std::isfinite(white) || white <= 0.0f) {
        view.failureReason = "invalid_effective_white_level_in_master_units";
        return view;
    }
    for (float black : view.info.effectiveBlackLevelPatternInMasterUnits) {
        if (!std::isfinite(black) || black < 0.0f || black >= white) {
            view.failureReason = "invalid_effective_black_level_pattern_in_master_units";
            return view;
        }
    }

    view.valid = true;
    view.failureReason = "none";
    return view;
}

LinearFloatRaw normalizeRawForJpeg(const uint16_t* masterRaw16, const RawDomainInfo& requestedInfo) {
    LinearFloatRaw out{};
    out.info = requestedInfo;
    out.info.sensorCfaPattern = safeCfaPattern(out.info.sensorCfaPattern);
    out.info.effectiveCfaPattern = effectiveCfaPatternAtOrigin(
            out.info.sensorCfaPattern,
            out.info.cfaOffsetX,
            out.info.cfaOffsetY
    );

    if (masterRaw16 == nullptr || out.info.width <= 0 || out.info.height <= 0) {
        out.diagnostics.failureReason = "invalid_master_pointer_or_dimensions";
        RAW_DOMAIN_LOGE("RAW normalization rejected invalid input");
        return out;
    }

    const size_t minimumStride = static_cast<size_t>(out.info.width) * sizeof(uint16_t);
    const size_t rowStride = out.info.masterRowStrideBytes > 0
            ? out.info.masterRowStrideBytes
            : minimumStride;
    if (rowStride < minimumStride) {
        out.diagnostics.failureReason = "master_row_stride_smaller_than_width";
        RAW_DOMAIN_LOGE("RAW normalization rejected rowStride=%zu minimum=%zu", rowStride, minimumStride);
        return out;
    }

    const float white = out.info.effectiveWhiteLevelInMasterUnits;
    if (!std::isfinite(white) || white <= 0.0f) {
        out.diagnostics.failureReason = "invalid_effective_white_level_in_master_units";
        RAW_DOMAIN_LOGE("RAW normalization rejected master white=%f", white);
        return out;
    }
    for (float black : out.info.effectiveBlackLevelPatternInMasterUnits) {
        if (!std::isfinite(black) || black < 0.0f || black >= white) {
            out.diagnostics.failureReason = "invalid_effective_black_level_pattern_in_master_units";
            RAW_DOMAIN_LOGE("RAW normalization rejected black=%f white=%f", black, white);
            return out;
        }
    }

    out.mosaic = cv::Mat(out.info.height, out.info.width, CV_32FC1);
    cv::parallel_for_(cv::Range(0, out.info.height), [&](const cv::Range& range) {
        for (int y = range.start; y < range.end; ++y) {
            const auto* sourceRow = reinterpret_cast<const uint16_t*>(
                    reinterpret_cast<const uint8_t*>(masterRaw16) + static_cast<size_t>(y) * rowStride
            );
            float* destinationRow = out.mosaic.ptr<float>(y);
            const int cfaY = (y + out.info.cfaOffsetY) & 1;
            const int bIdx0 = cfaY * 2 + ((0 + out.info.cfaOffsetX) & 1);
            const int bIdx1 = cfaY * 2 + ((1 + out.info.cfaOffsetX) & 1);
            const float blk0 = out.info.effectiveBlackLevelPatternInMasterUnits[static_cast<size_t>(bIdx0)];
            const float blk1 = out.info.effectiveBlackLevelPatternInMasterUnits[static_cast<size_t>(bIdx1)];
            const float invRange0 = 1.0f / std::max(1.0f, white - blk0);
            const float invRange1 = 1.0f / std::max(1.0f, white - blk1);

            int x = 0;
#if defined(__ARM_NEON) || defined(__ARM_NEON__)
            for (; x + 3 < out.info.width; x += 4) {
                const float v0 = (static_cast<float>(sourceRow[x + 0]) - blk0) * invRange0;
                const float v1 = (static_cast<float>(sourceRow[x + 1]) - blk1) * invRange1;
                const float v2 = (static_cast<float>(sourceRow[x + 2]) - blk0) * invRange0;
                const float v3 = (static_cast<float>(sourceRow[x + 3]) - blk1) * invRange1;
                destinationRow[x + 0] = std::clamp(v0, 0.0f, 1.0f);
                destinationRow[x + 1] = std::clamp(v1, 0.0f, 1.0f);
                destinationRow[x + 2] = std::clamp(v2, 0.0f, 1.0f);
                destinationRow[x + 3] = std::clamp(v3, 0.0f, 1.0f);
            }
#endif
            for (; x < out.info.width; ++x) {
                const int cfaX = (x + out.info.cfaOffsetX) & 1;
                const float blk = (cfaX == 0) ? blk0 : blk1;
                const float invR = (cfaX == 0) ? invRange0 : invRange1;
                destinationRow[x] = std::clamp((static_cast<float>(sourceRow[x]) - blk) * invR, 0.0f, 1.0f);
            }
        }
    });

    // The baseline needs only the validity contract; detailed diagnostic distributions were a
    // costly legacy experiment and are deliberately no longer collected on every capture.
    out.diagnostics.clampAppliedAfterStats = true;
    out.diagnostics.valid = true;
    out.diagnostics.failureReason = "none";

    if (out.info.debugDumpsEnabled) dumpNormalizedMosaicPreview(out);
    return out;
}

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
) {
    const RawDomainInfo& info = workingRaw.info;
    const RawNormalizationDiagnostics& diagnostics = workingRaw.diagnostics;
    static const std::array<const char*, 4> channelNames{{"R", "Gr", "Gb", "B"}};
    std::ostringstream out;
    out << std::fixed << std::setprecision(5)
        << "RAW_NORMALIZATION_DEBUG:"
        << ";sourceFormat=" << rawSourceFormatName(info.sourceFormat)
        << ";imageWidth=" << info.width
        << ";imageHeight=" << info.height
        << ";nativeBitDepth=" << info.nativeBitDepth
        << ";sourceStorageAlignment=" << rawStorageAlignmentName(info.sourceStorageAlignment)
        << ";masterStorageAlignment=" << rawStorageAlignmentName(info.masterStorageAlignment)
        << ";nativeBlackLevels=" << array4(info.nativeBlackLevels)
        << ";nativeWhiteLevel=" << info.nativeWhiteLevel
        << ";payloadBlackLevels=" << array4(info.payloadBlackLevels)
        << ";payloadWhiteLevel=" << info.payloadWhiteLevel
        << ";sampleTransform=" << rawSampleTransformName(info.sampleTransform)
        << ";dynamicBlackLevelUsed=" << (info.dynamicBlackLevelUsed ? "true" : "false")
        << ";dynamicWhiteLevelUsed=" << (info.dynamicWhiteLevelUsed ? "true" : "false")
        << ";staticBlackLevelUsed=" << (info.staticBlackLevelUsed ? "true" : "false")
        << ";staticWhiteLevelUsed=" << (info.staticWhiteLevelUsed ? "true" : "false")
        << ";manualOverrideUsed=" << (info.manualOverrideUsed ? "true" : "false")
        << ";physicalCameraId=" << info.physicalCameraId
        << ";lensId=" << info.lensId
        << ";lensShadingState=" << info.lensShadingState
        << ";validationWarnings=" << info.validationWarnings
        << ";sourceRowStride=" << info.sourceRowStrideBytes
        << ";sourcePixelStride=" << info.sourcePixelStrideBytes
        << ";masterRowStride=" << info.masterRowStrideBytes
        << ";masterPixelStride=" << info.masterPixelStrideBytes
        << ";cfaPatternFromCameraCharacteristics=" << rawCfaPatternName(info.sensorCfaPattern)
        << ";effectiveCfaPatternAtBufferOrigin=" << rawCfaPatternName(info.effectiveCfaPattern)
        << ";cfaOffsetX=" << info.cfaOffsetX
        << ";cfaOffsetY=" << info.cfaOffsetY
        << ";activeArray=" << intArray4(info.activeArray)
        << ";cropRegion=" << intArray4(info.cropRegion)
        << ";preCorrectionArray=" << intArray4(info.preCorrectionArray)
        << ";sensorInfoWhiteLevel=" << info.sensorInfoWhiteLevel
        << ";sensorDynamicWhiteLevel=" << (info.hasDynamicWhiteLevel ? std::to_string(info.sensorDynamicWhiteLevel) : "missing")
        << ";sensorBlackLevelPattern=" << array4(info.sensorBlackLevelPattern)
        << ";sensorDynamicBlackLevel=" << (info.hasDynamicBlackLevel ? array4(info.sensorDynamicBlackLevel) : "missing")
        << ";chosenBlackLevelSource=" << info.chosenBlackLevelSource
        << ";chosenBlackLevelPatternMasterUnits=" << array4(info.effectiveBlackLevelPatternInMasterUnits)
        << ";chosenWhiteLevelSource=" << info.chosenWhiteLevelSource
        << ";chosenWhiteLevelMasterUnits=" << info.effectiveWhiteLevelInMasterUnits
        << ";sourceBitDepth=" << info.sourceBitDepth
        << ";effectiveSourceRange=" << info.effectiveSourceRange
        << ";masterStorageScale=" << info.masterStorageScale
        << ";masterStorageLeftShift=" << info.masterStorageLeftShift
        << ";masterStorageContract=" << info.masterStorageContract
        << ";negativeValuesTrackedBeforeClamp=" << (diagnostics.negativeValuesTrackedBeforeClamp ? "true" : "false")
        << ";clampAppliedAfterStats=" << (diagnostics.clampAppliedAfterStats ? "true" : "false")
        << ";normalizationValid=" << (diagnostics.valid ? "true" : "false")
        << ";normalizationFailureReason=" << diagnostics.failureReason;

    for (size_t channel = 0; channel < 4; ++channel) {
        const auto& stats = diagnostics.channels[channel];
        out << ";rawBeforeBlack_" << channelNames[channel] << "={" << distributionText(stats.beforeBlack) << "}"
            << ";rawAfterBlack_" << channelNames[channel] << "={" << distributionText(stats.afterBlack) << "}"
            << ";belowZeroPct_" << channelNames[channel] << "=" << stats.belowZeroPct
            << ";aboveWhitePct_" << channelNames[channel] << "=" << stats.aboveWhitePct
            << ";normalizedBeforeClamp_" << channelNames[channel] << "={" << distributionText(stats.normalizedBeforeClamp) << "}"
            << ";normalizedFinal_" << channelNames[channel] << "={" << distributionText(stats.normalizedFinal) << "}";
    }

    out << ";demosaicCfaModeUsed=" << demosaicMode
        << ";awbGainsUsed=" << array4(awbGains, 5)
        << ";ccmUsed=" << array9(colorMatrix)
        << ";sceneLuminanceP50=" << luminanceP50
        << ";sceneLuminanceP75=" << luminanceP75
        << ";sceneLuminanceP95=" << luminanceP95
        << ";sceneLuminanceP99=" << luminanceP99
        << ";renderGainUsed=" << renderGain
        << ";toneCurveModeUsed=" << toneCurveMode
        << ";gammaModeUsed=" << gammaMode
        << ";debugDumpsEnabled=" << (info.debugDumpsEnabled ? "true" : "false");
    return out.str();
}

bool dumpNormalizedMosaicPreview(const LinearFloatRaw& workingRaw) {
    if (!workingRaw.info.debugDumpsEnabled || workingRaw.info.debugDumpDirectory.empty() ||
        workingRaw.mosaic.empty() || workingRaw.mosaic.type() != CV_32FC1) {
        return false;
    }
    try {
        cv::Mat preview16;
        workingRaw.mosaic.convertTo(preview16, CV_16UC1, 65535.0);
        const std::string path = joinPath(workingRaw.info.debugDumpDirectory, "normalized_mosaic_preview.pgm");
        const bool written = cv::imwrite(path, preview16);
        RAW_DOMAIN_LOGI("RAW debug normalized mosaic dump %s: %s", written ? "written" : "failed", path.c_str());
        return written;
    } catch (const cv::Exception& error) {
        RAW_DOMAIN_LOGE("RAW normalized mosaic dump failed: %s", error.what());
        return false;
    }
}
