#include "../SpectraCoreSnapshot.h"
#include "../SpectraNeuralAdaptiveAuthority.h"
#include "../SpectraNeuralConditioning.h"
#include "../SpectraNeuralResidualControl.h"

#include <array>
#include <cassert>
#include <cmath>
#include <iostream>

using namespace bncam::spectra::neural;

static bool near(float a, float b, float eps = 1.0e-6f) {
    return std::fabs(a - b) <= eps;
}

static SpectraCoreSnapshot snapshotWithNoise(float sScale, float oScale) {
    SpectraCoreSnapshot snapshot{};
    snapshot.schemaVersion = kSpectraCoreSnapshotSchemaVersion;
    snapshot.cfa = resolveCanonicalBayerPack(bncam::raw::CFA_RGGB, 0, 0);
    snapshot.rawWidth = 4000u;
    snapshot.rawHeight = 3000u;
    snapshot.noise.shotS = {{
        0.0010f * sScale,
        0.0011f * sScale,
        0.0012f * sScale,
        0.0013f * sScale,
    }};
    snapshot.noise.readO = {{
        0.000010f * oScale,
        0.000011f * oScale,
        0.000012f * oScale,
        0.000013f * oScale,
    }};
    snapshot.noise.trust = 1.0f;
    snapshot.metadataTrust = {1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f};
    snapshot.blackResidual.trust = 1.0f;
    snapshot.remainingLsc.mapTrust = 1.0f;
    return snapshot;
}

int main() {
    const auto lowNoise = snapshotWithNoise(0.5f, 0.5f);
    const auto highNoise = snapshotWithNoise(2.0f, 2.0f);
    assert(lowNoise.noise.valid());
    assert(highNoise.noise.valid());

    // Effective S/O owns the PHYSICAL baseline Bayer denoise. A larger frozen physical model
    // must predict more pre-baseline variance at the same signal. It must not, by itself, be
    // interpreted as stronger Neural authority after that baseline has already run.
    constexpr float signal = 0.08f;
    for (std::size_t i = 0; i < 4; ++i) {
        const auto ch = static_cast<CanonicalCfaChannel>(i);
        const float lowSigma = lowNoise.noise.sigma(ch, signal);
        const float highSigma = highNoise.noise.sigma(ch, signal);
        assert(std::isfinite(lowSigma));
        assert(std::isfinite(highSigma));
        assert(highSigma > lowSigma);
    }

    // Neural authority is downstream and is driven by measured REMAINING post-physical noise.
    // No residual-noise evidence is effectively identity for an ordinary non-zero signal.
    const float noResidualAuthority =
            neuralAdaptiveAuthorityScale(signal, kNeuralAdaptiveSigmaFloor, 1.0f);
    assert(near(noResidualAuthority, 0.0f));

    // More measured residual noise may grant more Neural correction authority. These sigmas are
    // deliberately independent of the pre-baseline S/O pair above: the production shader first
    // measures post-physical variance and only uses effective S/O as an upper bound.
    constexpr float lowResidualSigma = 0.012f;
    constexpr float highResidualSigma = 0.040f;
    const float lowResidualAuthority =
            neuralAdaptiveAuthorityScale(signal, lowResidualSigma, 1.0f);
    const float highResidualAuthority =
            neuralAdaptiveAuthorityScale(signal, highResidualSigma, 1.0f);
    assert(highResidualAuthority > lowResidualAuthority);
    assert(highResidualAuthority - lowResidualAuthority > 0.50f);
    assert(lowResidualAuthority >= 0.0f && lowResidualAuthority <= 1.0f);
    assert(highResidualAuthority >= 0.0f && highResidualAuthority <= 1.0f);

    // Neural Off is exact identity at the residual-authority layer. The physical baseline NR is
    // outside this control and therefore remains active when Neural is disabled.
    NeuralDenoiseControls off = neuralCharacterControls(NeuralCharacterPreset::Natural, false);
    off.noiseReduction = 0.0f;
    off.lumaNoise = 0.0f;
    off.chromaNoise = 0.0f;
    off.detailProtection = 0.0f;
    off.lowFrequencyCleanup = 0.0f;
    off.adaptiveResponse = 0.0f;
    const std::array<float, 4> predictedResidual{{0.10f, -0.05f, 0.03f, -0.08f}};
    const auto offResidual = applyNeuralResidualComponentAuthorities(predictedResidual, off);
    for (float value : offResidual) {
        assert(near(value, 0.0f));
    }

    // Neural On is allowed to publish a residual correction; master/adaptive authority are fixed.
    const auto on = neuralCharacterControls(NeuralCharacterPreset::Natural, true);
    assert(on.enabled);
    assert(near(on.noiseReduction, 1.0f));
    assert(near(on.adaptiveResponse, 1.0f));
    const auto onResidual = applyNeuralResidualComponentAuthorities(predictedResidual, on);
    bool anyNonZero = false;
    for (float value : onResidual) {
        anyNonZero = anyNonZero || std::fabs(value) > 1.0e-7f;
    }
    assert(anyNonZero);

    std::cout << "PHASE15_NEURAL_VISUAL_EFFECT_CONTRACT_PASS\n";
    return 0;
}
