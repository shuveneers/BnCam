#include "SpectraAnisotropicDetail.h"

#include <cassert>
#include <cmath>
#include <iostream>

using namespace bncam::spectra2;

int main() {
    const auto flat = structureTensorFromMoments(0.0f, 0.0f, 0.0f, 1.0e-4f);
    assert(flat.valid);
    assert(!flat.confident);
    assert(flat.status == "LOW_TENSOR_CONFIDENCE_ISOTROPIC_FALLBACK");

    const auto verticalEdge = structureTensorFromMoments(0.04f, 0.0f, 0.0002f, 0.0001f);
    assert(verticalEdge.valid);
    assert(verticalEdge.confident);
    assert(verticalEdge.coherence > 0.9f);
    assert(std::abs(verticalEdge.tangentX) < 0.1f);
    assert(std::abs(std::abs(verticalEdge.tangentY) - 1.0f) < 0.1f);

    const auto along = resolveDirectionalSampleWeight(verticalEdge, 0.0f, 2.0f, 0.0f);
    const auto across = resolveDirectionalSampleWeight(verticalEdge, 2.0f, 0.0f, 0.0f);
    assert(along.weight > across.weight);
    assert(along.alongStructureSupported);
    assert(across.crossEdgeProtected);

    const auto fallbackWeight = resolveDirectionalSampleWeight(flat, 2.0f, 0.0f, 10.0f);
    assert(std::abs(fallbackWeight.weight - 1.0f) < 1.0e-6f);

    const auto directionalAuthority = resolveAnisotropicAuthority(verticalEdge, 1.0f);
    const auto fallbackAuthority = resolveAnisotropicAuthority(flat, 1.0f);
    assert(directionalAuthority.usedDirectionalAuthority);
    assert(directionalAuthority.finalScale > fallbackAuthority.finalScale);
    assert(directionalAuthority.finalScale <= 0.78f);

    assert(orientationBin(0.0f) == 0);
    assert(orientationBin(45.0f) == 1);
    assert(orientationBin(90.0f) == 2);
    assert(orientationBin(135.0f) == 3);

    const auto nonFinite = structureTensorFromMoments(NAN, 0.0f, 0.0f, 1.0f);
    assert(!nonFinite.valid);
    assert(!nonFinite.confident);

    std::cout << "SPECTRA_ANISOTROPIC_DETAIL_TESTS_OK\n";
    return 0;
}
