#include "../../main/cpp/ProfileNoiseReductionPolicy.h"
#include <cassert>
#include <cmath>

int main() {
    using namespace bncam::profile_nr;
    const auto neutral = resolveProfileNoiseReduction(0.0f, 1.0f, 1.0f, 0.0f, 1.0f, 1.0f);
    assert(!neutral.requested);
    assert(neutral.lumaCreativeBlend == 0.0f);
    assert(neutral.chromaCreativeBlend == 0.0f);
    assert(neutral.lowFrequencyChromaAuthority == 0.0f);
    assert(lumaProfileStructureGate(1.0f, neutral) == 1.0f);
    assert(chromaProfileStructureGate(1.0f, neutral) == 1.0f);

    const auto active = resolveProfileNoiseReduction(0.8f, 0.75f, 0.5f, 0.7f, 0.8f, 0.9f);
    assert(active.requested);
    assert(active.lumaCreativeBlend > 0.0f);
    assert(active.chromaCreativeBlend > 0.0f);
    assert(active.lowFrequencyChromaAuthority > 0.0f);
    assert(lumaProfileStructureGate(0.8f, active) < 1.0f);
    assert(chromaProfileStructureGate(0.8f, active) < 1.0f);

    const float weakBase = combineWithResidualHeadroom(0.10f, active.lumaCreativeBlend, 0.95f);
    const float strongBase = combineWithResidualHeadroom(0.80f, active.lumaCreativeBlend, 0.95f);
    assert(weakBase > 0.10f && weakBase <= 0.95f);
    assert(strongBase > 0.80f && strongBase <= 0.95f);
    // A strong SPECTRA/base state leaves less absolute room for creative NR.
    assert((weakBase - 0.10f) > (strongBase - 0.80f));

    const auto clamped = resolveProfileNoiseReduction(NAN, -1.0f, 2.0f, 3.0f, NAN, -2.0f);
    assert(clamped.luminance == 0.0f);
    assert(clamped.luminanceDetail == 0.0f);
    assert(clamped.luminanceContrast == 1.0f);
    assert(clamped.color == 1.0f);
    assert(std::abs(clamped.colorDetail - 0.5f) < 1e-6f);
    assert(clamped.colorSmoothness == 0.0f);
    return 0;
}
