#pragma once

#include "NeuralRawDenoisePolicy.h"
#include "SpectraNeuralConditioning.h"

#include <array>
#include <cmath>
#include <cstdint>

namespace bncam::spectra::neural {

// adaptive-v2-prod1 production-conditioning contract.
//
// The accepted Vulkan package applies zero_forbidden_gain_film_columns_v1.
// Its provenance records exact output parity after zeroing global columns
// 1 (analog gain) and 2 (digital gain). Production therefore feeds neutral
// 1x gain values and never synthesizes a gain split from ISO for this package.
constexpr bool kAdaptiveV2Prod1ConsumesExplicitGainConditioning = false;
constexpr float kAdaptiveV2Prod1NeutralAnalogGain = 1.0f;
constexpr float kAdaptiveV2Prod1NeutralDigitalGain = 1.0f;

inline SpectraNeuralConditioningConfig adaptiveV2Prod1ConditioningConfig() noexcept {
    SpectraNeuralConditioningConfig config{};
    config.logSigmaFloor = 1.0e-8f;
    config.headroomSpan = 0.08f;
    config.clippingEpsilon = 0.0f;
    return config;
}

// Production mutation authority. Values intentionally mirror the existing
// FinalSensorCalibrationNative::spectraProcessingMode ABI without including
// NativeRenderQualityConfig.h here.
enum class NeuralProductionMutationMode : std::uint8_t {
    Off = 0,
    Auto = 1,
    Manual = 2,
};

struct NeuralProductionFrameEvidence {
    std::uint64_t frameId = 0u;
    std::uint32_t rawWidth = 0u;
    std::uint32_t rawHeight = 0u;
    int sensorArrangement = bncam::raw::CFA_UNSUPPORTED;
    int cfaOffsetX = 0;
    int cfaOffsetY = 0;

    std::array<float, 4> blackLevelRaw{{0.0f, 0.0f, 0.0f, 0.0f}};
    std::array<float, 4> whiteLevelRaw{{1.0f, 1.0f, 1.0f, 1.0f}};
    bool rawLevelsValid = false;

    std::array<float, 4> effectiveS{{0.0f, 0.0f, 0.0f, 0.0f}};
    std::array<float, 4> effectiveO{{0.0f, 0.0f, 0.0f, 0.0f}};
    float noiseModelTrust = 0.0f;

    MetadataTrustState metadataTrust{};
    BlackLevelResidualState blackResidual{};
    RemainingLensShadingState remainingLsc{};
    StructuredNoiseEvidence structuredNoise{};

    // Physics must come from explicit capture metadata or an equally explicit
    // upstream calibration. No ISO/base-ISO gain split may be fabricated.
    NeuralFramePhysicsContext framePhysics{};
    bool explicitGainMetadataValid = false;

    NeuralProductionMutationMode mutationMode = NeuralProductionMutationMode::Off;

