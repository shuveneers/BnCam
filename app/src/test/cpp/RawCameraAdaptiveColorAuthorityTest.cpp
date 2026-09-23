#include "../../main/cpp/RawCameraAdaptiveColorAuthority.h"

#include <cassert>
#include <cmath>

int main() {
    using namespace bncam::color;

    const std::array<float, 9> mainExact{
            1.14844f, -0.14844f, 0.00000f,
           -0.13281f,  1.07031f, 0.06250f,
            0.00781f, -0.78906f, 1.78125f
    };
    const std::array<float, 9> mainDng{
            1.15110f, -0.15070f, -0.00020f,
           -0.13310f,  1.07190f, 0.06120f,
            0.00810f, -0.79020f, 1.78230f
    };
    const auto coherent = resolveRawAdaptiveColorAuthority({
            true, true, false, mainExact, mainDng, 0.0905f
    });
    assert(coherent.ready);
    assert(coherent.matrixShapeDistance < 0.005f);
    assert(coherent.profileWeight > 0.99f);
    assert(coherent.exactFrameWeight < 0.01f);

    // Same-frame front debug: both routes are individually valid and neutral-preserving, but the
    // DNG post-WB matrix shape materially diverges from the exact Camera2 pair while main/tele/UW
    // are ~0.001-0.002 apart. Authority must move generically toward exact-frame color here.
    const std::array<float, 9> frontExact{
             1.96875f, -1.03125f,  0.06250f,
            -0.26563f,  1.68750f, -0.42188f,
            -0.17969f, -0.72656f,  1.91406f
    };
    const std::array<float, 9> frontDng{
             2.023355f, -0.693707f, -0.035898f,
            -0.245420f,  2.052763f, -0.513357f,
            -0.189502f, -0.717059f,  2.200828f
    };
    const auto divergent = resolveRawAdaptiveColorAuthority({
            true, true, false, frontExact, frontDng, 0.18481f
    });
    assert(divergent.ready);
    assert(divergent.matrixShapeDistance > 0.040f);
    assert(divergent.profileWeight < 0.10f);
    assert(divergent.exactFrameWeight > 0.90f);

    const float profileNeutral = adaptiveColorNeutralMean(frontDng);
    const float effectiveNeutral = adaptiveColorNeutralMean(divergent.effectivePostWbMatrix);
    assert(std::abs(profileNeutral - effectiveNeutral) < 1.0e-4f);

    const auto pairedHsm = resolveRawAdaptiveColorAuthority({
            true, true, true, frontExact, frontDng, 0.18481f
    });
    assert(pairedHsm.ready);
    assert(pairedHsm.profileWeight == 1.0f);
    assert(pairedHsm.exactFrameWeight == 0.0f);

    return 0;
}
