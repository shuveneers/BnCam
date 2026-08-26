#include "../../main/cpp/SpectraVisibleChroma.h"

#include <cassert>
#include <cmath>
#include <iostream>

using namespace bncam::spectra2;

int main() {
    const auto plan = buildVisibleChromaPlan(
            true,
            3.85e-6f,
            5.339e-6f,
            5.13e-7f,
            0.426384f,
            0.40f,
            0.153785f,
            0.65f
    );
    assert(plan.enabled);
    const auto covariance = makeOpponentCovariance(
            plan.predictedVarianceRG,
            plan.predictedVarianceBG,
            plan.predictedCovarianceRgBg
    );
    assert(covariance.valid);

    // Flat, well-supported local chroma deviation around the physical noise scale.
    // The safety gate should remain permissive; amplitude is already scaled once by noiseNeed.
    const auto flatNoise = resolveVisibleChromaDecision(
            plan, covariance,
            0.0038f, -0.0034f,
            0.0004f, -0.0003f,
            1.0f,
            0.35f, 0.45f,
            1.2f, 1.4f,
            0.16f, 0.18f,
            0.90f
    );
    assert(flatNoise.acceptance > 0.45f);
    assert(flatNoise.correctionMagnitude > 1.0e-6f);
    assert(flatNoise.correctionMagnitude <= plan.maximumCorrection + 1.0e-6f);
    assert(flatNoise.mahalanobisAfter <= flatNoise.mahalanobisBefore + 1.0e-5f);

    // A hard coherent edge must still receive substantially less authority.
    const auto hardEdge = resolveVisibleChromaDecision(
            plan, covariance,
            0.0038f, -0.0034f,
            0.0004f, -0.0003f,
            1.0f,
            2.2f, 3.0f,
            8.5f, 8.5f,
            0.45f, 0.55f,
            0.90f
    );
    assert(hardEdge.acceptance < flatNoise.acceptance * 0.35f);

    std::cout << "SPECTRA_VISIBLE_CHROMA_SINGLE_NOISE_GATE_TESTS_OK\n";
    return 0;
}
