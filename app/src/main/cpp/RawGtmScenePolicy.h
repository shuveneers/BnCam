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
 * Phase 7 RAW GTM scene-placement policy.
 *
 * Phase 5 already owns spatial exposure. GTM therefore never normalizes the RAW median to a
 * target and never adds automatic positive exposure. Its only automatic scene-placement
 * authority is a bounded sub-unity allocation for genuinely dark scenes, preventing the AgX
 * display transform from making a dim capture look like a normally lit scene.
 *
 * The output remains scene-linear and is consumed by FLLF before AgX. This is intentionally
 * not a display-range compression curve and cannot create the old GTM+AgX double compression.
 */
inline RawGtmScenePlan resolveRawGtmScenePlan(const RawGtmSceneInput& input) noexcept {
    RawGtmScenePlan out{};
    const float p50 = std::clamp(std::isfinite(input.p50) ? input.p50 : 0.0f, 0.0f, 1.5f);
    const float p75 = std::clamp(std::isfinite(input.p75) ? input.p75 : 0.0f, 0.0f, 1.5f);
    const float indoor = std::clamp(
            std::isfinite(input.indoorLowLightConfidence) ? input.indoorLowLightConfidence : 0.0f,
            0.0f, 1.0f);
    // Dynamic-range/highlight pressure is intentionally not an input to the low-end placement.
    // Highlights have their own FLLF/AgX owners; allowing the upper tail to weaken dark-scene
    // placement made the same room's blacks visibly float upward when a lamp/display entered frame.
    (void)input.dynamicRangePressure;

    if (input.outdoorSkyScene || (!input.lowLightScene && indoor < 0.42f)) {
        out.authority = input.outdoorSkyScene ? "OUTDOOR_SKY_NEUTRAL" : "NON_LOW_LIGHT_NEUTRAL";
        return out;
    }

    // Median and lower-midtone evidence must both support a dark-scene interpretation. A single
    // black object cannot darken a normally exposed frame. p50 controls the main pressure while
    // p75 releases authority quickly when useful scene content is already normally illuminated.
    const float medianDark = 1.0f - rawGtmSmoothstep(0.040f, 0.115f, p50);
    const float lowerMidDark = 1.0f - rawGtmSmoothstep(0.090f, 0.245f, p75);
    const float histogramDark = std::clamp(0.68f * medianDark + 0.32f * lowerMidDark, 0.0f, 1.0f);
    const float sceneConfidence = std::clamp(
            std::max(input.lowLightScene ? 0.62f : 0.0f, indoor), 0.0f, 1.0f);
    float pressure = histogramDark * sceneConfidence;

    // Low-end placement is resolved exclusively from the lower distribution (p50/p75) and
    // low-light confidence. A localized highlight cannot lift or darken the global shadow anchor.
    // FLLF and AgX own the upper range independently.
    pressure = std::clamp(pressure, 0.0f, 1.0f);

    // At maximum authority retain roughly two thirds of the incoming scene-linear level
    // (-0.62 EV). This is material enough to keep a dim room dim while remaining far from the
    // multi-stop scene normalization that previously washed low-light captures.
    const float attenuationEv = -0.62f * pressure;
    out.scenePlacementEv = std::clamp(attenuationEv, -0.62f, 0.0f);
    out.scenePlacementGain = std::clamp(std::exp2(out.scenePlacementEv), 0.65f, 1.0f);
    out.lowLightMoodPressure = pressure;
    out.active = out.scenePlacementGain < 0.995f;
    out.authority = out.active ? "LOW_LIGHT_MOOD_PRESERVATION" : "NEUTRAL";
    return out;
}

} // namespace bncam::tone
