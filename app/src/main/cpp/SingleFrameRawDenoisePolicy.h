#pragma once

#include <algorithm>
#include <cmath>

namespace bncam::singleframe {

/**
 * Physical pre-demosaic single-frame RAW luminance denoise policy.
 *
 * Phase-6 ownership contract:
 * - authority is derived only from calibrated sensor variance V(x)=S*x+O evidence;
 * - RAW container type and capture ISO are deliberately absent from the API;
 * - this owner controls luminance cleanup only;
 * - chroma / low-frequency chroma are not part of this plan and remain separate owners;
 * - the Vulkan shader remains responsible for local per-CFA noise significance and
 *   structure protection.
 */
struct RawDenoisePlan {
    bool active = false;
    float modelConfidence = 0.0f;
    float physicalNoisePressure = 0.0f;
    float lumaAuthority = 0.0f;

    float blendStrength = 0.0f;
    float targetFloorScale = 1.0f;
    float minimumResidualRatio = 1.0f;
    float detailRetentionFloor = 1.0f;
    float maxLinearShift = 0.0f;
};

struct PhysicalNoisePressure {
    bool modelDriven = false;
    float calibratedNoiseSigma = 0.0f;
    float modelConfidence = 0.0f;
    float pressure = 0.0f;
};

inline float smoothstep01(float edge0, float edge1, float value) {
    if (!std::isfinite(value) || !(edge1 > edge0)) {
        return 0.0f;
    }
    const float t = std::clamp((value - edge0) / (edge1 - edge0), 0.0f, 1.0f);
    return t * t * (3.0f - 2.0f * t);
}

/**
 * Convert already-measured normalized sensor variance into a bounded physical pressure.
 *
 * `meanSensorNoiseVariance` is compact frame evidence evaluated from Camera2 S/O.
 * `calibrationFactor` scales variance, not sigma. Confidence gates authority instead of
 * inventing an ISO or RAW-format fallback.
 */
inline PhysicalNoisePressure resolvePhysicalNoisePressure(
        float meanSensorNoiseVariance,
        float calibrationFactor,
        float modelConfidence) {
    PhysicalNoisePressure out{};

    const float confidence = std::clamp(
            std::isfinite(modelConfidence) ? modelConfidence : 0.0f,
            0.0f, 1.0f);
    const float variance = std::isfinite(meanSensorNoiseVariance)
            ? std::max(0.0f, meanSensorNoiseVariance)
            : 0.0f;
    if (!(variance > 0.0f) || confidence <= 0.0f) {
        return out;
    }

    const float varianceScale = std::clamp(
            std::isfinite(calibrationFactor) ? calibrationFactor : 1.0f,
            0.25f, 4.0f);

    out.modelDriven = true;
    out.calibratedNoiseSigma = std::sqrt(variance * varianceScale);
    out.modelConfidence = confidence;

    // This maps measured normalized-sensor sigma onto an authority coordinate; it does
    // not estimate noise from ISO or rendered brightness.
    constexpr float kReferenceSigma = 0.0015f;
    constexpr float kFullAuthorityStops = 3.25f;
    const float sigmaStops = std::max(
            0.0f,
            std::log2(std::max(out.calibratedNoiseSigma, kReferenceSigma) /
                      kReferenceSigma));
    const float physicalPressure =
            smoothstep01(0.0f, kFullAuthorityStops, sigmaStops);
    out.pressure = std::clamp(physicalPressure * confidence, 0.0f, 1.0f);
    return out;
}

inline RawDenoisePlan resolveRawDenoisePlan(
        float meanSensorNoiseVariance,
        float calibrationFactor,
        float modelConfidence) {
    RawDenoisePlan plan{};
    const PhysicalNoisePressure physical = resolvePhysicalNoisePressure(
            meanSensorNoiseVariance, calibrationFactor, modelConfidence);

    if (!physical.modelDriven || physical.modelConfidence < 0.25f ||
        !(physical.calibratedNoiseSigma > 0.0f)) {
        return plan;
    }

    plan.active = true;
    plan.modelConfidence = physical.modelConfidence;
    plan.physicalNoisePressure = physical.pressure;

    const float p = plan.physicalNoisePressure;
    const float c = plan.modelConfidence;
    const float confidenceScale = 0.70f + 0.30f * c;

    // Phase 6: remove the former fixed denoise floor in physically clean captures. The
    // calibrated S/O pressure now owns how much pre-demosaic cleanup is permitted: near-zero
    // pressure is deliberately close to identity, while high physical pressure retains the
    // strong single-frame cleanup envelope. Local structure/texture decisions still remain
    // entirely inside the resident Vulkan Pass1 shader.
    plan.lumaAuthority = std::clamp(
            (0.04f + 0.86f * p) * confidenceScale,
            0.02f,
            0.90f);

    plan.blendStrength = std::clamp(
            (0.10f + 1.20f * p) * (0.72f + 0.28f * c),
            0.05f,
            1.30f);

    // No-regret/detail envelopes track the same physical pressure. Clean captures preserve
    // almost all measured residual/structure and permit only sub-millipercent linear shifts;
    // noisy captures progressively release more residual reduction authority.
    plan.targetFloorScale = std::clamp(1.08f - 0.28f * p, 0.80f, 1.08f);
    plan.minimumResidualRatio = std::clamp(0.94f - 0.64f * p, 0.28f, 0.94f);
    plan.detailRetentionFloor = std::clamp(0.999f - 0.098f * p, 0.90f, 0.999f);
    plan.maxLinearShift = std::clamp(
            0.0005f + 0.0130f * p,
            0.0004f,
            0.0140f);
    return plan;
}

}  // namespace bncam::singleframe
