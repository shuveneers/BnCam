#pragma once

#include <algorithm>
#include <array>
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <limits>
#include <string>
#include <vector>

namespace bncam::raw_exposure {

constexpr std::uint32_t kGridWidth = 64u;
constexpr std::uint32_t kGridHeight = 48u;
constexpr std::size_t kTileCount = static_cast<std::size_t>(kGridWidth) * kGridHeight;

struct TileEvidence {
    float luma = 0.0f;           // black-relative, lens-corrected RAW green-domain signal
    float highSignal = 0.0f;     // robust upper-tail signal for absolute headroom evidence
    float snr = 0.0f;            // physical S/O-derived signal/noise evidence
    float clipFraction = 0.0f;   // fraction at/above normalized sensor white in sampled tile
    bool valid = false;
};

struct Plan {
    bool valid = false;
    bool physicalNoiseModelAvailable = false;
    std::uint32_t gridWidth = kGridWidth;
    std::uint32_t gridHeight = kGridHeight;
    std::array<float, kTileCount> ev{};

    float p10 = 0.0f;
    float p25 = 0.0f;
    float p50 = 0.0f;
    float p75 = 0.0f;
    float p90 = 0.0f;
    float p95 = 0.0f;
    float p99 = 0.0f;
    float measuredSceneDrEv = 0.0f;
    float lowerNeutralBoundaryEv = 0.0f;
    float upperNeutralBoundaryEv = 0.0f;
    float spatialAuthority = 0.0f;

