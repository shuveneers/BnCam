#include "../../main/cpp/PhysicalLumaDenoisePolicy.h"

#include <cassert>

int main() {
    using bncam::luma_nr::resolveResidualLumaPlan;

    const auto unavailable = resolveResidualLumaPlan(0.02f, 1.0f, false);
    assert(!unavailable.active);

    const auto weak = resolveResidualLumaPlan(0.02f, 0.05f, true);
    assert(!weak.active);

    const auto clean = resolveResidualLumaPlan(0.0025f, 1.0f, true);
    const auto noisy = resolveResidualLumaPlan(0.025f, 1.0f, true);
    assert(clean.active && noisy.active);
    assert(noisy.residualNoisePressure > clean.residualNoisePressure);
    assert(noisy.baselineFraction > clean.baselineFraction);
    assert(noisy.targetSigma > clean.targetSigma);
    assert(clean.baselineFraction <= 0.10f);
    assert(noisy.baselineFraction <= 0.78f);

    // The policy acts on propagated residual sigma only; no ISO/format input exists.
    const auto lowerConfidence = resolveResidualLumaPlan(0.025f, 0.40f, true);
    assert(lowerConfidence.active);
    assert(lowerConfidence.baselineFraction < noisy.baselineFraction);
    return 0;
}
