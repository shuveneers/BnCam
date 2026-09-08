#include "../SpectraNeuralProductionPolicy.h"

#include <cassert>
#include <iostream>

using namespace bncam::spectra::neural;

static NeuralProductionFrameEvidence validEvidence() {
    NeuralProductionFrameEvidence in{};
    in.frameId = 42u;
    in.rawWidth = 4000u;
    in.rawHeight = 3000u;
    in.sensorArrangement = bncam::raw::CFA_RGGB;
    in.rawLevelsValid = true;
    in.blackLevelRaw = {{64.0f, 64.0f, 64.0f, 64.0f}};
    in.whiteLevelRaw = {{1023.0f, 1023.0f, 1023.0f, 1023.0f}};
    in.effectiveS = {{0.001f, 0.0011f, 0.0011f, 0.0012f}};
    in.effectiveO = {{1.0e-5f, 1.1e-5f, 1.1e-5f, 1.2e-5f}};
    in.noiseModelTrust = 0.9f;
    in.metadataTrust = {0.9f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f};
    in.blackResidual.trust = 1.0f;
    in.remainingLsc.lensShadingAlreadyApplied = false;
    in.remainingLsc.remainingCorrectionExpected = false;
    in.remainingLsc.hasSpatialGainMap = false;
    in.remainingLsc.mapTrust = 1.0f;
    in.structuredNoise.confidence = 0.7f;
    in.framePhysics.captureDomain = RawCaptureDomain::Raw10;
    in.framePhysics.exposureTimeSeconds = 1.0 / 120.0;
    in.framePhysics.analogGain = 2.0f;
    in.framePhysics.digitalGain = 1.0f;
    in.framePhysics.bitDepth = 10u;
    in.explicitGainMetadataValid = true;
    in.mutationMode = NeuralProductionMutationMode::Auto;
    return in;
}

int main() {
    {
        auto in = validEvidence();
        in.mutationMode = NeuralProductionMutationMode::Off;
        in.rawWidth = 0u;
        in.explicitGainMetadataValid = false;
        const auto out = prepareNeuralProductionContext(in);
        assert(!out.structuralOodSafe);
        assert(!out.controls.enabled);
        assert(out.controls.noiseReduction == 0.0f);
        assert(out.structuralBypassReason == NeuralBypassReason::NeuralDisabled);
    }
    {
        const auto out = prepareNeuralProductionContext(validEvidence());
        assert(out.structuralOodSafe);
        assert(out.controls.enabled);
        assert(out.controls.noiseReduction == 1.0f);
        assert(out.core.cfa.valid());
        assert(out.framePhysics.valid());
    }
    {
        // Student-v1 has no active gain FiLM projection. Missing explicit analog/digital split
        // therefore stays structurally safe when the caller supplies neutral 1/1 placeholders.
        auto in = validEvidence();
        in.explicitGainMetadataValid = false;
        in.framePhysics.analogGain = 1.0f;
        in.framePhysics.digitalGain = 1.0f;
        const auto out = prepareNeuralProductionContext(in);
        assert(out.structuralOodSafe);
        assert(out.structuralBypassReason == NeuralBypassReason::None);
    }
    {
        // Invalid physics still fails closed; the relaxed gate never licenses malformed values.
        auto in = validEvidence();
        in.explicitGainMetadataValid = false;
        in.framePhysics.analogGain = 0.0f;
        const auto out = prepareNeuralProductionContext(in);
        assert(!out.structuralOodSafe);
        assert(out.structuralBypassReason == NeuralBypassReason::OodUnsafe);
    }
    {
        auto in = validEvidence();
        in.sensorArrangement = bncam::raw::CFA_MONO;
        const auto out = prepareNeuralProductionContext(in);
        assert(!out.structuralOodSafe);
        assert(out.structuralBypassReason == NeuralBypassReason::UnsupportedCfa);
    }
    {
        auto in = validEvidence();
        in.remainingLsc.remainingCorrectionExpected = true;
        in.remainingLsc.mapTrust = 0.8f;
        const auto out = prepareNeuralProductionContext(in);
        assert(!out.structuralOodSafe);
        assert(out.structuralBypassReason == NeuralBypassReason::MissingRequiredLsc);
    }
    {
        auto prepared = prepareNeuralProductionContext(validEvidence());
        NeuralRuntimeReadiness runtime{};
        runtime.oodSafe = true;
        assert(mergeProductionReadiness(prepared, runtime).oodSafe);
        prepared.structuralOodSafe = false;
        assert(!mergeProductionReadiness(prepared, runtime).oodSafe);
    }

    std::cout << "NeuralProductionPolicyTest PASS\n";
    return 0;
}
