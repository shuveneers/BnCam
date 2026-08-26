#include "../../main/cpp/SpectraMultiscaleChromaContext.h"

#include <cassert>
#include <cmath>
#include <iostream>
#include <random>

int main() {
    using bncam::spectra2::resolveMultiscaleChromaContext;

    const auto flat = resolveMultiscaleChromaContext(0.010f, 0.012f, 0.006f, 0.009f, 1.85f);
    assert(flat.structureWeight > 0.90f);

    const auto coherent = resolveMultiscaleChromaContext(0.070f, 0.010f, 0.050f, 0.009f, 1.85f);
    assert(coherent.coherentStructureEvidence > 0.70f);
    assert(coherent.structureWeight < 0.25f);

    const auto fineOnly = resolveMultiscaleChromaContext(0.070f, 0.010f, 0.008f, 0.009f, 1.85f);
    assert(fineOnly.stochasticFineEvidence > 0.65f);
    assert(fineOnly.structureWeight > coherent.structureWeight + 0.45f);


    const auto uncertainNearFloor = resolveMultiscaleChromaContext(
            0.030f, 0.010f, 0.027f, 0.010f, 1.85f, 0.20f);
    const auto confidentNearFloor = resolveMultiscaleChromaContext(
            0.030f, 0.010f, 0.027f, 0.010f, 1.85f, 1.00f);
    assert(uncertainNearFloor.structureWeight >= confidentNearFloor.structureWeight - 1.0e-6f);
    const auto uncertainStrongEdge = resolveMultiscaleChromaContext(
            0.090f, 0.010f, 0.080f, 0.010f, 1.85f, 0.20f);
    assert(uncertainStrongEdge.structureWeight < 0.35f);

    std::mt19937 rng(0x4348524fu);
    std::uniform_real_distribution<float> gradient(-0.1f, 0.2f);
    std::uniform_real_distribution<float> floor(-0.01f, 0.05f);
    std::uniform_real_distribution<float> sensitivity(-1.0f, 4.0f);
    for (int i = 0; i < 200000; ++i) {
        const auto d = resolveMultiscaleChromaContext(
                gradient(rng), floor(rng), gradient(rng), floor(rng), sensitivity(rng));
        assert(std::isfinite(d.fineStructureEvidence));
        assert(std::isfinite(d.coarseStructureEvidence));
        assert(std::isfinite(d.coherentStructureEvidence));
        assert(std::isfinite(d.stochasticFineEvidence));
        assert(std::isfinite(d.structureWeight));
        assert(d.fineStructureEvidence >= 0.0f && d.fineStructureEvidence <= 1.0f);
        assert(d.coarseStructureEvidence >= 0.0f && d.coarseStructureEvidence <= 1.0f);
        assert(d.coherentStructureEvidence >= 0.0f && d.coherentStructureEvidence <= 1.0f);
        assert(d.stochasticFineEvidence >= 0.0f && d.stochasticFineEvidence <= 1.0f);
        assert(d.structureWeight >= 0.0f && d.structureWeight <= 1.0f);
    }

    std::cout << "SPECTRA_MULTISCALE_CHROMA_CONTEXT_TESTS_OK\n";
    return 0;
}
