#include "../../main/cpp/SpectraNeuralEffectTelemetry.h"

#include <cassert>
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <iostream>
#include <limits>
#include <vector>

namespace {
bool near(float actual, float expected, float tolerance = 1.0e-6f) {
    return std::fabs(actual - expected) <= tolerance;
}
}

int main() {
    using namespace bncam::spectra::neural;
    constexpr std::size_t groups = 2u;
    constexpr std::uint64_t samples = 10u;
    constexpr std::size_t lanes = 4u;
    constexpr std::size_t stride = kNeuralEffectSummaryVec4PerGroup * lanes;
    std::vector<float> values(groups * stride, 0.0f);

    const auto setTotal = [&](std::size_t slot, std::size_t lane, float total) {
        values[slot * lanes + lane] = total * 0.25f;
        values[stride + slot * lanes + lane] = total * 0.75f;
    };

    const float sumDelta[4] = {1.0f, -2.0f, 3.0f, -4.0f};
    const float sumAbs[4] = {2.0f, 2.0f, 4.0f, 4.0f};
    const float sumSq[4] = {4.0f, 9.0f, 16.0f, 25.0f};
    const float sigmaSq[4] = {1.0f, 4.0f, 9.0f, 16.0f};
    const float posteriorRatio[4] = {5.0f, 6.0f, 7.0f, 8.0f};
    const float posteriorSum[4] = {0.5f, 1.0f, 1.5f, 2.0f};
    const float halfCount[4] = {2.0f, 3.0f, 4.0f, 5.0f};
    const float oneCount[4] = {1.0f, 2.0f, 3.0f, 4.0f};
    const float twoCount[4] = {0.0f, 1.0f, 2.0f, 3.0f};
    const float highSigmaSq[4] = {1.0f, 4.0f, 9.0f, 16.0f};
    const float highCount[4] = {2.0f, 2.0f, 3.0f, 4.0f};

    for (std::size_t c = 0u; c < 4u; ++c) {
        setTotal(0u, c, sumDelta[c]);
        setTotal(1u, c, sumAbs[c]);
        setTotal(2u, c, sumSq[c]);
        setTotal(3u, c, sigmaSq[c]);
        setTotal(4u, c, halfCount[c]);
        setTotal(5u, c, oneCount[c]);
        setTotal(6u, c, twoCount[c]);
        setTotal(7u, c, posteriorRatio[c]);
        setTotal(10u, c, highSigmaSq[c]);
        setTotal(11u, c, highCount[c]);
        setTotal(12u, c, posteriorSum[c]);
    }
    const float basisRawSq[4] = {9.0f, 16.0f, 25.0f, 36.0f};
    const float basisSigmaSq[4] = {4.0f, 9.0f, 16.0f, 25.0f};
    for (std::size_t component = 0u; component < 4u; ++component) {
        setTotal(8u, component, basisRawSq[component]);
        setTotal(9u, component, basisSigmaSq[component]);
    }

    std::array<float, 4> posteriorMean{};
    assert(reducePosteriorMeanFromNeuralEffectSummary(
            values.data(), groups, samples, posteriorMean));
    assert(near(posteriorMean[0], 0.05f));
    assert(near(posteriorMean[3], 0.20f));

    const auto telemetry = reduceNeuralEffectSummary(values.data(), groups, samples);
    assert(telemetry.ready);
    assert(telemetry.packedSampleCount == samples);
    assert(near(telemetry.meanCorrectionCfa[0], 0.1f));
    assert(near(telemetry.meanCorrectionCfa[3], -0.4f));
    assert(near(telemetry.meanAbsCorrectionCfa[2], 0.4f));
    assert(near(telemetry.rmsCorrectionCfa[0], std::sqrt(0.4f)));
    assert(near(telemetry.rmsCorrectionSigmaCfa[2], std::sqrt(0.9f)));
    assert(near(telemetry.fractionAboveHalfSigmaCfa[3], 0.5f));
    assert(near(telemetry.fractionAboveOneSigmaCfa[1], 0.2f));
    assert(near(telemetry.fractionAboveTwoSigmaCfa[2], 0.2f));
    assert(near(telemetry.posteriorToInputVarianceRatioCfa[0], 0.5f));
    assert(near(telemetry.posteriorMeanVarianceCfa[2], 0.15f));
    assert(near(telemetry.residualBasisRms[0], std::sqrt(0.9f)));
    assert(near(telemetry.residualBasisRmsSigma[3], std::sqrt(2.5f)));
    assert(near(telemetry.highSnrRmsCorrectionSigmaCfa[1], std::sqrt(2.0f)));
    assert(near(telemetry.highSnrSampleFractionCfa[3], 0.4f));

    // Effect telemetry is observational. A corrupted effect slot must not hide a still-valid
    // posterior summary; callers can keep fail-closed posterior propagation while reporting the
    // optional effect summary as unavailable.
    values[0u] = std::numeric_limits<float>::quiet_NaN();
    posteriorMean = {};
    assert(reducePosteriorMeanFromNeuralEffectSummary(
            values.data(), groups, samples, posteriorMean));
    assert(near(posteriorMean[1], 0.10f));
    assert(!reduceNeuralEffectSummary(values.data(), groups, samples).ready);

    std::cout << "SpectraNeuralEffectTelemetryTest PASS\n";
    return 0;
}
