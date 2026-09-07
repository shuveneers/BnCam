#pragma once

#include <opencv2/opencv.hpp>
#include <algorithm>
#include <array>
#include <cmath>
#include <cstdint>
#include <string>
#include <vector>
#include "NativeRenderQualityConfig.h"
#include "Demosaic.h"
#include "RawDomain.h"
#include "SpectraNoisePropagation.h"
#include "SpectraNoiseCalibration.h"
#include "SpectraChromaBands.h"
#include "SpectraChromaMultiscale.h"
#include "SpectraVisibleChroma.h"
#include "SpectraAnisotropicDetail.h"
#include "SpectraDownstreamIsp.h"
#include "SpectraPerformanceBackend.h"

struct IspFrameMetadata {
    int cfaPattern = 0;
    int requestedDemosaicMode = static_cast<int>(DemosaicMode::NormalMalvar2004);
    bool demosaicFallbackOccurred = false;
    std::string demosaicFallbackReason = "none";
    bool demosaicFocusStabilityKnown = false;
    float demosaicFocusStabilityConfidence = 0.0f;
    float demosaicFocusSharpConfidence = 0.0f;
    float demosaicFocusMotionRisk = 0.0f;
    float demosaicFocusVelocityDioptersPerSec = 0.0f;
    float demosaicPredictiveAfConfidence = 0.0f;
    bool demosaicPersonEvidenceKnown = false;
    float demosaicPersonConfidence = 0.0f;
    int demosaicDetectedFaceCount = 0;
    float demosaicMaxFaceCoverage = 0.0f;
    bool demosaicTemporalStabilityKnown = false;
    float demosaicTemporalStaticConfidence = 0.0f;
    float demosaicTemporalObserverConfidence = 0.0f;
    float demosaicTemporalMotionAcceptance = 0.0f;
    int demosaicTemporalAcceptedPairs = 0;
    bool isRaw10 = false;
    int captureSensitivityIso = 0;
    int64_t captureExposureTimeNs = 0;
    float raw10UnpackMs = 0.0f;
    float raw10ToMasterRaw16Ms = 0.0f;
    float raw10MergeOrSingleMasterMs = 0.0f;
    float rawSensorReadMs = 0.0f;
    float rawSensorToMasterRaw16Ms = 0.0f;

    // Android LensShadingMap
    std::vector<float> lensShadingMap;
    int lensShadingColumns = 0;
    int lensShadingRows = 0;
    bool lensShadingFromMetadata = false;

    // Crop/active array variables
    bool activeArrayAppliedToRawJpeg = false;
    int rawJpegCropRegion[4] = {0, 0, 0, 0};
    int rawPreCorrectionArray[4] = {0, 0, 0, 0};
    int rawActiveArraySize[4] = {0, 0, 0, 0};
    bool rawVisibleCropApplied = false;
    int rawInvalidBorderCropPxLeft = 0;
    int rawInvalidBorderCropPxTop = 0;
    int rawInvalidBorderCropPxRight = 0;
    int rawInvalidBorderCropPxBottom = 0;
    bool rawBorderArtifactGuardApplied = false;
    std::string rawBorderArtifactReason = "none";
    bool raw10EdgeGuardApplied = false;
    std::string raw10EdgeGuardReason = "none";

    // Lens shading max/clamped debug
    float lensShadingEdgeGainMax = 1.0f;
    float lensShadingEdgeGainClamped = 3.5f;

    // Optional developer-only intermediate dumps. Disabled by default and never used by DNG.
    bool rawJpegDebugDumpsEnabled = false;
    std::string rawJpegDebugDumpDirectory;

    // Auxiliary phone color sensor fields (Phase 4S)
    bool phoneAssistanceSensorsEnabled = false;
    bool auxSensorValid = false;
    float auxCctKelvin = 0.0f;
    float auxContributionWeight = 0.0f;

    std::string captureAttemptId = "single_frame";

    // --- DE NIEUWE SINGLE SOURCE OF TRUTH ---
    FinalSensorCalibrationNative calibration;
};


struct SpectraIsoAdaptiveState {
    int captureIso = 100;
    int postRawSensitivityBoost = 100;
    float effectiveIso = 100.0f;
    float isoEvAbove100 = 0.0f;
    float isoPressure = 0.0f;
    float modelNoisePressure = 0.0f;
    float combinedNoisePressure = 0.0f;
    float lumaAuthority = 0.0f;
    float chromaAuthority = 0.0f;
    float lowFrequencyAuthority = 0.0f;
    float temporalAuthority = 0.0f;
    float detailRetentionFloor = 0.95f;
    float minimumResidualRatio = 0.80f;
    float targetFloorScale = 1.0f;
    float maxLinearShift = 0.003f;
    std::string regime = "LOW";

    std::string formatDebugString() const;
};

