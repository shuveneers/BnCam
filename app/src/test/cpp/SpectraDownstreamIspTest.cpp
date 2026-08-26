#include "SpectraDownstreamIsp.h"

#include <cassert>
#include <cmath>
#include <iostream>

using namespace bncam::spectra2;

namespace {

NoiseState makeResidual(double varianceYProxy, double confidence = 0.9) {
    const double variance = std::max(0.0, varianceYProxy);
    return makeState(
            "POST_VISIBLE_CHROMA",
            "TEST_DIAGONAL",
            "PROPAGATED",
            confidence,
            diagonalCovariance(variance, variance, variance)
    );
}

void expectNear(double actual, double expected, double tolerance) {
    assert(std::abs(actual - expected) <= tolerance);
}

} // namespace

int main() {
    const NoiseState lowNoise = makeResidual(1.0e-5, 0.95);
    const NoiseState highNoise = makeResidual(8.0e-4, 0.95);

    const auto lowPlan = resolveDownstreamSharpenPlan(
            true, lowNoise, 1.2e-5, 0.9, 0.0, 0.12
    );
    const auto highPlan = resolveDownstreamSharpenPlan(
            true, highNoise, 9.0e-4, 0.9, 0.0, 0.12
    );
    assert(lowPlan.enabled);
    assert(lowPlan.maximumAmount > highPlan.maximumAmount);
    assert(lowPlan.localContrastAuthority >= highPlan.localContrastAuthority);
    assert(lowPlan.minimumEdgeSnr < highPlan.minimumEdgeSnr);
    assert(unsharpVarianceGain(lowPlan.maximumAmount) <=
            lowPlan.maximumPredictedVarianceGain + 1.0e-6);
    assert(unsharpVarianceGain(highPlan.maximumAmount) <=
            highPlan.maximumPredictedVarianceGain + 1.0e-6);

    const auto cleanBudgetPlan = resolveDownstreamSharpenPlan(
            true, lowNoise, 1.2e-5, 0.35, 0.0, 0.12
    );
    const auto residualBudgetPlan = resolveDownstreamSharpenPlan(
            true, lowNoise, 1.2e-5, 1.0, 0.0, 0.12
    );
    assert(cleanBudgetPlan.maximumAmount > residualBudgetPlan.maximumAmount);

    const auto edgeDecision = resolveDownstreamPixelDecision(
            lowPlan, 0.045, 0.060, 0.45
    );
    const auto noiseDecision = resolveDownstreamPixelDecision(
            lowPlan, 0.001, 0.001, 0.45
    );
    assert(edgeDecision.localAuthority > noiseDecision.localAuthority);
    assert(noiseDecision.noiseRejected);
    assert(edgeDecision.edgeSnr > noiseDecision.edgeSnr);

    const auto legacyPlan = resolveDownstreamSharpenPlan(
            false, lowNoise, 0.0, 1.0, 0.0, 0.10
    );
    assert(!legacyPlan.spectraAware);
    expectNear(legacyPlan.maximumAmount, 0.10, 1.0e-9);
    assert(legacyPlan.status == "LEGACY_SHARPENING_PRESERVED_SPECTRA_OFF");

    const NoiseState quantized = propagateQuantization8Bit(lowNoise);
    assert(quantized.varianceY > lowNoise.varianceY);
    assert(quantized.varianceRG > lowNoise.varianceRG);
    assert(quantized.varianceBG > lowNoise.varianceBG);

    const NoiseState finalMeasured = propagateNoiseAwareSharpen(
            quantized, 1.04, 1.06, true
    );
    const NoiseState finalPredicted = propagateNoiseAwareSharpen(
            quantized, 0.0, 1.05, false
    );
    assert(finalMeasured.varianceY >= quantized.varianceY);
    assert(finalMeasured.varianceY <= quantized.varianceY * 1.30);
    assert(finalPredicted.varianceY >= quantized.varianceY);
    assert(std::isfinite(finalMeasured.covarianceRgBg));
    assert(determinant(finalMeasured.covariance) >= -1.0e-9);

    const double cappedAmount = maximumAmountForVarianceGain(0.20, 1.04);
    assert(cappedAmount < 0.20);
    assert(unsharpVarianceGain(cappedAmount) <= 1.04 + 1.0e-6);

    std::cout << "SPECTRA_DOWNSTREAM_ISP_TESTS_OK\n";
    return 0;
}
