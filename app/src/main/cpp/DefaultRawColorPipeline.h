#pragma once
#include "HighlightGamutProtectionV2.h"
#include "RawCameraAdaptiveColorAuthority.h"

namespace bncam::color {
// A complete Camera2 solution is indivisible. Static DNG fallback remains available
// when no exact pair exists; it must not rescale/blend an available exact pair.
inline RawAdaptiveColorAuthorityPlan exactCamera2Authority(const std::array<float,9>& matrix) {
    RawAdaptiveColorAuthorityPlan out{};
    out.ready = true;
    out.exactFrameWeight = 1.0f;
    out.effectivePostWbMatrix = matrix;
    out.status = "EXACT_CAMERA2_PAIR_UNMODIFIED";
    return out;
}

inline highlight::GamutProtectionResult preserveSignedSceneColor(const highlight::Rgb& rgb) {
    highlight::GamutProtectionResult out{};
    out.rgb = highlight::finiteRgb(rgb) ? rgb : highlight::Rgb{};
    out.applied = !highlight::finiteRgb(rgb);
    out.excursion = std::min({rgb.r,rgb.g,rgb.b}) < -highlight::kCcmNegativeTolerance;
    out.preservedLuma = highlight::luma(out.rgb);
    return out;
}

// Same display-entrance operation as mapSceneToKhronosInput in the resident shader.
inline highlight::Rgb mapSceneToKhronosInput(const highlight::Rgb& rgb) {
    if (!highlight::finiteRgb(rgb)) return {};
    const float minimum = std::min({rgb.r,rgb.g,rgb.b});
    if (minimum >= 0.0f) return rgb;
    const float y = highlight::luma(rgb);
    if (!(y > 1.e-7f)) return {};
    const float scale = std::clamp(y / std::max(y-minimum,1.e-8f),0.f,1.f);
    return {std::max(0.f,y+(rgb.r-y)*scale), std::max(0.f,y+(rgb.g-y)*scale),
            std::max(0.f,y+(rgb.b-y)*scale)};
}
} // namespace bncam::color
