#pragma once

#include <algorithm>
#include <cmath>

namespace bncam::tone {

struct LocalToneInput {
    float sceneMidtoneTarget = 0.155f;
    float shadowPressure = 0.0f;
    float dynamicRangePressure = 0.0f;
    float recoverableHighlightPressure = 0.0f;
    float sensorClipPressure = 0.0f;
    float noisePressure = 0.0f;
    bool lowLightScene = false;
};

struct LocalTonePlan {
    bool enabled = true;
    float strength = 0.08f;
    float sceneKey = 0.155f;
    float maxLiftEv = 0.25f;
    float maxCompressEv = 0.15f;
};

struct LocalToneExposureStackGuard {
    LocalTonePlan plan{};
    bool active = false;
    float globalLiftEv = 0.0f;
    float attenuation = 0.0f;
};

inline float localToneSmoothstep(float edge0, float edge1, float x) noexcept {
    if (!(edge1 > edge0)) return x >= edge1 ? 1.0f : 0.0f;
    const float t = std::clamp((x - edge0) / (edge1 - edge0), 0.0f, 1.0f);
    return t * t * (3.0f - 2.0f * t);
}

/**
 * Prevent the automatic local-tone stage from becoming a second exposure lift.
 *
 * The scene exposure governor may legitimately add substantial RAW render gain. Once it does,
 * automatic positive LTM authority must fall away; otherwise dark scenes receive a global lift
 * followed by another broad local lift, flattening blacks/microcontrast and creating a haze-like
 * presentation. Highlight compression is intentionally retained because it solves a different
 * problem and does not act as fill light. Profile Local Tone Bias is applied after this guard so
 * explicit user intent remains authoritative.
 */
inline LocalToneExposureStackGuard guardLocalToneAgainstGlobalExposure(
        const LocalTonePlan& input,
        float automaticExposureGain) noexcept {
    LocalToneExposureStackGuard out{};
    out.plan = input;

    const float safeGain = std::max(1.0f,
            std::isfinite(automaticExposureGain) ? automaticExposureGain : 1.0f);
    out.globalLiftEv = std::log2(safeGain);
    out.attenuation = localToneSmoothstep(0.35f, 1.50f, out.globalLiftEv);
    out.active = out.attenuation > 1.0e-4f;

    if (!out.active) return out;

    // Preserve enough local authority for genuinely difficult scenes, but aggressively suppress
    // positive fill-light once the global renderer has already lifted the scene by ~1.5 EV+.
    out.plan.strength *= 1.0f - 0.45f * out.attenuation;
    out.plan.maxLiftEv *= 1.0f - 0.68f * out.attenuation;
    out.plan.strength = std::clamp(out.plan.strength, 0.02f, 0.42f);
    out.plan.maxLiftEv = std::clamp(out.plan.maxLiftEv, 0.04f, 0.62f);
    out.plan.enabled = out.plan.strength >= 0.02f;
    return out;
}

inline float guardAutomaticLowerMidLiftAgainstGlobalExposure(
        float requestedLift,
        float exposureStackAttenuation) noexcept {
    const float lift = std::max(0.0f, std::isfinite(requestedLift) ? requestedLift : 0.0f);
    const float exposureAuthority = std::clamp(
            std::isfinite(exposureStackAttenuation) ? exposureStackAttenuation : 0.0f,
            0.0f, 1.0f);
    // Retain a small 20% floor so highlight-separated scenes can keep gentle lower-mid shaping,
    // but remove the broad automatic fill-light behavior when global gain already did the work.
    return lift * (1.0f - 0.80f * exposureAuthority);
}

/**
 * Scalar policy for the Vulkan local-tone stage.
 *
 * LTM is deliberately conservative in ordinary scenes and gains authority only when the linear
 * histogram demonstrates simultaneous shadow/highlight pressure. Noise pressure attenuates local
 * shadow lift in low light so LTM does not expose residual sensor noise merely to make the image
 * brighter. The full spatial operation remains GPU-resident.
 */
inline LocalTonePlan resolveLocalTonePlan(const LocalToneInput& input) noexcept {
    LocalTonePlan out{};
    const float shadow = std::clamp(input.shadowPressure, 0.0f, 1.0f);
    const float dynamicRange = std::clamp(input.dynamicRangePressure, 0.0f, 1.0f);
    const float highlight = std::clamp(
            std::max(input.recoverableHighlightPressure, 0.72f * input.sensorClipPressure),
            0.0f, 1.0f);
    const float noise = std::clamp(input.noisePressure, 0.0f, 1.0f);

    const float highDrAuthority = dynamicRange * (0.65f + 0.35f * highlight);
    // LTM is a selective rescue stage, not a full-frame fill-light. The former coefficients
    // routinely produced ~0.31 strength and ~0.68 EV lift in ordinary backlit daylight, causing
    // >80% of pixels to move and visibly washing out local contrast.
    float strength = 0.06f + 0.28f * highDrAuthority + 0.05f * shadow * highlight;
    if (input.lowLightScene) {
        strength *= 1.0f - 0.50f * noise;
    }
    out.strength = std::clamp(strength, 0.04f, 0.42f);
    out.enabled = out.strength >= 0.04f;
    out.sceneKey = std::clamp(input.sceneMidtoneTarget, 0.130f, 0.170f);
    float liftEv = 0.16f + 0.38f * dynamicRange + 0.06f * shadow;
    if (input.lowLightScene) {
        liftEv *= (1.0f - 0.72f * noise);
    }
    out.maxLiftEv = std::clamp(liftEv, 0.05f, 0.62f);
    out.maxCompressEv = std::clamp(
            0.10f + 0.24f * highlight + 0.06f * dynamicRange,
            0.10f, 0.36f);
    return out;
}

} // namespace bncam::tone
