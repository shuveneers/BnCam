#pragma once

#include "SpectraNoiseProfileUncertainty.h"

#include <algorithm>
#include <array>
#include <cmath>
#include <cstdint>
#include <string>

namespace bncam::spectra2 {

struct OpponentCovariance2 {
    float varianceRG = 0.0f;
    float varianceBG = 0.0f;
    float covarianceRgBg = 0.0f;
    float determinant = 0.0f;
    float inverse00 = 0.0f;
    float inverse01 = 0.0f;
    float inverse11 = 0.0f;
    float correlation = 0.0f;
    bool valid = false;
    std::string status = "UNAVAILABLE";
};

inline float visibleFiniteUnit(float value) {
    return std::isfinite(value) ? std::clamp(value, 0.0f, 1.0f) : 0.0f;
}

inline float visibleSmoothstep(float edge0, float edge1, float value) {
    if (!std::isfinite(value) || !(edge1 > edge0)) return 0.0f;
    const float t = std::clamp((value - edge0) / (edge1 - edge0), 0.0f, 1.0f);
    return t * t * (3.0f - 2.0f * t);
}

// The visible-chroma pass is primarily a colour-residual filter, but luma is used to
// decide whether neighbours belong to the same structure. SPECTRA can legitimately
// predict a very small residual luma variance after its upstream passes while visible
// opponent noise is still amplified by WB/CCM. Using that tiny luma sigma literally
// makes ordinary chroma-noise texture look like a hard luma edge and suppresses the
// downstream cleanup. Couple the luma guard weakly to the opponent model so the edge
// detector cannot become materially more sensitive than the colour-noise evidence.
inline float resolveVisibleChromaLumaGuardSigma(
        float predictedVarianceY,
        float sigmaRG,
        float sigmaBG) noexcept {
    const float predictedSigmaY = std::sqrt(std::max(
            0.0f, std::isfinite(predictedVarianceY) ? predictedVarianceY : 0.0f));
    const float opponentSigma = std::max(
            std::isfinite(sigmaRG) ? std::max(0.0f, sigmaRG) : 0.0f,
            std::isfinite(sigmaBG) ? std::max(0.0f, sigmaBG) : 0.0f);
    return std::clamp(std::max(predictedSigmaY, 0.65f * opponentSigma), 0.0015f, 0.08f);
}

inline OpponentCovariance2 makeOpponentCovariance(
        float varianceRG,
        float varianceBG,
        float covarianceRgBg,
        float varianceFloor = 1.0e-8f
) {
    OpponentCovariance2 model{};
    if (!std::isfinite(varianceRG) || !std::isfinite(varianceBG) ||
        !std::isfinite(covarianceRgBg)) {
        model.status = "NONFINITE_COVARIANCE_FALLBACK";
        return model;
    }
    model.varianceRG = std::max(varianceFloor, varianceRG);
    model.varianceBG = std::max(varianceFloor, varianceBG);
    const float covarianceBound = 0.98f * std::sqrt(model.varianceRG * model.varianceBG);
    model.covarianceRgBg = std::clamp(covarianceRgBg, -covarianceBound, covarianceBound);
    model.determinant = model.varianceRG * model.varianceBG -
            model.covarianceRgBg * model.covarianceRgBg;
    const float determinantFloor = std::max(
            varianceFloor * varianceFloor,
            1.0e-5f * model.varianceRG * model.varianceBG
    );
    if (!(model.determinant > determinantFloor)) {
        // A diagonal fallback is safer than pretending a near-singular model can be inverted.
        model.covarianceRgBg = 0.0f;
        model.determinant = model.varianceRG * model.varianceBG;
        model.status = "DIAGONAL_REGULARIZED_FALLBACK";
    } else {
        model.status = "COVARIANCE_2X2_READY";
    }
    model.inverse00 = model.varianceBG / model.determinant;
    model.inverse01 = -model.covarianceRgBg / model.determinant;
    model.inverse11 = model.varianceRG / model.determinant;
    model.correlation = model.covarianceRgBg /
            std::sqrt(model.varianceRG * model.varianceBG);
    model.valid = std::isfinite(model.inverse00) && std::isfinite(model.inverse01) &&
            std::isfinite(model.inverse11) && model.inverse00 > 0.0f &&
            model.inverse11 > 0.0f;
    if (!model.valid) model.status = "INVERSE_INVALID_FALLBACK";
    return model;
}

inline float opponentMahalanobisSquared(
        const OpponentCovariance2& model,
        float deltaRG,
        float deltaBG,
        float localNoiseScale = 1.0f
) {
    if (!model.valid || !std::isfinite(deltaRG) || !std::isfinite(deltaBG)) return 0.0f;
    const float scale = std::clamp(
            std::isfinite(localNoiseScale) ? localNoiseScale : 1.0f,
            0.35f,
            3.0f
    );
    const float invScale2 = 1.0f / (scale * scale);
    const float value = invScale2 * (
            model.inverse00 * deltaRG * deltaRG +
            2.0f * model.inverse01 * deltaRG * deltaBG +
            model.inverse11 * deltaBG * deltaBG
    );
    return std::isfinite(value) ? std::max(0.0f, value) : 0.0f;
}

struct VisibleChromaPlan {
    bool enabled = false;
    std::string status = "UNINITIALIZED";
    std::string method = "SPECTRA_CONTEXT_FUSION_OPPONENT_5X5";
    std::string noRegretMethod =
            "ROBUST_NOISE_NORMALIZED_CONTEXT_HARD_EDGE_GUARD";
    float modelConfidence = 0.0f;
    float authorityConfidenceFactor = 0.0f;
    float authority = 0.0f;
    float maximumCorrection = 0.0f;
    float sigmaRG = 0.0f;
    float sigmaBG = 0.0f;
    float covarianceCorrelation = 0.0f;
    float predictedVarianceRG = 0.0f;
    float predictedVarianceBG = 0.0f;
    float predictedCovarianceRgBg = 0.0f;
};

inline VisibleChromaPlan buildVisibleChromaPlan(
        bool spectraEnabled,
        float predictedVarianceRG,
        float predictedVarianceBG,
        float predictedCovarianceRgBg,
        float modelConfidence,
        float downstreamChromaAuthority,
        float configuredStrength,
        float visibleChromaAmplification
) {
    VisibleChromaPlan plan{};
    plan.predictedVarianceRG = std::max(
            0.0f,
            std::isfinite(predictedVarianceRG) ? predictedVarianceRG : 0.0f
    );
    plan.predictedVarianceBG = std::max(
            0.0f,
            std::isfinite(predictedVarianceBG) ? predictedVarianceBG : 0.0f
    );
    plan.predictedCovarianceRgBg = std::isfinite(predictedCovarianceRgBg)
            ? predictedCovarianceRgBg
            : 0.0f;
    plan.modelConfidence = visibleFiniteUnit(modelConfidence);
    if (plan.predictedVarianceRG <= 1.0e-8f && plan.predictedVarianceBG <= 1.0e-8f) {
        plan.status = spectraEnabled ? "VISIBLE_PREDICTION_UNAVAILABLE" : "SPECTRA_OFF";
        return plan;
    }
    const OpponentCovariance2 covariance = makeOpponentCovariance(
            plan.predictedVarianceRG,
            plan.predictedVarianceBG,
            plan.predictedCovarianceRgBg
    );
    plan.sigmaRG = std::sqrt(std::max(0.0f, covariance.varianceRG));
    plan.sigmaBG = std::sqrt(std::max(0.0f, covariance.varianceBG));
    plan.covarianceCorrelation = covariance.correlation;

    if (!spectraEnabled) {
        plan.status = "SPECTRA_OFF";
        return plan;
    }
    if (!covariance.valid || plan.modelConfidence < 0.10f) {
        plan.status = covariance.valid
                ? "MODEL_CONFIDENCE_TOO_LOW"
                : covariance.status;
        return plan;
    }
    const float pressure = std::clamp(
            std::isfinite(visibleChromaAmplification) ? visibleChromaAmplification : 1.0f,
            0.65f,
            4.0f
    );
    const float pressureAuthority = std::clamp(0.88f + 0.12f * (pressure - 1.0f), 0.78f, 1.25f);
    const float strengthMultiplier = (configuredStrength >= 0.0f)
            ? (1.0f + configuredStrength * 1.20f)
            : (1.0f + configuredStrength * 0.55f);
    plan.authorityConfidenceFactor = resolveNoiseProfileAuthorityConfidence(plan.modelConfidence);
    const float effectiveDownstream = std::max(
            visibleFiniteUnit(downstreamChromaAuthority),
            0.55f + 0.30f * std::max(0.0f, configuredStrength)
    );
    const float baseAuthority = effectiveDownstream *
            strengthMultiplier * plan.authorityConfidenceFactor;
    plan.authority = std::clamp(baseAuthority * pressureAuthority, 0.0f, 0.96f);
    const float maximumSigma = std::max(plan.sigmaRG, plan.sigmaBG);
    plan.maximumCorrection = std::clamp(5.5f * maximumSigma * strengthMultiplier, 0.008f, 0.18f);
    plan.enabled = plan.authority >= 0.01f && plan.maximumCorrection > 0.0f;
    plan.status = plan.enabled ? "VISIBLE_CHROMA_PLAN_READY" : "AUTHORITY_TOO_LOW";
    return plan;
}

struct VisibleChromaDecision {
    bool supported = false;
    std::string status = "UNINITIALIZED";
    float candidateRG = 0.0f;
    float candidateBG = 0.0f;
    float outputRG = 0.0f;
    float outputBG = 0.0f;
    float correctionRG = 0.0f;
    float correctionBG = 0.0f;
    float mahalanobisBefore = 0.0f;
    float mahalanobisAfter = 0.0f;
    float noiseImprovement = 0.0f;
    float structureWeight = 0.0f;
    float colourEdgeWeight = 0.0f;
    float colourDriftWeight = 0.0f;
    float oversmoothingWeight = 0.0f;
    float acceptance = 0.0f;
    float correctionMagnitude = 0.0f;
};

inline VisibleChromaDecision resolveVisibleChromaDecision(
        const VisibleChromaPlan& plan,
        const OpponentCovariance2& covariance,
        float centerRG,
        float centerBG,
        float filteredRG,
        float filteredBG,
        float localNoiseScale,
        float lumaEdgeSigma,
        float colourEdgeSigma,
        float maximumLumaEdgeSigma,
        float maximumColourEdgeSigma,
        float luminance,
        float saturation,
        float supportFraction
) {
    VisibleChromaDecision result{};
    result.candidateRG = std::isfinite(filteredRG) ? filteredRG : centerRG;
    result.candidateBG = std::isfinite(filteredBG) ? filteredBG : centerBG;
    result.outputRG = centerRG;
    result.outputBG = centerBG;
    if (!plan.enabled || !covariance.valid || !std::isfinite(centerRG) ||
        !std::isfinite(centerBG)) {
        result.status = plan.enabled ? "INVALID_INPUT_OR_COVARIANCE" : plan.status;
        return result;
    }

    const float deltaRG = result.candidateRG - centerRG;
    const float deltaBG = result.candidateBG - centerBG;
    result.mahalanobisBefore = opponentMahalanobisSquared(
            covariance,
            deltaRG,
            deltaBG,
            localNoiseScale
    );
    // Below roughly half a two-dimensional sigma there is too little evidence to justify a change.
    const float noiseNeed = visibleSmoothstep(0.15f, 2.50f, result.mahalanobisBefore);
    result.structureWeight = std::clamp(
            1.0f - 0.80f * visibleSmoothstep(1.25f, 2.30f, lumaEdgeSigma),
            0.20f,
            1.0f
    );
    result.colourEdgeWeight = std::clamp(
            1.0f - 0.78f * visibleSmoothstep(1.85f, 3.35f, colourEdgeSigma),
            0.22f,
            1.0f
    );
    if (maximumLumaEdgeSigma > 7.0f && lumaEdgeSigma > 1.55f) {
        result.structureWeight *= 0.25f;
    }
    if (maximumColourEdgeSigma > 7.0f && colourEdgeSigma > 2.20f) {
        result.colourEdgeWeight *= 0.25f;
    }
    const float luminanceReliability = visibleSmoothstep(0.10f, 0.32f, luminance);
    const float colourStructureReliability = visibleSmoothstep(1.70f, 3.20f, colourEdgeSigma);
    const float saturationProtection = visibleSmoothstep(0.30f, 0.80f, saturation) *
            luminanceReliability * (0.45f + 0.55f * colourStructureReliability);
    const float saturationWeight = std::clamp(1.0f - 0.85f * saturationProtection, 0.15f, 1.0f);
    const float supportWeight = visibleSmoothstep(0.08f, 0.38f, supportFraction);

    const float requestedRG = deltaRG * plan.authority * noiseNeed;
    const float requestedBG = deltaBG * plan.authority * noiseNeed;
    const float requestedMagnitude = std::sqrt(
            requestedRG * requestedRG + requestedBG * requestedBG
    );
    result.colourDriftWeight = requestedMagnitude > 1.0e-9f
            ? std::clamp(plan.maximumCorrection / requestedMagnitude, 0.0f, 1.0f)
            : 1.0f;

    // Strong local chroma structure is a useful oversmoothing warning even when luma is flat.
    result.oversmoothingWeight = std::clamp(
            0.55f + 0.45f * result.colourEdgeWeight,
            0.0f,
            1.0f
    );
    // `noiseNeed` already scales requestedRG/requestedBG above. Applying it again here
    // squares the evidence term and collapses moderate-but-real chroma corrections toward zero.
    // Acceptance is therefore a pure safety/context gate; noise demand owns amplitude exactly once.
    result.acceptance = std::clamp(
            result.structureWeight * result.colourEdgeWeight *
                    saturationWeight * supportWeight * result.colourDriftWeight *
                    result.oversmoothingWeight,
            0.0f,
            1.0f
    );

    result.correctionRG = requestedRG * result.acceptance;
    result.correctionBG = requestedBG * result.acceptance;
    const float correctionMagnitude = std::sqrt(
            result.correctionRG * result.correctionRG +
            result.correctionBG * result.correctionBG
    );
    if (correctionMagnitude > plan.maximumCorrection && correctionMagnitude > 1.0e-9f) {
        const float scale = plan.maximumCorrection / correctionMagnitude;
        result.correctionRG *= scale;
        result.correctionBG *= scale;
    }
    result.correctionMagnitude = std::sqrt(
            result.correctionRG * result.correctionRG +
            result.correctionBG * result.correctionBG
    );
    result.outputRG = centerRG + result.correctionRG;
    result.outputBG = centerBG + result.correctionBG;
    const float remainingRG = result.candidateRG - result.outputRG;
    const float remainingBG = result.candidateBG - result.outputBG;
    result.mahalanobisAfter = opponentMahalanobisSquared(
            covariance,
            remainingRG,
            remainingBG,
            localNoiseScale
    );
    result.noiseImprovement = result.mahalanobisBefore > 1.0e-9f
            ? std::clamp(
                    (result.mahalanobisBefore - result.mahalanobisAfter) /
                            result.mahalanobisBefore,
                    -1.0f,
                    1.0f
            )
            : 0.0f;
    result.supported = result.correctionMagnitude >= 1.0e-6f &&
            result.acceptance >= 0.01f && result.noiseImprovement >= 0.0f;
    result.status = result.supported
            ? "VISIBLE_CHROMA_CORRECTION_ACCEPTED"
            : (noiseNeed <= 0.0f
                    ? "BELOW_VISIBLE_NOISE_FLOOR"
                    : "NO_REGRET_REJECTED_OR_NEGLIGIBLE");
    return result;
}

struct VisibleChromaTelemetry {
    VisibleChromaPlan plan{};
    bool applied = false;
    std::string resultStatus = "NOT_RUN";
    std::uint64_t processedPixelCount = 0;
    std::uint64_t candidatePixelCount = 0;
    std::uint64_t changedPixelCount = 0;
    std::uint64_t lumaEdgeProtectedPixelCount = 0;
    std::uint64_t colourEdgeProtectedPixelCount = 0;
    std::uint64_t saturationProtectedPixelCount = 0;
    std::uint64_t fullyAcceptedPixelCount = 0;
    std::uint64_t partiallyAcceptedPixelCount = 0;
    std::uint64_t rejectedPixelCount = 0;
    std::uint64_t fullyAcceptedTileCount = 0;
    std::uint64_t partiallyAcceptedTileCount = 0;
    std::uint64_t rejectedTileCount = 0;
    std::uint64_t evaluatedTileCount = 0;
    float changedPixelFraction = 0.0f;
    float meanAcceptance = 0.0f;
    float acceptanceP10 = 0.0f;
    float acceptanceP50 = 0.0f;
    float acceptanceP90 = 0.0f;
    float meanColourShift = 0.0f;
    float maximumColourShift = 0.0f;
    float meanNoiseImprovement = 0.0f;
    float edgePreservationScore = 1.0f;
    float oversmoothingScore = 0.0f;
    float inputVarianceRG = 0.0f;
    float inputVarianceBG = 0.0f;
    float outputVarianceRG = 0.0f;
    float outputVarianceBG = 0.0f;
    float inputCovarianceRgBg = 0.0f;
    float outputCovarianceRgBg = 0.0f;
    int inputResidualSampleCount = 0;
    int outputResidualSampleCount = 0;
    float processingTimeMs = 0.0f;
    float inputMeasurementMs = 0.0f;
    float outputMeasurementMs = 0.0f;

