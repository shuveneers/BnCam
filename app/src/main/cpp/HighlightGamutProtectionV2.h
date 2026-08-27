#pragma once

#include <algorithm>
#include <array>
#include <cmath>
#include <cstddef>
#include <cstdint>

namespace bncam::highlight {

constexpr float kSensorClipThreshold = 0.985f;
// Phase 9 / Delta 0061: clipping-aware highlight color is confidence driven, not a
// post-WB hue guess. Below the start point the sensor ratios are trusted exactly.
// Near the normalized sensor ceiling confidence falls continuously; >=zero is treated
// as no reliable color-ratio information. The hard clip threshold remains telemetry only.
constexpr float kColorConfidenceStart = 0.960f;
constexpr float kColorConfidenceZero = 0.995f;
constexpr float kCcmNegativeTolerance = 1.0e-6f;

struct Rgb {
    float r = 0.0f;
    float g = 0.0f;
    float b = 0.0f;

    float& operator[](std::size_t i) noexcept {
        return i == 0u ? r : (i == 1u ? g : b);
    }
    const float& operator[](std::size_t i) const noexcept {
        return i == 0u ? r : (i == 1u ? g : b);
    }
};

struct SensorClipEvidence {
    std::uint8_t mask = 0u;
    int clippedChannels = 0;
    bool any = false;
    bool all = false;
};


struct SensorColorConfidence {
    float confidence = 1.0f;
    float peak = 0.0f;
    bool reduced = false;
    bool zero = false;
};

struct ClippingAwareHighlightColorResult {
    Rgb rgb{};
    float confidence = 1.0f;
    float preservedLuma = 0.0f;
    bool applied = false;
};


struct GamutProtectionResult {
    Rgb rgb{};
    bool excursion = false;
    bool applied = false;
    float chromaScale = 1.0f;
    float preservedLuma = 0.0f;
};

inline bool finite(float v) noexcept {
    return std::isfinite(v);
}

inline bool finiteRgb(const Rgb& v) noexcept {
    return finite(v.r) && finite(v.g) && finite(v.b);
}

inline float luma(const Rgb& v) noexcept {
    return 0.2126f * v.r + 0.7152f * v.g + 0.0722f * v.b;
}

inline float smoothstep(float edge0, float edge1, float value) noexcept {
    if (!(edge1 > edge0)) return value >= edge1 ? 1.0f : 0.0f;
    const float u = std::clamp((value - edge0) / (edge1 - edge0), 0.0f, 1.0f);
    return u * u * (3.0f - 2.0f * u);
}

inline SensorClipEvidence classifySensorClip(const Rgb& raw) noexcept {
    SensorClipEvidence evidence{};
    if (!finiteRgb(raw)) return evidence;
    for (std::size_t c = 0u; c < 3u; ++c) {
        if (raw[c] >= kSensorClipThreshold) {
            evidence.mask = static_cast<std::uint8_t>(evidence.mask | (1u << c));
            ++evidence.clippedChannels;
        }
    }
    evidence.any = evidence.clippedChannels > 0;
    evidence.all = evidence.clippedChannels == 3;
    return evidence;
}

inline SensorColorConfidence resolveSensorColorConfidence(const Rgb& raw) noexcept {
    SensorColorConfidence out{};
    if (!finiteRgb(raw)) {
        out.confidence = 0.0f;
        out.reduced = true;
        out.zero = true;
        return out;
    }
    out.peak = std::max({raw.r, raw.g, raw.b});
    const SensorClipEvidence hardClip = classifySensorClip(raw);
    const float pressure = smoothstep(kColorConfidenceStart, kColorConfidenceZero, out.peak);
    out.confidence = hardClip.all ? 0.0f : std::clamp(1.0f - pressure, 0.0f, 1.0f);
    out.reduced = out.confidence < 1.0f - 1.0e-6f;
    out.zero = out.confidence <= 1.0e-6f;
    return out;
}

// Apply only after normal WB + camera color transform, while values are still linear.
// Mixing toward [Y,Y,Y] preserves BT.709 linear luminance exactly because the luma
// coefficients sum to one. Values above 1.0 remain scene-linear headroom for Phase 10.
inline ClippingAwareHighlightColorResult applyClippingAwareHighlightColor(
        const Rgb& postCcmLinearRgb,
        const SensorColorConfidence& evidence) noexcept {
    ClippingAwareHighlightColorResult out{};
    out.rgb = postCcmLinearRgb;
    out.confidence = std::clamp(
            std::isfinite(evidence.confidence) ? evidence.confidence : 0.0f, 0.0f, 1.0f);
    if (!finiteRgb(postCcmLinearRgb)) {
        out.rgb = {};
        out.confidence = 0.0f;
        out.applied = true;
        return out;
    }
    out.preservedLuma = luma(postCcmLinearRgb);
    if (out.confidence >= 1.0f - 1.0e-6f) return out;
    const Rgb neutral{out.preservedLuma, out.preservedLuma, out.preservedLuma};
    out.rgb = {
        neutral.r + (postCcmLinearRgb.r - neutral.r) * out.confidence,
        neutral.g + (postCcmLinearRgb.g - neutral.g) * out.confidence,
        neutral.b + (postCcmLinearRgb.b - neutral.b) * out.confidence
    };
    out.applied = finiteRgb(out.rgb) &&
            (std::abs(out.rgb.r - postCcmLinearRgb.r) > 1.0e-7f ||
             std::abs(out.rgb.g - postCcmLinearRgb.g) > 1.0e-7f ||
             std::abs(out.rgb.b - postCcmLinearRgb.b) > 1.0e-7f);
    return out;
}

inline bool wbAboveUnityWithoutSensorClip(const Rgb& raw, const Rgb& wb) noexcept {
    if (classifySensorClip(raw).any || !finiteRgb(wb)) return false;
    return wb.r > 1.0f || wb.g > 1.0f || wb.b > 1.0f;
}

inline Rgb multiplyMatrix(const std::array<float, 9>& m, const Rgb& v) noexcept {
    return {
        m[0] * v.r + m[1] * v.g + m[2] * v.b,
        m[3] * v.r + m[4] * v.g + m[5] * v.b,
        m[6] * v.r + m[7] * v.g + m[8] * v.b
    };
}

inline bool heuristicMagentaHighlightRisk(const Rgb& rgb) noexcept {
    if (!finiteRgb(rgb)) return false;
    const float maximum = std::max({rgb.r, rgb.g, rgb.b});
    if (maximum <= 0.35f) return false;
    const float rbFloor = std::min(rgb.r, rgb.b);
    const float greenDeficit = rbFloor - rgb.g;
    const float relativeDeficit = greenDeficit / std::max(maximum, 1.0e-5f);
    const float y = luma(rgb);
    return y > 0.30f && rbFloor > 0.62f * maximum && relativeDeficit > 0.085f;
}


// Scene-linear sRGB has no meaningful upper unit bound before tone mapping; values >1 are HDR
// headroom. The relevant pre-tone gamut failure is a negative CCM component. Compress chroma
// toward the neutral axis at constant linear-sRGB luminance only when needed to reach RGB>=0.
inline GamutProtectionResult protectSignedCcmLowerGamut(const Rgb& signedRgb) noexcept {
    GamutProtectionResult result{};
    result.rgb = signedRgb;
    if (!finiteRgb(signedRgb)) {
        result.rgb = {};
        result.excursion = true;
        result.applied = true;
        result.chromaScale = 0.0f;
        return result;
    }
    const float minimum = std::min({signedRgb.r, signedRgb.g, signedRgb.b});
    result.excursion = minimum < -kCcmNegativeTolerance;
    result.preservedLuma = luma(signedRgb);
    if (!result.excursion) return result;

    const float y = result.preservedLuma;
    if (!(y > 1.0e-7f)) {
        // No positive luminance anchor exists for a hue-preserving in-gamut solution.
        result.rgb = {};
        result.chromaScale = 0.0f;
        result.applied = true;
        return result;
    }
    const float denominator = y - minimum;
    const float scale = denominator > 1.0e-8f ? std::clamp(y / denominator, 0.0f, 1.0f) : 1.0f;
    result.chromaScale = scale;
    result.rgb = {
        y + (signedRgb.r - y) * scale,
        y + (signedRgb.g - y) * scale,
        y + (signedRgb.b - y) * scale
    };
    for (std::size_t c = 0u; c < 3u; ++c) {
        if (result.rgb[c] < 0.0f && result.rgb[c] > -2.0e-6f) result.rgb[c] = 0.0f;
    }
    result.applied = finiteRgb(result.rgb);
    if (!result.applied) {
        result.rgb = {};
        result.chromaScale = 0.0f;
        result.applied = true;
    }
    return result;
}

} // namespace bncam::highlight
