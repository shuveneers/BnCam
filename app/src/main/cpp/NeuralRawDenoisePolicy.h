#pragma once

#include "SpectraCoreSnapshot.h"

#include <algorithm>
#include <cmath>
#include <cstdint>

namespace bncam::spectra::neural {

constexpr std::uint32_t kNeuralDenoiseControlsSchemaVersion = 2;
constexpr float kNeuralAuthorityBypassEpsilon = 1.0e-6f;

// Single production noise-reduction control truth. Every field shapes the one
// Student residual; none of these fields authorizes a second post-demosaic NR engine.
// User-facing values are unit-range and are projected directly into Vulkan writeback.
struct NeuralDenoiseControls {
    std::uint32_t schemaVersion = kNeuralDenoiseControlsSchemaVersion;
    bool enabled = false;
    float noiseReduction = 0.0f;
    float lumaNoise = 0.0f;
    float chromaNoise = 0.0f;
    float detailProtection = 0.0f;
    float lowFrequencyCleanup = 0.0f;
    float adaptiveResponse = 0.0f;

    bool valid() const noexcept {
        return schemaVersion == kNeuralDenoiseControlsSchemaVersion &&
               finiteUnit(noiseReduction) && finiteUnit(lumaNoise) && finiteUnit(chromaNoise) &&
               finiteUnit(detailProtection) && finiteUnit(lowFrequencyCleanup) &&
               finiteUnit(adaptiveResponse);
    }
};

enum class NeuralBypassReason : std::uint16_t {
    None = 0,
    UserDisabled,
    ZeroAuthority,
    InvalidControls,
    UnsupportedCfa,
    InvalidCoreSnapshot,
    MissingRequiredLsc,
    InvalidConditioningSchema,
    ModelMissing,
    ModelIntegrityFailed,
    ModelSchemaMismatch,
    BackendUnavailable,
    OodUnsafe,
    BackendFailure,
    NonFiniteOutput,
    PosteriorInvalid
};

inline const char* neuralBypassReasonName(NeuralBypassReason reason) noexcept {
    switch (reason) {
        case NeuralBypassReason::None: return "none";
        case NeuralBypassReason::UserDisabled: return "user_disabled";
        case NeuralBypassReason::ZeroAuthority: return "zero_authority";
        case NeuralBypassReason::InvalidControls: return "invalid_controls";
        case NeuralBypassReason::UnsupportedCfa: return "unsupported_cfa";
        case NeuralBypassReason::InvalidCoreSnapshot: return "invalid_core_snapshot";
        case NeuralBypassReason::MissingRequiredLsc: return "missing_required_lsc";
        case NeuralBypassReason::InvalidConditioningSchema: return "invalid_conditioning_schema";
        case NeuralBypassReason::ModelMissing: return "model_missing";
        case NeuralBypassReason::ModelIntegrityFailed: return "model_integrity_failed";
        case NeuralBypassReason::ModelSchemaMismatch: return "model_schema_mismatch";
        case NeuralBypassReason::BackendUnavailable: return "backend_unavailable";
        case NeuralBypassReason::OodUnsafe: return "ood_unsafe";
        case NeuralBypassReason::BackendFailure: return "backend_failure";
        case NeuralBypassReason::NonFiniteOutput: return "non_finite_output";
        case NeuralBypassReason::PosteriorInvalid: return "posterior_invalid";
        default: return "unknown";
    }
}

struct NeuralRuntimeReadiness {
    bool conditioningSchemaCompatible = false;
    bool modelPresent = false;
    bool modelIntegrityVerified = false;
    bool modelSchemaCompatible = false;
    bool backendAvailable = false;
    bool oodSafe = false;
};

struct NeuralInvocationDecision {
    bool runInference = false;
    float masterAuthority = 0.0f;
    NeuralBypassReason bypassReason = NeuralBypassReason::None;
};

// Central exact-bypass policy. It has no classical fallback branch: any failed
// prerequisite resolves to identity at the caller boundary.
inline NeuralInvocationDecision decideNeuralInvocation(
        const SpectraCoreSnapshot& snapshot,
        const NeuralDenoiseControls& controls,
        const NeuralRuntimeReadiness& runtime) noexcept {
    NeuralInvocationDecision out{};

    if (!controls.valid()) {
        out.bypassReason = NeuralBypassReason::InvalidControls;
        return out;
    }
    if (!controls.enabled) {
        out.bypassReason = NeuralBypassReason::UserDisabled;
        return out;
    }
    if (controls.noiseReduction <= kNeuralAuthorityBypassEpsilon) {
        out.bypassReason = NeuralBypassReason::ZeroAuthority;
        return out;
    }
    if (!snapshot.cfa.valid()) {
        out.bypassReason = NeuralBypassReason::UnsupportedCfa;
        return out;
    }
    if (!snapshot.structurallyValid()) {
        out.bypassReason = NeuralBypassReason::InvalidCoreSnapshot;
        return out;
    }
    if (!snapshot.remainingLsc.hasRequiredCondition()) {
        out.bypassReason = NeuralBypassReason::MissingRequiredLsc;
        return out;
    }
    if (!runtime.conditioningSchemaCompatible) {
        out.bypassReason = NeuralBypassReason::InvalidConditioningSchema;
        return out;
    }
    if (!runtime.modelPresent) {
        out.bypassReason = NeuralBypassReason::ModelMissing;
        return out;
    }
    if (!runtime.modelIntegrityVerified) {
        out.bypassReason = NeuralBypassReason::ModelIntegrityFailed;
        return out;
    }
    if (!runtime.modelSchemaCompatible) {
        out.bypassReason = NeuralBypassReason::ModelSchemaMismatch;
        return out;
    }
    if (!runtime.backendAvailable) {
        out.bypassReason = NeuralBypassReason::BackendUnavailable;
        return out;
    }
    if (!runtime.oodSafe) {
        out.bypassReason = NeuralBypassReason::OodUnsafe;
        return out;
    }

    out.runInference = true;
    out.masterAuthority = std::clamp(controls.noiseReduction, 0.0f, 1.0f);
    out.bypassReason = NeuralBypassReason::None;
    return out;
}

} // namespace bncam::spectra::neural
