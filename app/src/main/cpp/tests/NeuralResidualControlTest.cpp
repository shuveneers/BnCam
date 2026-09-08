#include "../SpectraNeuralResidualControl.h"
#include <array>
#include <cassert>
#include <cmath>

using namespace bncam::spectra::neural;

static bool near(float a, float b, float eps = 2.0e-6f) {
    return std::fabs(a - b) <= eps;
}

int main() {
    const std::array<std::array<float,4>,5> vectors{{
        {{0.10f,-0.20f,0.30f,-0.40f}},
        {{1.0f,0.0f,0.0f,0.0f}},
        {{0.0f,1.0f,0.0f,0.0f}},
        {{0.0f,0.0f,1.0f,0.0f}},
        {{0.0f,0.0f,0.0f,1.0f}},
    }};
    for (const auto& v : vectors) {
        const auto roundTrip = neuralResidualInverse(neuralResidualForward(v));
        for (int c=0;c<4;++c) assert(near(v[c], roundTrip[c]));
    }

    NeuralDenoiseControls luma{};
    luma.enabled = true;
    luma.noiseReduction = 1.0f;
    luma.lumaNoise = 1.0f;
    luma.chromaNoise = 0.0f;
    const std::array<float,4> common{{0.25f,0.25f,0.25f,0.25f}};
    const auto commonOut = applyNeuralResidualComponentAuthorities(common,luma);
    for (float v : commonOut) assert(near(v,0.25f));

    NeuralDenoiseControls chroma = luma;
    chroma.lumaNoise = 0.0f;
    chroma.chromaNoise = 1.0f;
    const auto commonSuppressed = applyNeuralResidualComponentAuthorities(common,chroma);
    for (float v : commonSuppressed) assert(near(v,0.0f));

    const auto natural = neuralCharacterControls(NeuralCharacterPreset::Natural);
    const auto clean = neuralCharacterControls(NeuralCharacterPreset::Clean);
    const auto texture = neuralCharacterControls(NeuralCharacterPreset::Texture);
    const auto night = neuralCharacterControls(NeuralCharacterPreset::Night);
    assert(natural.valid() && clean.valid() && texture.valid() && night.valid());
    assert(clean.noiseReduction > natural.noiseReduction);
    assert(texture.detailProtection > natural.detailProtection);
    assert(night.lowFrequencyCleanup > natural.lowFrequencyCleanup);

    SpectraCoreSnapshot snapshot{};
    NeuralRuntimeReadiness readiness{};
    NeuralDenoiseControls zero = natural;
    zero.noiseReduction = 0.0f;
    // Exact zero authority is decided before any model/backend readiness checks.
    const auto decision = decideNeuralInvocation(snapshot, zero, readiness);
    assert(!decision.runInference);
    assert(decision.bypassReason == NeuralBypassReason::ZeroAuthority);
    return 0;
}
