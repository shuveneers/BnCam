#pragma once

#include <string>

#include <opencv2/core.hpp>

enum class DemosaicMode : int {
    Auto = 0,
    NormalMalvar2004 = 1,
    QualityMenon2007 = 2,
    Bilinear = 3
};

enum class DemosaicAlgorithm : int {
    Malvar2004 = 0,
    Menon2007 = 1,
    Bilinear = 2, // Legacy/reference-only; no longer used by product bridge slot 3.
    NeuralJdd = 3,
    RcdInspired = NeuralJdd, // Legacy ABI alias; product identity is Neural JDD.
    AmazeInspired = 4
};

struct DemosaicResolution {
    DemosaicMode requestedMode = DemosaicMode::NormalMalvar2004;
    DemosaicAlgorithm algorithm = DemosaicAlgorithm::Malvar2004;
    std::string reason = "normal_mode_forces_malvar_2004";
    bool fallbackOccurred = false;
    std::string fallbackReason = "none";
    bool autoSceneAnalysisUsed = false;
    int autoSampleCount = 0;
    float autoMedianSignal = 0.0f;
    float autoMeanGradient = 0.0f;
    float autoP90Gradient = 0.0f;
    float autoEdgeFraction = 0.0f;
    float autoCoherentEdgeFraction = 0.0f;
    float autoLowSignalFraction = 0.0f;
    float autoMalvarScore = 0.0f;
    float autoRcdScore = 0.0f; // Legacy storage field; Phase 5 stores Neural-JDD score here.
    float autoAmazeScore = 0.0f;
    float autoCfaChromaRisk = 0.0f;
    float autoScoreDelta = 0.0f;
    std::string autoRunnerUp = "not_used";
    std::string autoSignals = "not_used";
};


struct AutoDemosaicSceneMetrics {
    bool valid = false;
    int sampleCount = 0;
    float medianSignal = 0.0f;
    float meanGradient = 0.0f;
    float p90Gradient = 0.0f;
    float edgeFraction = 0.0f;
    float coherentEdgeFraction = 0.0f;
    float lowSignalFraction = 0.0f;
};

struct AutoDemosaicContext {
    int captureIso = 0;
    // Physical single-frame Noise Truth. When available, Auto must use this instead of ISO
    // as its noise authority. ISO remains metadata/fallback only for captures without S/O.
    bool physicalNoiseKnown = false;
    float noiseSigmaY = 0.0f;
    float noiseSigmaChroma = 0.0f;
    float physicalNoisePressure = 0.0f;
    bool cfaCertain = true;
    bool greenPlaneAnomaly = false;
    bool rawColorAuditFailed = false;
    bool motionRiskKnown = false;
    bool highMotionRisk = false;
    bool focusStabilityKnown = false;
    float focusStabilityConfidence = 0.0f;
    float focusSharpConfidence = 0.0f;
    float focusMotionRisk = 0.0f;
    float focusVelocityDioptersPerSec = 0.0f;
    float predictiveAfConfidence = 0.0f;
    bool personEvidenceKnown = false;
    float personConfidence = 0.0f;
    int detectedFaceCount = 0;
    float maxFaceCoverage = 0.0f;
    bool temporalStabilityKnown = false;
    float temporalStaticConfidence = 0.0f;
    float temporalObserverConfidence = 0.0f;
    float temporalMotionAcceptance = 0.0f;
    int temporalAcceptedPairs = 0;
    bool cfaChromaEvidenceKnown = false;
    float cfaStructureProtection = 0.0f;
    float cfaFineCorrectionConfidence = 0.0f;
    float cfaMidCorrectionConfidence = 0.0f;
    float cfaLowCorrectionConfidence = 0.0f;
    float cfaRedOpponentCorrectionConfidence = 0.0f;
    float cfaBlueOpponentCorrectionConfidence = 0.0f;
    bool memoryPressureHigh = false;
};

/**
 * Compact immutable SPECTRA CFA evidence handed to reconstruction routes that opt into it.
 * Pure Malvar-He-Cutler 2004 intentionally ignores this contract; adaptive routes may consume
 * it through their bounded algorithm-specific chroma adaptations.
 */
struct DemosaicCfaEvidence {
    bool available = false;
    float commonOpponentSupport = 0.0f;
    float structureProtection = 0.0f;
    float fineCorrectionConfidence = 0.0f;
    float midCorrectionConfidence = 0.0f;
    float lowCorrectionConfidence = 0.0f;
    float redOpponentCorrectionConfidence = 0.0f;
    float blueOpponentCorrectionConfidence = 0.0f;
};