    // Milestone 8C pixel-backend observability. These fields describe the
    // immutable-tile opponent preprocessing and deterministic tile reduction;
    // they do not alter the Milestone-4 correction contract.
    std::string pixelBackend = "NOT_SELECTED";
    std::string pixelBackendSelectionReason = "UNAVAILABLE";
    std::string pixelBackendFallbackReason = "none";
    bool pixelNeonCompiled = false;
    bool pixelSimdSelfTestPerformed = false;
    bool pixelSimdSelfTestPassed = false;
    float pixelSimdSelfTestMaximumAbsoluteDelta = 0.0f;
    float pixelSimdSelfTestElapsedMs = 0.0f;
    bool pixelLatencyBenchmarkPerformed = false;
    bool pixelLatencyBenchmarkPassed = false;
    float pixelScalarBenchmarkMs = 0.0f;
    float pixelNeonBenchmarkMs = 0.0f;
    float pixelBenchmarkSpeedup = 0.0f;
    std::uint64_t opponentVectorizedPixelCount = 0;
    std::uint64_t opponentScalarPixelCount = 0;
    std::uint64_t opponentRejectedNonFinitePixelCount = 0;
    std::uint64_t opponentEstimatedBytesRead = 0;
    std::uint64_t opponentEstimatedBytesWritten = 0;
    std::uint64_t opponentPeakScratchBytes = 0;
    int opponentTileCount = 0;
    int opponentTileSize = 0;
    float opponentTileBuildMs = 0.0f;
    bool mutexFreeTileReduction = false;

