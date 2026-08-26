#include "../../main/cpp/NoiseAwareRenderGainPolicy.h"

#include <cassert>
#include <cmath>

int main() {
    using bncam::tone::resolveNoiseAwareRenderGainCap;

    const auto currentOn = resolveNoiseAwareRenderGainCap(true, false, true, 1391, 40.0f);
    assert(currentOn.active);
    assert(currentOn.cap > 1.45f && currentOn.cap < 1.75f);

    const auto currentOff = resolveNoiseAwareRenderGainCap(true, false, true, 1476, 40.0f);
    assert(currentOff.active);
    assert(currentOff.cap > 1.45f && currentOff.cap < 1.75f);

    const auto lowIso = resolveNoiseAwareRenderGainCap(true, false, true, 400, 30.0f);
    assert(!lowIso.active);

    const auto brightRaw = resolveNoiseAwareRenderGainCap(true, false, false, 1600, 5.0f);
    assert(!brightRaw.active);

    const auto yuv = resolveNoiseAwareRenderGainCap(false, false, true, 1600, 40.0f);
    assert(!yuv.active);

    const auto extreme = resolveNoiseAwareRenderGainCap(true, false, true, 6400, 60.0f);
    assert(extreme.active);
    assert(std::fabs(extreme.cap - 1.45f) < 1.0e-5f);

    return 0;
}
