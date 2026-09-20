#pragma once

#include <algorithm>
#include <cmath>
#include <string>

namespace bncam::tone {

struct GlobalSceneExposureInput {
    float p35 = 0.10f;
    float p50 = 0.14f;
    float p75 = 0.24f;
    float p90 = 0.40f;
    float p95 = 0.62f;
    float p99 = 0.90f;
    float rawClipFraction = 0.0f;      // fraction, 0..1
    float sensorSaturatedPct = 0.0f;   // percent, 0..100
    float physicalNoiseSigmaY = 0.0f;
    float noiseModelConfidence = 0.0f;
};

struct GlobalSceneExposurePlan {
    bool valid = false;
    float requestedEv = 0.0f;
    float highlightLimitedEv = 0.0f;
    float appliedEv = 0.0f;
    float sceneKey = 0.148f;
    float midtone = 0.14f;
    float highlightHeadroomEv = 0.0f;
    float sceneRangeEv = 0.0f;
    float highlightPressure = 0.0f;
    float noisePressure = 0.0f;
    float gain = 1.0f;
    std::string reason = "INVALID_EVIDENCE_IDENTITY";
};

inline float globalSceneSmoothstep(float edge0, float edge1, float x) noexcept {
    if (!(edge1 > edge0)) return x >= edge1 ? 1.0f : 0.0f;
    const float t = std::clamp((x - edge0) / (edge1 - edge0), 0.0f, 1.0f);
    return t * t * (3.0f - 2.0f * t);
}

inline float globalSceneSafeLog2(float value) noexcept {
    return std::log2(std::max(value, 1.0e-6f));
}

/**
 * Phase 11F single automatic JPEG-development exposure owner.
 *
 * This policy never changes Camera2 shutter/ISO and never performs local tone. It resolves one
 * scene-linear scalar from robust post-CCM luminance evidence, then limits positive placement by
 * robust highlight headroom, true RAW clipping and propagated physical-noise pressure.
 *
 * Contract:
 *   calibrated linear RGB *= exp2(appliedEv)
 *   -> GTM -> FLLF -> display mapper -> explicit profile look
 */
inline GlobalSceneExposurePlan resolveGlobalSceneExposurePlan(
        const GlobalSceneExposureInput& input) noexcept {
    GlobalSceneExposurePlan out{};
    const float p35 = std::clamp(input.p35, 0.0f, 8.0f);
    const float p50 = std::clamp(input.p50, 0.0f, 8.0f);
    const float p75 = std::clamp(input.p75, 0.0f, 8.0f);
    const float p90 = std::clamp(input.p90, 0.0f, 8.0f);
    const float p95 = std::clamp(input.p95, 0.0f, 8.0f);
    const float p99 = std::clamp(input.p99, 0.0f, 8.0f);
    if (!(p50 > 1.0e-6f) || !(p75 > 1.0e-6f) || !std::isfinite(p99)) {
        return out;
    }

    out.valid = true;
    out.midtone = std::max(0.001f, 0.20f * p35 + 0.35f * p50 + 0.45f * p75);
    const float shadowPressure = std::clamp(
            0.65f * (1.0f - globalSceneSmoothstep(0.060f, 0.210f, p50)) +
            0.35f * (1.0f - globalSceneSmoothstep(0.120f, 0.360f, p75)),
            0.0f, 1.0f);
    out.sceneRangeEv = std::clamp(
            globalSceneSafeLog2(std::max(p95, 0.010f)) -
            globalSceneSafeLog2(std::max(p35, 0.006f)), 0.0f, 10.0f);
    const float dynamicRangePressure = globalSceneSmoothstep(2.0f, 5.0f, out.sceneRangeEv) *
            globalSceneSmoothstep(0.28f, 0.72f, p95);

    const float strictClipFraction = std::max(
            std::clamp(input.rawClipFraction, 0.0f, 1.0f),
            std::clamp(input.sensorSaturatedPct * 0.01f, 0.0f, 1.0f));
    out.highlightPressure = std::clamp(std::max(
            0.58f * globalSceneSmoothstep(0.58f, 0.92f, p95) +
            0.27f * globalSceneSmoothstep(0.76f, 1.08f, p99) +
            0.15f * globalSceneSmoothstep(0.60f, 1.05f, p90),
            globalSceneSmoothstep(0.0005f, 0.020f, strictClipFraction)), 0.0f, 1.0f);

    const float sigmaY = std::max(0.0f,
            std::isfinite(input.physicalNoiseSigmaY) ? input.physicalNoiseSigmaY : 0.0f);
    const float confidence = std::clamp(
            std::isfinite(input.noiseModelConfidence) ? input.noiseModelConfidence : 0.0f,
            0.0f, 1.0f);
    const float referenceSignal = std::max(0.025f, std::min(0.24f, 0.65f * p50 + 0.35f * p75));
    out.noisePressure = confidence * globalSceneSmoothstep(0.025f, 0.16f, sigmaY / referenceSignal);

    // Capture development and RAW preview intentionally live in the same photographic range.
    // The exact scene key remains evidence-derived; no fixed +EV offset exists.
    out.sceneKey = std::clamp(
            0.148f + 0.018f * shadowPressure + 0.006f * dynamicRangePressure -
            0.012f * out.highlightPressure - 0.010f * out.noisePressure,
            0.132f, 0.170f);

    constexpr float kMaxPositiveEv = 1.25f;
    constexpr float kMaxNegativeEv = -0.50f;
    out.requestedEv = std::clamp(
            globalSceneSafeLog2(out.sceneKey / out.midtone),
            kMaxNegativeEv, kMaxPositiveEv);

    // GTM is allowed to shoulder-compress above unity, so robust p95/p99 evidence receives 1.20x
    // scene-linear headroom rather than being forcibly clipped to display white before GTM.
    const float robustHigh = std::max(0.020f, 0.75f * p95 + 0.25f * p99);
    out.highlightHeadroomEv = std::clamp(
            globalSceneSafeLog2(1.20f / robustHigh), kMaxNegativeEv, kMaxPositiveEv);

    if (out.requestedEv > 0.0f) {
        const float clipPressure = globalSceneSmoothstep(0.0005f, 0.020f, strictClipFraction);
        const float clipLimitedEv = (1.0f - clipPressure) * kMaxPositiveEv;
        const float noiseLimitedEv = (1.0f - out.noisePressure) * kMaxPositiveEv +
                out.noisePressure * 0.65f;
        out.highlightLimitedEv = std::clamp(
                std::min({out.requestedEv, out.highlightHeadroomEv,
                          clipLimitedEv, noiseLimitedEv}),
                0.0f, kMaxPositiveEv);
    } else {
        // A bright scene may need a small negative placement. Do not invent extra darkening from
        // clipping: acquisition ETTR owns sensor exposure; this is only development placement.
        out.highlightLimitedEv = out.requestedEv;
    }

    out.appliedEv = std::clamp(out.highlightLimitedEv, kMaxNegativeEv, kMaxPositiveEv);
    out.gain = std::exp2(out.appliedEv);
    if (out.requestedEv > out.appliedEv + 0.02f) {
        if (strictClipFraction >= 0.0005f) out.reason = "HIGHLIGHT_CLIP_LIMITED";
        else if (out.highlightHeadroomEv < out.requestedEv) out.reason = "HIGHLIGHT_HEADROOM_LIMITED";
        else if (out.noisePressure > 0.25f) out.reason = "PHYSICAL_NOISE_LIMITED";
        else out.reason = "SCENE_PLACEMENT_LIMITED";
    } else if (out.appliedEv > 0.02f) {
        out.reason = "MIDTONE_PLACEMENT_LIFT";
    } else if (out.appliedEv < -0.02f) {
        out.reason = "BRIGHT_SCENE_PLACEMENT";
    } else {
        out.reason = "SCENE_ALREADY_PLACED";
    }
    return out;
}

} // namespace bncam::tone
