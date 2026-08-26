#include "../../main/cpp/SpectraMultiscaleResidualConsensus.h"

#include <cassert>
#include <cmath>
#include <cstdint>
#include <random>

int main() {
    using bncam::spectra2::resolveMultiscaleResidualConsensus;

    const auto noisyFlat = resolveMultiscaleResidualConsensus(
            2.2f, 0.25f, 0.45f, 0.92f, 0.72f, 0.55f);
    assert(noisyFlat.contextAgreement > 0.90f);
    assert(noisyFlat.centerOutlierEvidence > 0.55f);
    assert(noisyFlat.contextMix > 0.20f);
    assert(noisyFlat.contextMix <= 0.62f);

    const auto coherentEdge = resolveMultiscaleResidualConsensus(
            2.2f, 0.25f, 5.0f, 0.08f, 0.72f, 0.55f);
    assert(coherentEdge.persistentStructureProtection > 0.90f);
    assert(coherentEdge.contextMix < 0.03f);

    const auto scaleDisagreement = resolveMultiscaleResidualConsensus(
            2.2f, 3.5f, 0.45f, 0.92f, 0.72f, 0.55f);
    assert(scaleDisagreement.contextAgreement < 0.05f);
    assert(scaleDisagreement.contextMix < 0.03f);

    const auto cleanCentre = resolveMultiscaleResidualConsensus(
            0.15f, 0.20f, 0.20f, 0.95f, 0.85f, 0.75f);
    assert(cleanCentre.centerOutlierEvidence < 0.01f);
    assert(cleanCentre.contextMix < 0.01f);

    const auto lowNoise = resolveMultiscaleResidualConsensus(
            2.2f, 0.25f, 0.45f, 0.92f, 0.05f, 0.75f);
    const auto highNoise = resolveMultiscaleResidualConsensus(
            2.2f, 0.25f, 0.45f, 0.92f, 0.95f, 0.75f);
    assert(highNoise.contextMix > lowNoise.contextMix);
    assert(highNoise.coarseWeight > lowNoise.coarseWeight);

    std::mt19937 rng(0xB0C0030u);
    std::uniform_real_distribution<float> z(0.0f, 8.0f);
    std::uniform_real_distribution<float> unit(0.0f, 1.0f);
    for (int i = 0; i < 200000; ++i) {
        const auto d = resolveMultiscaleResidualConsensus(
                z(rng), z(rng), z(rng), unit(rng), unit(rng), unit(rng));
        assert(std::isfinite(d.contextAgreement));
        assert(std::isfinite(d.centerOutlierEvidence));
        assert(std::isfinite(d.persistentStructureProtection));
        assert(std::isfinite(d.midWeight));
        assert(std::isfinite(d.coarseWeight));
        assert(std::isfinite(d.contextMix));
        assert(d.contextAgreement >= 0.0f && d.contextAgreement <= 1.0f);
        assert(d.centerOutlierEvidence >= 0.0f && d.centerOutlierEvidence <= 1.0f);
        assert(d.persistentStructureProtection >= 0.0f && d.persistentStructureProtection <= 1.0f);
        assert(d.midWeight >= 0.0f && d.midWeight <= 1.0f);
        assert(d.coarseWeight >= 0.0f && d.coarseWeight <= 1.0f);
        assert(std::abs((d.midWeight + d.coarseWeight) - 1.0f) < 1.0e-5f);
        assert(d.contextMix >= 0.0f && d.contextMix <= 0.62f);
    }
    return 0;
}