    // Milestone 8E Vulkan FP32 opponent-feature qualification and activation.
    bool vulkanOpponentKernelConnected = false;
    bool vulkanOpponentAttempted = false;
    bool vulkanOpponentExecutionSucceeded = false;
    bool vulkanOpponentUsedForOutput = false;
    bool vulkanOpponentTimestampQueryUsed = false;
    bool vulkanOpponentBenchmarkPerformed = false;
    bool vulkanOpponentNumericalEquivalencePassed = false;
    bool vulkanOpponentDeviceQualificationSelected = false;
    bool vulkanOpponentBenchmarkAborted = false;
    bool vulkanOpponentMemoryGatePassed = false;
    int vulkanOpponentBenchmarkSampleCount = 0;
    int vulkanOpponentFailedSampleCount = 0;
    float vulkanOpponentCurrentMaximumAbsoluteDelta = 0.0f;
    float vulkanOpponentMaximumAbsoluteDelta = 0.0f;
    float vulkanOpponentCurrentCpuReferenceMs = 0.0f;
    std::uint64_t vulkanOpponentCurrentCpuVectorizedPixelCount = 0;
    std::uint64_t vulkanOpponentCurrentCpuScalarPixelCount = 0;
    float vulkanOpponentCurrentGpuKernelMs = 0.0f;
    float vulkanOpponentCurrentGpuTotalMs = 0.0f;
    float vulkanOpponentCurrentTransferAndSyncMs = 0.0f;
    float vulkanOpponentCpuMedianMs = 0.0f;
    float vulkanOpponentGpuKernelMedianMs = 0.0f;
    float vulkanOpponentGpuTotalMedianMs = 0.0f;
    float vulkanOpponentTransferAndSyncMedianMs = 0.0f;
    float vulkanOpponentMeasuredSpeedup = 0.0f;
    float vulkanOpponentTransferFraction = 1.0f;
    float vulkanOpponentQualificationOverheadMs = 0.0f;
    std::uint64_t vulkanOpponentInputBytes = 0;
    std::uint64_t vulkanOpponentOutputBytes = 0;
    std::uint64_t vulkanOpponentAllocatedBytes = 0;
    std::uint64_t vulkanOpponentEstimatedTransientBytes = 0;
    std::uint64_t vulkanOpponentMaximumTransientBytes = 0;
    bool vulkanOpponentStripedMode = false;
    bool vulkanOpponentStripPlanValid = false;
    bool vulkanOpponentPersistentBufferReuseObserved = false;
    bool vulkanOpponentPersistentBufferReallocated = false;
    int vulkanOpponentStripCount = 0;
    int vulkanOpponentSuccessfulStripCount = 0;
    int vulkanOpponentTargetOutputRows = 0;
    int vulkanOpponentHaloRows = 0;
    int vulkanOpponentMaximumInputRows = 0;
    int vulkanOpponentPersistentBufferReuseHitCount = 0;
    int vulkanOpponentPersistentBufferReallocationCount = 0;
    float vulkanOpponentAssemblyMs = 0.0f;
    std::uint64_t vulkanOpponentFullFrameOutputBytes = 0;
    std::uint64_t vulkanOpponentMaximumStripInputBytes = 0;
    std::uint64_t vulkanOpponentMaximumStripOutputBytes = 0;
    std::uint64_t vulkanOpponentPersistentBufferCapacityBytes = 0;
    std::uint64_t vulkanOpponentPersistentResidentBytes = 0;
    std::uint64_t vulkanOpponentPersistentAllocationGeneration = 0;
    std::string vulkanOpponentStripPlanStatus = "NOT_PLANNED";
    std::string vulkanOpponentCpuReferenceBackend = "NOT_RUN";
    std::string vulkanOpponentCpuReferenceStatus = "NOT_RUN";
    std::string vulkanOpponentRouteKey = "UNSET";
    std::string vulkanOpponentExecutionStatus = "NOT_RUN";
    std::string vulkanOpponentQualificationStatus = "NO_SAMPLES";
    std::string vulkanOpponentFailureReason = "none";

