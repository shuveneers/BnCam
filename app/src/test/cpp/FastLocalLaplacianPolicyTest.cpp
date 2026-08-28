#include "../../main/cpp/FastLocalLaplacianPolicy.h"

#include <cassert>
#include <cmath>
#include <iostream>

using bncam::tone::FastLocalLaplacianInput;
using bncam::tone::resolveFastLocalLaplacianPlan;

int main() {
    FastLocalLaplacianInput ordinary{};
    ordinary.sceneMidtoneTarget = 0.15f;
    const auto quiet = resolveFastLocalLaplacianPlan(ordinary);
    assert(!quiet.enabled);
    assert(quiet.strength >= 0.045f && quiet.strength <= 0.40f);
    assert(quiet.pyramidLevels == 6u);
    assert(quiet.baseDownsample == 2u);

    FastLocalLaplacianInput hdr{};
    hdr.sceneMidtoneTarget = 0.15f;
    hdr.shadowPressure = 0.65f;
    hdr.dynamicRangePressure = 0.85f;
    hdr.recoverableHighlightPressure = 0.80f;
    hdr.sensorClipPressure = 0.15f;
    hdr.noisePressure = 0.20f;
    const auto strong = resolveFastLocalLaplacianPlan(hdr);
    assert(strong.enabled);
    assert(strong.strength > quiet.strength);
    assert(strong.maxLiftEv <= 0.46f + 1.0e-6f);
    assert(strong.maxCompressEv <= 0.56f + 1.0e-6f);
    assert(strong.edgeStopEv >= 0.54f && strong.edgeStopEv <= 0.78f);

    hdr.lowLightScene = true;
    hdr.noisePressure = 1.0f;
    const auto noisy = resolveFastLocalLaplacianPlan(hdr);
    assert(noisy.strength < strong.strength);
    assert(noisy.maxLiftEv < strong.maxLiftEv);
    assert(noisy.maxLiftEv >= 0.035f);

    std::cout << "FAST_LOCAL_LAPLACIAN_POLICY_TESTS_OK\n";
    return 0;
}
