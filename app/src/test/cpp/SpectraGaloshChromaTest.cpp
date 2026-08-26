#include "../../main/cpp/SpectraGaloshChroma.h"

#include <array>
#include <cassert>
#include <cmath>
#include <random>

int main() {
    using namespace bncam::spectra2;

    // Full-resolution pre-tone opponent representation is reversible and keeps
    // BT.709 scene-linear luminance exact, including highlight headroom > 1.0.
    std::mt19937 opponentRng(0x444u);
    std::uniform_real_distribution<float> sceneLinear(0.0f, 3.5f);
    for (int i = 0; i < 200000; ++i) {
        const std::array<float, 3> src{sceneLinear(opponentRng), sceneLinear(opponentRng), sceneLinear(opponentRng)};
        const auto opponent = galoshOpponent444(src[0], src[1], src[2]);
        const auto dst = galoshOpponent444ToRgb(opponent);
        for (int ch = 0; ch < 3; ++ch) assert(std::abs(src[ch] - dst[ch]) < 2.0e-6f);
        assert(std::abs(galoshBt709Luma(dst) - opponent.y) < 2.0e-6f);
    }

    // Exact transform/inverse identity: enabling the representation by itself
    // must not move colour or luminance.
    std::mt19937 rng(0x6A105u);
    std::uniform_real_distribution<float> uniform(0.0f, 1.0f);
    for (int i = 0; i < 200000; ++i) {
        const std::array<float, 4> src{uniform(rng), uniform(rng), uniform(rng), uniform(rng)};
        const auto basis = galoshWht2x2(src[0], src[1], src[2], src[3]);
        const auto dst = galoshInverseWht2x2(basis);
        for (int ch = 0; ch < 4; ++ch) assert(std::abs(src[ch] - dst[ch]) < 2.0e-6f);
        const float sourceEnergy = src[0]*src[0] + src[1]*src[1] + src[2]*src[2] + src[3]*src[3];
        const float basisEnergy = basis.luma*basis.luma + basis.c1*basis.c1 + basis.c2*basis.c2 + basis.c3*basis.c3;
        assert(std::abs(sourceEnergy - basisEnergy) < 4.0e-6f);
    }

    // Equal-luma neighbours must receive full support regardless of chroma.
    const float wSameLuma = galoshLumaGuideWeight(0.4f, 0.4f, 1e-4f, 1e-4f, 1.0f);
    assert(wSameLuma > 0.9999f);

    // Strong luminance discontinuities are rejected by the guide even though
    // chroma itself never participates in neighbour selection.
    const float wEdge = galoshLumaGuideWeight(0.2f, 0.7f, 1e-5f, 1e-5f, 1.0f);
    assert(wEdge < 1.0e-6f);

    // Lower model confidence broadens the physical-noise envelope, preventing
    // uncertain noise from being misclassified as scene structure.
    const float hi = galoshLumaGuideWeight(0.3f, 0.34f, 1e-4f, 1e-4f, 1.0f);
    const float lo = galoshLumaGuideWeight(0.3f, 0.34f, 1e-4f, 1e-4f, 0.1f);
    assert(lo >= hi);

    assert(std::abs(galoshLumaVarianceFromIndependentSensels(1.f, 2.f, 3.f, 4.f) - 2.5f) < 1e-6f);
    return 0;
}
