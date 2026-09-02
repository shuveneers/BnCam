#pragma once

#include "RawCameraHueSatMap.h"
#include "RawCameraHueSatMapColorDomain.h"

#include <algorithm>
#include <array>
#include <cmath>

namespace bncam::color {

inline RawCameraHueSatValue rawCameraLinearRgbToHsv(const RawCameraVec3& rgb) noexcept {
    const float r = std::max(0.0f, rgb[0]);
    const float g = std::max(0.0f, rgb[1]);
    const float b = std::max(0.0f, rgb[2]);
    const float mx = std::max(r, std::max(g, b));
    const float mn = std::min(r, std::min(g, b));
    const float d = mx - mn;
    RawCameraHueSatValue out{};
    out.value = mx;
    out.saturation = mx > 1.0e-12f ? d / mx : 0.0f;
    if (d <= 1.0e-12f) {
        out.hueDegrees = 0.0f;
    } else if (mx == r) {
        out.hueDegrees = 60.0f * std::fmod((g - b) / d, 6.0f);
    } else if (mx == g) {
        out.hueDegrees = 60.0f * (((b - r) / d) + 2.0f);
    } else {
        out.hueDegrees = 60.0f * (((r - g) / d) + 4.0f);
    }
    out.hueDegrees = rawCameraHueWrapDegrees(out.hueDegrees);
    return out;
}

inline RawCameraVec3 rawCameraHsvToLinearRgb(const RawCameraHueSatValue& hsv) noexcept {
    const float h = rawCameraHueWrapDegrees(hsv.hueDegrees);
    const float s = std::clamp(hsv.saturation, 0.0f, 1.0f);
    const float v = std::max(0.0f, hsv.value);
    const float c = v * s;
    const float x = c * (1.0f - std::abs(std::fmod(h / 60.0f, 2.0f) - 1.0f));
    const float m = v - c;
    RawCameraVec3 rgb{};
    if (h < 60.0f) rgb = {c,x,0};
    else if (h < 120.0f) rgb = {x,c,0};
    else if (h < 180.0f) rgb = {0,c,x};
    else if (h < 240.0f) rgb = {0,x,c};
    else if (h < 300.0f) rgb = {x,0,c};
    else rgb = {c,0,x};
    for (float& q : rgb) q += m;
    return rgb;
}

inline RawCameraHueSatMap rawCameraBlendHueSatMaps(
        const RawCameraHueSatMap& first,
        const RawCameraHueSatMap* second,
        float weightFirst) {
    if (second == nullptr || weightFirst >= 1.0f - 1.0e-6f) return first;
    if (!first.productEligible() || !second->productEligible() ||
        first.hueDivisions != second->hueDivisions ||
        first.saturationDivisions != second->saturationDivisions ||
        first.valueDivisions != second->valueDivisions || first.encoding != second->encoding) {
        RawCameraHueSatMap invalid{};
        return invalid;
    }
    RawCameraHueSatMap out = first;
    out.source = RawCameraHueSatMapSource::DNG_INTERPOLATED_PROFILE;
    const float w1 = std::clamp(weightFirst, 0.0f, 1.0f);
    const float w2 = 1.0f - w1;
    for (std::size_t i = 0; i < out.entries.size(); ++i) {
        out.entries[i].hueShiftDegrees = w1*first.entries[i].hueShiftDegrees + w2*second->entries[i].hueShiftDegrees;
        out.entries[i].saturationScale = w1*first.entries[i].saturationScale + w2*second->entries[i].saturationScale;
        out.entries[i].valueScale = w1*first.entries[i].valueScale + w2*second->entries[i].valueScale;
    }
    return out;
}

/**
 * Apply a trusted DNG HueSatMap to a paired linear-sRGB representation of DNG PCS.
 * BnCam can carry scene-linear headroom above 1.0 into AgX. DNG HSM lookup coordinates are
 * bounded to [0,1], so headroom is factored out before HSV lookup and restored afterwards.
 * This extends the calibrated chroma/value ratio to highlights without clipping scene headroom.
 */
inline RawCameraVec3 rawCameraApplyCalibratedHueSatMapLinearSrgb(
        const RawCameraHueSatMap& map,
        const RawCameraVec3& linearSrgb,
        bool* applied = nullptr) noexcept {
    if (applied) *applied = false;
    if (!map.productEligible() || !map.directReferenceApplicationSupported()) return linearSrgb;
    // Linear-sRGB is only a transport representation of the paired DNG PCS. Do not clamp
    // negative sRGB components before converting into the much wider RIMM gamut: a valid
    // in-gamut RIMM colour can legitimately be outside sRGB. Clamp only after the PCS->RIMM
    // bridge where the HSM domain itself requires non-negative RGB/HSV coordinates.
    RawCameraVec3 rimm = rawCameraLinearSrgbToLinearRimm(linearSrgb);
    for (float& q : rimm) q = std::max(0.0f, q);
    const float headroom = std::max(1.0f, std::max(rimm[0], std::max(rimm[1], rimm[2])));
    for (float& q : rimm) q /= headroom;
    auto hsv = rawCameraLinearRgbToHsv(rimm);
    RawCameraHueSatMapSample sample{};
    hsv = applyRawCameraHueSatMap(map, hsv, &sample);
    if (!sample.applied) return linearSrgb;
    RawCameraVec3 corrected = rawCameraHsvToLinearRgb(hsv);
    for (float& q : corrected) q *= headroom;
    if (applied) *applied = true;
    return rawCameraLinearRimmToLinearSrgb(corrected);
}

} // namespace bncam::color
