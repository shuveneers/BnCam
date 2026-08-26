#pragma once

#include <algorithm>
#include <cmath>

namespace bncam::spectra2 {

/**
 * Candidate authority for the pre-demosaic SPECTRA chroma-rescue path.
 *
 * Contract:
 * - profileChromaAuthority remains the master user/ISO authority; exact zero is a no-op.
 * - combinedNoisePressure may release additional candidate authority as physical noise rises.
 * - model confidence only provides a bounded reliability modulation here; the caller and
 *   downstream Context Fusion / No-Regret stages own the hard confidence and structure gates.
 * - the square-root mapping deliberately avoids multiplying two already-conservative
 *   authorities, which was the source of the old under-powered Pass-2 behaviour.
 */
inline float resolveContextFusionChromaCandidateAuthority(
        float combinedNoisePressure,
        float modelConfidence,
        float profileChromaAuthority
) {
    if (!std::isfinite(profileChromaAuthority) || profileChromaAuthority <= 0.0f) {
        return 0.0f;
    }

    const float pressure = std::isfinite(combinedNoisePressure)
            ? std::clamp(combinedNoisePressure, 0.0f, 1.0f)
            : 0.0f;
    const float confidence = std::isfinite(modelConfidence)
            ? std::clamp(modelConfidence, 0.0f, 1.0f)
            : 0.0f;

    // The caller currently hard-rejects very low-confidence models before reaching this
    // helper. Retain a defensive no-op here so the policy remains safe if reused elsewhere.
    if (confidence < 0.10f) {
        return 0.0f;
    }

    const float profileAuthority = std::sqrt(
            std::clamp(profileChromaAuthority, 0.0f, 1.65f));
    const float pressureFactor = 0.92f + 0.16f * pressure;
    const float confidenceFactor = 0.94f + 0.06f * confidence;

    return std::clamp(
            profileAuthority * pressureFactor * confidenceFactor,
            0.0f,
            0.98f);
}

} // namespace bncam::spectra2
