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
 * Automatic RAW local-tone policy.
 *
 * Contract:
 *   scene-linear RGB -> log2(Y) -> physical-noise-aware FLLF -> strict Ynew/Yold RGB ratio.
 *
 * Important ownership rule:
 *   FLLF is a LOCAL redistribution stage. It must not use any scene key to drag a naturally
 *   dark/low-key capture toward middle grey. The coarse/DC base is identity; shadow lift and
 *   highlight compression are produced only by signed local/multiscale Laplacian remapping.
 *
 * The future explicit Adaptive Scene EV owner is allowed to operate in [-0.50, +0.25] EV. That
 * global range is intentionally NOT emulated by increasing this FLLF scene key: positive global
 * exposure and local shadow redistribution are separate responsibilities.
 *
 * Khronos/PBR Neutral remains a separate scene-to-display mapper. Physical noise is supplied in
 * the post-detail scene-linear domain and limits positive shadow lift. Real bright-range evidence
 * continues to retain negative/local highlight-compression authority.
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

    // Convert propagated scene-linear sigma into scene-relative pressure. Confidence gates only
    // automatic positive/local lift; missing or low-confidence noise evidence never invents noise.
    const float referenceSignal = std::max(0.025f, std::min(0.22f, 0.65f * p50 + 0.35f * p75));
    const float relativeSigma = sigmaY / referenceSignal;
    out.noisePressure = modelConfidence * fllfSmoothstep(0.025f, 0.16f, relativeSigma);
    out.physicalNoiseSigmaY = sigmaY;
    out.noiseModelConfidence = modelConfidence;

    // Delta 0195 ownership cleanup. After the coarse-base/DC repair, RAW FLLF no longer reads
    // sceneKey as an exposure target. Keep a scene-relative value only for diagnostics/ABI
    // compatibility; it has zero image authority. Automatic global EV therefore remains exactly
    // neutral (0 EV) until a separate bounded scene-EV owner is deliberately introduced.
    out.sceneKey = std::clamp(p50, 0.008f, 0.24f);

    // Local shadow lift and local highlight compression are independent adaptive responsibilities.
    // A shadow does NOT need a bright highlight elsewhere in the frame to qualify for lift. The
    // Laplacian band, physical-SNR gate and edge-locality logic in the shader decide whether the
    // correction is truly local; low P50/P75 alone can no longer move the DC/global level.
    const float localShadowPressure = out.shadowPressure;

    float strength = 0.42f +
            0.20f * localShadowPressure +
            0.20f * out.dynamicRangePressure +
            0.10f * out.highlightPressure;
    if (input.outdoorSkyScene) strength += 0.020f;
    if (input.strongHighlightScene) strength += 0.025f;
    // Do not attenuate the shared remap strength with noise: that would also weaken safe negative
    // highlight compression. Noise constrains the positive shadow-lift branch separately below and
    // per pixel in fllfRemapBand()/sampleFllfFullResolutionCorrection().
    out.strength = std::clamp(strength, 0.40f, 0.88f);

    // Positive/local shadow lift remains adaptive, but physical noise can reduce only this branch.
    float lift = 0.38f +
            0.78f * localShadowPressure +
            0.28f * out.dynamicRangePressure;
    lift *= 1.0f - (input.lowLightScene ? 0.62f : 0.34f) * out.noisePressure;
    out.maxLiftEv = std::clamp(lift, 0.24f, 1.35f);

    // Highlight compression remains available and is intentionally not weakened: preserving bright
    // sources/headroom has priority over making a dark scene brighter.
    out.maxCompressEv = std::clamp(
            0.38f + 0.52f * out.highlightPressure + 0.22f * out.dynamicRangePressure,
            0.34f, 1.10f);

    // Engage edge stopping earlier than the previous 0.54..0.76 EV policy. This is a conservative
    // policy-side guardrail; the structural joint-upsampling/edge-leakage repair remains a shader task.
    out.edgeStopEv = std::clamp(0.49f + 0.12f * out.noisePressure, 0.47f, 0.62f);
    out.refinement = std::clamp(
            0.075f + 0.055f * out.dynamicRangePressure - 0.020f * out.noisePressure,
            0.055f, 0.135f);

    // Keep FLLF resident when there is useful local tonal work. Balanced scenes can resolve close
    // to identity; low-light alone keeps the stage available for guarded local shadow recovery.
    const float toneNeed = std::max({localShadowPressure, out.highlightPressure,
                                     out.dynamicRangePressure});
    out.enabled = toneNeed > 0.035f || input.lowLightScene || input.outdoorSkyScene;
    return out;
}

} // namespace bncam::tone
