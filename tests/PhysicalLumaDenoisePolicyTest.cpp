#include "PhysicalLumaDenoisePolicy.h"
#include "SpectraPostDemosaicResidualNr.h"

#include <cassert>
#include <cmath>
#include <iostream>

int main() {
    using bncam::luma_nr::resolveResidualLumaPlan;
    using bncam::spectra2::resolvePostDemosaicResidualNr;

    {
        const auto p = resolveResidualLumaPlan(0.01f, 1.0f, false);
        assert(!p.active);
    }
    {
        const auto p = resolveResidualLumaPlan(0.01f, 0.05f, true);
        assert(!p.active);
    }
    {
        const auto p = resolveResidualLumaPlan(0.01f, 1.0f, true);
        assert(p.active);
        assert(p.baselineFraction >= 0.45f && p.baselineFraction <= 0.64f);
        assert(std::abs(p.targetSigma - p.inputResidualSigma * p.baselineFraction) < 1e-7f);
    }
    {
        // Phase-5 positive exposure covariance propagation must be scale-covariant:
        // doubling propagated residual sigma doubles the physical luma target until clamp.
        const auto a = resolveResidualLumaPlan(0.010f, 0.9f, true);
        const auto b = resolveResidualLumaPlan(0.020f, 0.9f, true);
        assert(a.active && b.active);
        assert(std::abs(b.targetSigma / a.targetSigma - 2.0f) < 1e-5f);
    }
    {
        const auto off = resolvePostDemosaicResidualNr(
            0.02f, 0.03f, 0.9f,
            0.0f, 0.0f, 0.0f,
            1.0f, 1.0f, true, false);
        const auto on = resolvePostDemosaicResidualNr(
            0.02f, 0.03f, 0.9f,
            0.0f, 0.0f, 0.0f,
            1.0f, 1.0f, true, true);
        assert(off.active && on.active);
        assert(off.physicalBaselineActive && on.physicalBaselineActive);
        assert(on.lumaSigma >= off.lumaSigma);
        assert(off.baselineLumaFraction == on.baselineLumaFraction);
        assert(off.baselineChromaFraction == on.baselineChromaFraction);
    }

    std::cout << "Phase-6 propagated residual luma policy tests: PASS\n";
    return 0;
}
