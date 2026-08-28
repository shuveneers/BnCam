#pragma once

#include <algorithm>
#include <cmath>
#include <cstdint>

namespace bncam::tone {

struct FastLocalLaplacianInput {
    float sceneMidtoneTarget = 0.150f;
    float shadowPressure = 0.0f;
    float dynamicRangePressure = 0.0f;
    float recoverableHighlightPressure = 0.0f;
    float sensorClipPressure = 0.0f;
    float noisePressure = 0.0f;
    bool lowLightScene = false;
};

struct FastLocalLaplacianPlan {
    bool enabled = true;
    float strength = 0.08f;
    float sceneKey = 0.150f;
    float maxLiftEv = 0.18f;
    float maxCompressEv = 0.20f;
    float edgeStopEv = 0.62f;
    float refinement = 0.10f;
    std::uint32_t pyramidLevels = 6u;   // level 0 is half-resolution.
    std::uint32_t baseDownsample = 2u;
};

/**
 * Phase-10 scalar FLLF policy.
 *
 * This policy owns only local spatial adaptation. It deliberately does not contain a display
 * shoulder, toe, saturation, gamut mapping, or a global contrast curve. Positive local exposure
 * is strongly noise-limited; negative local exposure remains available for broad bright regions.
 */
inline FastLocalLaplacianPlan resolveFastLocalLaplacianPlan(
        const FastLocalLaplacianInput& input) noexcept {
    FastLocalLaplacianPlan out{};
    const float shadow = std::clamp(input.shadowPressure, 0.0f, 1.0f);
    const float dynamicRange = std::clamp(input.dynamicRangePressure, 0.0f, 1.0f);
    const float highlight = std::clamp(
            std::max(input.recoverableHighlightPressure, 0.72f * input.sensorClipPressure),
            0.0f, 1.0f);
    const float noise = std::clamp(input.noisePressure, 0.0f, 1.0f);
    const float highDrAuthority = std::clamp(
            dynamicRange * (0.62f + 0.38f * highlight), 0.0f, 1.0f);

    // Ordinary scenes receive only weak adaptation. High-DR scenes can use materially more local
    // authority without turning FLLF into a second global tone mapper.
    out.strength = std::clamp(
            0.055f + 0.30f * highDrAuthority + 0.045f * shadow * highlight,
            0.045f, 0.40f);
    if (input.lowLightScene) {
        out.strength *= 1.0f - 0.38f * noise;
    }

    out.sceneKey = std::clamp(input.sceneMidtoneTarget, 0.125f, 0.170f);

    float lift = 0.10f + 0.34f * dynamicRange + 0.05f * shadow;
    if (input.lowLightScene) {
        lift *= 1.0f - 0.82f * noise;
    } else {
        lift *= 1.0f - 0.30f * noise;
    }
    out.maxLiftEv = std::clamp(lift, 0.035f, 0.46f);
    out.maxCompressEv = std::clamp(
            0.12f + 0.34f * highlight + 0.10f * dynamicRange,
            0.10f, 0.56f);

    // More noise asks for a larger log-luma edge before local exposure propagation is stopped.
    // This avoids interpreting fine sensor texture as a structural edge while still stopping
    // corrections across real high-contrast boundaries.
    out.edgeStopEv = std::clamp(0.56f + 0.20f * noise, 0.54f, 0.78f);
    out.refinement = std::clamp(0.08f + 0.08f * highDrAuthority, 0.06f, 0.16f);
    out.enabled = out.strength >= 0.04f &&
            (dynamicRange > 0.025f || highlight > 0.10f || shadow > 0.32f);
    return out;
}

} // namespace bncam::tone
