#pragma once

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <string>

namespace bncam::spectra2 {

enum class ChromaBandKind {
    Fine,
    Mid,
    Low
};

inline const char* chromaBandName(ChromaBandKind kind) {
    switch (kind) {
        case ChromaBandKind::Fine: return "FINE";
        case ChromaBandKind::Mid: return "MID";
        case ChromaBandKind::Low: return "LOW";
    }
    return "UNKNOWN";
}

/**
 * Sampled pre-demosaic opponent-energy decomposition introduced in Milestone 3A
 * and consumed by the active band-limited Milestone 3B kernels.
 * The three energies are proxies in distinct spatial bands; they are not claimed
 * to be an orthogonal wavelet decomposition or calibrated sensor variances.
 */
struct ChromaBandEnergySnapshot {
    float fineEnergy = 0.0f;
    float midEnergy = 0.0f;
    float lowEnergy = 0.0f;
    // Delta 21: per-opponent-channel energies use the same samples/bands as the aggregate
    // values. They are evidence only; no asymmetric correction authority is granted yet.
    float redFineEnergy = 0.0f;
    float redMidEnergy = 0.0f;
    float redLowEnergy = 0.0f;
    float blueFineEnergy = 0.0f;
    float blueMidEnergy = 0.0f;
    float blueLowEnergy = 0.0f;
    float rowPatternProxy = 0.0f;
    float columnPatternProxy = 0.0f;
    std::uint64_t sampleCount = 0;
    std::uint64_t redSampleCount = 0;
    std::uint64_t blueSampleCount = 0;
    float redBlueSampleBalance = 0.0f;
    float confidence = 0.0f;
    std::string status = "UNAVAILABLE";
    std::string method =
            "R_MINUS_GREF_B_MINUS_GREF_SAME_COLOUR_RADII_2_4_8_STRIDE16_SAMPLED";
};

struct ChromaBandPlan {
    ChromaBandKind kind = ChromaBandKind::Fine;
    bool enabled = false;
    std::string status = "UNINITIALIZED";
    std::string kernel = "NONE";
    std::string targetStatus = "UNAVAILABLE";
    std::string noRegretScope = "COMBINED_PASS_GATE";
    float inputEnergy = 0.0f;
    float targetFloor = 0.0f;
    float excessEnergy = 0.0f;
    float requiredReductionFraction = 0.0f;
    float authorityScale = 0.0f;
    float maximumCorrectionScale = 0.0f;
    float modelConfidence = 0.0f;
    float visibleChromaAmplification = 1.0f;
    float evidence = 0.0f;
};

struct ChromaBandTelemetry {
    ChromaBandPlan plan{};
    bool applied = false;
    std::string resultStatus = "NOT_RUN";
    std::string outputStage = "UNAVAILABLE";
    std::string runtimeMethod = "NOT_RUN";
    float outputEnergy = 0.0f;
    float reductionPercentage = 0.0f;
    float coefficientEnergyBefore = 0.0f;
    float coefficientEnergyAfter = 0.0f;
    float maximumCorrection = 0.0f;
    float changedPixelFraction = 0.0f;
    std::uint64_t candidatePixelCount = 0;
    std::uint64_t changedPixelCount = 0;
    std::uint64_t structureRejectedPixelCount = 0;
    float meanNoiseSigma = 0.0f;
    float meanStructureWeight = 0.0f;
    float meanShrinkage = 0.0f;
    float shrinkageP90 = 0.0f;
    float maximumShrinkage = 0.0f;
    float processingTimeMs = 0.0f;
    float measurementTimeMs = 0.0f;
    float noRegretMeanAcceptance = 0.0f;
    float noRegretAttenuatedPixelFraction = 0.0f;
};

inline float finiteNonNegative(float value) {
    return std::isfinite(value) ? std::max(0.0f, value) : 0.0f;
}

inline float reductionPercentage(float before, float after) {
    const float safeBefore = finiteNonNegative(before);
    const float safeAfter = finiteNonNegative(after);
    if (safeBefore <= 1.0e-12f) return 0.0f;
    return 100.0f * std::clamp((safeBefore - safeAfter) / safeBefore, -1.0f, 1.0f);
}

/**
 * Conservative Milestone-3B band planner.
 *
 * It activates the band-limited fine/mid/low kernels while retaining bounded
 * authority and correction limits. Target splits remain provisional until device
 * calibration is available.
 */
inline ChromaBandPlan resolveChromaBandPlan(
        ChromaBandKind kind,
        float inputEnergy,
        float predictedChromaFloor,
        float visibleChromaAmplification,
        float modelConfidence,
        float isoAuthority,
        float configuredStrength = 0.0f,
        float directionalEvidence = 0.0f
) {
    ChromaBandPlan plan{};
    plan.kind = kind;
    plan.inputEnergy = finiteNonNegative(inputEnergy);
    plan.visibleChromaAmplification = std::isfinite(visibleChromaAmplification)
            ? std::clamp(visibleChromaAmplification, 0.25f, 8.0f)
            : 1.0f;
    plan.modelConfidence = std::isfinite(modelConfidence)
            ? std::clamp(modelConfidence, 0.0f, 1.0f)
            : 0.0f;
    plan.evidence = std::isfinite(directionalEvidence)
            ? std::clamp(directionalEvidence, 0.0f, 1.0f)
            : 0.0f;

    const float floor = finiteNonNegative(predictedChromaFloor);
    const float floorScale = kind == ChromaBandKind::Fine
            ? 1.00f
            : (kind == ChromaBandKind::Mid ? 0.38f : 0.16f);

    // The floor is measured in the pre-demosaic opponent domain, while the user sees the
    // residual after demosaic/WB/CCM/tone have amplified it. Previously visible amplification
    // only increased authority after the budget had already decided that the raw-domain floor
    // was reached. On the 2026-08-15 Natural A/B that left Pass 2 with only ~9% reduction while
    // the same chroma residual was predicted to be amplified ~2.85x downstream.
    //
    // Fine/mid bands now normalize their target to the visible domain. The divisor is bounded
    // and linear in amplitude (rather than squared) because the current band energy proxy is
    // empirical, not a calibrated RGB variance. Low-frequency cloud/banding keeps its separate
    // field-aware budget and is intentionally not changed here.
    const float visibleFloorDivisor = kind == ChromaBandKind::Low
            ? 1.0f
            : std::clamp(plan.visibleChromaAmplification, 1.0f, 3.5f);
    plan.targetFloor = (floor * floorScale) / visibleFloorDivisor;
    plan.targetStatus = floor > 0.0f
            ? (kind == ChromaBandKind::Low
                    ? "PRE_DEMOSAIC_LOW_FREQUENCY_FLOOR_FIELD_BUDGET"
                    : "VISIBLE_DOMAIN_NORMALIZED_PRE_DEMOSAIC_CHROMA_FLOOR")
            : "FLOOR_UNAVAILABLE_CONSERVATIVE_FALLBACK";
    plan.excessEnergy = std::max(0.0f, plan.inputEnergy - plan.targetFloor);
    plan.requiredReductionFraction = plan.inputEnergy > 1.0e-12f
            ? std::clamp(plan.excessEnergy / plan.inputEnergy, 0.0f, 1.0f)
            : 0.0f;

    switch (kind) {
        case ChromaBandKind::Fine:
            plan.kernel = "M3B_FINE_WIENER_DETAIL_RADIUS2";
            break;
        case ChromaBandKind::Mid:
            plan.kernel = "M3B_MID_ATROUS_RESIDUAL_RADIUS2_TO6";
            break;
        case ChromaBandKind::Low:
            plan.kernel = "M3B_LOW_CONTINUOUS_FIELD_SOFT_THRESHOLD";
            plan.noRegretScope = "PASS3_TILE_GATE";
            break;
    }

    if (plan.inputEnergy <= 0.0f) {
        plan.status = "NO_VALID_BAND_ENERGY";
        return plan;
    }

    const float confidenceThreshold = kind == ChromaBandKind::Low ? 0.20f : 0.15f;
    if (plan.modelConfidence < confidenceThreshold) {
        plan.status = "LOW_MODEL_CONFIDENCE";
        return plan;
    }

    const float normalizedIso = std::isfinite(isoAuthority)
            ? std::clamp(isoAuthority, 0.0f, 1.5f) / 1.5f
            : 0.0f;
    const float visiblePressure = std::clamp(
            (plan.visibleChromaAmplification - 1.0f) / 1.5f,
            0.0f,
            1.0f
    );
    const float need = std::clamp(
            0.72f * plan.requiredReductionFraction +
                    0.18f * visiblePressure +
                    0.10f * normalizedIso,
            0.0f,
            1.0f
    );

    const float enableThreshold = kind == ChromaBandKind::Fine
            ? 0.015f
            : (kind == ChromaBandKind::Mid ? 0.045f : 0.075f);
    const bool directionalNeed = kind == ChromaBandKind::Low && plan.evidence >= 0.18f;
    if (need < enableThreshold && !directionalNeed) {
        plan.status = "BAND_BUDGET_ALREADY_REACHED";
        return plan;
    }

    plan.enabled = true;
    plan.status = floor > 0.0f
            ? "ENABLED_CONSERVATIVE_M3B_ACTIVE_BAND_KERNEL"
            : "ENABLED_FLOOR_FALLBACK_CONSERVATIVE_M3B";

    const float strengthMultiplier = (configuredStrength >= 0.0f)
            ? (1.0f + configuredStrength * 0.85f)
            : (1.0f + configuredStrength * 0.55f);

    const float minimumScale = kind == ChromaBandKind::Fine
            ? 0.65f
            : (kind == ChromaBandKind::Mid ? 0.50f : 0.45f);
    const float maximumScale = kind == ChromaBandKind::Fine
            ? 0.95f
            : (kind == ChromaBandKind::Mid ? 0.88f : 0.80f);
    plan.authorityScale = std::clamp(
            (minimumScale + (maximumScale - minimumScale) * need) * strengthMultiplier,
            0.0f,
            0.98f
    );
    plan.maximumCorrectionScale = std::clamp(
            (0.72f + 0.20f * need) * strengthMultiplier,
            0.0f,
            0.98f
    );
    return plan;
}

} // namespace bncam::spectra2
