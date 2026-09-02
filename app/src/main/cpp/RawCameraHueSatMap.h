#pragma once

#include <algorithm>
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <string>
#include <vector>

namespace bncam::color {

enum class RawCameraHueSatMapSource : std::uint8_t {
    NONE = 0,
    APP_CALIBRATED_PROFILE = 1,
    DNG_PROFILE_DATA_1 = 2,
    DNG_PROFILE_DATA_2 = 3,
    DNG_PROFILE_DATA_3 = 4,
    DNG_INTERPOLATED_PROFILE = 5
};

inline const char* rawCameraHueSatMapSourceName(RawCameraHueSatMapSource source) noexcept {
    switch (source) {
        case RawCameraHueSatMapSource::APP_CALIBRATED_PROFILE:
            return "APP_CALIBRATED_PROFILE";
        case RawCameraHueSatMapSource::DNG_PROFILE_DATA_1:
            return "DNG_PROFILE_DATA_1";
        case RawCameraHueSatMapSource::DNG_PROFILE_DATA_2:
            return "DNG_PROFILE_DATA_2";
        case RawCameraHueSatMapSource::DNG_PROFILE_DATA_3:
            return "DNG_PROFILE_DATA_3";
        case RawCameraHueSatMapSource::DNG_INTERPOLATED_PROFILE:
            return "DNG_INTERPOLATED_PROFILE";
        case RawCameraHueSatMapSource::NONE:
        default:
            return "NONE";
    }
}

enum class RawCameraHueSatMapEncoding : std::uint8_t {
    LINEAR = 0,
    SRGB = 1
};

enum class RawCameraHueSatMapDynamicRange : std::uint8_t {
    SDR = 0,
    HDR = 1
};

/**
 * DNG camera HueSatMap processing is defined in the PCS path:
 * camera -> XYZ D50 -> linear ProPhoto/RIMM -> HSV -> HueSatMap -> RIMM -> XYZ D50.
 *
 * This is intentionally not scene-adaptive. It is a fixed standards domain for calibrated
 * camera characterization. Scene/illuminant adaptivity belongs to profile interpolation.
 */
enum class RawCameraHueSatMapColorDomain : std::uint8_t {
    DNG_RIMM_LINEAR_FROM_XYZ_D50 = 0
};

struct RawCameraHueSatMapEntry {
    float hueShiftDegrees = 0.0f;
    float saturationScale = 1.0f;
    float valueScale = 1.0f;
};

struct RawCameraHueSatMap {
    int hueDivisions = 0;
    int saturationDivisions = 0;
    int valueDivisions = 0;
    std::vector<RawCameraHueSatMapEntry> entries;
    RawCameraHueSatMapSource source = RawCameraHueSatMapSource::NONE;
    std::string sourceId;
    bool trustedCalibration = false;
    RawCameraHueSatMapEncoding encoding = RawCameraHueSatMapEncoding::LINEAR;
    RawCameraHueSatMapDynamicRange dynamicRange = RawCameraHueSatMapDynamicRange::SDR;
    RawCameraHueSatMapColorDomain colorDomain =
            RawCameraHueSatMapColorDomain::DNG_RIMM_LINEAR_FROM_XYZ_D50;

    static constexpr std::size_t kMaxEntries = 1u << 20;

    bool dimensionsValid() const noexcept {
        // DNG 1.7.1 ProfileHueSatMapDims requirements:
        // HueDivisions >= 1, SaturationDivisions >= 2, ValueDivisions >= 1.
        return hueDivisions >= 1 && saturationDivisions >= 2 && valueDivisions >= 1;
    }

    std::size_t expectedEntryCount() const noexcept {
        if (!dimensionsValid()) return 0u;
        const std::size_t h = static_cast<std::size_t>(hueDivisions);
        const std::size_t s = static_cast<std::size_t>(saturationDivisions);
        const std::size_t v = static_cast<std::size_t>(valueDivisions);
        if (h > kMaxEntries || s > kMaxEntries || v > kMaxEntries) return 0u;
        if (h > kMaxEntries / s) return 0u;
        const std::size_t hs = h * s;
        if (hs > kMaxEntries / v) return 0u;
        const std::size_t count = hs * v;
        return count <= kMaxEntries ? count : 0u;
    }

    std::size_t index(int hueIndex, int saturationIndex, int valueIndex) const noexcept {
        const int h = std::clamp(hueIndex, 0, std::max(0, hueDivisions - 1));
        const int s = std::clamp(saturationIndex, 0, std::max(0, saturationDivisions - 1));
        const int v = std::clamp(valueIndex, 0, std::max(0, valueDivisions - 1));
        return (static_cast<std::size_t>(v) * static_cast<std::size_t>(hueDivisions) +
                static_cast<std::size_t>(h)) * static_cast<std::size_t>(saturationDivisions) +
                static_cast<std::size_t>(s);
    }