struct SpectraProvenanceTile {
    std::array<float, 4> meanSignal{0.0f, 0.0f, 0.0f, 0.0f};
    std::array<float, 4> predictedRawVarianceByChannel{0.0f, 0.0f, 0.0f, 0.0f};
    std::array<float, 4> predictedVisibleVarianceByChannel{0.0f, 0.0f, 0.0f, 0.0f};
    float predictedRawVariance = 0.0f;
    float predictedVisibleVariance = 0.0f;
    float predictedSpatialResidualVariance = 0.0f;
    float predictedChromaResidualVariance = 0.0f;
    float residualEnergy = 0.0f;
    float chromaResidualEnergy = 0.0f;
    float greenSplitResidualEnergy = 0.0f;
    float structureEnergy = 0.0f;
    float meanLensShadingGain = 1.0f;
    // Delta 36: channel-resolved lens/color-shading audit. CFA order is R,G1,G2,B.
    std::array<float, 4> meanLensShadingGainByChannel{1.0f, 1.0f, 1.0f, 1.0f};
    float confidence = 0.0f; // existing runtime authority; preserved for SPECTRA 1 behavior
    float diagnosticConfidence = 0.0f; // scene/model agreement for Milestone 1 observability
    uint32_t sampleCount = 0;
    bool valid = false;
};

struct SpectraProvenanceField {
    int gridCols = 0;
    int gridRows = 0;
    int tileWidth = 0;
    int tileHeight = 0;
    std::vector<SpectraProvenanceTile> tiles;
    float meanPredictedRawVariance = 0.0f;
    float meanPredictedVisibleVariance = 0.0f;
    float meanPredictedSpatialResidualVariance = 0.0f;
    float meanPredictedChromaResidualVariance = 0.0f;
    float meanResidualEnergy = 0.0f;
    float meanChromaResidualEnergy = 0.0f;
    float meanGreenSplitResidualEnergy = 0.0f;
    float meanLensShadingGain = 1.0f;
    float meanConfidence = 0.0f; // existing runtime confidence
    float meanDiagnosticConfidence = 0.0f;
    int totalTiles = 0;
    int validTiles = 0;
    float confidenceP10 = 0.0f;
    float confidenceP50 = 0.0f;
    float confidenceP90 = 0.0f;
    float lensShadingGainP10 = 1.0f;
    float lensShadingGainP50 = 1.0f;
    float lensShadingGainP90 = 1.0f;
    // Channel-dependent shading is reported independently from the scalar gain so a spatial
    // R/G or B/G correction cannot masquerade as scene chroma. Ratios are dimensionless.
    float lensShadingRedToGreenP50 = 1.0f;
    float lensShadingRedToGreenP90 = 1.0f;
    float lensShadingBlueToGreenP50 = 1.0f;
    float lensShadingBlueToGreenP90 = 1.0f;
    float lensShadingOpponentDifferentialStopsP50 = 0.0f;
    float lensShadingOpponentDifferentialStopsP90 = 0.0f;
    float lensShadingOuterMinusCenterRedToGreen = 0.0f;
    float lensShadingOuterMinusCenterBlueToGreen = 0.0f;
    float noiseBudgetP10 = 0.0f;
    float noiseBudgetP50 = 0.0f;
    float noiseBudgetP90 = 0.0f;
    float modelMismatchP10 = 0.0f;
    float modelMismatchP50 = 0.0f;
    float modelMismatchP90 = 0.0f;

    std::string formatDebugString() const;
};

struct SpectraNoRegretResult {
    int passIndex = 0;
    int totalTiles = 0;
    int evaluatedTiles = 0;
    int invalidTiles = 0;
    int acceptedTiles = 0;
    int partiallyAcceptedTiles = 0;
    int rejectedTiles = 0;
    int rejectedOversmooth = 0;
    int rejectedDetailLoss = 0;
    int rejectedMeanDrift = 0;
    int rejectedNoImprovement = 0;
    float meanAcceptance = 0.0f;
    float acceptanceP10 = 0.0f;
    float acceptanceP50 = 0.0f;
    float acceptanceP90 = 0.0f;
    float attenuatedPixelFraction = 0.0f;
    float rolledBackPixelFraction = 0.0f; // compatibility alias for attenuatedPixelFraction
    float meanRiskImprovement = 0.0f;
    float meanColourShift = 0.0f;
    float maxColourShift = 0.0f;
    float edgePreservationScore = 1.0f;
    float oversmoothingScore = 0.0f;

    std::string formatDebugString() const;
};

struct SpectraPass0State {
    int spectraMode = 0; // 0=Legacy/Off, 1=Auto, 2=Manual
    std::string sourceFormat = "UNKNOWN";
    std::string lensKey = "unknown";

    int totalTileCount = 0;
    int acceptedTileCount = 0;
    float darkTileConfidence = 0.0f;

    std::array<float, 4> channelBiasBefore{0.0f, 0.0f, 0.0f, 0.0f};
    std::array<float, 4> appliedChannelBias{0.0f, 0.0f, 0.0f, 0.0f};
    std::array<float, 4> channelBiasAfter{0.0f, 0.0f, 0.0f, 0.0f};

    float g1g2Before = 0.0f;
    float g1g2After = 0.0f;

    float rowVarianceBefore = 0.0f;
    float rowVarianceAfter = 0.0f;
    float colVarianceBefore = 0.0f;
    float colVarianceAfter = 0.0f;

    std::array<float, 4> zeroClipBefore{0.0f, 0.0f, 0.0f, 0.0f};
    std::array<float, 4> zeroClipAfter{0.0f, 0.0f, 0.0f, 0.0f};

    float channelBiasConfidence = 0.0f;
    float rowPatternConfidence = 0.0f;
    float columnPatternConfidence = 0.0f;

