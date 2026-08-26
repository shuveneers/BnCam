#pragma once

#include <algorithm>
#include <cmath>

namespace bncam::spectra {

struct ContextFusionNoRegretInput {
    float beforeEnergy = 0.0f;
    float afterEnergy = 0.0f;
    float predictedTarget = 0.0f;
    float beforeStructureEnergy = 0.0f;
    float afterStructureEnergy = 0.0f;
    float minimumResidualRatio = 0.70f;
    float detailRetentionFloor = 0.95f;
    float tileConfidence = 1.0f;
    float riskImprovement = 0.0f;
    bool protectStructure = true;
};

struct ContextFusionNoRegretDecision {
    bool correctionWasNeeded = false;
    bool worsened = false;
    bool noImprovement = false;
    bool residualFloorLimited = false;
    bool detailFloorLimited = false;
    bool reject = false;
    float noiseDominance = 0.0f;
    float structureRetention = 1.0f;
    float maximumSafeAcceptance = 1.0f;
    float acceptance = 0.0f;
};

inline float contextFusionSmoothstep(float edge0, float edge1, float value) {
    if (!std::isfinite(value) || !(edge1 > edge0)) return 0.0f;
    const float t = std::clamp((value - edge0) / (edge1 - edge0), 0.0f, 1.0f);
    return t * t * (3.0f - 2.0f * t);
}

// For the provenance structure metric
//   0.5 * (|L-R| + |U-D|)
// with independent Gaussian sensor noise of variance sigma^2, the expected
// squared metric is ~1.64*sigma^2. Removing this analytically expected floor
// keeps ordinary sensor noise from masquerading as image structure.
inline constexpr float kContextFusionStructureNoiseFloorScale = 1.64f;

inline float contextFusionAmplitudeLimitedWeight(
        float beforeEnergy,
        float afterEnergy,
        float requiredMinimumEnergy) {
    beforeEnergy = std::max(0.0f, beforeEnergy);
    afterEnergy = std::max(0.0f, afterEnergy);
    requiredMinimumEnergy = std::max(0.0f, requiredMinimumEnergy);
    if (!(afterEnergy < requiredMinimumEnergy && beforeEnergy > requiredMinimumEnergy)) return 1.0f;
    const float beforeAmplitude = std::sqrt(beforeEnergy);
    const float afterAmplitude = std::sqrt(afterEnergy);
    const float minimumAmplitude = std::sqrt(requiredMinimumEnergy);
    const float denominator = beforeAmplitude - afterAmplitude;
    if (!(denominator > 1.0e-12f)) return 0.0f;
    return std::clamp((beforeAmplitude - minimumAmplitude) / denominator, 0.0f, 1.0f);
}

inline ContextFusionNoRegretDecision resolveContextFusionNoRegret(
        const ContextFusionNoRegretInput& input) {
    ContextFusionNoRegretDecision out{};
    const float target = std::max(1.0e-12f, input.predictedTarget);
    const float beforeEnergy = std::max(0.0f, input.beforeEnergy);
    const float afterEnergy = std::max(0.0f, input.afterEnergy);
    const float confidence = std::clamp(input.tileConfidence, 0.0f, 1.0f);

    out.correctionWasNeeded = beforeEnergy > target * 1.035f;
    out.worsened = afterEnergy > beforeEnergy * 1.03f;

    const float structureNoiseFloor = kContextFusionStructureNoiseFloorScale * target;
    const float beforeExcessStructure = std::max(0.0f, input.beforeStructureEnergy - structureNoiseFloor);
    const float afterExcessStructure = std::max(0.0f, input.afterStructureEnergy - structureNoiseFloor);
    const float excessStructureRatio = beforeExcessStructure / target;
    const bool structureRelevant = input.protectStructure && excessStructureRatio > 2.25f;
    out.structureRetention = beforeExcessStructure > 1.0e-12f
            ? std::clamp(afterExcessStructure / beforeExcessStructure, 0.0f, 2.0f)
            : 1.0f;

    const float noiseNeed = contextFusionSmoothstep(1.035f, 2.40f, beforeEnergy / target);
    const float flatness = 1.0f - contextFusionSmoothstep(1.0f, 3.25f, excessStructureRatio);
    out.noiseDominance = std::clamp(noiseNeed * flatness, 0.0f, 1.0f);

    const float relativeReduction = beforeEnergy > 1.0e-12f
            ? (beforeEnergy - afterEnergy) / beforeEnergy : 0.0f;
    out.noImprovement = out.correctionWasNeeded &&
            relativeReduction < 0.0008f && input.riskImprovement < 0.001f;

    if (out.worsened || out.noImprovement) {
        out.reject = true;
        out.maximumSafeAcceptance = 0.0f;
        out.acceptance = 0.0f;
        return out;
    }

    // Instead of rejecting an over-clean candidate, solve the blend weight that
    // lands exactly on the configured physical residual floor. Under the normal
    // residual-amplitude scaling assumption this keeps the final tile at or above
    // the predicted floor while retaining as much safe denoise as possible.
    if (input.protectStructure) {
        const float minimumEnergy = target * std::clamp(input.minimumResidualRatio, 0.0f, 1.25f);
        const float residualLimit = contextFusionAmplitudeLimitedWeight(
                beforeEnergy, afterEnergy, minimumEnergy);
        if (residualLimit < 0.999f) {
            out.residualFloorLimited = true;
            out.maximumSafeAcceptance = std::min(out.maximumSafeAcceptance, residualLimit);
        }
    }

    if (structureRelevant) {
        const float desiredStructureEnergy = beforeExcessStructure *
                std::clamp(input.detailRetentionFloor, 0.0f, 1.0f);
        const float detailLimit = contextFusionAmplitudeLimitedWeight(
                beforeExcessStructure, afterExcessStructure, desiredStructureEnergy);
        if (detailLimit < 0.999f) {
            out.detailFloorLimited = true;
            out.maximumSafeAcceptance = std::min(out.maximumSafeAcceptance, detailLimit);
        }
    }

    const float improvementAuthority = out.correctionWasNeeded
            ? contextFusionSmoothstep(-0.002f, 0.15f, input.riskImprovement)
            : 0.68f;
    const float reductionAuthority = out.correctionWasNeeded
            ? contextFusionSmoothstep(0.0025f, 0.20f, relativeReduction)
            : 0.68f;
    float benefitAuthority = std::max(improvementAuthority, 0.88f * reductionAuthority);

    // In a physically predicted, noise-dominated flat tile, a candidate that
    // moves energy toward the target is inherently useful. Give it a substantial
    // acceptance floor instead of forcing it through a texture-biased global gate.
    if (afterEnergy <= beforeEnergy) {
        const float flatNoiseFloor = out.noiseDominance * (0.72f + 0.18f * confidence);
        benefitAuthority = std::max(benefitAuthority, flatNoiseFloor);
    }

    // When the candidate violates the detail floor, maximumSafeAcceptance already
    // solves the exact partial blend that restores the configured floor. Do not
    // punish that safe partial blend a second time based on the unblended candidate.
    const float detailAuthority = structureRelevant && !out.detailFloorLimited
            ? contextFusionSmoothstep(
                    std::max(0.0f, input.detailRetentionFloor - 0.08f),
                    1.0f,
                    out.structureRetention)
            : 1.0f;
    const float confidenceAuthority = 0.62f + 0.38f * confidence;
    float acceptance = (0.42f + 0.58f * benefitAuthority) * detailAuthority * confidenceAuthority;

    if (out.noiseDominance > 0.65f && afterEnergy <= beforeEnergy) {
        acceptance = std::max(acceptance, (0.64f + 0.20f * confidence) * out.noiseDominance);
    }

    out.acceptance = std::clamp(
            std::min(acceptance, out.maximumSafeAcceptance),
            0.0f,
            1.0f);
    out.reject = out.acceptance <= 0.0f;
    return out;
}

} // namespace bncam::spectra
