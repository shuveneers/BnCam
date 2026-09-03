#include "../../main/cpp/SpectraPhysicalBaselineNr.h"

#include <cassert>
#include <cmath>

int main() {
    using bncam::spectra2::resolvePhysicalPreToneChroma;

    const auto unavailable = resolvePhysicalPreToneChroma(true, false, false, 1.0f, 1.0f);
    assert(!unavailable.enabled);

    const auto yuv = resolvePhysicalPreToneChroma(false, true, false, 1.0f, 1.0f);
    assert(!yuv.enabled);

    const auto mainOff = resolvePhysicalPreToneChroma(true, true, false, 0.902118f, 1.0f);
    assert(mainOff.enabled);
    assert(mainOff.physicalBaselineActive);
    assert(!mainOff.spectraEnhancementActive);
    assert(std::fabs(mainOff.finalStrength - 0.86f) < 1.0e-5f);

    const auto mainOn = resolvePhysicalPreToneChroma(true, true, true, 0.902118f, 1.0f);
    assert(mainOn.spectraEnhancementActive);
    assert(mainOn.finalStrength > mainOff.finalStrength);
    assert(mainOn.finalStrength <= 0.94f);

    const auto teleOff = resolvePhysicalPreToneChroma(true, true, false, 1.0f, 1.0f);
    assert(std::fabs(teleOff.finalStrength - 0.86f) < 1.0e-5f);

    const auto teleOn = resolvePhysicalPreToneChroma(true, true, true, 1.0f, 1.0f);
    assert(std::fabs(teleOn.finalStrength - 0.94f) < 1.0e-5f);
    return 0;
}
