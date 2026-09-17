#include "../SpectraNeuralResidualControl.h"
#include "../SpectraNeuralAdaptiveAuthority.h"
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
    assert(clean.noiseReduction == 1.0f);
    assert(natural.noiseReduction == 1.0f);
    assert(texture.noiseReduction == 1.0f);
    assert(night.noiseReduction == 1.0f);
    assert(natural.adaptiveResponse == 1.0f);
    assert(clean.adaptiveResponse == 1.0f);
    assert(texture.adaptiveResponse == 1.0f);
    assert(night.adaptiveResponse == 1.0f);
    assert(texture.detailProtection > natural.detailProtection);
    assert(night.lowFrequencyCleanup > natural.lowFrequencyCleanup);

    // Phase 9 cross-sensor behavior is driven only by local physical SNR. The same
    // signal/noise pair must resolve identically regardless of sensor provenance.
    assert(near(neuralAdaptiveNoiseEvidenceFromSnr(1.0f), 1.0f));
    assert(near(neuralAdaptiveNoiseEvidenceFromSnr(kNeuralAdaptiveFullEvidenceSnr), 1.0f));
    assert(near(neuralAdaptiveNoiseEvidenceFromSnr(kNeuralAdaptiveIdentitySnr), 0.0f));
    assert(near(neuralAdaptiveNoiseEvidenceFromSnr(16.0f), 0.0f));

    const float noisy = neuralAdaptiveAuthorityScale(0.02f, 0.02f, natural.adaptiveResponse);
    const float medium = neuralAdaptiveAuthorityScale(0.08f, 0.02f, natural.adaptiveResponse);
    const float cleanAuthority = neuralAdaptiveAuthorityScale(0.32f, 0.02f, natural.adaptiveResponse);
    assert(noisy > medium);
    assert(medium > cleanAuthority);
    assert(near(cleanAuthority, 0.0f));

    // At the same RAW signal, stronger measured sigma must never produce less
    // authority. This is the cross-sensor rule without any sensor identity input.
    const float lowNoise = neuralAdaptiveAuthorityScale(0.08f, 0.005f, natural.adaptiveResponse);
    const float mediumNoise = neuralAdaptiveAuthorityScale(0.08f, 0.02f, natural.adaptiveResponse);
    const float highNoise = neuralAdaptiveAuthorityScale(0.08f, 0.08f, natural.adaptiveResponse);
    assert(highNoise > mediumNoise);
    assert(mediumNoise > lowNoise);
    assert(near(lowNoise, 0.0f));
    assert(highNoise <= 1.0f);

    float previousEvidence = 1.0f;
    for (int step = 0; step <= 200; ++step) {
        const float snr = 0.1f * static_cast<float>(step);
        const float evidence = neuralAdaptiveNoiseEvidenceFromSnr(snr);
        assert(evidence >= 0.0f && evidence <= 1.0f);
        assert(evidence <= previousEvidence + 1.0e-6f);
        previousEvidence = evidence;
    }

    // Corrective contract: SNR may attenuate writeback exactly once.  Adaptive Response is
    // production-fixed and therefore cannot add a second inverse-SNR attenuation.  A representative
    // SNR=4 sample must retain the physical evidence envelope (~0.741), rather than the retired
    // double-gated value (~0.331).
    const float evidenceMid = neuralAdaptiveNoiseEvidence(0.08f, 0.02f);
    assert(evidenceMid > 0.70f);
    for (float response : {0.0f, 0.5f, 1.0f}) {
        const float authority = neuralAdaptiveAuthorityScale(0.08f, 0.02f, response);
        assert(near(authority, evidenceMid));
    }

    // High-SNR identity remains an invariant independent of the retained compatibility parameter.
    assert(near(neuralAdaptiveAuthorityScale(0.32f, 0.02f, 0.0f), 0.0f));
    assert(near(neuralAdaptiveAuthorityScale(0.32f, 0.02f, 1.0f), 0.0f));

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
