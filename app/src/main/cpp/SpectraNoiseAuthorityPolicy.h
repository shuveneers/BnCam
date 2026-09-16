#pragma once

#include <algorithm>
#include <cmath>

namespace bncam::spectra {

/**
 * SPECTRA consumes already-resolved physical shutter-noise evidence.
 *
 * OEM/System/Manual/Preset selection, Dynamic ISO and A/B/C/D evaluation all happen upstream.
 * Native SPECTRA receives only the frozen physical S/O model and may never synthesize a replacement
 * model from ISO when that evidence is unavailable. Kotlin has already validated RAW normalization
 * and CFA applicability before it marks the flattened S/O payload applied; native then validates the
 * physical payload once via `physicalNoiseModelAvailable`. The ISO argument remains telemetry/context
 * only and has zero fallback authority.
 */
struct SpectraNoiseAuthorityDecision {
    bool physicalModelUsable = false;
    float physicalModelAuthority = 0.0f;
    // Retained telemetry field. Strict add-on contract requires this to remain zero.
    float isoFallbackAuthority = 0.0f;
    float combinedNoisePressure = 0.0f;
};

inline SpectraNoiseAuthorityDecision resolveSpectraNoiseAuthority(
        float modelNoisePressure,
        float isoPressure,
        float signalModelConfidence,
        bool physicalNoiseModelAvailable) noexcept {
    SpectraNoiseAuthorityDecision out{};
    const float modelPressure = std::isfinite(modelNoisePressure)
            ? std::clamp(modelNoisePressure, 0.0f, 1.0f) : 0.0f;
    const float confidence = std::isfinite(signalModelConfidence)
            ? std::clamp(signalModelConfidence, 0.0f, 1.0f) : 0.0f;

    // `isoPressure` is intentionally ignored for authority. It can still be logged by callers,
    // but SPECTRA cannot run an ISO-only surrogate model when physical S/O is missing.
    (void) isoPressure;

    out.physicalModelUsable = physicalNoiseModelAvailable && confidence > 0.0f;
    out.physicalModelAuthority = out.physicalModelUsable ? confidence : 0.0f;
    out.isoFallbackAuthority = 0.0f;
    out.combinedNoisePressure = out.physicalModelUsable
            ? std::clamp(out.physicalModelAuthority * modelPressure, 0.0f, 1.0f)
            : 0.0f;
    return out;
}

/**
 * SENSOR_SENSITIVITY remains useful as capture context/telemetry. It is not a replacement for the
 * physical shutter S/O model and CONTROL_POST_RAW_SENSITIVITY_BOOST must not be folded into RAW
 * pre-demosaic noise evidence.
 */
inline float rawNoiseEvidenceIso(int captureSensitivityIso) noexcept {
    if (captureSensitivityIso <= 0) return 100.0f;
    return std::clamp(static_cast<float>(captureSensitivityIso), 25.0f, 102400.0f);
}

} // namespace bncam::spectra
