#include "../../main/cpp/SpectraVisibleChromaFastMath.h"

#include <cassert>
#include <cmath>
#include <iostream>

using namespace bncam::spectra2;

int main() {
    float maximumAbsoluteError = 0.0f;
    for (int index = 0; index <= 200000; ++index) {
        const float squaredDistance = 32.0f * static_cast<float>(index) / 200000.0f;
        const float expected = std::exp(-0.5f * squaredDistance);
        const float actual = fastVisibleGaussianWeight(squaredDistance);
        maximumAbsoluteError = std::max(maximumAbsoluteError, std::abs(actual - expected));
    }
    assert(maximumAbsoluteError <= kVisibleGaussianWeightLutMaximumAbsoluteError);
    assert(fastVisibleGaussianWeight(-1.0f) == 1.0f);
    assert(fastVisibleGaussianWeight(std::numeric_limits<float>::infinity()) == 0.0f);
    assert(fastVisibleGaussianWeight(64.0f) == 0.0f);
    assert(kVisibleNeighbourOffsets.size() == 24u);

    float spatialSum = 1.0f; // hoisted center sample
    for (const auto& offset : kVisibleNeighbourOffsets) {
        assert(offset.dx != 0 || offset.dy != 0);
        spatialSum += offset.spatialWeight;
    }
    assert(std::abs(spatialSum - 7.48f) <= 1.0e-5f);

    std::cout << "SPECTRA_VISIBLE_CHROMA_FAST_MATH_TESTS_OK maxError="
              << maximumAbsoluteError << "\n";
    return 0;
}
