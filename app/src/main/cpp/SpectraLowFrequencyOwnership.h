#pragma once

#include <algorithm>
#include <cmath>

namespace bncam::spectra2 {

// One owner per low-frequency opponent-chroma band.
// Pass 3 already operates in the sensor/CFA pipeline before demosaic. Once its
// low-frequency candidate survives No-Regret, a second post-demosaic/pre-WB
// spatial cloud correction would process the same broad residual twice and can
// turn legitimate scene colour fields into green/magenta shading.
inline bool pass3OwnsLowFrequencyChroma(
        bool spectraProcessingActive,
        bool pass3Applied,
        bool pass3LowFrequencyEnabled,
        float pass3NoRegretMeanAcceptance
) {
    const float acceptance = std::isfinite(pass3NoRegretMeanAcceptance)
            ? std::clamp(pass3NoRegretMeanAcceptance, 0.0f, 1.0f)
            : 0.0f;
    return spectraProcessingActive &&
            pass3Applied &&
            pass3LowFrequencyEnabled &&
            acceptance >= 0.02f;
}

}  // namespace bncam::spectra2
