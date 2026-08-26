#pragma once

#include <algorithm>
#include <cmath>

namespace bncam::tone {

struct NoiseAwareRenderGainDecision {
    bool active = false;
    float cap = 0.0f;
    float isoPressure = 0.0f;
    float exposurePressure = 0.0f;
    float combinedPressure = 0.0f;
};

inline float unitRamp(float value, float low, float high) {
    if (!std::isfinite(value) || !(high > low)) return 0.0f;
    return std::clamp((value - low) / (high - low), 0.0f, 1.0f);
}

inline NoiseAwareRenderGainDecision resolveNoiseAwareRenderGainCap(
        bool rawBayer,
        bool raw10,
        bool lowLightScene,
        int captureIso,
        float exposureMs) {
    NoiseAwareRenderGainDecision decision{};
    if (!rawBayer || !lowLightScene || captureIso <= 0) return decision;

    // Long high-ISO frames already contain the sensor exposure that was actually captured.
    // A large post-RAW render multiplier cannot improve their SNR; it only magnifies residual
    // luma/chroma noise before the non-linear tone curve. Keep low-ISO rendering unchanged and
    // progressively restrict only the extra digital lift once the capture itself is noise-heavy.
    const float safeIso = static_cast<float>(std::max(1, captureIso));
    const float isoStopsAbove800 = std::log2(std::max(1.0f, safeIso / 800.0f));
    decision.isoPressure = unitRamp(isoStopsAbove800, 0.0f, 2.0f); // ISO 800 -> 3200
    decision.exposurePressure = unitRamp(exposureMs, 20.0f, 50.0f);

    const bool captureIsNoiseHeavy = captureIso >= 800 && exposureMs >= 20.0f;
    const bool veryHighIso = captureIso >= 1200;
    if (!captureIsNoiseHeavy && !veryHighIso) return decision;

    decision.combinedPressure = std::clamp(
            0.70f * decision.isoPressure + 0.30f * decision.exposurePressure,
            0.0f,
            1.0f);

    const float upperCap = raw10 ? 1.80f : 1.90f;
    const float lowerCap = raw10 ? 1.35f : 1.45f;
    decision.cap = upperCap + (lowerCap - upperCap) * decision.combinedPressure;
    decision.active = true;
    return decision;
}

} // namespace bncam::tone