    float minEv = 0.0f;
    float p10Ev = 0.0f;
    float p50Ev = 0.0f;
    float p90Ev = 0.0f;
    float maxEv = 0.0f;
    float meanPositiveEv = 0.0f;
    float meanNegativeEv = 0.0f;
    float positiveFraction = 0.0f;
    float neutralFraction = 1.0f;
    float negativeFraction = 0.0f;
    float meanGainSquared = 1.0f;
    float p90PositiveGain = 1.0f;
    std::string status = "NOT_RUN";
};

inline float smoothstep(float edge0, float edge1, float x) noexcept {
    if (!(edge1 > edge0)) return x >= edge1 ? 1.0f : 0.0f;
    const float t = std::clamp((x - edge0) / (edge1 - edge0), 0.0f, 1.0f);
    return t * t * (3.0f - 2.0f * t);
}

inline float safeLog2(float value) noexcept {
    return std::log2(std::max(value, 1.0e-6f));
}

inline float percentileSorted(const std::vector<float>& values, float percentile) noexcept {
    if (values.empty()) return 0.0f;
    const float p = std::clamp(percentile, 0.0f, 1.0f);
    const std::size_t index = std::min(
            values.size() - 1u,
            static_cast<std::size_t>(std::floor(p * static_cast<float>(values.size() - 1u))));
    return values[index];
}

inline Plan resolve(
        const std::array<TileEvidence, kTileCount>& tiles,
        bool physicalNoiseModelAvailable) noexcept {
    Plan out{};
    out.physicalNoiseModelAvailable = physicalNoiseModelAvailable;

    std::vector<float> luma;
    luma.reserve(kTileCount);
    std::size_t validCount = 0u;
    for (const auto& tile : tiles) {
        if (!tile.valid || !std::isfinite(tile.luma) || tile.luma <= 0.0f) continue;
        luma.push_back(std::max(tile.luma, 1.0e-6f));
        ++validCount;
    }
    // Require broad spatial support. A sparse observation must not become an exposure owner.
    if (validCount < kTileCount / 4u) {
        out.status = "INSUFFICIENT_SPATIAL_EVIDENCE";
        return out;
    }
    std::sort(luma.begin(), luma.end());
    out.p10 = percentileSorted(luma, 0.10f);
    out.p25 = percentileSorted(luma, 0.25f);
    out.p50 = percentileSorted(luma, 0.50f);
    out.p75 = percentileSorted(luma, 0.75f);
    out.p90 = percentileSorted(luma, 0.90f);
    out.p95 = percentileSorted(luma, 0.95f);
    out.p99 = percentileSorted(luma, 0.99f);
    if (!(out.p50 > 1.0e-6f) || !std::isfinite(out.p50)) {
        out.status = "INVALID_SCENE_REFERENCE";
        return out;
    }

    out.measuredSceneDrEv = std::max(0.0f, safeLog2(out.p90) - safeLog2(out.p10));
    const float lowerNeutralSignal = percentileSorted(luma, 0.35f);
    out.lowerNeutralBoundaryEv = safeLog2(std::max(lowerNeutralSignal, 1.0e-6f) / out.p50);
    out.upperNeutralBoundaryEv = safeLog2(std::max(out.p75, 1.0e-6f) / out.p50);
    // Spatial authority emerges from measured inter-quartile/robust scene separation. Uniform
    // scenes naturally collapse toward zero correction rather than being forced into zones.
    const float interquartileEv = std::max(
            0.0f, safeLog2(std::max(out.p75, 1.0e-6f)) -
                  safeLog2(std::max(out.p25, 1.0e-6f)));
    out.spatialAuthority = std::max(
            smoothstep(0.55f, 2.2f, interquartileEv),
            0.75f * smoothstep(1.4f, 4.8f, out.measuredSceneDrEv));

    std::array<float, kTileCount> rawEv{};
    std::array<std::int8_t, kTileCount> rawSign{};
    for (std::size_t i = 0u; i < kTileCount; ++i) {
        const auto& tile = tiles[i];
        if (!tile.valid || !std::isfinite(tile.luma) || tile.luma <= 0.0f) continue;
        const float relativeEv = safeLog2(tile.luma / out.p50);
        float ev = 0.0f;

        if (relativeEv < out.lowerNeutralBoundaryEv) {
            const float distance = out.lowerNeutralBoundaryEv - relativeEv;
            // Digital lift cannot improve SNR. Physical S/O only decides whether making this
            // already-recorded shadow more visible is defensible; missing S/O gets zero authority.
            const float snrAuthority = physicalNoiseModelAvailable && std::isfinite(tile.snr)
                    ? smoothstep(1.5f, 6.0f, tile.snr)
                    : 0.0f;
            const float percentileDistance = std::max(
                    0.25f, std::abs(out.lowerNeutralBoundaryEv));
            const float requested = distance * (0.20f + 0.20f * smoothstep(
                    percentileDistance, percentileDistance * 3.0f, distance));
            // Available sensor headroom bounds presentation lift without assuming a fixed EV offset.
            const float high = std::max(tile.highSignal, tile.luma);
            const float headroomEv = std::max(0.0f, safeLog2(0.98f / std::max(high, 1.0e-6f)));
            ev = std::min(requested, headroomEv) * snrAuthority * out.spatialAuthority;
        }
        // Pre-demosaic spatial exposure is a shadow-recovery owner only. Bright-tail protection
        // belongs to the downstream scene-linear tone chain (GTM -> FLLF -> AgX). Darkening an
        // already-recorded bright tile here cannot recover clipped sensor information and caused
        // ordinary bright objects to receive multi-EV attenuation before color/tone processing.
        // Keep the exposure field non-negative so there is one high-end compression authority.
        if (!std::isfinite(ev)) ev = 0.0f;
        rawEv[i] = ev;
        rawSign[i] = ev > 1.0e-4f ? 1 : (ev < -1.0e-4f ? -1 : 0);
    }

    // Require spatial support for weak corrections. Strong relative/absolute highlights can stand
    // alone; ordinary shadow/highlight texture cannot make a salt-and-pepper exposure field.
    for (std::uint32_t y = 0u; y < kGridHeight; ++y) {
        for (std::uint32_t x = 0u; x < kGridWidth; ++x) {
            const std::size_t index = static_cast<std::size_t>(y) * kGridWidth + x;
            float ev = rawEv[index];
            const int sign = rawSign[index];
            if (sign == 0) {
                out.ev[index] = 0.0f;
                continue;
            }
            int support = 1;
            int possible = 1;
            const int dx[4] = {-1, 1, 0, 0};
            const int dy[4] = {0, 0, -1, 1};
            for (int n = 0; n < 4; ++n) {
                const int nx = static_cast<int>(x) + dx[n];
                const int ny = static_cast<int>(y) + dy[n];
                if (nx < 0 || ny < 0 || nx >= static_cast<int>(kGridWidth) ||
                    ny >= static_cast<int>(kGridHeight)) continue;
                ++possible;
                const std::size_t neighbour = static_cast<std::size_t>(ny) * kGridWidth +
                        static_cast<std::size_t>(nx);
                if (rawSign[neighbour] == sign) ++support;
            }
            const float supportRatio = static_cast<float>(support) / static_cast<float>(possible);
            // Only positive shadow lift can reach this pass. Require spatial agreement for weak
            // recovery so isolated dark texture/noise cannot become a local exposure owner.
            ev *= smoothstep(0.20f, 0.80f, supportRatio);
            out.ev[index] = std::isfinite(ev) ? ev : 0.0f;
        }
    }

    std::vector<float> sortedEv;
    std::vector<float> positiveGains;
    sortedEv.reserve(validCount);
    positiveGains.reserve(validCount);
    double sumPositive = 0.0;
    double sumNegative = 0.0;
    double sumGainSquared = 0.0;
    std::size_t positiveCount = 0u, neutralCount = 0u, negativeCount = 0u;
    out.minEv = std::numeric_limits<float>::infinity();
    out.maxEv = -std::numeric_limits<float>::infinity();
    for (std::size_t i = 0u; i < kTileCount; ++i) {
        if (!tiles[i].valid) continue;
        const float ev = out.ev[i];
        sortedEv.push_back(ev);
        out.minEv = std::min(out.minEv, ev);
        out.maxEv = std::max(out.maxEv, ev);
        const float gain = std::exp2(ev);
        sumGainSquared += static_cast<double>(gain) * gain;
        if (ev > 0.015f) {
            ++positiveCount;
            sumPositive += ev;
            positiveGains.push_back(gain);
        } else if (ev < -0.015f) {
            ++negativeCount;
            sumNegative += ev;
        } else {
            ++neutralCount;
        }
    }
    if (sortedEv.empty()) {
        out.status = "NO_VALID_EXPOSURE_TILES";
        return out;
    }
    std::sort(sortedEv.begin(), sortedEv.end());
    std::sort(positiveGains.begin(), positiveGains.end());
    out.p10Ev = percentileSorted(sortedEv, 0.10f);
    out.p50Ev = percentileSorted(sortedEv, 0.50f);
    out.p90Ev = percentileSorted(sortedEv, 0.90f);
    const float denom = static_cast<float>(sortedEv.size());
    out.positiveFraction = static_cast<float>(positiveCount) / denom;
    out.neutralFraction = static_cast<float>(neutralCount) / denom;
    out.negativeFraction = static_cast<float>(negativeCount) / denom;
    out.meanPositiveEv = positiveCount > 0u
            ? static_cast<float>(sumPositive / static_cast<double>(positiveCount)) : 0.0f;
    out.meanNegativeEv = negativeCount > 0u
            ? static_cast<float>(sumNegative / static_cast<double>(negativeCount)) : 0.0f;
    out.meanGainSquared = static_cast<float>(sumGainSquared / static_cast<double>(sortedEv.size()));
    out.p90PositiveGain = positiveGains.empty() ? 1.0f : percentileSorted(positiveGains, 0.90f);
    if (!std::isfinite(out.minEv)) out.minEv = 0.0f;
    if (!std::isfinite(out.maxEv)) out.maxEv = 0.0f;
    out.valid = true;
    out.status = out.spatialAuthority > 0.01f
            ? "SIGNED_SPATIAL_MAP_READY" : "LOW_SCENE_SEPARATION_NEUTRAL_MAP";
    return out;
}

} // namespace bncam::raw_exposure