    bool applyChannelBias = false;
    bool applyRowCorrection = false;
    bool applyColumnCorrection = false;
    float greenSplitMad = 0.0f;
    float greenSplitTileConsensus = 0.0f;
    int greenSplitTileCount = 0;

    // Residual post-metadata CFA black calibration. Values are sensor code units.
    float commonGreenResidualBefore = 0.0f;
    float commonGreenResidualAfter = 0.0f;
    float commonGreenResidualConfidence = 0.0f;
    float commonGreenTileMedian = 0.0f;
    float commonGreenTileMad = 0.0f;
    float commonGreenTileConsensus = 0.0f;
    float commonGreenLowerTailRbMismatch = 0.0f;
    int commonGreenStrictTileCount = 0;
    bool residualPedestalApplied = false;
    std::string residualPedestalReason = "not_evaluated";

    std::string classification = "K. Insufficient evidence";
    std::string fallbackReason = "none";
    std::string planningMethod = "LEGACY_FULL_FRAME_REFERENCE";
    std::uint64_t planningSampleCount = 0u;
    bool sceneBlackMetadataAuthoritative = false;
    bool sceneBlackImageMutationAllowed = true;
    std::string sceneBlackAuthorityMode = "IMAGE_DERIVED_FALLBACK_ELIGIBLE";
    float isoAuthority = 0.0f;
    float noRegretAcceptedTileFraction = 0.0f;
    float noRegretRollbackFraction = 0.0f;
    bool vulkanAttempted = false;
    bool vulkanExecutionSucceeded = false;
    bool vulkanUsedForOutput = false;
    bool vulkanCpuFallbackUsed = false;
    bool vulkanGpuNoRegretBlendUsed = false;
    bool vulkanCandidateReadbackAvoided = false;
    std::string vulkanStatus = "NOT_ATTEMPTED";
    std::string vulkanFailureReason = "none";
    float vulkanPass0KernelMs = 0.0f;
    float vulkanTileStatisticsKernelMs = 0.0f;
    float vulkanNoRegretDecisionMs = 0.0f;
    float vulkanNoRegretBlendMs = 0.0f;
    float vulkanSynchronizationMs = 0.0f;
    float vulkanCompactReadbackMs = 0.0f;
    std::uint64_t vulkanResidentGeneration = 0u;
    float processingTimeMs = 0.0f;

    std::string formatDebugString() const;
};

struct SpectraPass1State {
    bool physicalBaselineMode = false;
    std::string authoritySource = "SPECTRA";
    int spectraMode = 0; // 0=Legacy/Off, 1=Auto, 2=Manual
    bool applied = false;
    std::string fallbackReason = "none";
    float averageVstResidualVar = 1.0f;
    float maxPixelShift = 0.0f;
    // Linear-domain per-pixel safety cap. 0.028 preserves the existing non-physical GPU path.
    float maxLinearShift = 0.028f;
    float modelConfidence = 0.0f;
    float blendStrength = 0.0f;
    float averageWienerGain = 0.0f;
    float edgeProtectedFraction = 0.0f;
    float changedPixelFraction = 0.0f;
    float isoAuthority = 0.0f;
    float combinedNoisePressure = 0.0f;
    float localShadingAuthorityMean = 1.0f;
    float noRegretAcceptedTileFraction = 0.0f;
    float noRegretRollbackFraction = 0.0f;
    std::array<double, 4> effectiveS{0.0, 0.0, 0.0, 0.0};
    std::array<double, 4> effectiveO{0.0, 0.0, 0.0, 0.0};
    float processingTimeMs = 0.0f;
    bncam::spectra2::AnisotropicDetailTelemetry anisotropicDetail{};

    // Milestone 8H-B: Pass 1 is GPU-primary when the authoritative Vulkan
    // runtime is READY. CPU remains a typed fallback/reference only.
    bool vulkanKernelConnected = false;
    bool vulkanAttempted = false;
    bool vulkanExecutionSucceeded = false;
    bool vulkanUsedForOutput = false;
    bool vulkanCpuFallbackUsed = false;
    bool vulkanGpuNoRegretBlendUsed = false;
    bool vulkanCandidateReadbackAvoided = false;
    bool vulkanPersistentReuseHit = false;
    bool vulkanPersistentReallocated = false;
    std::string vulkanStatus = "NOT_RUN";
    std::string vulkanFailureReason = "none";
    float vulkanInputPackingMs = 0.0f;
    float vulkanTensorUploadMs = 0.0f;
    float vulkanPass1KernelMs = 0.0f;
    float vulkanTileStatisticsKernelMs = 0.0f;
    float vulkanNoRegretDecisionMs = 0.0f;
    float vulkanNoRegretBlendMs = 0.0f;
    float vulkanGpuKernelMs = 0.0f;
    float vulkanSynchronizationMs = 0.0f;
    float vulkanReadbackMs = 0.0f;
    float vulkanTransferAndSyncMs = 0.0f;
    float vulkanTotalMs = 0.0f;
    std::uint64_t vulkanResidentBytes = 0;
    std::uint64_t vulkanAllocationGeneration = 0;

    std::string formatDebugString() const;
};

