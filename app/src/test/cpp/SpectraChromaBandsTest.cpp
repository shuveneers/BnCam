#include "../../main/cpp/SpectraChromaBands.h"

#include <cassert>
#include <cmath>
#include <iostream>
#include <limits>

using bncam::spectra2::ChromaBandKind;
using bncam::spectra2::resolveChromaBandPlan;

int main() {
    const auto fineLow = resolveChromaBandPlan(
            ChromaBandKind::Fine, 1.01e-4f, 1.0e-4f, 1.0f, 0.9f, 0.0f);
    assert(!fineLow.enabled);
    assert(fineLow.authorityScale == 0.0f);

    const auto fineModerate = resolveChromaBandPlan(
            ChromaBandKind::Fine, 2.0e-4f, 1.0e-4f, 1.4f, 0.9f, 0.7f);
    const auto fineHigh = resolveChromaBandPlan(
            ChromaBandKind::Fine, 8.0e-4f, 1.0e-4f, 2.2f, 0.9f, 1.2f);
    assert(fineModerate.enabled);
    assert(fineHigh.enabled);
    assert(fineHigh.authorityScale >= fineModerate.authorityScale);
    assert(fineHigh.authorityScale <= 0.90f);
    assert(fineHigh.maximumCorrectionScale <= 1.0f);

    const auto mid = resolveChromaBandPlan(
            ChromaBandKind::Mid, 2.5e-4f, 1.0e-4f, 2.0f, 0.8f, 0.9f);
    assert(mid.enabled);
    assert(mid.authorityScale <= 0.76f);
    assert(mid.kernel.find("ATROUS_RESIDUAL_RADIUS2_TO6") != std::string::npos);

    const auto lowDirectional = resolveChromaBandPlan(
            ChromaBandKind::Low, 1.7e-5f, 1.0e-4f, 1.0f, 0.8f, 0.5f, 0.5f);
    assert(lowDirectional.enabled);
    assert(lowDirectional.evidence == 0.5f);
    assert(lowDirectional.authorityScale <= 0.64f);

    const auto invalid = resolveChromaBandPlan(
            ChromaBandKind::Fine,
            std::numeric_limits<float>::quiet_NaN(),
            std::numeric_limits<float>::infinity(),
            std::numeric_limits<float>::quiet_NaN(),
            -1.0f,
            std::numeric_limits<float>::infinity());
    assert(!invalid.enabled);
    assert(std::isfinite(invalid.inputEnergy));
    assert(std::isfinite(invalid.targetFloor));
    assert(std::isfinite(invalid.visibleChromaAmplification));

    assert(std::abs(bncam::spectra2::reductionPercentage(4.0f, 3.0f) - 25.0f) < 1e-5f);
    std::cout << "SPECTRA_CHROMA_BANDS_TESTS_OK\n";
    return 0;
}
