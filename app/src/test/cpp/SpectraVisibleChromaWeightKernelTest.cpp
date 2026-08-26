#include "../../main/cpp/SpectraVisibleChromaFastMath.h"

#include <algorithm>
#include <array>
#include <cassert>
#include <cmath>
#include <cstdint>
#include <iostream>
#include <random>

using namespace bncam::spectra2;

namespace {
struct KernelResult {
    float filteredRG = 0.0f;
    float filteredBG = 0.0f;
    float maximumLumaDifference = 0.0f;
    float maximumColourDistanceSquared = 0.0f;
    int supportedNeighbours = 0;
};

struct Neighbour {
    float y = 0.0f;
    float rg = 0.0f;
    float bg = 0.0f;
};

constexpr std::array<float, 25> kSpatial{{
    0.08f, 0.16f, 0.22f, 0.16f, 0.08f,
    0.16f, 0.38f, 0.62f, 0.38f, 0.16f,
    0.22f, 0.62f, 1.00f, 0.62f, 0.22f,
    0.16f, 0.38f, 0.62f, 0.38f, 0.16f,
    0.08f, 0.16f, 0.22f, 0.16f, 0.08f
}};

float distanceSquared(const Neighbour& sample, const Neighbour& center,
                      float inverse00, float inverse01, float inverse11,
                      float inverseScaleSquared) {
    const float drg = sample.rg - center.rg;
    const float dbg = sample.bg - center.bg;
    return std::max(0.0f, inverseScaleSquared * (
        inverse00 * drg * drg + 2.0f * inverse01 * drg * dbg + inverse11 * dbg * dbg
    ));
}

KernelResult referenceKernel(const std::array<Neighbour, 25>& samples,
                             float inverse00, float inverse01, float inverse11,
                             float localNoiseScale, float sigmaY) {
    KernelResult result{};
    const Neighbour& center = samples[12];
    float weightedRG = 0.0f;
    float weightedBG = 0.0f;
    float weightSum = 0.0f;
    const float scale = std::clamp(localNoiseScale, 0.35f, 3.0f);
    const float inverseScaleSquared = 1.0f / (scale * scale);
    for (int dy = -2; dy <= 2; ++dy) {
        for (int dx = -2; dx <= 2; ++dx) {
            const int index = (dy + 2) * 5 + dx + 2;
            const auto& sample = samples[static_cast<std::size_t>(index)];
            const float deltaY = sample.y - center.y;
            const float d2 = distanceSquared(sample, center, inverse00, inverse01, inverse11,
                                             inverseScaleSquared);
            result.maximumLumaDifference = std::max(result.maximumLumaDifference, std::abs(deltaY));
            result.maximumColourDistanceSquared = std::max(result.maximumColourDistanceSquared, d2);
            const float lumaDistance = deltaY / std::max(1.0e-5f, sigmaY * localNoiseScale);
            const float lumaWeight = std::exp(-0.5f * lumaDistance * lumaDistance);
            const float colourWeight = std::exp(-0.5f * std::min(16.0f, d2));
            const float weight = kSpatial[static_cast<std::size_t>(index)] * lumaWeight * colourWeight;
            if ((dx != 0 || dy != 0) && weight >= 0.035f) result.supportedNeighbours++;
            weightedRG += weight * sample.rg;
            weightedBG += weight * sample.bg;
            weightSum += weight;
        }
    }
    result.filteredRG = weightSum > 1.0e-6f ? weightedRG / weightSum : center.rg;
    result.filteredBG = weightSum > 1.0e-6f ? weightedBG / weightSum : center.bg;
    return result;
}

KernelResult optimizedKernel(const std::array<Neighbour, 25>& samples,
                             float inverse00, float inverse01, float inverse11,
                             float localNoiseScale, float sigmaY) {
    KernelResult result{};
    const Neighbour& center = samples[12];
    float weightedRG = center.rg;
    float weightedBG = center.bg;
    float weightSum = 1.0f;
    const float scale = std::clamp(localNoiseScale, 0.35f, 3.0f);
    const float inverseScaleSquared = 1.0f / (scale * scale);
    const float inverseLumaSigma = 1.0f / std::max(1.0e-5f, sigmaY * localNoiseScale);
    for (const auto& offset : kVisibleNeighbourOffsets) {
        const int index = (offset.dy + 2) * 5 + offset.dx + 2;
        const auto& sample = samples[static_cast<std::size_t>(index)];
        const float deltaY = sample.y - center.y;
        const float d2 = distanceSquared(sample, center, inverse00, inverse01, inverse11,
                                         inverseScaleSquared);
        result.maximumLumaDifference = std::max(result.maximumLumaDifference, std::abs(deltaY));
        result.maximumColourDistanceSquared = std::max(result.maximumColourDistanceSquared, d2);
        const float lumaDistance = deltaY * inverseLumaSigma;
        const float weight = offset.spatialWeight *
            fastVisibleGaussianWeight(lumaDistance * lumaDistance) *
            fastVisibleGaussianWeight(std::min(16.0f, d2));
        if (weight >= 0.035f) result.supportedNeighbours++;
        weightedRG += weight * sample.rg;
        weightedBG += weight * sample.bg;
        weightSum += weight;
    }
    result.filteredRG = weightedRG / weightSum;
    result.filteredBG = weightedBG / weightSum;
    return result;
}
}

int main() {
    std::mt19937 rng(0x8A11CEu);
    std::uniform_real_distribution<float> signal(-0.25f, 1.25f);
    std::uniform_real_distribution<float> scale(0.35f, 2.25f);
    std::uniform_real_distribution<float> sigma(0.002f, 0.08f);

    float maximumFilteredDelta = 0.0f;
    for (int iteration = 0; iteration < 50000; ++iteration) {
        std::array<Neighbour, 25> samples{};
        for (auto& sample : samples) {
            sample.y = signal(rng);
            sample.rg = signal(rng) * 0.25f;
            sample.bg = signal(rng) * 0.25f;
        }
        const float inverse00 = 40.0f;
        const float inverse01 = -5.0f;
        const float inverse11 = 32.0f;
        const float localScale = scale(rng);
        const float sigmaY = sigma(rng);
        const auto reference = referenceKernel(samples, inverse00, inverse01, inverse11,
                                               localScale, sigmaY);
        const auto optimized = optimizedKernel(samples, inverse00, inverse01, inverse11,
                                               localScale, sigmaY);
        maximumFilteredDelta = std::max({maximumFilteredDelta,
            std::abs(reference.filteredRG - optimized.filteredRG),
            std::abs(reference.filteredBG - optimized.filteredBG)});
        assert(reference.supportedNeighbours == optimized.supportedNeighbours);
        assert(std::abs(reference.maximumLumaDifference - optimized.maximumLumaDifference) <= 1.0e-7f);
        assert(std::abs(reference.maximumColourDistanceSquared -
                        optimized.maximumColourDistanceSquared) <= 1.0e-6f);
    }
    assert(maximumFilteredDelta <= 1.0e-6f);
    std::cout << "SPECTRA_VISIBLE_CHROMA_WEIGHT_KERNEL_EQUIVALENCE_OK maxFilteredDelta="
              << maximumFilteredDelta << "\n";
    return 0;
}
