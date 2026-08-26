#include "../../main/cpp/SpectraCfaSurfaceClassifier.h"

#include <algorithm>
#include <array>
#include <cassert>
#include <cmath>
#include <cstdint>
#include <random>

namespace {

float normalizedMad(std::array<float, 9> values) {
    std::sort(values.begin(), values.end());
    const float median = values[4];
    std::array<float, 9> deviations{};
    for (size_t i = 0; i < values.size(); ++i) {
        deviations[i] = std::abs(values[i] - median);
    }
    std::sort(deviations.begin(), deviations.end());
    return deviations[4] / 0.58601043f;
}

} // namespace

int main() {
    std::mt19937 rng(0x5FACE003u);
    std::normal_distribution<float> gaussian(0.0f, 1.0f);

    // Flat VST noise: most neighbourhoods should be recognized as flat/noise
    // and receive >1 authority rather than being treated as texture.
    constexpr int kFlatTrials = 120000;
    double flatConfidenceSum = 0.0;
    double flatAuthoritySum = 0.0;
    std::uint64_t falseTexture = 0;
    for (int trial = 0; trial < kFlatTrials; ++trial) {
        std::array<float, 9> samples{};
        for (float& sample : samples) sample = gaussian(rng);
        const auto d = bncam::spectra2::resolveCfaSurfaceClassification(
                normalizedMad(samples), 0.80f, 0.10f, 0.70f, 0.80f);
        flatConfidenceSum += d.flatConfidence;
        flatAuthoritySum += d.authorityScale;
        if (d.textureConfidence > 0.50f) ++falseTexture;
    }
    assert(flatConfidenceSum / kFlatTrials > 0.90);
    assert(flatAuthoritySum / kFlatTrials > 1.20);
    assert(static_cast<double>(falseTexture) / kFlatTrials < 0.03);

    // Repeating same-CFA microtexture must be protected. The texture amplitude
    // is deliberately close enough to the noise floor to test the intended
    // wall-vs-stitch distinction rather than an obvious hard edge.
    constexpr std::array<float, 9> kPattern{1.0f, -1.0f, 1.0f, -1.0f, 0.0f,
                                             1.0f, -1.0f, 1.0f, -1.0f};
    constexpr int kTextureTrials = 60000;
    double textureConfidenceSum = 0.0;
    double textureAuthoritySum = 0.0;
    std::uint64_t stronglyProtected = 0;
    for (int trial = 0; trial < kTextureTrials; ++trial) {
        std::array<float, 9> samples{};
        for (size_t i = 0; i < samples.size(); ++i) {
            samples[i] = gaussian(rng) + 1.50f * kPattern[i];
        }
        const auto d = bncam::spectra2::resolveCfaSurfaceClassification(
                normalizedMad(samples), 3.20f, 0.75f, 0.70f, 0.80f);
        textureConfidenceSum += d.textureConfidence;
        textureAuthoritySum += d.authorityScale;
        if (d.textureConfidence > 0.50f && d.flatConfidence < 0.20f) {
            ++stronglyProtected;
        }
    }
    assert(textureConfidenceSum / kTextureTrials > 0.75);
    assert(textureAuthoritySum / kTextureTrials < 0.85);
    assert(static_cast<double>(stronglyProtected) / kTextureTrials > 0.72);

    // A less certain profile may widen the noise envelope, but must not reduce
    // flat-surface denoise authority for the same near-floor observation.
    const auto highConfidence = bncam::spectra2::resolveCfaSurfaceClassification(
            1.45f, 1.10f, 0.10f, 0.75f, 1.0f);
    const auto lowConfidence = bncam::spectra2::resolveCfaSurfaceClassification(
            1.45f, 1.10f, 0.10f, 0.75f, 0.20f);
    assert(lowConfidence.flatConfidence >= highConfidence.flatConfidence);
    assert(lowConfidence.authorityScale >= highConfidence.authorityScale);

    // Strong coherent structure stays protected regardless of a high global
    // noise-pressure request.
    const auto edge = bncam::spectra2::resolveCfaSurfaceClassification(
            2.30f, 5.0f, 0.90f, 1.0f, 0.85f);
    assert(edge.textureConfidence > 0.95f);
    assert(edge.authorityScale < 0.75f);

    return 0;
}
