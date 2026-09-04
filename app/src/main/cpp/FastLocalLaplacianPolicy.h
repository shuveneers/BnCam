#pragma once

#include <algorithm>
#include <cmath>
#include <cstdint>

namespace bncam::tone {

struct FastLocalLaplacianInput {
    float p50 = 0.14f;
    float p75 = 0.24f;
    float p95 = 0.62f;
    float p99 = 0.90f;
    float sensorSaturatedPct = 0.0f;  // percentage, 0..100
    float nearWhiteFraction = 0.0f;   // fraction, 0..1
    // Physical post-detail luma sigma propagated through
    // LSC -> spatial exposure -> demosaic -> AWB -> CCM -> detail.
    float physicalNoiseSigmaY = 0.0f;
    float noiseModelConfidence = 0.0f;
    bool lowLightScene = false;
    bool outdoorSkyScene = false;
    bool strongHighlightScene = false;
};

struct FastLocalLaplacianPlan {
    bool enabled = true;
    float strength = 0.58f;
    float sceneKey = 0.158f;
    float maxLiftEv = 1.00f;
    float maxCompressEv = 0.70f;
    float edgeStopEv = 0.62f;
    float refinement = 0.14f;
    float physicalNoiseSigmaY = 0.0f;
    float noiseModelConfidence = 0.0f;
    float noisePressure = 0.0f;
    float shadowPressure = 0.0f;
    float highlightPressure = 0.0f;
    float dynamicRangePressure = 0.0f;
    float sceneRangeStops = 0.0f;
    std::uint32_t pyramidLevels = 6u;   // level 0 is half-resolution.
    std::uint32_t baseDownsample = 2u;
};

inline float fllfSmoothstep(float edge0, float edge1, float x) noexcept {
    if (!(edge1 > edge0)) return x >= edge1 ? 1.0f : 0.0f;
    const float t = std::clamp((x - edge0) / (edge1 - edge0), 0.0f, 1.0f);
    return t * t * (3.0f - 2.0f * t);
}

/**
 * Phase-5 automatic RAW tone authority.
 *
 * Contract:
 *   scene-linear RGB -> log2(Y) -> physical-noise-aware FLLF -> strict Ynew/Yold RGB ratio.
 *
 * There is deliberately no separate global S-curve/Reinhard stage in this policy and no display mapper.
 * Khronos PBR Neutral is the separate final scene-to-display mapper. Physical noise is supplied
 * in the exact post-detail scene-linear domain; it limits positive shadow lift here and is also
 * consumed by the Vulkan FLLF correction coring. Negative highlight compression is not credited
 * as denoise and remains available when the scene contains real bright-range evidence.
 */
inline FastLocalLaplacianPlan resolveFastLocalLaplacianPlan(
        const FastLocalLaplacianInput& input) noexcept {
    FastLocalLaplacianPlan out{};
    const float p50 = std::clamp(input.p50, 0.0f, 4.0f);
    const float p75 = std::clamp(input.p75, 0.0f, 4.0f);
    const float p95 = std::clamp(input.p95, 0.0f, 4.0f);
    const float p99 = std::clamp(input.p99, 0.0f, 4.0f);
    const float saturatedPct = std::max(0.0f, input.sensorSaturatedPct);
    const float nearWhite = std::clamp(input.nearWhiteFraction, 0.0f, 1.0f);
    const float sigmaY = std::max(0.0f,
            std::isfinite(input.physicalNoiseSigmaY) ? input.physicalNoiseSigmaY : 0.0f);
    const float modelConfidence = std::clamp(
            std::isfinite(input.noiseModelConfidence) ? input.noiseModelConfidence : 0.0f,
            0.0f, 1.0f);

    out.shadowPressure = std::clamp(
            0.62f * (1.0f - fllfSmoothstep(0.055f, 0.20f, p50)) +
            0.38f * (1.0f - fllfSmoothstep(0.13f, 0.38f, p75)),
            0.0f, 1.0f);

    const float brightTail = std::clamp(
            0.50f * fllfSmoothstep(0.58f, 0.92f, p95) +
            0.30f * fllfSmoothstep(0.76f, 1.04f, p99) +
            0.20f * fllfSmoothstep(0.0025f, 0.045f, nearWhite),
            0.0f, 1.0f);
    const float sensorClip = std::clamp(
            0.70f * fllfSmoothstep(0.05f, 0.60f, saturatedPct) +
            0.30f * fllfSmoothstep(0.60f, 2.00f, saturatedPct),
            0.0f, 1.0f);
    out.highlightPressure = std::clamp(
            std::max(brightTail, 0.80f * sensorClip), 0.0f, 1.0f);

    const float rangeLow = std::max(0.006f, p50);
    const float rangeHigh = std::max(rangeLow, p95);
    out.sceneRangeStops = std::clamp(std::log2(rangeHigh / rangeLow), 0.0f, 10.0f);
    const float rangeEvidence = fllfSmoothstep(1.55f, 3.85f, out.sceneRangeStops) *
            fllfSmoothstep(0.28f, 0.72f, p95);
    out.dynamicRangePressure = std::clamp(
            std::max(rangeEvidence, out.shadowPressure * out.highlightPressure),
            0.0f, 1.0f);

    // Convert the propagated scene-linear sigma into a scene-relative pressure. Confidence gates
    // only the amount of automatic lift; a missing/low-confidence model never invents extra noise.
    const float referenceSignal = std::max(0.025f, std::min(0.22f, 0.65f * p50 + 0.35f * p75));
    const float relativeSigma = sigmaY / referenceSignal;
    out.noisePressure = modelConfidence * fllfSmoothstep(0.025f, 0.16f, relativeSigma);
    out.physicalNoiseSigmaY = sigmaY;
    out.noiseModelConfidence = modelConfidence;

    // FLLF is the primary automatic brightness/DR owner. Software exposure remains neutral; the
    // local log-luma field therefore needs enough positive range to place a valid dark foreground
    // before PBR Neutral applies its display toe. Shadow-pressure key lift is intentionally local:
    // it raises underexposed foreground structure without introducing a hidden global EV offset.
    const float shadowKeyLift = 0.030f * out.shadowPressure;
    const float highDrKeyLift = 0.010f * out.dynamicRangePressure;
    const float lowLightReduction = input.lowLightScene
            ? 0.010f * out.noisePressure * (1.0f - 0.55f * out.dynamicRangePressure)
            : 0.0f;
    out.sceneKey = std::clamp(
            0.150f + shadowKeyLift + highDrKeyLift - lowLightReduction,
            0.138f, 0.185f);

    float strength = 0.50f +
            0.24f * out.dynamicRangePressure +
            0.14f * out.shadowPressure +
            0.06f * out.highlightPressure;
    if (input.outdoorSkyScene) strength += 0.025f;
    if (input.strongHighlightScene) strength += 0.030f;
    strength *= 1.0f - (input.lowLightScene ? 0.30f : 0.14f) * out.noisePressure;
    out.strength = std::clamp(strength, 0.45f, 0.92f);

    // A broad, well-supported dark region may require more than one stop of local lift merely to
    // enter the useful part of PBR Neutral. The limit is still bounded and noise-aware; actual
    // per-pixel application is further guarded in the Vulkan shader by propagated physical sigma,
    // local SNR and multiscale edge stopping.
    float lift = 0.62f +
            0.88f * out.shadowPressure +
            0.42f * out.dynamicRangePressure;
    lift *= 1.0f - (input.lowLightScene ? 0.58f : 0.30f) * out.noisePressure;
    out.maxLiftEv = std::clamp(lift, 0.45f, 1.80f);
    out.maxCompressEv = std::clamp(
            0.38f + 0.48f * out.highlightPressure + 0.24f * out.dynamicRangePressure,
            0.34f, 1.10f);

    // FLLF works in pure log2 luminance. A slightly larger edge threshold is permitted when the
    // physical luma floor is high so sensor texture is not misclassified as scene structure.
    out.edgeStopEv = std::clamp(0.56f + 0.18f * out.noisePressure, 0.54f, 0.76f);
    out.refinement = std::clamp(
            0.11f + 0.07f * out.dynamicRangePressure - 0.025f * out.noisePressure,
            0.08f, 0.18f);

    // PBR Neutral still maps even when FLLF makes no local correction. Keep FLLF resident for any
    // scene with measurable tonal imbalance/range; balanced scenes may resolve to near-identity.
    const float toneNeed = std::max({out.shadowPressure, out.highlightPressure,
                                     out.dynamicRangePressure});
    out.enabled = toneNeed > 0.035f || input.lowLightScene || input.outdoorSkyScene;
    return out;
}

} // namespace bncam::tone
