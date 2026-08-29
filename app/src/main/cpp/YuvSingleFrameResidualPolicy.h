#pragma once

#include <algorithm>
#include <cmath>
#include <cstdint>

namespace bncam::yuv_phase13 {

// FASE 13 compact GPU telemetry contract. Words 0..7 remain available to the
// existing Ultra HDR path; the residual-noise model owns 8..63.
constexpr std::uint32_t kTelemetryWordCount = 64u;
constexpr std::uint32_t kHistogramBins = 16u;
constexpr std::uint32_t kLumaHistogramBase = 8u;
constexpr std::uint32_t kUHistogramBase = 24u;
constexpr std::uint32_t kVHistogramBase = 40u;
constexpr std::uint32_t kLumaSampleCountWord = 56u;
constexpr std::uint32_t kChromaSampleCountWord = 57u;
constexpr std::uint32_t kSigmaYQ24Word = 58u;
constexpr std::uint32_t kSigmaUQ24Word = 59u;
constexpr std::uint32_t kSigmaVQ24Word = 60u;
constexpr std::uint32_t kLumaAuthorityQ24Word = 61u;
constexpr std::uint32_t kChromaAuthorityQ24Word = 62u;
constexpr std::uint32_t kModelConfidenceQ24Word = 63u;
constexpr float kQ24Scale = 16777216.0f;

// For the Immerkær 3x3 Laplacian kernel, white-noise response sigma is 6*sigma.
// The 35th percentile of |N(0,1)| is 0.45376219, hence 2.72257314*sigma.
// A lower-than-median quantile is deliberately used to reduce contamination by
// real texture while retaining a closed-form conversion back to sigma.
constexpr float kAbsLaplacianP35ToSigma = 2.722573141f;

inline float sigmaFromAbsLaplacianP35CodeValue(float responseCodeValue) {
    if (!std::isfinite(responseCodeValue) || responseCodeValue <= 0.0f) return 0.0f;
    return std::clamp(
            responseCodeValue / (kAbsLaplacianP35ToSigma * 255.0f),
            0.0f,
            0.08f);
}

inline float smoothstep(float edge0, float edge1, float value) {
    if (!std::isfinite(value) || !(edge1 > edge0)) return 0.0f;
    const float t = std::clamp((value - edge0) / (edge1 - edge0), 0.0f, 1.0f);
    return t * t * (3.0f - 2.0f * t);
}

inline float modelConfidence(std::uint32_t lumaSamples, std::uint32_t chromaSamples) {
    const float luma = smoothstep(24.0f, 256.0f, static_cast<float>(lumaSamples));
    const float chroma = smoothstep(16.0f, 128.0f, static_cast<float>(chromaSamples));
    return std::min(luma, chroma);
}

inline float measuredLumaAuthority(float sigmaY, float confidence) {
    const float pressure = smoothstep(0.70f, 4.00f, sigmaY * 255.0f);
    return std::clamp(0.38f * pressure * std::clamp(confidence, 0.0f, 1.0f), 0.0f, 0.38f);
}

inline float measuredChromaAuthority(float sigmaU, float sigmaV, float confidence) {
    const float sigmaC = std::sqrt(std::max(0.0f, 0.5f * (sigmaU * sigmaU + sigmaV * sigmaV)));
    const float pressure = smoothstep(0.50f, 4.00f, sigmaC * 255.0f);
    return std::clamp(0.70f * pressure * std::clamp(confidence, 0.0f, 1.0f), 0.0f, 0.70f);
}

inline float finalAuthority(float measuredAuthority, float creativeAuthority, float ceiling) {
    return std::clamp(std::max(
            std::isfinite(measuredAuthority) ? measuredAuthority : 0.0f,
            std::isfinite(creativeAuthority) ? creativeAuthority : 0.0f), 0.0f, ceiling);
}

inline float nearBlackPressure(float luma, float sigmaY) {
    const float safeSigma = std::max(
            std::isfinite(sigmaY) ? sigmaY : 0.0f,
            1.0f / 255.0f);
    const float snr = std::max(0.0f, std::isfinite(luma) ? luma : 0.0f) / safeSigma;
    return 1.0f - smoothstep(4.0f, 12.0f, snr);
}

inline float residualPreserve(float residualSnr, float protection, bool chroma) {
    const float p = std::clamp(std::isfinite(protection) ? protection : 0.0f, 0.0f, 1.0f);
    const float low = chroma ? (1.55f + (0.95f - 1.55f) * p)
                             : (1.25f + (0.75f - 1.25f) * p);
    const float high = chroma ? (3.80f + (2.60f - 3.80f) * p)
                              : (3.20f + (2.00f - 3.20f) * p);
    return smoothstep(low, high, std::max(0.0f, residualSnr));
}

}  // namespace bncam::yuv_phase13