struct SpectraPass2State {
    bool physicalBaselineMode = false;
    std::string authoritySource = "SPECTRA";
    int spectraMode = 0; // 0=Legacy/Off, 1=Auto, 2=Manual
    bool applied = false;
    std::string fallbackReason = "none";
    float averageChromaReductionPct = 0.0f;
    float maxChromaShift = 0.0f;
    float modelConfidence = 0.0f;
    float blendStrength = 0.0f;
    float isoAuthority = 0.0f;
    float localShadingAuthorityMean = 1.0f;
    float noRegretAcceptedTileFraction = 0.0f;
    float noRegretRollbackFraction = 0.0f;
    std::array<double, 4> effectiveS{0.0, 0.0, 0.0, 0.0};
    std::array<double, 4> effectiveO{0.0, 0.0, 0.0, 0.0};
    float processingTimeMs = 0.0f;
    std::string multiscaleArchitecture = "SPECTRA_CONTEXT_FUSION_MULTISCALE_CFA_CHROMA";
    bncam::spectra2::ChromaBandTelemetry fineBand{};
    bncam::spectra2::ChromaBandTelemetry midBand{};
    std::string bandEnergyStatus = "UNAVAILABLE";
    std::string bandEnergyMethod = "UNINITIALIZED";
    std::uint64_t bandEnergySampleCount = 0;
    std::uint64_t bandEnergyRedSampleCount = 0;
    std::uint64_t bandEnergyBlueSampleCount = 0;
    float bandEnergyRedBlueSampleBalance = 0.0f;
    float bandEnergyConfidence = 0.0f;
    float bandEnergyMeasurementMs = 0.0f;
    std::uint64_t profiledStrongFineBlendQuadCount = 0;
    std::uint64_t profiledColorEdgeProtectedQuadCount = 0;
    std::uint64_t profiledHeavyFineCleanQuadCount = 0;
    float profiledStrongFineBlendFraction = 0.0f;
    float profiledColorEdgeProtectedFraction = 0.0f;
    float profiledHeavyFineCleanFraction = 0.0f;

    // Delta 23: conservative per-opponent redistribution of the existing Pass-2 authority.
    // These multipliers remain centred on 1.0 and are bounded to +/-12%; they do not alter
    // whether a band is enabled or its maximum correction scale.
    bool splitOpponentAuthorityActive = false;
    float splitOpponentMaximumModulation = 0.12f;
    float fineRedAuthorityMultiplier = 1.0f;
    float fineBlueAuthorityMultiplier = 1.0f;
    float midRedAuthorityMultiplier = 1.0f;
    float midBlueAuthorityMultiplier = 1.0f;

    int pixelWorkingCloneCount = 0;
    int avoidedFullFrameCloneCount = 0;
    std::string pixelBufferStrategy = "UNINITIALIZED";

    // Milestone 8H-C: Pass 2 full-frame fine/mid candidate and No-Regret blend are GPU-primary.
    bool vulkanKernelConnected = false;
    bool vulkanAttempted = false;
    bool vulkanExecutionSucceeded = false;
    bool vulkanUsedForOutput = false;
    bool vulkanCpuFallbackUsed = false;
    bool vulkanGpuNoRegretBlendUsed = false;
    bool vulkanCandidateReadbackAvoided = false;
    bool vulkanPersistentReuseHit = false;
    bool vulkanPersistentReallocated = false;
    std::string vulkanStatus = "NOT_RUN";
    std::string vulkanFailureReason = "none";
    float vulkanInputPackingMs = 0.0f;
    float vulkanAuxiliaryUploadMs = 0.0f;
    float vulkanFineKernelMs = 0.0f;
    float vulkanMidKernelMs = 0.0f;
    float vulkanTileStatisticsKernelMs = 0.0f;
    float vulkanNoRegretDecisionMs = 0.0f;
    float vulkanNoRegretBlendMs = 0.0f;
    float vulkanGpuKernelMs = 0.0f;
    float vulkanSynchronizationMs = 0.0f;
    float vulkanReadbackMs = 0.0f;
    float vulkanTransferAndSyncMs = 0.0f;
    float vulkanTotalMs = 0.0f;
    std::uint64_t vulkanResidentBytes = 0;
    std::uint64_t vulkanAllocationGeneration = 0;

    std::string formatDebugString() const;
};

struct SpectraPass3State {
    bool physicalBaselineMode = false;
    std::string authoritySource = "SPECTRA";
    int spectraMode = 0; // 0=Legacy/Off, 1=Auto, 2=Manual
    bool applied = false;
    bool applyRowBanding = false;
    bool applyColBanding = false;
    bool applyLowFreqChroma = false;
    std::string fallbackReason = "none";
    float rowPatternEnergyBefore = 0.0f;
    float rowPatternEnergyAfter = 0.0f;
    float colPatternEnergyBefore = 0.0f;
    float colPatternEnergyAfter = 0.0f;
    float maxLowFreqChromaShift = 0.0f;
    float lowFreqChromaConfidence = 0.0f;
    float rowPatternConfidence = 0.0f;
    float columnPatternConfidence = 0.0f;
    float bandingAuthority = 0.0f;
    float chromaAuthority = 0.0f;
    float isoAuthority = 0.0f;
    float noRegretAcceptedTileFraction = 0.0f;
    float noRegretRollbackFraction = 0.0f;
    int chromaGridCols = 0;
    int chromaGridRows = 0;
    std::array<std::vector<float>, 4> rowProfileByChannel;
    std::array<std::vector<float>, 4> columnProfileByChannel;
    std::vector<float> lowFreqRGrid;
    std::vector<float> lowFreqBGrid;
    std::vector<float> lowFreqRConfidenceGrid;
    std::vector<float> lowFreqBConfidenceGrid;
    float processingTimeMs = 0.0f;
    std::string multiscaleArchitecture = "SPECTRA_CONTEXT_FUSION_MULTISCALE_CFA_CHROMA";
    bncam::spectra2::ChromaBandTelemetry lowBand{};
    std::string bandEnergyStatus = "UNAVAILABLE";
    std::string bandEnergyMethod = "UNINITIALIZED";
    std::uint64_t bandEnergySampleCount = 0;
    std::uint64_t bandEnergyRedSampleCount = 0;
    std::uint64_t bandEnergyBlueSampleCount = 0;
    float bandEnergyRedBlueSampleBalance = 0.0f;
    float bandEnergyConfidence = 0.0f;
    float bandEnergyMeasurementMs = 0.0f;

