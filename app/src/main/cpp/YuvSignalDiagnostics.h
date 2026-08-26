#pragma once

#include <algorithm>
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <array>

namespace bncam::yuvdiag {

// Compact, read-only diagnostics for an NV21 single-frame input. These values describe
// the observed signal distribution; neighbour-delta metrics deliberately do NOT claim to
// be pure sensor-noise estimates because texture/edges contribute to them as well.
struct YuvSignalDiagnostics {
    std::uint32_t sampleCount = 0u;
    float yMean = 0.0f;
    float yStdDev = 0.0f;
    float uCenteredMean = 0.0f;
    float vCenteredMean = 0.0f;
    float uStdDev = 0.0f;
    float vStdDev = 0.0f;
    float chromaRmsFromNeutral = 0.0f;
    float lumaNeighbourDeltaMean = 0.0f;
    float chromaNeighbourDeltaMean = 0.0f;
    float yP0_1 = 0.0f;
    float yP1 = 0.0f;
    float yP5 = 0.0f;
    float yP50 = 0.0f;
    float yP95 = 0.0f;
    float yP99 = 0.0f;
    float yP99_9 = 0.0f;
    float blackClippedFraction = 0.0f;
    float whiteClippedFraction = 0.0f;
    float contrastRatio = 0.0f;
};

inline float histogramPercentile(
        const std::array<std::uint32_t, 256u>& histogram,
        std::uint32_t sampleCount,
        double percentile) {
    if (sampleCount == 0u) return 0.0f;
    const std::uint64_t rank = std::min<std::uint64_t>(
            sampleCount - 1u,
            static_cast<std::uint64_t>(std::floor(percentile * static_cast<double>(sampleCount))));
    std::uint64_t cumulative = 0u;
    for (std::size_t i = 0u; i < histogram.size(); ++i) {
        cumulative += histogram[i];
        if (cumulative > rank) return static_cast<float>(i) / 255.0f;
    }
    return 1.0f;
}

inline YuvSignalDiagnostics summarizeNv21Signal(
        const std::uint8_t* nv21,
        std::size_t nv21Bytes,
        std::uint32_t width,
        std::uint32_t height,
        std::uint32_t targetSamples = 4096u) {
    YuvSignalDiagnostics out{};
    if (nv21 == nullptr || width < 2u || height < 2u || (width & 1u) != 0u || (height & 1u) != 0u) return out;
    const std::size_t yBytes = static_cast<std::size_t>(width) * height;
    const std::size_t required = yBytes + yBytes / 2u;
    if (nv21Bytes < required) return out;

    const double aspect = static_cast<double>(width) / static_cast<double>(height);
    const std::uint32_t gridY = std::max<std::uint32_t>(1u, static_cast<std::uint32_t>(
            std::sqrt(static_cast<double>(std::max<std::uint32_t>(1u, targetSamples)) /
                      std::max(1.0e-9, aspect))));
    const std::uint32_t gridX = std::max<std::uint32_t>(1u, static_cast<std::uint32_t>(
            std::ceil(static_cast<double>(targetSamples) / static_cast<double>(gridY))));

    std::array<std::uint32_t, 256u> yHistogram{};
    double sumY = 0.0, sumY2 = 0.0;
    double sumU = 0.0, sumU2 = 0.0;
    double sumV = 0.0, sumV2 = 0.0;
    double sumChromaEnergy = 0.0;
    double sumYDelta = 0.0, sumChromaDelta = 0.0;
    std::uint64_t deltaCount = 0u;

    for (std::uint32_t gy = 0u; gy < gridY; ++gy) {
        std::uint32_t y = static_cast<std::uint32_t>(
                (static_cast<std::uint64_t>(2u * gy + 1u) * height) / (2u * gridY));
        y = std::min(y, height - 1u);
        for (std::uint32_t gx = 0u; gx < gridX; ++gx) {
            std::uint32_t x = static_cast<std::uint32_t>(
                    (static_cast<std::uint64_t>(2u * gx + 1u) * width) / (2u * gridX));
            x = std::min(x, width - 1u);

            const std::size_t yIndex = static_cast<std::size_t>(y) * width + x;
            const float yf = static_cast<float>(nv21[yIndex]) / 255.0f;

            const std::uint32_t cx = x & ~1u;
            const std::uint32_t cy = y & ~1u;
            const std::size_t vuIndex = yBytes +
                    static_cast<std::size_t>(cy / 2u) * width + cx;
            const float vf = (static_cast<float>(nv21[vuIndex]) - 128.0f) / 255.0f;
            const float uf = (static_cast<float>(nv21[vuIndex + 1u]) - 128.0f) / 255.0f;

            ++yHistogram[nv21[yIndex]];
            sumY += yf;
            sumY2 += static_cast<double>(yf) * yf;
            sumU += uf;
            sumU2 += static_cast<double>(uf) * uf;
            sumV += vf;
            sumV2 += static_cast<double>(vf) * vf;
            sumChromaEnergy += static_cast<double>(uf) * uf + static_cast<double>(vf) * vf;
            ++out.sampleCount;

            if (x + 2u < width) {
                const float yRight = static_cast<float>(nv21[yIndex + 2u]) / 255.0f;
                const std::size_t vuRight = yBytes +
                        static_cast<std::size_t>(cy / 2u) * width + (cx + 2u);
                const float vRight = (static_cast<float>(nv21[vuRight]) - 128.0f) / 255.0f;
                const float uRight = (static_cast<float>(nv21[vuRight + 1u]) - 128.0f) / 255.0f;
                sumYDelta += std::abs(yf - yRight);
                const float du = uf - uRight;
                const float dv = vf - vRight;
                sumChromaDelta += std::sqrt(0.5 * (static_cast<double>(du) * du + static_cast<double>(dv) * dv));
                ++deltaCount;
            }
            if (y + 2u < height) {
                const std::size_t yDownIndex = static_cast<std::size_t>(y + 2u) * width + x;
                const float yDown = static_cast<float>(nv21[yDownIndex]) / 255.0f;
                const std::size_t vuDown = yBytes +
                        static_cast<std::size_t>((cy + 2u) / 2u) * width + cx;
                const float vDown = (static_cast<float>(nv21[vuDown]) - 128.0f) / 255.0f;
                const float uDown = (static_cast<float>(nv21[vuDown + 1u]) - 128.0f) / 255.0f;
                sumYDelta += std::abs(yf - yDown);
                const float du = uf - uDown;
                const float dv = vf - vDown;
                sumChromaDelta += std::sqrt(0.5 * (static_cast<double>(du) * du + static_cast<double>(dv) * dv));
                ++deltaCount;
            }
        }
    }

    if (out.sampleCount == 0u) return out;
    const double invN = 1.0 / static_cast<double>(out.sampleCount);
    const double meanY = sumY * invN;
    const double meanU = sumU * invN;
    const double meanV = sumV * invN;
    out.yMean = static_cast<float>(meanY);
    out.uCenteredMean = static_cast<float>(meanU);
    out.vCenteredMean = static_cast<float>(meanV);
    out.yStdDev = static_cast<float>(std::sqrt(std::max(0.0, sumY2 * invN - meanY * meanY)));
    out.uStdDev = static_cast<float>(std::sqrt(std::max(0.0, sumU2 * invN - meanU * meanU)));
    out.vStdDev = static_cast<float>(std::sqrt(std::max(0.0, sumV2 * invN - meanV * meanV)));
    out.chromaRmsFromNeutral = static_cast<float>(std::sqrt(std::max(0.0, 0.5 * sumChromaEnergy * invN)));
    if (deltaCount > 0u) {
        const double invDelta = 1.0 / static_cast<double>(deltaCount);
        out.lumaNeighbourDeltaMean = static_cast<float>(sumYDelta * invDelta);
        out.chromaNeighbourDeltaMean = static_cast<float>(sumChromaDelta * invDelta);
    }
    out.yP0_1 = histogramPercentile(yHistogram, out.sampleCount, 0.001);
    out.yP1 = histogramPercentile(yHistogram, out.sampleCount, 0.01);
    out.yP5 = histogramPercentile(yHistogram, out.sampleCount, 0.05);
    out.yP50 = histogramPercentile(yHistogram, out.sampleCount, 0.50);
    out.yP95 = histogramPercentile(yHistogram, out.sampleCount, 0.95);
    out.yP99 = histogramPercentile(yHistogram, out.sampleCount, 0.99);
    out.yP99_9 = histogramPercentile(yHistogram, out.sampleCount, 0.999);
    const std::uint32_t blackCount = yHistogram[0u] + yHistogram[1u];
    const std::uint32_t whiteCount = yHistogram[254u] + yHistogram[255u];
    out.blackClippedFraction = static_cast<float>(blackCount) / static_cast<float>(out.sampleCount);
    out.whiteClippedFraction = static_cast<float>(whiteCount) / static_cast<float>(out.sampleCount);
    out.contrastRatio = out.yP99_9 / std::max(1.0f / 255.0f, out.yP0_1);
    return out;
}

} // namespace bncam::yuvdiag
