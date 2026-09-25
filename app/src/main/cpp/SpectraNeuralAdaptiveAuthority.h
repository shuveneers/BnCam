#pragma once

#include <algorithm>
#include <cmath>

namespace bncam::spectra::neural {

// Trained Neural authority contract.
//
// SPECTRA is the sole general RAW denoise owner when enabled. Its sigma is the same physical
// per-CFA S/O sigma used during training: sqrt(S*x + O). There is no classical baseline denoiser
// ahead of the network. Keep the gate sensor-independent and dimensionless: full authority through
// SNR 4, smooth attenuation to exact identity at SNR 48. Student residual, confidence/posterior
// protection and user Luma/Chroma/Detail controls remain independent gates.
constexpr float kNeuralAdaptiveFullEvidenceSnr = 4.0f;
constexpr float kNeuralAdaptiveIdentitySnr = 48.0f;
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

// Use the physical S/O SNR envelope exactly once. Adaptive Response is production-fixed at 100%
// and retained only for ABI/source compatibility. No inverse-SNR multiplier or Dynamic-ISO
// coefficient is applied here; Dynamic ISO, when selected, has already resolved effective S/O.
inline float neuralAdaptiveAuthorityScale(
        float normalizedSignal,
        float sigma,
        float adaptiveResponse) noexcept {
    if (!std::isfinite(normalizedSignal) || !std::isfinite(sigma) || sigma <= 0.0f) {
        return 0.0f;
    }
    (void) adaptiveResponse;
    return neuralAdaptiveNoiseEvidence(normalizedSignal, sigma);
}

} // namespace bncam::spectra::neural
