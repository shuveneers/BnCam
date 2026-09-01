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
    bool enabled = false;
    float strength = 0.0f;
    float sceneKey = 0.150f;
    float maxLiftEv = 0.0f;
    float maxCompressEv = 0.0f;
    float edgeStopEv = 0.62f;
    float refinement = 0.10f;
    float shadowLiftNoiseGuardPressure = 0.0f;
    std::uint32_t pyramidLevels = 6u;
    std::uint32_t baseDownsample = 2u;
};

/**
 * Phase 7 FLLF local-contrast policy.
 *
 * Phase 5 owns exposure and RAW GTM owns only bounded global scene placement. FLLF therefore
 * cannot normalize a shadow field toward middle grey. It is a local contrast/highlight owner:
 * broad bright zones may be compressed and only a very small, noise-gated positive correction
 * is allowed in mixed-DR scenes. A dark scene with no real bright-range evidence stays untouched.
 */
inline FastLocalLaplacianPlan resolveFastLocalLaplacianPlan(
        const FastLocalLaplacianInput& input) noexcept {
    FastLocalLaplacianPlan out{};
    const float shadow = std::clamp(input.shadowPressure, 0.0f, 1.0f);
    const float dynamicRange = std::clamp(input.dynamicRangePressure, 0.0f, 1.0f);
    const float recoverable = std::clamp(input.recoverableHighlightPressure, 0.0f, 1.0f);
    const float sensorClip = std::clamp(input.sensorClipPressure, 0.0f, 1.0f);
    const float highlight = std::clamp(std::max(recoverable, 0.72f * sensorClip), 0.0f, 1.0f);
    const float noise = std::clamp(input.noisePressure, 0.0f, 1.0f);
    const float brightRangeEvidence = std::clamp(
            std::max(highlight, 0.72f * dynamicRange), 0.0f, 1.0f);

    out.sceneKey = std::clamp(input.sceneMidtoneTarget, 0.125f, 0.170f);

    // Authority is conditional on actual mixed-DR/bright-range evidence. Shadow pressure by
    // itself is explicitly insufficient: that was the old route by which a dim room acquired
    // a broad local exposure field and lost its captured lighting intent.
    out.strength = std::clamp(
            0.16f + 0.28f * dynamicRange + 0.22f * highlight,
            0.16f, 0.58f);

    // Positive correction is intentionally tiny and vanishes in noisy low light. This keeps
    // FLLF in the local-contrast domain instead of becoming a second exposure owner.
    const float positivePermission = brightRangeEvidence * (1.0f - noise) *
            (input.lowLightScene ? 0.45f : 1.0f);
    out.maxLiftEv = std::clamp(0.14f * positivePermission, 0.0f, 0.14f);

    out.maxCompressEv = std::clamp(
            0.24f + 0.36f * highlight + 0.18f * dynamicRange,
            0.24f, input.lowLightScene ? 0.58f : 0.72f);
    out.edgeStopEv = std::clamp(0.54f + 0.18f * noise, 0.54f, 0.74f);
    out.refinement = std::clamp(0.08f + 0.08f * brightRangeEvidence, 0.08f, 0.16f);
    out.shadowLiftNoiseGuardPressure = noise;
    out.pyramidLevels = 6u;
    out.baseDownsample = 2u;
    out.enabled = brightRangeEvidence >= 0.10f && out.strength >= 0.16f;
    return out;
}

} // namespace bncam::tone
