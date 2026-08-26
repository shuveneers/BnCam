#pragma once

#include <algorithm>
#include <array>
#include <cmath>
#include <cstdint>
#include <string>

namespace bncam::spectra2 {

inline float anisotropicFiniteUnit(float value) {
    return std::isfinite(value) ? std::clamp(value, 0.0f, 1.0f) : 0.0f;
}

inline float anisotropicSmoothstep(float edge0, float edge1, float value) {
    if (!std::isfinite(value) || !(edge1 > edge0)) return 0.0f;
    const float t = std::clamp((value - edge0) / (edge1 - edge0), 0.0f, 1.0f);
    return t * t * (3.0f - 2.0f * t);
}

struct StructureTensorEstimate {
    bool valid = false;
    bool confident = false;
    std::string status = "UNAVAILABLE";
    float jxx = 0.0f;
    float jxy = 0.0f;
    float jyy = 0.0f;
    float lambdaMajor = 0.0f;
    float lambdaMinor = 0.0f;
    float coherence = 0.0f;
    float normalizedEnergy = 0.0f;
    float confidence = 0.0f;
    float normalX = 1.0f;
    float normalY = 0.0f;
    float tangentX = 0.0f;
    float tangentY = 1.0f;
    float tangentDegrees = 90.0f;
};

inline StructureTensorEstimate structureTensorFromMoments(
        float jxx,
        float jxy,
        float jyy,
        float gradientNoiseVariance
) {
    StructureTensorEstimate tensor{};
    if (!std::isfinite(jxx) || !std::isfinite(jxy) || !std::isfinite(jyy) ||
        !std::isfinite(gradientNoiseVariance)) {
        tensor.status = "NONFINITE_TENSOR_FALLBACK";
        return tensor;
    }
    tensor.jxx = std::max(0.0f, jxx);
    tensor.jxy = jxy;
    tensor.jyy = std::max(0.0f, jyy);
    const float trace = tensor.jxx + tensor.jyy;
    const float discriminant = std::sqrt(std::max(
            0.0f,
            (tensor.jxx - tensor.jyy) * (tensor.jxx - tensor.jyy) +
                    4.0f * tensor.jxy * tensor.jxy
    ));
    tensor.lambdaMajor = 0.5f * (trace + discriminant);
    tensor.lambdaMinor = std::max(0.0f, 0.5f * (trace - discriminant));
    tensor.coherence = trace > 1.0e-12f
            ? std::clamp(discriminant / trace, 0.0f, 1.0f)
            : 0.0f;
    tensor.normalizedEnergy = tensor.lambdaMajor /
            std::max(1.0e-10f, gradientNoiseVariance);

    // The tensor only receives directional authority when both structure energy
    // and eigenvalue separation are credible. Flat/noisy regions intentionally
    // fall back to the established isotropic Wiener behaviour.
    const float energyConfidence = anisotropicSmoothstep(
            0.70f,
            5.00f,
            tensor.normalizedEnergy
    );
    const float coherenceConfidence = anisotropicSmoothstep(
            0.08f,
            0.72f,
            tensor.coherence
    );
    tensor.confidence = std::clamp(
            energyConfidence * coherenceConfidence,
            0.0f,
            1.0f
    );

    float nx = tensor.jxy;
    float ny = tensor.lambdaMajor - tensor.jxx;
    if (std::abs(nx) + std::abs(ny) < 1.0e-8f) {
        nx = tensor.lambdaMajor - tensor.jyy;
        ny = tensor.jxy;
    }
    const float norm = std::sqrt(nx * nx + ny * ny);
    if (norm > 1.0e-8f) {
        tensor.normalX = nx / norm;
        tensor.normalY = ny / norm;
    } else if (tensor.jxx >= tensor.jyy) {
        tensor.normalX = 1.0f;
        tensor.normalY = 0.0f;
    } else {
        tensor.normalX = 0.0f;
        tensor.normalY = 1.0f;
    }
    tensor.tangentX = -tensor.normalY;
    tensor.tangentY = tensor.normalX;
    float degrees = std::atan2(tensor.tangentY, tensor.tangentX) *
            (180.0f / 3.14159265358979323846f);
    while (degrees < 0.0f) degrees += 180.0f;
    while (degrees >= 180.0f) degrees -= 180.0f;
    tensor.tangentDegrees = degrees;
    tensor.valid = std::isfinite(tensor.confidence) &&
            std::isfinite(tensor.tangentDegrees);
    tensor.confident = tensor.valid && tensor.confidence >= 0.12f;
    tensor.status = tensor.confident
            ? "DIRECTIONAL_TENSOR_READY"
            : (tensor.valid ? "LOW_TENSOR_CONFIDENCE_ISOTROPIC_FALLBACK" : "INVALID_TENSOR_FALLBACK");
    return tensor;
}

struct DirectionalWeightDecision {
    float weight = 1.0f;
    float alongProjection = 0.0f;
    float acrossProjection = 0.0f;
    float guideWeight = 1.0f;
    bool crossEdgeProtected = false;
    bool alongStructureSupported = false;
};

inline DirectionalWeightDecision resolveDirectionalSampleWeight(
        const StructureTensorEstimate& tensor,
        float dx,
        float dy,
        float guideDeltaSigma
) {
    DirectionalWeightDecision decision{};
    if (!tensor.confident || !std::isfinite(dx) || !std::isfinite(dy)) {
        return decision;
    }
    const float distance = std::max(1.0e-6f, std::sqrt(dx * dx + dy * dy));
    decision.alongProjection = std::abs(
            dx * tensor.tangentX + dy * tensor.tangentY
    ) / distance;
    decision.acrossProjection = std::abs(
            dx * tensor.normalX + dy * tensor.normalY
    ) / distance;
    const float strength = tensor.confidence * (0.35f + 0.65f * tensor.coherence);
    const float crossPenalty = std::exp(
            -3.25f * strength * decision.acrossProjection * decision.acrossProjection
    );
    const float alongSupport = 0.88f + 0.34f * strength *
            decision.alongProjection * decision.alongProjection;
    const float excessGuideDelta = std::max(
            0.0f,
            (std::isfinite(guideDeltaSigma) ? std::abs(guideDeltaSigma) : 8.0f) - 1.25f
    );
    decision.guideWeight = std::exp(-0.22f * excessGuideDelta * excessGuideDelta);
    decision.weight = std::clamp(
            crossPenalty * alongSupport * decision.guideWeight,
            0.04f,
            1.22f
    );
    decision.crossEdgeProtected = decision.acrossProjection > 0.70f &&
            decision.weight < 0.55f;
    decision.alongStructureSupported = decision.alongProjection > 0.70f &&
            decision.weight > 0.82f;
    return decision;
}

struct AnisotropicAuthorityDecision {
    bool usedDirectionalAuthority = false;
    bool usedFallback = true;
    float isotropicScale = 1.0f;
    float directionalScale = 1.0f;
    float finalScale = 1.0f;
};

inline AnisotropicAuthorityDecision resolveAnisotropicAuthority(
        const StructureTensorEstimate& tensor,
        float isotropicEdgeProtection
) {
    AnisotropicAuthorityDecision decision{};
    const float edge = anisotropicFiniteUnit(isotropicEdgeProtection);
    decision.isotropicScale = std::clamp(1.0f - 0.90f * edge, 0.10f, 1.0f);
    decision.directionalScale = decision.isotropicScale;
    decision.finalScale = decision.isotropicScale;
    if (!tensor.confident) return decision;

    // A credible tensor permits more cleanup along the edge direction, while
    // cross-edge samples have already been suppressed by directional weights.
    const float directionalFloor = std::clamp(
            0.26f + 0.48f * tensor.confidence + 0.16f * tensor.coherence,
            0.26f,
            0.78f
    );
    decision.directionalScale = std::max(decision.isotropicScale, directionalFloor);
    decision.finalScale = std::clamp(
            decision.isotropicScale * (1.0f - tensor.confidence) +
                    decision.directionalScale * tensor.confidence,
            decision.isotropicScale,
            0.78f
    );
    decision.usedDirectionalAuthority = decision.finalScale > decision.isotropicScale + 1.0e-4f;
    decision.usedFallback = !decision.usedDirectionalAuthority;
    return decision;
}

inline int orientationBin(float tangentDegrees) {
    if (!std::isfinite(tangentDegrees)) return 0;
    float degrees = tangentDegrees;
    while (degrees < 0.0f) degrees += 180.0f;
    while (degrees >= 180.0f) degrees -= 180.0f;
    if (degrees < 22.5f || degrees >= 157.5f) return 0;
    if (degrees < 67.5f) return 1;
    if (degrees < 112.5f) return 2;
    return 3;
}

struct AnisotropicDetailTelemetry {
    bool enabled = false;
    bool applied = false;
    std::string status = "NOT_RUN";
    std::string architecture = "SPECTRA_CONTEXT_FUSION_PHYSICS_GUIDED_CFA";
    std::string tensorMethod = "COARSE_GREEN_LUMA_TENSOR_BILINEAR_MOMENT_INTERPOLATION";
    std::string filterMethod = "SPECTRA_CONTEXT_FUSION_NOISE_NORMALIZED_MULTI_SCALE_CFA";
    std::string fallbackMethod = "NOISE_NORMALIZED_ISOTROPIC_CONTEXT_FUSION";
    std::string timingAccounting = "NESTED_IN_SPECTRA_PASS1_PROCESSING_MS";
    std::uint64_t evaluatedPixelCount = 0;
    std::uint64_t validTensorPixelCount = 0;
    std::uint64_t confidentTensorPixelCount = 0;
    std::uint64_t fallbackPixelCount = 0;
    std::uint64_t directionalChangedPixelCount = 0;
    std::uint64_t crossEdgeProtectedSampleCount = 0;
    std::uint64_t alongStructureSupportedSampleCount = 0;
    std::uint64_t contextFlatPixelCount = 0;
    std::uint64_t contextStructureProtectedPixelCount = 0;
    std::uint64_t contextBoostedPixelCount = 0;
    std::uint64_t profiledMultibandPixelCount = 0;
    std::uint64_t profiledHeavyFineShrinkPixelCount = 0;
    std::uint64_t coherentDetailRestitutionPixelCount = 0;
    std::uint64_t profiledPatchConsensusPixelCount = 0;
    std::uint64_t profiledStrongPatchConsensusPixelCount = 0;
    std::uint64_t profiledPatchPosteriorCleanPixelCount = 0;
    std::uint64_t profiledPatchGradientProtectedPixelCount = 0;
    std::array<std::uint64_t, 4> orientationHistogram{0, 0, 0, 0};
    float contextFlatFraction = 0.0f;
    float contextStructureProtectedFraction = 0.0f;
    float contextBoostedFraction = 0.0f;
    float profiledMultibandFraction = 0.0f;
    float profiledHeavyFineShrinkFraction = 0.0f;
    float coherentDetailRestitutionFraction = 0.0f;
    float profiledPatchConsensusFraction = 0.0f;
    float profiledStrongPatchConsensusFraction = 0.0f;
    float profiledPatchPosteriorCleanFraction = 0.0f;
    float profiledPatchGradientProtectedFraction = 0.0f;
    float validTensorFraction = 0.0f;
    float confidentTensorFraction = 0.0f;
    float fallbackFraction = 1.0f;
    float directionalChangedFraction = 0.0f;
    float meanConfidence = 0.0f;
    float confidenceP10 = 0.0f;
    float confidenceP50 = 0.0f;
    float confidenceP90 = 0.0f;
    float meanCoherence = 0.0f;
    float coherenceP10 = 0.0f;
    float coherenceP50 = 0.0f;
    float coherenceP90 = 0.0f;
    float meanDirectionalWeight = 1.0f;
    float meanIsotropicAuthorityScale = 1.0f;
    float meanDirectionalAuthorityScale = 1.0f;
    float maximumLinearCorrection = 0.0f;
    float tensorFieldBuildMs = 0.0f;
    float directionalFilterMs = 0.0f;
};

}  // namespace bncam::spectra2
