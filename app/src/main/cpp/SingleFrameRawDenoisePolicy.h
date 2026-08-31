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
 * - chroma / low-frequency chroma remain separate owners and receive zero authority here;
 * - the Vulkan shader remains responsible for local per-CFA noise significance and
 *   structure protection.
 *
 * The compatibility chroma fields remain in RawDenoisePlan for the current IspCore ABI.
 * They are intentionally pinned to zero until the call sites are removed in a later
 * ownership-cleanup delta.
 */
struct RawDenoisePlan {
    bool active = false;
    float modelConfidence = 0.0f;
    float physicalNoisePressure = 0.0f;
    float lumaAuthority = 0.0f;

    // Phase-6 compatibility fields. This policy does not own chroma.
    float chromaAuthority = 0.0f;
    float lowFrequencyChromaAuthority = 0.0f;

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

    // Preserve the established conservative luma envelope in this ownership delta.
    // Phase-6 quality tuning can now change this owner without silently driving chroma.
    plan.lumaAuthority = std::clamp((0.16f + 0.70f * p) * c, 0.10f, 0.88f);

    // Critical Phase-6 ownership boundary: no pre-demosaic chroma authority originates
    // from the single-frame luminance baseline.
    plan.chromaAuthority = 0.0f;
    plan.lowFrequencyChromaAuthority = 0.0f;

    plan.blendStrength =
            std::clamp((0.30f + 1.00f * p) * (0.76f + 0.24f * c),
                       0.14f, 1.28f);

    plan.targetFloorScale = std::clamp(1.02f - 0.18f * p, 0.82f, 1.04f);
    plan.minimumResidualRatio = std::clamp(0.78f - 0.44f * p, 0.30f, 0.82f);
    plan.detailRetentionFloor = std::clamp(0.992f - 0.090f * p, 0.90f, 0.995f);
    plan.maxLinearShift =
            std::clamp(0.0018f + 0.0100f * p, 0.0012f, 0.0135f);
    return plan;
}

}  // namespace bncam::singleframe
