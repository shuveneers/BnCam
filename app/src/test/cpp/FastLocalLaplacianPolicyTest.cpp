#include "FastLocalLaplacianPolicy.h"

#include <cassert>
#include <cmath>

using bncam::tone::FastLocalLaplacianInput;
using bncam::tone::resolveFastLocalLaplacianPlan;

int main() {
    // Safe identity when constructed outside the per-frame planner.
    const bncam::tone::FastLocalLaplacianPlan defaultPlan{};
    assert(!defaultPlan.enabled);
    assert(defaultPlan.strength == 0.0f);
    assert(defaultPlan.maxLiftEv == 0.0f);
    assert(defaultPlan.maxCompressEv == 0.0f);

    // Mixed dynamic-range input: a physically supported shadow may still be recovered.
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
    assert(clean.maxLiftEv > 0.20f && clean.maxLiftEv <= 0.89f);
    assert(clean.maxCompressEv > 0.20f);
    assert(clean.physicalNoiseSigmaY == mixed.physicalNoiseSigmaY);
    assert(clean.noiseModelConfidence > 0.90f);

    // Same scene with higher physical noise: never add MORE positive shadow authority.
    FastLocalLaplacianInput noisy = mixed;
    noisy.lowLightScene = true;
    noisy.physicalNoiseSigmaY = 0.030f;
    const auto noisyPlan = resolveFastLocalLaplacianPlan(noisy);
    assert(noisyPlan.enabled);
    assert(noisyPlan.noisePressure > clean.noisePressure);
    assert(noisyPlan.maxLiftEv < clean.maxLiftEv);
    assert(noisyPlan.maxCompressEv == clean.maxCompressEv);

    // If the physical model is unavailable, don't guess that raising noisy blacks is safe.
    FastLocalLaplacianInput noNoiseModel = mixed;
    noNoiseModel.physicalNoiseSigmaY = 0.20f;
    noNoiseModel.noiseModelConfidence = 0.0f;
    const auto untrusted = resolveFastLocalLaplacianPlan(noNoiseModel);
    assert(std::abs(untrusted.noisePressure) < 1.0e-6f);
    assert(untrusted.maxLiftEv == 0.0f);
    assert(untrusted.maxCompressEv > 0.20f);

    // Uniform dark scene: no automatic correction to middle grey and no extra noise lift.
    FastLocalLaplacianInput dark{};
    dark.p50 = 0.025f;
    dark.p75 = 0.060f;
    dark.p95 = 0.16f;
    dark.p99 = 0.30f;
    dark.lowLightScene = true;
    dark.physicalNoiseSigmaY = 0.018f;
    dark.noiseModelConfidence = 0.95f;
    const auto darkPlan = resolveFastLocalLaplacianPlan(dark);
    assert(darkPlan.sceneKey < 0.06f);
    assert(darkPlan.maxLiftEv < 0.025f);
    assert(darkPlan.maxCompressEv < 0.025f);
    assert(!darkPlan.enabled);

    // Uniform midtone without highlights does not need local tone redistribution.
    FastLocalLaplacianInput flat{};
    flat.p50 = 0.19f;
    flat.p75 = 0.20f;
    flat.p95 = 0.21f;
    flat.p99 = 0.22f;
    flat.noiseModelConfidence = 1.0f;
    const auto flatPlan = resolveFastLocalLaplacianPlan(flat);
    assert(flatPlan.maxLiftEv == 0.0f);
    assert(flatPlan.maxCompressEv == 0.0f);
    assert(!flatPlan.enabled);

    // Evidence of actual bright structure enables highlight compression independently of
    // shadow recovery, and does not require a noise model.
    FastLocalLaplacianInput bright{};
    bright.p50 = 0.27f;
    bright.p75 = 0.45f;
    bright.p95 = 0.94f;
    bright.p99 = 1.10f;
    const auto brightPlan = resolveFastLocalLaplacianPlan(bright);
    assert(brightPlan.enabled);
    assert(brightPlan.maxCompressEv > 0.25f);
    assert(brightPlan.maxLiftEv == 0.0f);
    return 0;
}