    // Delta 24: conservative R-G / B-G redistribution of low-frequency Pass-3 authority.
    // This affects the low-frequency chroma field only; row/column banding authority is unchanged.
    bool splitLowOpponentAuthorityActive = false;
    float splitLowOpponentMaximumModulation = 0.12f;
    float lowRedAuthorityMultiplier = 1.0f;
    float lowBlueAuthorityMultiplier = 1.0f;

    // Milestone 8H-H: compact Pass-3 planning is GPU-primary when Pass 2 is resident.
    bool plannerGpuAttempted = false;
    bool plannerGpuUsed = false;
    bool plannerResidentInputUsed = false;
    bool plannerCpuFallbackUsed = false;
    std::string plannerStatus = "NOT_RUN";
    std::string plannerFailureReason = "none";
    std::string plannerSamplingMethod = "NOT_RUN";
    float plannerKernelMs = 0.0f;
    float plannerSynchronizationMs = 0.0f;
    float plannerReadbackMs = 0.0f;
    float plannerTotalMs = 0.0f;
    std::uint64_t plannerCompactBytes = 0;

    // Milestone 8H-C: Pass 3 full-frame banding/low-frequency application and No-Regret blend are GPU-primary.
    bool vulkanKernelConnected = false;
    bool vulkanAttempted = false;
    bool vulkanExecutionSucceeded = false;
    bool vulkanUsedForOutput = false;
    bool vulkanCpuFallbackUsed = false;
    bool vulkanGpuNoRegretBlendUsed = false;
    bool vulkanCandidateReadbackAvoided = false;
    bool vulkanPersistentReuseHit = false;
    bool vulkanPersistentReallocated = false;
    std::string vulkanStatus = "NOT_RUN";
    std::string vulkanFailureReason = "none";
    float vulkanInputPackingMs = 0.0f;
    float vulkanAuxiliaryUploadMs = 0.0f;
    float vulkanPass3KernelMs = 0.0f;
    float vulkanTileStatisticsKernelMs = 0.0f;
    float vulkanNoRegretDecisionMs = 0.0f;
    float vulkanNoRegretBlendMs = 0.0f;
    float vulkanGpuKernelMs = 0.0f;
    float vulkanSynchronizationMs = 0.0f;
    float vulkanReadbackMs = 0.0f;
    float vulkanTransferAndSyncMs = 0.0f;
    float vulkanTotalMs = 0.0f;
    std::uint64_t vulkanResidentBytes = 0;
    std::uint64_t vulkanAllocationGeneration = 0;

    std::string formatDebugString() const;
};

// Phase 13 final production RAW-JPEG entry. Pixel ownership stays in Vulkan;
// masterRaw16 is used only for bounded compact planning and explicit CPU failsafe after GPU failure.
struct ResidentRawRenderInput {
    RawNormalizedSampleView sampleView{};
    std::uint64_t rawNormalizeGeneration = 0u;
};

struct SpectraResidualNoiseState {
    std::string domain = "FINAL_JPEG_RGB";
    std::string valueStage = "FINAL_JPEG_PRE_ENCODE";
    std::string lastObservedStage = "PRE_JPEG_ENCODE_8BIT";
    std::string propagationStatus = "MILESTONE_2_PROPAGATED_THROUGH_TONE";
    std::string opponentVarianceStatus = "DERIVED_FROM_RGB_COVARIANCE";
    std::string covarianceStatus = "SYMMETRIC_PSD_BOUNDED";
    std::string comparabilityStatus =
            "HIGHLIGHT_RECOVERY_VIBRANCE_COLOR_MANAGEMENT_NR_SHARPEN_QUANTIZATION_NOT_PROPAGATED";
    float varianceY = 0.0f;
    float varianceRG = 0.0f;
    float varianceBG = 0.0f;
    float covarianceRgBg = 0.0f;
    std::array<float, 9> covarianceRgb{};
    float highFrequencyBudget = 0.0f;
    float midFrequencyBudget = 0.0f;
    float lowFrequencyBudget = 0.0f;
    float correlationLength = 0.0f;
    float directionalPatternEnergy = 0.0f;
    float rowPatternEnergy = 0.0f;
    float columnPatternEnergy = 0.0f;
    float temporalCorrelation = 0.0f;
    float independentNoiseFraction = 1.0f;
    float effectiveFrameCount = 1.0f;
    float modelConfidence = 0.0f;
    float motionConfidence = 0.0f;
    float alignmentConfidence = 0.0f;

