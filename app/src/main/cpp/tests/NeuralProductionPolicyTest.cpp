#include "../SpectraNeuralProductionPolicy.h"

#include <cassert>
#include <cmath>
#include <iostream>

using namespace bncam::spectra::neural;

static_assert(kBaseSpatialConditioningChannelCount == 14u);
static_assert(kGlobalConditioningValueCount == 18u);
static_assert(!kAdaptiveV2Prod1ConsumesExplicitGainConditioning);
static_assert(kAdaptiveV2Prod1NeutralAnalogGain == 1.0f);
static_assert(kAdaptiveV2Prod1NeutralDigitalGain == 1.0f);

static bool near(float a, float b, float epsilon = 1.0e-6f) {
    return std::abs(a - b) <= epsilon;
}

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
        const auto config = adaptiveV2Prod1ConditioningConfig();
        assert(config.valid());
        assert(config.logSigmaFloor == 1.0e-8f);
        assert(config.headroomSpan == 0.08f);
        assert(config.clippingEpsilon == 0.0f);
    }
    {
        // Frozen 18-value ordering/formulas used by training and runtime.
        auto in = validEvidence();
        in.framePhysics.exposureTimeSeconds = 0.01;
        in.framePhysics.analogGain = kAdaptiveV2Prod1NeutralAnalogGain;
        in.framePhysics.digitalGain = kAdaptiveV2Prod1NeutralDigitalGain;
        in.framePhysics.bitDepth = 10u;
        in.noiseModelTrust = 0.90f;
        in.blackResidual.trust = 0.85f;
        in.remainingLsc.mapTrust = 0.80f;
        in.structuredNoise.rowPeriodicity = 0.10f;
        in.structuredNoise.columnPeriodicity = 0.05f;
        in.structuredNoise.fixedPattern = 0.15f;
        in.structuredNoise.dsnuLike = 0.08f;
        in.structuredNoise.prnuLike = 0.04f;
        in.structuredNoise.lowFrequencyResidual = 0.12f;
        in.structuredNoise.lowFrequencyChroma = 0.07f;
        in.structuredNoise.channelImbalance = 0.03f;
        in.structuredNoise.spatialBlackDrift = 0.02f;
        in.structuredNoise.rareReadoutPattern = 0.01f;
        in.structuredNoise.confidence = 0.75f;

        const auto prepared = prepareNeuralProductionContext(in);
        assert(prepared.structuralOodSafe);
        const auto global = buildGlobalConditioning(prepared.core, prepared.framePhysics);
        assert(global.valid());
        const auto values = encodeGlobalConditioning(global);

        assert(near(values[globalConditioningIndex(GlobalConditioningField::LogExposureSeconds)],
                    static_cast<float>(std::log(0.01))));
        assert(near(values[globalConditioningIndex(GlobalConditioningField::LogAnalogGain)], 0.0f));
        assert(near(values[globalConditioningIndex(GlobalConditioningField::LogDigitalGain)], 0.0f));
        assert(near(values[globalConditioningIndex(GlobalConditioningField::BitDepthOver32)], 10.0f / 32.0f));
        assert(near(values[globalConditioningIndex(GlobalConditioningField::NoiseModelTrust)], 0.90f));
        assert(near(values[globalConditioningIndex(GlobalConditioningField::BlackLevelTrust)], 0.85f));
        assert(near(values[globalConditioningIndex(GlobalConditioningField::RemainingLscTrust)], 0.80f));
        assert(near(values[globalConditioningIndex(GlobalConditioningField::RowPeriodicity)], 0.10f));
        assert(near(values[globalConditioningIndex(GlobalConditioningField::ColumnPeriodicity)], 0.05f));
        assert(near(values[globalConditioningIndex(GlobalConditioningField::FixedPattern)], 0.15f));
        assert(near(values[globalConditioningIndex(GlobalConditioningField::DsnuLike)], 0.08f));
        assert(near(values[globalConditioningIndex(GlobalConditioningField::PrnuLike)], 0.04f));
        assert(near(values[globalConditioningIndex(GlobalConditioningField::LowFrequencyResidual)], 0.12f));
        assert(near(values[globalConditioningIndex(GlobalConditioningField::LowFrequencyChroma)], 0.07f));
        assert(near(values[globalConditioningIndex(GlobalConditioningField::ChannelImbalance)], 0.03f));
        assert(near(values[globalConditioningIndex(GlobalConditioningField::SpatialBlackDrift)], 0.02f));
        assert(near(values[globalConditioningIndex(GlobalConditioningField::RareReadoutPattern)], 0.01f));
        assert(near(values[globalConditioningIndex(GlobalConditioningField::StructuredConfidence)], 0.75f));
    }
    {
        auto in = validEvidence();
        in.mutationMode = NeuralProductionMutationMode::Off;
        in.rawWidth = 0u;
        in.explicitGainMetadataValid = false;
        const auto out = prepareNeuralProductionContext(in);
        assert(!out.structuralOodSafe);
        assert(!out.controls.enabled);
        assert(out.controls.noiseReduction == 0.0f);
        assert(out.structuralBypassReason == NeuralBypassReason::UserDisabled);
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
        // adaptive-v2-prod1 requires neutral gain slots after the accepted
        // zero_forbidden_gain_film_columns_v1 production adapter.
        auto in = validEvidence();
        in.explicitGainMetadataValid = false;
        in.framePhysics.analogGain = kAdaptiveV2Prod1NeutralAnalogGain;
        in.framePhysics.digitalGain = kAdaptiveV2Prod1NeutralDigitalGain;
        const auto out = prepareNeuralProductionContext(in);
        assert(out.structuralOodSafe);
        assert(out.structuralBypassReason == NeuralBypassReason::None);
    }
    {
        // Invalid physics still fails closed.
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
