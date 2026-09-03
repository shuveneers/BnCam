#pragma once

#include "RawCameraColorProfileRegistry.h"

#include <algorithm>
#include <cmath>
#include <cstddef>
#include <string>

namespace bncam::color {

struct RawCameraHueSatMapTelemetrySummary {
    bool available = false;
    int hueDivisions = 0;
    int saturationDivisions = 0;
    int valueDivisions = 0;
    int encoding = 0;
    int illuminantCount = 0;
    float interpolationWeight = 0.0f; // normalized weight of calibration/profile table 2
    double meanAbsHueShift = 0.0;
    double meanSaturationScale = 1.0;
    double meanValueScale = 1.0;
    double maxAbsHueShift = 0.0;
    std::string source = "NONE";
    std::string statisticDomain = "UNAVAILABLE";
};

inline std::string rawCameraHueSatMapProfileSourceName(int sourcePriority) {
    switch (sourcePriority) {
        case 300: return "OEM_DNGCREATOR";
        case 200: return "BNCAM_CALIBRATED_PROFILE";
        case 100: return "EXTERNAL_DNG_DCP_PROFILE";
        default:
            return sourcePriority > 0
                    ? "TRUSTED_PROFILE_PRIORITY_" + std::to_string(sourcePriority)
                    : "NONE";
    }
}

/**
 * Compact Phase-2 truth summary of the exact resolved calibration table.
 *
 * These statistics describe the weighted profile table that the Vulkan pass receives; they are
 * deliberately not scene/pixel averages. Pixel execution truth remains separately reported by
 * HueSatMapApplied/HueSatMapAppliedPixels. Keeping this calculation on compact profile metadata
 * avoids a full-frame readback and preserves the resident RAW path.
 */
inline RawCameraHueSatMapTelemetrySummary summarizeRawCameraHueSatMapProfile(
        const RawCameraNativeHueSatProfile* profile,
        float weightFirst,
        float weightSecond) {
    RawCameraHueSatMapTelemetrySummary out{};
    if (profile == nullptr || profile->profileId.empty() || profile->sourcePriority <= 0 ||
        profile->hueDivisions < 1 || profile->saturationDivisions < 2 ||
        profile->valueDivisions < 1 || (profile->encoding != 0 && profile->encoding != 1) ||
        profile->hueSatData1.empty()) {
        return out;
    }

    const std::size_t expectedEntries = static_cast<std::size_t>(profile->hueDivisions) *
            static_cast<std::size_t>(profile->saturationDivisions) *
            static_cast<std::size_t>(profile->valueDivisions);
    if (expectedEntries == 0u || expectedEntries > (1u << 20)) return out;
    const std::size_t floatCount = profile->hueSatData1.size();
    if (floatCount != expectedEntries * 3u) return out;

    float w1 = std::isfinite(weightFirst) ? std::clamp(weightFirst, 0.0f, 1.0f) : 1.0f;
    float w2 = std::isfinite(weightSecond) ? std::clamp(weightSecond, 0.0f, 1.0f) : 0.0f;
    const bool hasSecond = profile->dualHueSatMap() && profile->hueSatData2.size() == floatCount;
    if (!hasSecond) {
        w1 = 1.0f;
        w2 = 0.0f;
    } else {
        const float sum = w1 + w2;
        if (!(sum > 1.0e-8f)) {
            w1 = 1.0f;
            w2 = 0.0f;
        } else {
            w1 /= sum;
            w2 /= sum;
        }
    }

    const std::size_t entryCount = floatCount / 3u;
    double hueAbsSum = 0.0;
    double saturationSum = 0.0;
    double valueSum = 0.0;
    double maxAbsHue = 0.0;

    for (std::size_t entry = 0; entry < entryCount; ++entry) {
        const std::size_t base = entry * 3u;
        const float hue1 = profile->hueSatData1[base + 0u];
        const float sat1 = profile->hueSatData1[base + 1u];
        const float val1 = profile->hueSatData1[base + 2u];
        const float hue2 = hasSecond ? profile->hueSatData2[base + 0u] : hue1;
        const float sat2 = hasSecond ? profile->hueSatData2[base + 1u] : sat1;
        const float val2 = hasSecond ? profile->hueSatData2[base + 2u] : val1;

        const double hue = static_cast<double>(w1 * hue1 + w2 * hue2);
        const double saturation = static_cast<double>(w1 * sat1 + w2 * sat2);
        const double value = static_cast<double>(w1 * val1 + w2 * val2);
        if (!std::isfinite(hue) || !std::isfinite(saturation) || !std::isfinite(value)) {
            return RawCameraHueSatMapTelemetrySummary{};
        }
        const double absHue = std::abs(hue);
        hueAbsSum += absHue;
        saturationSum += saturation;
        valueSum += value;
        maxAbsHue = std::max(maxAbsHue, absHue);
    }

    if (entryCount == 0u) return out;
    const double inv = 1.0 / static_cast<double>(entryCount);
    out.available = true;
    out.hueDivisions = profile->hueDivisions;
    out.saturationDivisions = profile->saturationDivisions;
    out.valueDivisions = profile->valueDivisions;
    out.encoding = profile->encoding;
    out.illuminantCount = hasSecond ? 2 : 1;
    out.interpolationWeight = w2;
    out.meanAbsHueShift = hueAbsSum * inv;
    out.meanSaturationScale = saturationSum * inv;
    out.meanValueScale = valueSum * inv;
    out.maxAbsHueShift = maxAbsHue;
    out.source = rawCameraHueSatMapProfileSourceName(profile->sourcePriority);
    out.statisticDomain = hasSecond
            ? "RESOLVED_WEIGHTED_PROFILE_TABLE_DATA1_DATA2"
            : "RESOLVED_PROFILE_TABLE_DATA1";
    return out;
}

} // namespace bncam::color
