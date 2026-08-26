#pragma once

#include <algorithm>
#include <array>
#include <cmath>

namespace bncam::spectra2 {

// Clean-room CFA support basis inspired by the signal/noise separation
// properties of orthonormal Bayer transforms.  Coefficients are kept explicit
// so the runtime and shader can share the same numerical contract.
struct CfaOrthonormalBasis {
    float c0 = 0.0f; // signal-rich luminance-like axis
    float c1 = 0.0f; // opponent chroma axis 1
    float c2 = 0.0f; // opponent chroma axis 2
    float deltaG = 0.0f; // G1-G2 fine-structure axis
};

struct CfaOrthonormalVariance {
    float c0 = 0.0f;
    float c1 = 0.0f;
    float c2 = 0.0f;
    float deltaG = 0.0f;
};

inline CfaOrthonormalBasis transformCfaBlock(
        float r,
        float g1,
        float g2,
        float b) {
    return {
        0.541f * r + 0.436f * g1 + 0.436f * g2 + 0.572f * b,
       -0.794f * r + 0.107f * g1 + 0.107f * g2 + 0.588f * b,
       -0.276f * r + 0.546f * g1 + 0.546f * g2 - 0.572f * b,
        0.707f * g1 - 0.707f * g2
    };
}

inline CfaOrthonormalVariance propagateCfaBlockVariance(
        float varR,
        float varG1,
        float varG2,
        float varB) {
    auto square = [](float v) { return v * v; };
    return {
        square(0.541f) * varR + square(0.436f) * varG1 +
                square(0.436f) * varG2 + square(0.572f) * varB,
        square(0.794f) * varR + square(0.107f) * varG1 +
                square(0.107f) * varG2 + square(0.588f) * varB,
        square(0.276f) * varR + square(0.546f) * varG1 +
                square(0.546f) * varG2 + square(0.572f) * varB,
        square(0.707f) * varG1 + square(0.707f) * varG2
    };
}

inline float cfaLumaSupportWeight(
        float centerC0,
        float neighbourC0,
        float centerVarianceC0,
        float neighbourVarianceC0,
        float modelConfidence) {
    if (!std::isfinite(centerC0) || !std::isfinite(neighbourC0) ||
        !std::isfinite(centerVarianceC0) || !std::isfinite(neighbourVarianceC0)) {
        return 0.0f;
    }
    const float confidence = std::clamp(
            std::isfinite(modelConfidence) ? modelConfidence : 0.0f,
            0.0f,
            1.0f);
    // A less certain physical profile broadens the noise envelope instead of
    // turning denoise opacity down.  This prevents uncertain sigma from making
    // random CFA noise look like structure.
    const float uncertaintyEnvelope = 1.0f + 0.30f * (1.0f - confidence);
    const float differenceSigma = std::sqrt(std::max(
            1.0e-12f,
            (std::max(0.0f, centerVarianceC0) +
             std::max(0.0f, neighbourVarianceC0)))) * uncertaintyEnvelope;
    const float z = std::abs(neighbourC0 - centerC0) /
            std::max(1.0e-7f, differenceSigma);
    constexpr float kSupportSigma = 1.65f;
    return std::clamp(std::exp(-0.5f * (z / kSupportSigma) * (z / kSupportSigma)),
                      0.0f, 1.0f);
}

inline float smoothStep(float edge0, float edge1, float value) {
    if (!(edge1 > edge0)) return value >= edge1 ? 1.0f : 0.0f;
    const float t = std::clamp((value - edge0) / (edge1 - edge0), 0.0f, 1.0f);
    return t * t * (3.0f - 2.0f * t);
}

// Robust 9-sample span protection for real iso-luminant colour structure.
// The caller supplies sorted[7]-sorted[1] spans, intentionally discarding the
// most extreme sample on either side so an isolated chroma-noise impulse does
// not self-protect.
inline float cfaOpponentStructureProtection(
        float c1RobustSpan,
        float c2RobustSpan,
        float deltaGRobustSpan,
        float sigmaC1,
        float sigmaC2,
        float sigmaDeltaG,
        float modelConfidence) {
    const float confidence = std::clamp(
            std::isfinite(modelConfidence) ? modelConfidence : 0.0f,
            0.0f,
            1.0f);
    const float uncertaintyEnvelope = 1.0f + 0.30f * (1.0f - confidence);
    auto normalizedSpan = [&](float span, float sigma) {
        if (!std::isfinite(span) || !std::isfinite(sigma) || !(sigma > 0.0f)) return 0.0f;
        // The [1]..[7] span of nine Gaussian samples is naturally around two
        // standard deviations.  Normalising by 2*sigma therefore puts ordinary
        // sensor noise close to 1.0 rather than classifying it as colour detail.
        return std::max(0.0f, span) /
                std::max(1.0e-7f, 2.0f * sigma * uncertaintyEnvelope);
    };
    const float evidence = std::max({
            normalizedSpan(c1RobustSpan, sigmaC1),
            normalizedSpan(c2RobustSpan, sigmaC2),
            normalizedSpan(deltaGRobustSpan, sigmaDeltaG)
    });
    return smoothStep(1.40f, 2.30f, evidence);
}

} // namespace bncam::spectra2
