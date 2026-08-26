#include "../../main/cpp/SingleFrameRawDenoisePolicy.h"
#include <cassert>
#include <cmath>

int main() {
    using bncam::singleframe::resolveRawDenoisePlan;
    const auto missing = resolveRawDenoisePlan(0.0f, 1.0f, 1.0f);
    assert(!missing.active);

    const auto low = resolveRawDenoisePlan(3.3e-6f, 1.0f, 1.0f);
    const auto high = resolveRawDenoisePlan(1.8e-4f, 1.0f, 1.0f);
    assert(low.active && high.active);
    assert(high.physicalNoisePressure > low.physicalNoisePressure);
    assert(high.lumaAuthority > low.lumaAuthority);
    assert(high.chromaAuthority > low.chromaAuthority);
    assert(high.lowFrequencyChromaAuthority > low.lowFrequencyChromaAuthority);
    assert(high.detailRetentionFloor < low.detailRetentionFloor);
    assert(high.minimumResidualRatio < low.minimumResidualRatio);

    // Phase-4 clean/crispy retune: high-noise physical baseline may approach a
    // much cleaner flat-field residual while retaining a strict structure floor.
    assert(high.minimumResidualRatio < 0.40f);
    assert(high.detailRetentionFloor >= 0.90f);
    assert(high.lumaAuthority > 0.80f);
    assert(high.blendStrength >= 1.20f);

    // A weak model must not be promoted to physical truth.
    const auto weak = resolveRawDenoisePlan(1.8e-4f, 1.0f, 0.20f);
    assert(!weak.active);
    return 0;
}