struct DemosaicNoiseContext {
    bool available = false;
    float sigmaY = 0.0f;
    float sigmaChroma = 0.0f;
    float pressure = 0.0f;
};

struct DemosaicRunStats {
    float setupMs = 0.0f;
    float kernelMs = 0.0f;
    float finalizeMs = 0.0f;
    float inputPrepMs = 0.0f;
    float workspaceSetupMs = 0.0f;
    float pureKernelMs = 0.0f;
    float borderHandlingMs = 0.0f;
    float postCopyMs = 0.0f;
    float totalWrapperMs = 0.0f;
    uint64_t workingBufferBytesEstimate = 0;
    uint64_t allocatedScratchBytesThisShot = 0;
    bool allocationReuse = false;
};

struct DemosaicValidationResult {
    bool passed = false;
    bool constantFieldPassed = false;
    bool syntheticChannelsPassed = false;
    bool channelOrderPassed = false;
    bool kernelDcGainPassed = false;
    bool samplePreservationPassed = false;
    bool referenceVectorPassed = false;
    std::string details;
};

DemosaicResolution resolveDemosaicMode(int requestedModeValue);
DemosaicResolution resolveDemosaicForFrame(
        int requestedModeValue,
        const cv::Mat& normalizedBayer,
        const AutoDemosaicContext& context = {}
);
DemosaicResolution resolveDemosaicForSceneMetrics(
        int requestedModeValue,
        const AutoDemosaicSceneMetrics& metrics,
        const AutoDemosaicContext& context = {}
);
const char* demosaicModeName(DemosaicMode mode);
const char* demosaicAlgorithmName(DemosaicAlgorithm algorithm);

// Input is one black-subtracted, scene-linear Bayer plane. Output is CV_32FC3 in RGB order.
// No white balance, color matrix, lens shading, tone mapping, or channel gains are applied here.
cv::Mat demosaicBilinearToRgb32f(
        const cv::Mat& normalizedBayer,
        int effectiveCfaPattern,
        DemosaicRunStats* stats = nullptr
);
// Pure Malvar-He-Cutler 2004. Adaptive evidence/noise arguments are retained only for
// common call-site compatibility and must not influence this route's output.
cv::Mat demosaicMalvar2004ToRgb32f(
        const cv::Mat& normalizedBayer,
        int effectiveCfaPattern,
        DemosaicRunStats* stats = nullptr,
        const DemosaicCfaEvidence* cfaEvidence = nullptr,
        const DemosaicNoiseContext* noiseContext = nullptr
);
// Neural JDD CPU fallback/validation behind the legacy RCD symbol. Production execution is
// Vulkan-primary; the old symbol remains only to preserve existing native call-site ABI.
cv::Mat demosaicRcdInspiredToRgb32f(
        const cv::Mat& normalizedBayer,
        int effectiveCfaPattern,
        DemosaicRunStats* stats = nullptr,
        const DemosaicCfaEvidence* cfaEvidence = nullptr,
        const DemosaicNoiseContext* noiseContext = nullptr
);
// BnCam AMAZE-inspired reference/fallback implementation. It keeps the high-quality
// directional/detail-adaptive character without claiming a canonical AMaZE implementation.
cv::Mat demosaicAmazeInspiredToRgb32f(
        const cv::Mat& normalizedBayer,
        int effectiveCfaPattern,
        DemosaicRunStats* stats = nullptr,
        const DemosaicCfaEvidence* cfaEvidence = nullptr,
        const DemosaicNoiseContext* noiseContext = nullptr
);
cv::Mat demosaicMenon2007ToRgb32f(
        const cv::Mat& normalizedBayer,
        int effectiveCfaPattern,
        DemosaicRunStats* stats = nullptr
);
void recordMenonDemosaicTimeMs(float elapsedMs);

// Deterministic synthetic validation used by the debug/test JNI entry point.
DemosaicValidationResult validateBilinearImplementation();
DemosaicValidationResult validateMalvar2004Implementation();
DemosaicValidationResult validateRcdInspiredImplementation();
DemosaicValidationResult validateAmazeInspiredImplementation();
DemosaicValidationResult validateMenon2007Implementation();
