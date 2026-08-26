#include "../../main/cpp/SpectraChromaMultiscale.h"

#include <cassert>
#include <cmath>
#include <iostream>

using namespace bncam::spectra2;

int main() {
    const auto high = resolveBandKernelDecision(
            ChromaBandKind::Fine, 0.0030f, 0.0030f, 0.8f, 1.0f, 1.0f, 1.0f, 1.0f);
    const auto medium = resolveBandKernelDecision(
            ChromaBandKind::Fine, 0.0030f, 0.0030f, 0.8f, 0.426f, 1.0f, 1.0f, 1.0f);
    assert(high.supported && medium.supported);
    assert(std::abs(medium.correction) > 0.75f * std::abs(high.correction));

    const auto invalid = resolveBandKernelDecision(
            ChromaBandKind::Fine, 0.0030f, 0.0030f, 0.8f, 0.09f, 1.0f, 1.0f, 1.0f);
    assert(!invalid.supported);

    const auto mid = resolveBandKernelDecision(
            ChromaBandKind::Mid, 0.0020f, 0.0030f, 0.7f, 0.50f, 1.0f, 1.0f, 1.0f);
    assert(mid.supported);

    std::cout << "SpectraChromaBandConfidenceSemanticsTest passed\n";
    return 0;
}
