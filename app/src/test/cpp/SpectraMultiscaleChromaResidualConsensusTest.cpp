#include "../../main/cpp/SpectraMultiscaleChromaResidualConsensus.h"

#include <cassert>
#include <cmath>
#include <iostream>

using bncam::spectra2::resolveMultiscaleChromaResidualConsensus;

int main() {
    // Flat stochastic outlier: mid/coarse agree and local spread is noise-like.
    const auto noisy = resolveMultiscaleChromaResidualConsensus(
            0.020f, 0.012f, 0.010f, 0.0105f, 0.005f, 0.004f, 1.0f, 0.8f);
    assert(noisy.supported);
    assert(noisy.contextMix > 0.20f);

    // Cross-scale disagreement must not drag the local target.
    const auto disagree = resolveMultiscaleChromaResidualConsensus(
            0.020f, 0.012f, 0.002f, 0.025f, 0.005f, 0.004f, 1.0f, 0.8f);
    assert(disagree.contextMix < 0.05f);

    // Real local chroma structure: high local spread blocks broad consensus.
    const auto colourEdge = resolveMultiscaleChromaResidualConsensus(
            0.020f, 0.012f, 0.010f, 0.0105f, 0.030f, 0.004f, 1.0f, 0.8f);
    assert(colourEdge.contextMix < 0.05f);

    // Green/scene structure protection remains authoritative.
    const auto sceneEdge = resolveMultiscaleChromaResidualConsensus(
            0.020f, 0.012f, 0.010f, 0.0105f, 0.005f, 0.004f, 0.05f, 0.8f);
    assert(!sceneEdge.supported);

    std::cout << "SpectraMultiscaleChromaResidualConsensusTest passed\n";
    return 0;
}
