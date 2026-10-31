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
    // A default-constructed plan is identity. Only resolveFastLocalLaplacianPlan may
    // grant automatic local-tone authority based on per-frame scene evidence.
    bool enabled = false;
    float strength = 0.0f;
    float sceneKey = 0.158f;
    float maxLiftEv = 0.0f;
    float maxCompressEv = 0.0f;
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
 * Automatic RAW local-tone policy.
 *
 * Contract:
 *   scene-linear RGB -> log2(Y) -> physical-noise-aware FLLF -> strict Ynew/Yold RGB ratio.
 *
 * Ownership:
 *   FLLF may redistribute LOCAL contrast only when the captured scene carries real
 *   dynamic-range evidence. A low median luminance alone must not trigger shadow lift.
 *   The shader leaves the Gaussian/DC base untouched. A profile exposure change is the
 *   only user-controlled global EV owner and is applied downstream.
 *
 * With no trustworthy physical noise estimate, automatically raising shadow exposure
 * is unsafe. Highlight compression does not depend on the noise model.
 * Khronos/PBR Neutral remains the separate scene-to-display stage.
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

    const float recoverableHighlightPressure = std::clamp(
            0.50f * fllfSmoothstep(0.58f, 0.92f, p95) +
            0.30f * fllfSmoothstep(0.76f, 1.04f, p99) +
            0.20f * fllfSmoothstep(0.0025f, 0.045f, nearWhite),
            0.0f, 1.0f);
    const float sensorClipPressure = std::clamp(
            0.70f * fllfSmoothstep(0.05f, 0.60f, saturatedPct) +
            0.30f * fllfSmoothstep(0.60f, 2.00f, saturatedPct),
            0.0f, 1.0f);
    out.highlightPressure = std::clamp(
            std::max(recoverableHighlightPressure, 0.80f * sensorClipPressure), 0.0f, 1.0f);

    const float rangeLow = std::max(0.006f, p50);
    const float rangeHigh = std::max(rangeLow, p95);
    out.sceneRangeStops = std::clamp(std::log2(rangeHigh / rangeLow), 0.0f, 10.0f);
    const float rangeEvidence = fllfSmoothstep(1.55f, 3.85f, out.sceneRangeStops) *
            fllfSmoothstep(0.28f, 0.72f, p95);
    out.dynamicRangePressure = std::clamp(
            std::max(rangeEvidence, out.shadowPressure * out.highlightPressure),
            0.0f, 1.0f);

    // Retain physical noise evidence for the per-pixel / per-scale shader SNR guard.
    const float referenceSignal = std::max(0.025f, std::min(0.22f, 0.65f * p50 + 0.35f * p75));
    const float relativeSigma = sigmaY / referenceSignal;
    out.noisePressure = modelConfidence * fllfSmoothstep(0.025f, 0.16f, relativeSigma);
    out.physicalNoiseSigmaY = sigmaY;
    out.noiseModelConfidence = modelConfidence;

    // ABI/telemetry compatibility. The shader does NOT use sceneKey as an exposure target.
    out.sceneKey = std::clamp(p50, 0.008f, 0.24f);

    // A dark scene is not intrinsically underexposed. Positive lift needs BOTH dark-region
    // evidence and evidence of range that genuinely requires redistribution. Outdoor/indoor
    // classification is deliberately not an independent brightening authority.
    const float localShadowPressure = out.shadowPressure *
            fllfSmoothstep(0.14f, 0.55f, out.dynamicRangePressure);

    // Without physical S/O confidence the conservative fallback is no AUTOMATIC shadow lift;
    // this is not a veto on the user's explicit profile Shadow/Exposure controls.
    const float shadowNoiseTrust = fllfSmoothstep(0.25f, 0.75f, modelConfidence);
    const float shadowNoiseSafety = 1.0f - (input.lowLightScene ? 0.62f : 0.34f) * out.noisePressure;

    out.maxLiftEv = std::clamp(
            (0.65f * localShadowPressure + 0.24f * out.dynamicRangePressure) *
                    shadowNoiseTrust * shadowNoiseSafety,
            0.0f, 0.89f);

    // Highlight compression also needs measured bright-range evidence. A low-light frame
    // without bright structure must not receive nonzero FLLF just because it is dark.
    out.maxCompressEv = std::clamp(
            0.70f * out.highlightPressure + 0.24f * out.dynamicRangePressure,
            0.0f, 0.94f);

    // These are local coefficient-remap gains, not global exposure. Avoid a large hard-coded
    // base strength that would touch every ordinary daylight capture without a reason.
    out.strength = std::clamp(
            0.24f + 0.26f * localShadowPressure +
                    0.18f * out.dynamicRangePressure + 0.12f * out.highlightPressure,
            0.0f, 0.76f);

    out.edgeStopEv = std::clamp(0.49f + 0.12f * out.noisePressure, 0.47f, 0.62f);
    out.refinement = std::clamp(
            0.075f + 0.055f * out.dynamicRangePressure - 0.020f * out.noisePressure,
            0.055f, 0.135f);

    // Avoid running the pyramid at all when it can only be identity. The shader is still
    // responsible for checking each coefficient's spatial/edge/noise eligibility.
    out.enabled = out.strength > 0.0f &&
            (out.maxLiftEv > 0.02f || out.maxCompressEv > 0.02f);
    return out;
}

} // namespace bncam::tone
