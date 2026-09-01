#pragma once

#include <algorithm>
#include <cmath>

namespace bncam::color {

struct RawCameraProfileRenderInput {
    bool matrixFinite = false;
    float matrixDeterminant = 0.0f;
    float matrixMaxAbsCoefficient = 0.0f;
    float matrixNeutralAxisSpread = 0.0f;
    float sceneChromaP50 = 0.0f;
    float sceneChromaP90 = 0.0f;
    float sceneMeanRgbSpread = 0.0f;
    float dynamicRangePressure = 0.0f;
    float recoverableHighlightPressure = 0.0f;
    float sensorClipPressure = 0.0f;
    float physicalNoisePressure = 0.0f;
    bool lowLightScene = false;
};

struct RawCameraProfileRenderPlan {
    bool enabled = false;
    float renderStrength = 0.0f;
    float transportMultiplier = 1.0f;
    float matrixConfidence = 0.0f;
    float sceneColorNeed = 0.0f;
    float saturatedColorGuard = 1.0f;
    float noiseGuard = 1.0f;
    float highlightGuard = 1.0f;
    const char* reason = "DISABLED_INVALID_COLOR_TRANSFORM";
};

inline float rawCameraProfileSmoothstep(float edge0, float edge1, float value) noexcept {
    if (!(edge1 > edge0) || !std::isfinite(value)) return value >= edge1 ? 1.0f : 0.0f;
    const float t = std::clamp((value - edge0) / (edge1 - edge0), 0.0f, 1.0f);
    return t * t * (3.0f - 2.0f * t);
}

inline RawCameraProfileRenderPlan resolveRawCameraProfileRenderPlan(
        const RawCameraProfileRenderInput& in) noexcept {
    RawCameraProfileRenderPlan out{};

    const float absDet = std::abs(in.matrixDeterminant);
    const bool matrixPlausible = in.matrixFinite && std::isfinite(absDet) &&
            std::isfinite(in.matrixMaxAbsCoefficient) &&
            in.matrixMaxAbsCoefficient >= 0.05f && in.matrixMaxAbsCoefficient <= 8.0f &&
            absDet >= 1.0e-4f;
    if (!matrixPlausible) return out;

    // Matrix confidence is deliberately permissive: Camera2 direct transforms and
    // ForwardMatrix-derived transforms can have different coefficient/neutral-axis shapes.
    // Only pathological transforms lose authority; this policy must not become a second CCM.
    const float determinantConfidence = rawCameraProfileSmoothstep(1.0e-4f, 0.035f, absDet);
    const float coefficientConfidence = 1.0f - rawCameraProfileSmoothstep(
            3.5f, 7.5f, std::max(0.0f, in.matrixMaxAbsCoefficient));
    const float neutralSpreadPenalty = 0.18f * rawCameraProfileSmoothstep(
            0.18f, 0.85f, std::max(0.0f, in.matrixNeutralAxisSpread));
    out.matrixConfidence = std::clamp(
            (0.62f + 0.38f * determinantConfidence) *
                    (0.72f + 0.28f * coefficientConfidence) - neutralSpreadPenalty,
            0.35f, 1.0f);

    const float c50 = std::clamp(
            std::isfinite(in.sceneChromaP50) ? in.sceneChromaP50 : 0.0f, 0.0f, 1.5f);
    const float c90 = std::clamp(
            std::isfinite(in.sceneChromaP90) ? in.sceneChromaP90 : c50, 0.0f, 1.5f);
    const float meanSpread = std::clamp(
            std::isfinite(in.sceneMeanRgbSpread) ? static_cast<float>(in.sceneMeanRgbSpread) : 0.0f,
            0.0f, 2.0f);

    // The renderer is a photographic chroma-shaping fallback, not a saturation control.
    // Low/moderate scene chroma grants a little more authority; already-strong color vetoes it.
    const float weakMedianChroma = 1.0f - rawCameraProfileSmoothstep(0.10f, 0.30f, c50);
    const float weakMeanColor = 1.0f - rawCameraProfileSmoothstep(0.08f, 0.32f, meanSpread);
    out.sceneColorNeed = std::clamp(0.72f * weakMedianChroma + 0.28f * weakMeanColor, 0.0f, 1.0f);
    out.saturatedColorGuard = 1.0f - 0.72f * rawCameraProfileSmoothstep(0.34f, 0.72f, c90);

    const float noise = std::clamp(
            std::isfinite(in.physicalNoisePressure) ? in.physicalNoisePressure : 0.0f, 0.0f, 1.0f);
    out.noiseGuard = std::clamp(1.0f - noise * (in.lowLightScene ? 0.68f : 0.42f), 0.30f, 1.0f);

    // Per-pixel highlight protection remains authoritative in the shader. The scene-level gate
    // only backs the creative renderer off modestly in strongly clipped/high-DR captures.
    const float highlightPressure = std::clamp(
            0.48f * std::max(0.0f, in.recoverableHighlightPressure) +
            0.34f * std::max(0.0f, in.dynamicRangePressure) +
            0.18f * std::max(0.0f, in.sensorClipPressure),
            0.0f, 1.0f);
    out.highlightGuard = std::clamp(1.0f - 0.34f * highlightPressure, 0.66f, 1.0f);

    // A small always-photographic base plus measured need. Hard cap is intentionally far below
    // generic "Punchy"/35% boosts: sensor color remains owned by AWB + the calibrated matrix.
    const float requested = (0.055f + 0.085f * out.sceneColorNeed) *
            out.matrixConfidence * out.saturatedColorGuard * out.noiseGuard * out.highlightGuard;
    out.renderStrength = std::clamp(requested, 0.0f, 0.16f);
    out.enabled = out.renderStrength >= 0.012f;
    out.transportMultiplier = out.enabled ? 1.0f + out.renderStrength : 1.0f;
    out.reason = out.enabled
            ? "ADAPTIVE_HUE_PRESERVING_CAMERA_RENDER"
            : "DISABLED_BY_SCENE_OR_NOISE_GUARDS";
    return out;
}

} // namespace bncam::color
