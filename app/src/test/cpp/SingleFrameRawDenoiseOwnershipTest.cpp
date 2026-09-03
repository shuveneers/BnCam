#include "../../main/cpp/SingleFrameRawDenoisePolicy.h"
#include "../../main/cpp/SpectraPhysicalBaselineNr.h"

#include <cassert>
#include <cmath>

int main() {
    using bncam::singleframe::resolveRawDenoisePlan;
    using bncam::spectra2::resolvePhysicalPreToneChroma;

    const auto luma = resolveRawDenoisePlan(1.8e-4f, 1.0f, 1.0f);
    assert(luma.active);
    assert(luma.lumaAuthority > 0.80f);

    // Pre-tone chroma remains a separate owner. Upstream chroma reduction changes chroma
    // headroom but cannot alter the luma-only single-frame plan because luma has no chroma input.
    const auto pre0 = resolvePhysicalPreToneChroma(true, true, false, 0.8f, 0.5f, 0.0f);
    const auto pre60 = resolvePhysicalPreToneChroma(true, true, false, 0.8f, 0.5f, 0.60f);
    assert(pre0.enabled && pre60.enabled);
    assert(pre60.baselineStrength < pre0.baselineStrength);
    assert(pre60.residualHeadroom < pre0.residualHeadroom);

    const auto lumaAgain = resolveRawDenoisePlan(1.8e-4f, 1.0f, 1.0f);
    assert(std::abs(lumaAgain.lumaAuthority - luma.lumaAuthority) < 1.0e-7f);
    assert(std::abs(lumaAgain.blendStrength - luma.blendStrength) < 1.0e-7f);
    return 0;
}
