#pragma once

#include <algorithm>
#include <array>
#include <cmath>
#include <cstdint>

#include <opencv2/core.hpp>

namespace bncam::phase6 {

struct ResidualChromaArtifactPlan {
    bool enabled = false;
    float sigmaY = 0.0f;
    float sigmaChroma = 0.0f;
    float noisePressure = 0.0f;
    float maximumBlend = 0.92f;
    float maximumCorrection = 0.12f;
    const char* status = "DISABLED_NO_PHYSICAL_NOISE_TRUTH";
};

struct ResidualChromaArtifactTelemetry {
    std::uint64_t processedPixels = 0;
    std::uint64_t candidatePixels = 0;
    std::uint64_t isolatedOutlierPixels = 0;
    std::uint64_t zipperPixels = 0;
    std::uint64_t edgeProtectedPixels = 0;
    std::uint64_t saturatedDetailProtectedPixels = 0;
    double sumAbsCorrectionRG = 0.0;
    double sumAbsCorrectionBG = 0.0;
    float maximumAbsoluteCorrection = 0.0f;
};

struct OpponentChannelDecision {
    float target = 0.0f;
    float correction = 0.0f;
    float confidence = 0.0f;
    bool isolatedOutlier = false;
    bool zipper = false;
    bool edgeProtected = false;
    bool saturatedDetailProtected = false;
};

inline float finiteOrZero(float value) {
    return std::isfinite(value) ? value : 0.0f;
}

inline float smoothstep(float edge0, float edge1, float value) {
    if (!(edge1 > edge0)) return value >= edge1 ? 1.0f : 0.0f;
    const float t = std::clamp((value - edge0) / (edge1 - edge0), 0.0f, 1.0f);
    return t * t * (3.0f - 2.0f * t);
}

inline float median4(float a, float b, float c, float d) {
    const float lo0 = std::min(a, b);
    const float hi0 = std::max(a, b);
    const float lo1 = std::min(c, d);
    const float hi1 = std::max(c, d);
    return 0.5f * (std::max(lo0, lo1) + std::min(hi0, hi1));
}

inline ResidualChromaArtifactPlan resolveResidualChromaArtifactPlan(
        bool physicalNoiseKnown,
        float sigmaY,
        float sigmaChroma,
        float noisePressure) {
    ResidualChromaArtifactPlan plan{};
    plan.sigmaY = std::max(0.0f, finiteOrZero(sigmaY));
    plan.sigmaChroma = std::max(0.0f, finiteOrZero(sigmaChroma));
    plan.noisePressure = std::clamp(finiteOrZero(noisePressure), 0.0f, 1.0f);
    if (!physicalNoiseKnown || plan.sigmaChroma <= 1.0e-7f) {
        return plan;
    }
    plan.enabled = true;
    // This is an artifact remover, not another denoiser. More physical pressure may increase
    // the confidence ceiling slightly, but never changes the topology tests or grants broad blur.
    plan.maximumBlend = std::clamp(0.82f + 0.10f * plan.noisePressure, 0.82f, 0.92f);
    plan.maximumCorrection = std::clamp(
            0.045f + 5.5f * plan.sigmaChroma,
            0.055f,
            0.12f);
    plan.status = "PHYSICAL_SIGMA_TOPOLOGY_GATED";
    return plan;
}

inline OpponentChannelDecision classifyOpponentArtifact(
        float center,
        const std::array<float, 8>& neighbours,
        float centerGreen,
        const std::array<float, 8>& greenNeighbours,
        const ResidualChromaArtifactPlan& plan) {
    OpponentChannelDecision decision{};
    if (!plan.enabled) return decision;

    const float sigma = std::max(plan.sigmaChroma, 2.5e-5f);
    const float sigmaY = std::max(plan.sigmaY, 2.5e-5f);
    const float c = finiteOrZero(center);
    std::array<float, 8> n{};
    std::array<float, 8> g{};
    for (std::size_t i = 0; i < 8; ++i) {
        n[i] = finiteOrZero(neighbours[i]);
        g[i] = finiteOrZero(greenNeighbours[i]);
    }

    // Axial median is robust against one-pixel demosaic colour spikes and avoids averaging
    // across a diagonal real colour boundary. Diagonals contribute to coherence/support only.
    decision.target = median4(n[0], n[1], n[2], n[3]);
    const float localMin = *std::min_element(n.begin(), n.end());
    const float localMax = *std::max_element(n.begin(), n.end());
    const float delta = c - decision.target;
    const float outsideEnvelope = c < localMin ? c - localMin : (c > localMax ? c - localMax : 0.0f);

    const float supportTolerance = 2.35f * sigma + 0.045f * std::abs(c) + 7.5e-4f;
    int supportCount = 0;
    for (float value : n) {
        if (std::abs(value - c) <= supportTolerance) ++supportCount;
    }

    const float horizontalSecond = std::abs(n[0] - 2.0f * c + n[1]);
    const float verticalSecond = std::abs(n[2] - 2.0f * c + n[3]);
    const float zipperMetric = std::max(horizontalSecond, verticalSecond);
    const float axialSpan = std::max({n[0], n[1], n[2], n[3]}) -
                            std::min({n[0], n[1], n[2], n[3]});

    float maximumGreenDelta = 0.0f;
    for (float value : g) maximumGreenDelta = std::max(maximumGreenDelta, std::abs(value - centerGreen));
    const float structure = smoothstep(
            2.0f * sigmaY + 0.004f,
            7.0f * sigmaY + 0.055f,
            maximumGreenDelta);

    const float directionalSupport = std::min(
            std::min(std::abs(c - n[0]), std::abs(c - n[1])),
            std::min(std::abs(c - n[2]), std::abs(c - n[3])));
    const float directionalTolerance = 2.75f * sigma + 0.035f * std::abs(c) + 7.5e-4f;
    const bool coherentColourSupport = supportCount >= 2 || directionalSupport <= directionalTolerance;
    decision.edgeProtected = coherentColourSupport && (axialSpan > 2.0f * sigma || structure > 0.20f);

    const float saturatedThreshold = std::max(0.075f, 6.0f * sigma);
    decision.saturatedDetailProtected = std::abs(c) >= saturatedThreshold && supportCount >= 2;

    const float isolatedScore = 1.0f - smoothstep(0.75f, 2.75f, static_cast<float>(supportCount));
    const float residualScore = smoothstep(2.0f * sigma, 5.5f * sigma + 0.002f, std::abs(delta));
    const float overshootScore = smoothstep(
            1.35f * sigma,
            4.0f * sigma + 0.0015f,
            std::abs(outsideEnvelope));
    const float zipperScore = smoothstep(
            2.5f * sigma + 0.30f * axialSpan,
            6.0f * sigma + 0.70f * axialSpan + 0.002f,
            zipperMetric) * isolatedScore;

    decision.isolatedOutlier = isolatedScore > 0.62f &&
            std::max(residualScore, overshootScore) > 0.35f;
    decision.zipper = zipperScore > 0.38f;

    float confidence = std::max({
            overshootScore,
            zipperScore,
            residualScore * isolatedScore
    });
    if (decision.edgeProtected) {
        // A coherent colour edge is evidence, not noise. Preserve it aggressively. Only a strong
        // outside-envelope outlier retains a narrow correction path.
        confidence *= 0.10f + 0.22f * overshootScore;
    }
    if (decision.saturatedDetailProtected) confidence *= 0.08f;
    // Strong luma structure without chroma support may be demosaic zipper, so structure alone
    // never vetoes correction. It only reinforces an already coherent colour-edge protection.
    if (coherentColourSupport) confidence *= 1.0f - 0.45f * structure;

    confidence = std::clamp(confidence, 0.0f, plan.maximumBlend);
    if (confidence < 0.015f) return decision;

    const float correctionCap = std::min(
            plan.maximumCorrection,
            0.006f + 6.0f * sigma + 0.045f * std::abs(decision.target));
    const float boundedDelta = std::clamp(delta, -correctionCap, correctionCap);
    decision.correction = boundedDelta * confidence;
    decision.confidence = confidence;
    return decision;
}

inline void applyResidualChromaArtifactCpu(
        cv::Mat& rgb,
        const ResidualChromaArtifactPlan& plan,
        ResidualChromaArtifactTelemetry* telemetry = nullptr) {
    if (!plan.enabled || rgb.empty() || rgb.type() != CV_32FC3 || rgb.cols < 3 || rgb.rows < 3) return;

    // Failure/reference path only. Read from an immutable snapshot so CPU and the two-pass GPU
    // implementation share the same no-feedback contract.
    const cv::Mat source = rgb.clone();
    ResidualChromaArtifactTelemetry local{};
    for (int y = 1; y < rgb.rows - 1; ++y) {
        cv::Vec3f* dst = rgb.ptr<cv::Vec3f>(y);
        for (int x = 1; x < rgb.cols - 1; ++x) {
            const cv::Vec3f center = source.at<cv::Vec3f>(y, x);
            if (!std::isfinite(center[0]) || !std::isfinite(center[1]) || !std::isfinite(center[2])) continue;
            ++local.processedPixels;

            static constexpr std::array<int, 8> dx{-1, 1, 0, 0, -1, 1, -1, 1};
            static constexpr std::array<int, 8> dy{0, 0, -1, 1, -1, -1, 1, 1};
            std::array<float, 8> rg{};
            std::array<float, 8> bg{};
            std::array<float, 8> green{};
            for (std::size_t i = 0; i < 8; ++i) {
                const cv::Vec3f p = source.at<cv::Vec3f>(y + dy[i], x + dx[i]);
                green[i] = p[1];
                rg[i] = p[0] - p[1];
                bg[i] = p[2] - p[1];
            }
            const float centerRg = center[0] - center[1];
            const float centerBg = center[2] - center[1];
            const auto red = classifyOpponentArtifact(centerRg, rg, center[1], green, plan);
            const auto blue = classifyOpponentArtifact(centerBg, bg, center[1], green, plan);

            const float correctionRg = red.correction;
            const float correctionBg = blue.correction;
            const float maxCorrection = std::max(std::abs(correctionRg), std::abs(correctionBg));
            if (maxCorrection > 1.0e-7f) {
                ++local.candidatePixels;
                dst[x][0] = finiteOrZero(center[0] - correctionRg);
                dst[x][1] = center[1];
                dst[x][2] = finiteOrZero(center[2] - correctionBg);
                local.sumAbsCorrectionRG += std::abs(correctionRg);
                local.sumAbsCorrectionBG += std::abs(correctionBg);
                local.maximumAbsoluteCorrection = std::max(local.maximumAbsoluteCorrection, maxCorrection);
            }
            if (red.isolatedOutlier || blue.isolatedOutlier) ++local.isolatedOutlierPixels;
            if (red.zipper || blue.zipper) ++local.zipperPixels;
            if (red.edgeProtected || blue.edgeProtected) ++local.edgeProtectedPixels;
            if (red.saturatedDetailProtected || blue.saturatedDetailProtected) {
                ++local.saturatedDetailProtectedPixels;
            }
        }
    }
    if (telemetry != nullptr) *telemetry = local;
}

}  // namespace bncam::phase6
