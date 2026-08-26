#pragma once

#include <algorithm>

namespace bncam::noise {

struct LowLightPresentationInput {
    bool rawBayer = false;
    bool lowLightScene = false;
    bool displayHighlightScene = false;
    bool automaticMidtoneLiftActive = false;
    float indoorLowLightConfidence = 0.0f;
    float physicalNoisePressure = 0.0f;
    float existingToneGuardRisk = 0.0f;
};

struct LowLightPresentationPlan {
    float presentationNoisePressure = 0.0f;
    float automaticMidtoneLiftAttenuation = 0.0f;
    float rawBaseVibrance = 1.0f;
};

inline float presentationSmoothstep(float edge0, float edge1, float x) noexcept {
    if (!(edge1 > edge0)) return x >= edge1 ? 1.0f : 0.0f;
    const float t = std::clamp((x - edge0) / (edge1 - edge0), 0.0f, 1.0f);
    return t * t * (3.0f - 2.0f * t);
}

/**
 * Scalar policy that prevents the display/tone stage from re-amplifying sensor noise that the
 * physical noise model has already identified. It does not add a denoise pass: full-frame tone
 * and vibrance remain on the existing Vulkan-resident path.
 *
 * Zero physical pressure keeps a small photographic colour compensation only. AgX already
 * restores part of the display-referred chroma, so a large second base-vibrance multiplier would
 * re-amplify WB/CCM residual colour noise. In low light, physical pressure further limits colour
 * amplification while preserving bounded separation.
 */
inline LowLightPresentationPlan resolveLowLightPresentationPlan(
        const LowLightPresentationInput& input) noexcept {
    LowLightPresentationPlan out{};
    if (!input.rawBayer) {
        return out;
    }

    if (!input.lowLightScene) {
        out.rawBaseVibrance = 1.16f;
        return out;
    }

    const float confidence = std::clamp(input.indoorLowLightConfidence, 0.0f, 1.0f);
    const float physicalPressure = std::clamp(input.physicalNoisePressure, 0.0f, 1.0f);
    out.presentationNoisePressure = std::clamp(
            physicalPressure * (0.65f + 0.35f * confidence),
            0.0f,
            1.0f);

    const float existingRisk = std::clamp(input.existingToneGuardRisk, 0.0f, 1.0f);
    if (input.automaticMidtoneLiftActive) {
        const float existingAttenuation = 0.40f * existingRisk;
        const float physicalAttenuation =
                0.75f * out.presentationNoisePressure * (1.0f - 0.35f * existingRisk);
        out.automaticMidtoneLiftAttenuation = std::clamp(
                existingAttenuation + physicalAttenuation,
                0.0f,
                1.00f);
    }

    const bool strongLowLightColourBoost =
            input.displayHighlightScene || confidence >= 0.60f;
    const float legacyLowLightVibrance = strongLowLightColourBoost ? 1.22f : 1.18f;
    const float noiseLimitedVibranceFloor = strongLowLightColourBoost ? 1.12f : 1.10f;
    const float attenuation = presentationSmoothstep(
            0.18f,
            0.82f,
            out.presentationNoisePressure);
    out.rawBaseVibrance = std::clamp(
            legacyLowLightVibrance +
                    (noiseLimitedVibranceFloor - legacyLowLightVibrance) * attenuation,
            noiseLimitedVibranceFloor,
            legacyLowLightVibrance);
    return out;
}

} // namespace bncam::noise
