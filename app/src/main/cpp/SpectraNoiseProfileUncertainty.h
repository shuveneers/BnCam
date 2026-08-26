#pragma once

#include <algorithm>
#include <cmath>

namespace bncam::spectra2 {

// Robustness envelope for an imperfect sensor-noise profile.
// 1.0 means high confidence in S/O. Lower confidence widens the noise band used
// by context classifiers, so near-floor random variation is less likely to be
// protected as scene structure. This affects classification only; it does not
// silently scale the physical S/O calibration or the user's SPECTRA strength.
inline float resolveNoiseProfileUncertaintyEnvelope(float modelConfidence) {
    const float confidence = std::clamp(
            std::isfinite(modelConfidence) ? modelConfidence : 0.0f,
            0.0f, 1.0f);
    return 1.0f + 0.30f * (1.0f - confidence);
}

// Confidence describes how tightly the propagated variance agrees with compact measurements;
// it is not a denoise-opacity slider. Moderate disagreement should widen the classifier's
// uncertainty band, not linearly disable a safe context correction. Keep a hard gate at the
// caller for truly unusable confidence, then use this bounded soft factor for authority.
inline float resolveNoiseProfileAuthorityConfidence(float modelConfidence) {
    const float confidence = std::clamp(
            std::isfinite(modelConfidence) ? modelConfidence : 0.0f,
            0.0f, 1.0f);
    return 0.55f + 0.45f * std::sqrt(confidence);
}

}  // namespace bncam::spectra2
