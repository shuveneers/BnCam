#include "../../main/cpp/NativeRenderQualityConfig.h"
#include "../../main/cpp/SpectraNeuralProductionPolicy.h"

#include <cassert>
#include <cmath>

int main() {
    FinalSensorCalibrationNative calibration{};
    calibration.physicalNoiseJniPayloadReceived = true;

    const double expectedS[4] = {0.000473123456, 0.000557234567, 0.000288345678, 0.000309456789};
    const double expectedO[4] = {0.000001234567, 0.000001345678, 0.000001456789, 0.000001567890};
    for (int ch = 0; ch < 4; ++ch) {
        calibration.effectiveS[ch] = expectedS[ch];
        calibration.effectiveO[ch] = expectedO[ch];
    }

    const auto frozen = calibration.frozenPhysicalNoiseModel();
    assert(frozen.available);
    for (int ch = 0; ch < 4; ++ch) {
        assert(frozen.shotS[ch] == static_cast<float>(expectedS[ch]));
        assert(frozen.readO[ch] == static_cast<float>(expectedO[ch]));
    }

    bncam::spectra::neural::NeuralProductionFrameEvidence evidence{};
    evidence.mutationMode = bncam::spectra::neural::NeuralProductionMutationMode::Auto;
    evidence.effectiveS = frozen.shotS;
    evidence.effectiveO = frozen.readO;
    evidence.noiseModelTrust = frozen.available ? 1.0f : 0.0f;

    const auto prepared = bncam::spectra::neural::prepareNeuralProductionContext(evidence);
    for (int ch = 0; ch < 4; ++ch) {
        assert(prepared.core.noise.shotS[ch] == frozen.shotS[ch]);
        assert(prepared.core.noise.readO[ch] == frozen.readO[ch]);
    }

    // Invalid physical state is all-or-none. No parallel JNI carrier exists to rescue it.
    FinalSensorCalibrationNative invalid = calibration;
    invalid.effectiveS[2] = -1.0;
    const auto unavailable = invalid.frozenPhysicalNoiseModel();
    assert(!unavailable.available);
    for (int ch = 0; ch < 4; ++ch) {
        assert(unavailable.shotS[ch] == 0.0f);
        assert(unavailable.readO[ch] == 0.0f);
    }

    return 0;
}
