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
    for (int ch = 0; ch < 4; ++ch) {
        calibration.effectiveS[ch] = 1.0e-4 + ch * 1.0e-5;
        calibration.effectiveO[ch] = 1.0e-6 + ch * 1.0e-7;
    }
    assert(calibration.physicalNoiseModelAvailable());

    calibration.noiseProfileChannelCount = 3;
    assert(!calibration.physicalNoiseModelAvailable());
    calibration.noiseProfileChannelCount = 4;

    // Native SPECTRA gate: profile request may only become active after physical S/O is valid.
    assert(resolveSpectraProcessingMode(false, calibration) == 0);
    assert(resolveSpectraProcessingMode(true, calibration) == 1);

    FinalSensorCalibrationNative unavailableForSpectra = calibration;
    for (int ch = 0; ch < 4; ++ch) {
        unavailableForSpectra.effectiveS[ch] = 0.0;
        unavailableForSpectra.effectiveO[ch] = 0.0;
    }
    assert(!unavailableForSpectra.physicalNoiseModelAvailable());
    assert(resolveSpectraProcessingMode(true, unavailableForSpectra) == 0);

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
