#pragma once

#include <algorithm>
#include <cmath>

namespace bncam::spectra::neural {

// Post-physical Neural authority contract.
//
// Neural receives an already denoised Bayer image. Its sigma is therefore the measured remaining
// post-physical noise budget, not the original sensor noise prediction. The former 2..8 SNR
// envelope classified almost every normal post-physical sample as exact identity (device telemetry
// showed ~98% at SNR>=8), so the add-on could not materially clean residual shadow/chroma noise.
// Keep the gate sensor-independent and dimensionless, but move it to the residual-noise regime:
// full authority through SNR 4, smooth attenuation to exact identity at SNR 48. Student residual,
// confidence/posterior protection and user Luma/Chroma/Detail controls remain independent gates.
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

// Use the residual SNR envelope exactly once. Adaptive Response is production-fixed at 100% and
// retained only for ABI/source compatibility. No inverse-SNR multiplier or Dynamic-ISO coefficient
// is applied here; Dynamic ISO already affected the physical baseline through effective S/O.
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
