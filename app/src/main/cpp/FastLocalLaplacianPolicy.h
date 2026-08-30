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
    float strength = 0.30f;
    float sceneKey = 0.150f;
    float maxLiftEv = 0.55f;
    float maxCompressEv = 0.55f;
    float edgeStopEv = 0.62f;
    float refinement = 0.10f;
    // 0..1 physical noise pressure controlling only positive deep-shadow lift permission.
    float shadowLiftNoiseGuardPressure = 0.0f;
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

    // The FLLF field is the actual local exposure normalizer, not a cosmetic micro-contrast pass.
    // Device captures proved that the old 0.05..0.20 authority yielded only ~0.006..0.083 EV of
    // real correction: effectively disabled. Keep materially useful authority in mixed-DR scenes.
    // Do not reduce the complete field for high ISO: negative highlight compression is safe in
    // noise and must remain available. Noise limits only positive shadow lift below.
    out.strength = std::clamp(
            0.30f + 0.36f * dynamicRange + 0.18f * highlight +
                    0.08f * shadow * highlight,
            0.28f, 0.82f);

    out.sceneKey = std::clamp(input.sceneMidtoneTarget, 0.125f, 0.170f);

    float lift = 0.45f + 0.70f * dynamicRange + 0.25f * shadow;
    if (input.lowLightScene) {
        lift *= 1.0f - 0.55f * noise;
    } else {
        lift *= 1.0f - 0.20f * noise;
    }
    out.maxLiftEv = std::clamp(lift, 0.18f, 1.20f);
    // Negative local exposure must be driven by actual highlight evidence, not by generic
    // dynamic-range pressure. Device captures with a bright display in an otherwise dark room
    // produced dynamicRangePressure ~= 0.72 while recoverable highlight pressure stayed near
    // zero; coupling compression strongly to dynamicRange therefore turned the display into a
    // dark island. Dynamic range may add only a small amount of headroom here. Broad/real
    // highlight evidence remains the primary authority.
    out.maxCompressEv = std::clamp(
            0.34f + 0.60f * highlight + 0.18f * dynamicRange,
            0.28f, 0.85f);
    if (input.lowLightScene) {
        out.maxCompressEv = std::min(out.maxCompressEv, 0.72f);
    }

    // More noise asks for a larger log-luma edge before local exposure propagation is stopped.
    // This avoids interpreting fine sensor texture as a structural edge while still stopping
    // corrections across real high-contrast boundaries.
    out.edgeStopEv = std::clamp(0.56f + 0.20f * noise, 0.54f, 0.78f);
    out.refinement = std::clamp(0.10f + 0.10f * highDrAuthority, 0.08f, 0.22f);
    // Do not invent a sensor-specific luma threshold here. The normalized S/O model has
    // already been collapsed into physical noise pressure; the Vulkan stage converts that
    // pressure into a monotonic deep-shadow lift gate.
    out.shadowLiftNoiseGuardPressure = input.lowLightScene ? noise : 0.0f;
    out.enabled = out.strength >= 0.20f &&
            (dynamicRange > 0.015f || highlight > 0.08f || shadow > 0.28f);
    return out;
}

} // namespace bncam::tone