    bncam::spectra2::NoiseState preDemosaic{};
    bncam::spectra2::NoiseState postDemosaic{};
    bncam::spectra2::NoiseState postAwb{};
    bncam::spectra2::NoiseState postColourTransform{};
    bncam::spectra2::NoiseState postLinearDetail{};
    bncam::spectra2::NoiseState postTone{};
    bncam::spectra2::NoiseState postVisibleChroma{};
    bncam::spectra2::NoiseState postQuantization{};
    bncam::spectra2::NoiseState finalJpeg{};

    bncam::spectra2::ResidualObservation measuredPostDemosaic{};
    bncam::spectra2::ResidualObservation measuredPostColourTransform{};
    bncam::spectra2::CalibrationComparison postDemosaicCalibration{};
    bncam::spectra2::CalibrationComparison postColourTransformCalibration{};
    std::string calibrationStatus = "MILESTONE_2B_NOT_EVALUATED";
    std::array<float, 3> propagationAwbGainsRgb{1.0f, 1.0f, 1.0f};
    std::array<float, 9> propagationColourMatrix{};

    bncam::spectra2::DerivativeStats toneCurveDerivative{};
    bncam::spectra2::DerivativeStats sectionCurveDerivative{};
    bncam::spectra2::DerivativeStats gammaCurveDerivative{};
    bncam::spectra2::DerivativeStats totalToneDerivative{};
    bncam::spectra2::DerivativeStats toneChromaScale{};

    float predictedVisibleChromaAmplification = 1.0f;
    bool demosaicMeasuredChromaCalibrationReady = false;
    float demosaicMeasuredToPredictedPostChromaRmsRatio = 1.0f;
    float demosaicMeasuredChromaAuthorityPressure = 1.0f;

    // Delta 41: low-frequency post-demosaic chroma-field classification. These values are
    // evidence only; no pixel authority is granted by the classifier itself.
    bool demosaicChromaCloudClassificationReady = false;
    float demosaicChromaFieldToResidualRmsRatio = 0.0f;
    float demosaicChromaFieldCoherence = 0.0f;
    float demosaicUnexpectedChromaAmplification = 0.0f;
    float demosaicPreLowFrequencyChromaSupport = 0.0f;
    float demosaicChromaCloudRiskEvidence = 0.0f;
    std::string demosaicChromaCloudRiskStatus = "UNAVAILABLE";

    float visibleChromaPlanningPressure = 1.0f;
    float predictedVisibleVarianceY = 0.0f;
    float predictedVisibleVarianceRG = 0.0f;
    float predictedVisibleVarianceBG = 0.0f;
    float predictedVisibleCovarianceRgBg = 0.0f;
    float measuredPreSharpenVarianceY = 0.0f;
    float measuredPreSharpenVarianceRG = 0.0f;
    float measuredPreSharpenVarianceBG = 0.0f;
    float measuredPreSharpenCovarianceRgBg = 0.0f;
    int measuredPreSharpenSampleCount = 0;
    float measuredPostIspVarianceY = 0.0f;
    float measuredPostIspVarianceRG = 0.0f;
    float measuredPostIspVarianceBG = 0.0f;
    float measuredPostIspCovarianceRgBg = 0.0f;
    int measuredPostIspSampleCount = 0;
    std::string measuredPostIspStage = "FINAL_JPEG_PRE_ENCODE_POST_QUANTIZATION_IDENTITY";
    std::string measuredPostIspMethod = "CROSS_5_HIGH_PASS_ENERGY_DIVIDED_BY_1_25";
    std::string measuredPostIspStatus =
            "CONTROLLED_SCENE_PROXY_SPATIAL_CORRELATION_AND_TEXTURE_NOT_DECONVOLVED";
    float measuredPostIspFilterEnergyGain = 1.25f;
    bool pass2VisibleTargetReady = false;
    std::string pass2VisibleTargetStatus = "UNAVAILABLE";
    bool pass2VisibleTargetPreventedSkip = false;
    float pass2VisibleTargetConfidence = 0.0f;
    float pass2VisibleChromaAmplification = 1.0f;

    float demosaicPropagationMs = 0.0f;
    float awbPropagationMs = 0.0f;
    float colourTransformPropagationMs = 0.0f;
    float linearDetailPropagationMs = 0.0f;
    float tonePropagationMs = 0.0f;
    float measuredPostDemosaicResidualMs = 0.0f;
    float measuredPostColourTransformResidualMs = 0.0f;
    float measuredVisibleResidualMs = 0.0f;
    float measuredPreSharpenResidualMs = 0.0f;
    float downstreamSharpenPropagationMs = 0.0f;

    std::string formatDebugString() const;
};

struct SpectraVisibleChromaState {
    bncam::spectra2::VisibleChromaTelemetry telemetry{};
    std::string activationSource = "DISABLED";
    std::string architecture = "SPECTRA_CONTEXT_FUSION_POST_TONE_VISIBLE_CHROMA";
    std::string inputStage = "POST_TONE_VIBRANCE_COLOR_MANAGEMENT_LINEAR_RGB";
    std::string outputStage = "PRE_QUANTIZATION_LINEAR_RGB";
    std::string covarianceSource = "SPECTRA_POST_TONE_PROPAGATED_RG_BG_COVARIANCE";
    std::string timingAccounting = "PROCESSING_INCLUDES_NEIGHBOUR_FILTER_EXCLUDES_RESIDUAL_MEASUREMENTS";

