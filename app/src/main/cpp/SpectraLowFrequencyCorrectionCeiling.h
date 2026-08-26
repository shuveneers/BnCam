#pragma once

#include <algorithm>
#include <cmath>

namespace bncam::spectra2 {

struct LowFrequencyCorrectionCeiling {
    float physicalSigmaCeiling = 0.0f;
    float fieldFractionCeiling = 0.0f;
    float perFieldCeiling = 0.0f;
    float totalPassCeiling = 0.0f;
};

// Low-frequency chroma clouds are coherent fields, not single-pixel impulses.
// Their safe correction ceiling therefore scales with both the physical noise
// floor and the measured field magnitude. The old fixed 0.0048 ceiling clipped
// physically-supported high-ISO corrections before No-Regret could evaluate
// them. This policy raises that ceiling only when BOTH sources support it.
inline LowFrequencyCorrectionCeiling resolveLowFrequencyCorrectionCeiling(
        float noiseSigma,
        float fieldCoefficient,
        float maximumCorrectionScale) {
    LowFrequencyCorrectionCeiling out{};
    const float sigma = std::isfinite(noiseSigma) ? std::max(0.0f, noiseSigma) : 0.0f;
    const float field = std::isfinite(fieldCoefficient) ? std::abs(fieldCoefficient) : 0.0f;
    const float scale = std::isfinite(maximumCorrectionScale)
            ? std::clamp(maximumCorrectionScale, 0.0f, 1.0f)
            : 0.0f;
    if (sigma <= 0.0f || field <= 0.0f || scale <= 0.0f) {
        return out;
    }

    // Enough headroom for strong physically-supported cloud fields, while a
    // hard 0.009 normalized-RAW ceiling prevents one pass from making a large
    // colour jump. Field fraction additionally prevents correcting more than
    // 55% of the coherent estimate in one pass.
    out.physicalSigmaCeiling = std::clamp(2.15f * sigma, 0.0005f, 0.0090f);
    out.fieldFractionCeiling = std::min(0.0090f, 0.55f * field);
    out.perFieldCeiling = std::min(
            out.physicalSigmaCeiling,
            out.fieldFractionCeiling) * scale;

    // Row/column corrections historically shared a 0.0055 pass clamp. Preserve
    // that baseline, but do not let it re-cap a low-frequency field that has a
    // larger physically-derived per-field ceiling.
    out.totalPassCeiling = std::max(0.0055f * scale, out.perFieldCeiling);
    return out;
}

}  // namespace bncam::spectra2
