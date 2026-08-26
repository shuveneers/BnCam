#pragma once

#include <algorithm>
#include <array>
#include <cmath>

namespace bncam::spectra2 {

struct ChromaNoRegretDomain {
    float beforeEnergy = 0.0f;
    float targetVariance = 0.0f;
};

inline float resolvePredictedChromaResidualVariance(
        const std::array<float, 4>& predictedRawVarianceByChannel) {
    const float greenVariance = 0.5f * (
            std::max(0.0f, predictedRawVarianceByChannel[1]) +
            std::max(0.0f, predictedRawVarianceByChannel[2]));
    float sum = 0.0f;
    int channels = 0;
    for (const int ch : {0, 3}) {
        const float colourVariance = std::max(0.0f, predictedRawVarianceByChannel[ch]);
        if (!(colourVariance > 0.0f)) continue;
        sum += 1.5f * (colourVariance + 0.25f * greenVariance);
        ++channels;
    }
    return channels > 0 ? sum / static_cast<float>(channels) : 0.0f;
}

struct ChromaProvenanceCarryPlan {
    float globalEnergyScale = 1.0f;
    bool usedGpuPostPassEnergy = false;
};

inline ChromaProvenanceCarryPlan resolveChromaProvenanceCarryPlan(
        float beforeGlobalChromaEnergy,
        float afterGlobalChromaEnergy) {
    ChromaProvenanceCarryPlan out{};
    if (!std::isfinite(beforeGlobalChromaEnergy) ||
        !std::isfinite(afterGlobalChromaEnergy) ||
        beforeGlobalChromaEnergy <= 1.0e-12f ||
        afterGlobalChromaEnergy < 0.0f) {
        return out;
    }
    // The resident compact observer measures post-pass chroma globally. Preserve the
    // measured spatial distribution from the previous provenance field and normalize it
    // to the GPU-observed post-pass mean. Bounds contain corrupt/global outliers.
    out.globalEnergyScale = std::clamp(
            afterGlobalChromaEnergy / beforeGlobalChromaEnergy,
            0.25f,
            4.0f);
    out.usedGpuPostPassEnergy = true;
    return out;
}

inline float carryChromaResidualEnergy(float beforeTileEnergy, float globalEnergyScale) {
    const float safeTile = std::isfinite(beforeTileEnergy)
            ? std::max(0.0f, beforeTileEnergy)
            : 0.0f;
    const float safeScale = std::isfinite(globalEnergyScale)
            ? std::clamp(globalEnergyScale, 0.25f, 4.0f)
            : 1.0f;
    return safeTile * safeScale;
}

inline ChromaNoRegretDomain resolveChromaNoRegretDomain(
        int passIndex,
        float spatialResidualEnergy,
        float chromaResidualEnergy,
        float predictedSpatialResidualVariance,
        float predictedChromaResidualVariance) {
    ChromaNoRegretDomain out{};
    const float spatial = std::max(0.0f, std::isfinite(spatialResidualEnergy)
            ? spatialResidualEnergy : 0.0f);
    const float chroma = std::max(0.0f, std::isfinite(chromaResidualEnergy)
            ? chromaResidualEnergy : 0.0f);
    const float predictedSpatial = std::max(0.0f, std::isfinite(predictedSpatialResidualVariance)
            ? predictedSpatialResidualVariance : 0.0f);
    const float predictedChroma = std::max(0.0f, std::isfinite(predictedChromaResidualVariance)
            ? predictedChromaResidualVariance : 0.0f);

    if (passIndex == 2) {
        out.beforeEnergy = chroma;
        out.targetVariance = predictedChroma;
    } else if (passIndex == 3) {
        out.beforeEnergy = 0.55f * spatial + 0.45f * chroma;
        out.targetVariance = 0.55f * predictedSpatial + 0.45f * predictedChroma;
    } else {
        out.beforeEnergy = spatial;
        out.targetVariance = predictedSpatial;
    }
    return out;
}

struct Pass3ColourDriftDecision {
    float acceptanceScale = 1.0f;
    float commonOpponentDrift = 0.0f;
    float differentialOpponentDrift = 0.0f;
    float commonOpponentLimit = 0.0f;
    float differentialOpponentLimit = 0.0f;
    bool hardReject = false;
};

inline Pass3ColourDriftDecision resolvePass3ColourDriftDecision(
        float deltaRg,
        float deltaBg,
        float predictedChromaResidualVariance,
        float combinedNoisePressure) {
    Pass3ColourDriftDecision out{};
    if (!std::isfinite(deltaRg) || !std::isfinite(deltaBg)) {
        out.acceptanceScale = 0.0f;
        out.hardReject = true;
        return out;
    }

    const float predictedSigma = std::sqrt(std::max(
            1.0e-12f,
            std::isfinite(predictedChromaResidualVariance)
                    ? predictedChromaResidualVariance : 0.0f));
    const float pressure = std::clamp(
            std::isfinite(combinedNoisePressure) ? combinedNoisePressure : 0.0f,
            0.0f,
            1.0f);

    // The common opponent axis (R-G and B-G moving together) is exactly the
    // green<->magenta direction that is visually most destructive on flat walls.
    // A low-frequency denoiser may reduce variance, but it must not create a new
    // local WB/tint field. Keep this bound intentionally tighter than the R<->B axis.
    out.commonOpponentDrift = std::abs(0.5f * (deltaRg + deltaBg));
    out.differentialOpponentDrift = std::abs(0.5f * (deltaRg - deltaBg));
    out.commonOpponentLimit = std::max(
            0.00018f,
            (0.28f + 0.12f * pressure) * predictedSigma);
    out.differentialOpponentLimit = std::max(
            0.00028f,
            (0.42f + 0.16f * pressure) * predictedSigma);

    if (out.commonOpponentDrift > 3.0f * out.commonOpponentLimit ||
        out.differentialOpponentDrift > 3.0f * out.differentialOpponentLimit) {
        out.acceptanceScale = 0.0f;
        out.hardReject = true;
        return out;
    }

    const auto boundedScale = [](float drift, float limit) {
        if (!(limit > 0.0f) || drift <= limit) return 1.0f;
        return std::clamp(limit / std::max(drift, 1.0e-12f), 0.0f, 1.0f);
    };
    out.acceptanceScale = std::min(
            boundedScale(out.commonOpponentDrift, out.commonOpponentLimit),
            boundedScale(out.differentialOpponentDrift, out.differentialOpponentLimit));
    return out;
}

} // namespace bncam::spectra2
