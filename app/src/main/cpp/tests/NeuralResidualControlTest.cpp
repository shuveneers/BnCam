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
    static_assert(kNeuralAdaptiveFullEvidenceSnr == 4.0f);
    static_assert(kNeuralAdaptiveIdentitySnr == 48.0f);

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

    // Post-physical Neural evidence is driven only by remaining local SNR. The same
    // signal/noise pair resolves identically regardless of sensor provenance.
    assert(near(neuralAdaptiveNoiseEvidenceFromSnr(1.0f), 1.0f));
    assert(near(neuralAdaptiveNoiseEvidenceFromSnr(kNeuralAdaptiveFullEvidenceSnr), 1.0f));
    assert(near(neuralAdaptiveNoiseEvidenceFromSnr(kNeuralAdaptiveIdentitySnr), 0.0f));
    assert(neuralAdaptiveNoiseEvidenceFromSnr(16.0f) > 0.0f);
    assert(neuralAdaptiveNoiseEvidenceFromSnr(16.0f) < 1.0f);

    const float noisy = neuralAdaptiveAuthorityScale(0.02f, 0.02f, natural.adaptiveResponse);       // SNR 1
    const float medium = neuralAdaptiveAuthorityScale(0.32f, 0.02f, natural.adaptiveResponse);      // SNR 16
    const float cleanAuthority = neuralAdaptiveAuthorityScale(0.96f, 0.02f, natural.adaptiveResponse); // SNR 48
    assert(noisy > medium);
    assert(medium > cleanAuthority);
    assert(near(cleanAuthority, 0.0f));

    // At the same RAW signal, stronger measured residual sigma must never produce less authority.
    const float lowNoise = neuralAdaptiveAuthorityScale(0.08f, 0.0010f, natural.adaptiveResponse);  // SNR 80
    const float mediumNoise = neuralAdaptiveAuthorityScale(0.08f, 0.0050f, natural.adaptiveResponse); // SNR 16
    const float highNoise = neuralAdaptiveAuthorityScale(0.08f, 0.0200f, natural.adaptiveResponse); // SNR 4
    assert(highNoise > mediumNoise);
    assert(mediumNoise > lowNoise);
    assert(near(lowNoise, 0.0f));
    assert(highNoise <= 1.0f);

    float previousEvidence = 1.0f;
    for (int step = 0; step <= 600; ++step) {
        const float snr = 0.1f * static_cast<float>(step);
        const float evidence = neuralAdaptiveNoiseEvidenceFromSnr(snr);
        assert(evidence >= 0.0f && evidence <= 1.0f);
        assert(evidence <= previousEvidence + 1.0e-6f);
        previousEvidence = evidence;
    }

    // Adaptive Response is compatibility-only. Residual SNR may attenuate writeback exactly once.
    const float evidenceFull = neuralAdaptiveNoiseEvidence(0.08f, 0.02f); // SNR 4
    assert(near(evidenceFull, 1.0f));
    for (float response : {0.0f, 0.5f, 1.0f}) {
        const float authority = neuralAdaptiveAuthorityScale(0.08f, 0.02f, response);
        assert(near(authority, evidenceFull));
    }

    // Exact high-SNR identity remains invariant at the new post-physical residual threshold.
    assert(near(neuralAdaptiveAuthorityScale(0.96f, 0.02f, 0.0f), 0.0f));
    assert(near(neuralAdaptiveAuthorityScale(0.96f, 0.02f, 1.0f), 0.0f));

    SpectraCoreSnapshot snapshot{};
    NeuralRuntimeReadiness readiness{};
    NeuralDenoiseControls zero = natural;
    zero.noiseReduction = 0.0f;
    const auto decision = decideNeuralInvocation(snapshot, zero, readiness);
    assert(!decision.runInference);
    assert(decision.bypassReason == NeuralBypassReason::ZeroAuthority);
    return 0;
}
