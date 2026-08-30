#include "../../main/cpp/RawResidualCfaPedestalPolicy.h"
#include "../../main/cpp/RawSceneBlackAuthorityPolicy.h"

#include <cassert>
#include <cmath>

int main() {
    using namespace bncam::raw_black;

    // Camera2 metadata remains the baseline authority, but it must not prohibit a
    // separately qualified residual correction after metadata subtraction.
    const auto metadataAuthority = resolveSceneBlackAuthority(true, false);
    assert(metadataAuthority.metadataAuthoritative);
    assert(metadataAuthority.imageDerivedMutationAllowed);

    // Reproduce the measured failure shape: both green sites share a large,
    // spatially coherent residual above a neutral R/B lower tail.
    const auto greenPedestal = resolveResidualGreenPedestal({
            40.8f, 41.2f, 2.0f, 0.94f, 1.5f, 2.5f, 1023.0f, 38
    });
    assert(greenPedestal.apply);
    assert(greenPedestal.correctionCode > 39.0f);
    assert(greenPedestal.correctionCode < 42.0f);
    assert(greenPedestal.confidence > 0.0f);

    // Normal dark noise close to the predicted sensor floor is not calibration evidence.
    const auto noiseOnly = resolveResidualGreenPedestal({
            2.0f, 2.2f, 0.8f, 0.92f, 0.6f, 1.5f, 4095.0f, 30
    });
    assert(!noiseOnly.apply);

    // A coloured dark object can make green high, but inconsistent tiles must not
    // become a global CFA correction.
    const auto colouredScene = resolveResidualGreenPedestal({
            25.0f, 28.0f, 5.0f, 0.62f, 4.0f, 2.0f, 4095.0f, 24
    });
    assert(!colouredScene.apply);

    // Strong R/B disagreement is also scene-content evidence, not a neutral pedestal.
    const auto nonNeutralTail = resolveResidualGreenPedestal({
            30.0f, 31.0f, 2.0f, 0.95f, 22.0f, 2.0f, 4095.0f, 24
    });
    assert(!nonNeutralTail.apply);

    // The correction has a hard safety ceiling even if corrupted statistics are huge.
    const auto bounded = resolveResidualGreenPedestal({
            500.0f, 500.0f, 1.0f, 1.0f, 0.0f, 1.0f, 1023.0f, 32
    });
    assert(bounded.apply);
    assert(std::abs(bounded.correctionCode - 51.15f) < 0.01f);

    return 0;
}
