#include "../../main/cpp/SpectraChromaMultiscale.h"

#include <cassert>
#include <cmath>
#include <iostream>
#include <limits>

using bncam::spectra2::ChromaBandKind;
using bncam::spectra2::resolveBandKernelDecision;
using bncam::spectra2::resolveLowBandFieldDecision;
using bncam::spectra2::resolveMidBandCoefficient;

int main() {
    const auto fineNoise = resolveBandKernelDecision(
            ChromaBandKind::Fine,
            0.0010f,
            0.0010f,
            0.8f,
            0.9f,
            1.0f,
            1.0f,
            1.5f
    );
    const auto fineDetail = resolveBandKernelDecision(
            ChromaBandKind::Fine,
            0.0080f,
            0.0010f,
            0.8f,
            0.9f,
            1.0f,
            1.0f,
            1.5f
    );
    assert(fineNoise.supported);
    assert(fineNoise.shrinkage > fineDetail.shrinkage);
    assert(fineNoise.shrinkage <= 0.92f);
    assert(fineDetail.retainedFraction > fineNoise.retainedFraction);

    const auto constantMid = resolveMidBandCoefficient(0.004f, 0.004f, 0.004f, 0.004f);
    assert(std::abs(constantMid.coefficient) < 1.0e-9f);
    const auto localMid = resolveMidBandCoefficient(0.006f, 0.005f, 0.001f, 0.001f);
    assert(localMid.coefficient > 0.0f);
    const auto invalidMid = resolveMidBandCoefficient(
            std::numeric_limits<float>::quiet_NaN(), 0.0f, 0.0f, 0.0f);
    assert(invalidMid.coefficient == 0.0f);

    const auto protectedEdge = resolveBandKernelDecision(
            ChromaBandKind::Mid,
            0.0010f,
            0.0010f,
            0.8f,
            0.9f,
            0.005f,
            1.0f,
            2.0f
    );
    assert(!protectedEdge.supported);
    assert(protectedEdge.correction == 0.0f);

    const auto impulse = resolveBandKernelDecision(
            ChromaBandKind::Fine,
            0.0060f,
            0.0010f,
            0.9f,
            1.0f,
            1.0f,
            1.0f,
            2.0f,
            3.0f
    );
    assert(impulse.shrinkage >= 0.60f);

    double syntheticBefore = 0.0;
    double syntheticAfter = 0.0;
    for (int i = -12; i <= 12; ++i) {
        const float coefficient = static_cast<float>(i) * 0.00018f;
        const auto decision = resolveBandKernelDecision(
                ChromaBandKind::Mid, coefficient, 0.0012f,
                0.65f, 0.85f, 0.95f, 1.0f, 1.8f);
        const float after = coefficient + decision.correction;
        assert(std::isfinite(after));
        assert(std::abs(after) <= std::abs(coefficient) + 1.0e-9f);
        if (coefficient != 0.0f) assert(after * coefficient >= 0.0f);
        syntheticBefore += static_cast<double>(coefficient) * coefficient;
        syntheticAfter += static_cast<double>(after) * after;
    }
    assert(syntheticAfter < syntheticBefore);

    const auto lowAuthority = resolveBandKernelDecision(
            ChromaBandKind::Fine, 0.001f, 0.001f,
            0.25f, 0.9f, 1.0f, 1.0f, 1.5f);
    const auto highAuthority = resolveBandKernelDecision(
            ChromaBandKind::Fine, 0.001f, 0.001f,
            0.75f, 0.9f, 1.0f, 1.0f, 1.5f);
    assert(highAuthority.shrinkage > lowAuthority.shrinkage);

    const auto lowBelow = resolveLowBandFieldDecision(
            0.0001f, 0.0020f, 0.6f, 0.9f, 1.0f, 1.0f, 1.0f);
    assert(!lowBelow.supported);
    const auto lowSupported = resolveLowBandFieldDecision(
            0.0020f, 0.0020f, 0.6f, 0.9f, 1.0f, 1.0f, 2.0f);
    assert(lowSupported.supported);
    assert(std::abs(lowSupported.correction) < 0.0020f);
    assert(lowSupported.shrinkage <= 0.68f);

    const auto invalid = resolveBandKernelDecision(
            ChromaBandKind::Fine,
            std::numeric_limits<float>::quiet_NaN(),
            std::numeric_limits<float>::infinity(),
            2.0f,
            -1.0f,
            std::numeric_limits<float>::quiet_NaN(),
            1.0f,
            std::numeric_limits<float>::infinity()
    );
    assert(!invalid.supported);
    assert(std::isfinite(invalid.coefficient));
    assert(std::isfinite(invalid.correction));

    std::cout << "SPECTRA_CHROMA_MULTISCALE_TESTS_OK\n";
    return 0;
}