    std::string formatDebugString() const;
};


struct SpectraDownstreamIspState {
    bncam::spectra2::DownstreamSharpenPlan plan{};
    bool applied = false;
    bool localContrastApplied = false;
    std::string architecture = "MILESTONE_7_NOISE_AWARE_DOWNSTREAM_ISP";
    std::string inputStage = "POST_QUANTIZATION_8BIT_PRE_SHARPEN";
    std::string outputStage = "FINAL_JPEG_PRE_ENCODE";
    std::string resultStatus = "NOT_RUN";
    std::uint64_t processedPixelCount = 0;
    std::uint64_t candidatePixelCount = 0;
    std::uint64_t changedPixelCount = 0;
    std::uint64_t noiseRejectedPixelCount = 0;
    std::uint64_t haloProtectedPixelCount = 0;
    std::uint64_t localContrastPixelCount = 0;
    float changedPixelFraction = 0.0f;
    float meanLocalAuthority = 0.0f;
    float authorityP10 = 0.0f;
    float authorityP50 = 0.0f;
    float authorityP90 = 0.0f;
    float meanEdgeSnr = 0.0f;
    float maximumAppliedAmount = 0.0f;
    float maximumLocalContrastAmount = 0.0f;
    float inputVarianceY = 0.0f;
    float outputVarianceY = 0.0f;
    float measuredVarianceGainY = 1.0f;
    float predictedVarianceGainY = 1.0f;
    int inputResidualSampleCount = 0;
    int outputResidualSampleCount = 0;
    float processingTimeMs = 0.0f;
    float inputMeasurementMs = 0.0f;
    float outputMeasurementMs = 0.0f;

    std::string formatDebugString() const;
};

struct SpectraBudgetState {
    int spectraMode = 0; // 0=Legacy/Off, 1=Auto, 2=Manual
    bool applied = false;
    float predictedNoiseFloor = 0.0f;
    float initialResidualEnergy = 0.0f;
    float pass1ResidualEnergy = 0.0f;
    float pass2ResidualEnergy = 0.0f;
    float pass3ResidualEnergy = 0.0f;
    float finalRemainingEnergy = 0.0f;
    float predictedChromaNoiseFloor = 0.0f;
    float initialChromaResidualEnergy = 0.0f;
    float pass2ChromaResidualEnergy = 0.0f;
    float downstreamLumaAuthority = 1.0f;
    float downstreamChromaAuthority = 1.0f;
    bool pass1SkippedBudgetReached = false;
    bool pass2SkippedBudgetReached = false;
    bool pass3SkippedBudgetReached = false;
    float effectiveIso = 100.0f;
    float isoNoisePressure = 0.0f;
    float provenanceMeanConfidence = 0.0f;
    float provenanceMeanShadingGain = 1.0f;
    float noRegretMeanAcceptance = 0.0f;
    std::string isoRegime = "LOW";

    std::string formatDebugString() const;
};

// SPECTRA Variance Stabilisation Transform (VST) helper functions
inline bool isVstValidDomain(float x, double S, double O) {
    if (S <= 1.0e-12 || O < 0.0 || !std::isfinite(S) || !std::isfinite(O)) return false;
    const double xd = static_cast<double>(x);
    return (S * xd + O + 0.375 * S * S) > 1.0e-6;
}

inline bool isVstValid(double S, double O) {
    return S > 1.0e-12 && O >= 0.0 && std::isfinite(S) && std::isfinite(O);
}

inline float spectraForwardVst(float x, double S, double O) {
    if (!isVstValidDomain(x, S, O)) return x;
    const double xd = static_cast<double>(x);
    const double arg = S * xd + O + 0.375 * S * S;
    return static_cast<float>((2.0 / S) * std::sqrt(arg));
}

inline float spectraInverseVst(float y, double S, double O) {
    if (S <= 1.0e-12 || O < 0.0 || !std::isfinite(S) || !std::isfinite(O)) return y;
    const double yd = static_cast<double>(y);
    const double sy2 = 0.5 * S * yd;
    const double xd = (1.0 / S) * (sy2 * sy2 - O - 0.375 * S * S);
    return static_cast<float>(xd);
}


struct UltraHdrGainmapArtifact {
    bool valid = false;
    std::uint32_t width = 0u;
    std::uint32_t height = 0u;
    std::uint32_t rowStrideBytes = 0u;
    float minContentBoost = 1.0f;
    float maxContentBoost = 1.0f;
    float gamma = 1.0f;
    float offsetSdr = 1.0f / 64.0f;
    float offsetHdr = 1.0f / 64.0f;
    std::vector<std::uint8_t> pixels;
    std::string status = "NOT_REQUESTED";
};

class IspCore {
public:
    static UltraHdrGainmapArtifact takeLastUltraHdrGainmapArtifact();

    static NativeRenderQualityConfig resolveForRaw(
            const NativeRenderQualityConfig& uiConfig,
            bool isRaw10
    );

    static NativeRenderQualityConfig resolveForYuv(
            const NativeRenderQualityConfig& uiConfig
    );

    static std::string describeResolvedConfig(
            const NativeRenderQualityConfig& uiConfig,
            bool isRaw10,
            bool isYuv
    );