    // Milestone 8G bounded Vulkan candidate-filter path. The GPU may produce a
    // 5x5 visible-chroma candidate, but the existing CPU No-Regret evaluator remains
    // authoritative. Selection requires shader equivalence and sampled final-decision
    // compatibility with the Milestone-4 CPU reference across warm device captures.
    bool vulkanVisibleCandidateKernelConnected = false;
    bool vulkanVisibleCandidateAttempted = false;
    bool vulkanVisibleCandidateExecutionSucceeded = false;
    bool vulkanVisibleCandidateUsedForOutput = false;
    bool vulkanVisibleCandidateTimestampQueryUsed = false;
    bool vulkanVisibleCandidateBenchmarkPerformed = false;
    bool vulkanVisibleCandidateShaderEquivalencePassed = false;
    bool vulkanVisibleCandidateFinalDecisionCompatibilityPassed = false;
    bool vulkanVisibleCandidateDeviceQualificationSelected = false;
    bool vulkanVisibleCandidateBenchmarkAborted = false;
    bool vulkanVisibleCandidateMemoryGatePassed = false;
    int vulkanVisibleCandidateBenchmarkSampleCount = 0;
    int vulkanVisibleCandidateFailedSampleCount = 0;
    int vulkanVisibleCandidateStripCount = 0;
    int vulkanVisibleCandidateSuccessfulStripCount = 0;
    std::uint64_t vulkanVisibleCandidateFinalDecisionComparedPixelCount = 0u;
    float vulkanVisibleCandidateStrength = 0.0f;
    float vulkanVisibleCandidateCurrentShaderMaximumAbsoluteDelta = 0.0f;
    float vulkanVisibleCandidateShaderMaximumAbsoluteDelta = 0.0f;
    float vulkanVisibleCandidateCurrentFinalDecisionMaximumAbsoluteDelta = 0.0f;
    float vulkanVisibleCandidateFinalDecisionMaximumAbsoluteDelta = 0.0f;
    float vulkanVisibleCandidateCurrentCpuReferenceMs = 0.0f;
    float vulkanVisibleCandidateCurrentGpuKernelMs = 0.0f;
    float vulkanVisibleCandidateCurrentGpuTotalMs = 0.0f;
    float vulkanVisibleCandidateCurrentTransferAndSyncMs = 0.0f;
    float vulkanVisibleCandidateCpuMedianMs = 0.0f;
    float vulkanVisibleCandidateGpuKernelMedianMs = 0.0f;
    float vulkanVisibleCandidateGpuTotalMedianMs = 0.0f;
    float vulkanVisibleCandidateTransferAndSyncMedianMs = 0.0f;
    float vulkanVisibleCandidateMeasuredSpeedup = 0.0f;
    float vulkanVisibleCandidateTransferFraction = 1.0f;
    float vulkanVisibleCandidateQualificationOverheadMs = 0.0f;
    std::uint64_t vulkanVisibleCandidateInputBytes = 0u;
    std::uint64_t vulkanVisibleCandidateOutputBytes = 0u;
    std::uint64_t vulkanVisibleCandidateAllocatedBytes = 0u;
    std::uint64_t vulkanVisibleCandidateEstimatedTransientBytes = 0u;
    std::uint64_t vulkanVisibleCandidateMaximumTransientBytes = 0u;
    std::string vulkanVisibleCandidateRouteKey = "UNSET";
    std::string vulkanVisibleCandidateExecutionStatus = "NOT_RUN";
    std::string vulkanVisibleCandidateQualificationStatus = "NO_SAMPLES";
    std::string vulkanVisibleCandidateFailureReason = "none";
    std::string vulkanVisibleCandidateNoRegretExecution =
            "CPU_AUTHORITATIVE_NO_REGRET_AFTER_VULKAN_CANDIDATE";

