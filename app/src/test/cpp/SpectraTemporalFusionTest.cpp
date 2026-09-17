#include "../../main/cpp/SpectraTemporalFusion.h"

#include <cassert>
#include <cmath>
#include <iostream>

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

    // FASE 6: temporal evidence may only modulate generic fusion authority. It must not
    // synthesize, fit or adapt a replacement physical S/O model.
    const double independent = 0.25;
    const double correlated = correlationAwareVarianceScale(independent, 0.5);
    assert(std::abs(correlated - 0.625) < 1.0e-9);
    assert(effectiveFrameCount(correlated) < effectiveFrameCount(independent));

    const float staticWeight = localFusionAuthority(1.0f, 1.0f, 1.0f, 1.0f);
    const float motionWeight = localFusionAuthority(0.1f, 1.0f, 1.0f, 1.0f);
    assert(staticWeight > 0.99f);
    assert(motionWeight < 0.1f);

    std::cout << "SPECTRA_TEMPORAL_FUSION_READ_ONLY_TESTS_OK\n";
    return 0;
}
