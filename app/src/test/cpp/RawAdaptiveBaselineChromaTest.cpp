#include "../../main/cpp/RawAdaptiveBaselineChroma.h"

#include <cassert>

int main() {
    using namespace bncam::raw_baseline;

    const AdaptiveChromaPlan cleanMain = resolveAdaptiveChromaPlan({
            true,
            true,
            0.85,
            4.0e-5,
            {7.5e-5, 7.5e-5, 1.80},
            {7.5e-5, 7.5e-5, 1.80}
    });
    assert(cleanMain.blendRedGreen < 0.10f);
    assert(cleanMain.blendBlueGreen < 0.10f);

    const AdaptiveChromaPlan noisyAmplifiedRaw = resolveAdaptiveChromaPlan({
            true,
            true,
            0.85,
            1.6e-4,
            {8.35e-4, 1.10e-3, 3.94},
            {8.35e-4, 1.10e-3, 3.94}
    });
    assert(noisyAmplifiedRaw.ready);
    assert(noisyAmplifiedRaw.blendRedGreen > 0.40f);
    assert(noisyAmplifiedRaw.blendBlueGreen > 0.40f);
    assert(noisyAmplifiedRaw.blendRedGreen > cleanMain.blendRedGreen + 0.30f);


    // A compact observer below the physical prediction is not evidence of scene structure.
    // It must therefore keep full physical-model agreement instead of suppressing the baseline.
    const AdaptiveChromaPlan measuredBelowPrediction = resolveAdaptiveChromaPlan({
            true,
            true,
            0.85,
            1.6e-4,
            {8.35e-4, 8.35e-6, 3.94},
            {8.35e-4, 8.35e-6, 3.94}
    });
    assert(measuredBelowPrediction.ready);
    assert(measuredBelowPrediction.blendRedGreen > 0.40f);
    assert(measuredBelowPrediction.blendBlueGreen > 0.40f);

    // Scene residuals far above the physical prediction are not permission to denoise harder.
    const AdaptiveChromaPlan disagreement = resolveAdaptiveChromaPlan({
            true,
            true,
            0.85,
            1.6e-4,
            {8.35e-4, 1.336e-2, 3.94},
            {8.35e-4, 1.336e-2, 3.94}
    });
    assert(disagreement.ready);
    assert(disagreement.blendRedGreen < noisyAmplifiedRaw.blendRedGreen);
    assert(disagreement.blendBlueGreen < noisyAmplifiedRaw.blendBlueGreen);

    const AdaptiveChromaPlan noPhysicalModel = resolveAdaptiveChromaPlan({
            false,
            true,
            1.0,
            1.6e-4,
            {8.35e-4, 1.10e-3, 3.94},
            {8.35e-4, 1.10e-3, 3.94}
    });
    assert(!noPhysicalModel.ready);
    assert(noPhysicalModel.blendRedGreen == 0.0f);
    assert(noPhysicalModel.blendBlueGreen == 0.0f);

    return 0;
}
