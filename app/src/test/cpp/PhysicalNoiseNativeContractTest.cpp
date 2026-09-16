#include "../../main/cpp/NativeRenderQualityConfig.h"
#include "../../main/cpp/SpectraNoiseAuthorityPolicy.h"

#include <cassert>
#include <cmath>

int main() {
    FinalSensorCalibrationNative calibration{};
    assert(!calibration.physicalNoiseModelAvailable());

    calibration.hasNoiseProfile = true;
    calibration.noiseProfileApplied = true;
    calibration.noiseProfileValid = true;
    calibration.noiseProfilePairCount = 4;
    calibration.noiseProfileChannelCount = 4;
    assert(calibration.physicalNoiseModelAvailable());

    calibration.noiseProfileChannelCount = 3;
    assert(!calibration.physicalNoiseModelAvailable());
    calibration.noiseProfileChannelCount = 4;

    NativeRenderQualityConfig quality{};
    assert(quality.captureSensitivityIso == 0);
    assert(std::abs(quality.profileSpectraStrength - 0.70f) < 1.0e-6f);
    assert(std::abs(quality.profileNeuralAdaptiveResponse - 0.45f) < 1.0e-6f);

    // SPECTRA is an optional consumer: ISO alone can never synthesize noise authority.
    const auto unavailable = bncam::spectra::resolveSpectraNoiseAuthority(
            0.75f, 1.0f, 1.0f, false);
    assert(!unavailable.physicalModelUsable);
    assert(unavailable.physicalModelAuthority == 0.0f);
    assert(unavailable.isoFallbackAuthority == 0.0f);
    assert(unavailable.combinedNoisePressure == 0.0f);

    const auto available = bncam::spectra::resolveSpectraNoiseAuthority(
            0.75f, 1.0f, 1.0f, calibration.physicalNoiseModelAvailable());
    assert(available.physicalModelUsable);
    assert(available.physicalModelAuthority == 1.0f);
    assert(available.isoFallbackAuthority == 0.0f);
    assert(std::abs(available.combinedNoisePressure - 0.75f) < 1.0e-6f);

    return 0;
}