    /** Native deterministic proof for S/O sampling, Dynamic ISO, and measurement-only noise-model truth. */
    static std::string validateNoiseModelImplementation(
            const std::array<double, 8>& lowNoiseSo,
            const std::array<double, 8>& highNoiseSo
    );

    static void applyBlackLevelAndWb(
            cv::Mat& bayer16,
            const IspFrameMetadata& meta,
            const NativeRenderQualityConfig& uiConfig
    );

    static SpectraIsoAdaptiveState resolveSpectraIsoAdaptiveState(
            const IspFrameMetadata& meta,
            const NativeRenderQualityConfig& uiConfig
    );

    static SpectraProvenanceField buildSpectraProvenanceField(
            const LinearFloatRaw& raw,
            const IspFrameMetadata& meta
    );

    // Phase 13 resident-path provenance: same compact estimator, sampled directly
    // from the canonical RAW16 publication alias without full-frame float materialization.
    static SpectraProvenanceField buildSpectraProvenanceFieldCompact(
            const RawNormalizedSampleView& raw,
            const IspFrameMetadata& meta
    );

    static SpectraNoRegretResult applySpectraNoRegretGate(
            const cv::Mat& before,
            LinearFloatRaw& candidate,
            const IspFrameMetadata& meta,
            const SpectraIsoAdaptiveState& isoState,
            const SpectraProvenanceField& beforeField,
            int passIndex
    );

    static SpectraPass0State computePass0State(
            const LinearFloatRaw& raw,
            const IspFrameMetadata& meta,
            const NativeRenderQualityConfig& uiConfig
    );

    // Phase 13 production-planning variant: bounded deterministic RAW16 samples only.
    static SpectraPass0State computePass0StateCompact(
            const RawNormalizedSampleView& raw,
            const IspFrameMetadata& meta,
            const NativeRenderQualityConfig& uiConfig
    );

    static void applySpectraPass0(
            LinearFloatRaw& raw,
            const IspFrameMetadata& meta,
            const SpectraPass0State& pass0State
    );

    static SpectraPass1State computePass1State(
            const LinearFloatRaw& raw,
            const IspFrameMetadata& meta,
            const NativeRenderQualityConfig& uiConfig
    );

    // Phase 13 resident-path state selection: metadata + validated RAW descriptor only.
    static SpectraPass1State computePass1StateCompact(
            const RawNormalizedSampleView& raw,
            const IspFrameMetadata& meta,
            const NativeRenderQualityConfig& uiConfig
    );

    static void applySpectraPass1(
            LinearFloatRaw& raw,
            const IspFrameMetadata& meta,
            SpectraPass1State& pass1State
    );

    static SpectraPass2State computePass2State(
            const LinearFloatRaw& raw,
            const IspFrameMetadata& meta,
            const NativeRenderQualityConfig& uiConfig
    );

    // Phase 13 resident-path state selection: no full-frame CPU mosaic dependency.
    static SpectraPass2State computePass2StateCompact(
            const RawNormalizedSampleView& raw,
            const IspFrameMetadata& meta,
            const NativeRenderQualityConfig& uiConfig
    );

    static void applySpectraPass2(
            LinearFloatRaw& raw,
            const IspFrameMetadata& meta,
            SpectraPass2State& pass2State
    );

    static SpectraPass3State computePass3State(
            const LinearFloatRaw& raw,
            const IspFrameMetadata& meta,
            const NativeRenderQualityConfig& uiConfig
    );

    static void applySpectraPass3(
            LinearFloatRaw& raw,
            const IspFrameMetadata& meta,
            SpectraPass3State& pass3State
    );

    static float computeResidualEnergy(
            const LinearFloatRaw& raw
    );

    static float computeChromaResidualEnergy(
            const LinearFloatRaw& raw
    );

    // The sole RAW10/RAW_SENSOR JPEG renderer. YUV and DNG do not enter this path.
    static std::vector<uint8_t> renderRawBaselineJpeg(
            LinearFloatRaw workingRaw,
            const IspFrameMetadata& meta,
            const NativeRenderQualityConfig& uiConfig,
            std::string* debugOut,
            int rotationDegrees = 0,
            const ResidentRawRenderInput* residentInput = nullptr
    );

    static std::vector<uint8_t> demosaicAndEncodeJpeg(
            const cv::Mat& bayer16,
            const IspFrameMetadata& meta,
            const NativeRenderQualityConfig& uiConfig,
            std::string* debugOut,
            int rotationDegrees = 0
    );

    static std::vector<uint8_t> demosaicAndEncodeJpeg(
            LinearFloatRaw& workingRaw,
            const IspFrameMetadata& meta,
            const NativeRenderQualityConfig& uiConfig,
            std::string* debugOut,
            int rotationDegrees = 0
    );

    static std::vector<uint8_t> demosaicAndEncodeJpeg(
            const cv::Mat& bayer16,
            const IspFrameMetadata& meta,
            const NativeRenderQualityConfig& uiConfig
    );

    // Compatibility overload for older callers. Values are interpreted as UI slider values
    // (-1..+1, neutral 0), not as final ISP multipliers.
    static std::vector<uint8_t> demosaicAndEncodeJpeg(
            const cv::Mat& bayer16,
            const IspFrameMetadata& meta,
            float exposureUi,
            float contrastUi,
            float saturationUi
    );
};
