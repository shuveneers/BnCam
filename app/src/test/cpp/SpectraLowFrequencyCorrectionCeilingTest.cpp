#include "../../main/cpp/SpectraLowFrequencyCorrectionCeiling.h"

#include <cassert>
#include <cmath>
#include <iostream>

using bncam::spectra2::resolveLowFrequencyCorrectionCeiling;

int main() {
    // Approximate the prior device case: sigma around 0.0047, strong coherent field.
    const auto strong = resolveLowFrequencyCorrectionCeiling(0.0047f, 0.030f, 0.9188f);
    assert(strong.perFieldCeiling > 0.0075f);
    assert(strong.perFieldCeiling <= 0.0090f * 0.9188f + 1e-7f);
    assert(strong.totalPassCeiling >= strong.perFieldCeiling);

    // Small fields remain field-limited, not inflated to the physical ceiling.
    const auto small = resolveLowFrequencyCorrectionCeiling(0.0047f, 0.0020f, 1.0f);
    assert(std::abs(small.perFieldCeiling - 0.0011f) < 1e-6f);

    // Low-noise scenes retain a tighter physical ceiling.
    const auto clean = resolveLowFrequencyCorrectionCeiling(0.0005f, 0.030f, 1.0f);
    assert(clean.perFieldCeiling < 0.0012f);

    const auto invalid = resolveLowFrequencyCorrectionCeiling(0.0f, 0.030f, 1.0f);
    assert(invalid.perFieldCeiling == 0.0f);
    assert(invalid.totalPassCeiling == 0.0f);

    std::cout << "SpectraLowFrequencyCorrectionCeilingTest passed\n";
    return 0;
}
