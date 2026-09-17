#include "../NativeRenderQualityConfig.h"
#include "../SpectraNeuralProductionPolicy.h"

#include <cassert>
#include <cmath>

namespace {
bool near(float a, float b, float eps = 1.0e-7f) {
    return std::fabs(a - b) <= eps;
}
}

int main() {
    using namespace bncam::spectra::neural;

    // A valid frozen Physical Noise Model remains available independently of Neural Off.
    FinalSensorCalibrationNative calibration{};
    calibration.effectiveS[0] = 0.00047;
    calibration.effectiveS[1] = 0.00055;
    calibration.effectiveS[2] = 0.00029;
    calibration.effectiveS[3] = 0.00031;
    calibration.effectiveO[0] = 1.0e-6;
    calibration.effectiveO[1] = 1.1e-6;
    calibration.effectiveO[2] = 0.9e-6;
    calibration.effectiveO[3] = 1.2e-6;
    calibration.physicalNoiseJniPayloadReceived = true;

    const auto frozen = calibration.frozenPhysicalNoiseModel();
    assert(frozen.available);

    // User Off forces every effective neural control to identity, regardless of stored sliders.
    const auto off = projectVisibleProfileControlsToNeural(
            NeuralProductionMutationMode::Off,
            1.0f, 1.0f, -1.0f, 1.0f, 1.0f);
    assert(!off.enabled);
    assert(near(off.noiseReduction, 0.0f));
    assert(near(off.lumaNoise, 0.0f));
    assert(near(off.chromaNoise, 0.0f));
    assert(near(off.detailProtection, 0.0f));
    assert(near(off.lowFrequencyCleanup, 0.0f));
    assert(near(off.adaptiveResponse, 0.0f));

    NeuralProductionFrameEvidence evidence{};
    evidence.mutationMode = NeuralProductionMutationMode::Off;
    evidence.userControls = off;
    evidence.userControlsPresent = true;
    evidence.effectiveS = frozen.shotS;
    evidence.effectiveO = frozen.readO;
    evidence.noiseModelTrust = 1.0f;

    const auto prepared = prepareNeuralProductionContext(evidence);
    assert(!prepared.controls.enabled);
    assert(prepared.structuralBypassReason == NeuralBypassReason::UserDisabled);
    assert(std::string(neuralBypassReasonName(prepared.structuralBypassReason)) == "user_disabled");

    return 0;
}
