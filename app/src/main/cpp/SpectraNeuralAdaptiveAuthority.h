#pragma once

#include <algorithm>
#include <cmath>

namespace bncam::spectra::neural {

// Phase 9 cross-sensor authority contract.
//
// These thresholds operate only on dimensionless local sensor-domain SNR. They do not encode
// sensor/lens identity. Phase 9 preserves the existing Phase-6 adaptive-response authority and
// can only attenuate it further. The Student still receives the full physical conditioning vector;
// this gate protects high-SNR texture without introducing a new strength boost.
constexpr float kNeuralAdaptiveFullEvidenceSnr = 2.0f;
constexpr float kNeuralAdaptiveIdentitySnr = 8.0f;
constexpr float kNeuralAdaptiveSigmaFloor = 1.0e-8f;

inline float neuralAdaptiveNoiseEvidenceFromSnr(float snr) noexcept {
    if (!std::isfinite(snr) || snr < 0.0f) {
        return 0.0f;
    }
    if (snr <= kNeuralAdaptiveFullEvidenceSnr) {
        return 1.0f;
    }
    if (snr >= kNeuralAdaptiveIdentitySnr) {
        return 0.0f;
    }
    const float t = std::clamp(
            (snr - kNeuralAdaptiveFullEvidenceSnr) /
                    (kNeuralAdaptiveIdentitySnr - kNeuralAdaptiveFullEvidenceSnr),
            0.0f, 1.0f);
    const float smooth = t * t * (3.0f - 2.0f * t);
    return 1.0f - smooth;
}

inline float neuralAdaptiveNoiseEvidence(float normalizedSignal, float sigma) noexcept {
    if (!std::isfinite(normalizedSignal) || !std::isfinite(sigma) || sigma <= 0.0f) {
        return 0.0f;
    }
    const float snr = std::abs(normalizedSignal) / std::max(sigma, kNeuralAdaptiveSigmaFloor);
    return neuralAdaptiveNoiseEvidenceFromSnr(snr);
}

// Preserve the Phase-6 adaptive response exactly, then apply the Phase-9 evidence envelope.
// This makes the change strictly downward-only versus the previous writeback: evidence=1 leaves
// the existing response untouched, while SNR >= 8 forces exact identity.
inline float neuralAdaptiveAuthorityScale(
        float normalizedSignal,
        float sigma,
        float adaptiveResponse) noexcept {
    if (!std::isfinite(normalizedSignal) || !std::isfinite(sigma) || sigma <= 0.0f) {
        return 0.0f;
    }
    const float evidence = neuralAdaptiveNoiseEvidence(normalizedSignal, sigma);
    const float response = std::isfinite(adaptiveResponse)
            ? std::clamp(adaptiveResponse, 0.0f, 1.0f) : 0.0f;
    const float inverseSnrFraction = sigma /
            (std::abs(normalizedSignal) + sigma + kNeuralAdaptiveSigmaFloor);
    const float phase6Scale = (1.0f - response) +
            response * std::sqrt(std::clamp(inverseSnrFraction, 0.0f, 1.0f));
    return evidence * phase6Scale;
}

} // namespace bncam::spectra::neural
