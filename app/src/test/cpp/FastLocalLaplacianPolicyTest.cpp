#include "FastLocalLaplacianPolicy.h"

#include <cassert>
#include <cmath>

using bncam::tone::FastLocalLaplacianInput;
using bncam::tone::resolveFastLocalLaplacianPlan;

int main() {
    FastLocalLaplacianInput mixed{};
    mixed.p50 = 0.075f;
    mixed.p75 = 0.18f;
    mixed.p95 = 0.82f;
    mixed.p99 = 0.98f;
    mixed.nearWhiteFraction = 0.012f;
    mixed.physicalNoiseSigmaY = 0.003f;
    mixed.noiseModelConfidence = 0.95f;
    mixed.strongHighlightScene = true;
    const auto clean = resolveFastLocalLaplacianPlan(mixed);
    assert(clean.enabled);
    assert(clean.dynamicRangePressure > 0.15f);
    assert(clean.maxLiftEv > 1.0f);
    assert(clean.maxCompressEv > 0.40f);
    assert(clean.physicalNoiseSigmaY == mixed.physicalNoiseSigmaY);
    assert(clean.noiseModelConfidence > 0.90f);

    FastLocalLaplacianInput noisy = mixed;
    noisy.lowLightScene = true;
    noisy.physicalNoiseSigmaY = 0.030f;
    const auto noisyPlan = resolveFastLocalLaplacianPlan(noisy);
    assert(noisyPlan.enabled);
    assert(noisyPlan.noisePressure > clean.noisePressure);
    assert(noisyPlan.maxLiftEv < clean.maxLiftEv);
    assert(noisyPlan.strength <= clean.strength);
    // Highlight compression remains a tone authority and must not receive fictitious denoise credit.
    assert(noisyPlan.maxCompressEv >= 0.34f);

    FastLocalLaplacianInput noNoiseModel = mixed;
    noNoiseModel.physicalNoiseSigmaY = 0.20f;
    noNoiseModel.noiseModelConfidence = 0.0f;
    const auto untrusted = resolveFastLocalLaplacianPlan(noNoiseModel);
    assert(std::abs(untrusted.noisePressure) < 1.0e-6f);

    FastLocalLaplacianInput dark{};
    dark.p50 = 0.025f;
    dark.p75 = 0.060f;
    dark.p95 = 0.16f;
    dark.p99 = 0.30f;
    dark.lowLightScene = true;
    dark.physicalNoiseSigmaY = 0.018f;
    dark.noiseModelConfidence = 0.95f;
    const auto darkPlan = resolveFastLocalLaplacianPlan(dark);
    assert(darkPlan.enabled);
    assert(darkPlan.sceneKey >= 0.150f);
    // Dark-scene brightness is placed by local tone mapping, not by a hidden global software EV.
    assert(darkPlan.maxLiftEv >= 0.45f);
    assert(darkPlan.maxLiftEv <= clean.maxLiftEv);

    return 0;
}
