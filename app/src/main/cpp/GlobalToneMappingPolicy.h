#pragma once

#include <algorithm>
#include <cmath>

namespace bncam::tone {

struct GlobalToneMappingInput {
    float p90 = 0.40f;
    float p95 = 0.62f;
    float p99 = 0.90f;
    float nearWhiteFraction = 0.0f;
    float rawClipFraction = 0.0f;
    float sceneRangeEv = 0.0f;
    float globalExposureEv = 0.0f;
};

struct GlobalToneMappingPlan {
    bool enabled = false;
    float shoulderStart = 0.82f;
    float shoulderStrength = 0.65f;
    float highlightPressure = 0.0f;
    float p95CompressionEv = 0.0f;
    float p99CompressionEv = 0.0f;
};

inline float gtmSmoothstep(float edge0, float edge1, float x) noexcept {
    if (!(edge1 > edge0)) return x >= edge1 ? 1.0f : 0.0f;
    const float t = std::clamp((x - edge0) / (edge1 - edge0), 0.0f, 1.0f);
    return t * t * (3.0f - 2.0f * t);
}

inline float gtmMapLuma(float y, float shoulderStart, float shoulderStrength) noexcept {
    const float luma = std::max(0.0f, std::isfinite(y) ? y : 0.0f);
    if (luma <= shoulderStart) return luma;
    const float headroom = std::max(0.05f, 1.0f - shoulderStart);
    const float normalized = (luma - shoulderStart) / headroom;
    return shoulderStart + headroom * normalized /
            (1.0f + shoulderStrength * normalized);
}

/**
 * Phase 11F GTM owner. GTM owns only the upper scene-linear range after global exposure.
 * It never changes the DC/midtone placement below shoulderStart and never performs local tone.
 */
inline GlobalToneMappingPlan resolveGlobalToneMappingPlan(
        const GlobalToneMappingInput& input) noexcept {
    GlobalToneMappingPlan out{};
    const float gain = std::exp2(std::clamp(input.globalExposureEv, -0.50f, 1.25f));
    const float p90 = std::max(0.0f, input.p90 * gain);
    const float p95 = std::max(0.0f, input.p95 * gain);
    const float p99 = std::max(0.0f, input.p99 * gain);
    const float nearWhite = std::clamp(input.nearWhiteFraction, 0.0f, 1.0f);
    const float clip = std::clamp(input.rawClipFraction, 0.0f, 1.0f);
    const float rangePressure = gtmSmoothstep(2.0f, 5.0f, input.sceneRangeEv);
    out.highlightPressure = std::clamp(std::max(
            0.15f * gtmSmoothstep(0.48f, 0.82f, p90) +
            0.35f * gtmSmoothstep(0.62f, 0.92f, p95) +
            0.30f * gtmSmoothstep(0.78f, 1.18f, p99) +
            0.20f * gtmSmoothstep(0.02f, 0.14f, nearWhite),
            gtmSmoothstep(0.0005f, 0.020f, clip)), 0.0f, 1.0f);

    out.shoulderStart = std::clamp(
            0.86f - 0.16f * out.highlightPressure - 0.025f * rangePressure,
            0.67f, 0.86f);
    // Strength below 1 deliberately leaves some >1 scene-linear headroom for the final display
    // mapper. This prevents GTM and display mapping from becoming two full-strength shoulders.
    out.shoulderStrength = std::clamp(
            0.55f + 0.30f * out.highlightPressure + 0.08f * rangePressure,
            0.55f, 0.93f);
    out.enabled = out.highlightPressure > 0.025f || p99 > 0.86f;

    if (out.enabled) {
        const float mapped95 = gtmMapLuma(p95, out.shoulderStart, out.shoulderStrength);
        const float mapped99 = gtmMapLuma(p99, out.shoulderStart, out.shoulderStrength);
        out.p95CompressionEv = p95 > 1.0e-6f
                ? std::max(0.0f, std::log2(p95 / std::max(mapped95, 1.0e-6f))) : 0.0f;
        out.p99CompressionEv = p99 > 1.0e-6f
                ? std::max(0.0f, std::log2(p99 / std::max(mapped99, 1.0e-6f))) : 0.0f;
    }
    return out;
}

} // namespace bncam::tone