    // Milestone 8H: the 5x5 opponent filter, covariance-aware No-Regret decision and
    // gamut-safe reconstruction execute in one Vulkan kernel. Production captures do
    // not also run the CPU pixel loop; CPU remains a typed fallback only.
    bool vulkanResidentKernelConnected = false;
    bool vulkanResidentAttempted = false;
    bool vulkanResidentExecutionSucceeded = false;
    bool vulkanResidentUsedForOutput = false;
    bool vulkanResidentTimestampQueryUsed = false;
    bool vulkanResidentCpuFallbackUsed = false;
    bool vulkanResidentPersistentReuseObserved = false;
    bool vulkanResidentPersistentReallocated = false;
    int vulkanResidentStripCount = 0;
    int vulkanResidentSuccessfulStripCount = 0;
    int vulkanResidentPersistentReuseHitCount = 0;
    int vulkanResidentPersistentReallocationCount = 0;
    float vulkanResidentInputPackingMs = 0.0f;
    float vulkanResidentSpatialKernelMs = 0.0f;
    float vulkanResidentVisibleKernelMs = 0.0f;
    float vulkanResidentGpuKernelMs = 0.0f;
    float vulkanResidentSynchronizationMs = 0.0f;
    float vulkanResidentReadbackMs = 0.0f;
    float vulkanResidentTransferAndSyncMs = 0.0f;
    float vulkanResidentTotalMs = 0.0f;
    std::uint64_t vulkanResidentInputBytes = 0u;
    std::uint64_t vulkanResidentIntermediateBytes = 0u;
    std::uint64_t vulkanResidentOutputBytes = 0u;
    std::uint64_t vulkanResidentSpatialMapBytes = 0u;
    std::uint64_t vulkanResidentPersistentResidentBytes = 0u;
    std::uint64_t vulkanResidentAllocationGeneration = 0u;
    std::string vulkanResidentExecutionStatus = "NOT_RUN";
    std::string vulkanResidentFailureReason = "none";
    std::string vulkanResidentStatisticsMethod = "NOT_RUN";
    std::string vulkanResidentAuthority = "GPU_FINAL_DECISION_PRIMARY_CPU_FALLBACK_ONLY";

    bool textureClassifierExecuted = false;
    bool adaptiveLumaExecuted = false;
    float avgStructureConfidence = 0.0f;
    float minStructureConfidence = 0.0f;
    float maxStructureConfidence = 0.0f;
    float avgAppliedLumaAuthority = 0.0f;
    float avgAppliedChromaAuthority = 0.0f;
    float smoothRegionPercentage = 0.0f;
    float protectedTexturePercentage = 0.0f;
};

} // namespace bncam::spectra2
