#include "../../main/cpp/SpectraTemporalFusion.h"

#include <cassert>
#include <cmath>
#include <iostream>
#include <vector>

using namespace spectra_temporal;

int main() {
    assert(staticProbability(0.0, 1.0, 1.0, 1.0) > 0.99f);
    assert(staticProbability(6.0, 1.0, 1.0, 1.0) < 0.05f);
    assert(staticProbability(0.0, 0.0, 1.0, 1.0) == 0.0f);

    const float consistent = forwardBackwardConsistency(2.0, -1.0, -2.0, 1.0, 0.25, 0.24);
    const float inconsistent = forwardBackwardConsistency(2.0, -1.0, 1.5, 1.0, 0.25, 0.24);
    assert(consistent > 0.8f);
    assert(inconsistent < consistent);

    StaticProbabilityField field{};
    field.imageWidth = 96;
    field.imageHeight = 48;
    field.cellSize = 48;
    field.columns = 2;
    field.rows = 1;
    field.values = {1.0f, 0.0f};
    const float middle = field.sample(48, 24);
    assert(middle > 0.35f && middle < 0.65f);

    InnovationDiagnostics innovation{};
    innovation.mean = 0.0;
    innovation.variance = 1.0;
    innovation.lagCorrelation = 0.0;
    innovation.heavyTailFraction = 0.01;
    innovation.samples = 4096;

    std::vector<TemporalFitPoint> clean;
    for (int i = 0; i < 8; ++i) {
        const double x = 0.03 + 0.08 * i;
        clean.push_back({x, 0.002 * x + 0.0001, 64.0});
    }
    const TemporalFitResult cleanFit = fitTemporalNoiseModel(clean, innovation);
    assert(cleanFit.valid);
    assert(std::abs(cleanFit.slope - 0.002) < 1.0e-6);
    assert(std::abs(cleanFit.offset - 0.0001) < 1.0e-6);

    std::vector<TemporalFitPoint> contaminated = clean;
    contaminated[4].variance *= 12.0;
    const TemporalFitResult robustFit = fitTemporalNoiseModel(contaminated, innovation);
    assert(robustFit.valid);
    assert(robustFit.estimator == TemporalFitEstimator::HUBER_IRLS);
    assert(std::abs(robustFit.slope - 0.002) < 0.002);

    const double stableFit = fitStability(1.02, 0.98, 1.00, 1.00);
    const double unstableFit = fitStability(1.25, 0.75, 0.75, 1.25);
    assert(stableFit > 0.75);
    assert(unstableFit < 0.05);

    const double independent = 0.25;
    const double correlated = correlationAwareVarianceScale(independent, 0.5);
    assert(std::abs(correlated - 0.625) < 1.0e-9);
    assert(effectiveFrameCount(correlated) < effectiveFrameCount(independent));

    const float staticWeight = localFusionAuthority(1.0f, 1.0f, 1.0f, 1.0f);
    const float motionWeight = localFusionAuthority(0.1f, 1.0f, 1.0f, 1.0f);
    assert(staticWeight > 0.99f);
    assert(motionWeight < 0.1f);

    std::cout << "SPECTRA_TEMPORAL_FUSION_TESTS_OK\n";
    return 0;
}
