#include "../../main/cpp/SpectraMultiscaleContext.h"

#include <cassert>
#include <cmath>
#include <iostream>
#include <random>

int main() {
    using bncam::spectra2::resolveMultiscaleContext;

    const auto flat = resolveMultiscaleContext(0.9f, 0.7f, 0.65f, 1.0f);
    assert(flat.edgeProtection < 0.10f);
    assert(flat.flatContext > 0.80f);
    assert(flat.denoiseAuthorityScale > 1.0f);

    const auto coherentEdge = resolveMultiscaleContext(6.0f, 5.5f, 0.65f, 1.0f);
    assert(coherentEdge.edgeProtection > 0.85f);
    assert(coherentEdge.denoiseAuthorityScale < 0.55f);

    const auto fineOnly = resolveMultiscaleContext(4.0f, 0.8f, 0.85f, 1.0f);
    assert(fineOnly.stochasticFineEvidence > 3.0f);
    assert(fineOnly.edgeProtection < coherentEdge.edgeProtection);
    assert(fineOnly.denoiseAuthorityScale > coherentEdge.denoiseAuthorityScale);


    const auto uncertainNearFloor = resolveMultiscaleContext(2.5f, 2.3f, 0.75f, 0.20f);
    const auto confidentNearFloor = resolveMultiscaleContext(2.5f, 2.3f, 0.75f, 1.00f);
    assert(uncertainNearFloor.edgeProtection <= confidentNearFloor.edgeProtection + 1.0e-6f);
    assert(uncertainNearFloor.denoiseAuthorityScale >= confidentNearFloor.denoiseAuthorityScale - 1.0e-6f);
    const auto uncertainStrongEdge = resolveMultiscaleContext(8.0f, 7.5f, 0.75f, 0.20f);
    assert(uncertainStrongEdge.edgeProtection > 0.65f);

    const auto lowPressure = resolveMultiscaleContext(0.8f, 0.7f, 0.05f, 1.0f);
    const auto highPressure = resolveMultiscaleContext(0.8f, 0.7f, 0.95f, 1.0f);
    assert(highPressure.denoiseAuthorityScale >= lowPressure.denoiseAuthorityScale);
    assert(highPressure.broadMixScale >= lowPressure.broadMixScale);

    // Fuzz boundedness/finite invariants.
    std::mt19937 rng(0x53435054u);
    std::uniform_real_distribution<float> z(-4.0f, 12.0f);
    std::uniform_real_distribution<float> unit(-1.0f, 2.0f);
    for (int i = 0; i < 200000; ++i) {
        const auto d = resolveMultiscaleContext(z(rng), z(rng), unit(rng), unit(rng));
        assert(std::isfinite(d.coherentStructureZ));
        assert(std::isfinite(d.stochasticFineEvidence));
        assert(std::isfinite(d.edgeProtection));
        assert(std::isfinite(d.flatContext));
        assert(std::isfinite(d.denoiseAuthorityScale));
        assert(std::isfinite(d.broadMixScale));
        assert(d.edgeProtection >= 0.0f && d.edgeProtection <= 1.0f);
        assert(d.flatContext >= 0.0f && d.flatContext <= 1.0f);
        assert(d.denoiseAuthorityScale >= 0.22f && d.denoiseAuthorityScale <= 1.32f);
        assert(d.broadMixScale >= 0.72f && d.broadMixScale <= 1.28f);
    }

    std::cout << "SPECTRA_MULTISCALE_CONTEXT_TESTS_OK\n";
    return 0;
}
