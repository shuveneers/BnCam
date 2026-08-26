#include "../../main/cpp/SpectraVisibleChromaCoarseGuide.h"

#include <cassert>
#include <cmath>
#include <iostream>
#include <random>

int main() {
    using bncam::spectra2::resolveVisibleChromaCoarseGuide;

    const auto weakNoise = resolveVisibleChromaCoarseGuide(0.9f, 0.05f, 0.8f, 0.4f, 0.6f, 0.12f, 1.3f);
    assert(weakNoise.blend < 0.02f);

    const auto darkBlotch = resolveVisibleChromaCoarseGuide(0.9f, 1.8f, 0.8f, 0.45f, 0.7f, 0.10f, 1.4f);
    assert(darkBlotch.blend > 0.20f);

    const auto coherentEdge = resolveVisibleChromaCoarseGuide(0.9f, 1.8f, 0.8f, 3.0f, 4.0f, 0.25f, 1.4f);
    assert(coherentEdge.blend < darkBlotch.blend * 0.25f);

    const auto zeroAuthority = resolveVisibleChromaCoarseGuide(0.0f, 5.0f, 1.0f, 0.0f, 0.0f, 0.0f, 2.0f);
    assert(zeroAuthority.blend == 0.0f);

    std::mt19937 rng(0x434f4152u);
    std::uniform_real_distribution<float> any(-3.0f, 8.0f);
    for (int i = 0; i < 200000; ++i) {
        const auto d = resolveVisibleChromaCoarseGuide(
                any(rng), any(rng), any(rng), any(rng), any(rng), any(rng), any(rng));
        assert(std::isfinite(d.noiseNeed));
        assert(std::isfinite(d.supportWeight));
        assert(std::isfinite(d.contextRelease));
        assert(std::isfinite(d.shadowContext));
        assert(std::isfinite(d.blend));
        assert(d.noiseNeed >= 0.0f && d.noiseNeed <= 1.0f);
        assert(d.supportWeight >= 0.0f && d.supportWeight <= 1.0f);
        assert(d.contextRelease >= 0.0f && d.contextRelease <= 1.0f);
        assert(d.shadowContext >= 0.45f && d.shadowContext <= 1.0f);
        assert(d.blend >= 0.0f && d.blend <= 0.65f);
    }

    std::cout << "SPECTRA_VISIBLE_CHROMA_COARSE_GUIDE_TESTS_OK\n";
    return 0;
}
