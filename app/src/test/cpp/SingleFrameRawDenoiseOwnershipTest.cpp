#include "../../main/cpp/SpectraPhysicalBaselineNr.h"
#include <cassert>

int main() {
    using bncam::spectra2::resolvePhysicalBaselineNr;
    using bncam::spectra2::resolvePhysicalPreToneChroma;

    const auto pre0 = resolvePhysicalPreToneChroma(true, true, false, 0.8f, 0.5f, 0.0f);
    const auto pre60 = resolvePhysicalPreToneChroma(true, true, false, 0.8f, 0.5f, 0.60f);
    assert(pre0.enabled && pre60.enabled);
    assert(pre60.baselineStrength < pre0.baselineStrength);
    assert(pre60.residualHeadroom < pre0.residualHeadroom);

    const auto post0 = resolvePhysicalBaselineNr(0.01f, 0.02f, true, false, 0.0f, 0.0f, pre0.finalStrength);
    const auto postReduced = resolvePhysicalBaselineNr(0.01f, 0.02f, true, false, 0.55f, 0.60f, pre60.finalStrength);
    assert(post0.active && postReduced.active);
    assert(postReduced.lumaFraction < post0.lumaFraction);
    assert(postReduced.chromaFraction < post0.chromaFraction);
    assert(postReduced.lumaFraction >= 0.24f);
    assert(postReduced.chromaFraction >= 0.34f);
    return 0;
}
