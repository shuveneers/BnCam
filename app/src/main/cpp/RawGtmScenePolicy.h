#pragma once

#include <algorithm>
#include <cmath>

namespace bncam::tone {

struct RawGtmSceneInput {
    float p50 = 0.0f;
    float p75 = 0.0f;
    float indoorLowLightConfidence = 0.0f;
    float dynamicRangePressure = 0.0f;
    bool lowLightScene = false;
    bool outdoorSkyScene = false;
};

struct RawGtmScenePlan {
    bool active = false;
    float scenePlacementGain = 1.0f;
    float scenePlacementEv = 0.0f;
    float lowLightMoodPressure = 0.0f;
    bool highlightIsolatedLowEnd = true;
    const char* authority = "NEUTRAL";
};

inline float rawGtmSmoothstep(float edge0, float edge1, float value) noexcept {
    if (!(edge1 > edge0)) return value >= edge1 ? 1.0f : 0.0f;
    const float t = std::clamp((value - edge0) / (edge1 - edge0), 0.0f, 1.0f);
    return t * t * (3.0f - 2.0f * t);
}

/**
 * RAW GTM scene-placement policy.
 *
 * Sensor exposure owns acquisition brightness and Phase-5 spatial exposure owns physically gated
 * shadow recovery. GTM therefore does not add a second automatic scene-wide exposure decision.
 * In particular, a dark histogram is not evidence that the renderer should make the already-dark
 * RAW darker again: the supplied device traces showed this policy applying about -0.5 EV to frames
 * that were already under-placed, while global highlight darkening was explicitly false.
 *
 * GTM remains the global tone/contrast stage elsewhere in the pipeline. This policy only governs
 * its scene-placement multiplier, which is now neutral for automatic rendering. FLLF and AgX keep
 * their independent local/high-end responsibilities without a hidden low-light attenuation owner.
 */
inline RawGtmScenePlan resolveRawGtmScenePlan(const RawGtmSceneInput& input) noexcept {
    RawGtmScenePlan out{};

    // Consume and sanitize the inputs for deterministic diagnostics/ABI continuity, but do not
    // convert scene darkness into a negative global exposure. This preserves one acquisition/
    // exposure authority and prevents low-light mood heuristics from compounding underexposure.
    const float p50 = std::clamp(std::isfinite(input.p50) ? input.p50 : 0.0f, 0.0f, 1.5f);
    const float p75 = std::clamp(std::isfinite(input.p75) ? input.p75 : 0.0f, 0.0f, 1.5f);
    const float indoor = std::clamp(
            std::isfinite(input.indoorLowLightConfidence) ? input.indoorLowLightConfidence : 0.0f,
            0.0f, 1.0f);
    const float dynamicRange = std::clamp(
            std::isfinite(input.dynamicRangePressure) ? input.dynamicRangePressure : 0.0f,
            0.0f, 1.0f);
    (void)p50;
    (void)p75;
    (void)indoor;
    (void)dynamicRange;

    out.active = false;
    out.scenePlacementGain = 1.0f;
    out.scenePlacementEv = 0.0f;
    out.lowLightMoodPressure = 0.0f;
    out.highlightIsolatedLowEnd = true;
    out.authority = input.outdoorSkyScene ? "OUTDOOR_SKY_NEUTRAL" :
            (input.lowLightScene || indoor >= 0.42f ? "LOW_LIGHT_NEUTRAL" : "NON_LOW_LIGHT_NEUTRAL");
    return out;
}

} // namespace bncam::tone