    bool zeroSaturationInvariantValid() const noexcept {
        if (expectedEntryCount() == 0u || entries.size() != expectedEntryCount()) return false;
        for (int v = 0; v < valueDivisions; ++v) {
            for (int h = 0; h < hueDivisions; ++h) {
                const float scale = entries[index(h, 0, v)].valueScale;
                if (!std::isfinite(scale) || std::abs(scale - 1.0f) > 1.0e-6f) return false;
            }
        }
        return true;
    }

    bool validData() const noexcept {
        const std::size_t expected = expectedEntryCount();
        if (expected == 0u || entries.size() != expected) return false;
        for (const auto& entry : entries) {
            if (!std::isfinite(entry.hueShiftDegrees) ||
                !std::isfinite(entry.saturationScale) ||
                !std::isfinite(entry.valueScale)) {
                return false;
            }
            if (entry.saturationScale < 0.0f || entry.valueScale < 0.0f) return false;
        }
        return zeroSaturationInvariantValid();
    }

    bool productEligible() const noexcept {
        return trustedCalibration && source != RawCameraHueSatMapSource::NONE && validData();
    }

    bool directReferenceApplicationSupported() const noexcept {
        // For DNG HDR profiles with ValueDivisions > 1 the ProfileDynamicRange encoding
        // function must be applied before/after the HSV table. This small reference contract
        // deliberately refuses to invent that function. ValueDivisions == 1 needs no V encoding.
        return dynamicRange == RawCameraHueSatMapDynamicRange::SDR || valueDivisions == 1;
    }
};

struct RawCameraHueSatMapSample {
    float hueShiftDegrees = 0.0f;
    float saturationScale = 1.0f;
    float valueScale = 1.0f;
    bool applied = false;
    const char* reason = "IDENTITY_NO_TRUSTED_CALIBRATION";
};

inline float rawCameraHueWrapDegrees(float degrees) noexcept {
    if (!std::isfinite(degrees)) return 0.0f;
    float wrapped = std::fmod(degrees, 360.0f);
    if (wrapped < 0.0f) wrapped += 360.0f;
    return wrapped >= 360.0f ? 0.0f : wrapped;
}

inline RawCameraHueSatMapEntry rawCameraHueSatMapLerp(
        const RawCameraHueSatMapEntry& a,
        const RawCameraHueSatMapEntry& b,
        float t) noexcept {
    // DNG tri-linear interpolation returns a scalar hue-shift in degrees. The input hue
    // coordinate wraps; the stored hue-shift itself is not an angular coordinate to be
    // shortest-path interpolated.
    const float u = std::clamp(t, 0.0f, 1.0f);
    return {
        a.hueShiftDegrees + (b.hueShiftDegrees - a.hueShiftDegrees) * u,
        a.saturationScale + (b.saturationScale - a.saturationScale) * u,
        a.valueScale + (b.valueScale - a.valueScale) * u
    };
}

inline float rawCameraSrgbEncodeUnit(float linear) noexcept {
    const float x = std::clamp(std::isfinite(linear) ? linear : 0.0f, 0.0f, 1.0f);
    return x <= 0.0031308f
            ? 12.92f * x
            : 1.055f * std::pow(x, 1.0f / 2.4f) - 0.055f;
}

inline float rawCameraSrgbDecodeUnit(float encoded) noexcept {
    const float x = std::clamp(std::isfinite(encoded) ? encoded : 0.0f, 0.0f, 1.0f);
    return x <= 0.04045f
            ? x / 12.92f
            : std::pow((x + 0.055f) / 1.055f, 2.4f);
}

inline float rawCameraHueSatMapLookupValue(
        const RawCameraHueSatMap& map,
        float linearValue) noexcept {
    const float value = std::clamp(std::isfinite(linearValue) ? linearValue : 0.0f, 0.0f, 1.0f);
    if (map.valueDivisions <= 1) return 0.0f;
    return map.encoding == RawCameraHueSatMapEncoding::SRGB
            ? rawCameraSrgbEncodeUnit(value)
            : value;
}

inline RawCameraHueSatMapSample sampleRawCameraHueSatMap(
        const RawCameraHueSatMap& map,
        float hueDegrees,
        float saturation,
        float linearValue) noexcept {
    RawCameraHueSatMapSample out{};
    if (!map.validData()) {
        out.reason = "IDENTITY_INVALID_HUESATMAP_DATA";
        return out;
    }
    if (!map.trustedCalibration || map.source == RawCameraHueSatMapSource::NONE) {
        out.reason = "IDENTITY_NO_TRUSTED_CALIBRATION";
        return out;
    }
    if (!map.directReferenceApplicationSupported()) {
        out.reason = "IDENTITY_HDR_PROFILE_DYNAMIC_RANGE_ENCODING_REQUIRED";
        return out;
    }
    if (!std::isfinite(hueDegrees) || !std::isfinite(saturation) || !std::isfinite(linearValue)) {
        out.reason = "IDENTITY_NONFINITE_INPUT";
        return out;
    }

    const float hueUnit = rawCameraHueWrapDegrees(hueDegrees) / 360.0f;
    const float hueCoordinate = hueUnit * static_cast<float>(map.hueDivisions);
    const int h0 = static_cast<int>(std::floor(hueCoordinate)) % map.hueDivisions;
    const int h1 = (h0 + 1) % map.hueDivisions;
    const float hf = hueCoordinate - std::floor(hueCoordinate);

    const float sat = std::clamp(saturation, 0.0f, 1.0f);
    const float lookupValue = rawCameraHueSatMapLookupValue(map, linearValue);
    const float satCoordinate = sat * static_cast<float>(map.saturationDivisions - 1);
    const float valCoordinate = map.valueDivisions > 1
            ? lookupValue * static_cast<float>(map.valueDivisions - 1)
            : 0.0f;

    const int s0 = static_cast<int>(std::floor(satCoordinate));
    const int s1 = std::min(s0 + 1, map.saturationDivisions - 1);
    const int v0 = static_cast<int>(std::floor(valCoordinate));
    const int v1 = std::min(v0 + 1, map.valueDivisions - 1);
    const float sf = satCoordinate - static_cast<float>(s0);
    const float vf = valCoordinate - static_cast<float>(v0);

    auto at = [&](int h, int s, int v) -> const RawCameraHueSatMapEntry& {
        return map.entries[map.index(h, s, v)];
    };

    const auto c000 = rawCameraHueSatMapLerp(at(h0, s0, v0), at(h1, s0, v0), hf);
    const auto c010 = rawCameraHueSatMapLerp(at(h0, s1, v0), at(h1, s1, v0), hf);
    const auto c001 = rawCameraHueSatMapLerp(at(h0, s0, v1), at(h1, s0, v1), hf);
    const auto c011 = rawCameraHueSatMapLerp(at(h0, s1, v1), at(h1, s1, v1), hf);
    const auto c00 = rawCameraHueSatMapLerp(c000, c010, sf);
    const auto c01 = rawCameraHueSatMapLerp(c001, c011, sf);
    const auto result = rawCameraHueSatMapLerp(c00, c01, vf);

    out.hueShiftDegrees = result.hueShiftDegrees;
    out.saturationScale = std::max(0.0f, result.saturationScale);
    out.valueScale = std::max(0.0f, result.valueScale);
    out.applied = true;
    out.reason = "TRILINEAR_DNG_HUESATMAP";
    return out;
}

struct RawCameraHueSatValue {
    float hueDegrees = 0.0f;
    float saturation = 0.0f;
    float value = 0.0f; // linear RIMM HSV value on input/output
};

inline RawCameraHueSatValue applyRawCameraHueSatMap(
        const RawCameraHueSatMap& map,
        RawCameraHueSatValue hsv,
        RawCameraHueSatMapSample* sampleOut = nullptr) noexcept {
    const RawCameraHueSatMapSample sample = sampleRawCameraHueSatMap(
            map, hsv.hueDegrees, hsv.saturation, hsv.value);
    if (sampleOut != nullptr) *sampleOut = sample;
    if (!sample.applied) return hsv;

    hsv.hueDegrees = rawCameraHueWrapDegrees(hsv.hueDegrees + sample.hueShiftDegrees);
    hsv.saturation = std::clamp(hsv.saturation * sample.saturationScale, 0.0f, 1.0f);

    const bool encodedValueDomain = map.valueDivisions > 1 &&
            map.encoding == RawCameraHueSatMapEncoding::SRGB;
    float valueDomain = encodedValueDomain
            ? rawCameraSrgbEncodeUnit(hsv.value)
            : std::max(0.0f, hsv.value);
    valueDomain *= sample.valueScale;
    if (map.dynamicRange == RawCameraHueSatMapDynamicRange::SDR) {
        valueDomain = std::min(valueDomain, 1.0f);
    }
    hsv.value = encodedValueDomain
            ? rawCameraSrgbDecodeUnit(std::min(valueDomain, 1.0f))
            : valueDomain;
    return hsv;
}

} // namespace bncam::color
