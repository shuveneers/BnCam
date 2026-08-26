#include "../../main/cpp/SpectraCfaOrthonormalSupport.h"

#include <algorithm>
#include <array>
#include <cassert>
#include <cmath>
#include <cstdint>
#include <random>

using bncam::spectra2::CfaOrthonormalBasis;
using bncam::spectra2::CfaOrthonormalVariance;

namespace {

float robustSpan(std::array<float, 9> values) {
    std::sort(values.begin(), values.end());
    return values[7] - values[1];
}

} // namespace

int main() {
    // The signal transform should be approximately energy preserving.
    const auto basis = bncam::spectra2::transformCfaBlock(0.21f, 0.26f, 0.24f, 0.18f);
    const float sourceEnergy = 0.21f * 0.21f + 0.26f * 0.26f +
            0.24f * 0.24f + 0.18f * 0.18f;
    const float basisEnergy = basis.c0 * basis.c0 + basis.c1 * basis.c1 +
            basis.c2 * basis.c2 + basis.deltaG * basis.deltaG;
    assert(std::abs(sourceEnergy - basisEnergy) / sourceEnergy < 0.002f);

    const auto variance = bncam::spectra2::propagateCfaBlockVariance(
            0.012f * 0.012f,
            0.008f * 0.008f,
            0.0085f * 0.0085f,
            0.014f * 0.014f);
    assert(variance.c0 > 0.0f && variance.c1 > 0.0f &&
           variance.c2 > 0.0f && variance.deltaG > 0.0f);

    // Flat-field CFA noise must keep broad C0 support. This is the core
    // regression against the old residual-self-matching bilateral gate.
    std::mt19937 rng(0xCFA002u);
    std::normal_distribution<float> normal(0.0f, 1.0f);
    constexpr std::array<float, 4> kSigma{0.012f, 0.008f, 0.0085f, 0.014f};
    constexpr std::array<float, 4> kBase{0.20f, 0.24f, 0.24f, 0.16f};
    const CfaOrthonormalVariance expectedVariance =
            bncam::spectra2::propagateCfaBlockVariance(
                    kSigma[0] * kSigma[0], kSigma[1] * kSigma[1],
                    kSigma[2] * kSigma[2], kSigma[3] * kSigma[3]);

    double supportSum = 0.0;
    std::uint64_t strongFalseStructure = 0;
    constexpr int kTrials = 120000;
    for (int trial = 0; trial < kTrials; ++trial) {
        std::array<CfaOrthonormalBasis, 9> samples{};
        std::array<float, 9> c1{}, c2{}, dg{};
        for (int i = 0; i < 9; ++i) {
            std::array<float, 4> v{};
            for (int ch = 0; ch < 4; ++ch) {
                v[ch] = kBase[ch] + kSigma[ch] * normal(rng);
            }
            samples[i] = bncam::spectra2::transformCfaBlock(v[0], v[1], v[2], v[3]);
            c1[i] = samples[i].c1;
            c2[i] = samples[i].c2;
            dg[i] = samples[i].deltaG;
        }
        supportSum += bncam::spectra2::cfaLumaSupportWeight(
                samples[4].c0, samples[0].c0,
                expectedVariance.c0, expectedVariance.c0, 0.75f);
        const float protection = bncam::spectra2::cfaOpponentStructureProtection(
                robustSpan(c1), robustSpan(c2), robustSpan(dg),
                std::sqrt(expectedVariance.c1), std::sqrt(expectedVariance.c2),
                std::sqrt(expectedVariance.deltaG), 0.75f);
        if (protection > 0.80f) ++strongFalseStructure;
    }
    const double meanSupport = supportSum / kTrials;
    const double falseStructureRate = static_cast<double>(strongFalseStructure) / kTrials;
    assert(meanSupport > 0.82);
    assert(falseStructureRate < 0.004);

    // An iso-luminant opponent-colour edge must still be protected even though
    // C0 intentionally sees little change. Add a coherent +4 sigma displacement
    // along C1 to four of nine blocks; an isolated noise outlier cannot mimic
    // this robust cross-neighbourhood span.
    std::uint64_t protectedColourEdges = 0;
    constexpr int kEdgeTrials = 50000;
    const float c1Sigma = std::sqrt(expectedVariance.c1);
    constexpr std::array<float, 4> kC1Axis{-0.794f, 0.107f, 0.107f, 0.588f};
    for (int trial = 0; trial < kEdgeTrials; ++trial) {
        std::array<float, 9> c1{}, c2{}, dg{};
        for (int i = 0; i < 9; ++i) {
            std::array<float, 4> v{};
            for (int ch = 0; ch < 4; ++ch) {
                v[ch] = kBase[ch] + kSigma[ch] * normal(rng);
            }
            if (i >= 5) {
                for (int ch = 0; ch < 4; ++ch) {
                    v[ch] += 4.0f * c1Sigma * kC1Axis[ch];
                }
            }
            const auto b = bncam::spectra2::transformCfaBlock(v[0], v[1], v[2], v[3]);
            c1[i] = b.c1;
            c2[i] = b.c2;
            dg[i] = b.deltaG;
        }
        const float protection = bncam::spectra2::cfaOpponentStructureProtection(
                robustSpan(c1), robustSpan(c2), robustSpan(dg),
                std::sqrt(expectedVariance.c1), std::sqrt(expectedVariance.c2),
                std::sqrt(expectedVariance.deltaG), 0.75f);
        if (protection > 0.50f) ++protectedColourEdges;
    }
    const double colourEdgeProtectionRate =
            static_cast<double>(protectedColourEdges) / kEdgeTrials;
    assert(colourEdgeProtectionRate > 0.82);

    // Profile uncertainty broadens support; it must never make the same C0
    // difference look MORE like structure than a high-confidence profile.
    const float centerC0 = 0.30f;
    const float neighbourC0 = centerC0 + 2.2f * std::sqrt(2.0f * variance.c0);
    const float highConfidenceSupport = bncam::spectra2::cfaLumaSupportWeight(
            centerC0, neighbourC0, variance.c0, variance.c0, 1.0f);
    const float lowConfidenceSupport = bncam::spectra2::cfaLumaSupportWeight(
            centerC0, neighbourC0, variance.c0, variance.c0, 0.20f);
    assert(lowConfidenceSupport >= highConfidenceSupport);

    return 0;
}
