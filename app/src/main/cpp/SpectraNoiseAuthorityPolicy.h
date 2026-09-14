#pragma once

#include <algorithm>
#include <cmath>

namespace bncam::spectra {

/**
 * Phase 0220: one evidence-first authority blend for SPECTRA sensor-noise adaptation.
 *
 * Camera2/manual S/O is the physical variance evidence. ISO is only a fallback context when
 * that model is unavailable or carries less than full confidence. The policy deliberately has
 * no lens/camera identifier input, so a lens role can never become an implicit denoise-strength
 * shortcut.
 */
struct SpectraNoiseAuthorityDecision {
    bool physicalModelUsable = false;
    float physicalModelAuthority = 0.0f;
    float isoFallbackAuthority = 1.0f;
    float combinedNoisePressure = 0.0f;
};

inline SpectraNoiseAuthorityDecision resolveSpectraNoiseAuthority(
        float modelNoisePressure,
        float isoPressure,
        float signalModelConfidence,
        bool noiseProfileApplied,
        bool normalizationCalibrationValid,
        bool cfaSupportedForBayerNoiseModel,
        int validModelChannels) noexcept {
    SpectraNoiseAuthorityDecision out{};
    const float modelPressure = std::isfinite(modelNoisePressure)
            ? std::clamp(modelNoisePressure, 0.0f, 1.0f) : 0.0f;
    const float fallbackPressure = std::isfinite(isoPressure)
            ? std::clamp(isoPressure, 0.0f, 1.0f) : 0.0f;
    const float confidence = std::isfinite(signalModelConfidence)
            ? std::clamp(signalModelConfidence, 0.0f, 1.0f) : 0.0f;

    out.physicalModelUsable = noiseProfileApplied && normalizationCalibrationValid &&
            cfaSupportedForBayerNoiseModel && validModelChannels == 4 && confidence > 0.0f;
    out.physicalModelAuthority = out.physicalModelUsable ? confidence : 0.0f;
    out.isoFallbackAuthority = 1.0f - out.physicalModelAuthority;
    out.combinedNoisePressure = std::clamp(
            out.physicalModelAuthority * modelPressure +
                    out.isoFallbackAuthority * fallbackPressure,
            0.0f,
            1.0f);
    return out;
}

/**
 * SENSOR_SENSITIVITY is already the RAW sensor-sensitivity observation. Android's
 * CONTROL_POST_RAW_SENSITIVITY_BOOST belongs to the post-RAW processed-image path and therefore
 * must not be folded into pre-demosaic RAW noise evidence.
 */
inline float rawNoiseEvidenceIso(int captureSensitivityIso) noexcept {
    if (captureSensitivityIso <= 0) return 100.0f;
    return std::clamp(static_cast<float>(captureSensitivityIso), 25.0f, 102400.0f);
}

} // namespace bncam::spectra
