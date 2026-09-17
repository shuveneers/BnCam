#include "../../main/cpp/SpectraNeuralConditioning.h"

#include <array>
#include <cassert>

using namespace bncam::spectra::neural;

namespace {

SpectraNeuralGlobalConditioning makeGlobal(RawCaptureDomain domain) {
    SpectraNeuralGlobalConditioning global{};
    global.frame.captureDomain = domain;
    global.frame.exposureTimeSeconds = 1.0 / 30.0;
    global.frame.analogGain = 1.0f;
    global.frame.digitalGain = 1.0f;
    global.frame.bitDepth = 12u;
    global.conservativeMetadataTrust = 0.90f;
    global.noiseModelTrust = 0.85f;
    global.blackLevelTrust = 0.95f;
    global.remainingLscTrust = 0.80f;
    global.lensShadingAlreadyApplied = false;
    global.remainingLscExpected = true;
    global.structuredNoise.rowPeriodicity = 0.10f;
    global.structuredNoise.columnPeriodicity = 0.05f;
    global.structuredNoise.fixedPattern = 0.08f;
    global.structuredNoise.dsnuLike = 0.04f;
    global.structuredNoise.prnuLike = 0.03f;
    global.structuredNoise.lowFrequencyResidual = 0.12f;
    global.structuredNoise.lowFrequencyChroma = 0.07f;
    global.structuredNoise.channelImbalance = 0.02f;
    global.structuredNoise.spatialBlackDrift = 0.01f;
    global.structuredNoise.rareReadoutPattern = 0.0f;
    global.structuredNoise.confidence = 0.75f;
    return global;
}

} // namespace

int main() {
    const auto raw10 = makeGlobal(RawCaptureDomain::Raw10);
    const auto rawSensor = makeGlobal(RawCaptureDomain::RawSensor);

    assert(raw10.valid());
    assert(rawSensor.valid());

    // The storage/container domain is deliberately NOT a model-conditioning feature.
    // With identical physical inputs and bit depth, RAW10 and RAW_SENSOR must produce
    // bit-identical global conditioning. Only genuine physical-domain differences such
    // as bit depth, normalized samples, S/O, exposure or metadata evidence may differ.
    const auto raw10Vector = encodeGlobalConditioning(raw10);
    const auto rawSensorVector = encodeGlobalConditioning(rawSensor);
    assert(raw10Vector == rawSensorVector);

    // Prove that a real physical input can still change conditioning: bit depth is part
    // of the model ABI, unlike the RAW container label.
    auto differentBitDepth = rawSensor;
    differentBitDepth.frame.bitDepth = 14u;
    const auto differentVector = encodeGlobalConditioning(differentBitDepth);
    assert(differentVector != rawSensorVector);

    return 0;
}
