#pragma once

#include <algorithm>
#include <cmath>

namespace bncam::spectra2 {

// Post-tone chroma noise must not choose its own neighbourhood. A chroma outlier that is many
// sigma away from otherwise luma-consistent neighbours is often exactly the residual we want to
// remove. Keep chroma distance as a soft affinity only; luma similarity remains the primary
// support signal and the later colour-edge / hard-edge / No-Regret gates remain authoritative.
inline float lumaGuidedVisibleChromaAffinity(float mahalanobisSquared) {
    const float d2 = std::isfinite(mahalanobisSquared)
            ? std::max(0.0f, mahalanobisSquared)
            : 1.0e6f;
    const float legacyAffinity = 1.0f / (1.0f + 0.15f * d2);
    return 0.50f + 0.50f * legacyAffinity;
}

} // namespace bncam::spectra2