    // Phase 6: one explicit user-control truth for the one Student residual.
    // When false, the narrow Phase-5 compatibility projection is used.
    NeuralDenoiseControls userControls{};
    bool userControlsPresent = false;
};

struct NeuralProductionPreparedContext {
    SpectraCoreSnapshot core{};
    NeuralFramePhysicsContext framePhysics{};
    NeuralDenoiseControls controls{};
    bool structuralOodSafe = false;
    NeuralBypassReason structuralBypassReason = NeuralBypassReason::None;
};

inline bool validProductionMutationMode(NeuralProductionMutationMode mode) noexcept {
    return mode == NeuralProductionMutationMode::Off ||
           mode == NeuralProductionMutationMode::Auto ||
           mode == NeuralProductionMutationMode::Manual;
}

// Frozen Phase-5 compatibility projection. It must reproduce the previous
// full-residual writeback if an old caller has not supplied Phase-6 controls.
inline NeuralDenoiseControls phase5ProductionControls(
        NeuralProductionMutationMode mode) noexcept {
    NeuralDenoiseControls out{};
    out.enabled = mode != NeuralProductionMutationMode::Off;
    if (!out.enabled) {
        // FASE 8: user Off is an exact runtime identity contract. No legacy shaping value
        // remains "effective" while mutation is disabled, even though profile persistence may
        // retain the user's preferred controls for the next On capture.
        return out;
    }
    out.noiseReduction = 1.0f;
    out.lumaNoise = 1.0f;
    out.chromaNoise = 1.0f;
    out.detailProtection = 0.0f;
    out.lowFrequencyCleanup = 1.0f;
    out.adaptiveResponse = 1.0f;
    return out;
}

inline float signedProfileControlToUnit(float value) noexcept {
    const float safe = std::isfinite(value) ? std::clamp(value, -1.0f, 1.0f) : 0.0f;
    return 0.5f * (safe + 1.0f);
}

// Global neural authority is binary by contract: Off=0, On=1. Legacy strength
// values may still arrive through old profile/ABI surfaces, but production ignores them.
inline NeuralDenoiseControls projectVisibleProfileControlsToNeural(
        NeuralProductionMutationMode mode,
        float signedLuma,
        float signedChroma,
        float signedDetailProtection,
        float signedLowFrequency,
        float legacyAdaptiveResponse) noexcept {
    (void) legacyAdaptiveResponse;
    NeuralDenoiseControls out{};
    out.enabled = mode != NeuralProductionMutationMode::Off;
    if (!out.enabled) {
        // FASE 8: Off is not a weak denoise setting. It is a complete neural mutation bypass.
        // Stored Luma/Chroma/Detail/LF values remain profile preferences, not effective controls.
        return out;
    }
    out.noiseReduction = 1.0f;
    out.lumaNoise = signedProfileControlToUnit(signedLuma);
    out.chromaNoise = signedProfileControlToUnit(signedChroma);
    out.detailProtection = signedProfileControlToUnit(signedDetailProtection);
    out.lowFrequencyCleanup = signedProfileControlToUnit(signedLowFrequency);
    // Adaptive response is binary by contract: enabled Neural always runs with full
    // sigma/SNR adaptation. Legacy profile values are accepted only for ABI compatibility.
    out.adaptiveResponse = 1.0f;
    return out;
}

// Legacy profile import compatibility only. Active Phase-6 production callers use the direct
// master projection above; this wrapper keeps old signed-strength profiles deterministic.
inline NeuralDenoiseControls projectProfileControlsToNeural(
        NeuralProductionMutationMode mode,
        float legacySignedMaster,
        float legacySignedLuma,
        float legacySignedChroma,
        float legacySignedDetailProtection,
        float legacySignedLowFrequency,
        float adaptiveResponse) noexcept {
    (void) legacySignedMaster;
    return projectVisibleProfileControlsToNeural(
            mode,
            legacySignedLuma,
            legacySignedChroma,
            legacySignedDetailProtection,
            legacySignedLowFrequency,
            adaptiveResponse);
}

inline NeuralProductionPreparedContext prepareNeuralProductionContext(
        const NeuralProductionFrameEvidence& input) noexcept {
    NeuralProductionPreparedContext out{};
    out.controls = input.userControlsPresent
            ? input.userControls
            : phase5ProductionControls(input.mutationMode);

    // Off wins before evaluating metadata. This preserves the production
    // requirement that a user-disabled denoiser is exact identity even when
    // conditioning metadata is missing or malformed.
    if (!validProductionMutationMode(input.mutationMode)) {
        out.structuralBypassReason = NeuralBypassReason::InvalidControls;
        return out;
    }
    if (input.mutationMode == NeuralProductionMutationMode::Off) {
        // FASE 8: effective runtime controls are all-zero under user Off. Persisted profile
        // preferences are intentionally not copied into the prepared mutation contract.
        out.controls = NeuralDenoiseControls{};
        out.structuralBypassReason = NeuralBypassReason::UserDisabled;
        return out;
    }
    if (!out.controls.valid()) {
        out.structuralBypassReason = NeuralBypassReason::InvalidControls;
        return out;
    }
    if (!out.controls.enabled) {
        out.controls = NeuralDenoiseControls{};
        out.structuralBypassReason = NeuralBypassReason::UserDisabled;
        return out;
    }
    if (out.controls.noiseReduction <= kNeuralAuthorityBypassEpsilon) {
        out.structuralBypassReason = NeuralBypassReason::ZeroAuthority;
        return out;
    }

    out.core.frameId = input.frameId;
    out.core.rawWidth = input.rawWidth;
    out.core.rawHeight = input.rawHeight;
    out.core.cfa = resolveCanonicalBayerPack(
            input.sensorArrangement, input.cfaOffsetX, input.cfaOffsetY);
    out.core.blackLevelRaw = input.blackLevelRaw;
    out.core.whiteLevelRaw = input.whiteLevelRaw;
    out.core.rawLevelsValid = input.rawLevelsValid;
    out.core.noise.shotS = input.effectiveS;
    out.core.noise.readO = input.effectiveO;
    out.core.noise.trust = input.noiseModelTrust;
    out.core.metadataTrust = input.metadataTrust;
    out.core.blackResidual = input.blackResidual;
    out.core.remainingLsc = input.remainingLsc;
    out.core.structuredNoise = input.structuredNoise;
    out.framePhysics = input.framePhysics;

    if (!out.core.cfa.valid()) {
        out.structuralBypassReason = NeuralBypassReason::UnsupportedCfa;
        return out;
    }
    if (!out.core.structurallyValid()) {
        out.structuralBypassReason = NeuralBypassReason::InvalidCoreSnapshot;
        return out;
    }
    if (!out.core.remainingLsc.hasRequiredCondition()) {
        out.structuralBypassReason = NeuralBypassReason::MissingRequiredLsc;
        return out;
    }
    // adaptive-v2-prod1 does not consume active analog/digital gain FiLM projections.
    // Its accepted deployment adapter zeros global columns 1/2 with exact frozen-set
    // output parity, so neutral 1/1 is required model input rather than a fallback.
    // A future package that activates gain conditioning must version its package
    // contract and add an explicit required-metadata gate.
    if (!out.framePhysics.valid()) {
        out.structuralBypassReason = NeuralBypassReason::OodUnsafe;
        return out;
    }

    out.structuralOodSafe = true;
    out.structuralBypassReason = NeuralBypassReason::None;
    return out;
}

inline NeuralRuntimeReadiness mergeProductionReadiness(
        const NeuralProductionPreparedContext& prepared,
        NeuralRuntimeReadiness runtime) noexcept {
    // Production OOD safety is the conjunction of model/backend readiness and
    // structurally supported physical metadata. No hidden confidence threshold
    // is introduced here.
    runtime.oodSafe = runtime.oodSafe && prepared.structuralOodSafe;
    return runtime;
}

} // namespace bncam::spectra::neural
