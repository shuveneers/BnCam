#include "../../main/cpp/SpectraDownstreamDenoisePolicy.h"

#include <cassert>
#include <cmath>
#include <iostream>

using namespace bncam::spectra2;

int main() {
    // Exact class of the 2026-08-15 device regression.
    const auto device = resolveBudgetedDenoiseStrength(
            0.0313799f,
            0.0650315f - 0.0313799f,
            0.35f,
            0.0f,
            0.22f);
    assert(device.physicalBaselinePreserved);
    assert(device.finalStrength >= 0.0313799f);
    assert(device.finalStrength > 0.0430f && device.finalStrength < 0.0433f);
    // Old math would have produced ~0.02276 and violated the physical baseline.
    assert(device.finalStrength > 0.022761f);

    const auto noHeadroomAuthority = resolveBudgetedDenoiseStrength(
            0.04f, 0.10f, 0.0f, 0.0f, 0.22f);
    assert(std::abs(noHeadroomAuthority.finalStrength - 0.04f) < 1.0e-6f);

    const auto full = resolveBudgetedDenoiseStrength(
            0.04f, 0.10f, 1.0f, 0.0f, 0.22f);
    assert(std::abs(full.finalStrength - 0.14f) < 1.0e-6f);

    const auto capped = resolveBudgetedDenoiseStrength(
            0.18f, 0.20f, 1.0f, 0.0f, 0.22f);
    assert(std::abs(capped.finalStrength - 0.22f) < 1.0e-6f);
    assert(capped.physicalBaselinePreserved);

    std::cout << "SPECTRA_DOWNSTREAM_DENOISE_POLICY_TESTS_OK\n";
    return 0;
}
