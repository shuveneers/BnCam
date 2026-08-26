#pragma once

#include <algorithm>
#include <cmath>

namespace bncam::spectra2 {

struct BudgetedDenoiseStrength {
    float physicalBaseline = 0.0f;
    float requestedAdditional = 0.0f;
    float budgetedAdditional = 0.0f;
    float authority = 0.0f;
    float finalStrength = 0.0f;
    bool physicalBaselinePreserved = true;
};

/**
 * Closed-loop budget authority owns only optional headroom above the physical sensor baseline.
 * It may prevent downstream over-processing after upstream SPECTRA work, but it must never make
 * the post-demosaic denoise weaker than the Camera2/manual S/O model says is physically present.
 */
inline BudgetedDenoiseStrength resolveBudgetedDenoiseStrength(
        float physicalBaseline,
        float requestedAdditional,
        float downstreamAuthority,
        float minimumStrength,
        float maximumStrength) noexcept {
    BudgetedDenoiseStrength out{};
    const float lo = std::min(minimumStrength, maximumStrength);
    const float hi = std::max(minimumStrength, maximumStrength);
    const float baseline = std::clamp(
            std::isfinite(physicalBaseline) ? physicalBaseline : lo,
            lo,
            hi);
    const float headroom = std::max(0.0f, hi - baseline);
    const float additional = std::clamp(
            std::isfinite(requestedAdditional) ? requestedAdditional : 0.0f,
            0.0f,
            headroom);
    const float authority = std::clamp(
            std::isfinite(downstreamAuthority) ? downstreamAuthority : 0.0f,
            0.0f,
            1.0f);

    out.physicalBaseline = baseline;
    out.requestedAdditional = additional;
    out.authority = authority;
    out.budgetedAdditional = additional * authority;
    out.finalStrength = std::clamp(baseline + out.budgetedAdditional, lo, hi);
    out.physicalBaselinePreserved = out.finalStrength + 1.0e-7f >= baseline;
    return out;
}

} // namespace bncam::spectra2
