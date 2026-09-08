#pragma once

#include <cstddef>
#include <cstdint>
#include <string>
#include <vector>
#include <algorithm>
#include <cmath>

// Enum matching Kotlin RawDomain
enum class NativeRawDomain {
    RAW10_PACKED_10BIT = 0,
    RAW_SENSOR_16BIT = 1,
    MASTER_RAW16_NORMALIZED = 2,
    UNKNOWN = 3
};

// De absolute 'Final Calibration' die Kotlin voor ons heeft uitgerekend
struct FinalSensorCalibrationNative {
    int effectiveWhiteLevel = 65535;
    float effectiveBlackLevels[4] = {0.0f, 0.0f, 0.0f, 0.0f};
    float blackLevelScaleFactor = 1.0f;

    // Android SENSOR_NOISE_PROFILE flattened as S0,O0,S1,O1... (not legacy A/B/C/D presets).
    // Values arrive as Java/Kotlin Double and stay double in native evaluation.
    double effectiveNoiseProfile[16] = {0.0};
    // Physical sensor noise calibration: 0=Off, 1=Auto/Camera2 exact-frame metadata, 2=Manual per-lens S/O.
    int noiseModelMode = 0;
    // SPECTRA mutation authority is deliberately separate: calibration may remain available
    // while the profile turns SPECTRA pixel processing off. 0=Off, 1=Auto, 2=Manual model.
    int spectraProcessingMode = 0;
    bool hasNoiseProfile = false;
    bool noiseProfileValid = false;
    bool normalizationCalibrationValid = false;
    bool cfaSupportedForBayerNoiseModel = false;
    bool noiseProfileApplied = false;
    std::string noiseProfileNotAppliedReason = "none";
    double manualNoiseAnchorIso = 100.0;
    double manualNoiseGainRatio = 1.0;
    bool manualNoiseSingleAnchorScaled = false;
    int noiseProfilePairCount = 0;
    int noiseProfileChannelCount = 0;

    bool hasBlackLevel = false;
    bool hasWhiteLevel = false;
    bool hasWbGains = false;
    bool calibrationApplied = false;
    std::string calibrationWarnings = "none";

    float effectiveWbGains[4] = {1.0f, 1.0f, 1.0f, 1.0f};

    float effectiveColorMatrix[9] = {1.0f, 0.0f, 0.0f, 0.0f, 1.0f, 0.0f, 0.0f, 0.0f, 1.0f};
    bool hasColorMatrix = false;
    bool colorMatrixFromMetadata = false;

    NativeRawDomain rawInputDomain = NativeRawDomain::UNKNOWN;
    NativeRawDomain ispWorkingDomain = NativeRawDomain::MASTER_RAW16_NORMALIZED;

    // SPECTRA Noise Engine snapshot fields. These two fields are part of the
    // canonical JNI -> native snapshot contract and must stay capture-local.
    bool spectraSnapshotPresent = false;
    std::string spectraLensKey = "unknown";
    int spectraMode = 0;
    float signalModelConfidence = 1.0f;
    double cameraS[4] = {0.0};
    double cameraO[4] = {0.0};
    double effectiveS[4] = {0.0};
    double effectiveO[4] = {0.0};
    int postRawSensitivityBoost = 0;
    std::string lensId = "unknown";
};


namespace bncam::profile_defaults {
inline constexpr float kDetailAmount = 0.00f;
inline constexpr float kDetailRadius = 0.00f;
inline constexpr float kDetailDetail = 0.00f;
inline constexpr float kDetailMasking = 0.00f;
inline constexpr float kDetailMinRadius = 0.50f;
inline constexpr float kDetailMaxRadius = 3.00f;
}

struct NativeRenderQualityConfig {
    int jpegQuality = 98;
    // Global output intent. Gainmap pixel formation must remain Vulkan/GPU-only.
    bool ultraHdrGainmapEnabled = false;

    // Portrait semantic confidence mask. The pointer references capture-owned direct Float32
    // storage for the duration of one native render. Thresholding/refinement/bokeh stay Vulkan.
    bool portraitEffectEnabled = false;
    const float* portraitMask = nullptr;
    std::size_t portraitMaskFloatCount = 0u;
    std::uint32_t portraitMaskWidth = 0u;
    std::uint32_t portraitMaskHeight = 0u;
    float portraitTargetLeft = 0.0f;
    float portraitTargetTop = 0.0f;
    float portraitTargetRight = 0.0f;
    float portraitTargetBottom = 0.0f;
    std::uint32_t portraitMaskRotationDegrees = 0u;

    // Dynamic exposure and BLC normalization model
    float exposureGain = 1.0f;
    float dynamicBlackLevels[4] = {0.0f, 0.0f, 0.0f, 0.0f};
    float dynamicWhiteLevel = 65535.0f;

    // Legacy tone-shape ABI fields. Runtime-neutral: GTM/LTM + profile tone own tone shape.
    std::vector<float> toneCurve;
    std::vector<float> gammaCurve;
    std::vector<float> sectionCurve;

    // White balance
    float wbRed = 1.0f;
    float wbGreenEven = 1.0f;
    float wbGreenOdd = 1.0f;
    float wbBlue = 1.0f;
    bool wbFromMetadata = false;

    // Color matrix
    float colorMatrix[9] = {1.0f, 0.0f, 0.0f, 0.0f, 1.0f, 0.0f, 0.0f, 0.0f, 1.0f};
    bool colorMatrixFromMetadata = false;

    // Lens-detail ISO NR policy. This is not a legacy A/B/C/D noise-model preset.
    // Mode: 0=Default/no user ISO NR, 1=dynamic coefficient x capture ISO, 2=manual ISO reference.
    int lensIsoNrMode = 0;
    float lensDynamicIsoCoeff = 0.0f;
    float lensManualIsoValue = 0.0f;
    int captureSensitivityIso = 0;
    // FASE 13 compatibility telemetry only. ISO-derived authority is retired for YUV: the
    // vendor-processed frame is denoised from measured residual evidence on Vulkan instead.
    float yuvLensIsoNoiseReductionBoost = 0.0f;
    bool yuvLensIsoNrApplied = false;

    // Advanced sensor noise calibration parameters
    float noiseModelCalibrationAdjustment = 0.0f;
    float dynamicChromaAuthorityAdjustment = 0.0f;
    float dynamicLumaAuthorityAdjustment = 0.0f;
    float noiseModelCalibrationFactor = 1.0f;
    float effectiveChromaAuthorityStops = 4.0f;
    float effectiveLumaAuthorityStops = 2.25f;
    float chromaUserScale = 1.0f;
    float lumaUserScale = 1.0f;
    float outerRingAuthority = 0.0f;

    // Phase 6 neural RAW denoise control transport. Master and Adaptive Response are direct
    // unit authorities. Existing Luma/Chroma/Detail/LF profile storage remains signed and is
    // projected to unit controls by SpectraNeuralProductionPolicy before Student inference.
    float profileSpectraStrength = 0.70f;
    float profileNeuralAdaptiveResponse = 0.45f;
    float profileSpectraLuma = 0.0f;
    float profileSpectraChroma = 0.0f;
    float profileSpectraDetailProtection = 0.0f;
    float profileSpectraLowFrequency = 0.0f;

    // Legacy Profile-NR ABI slots. They no longer own RAW or YUV pixels. The first RAW JNI
    // carrier is repurposed narrowly as profileNeuralAdaptiveResponse in makeQualityConfig;
    // every YUV caller sends the complete legacy tuple at neutral values.
    float profileNrLuminance = 0.0f;
    float profileNrLuminanceDetail = 0.5f;
    float profileNrLuminanceContrast = 0.0f;
    float profileNrColor = 0.0f;
    float profileNrColorDetail = 0.5f;
    float profileNrColorSmoothness = 0.5f;

    // Profile-owned render tone. These controls are ISP-only and never Camera2 authority.
    // Normalized range is [-1, 1]; Exposure maps to +/-2 EV in ProfileToneRenderPolicy.
    float profileToneExposure = 0.0f;
    float profileToneHighlights = 0.0f;
    float profileToneShadows = 0.0f;
    float profileToneWhites = 0.0f;
    float profileToneBlacks = 0.0f;
    float profileToneContrast = 0.0f;
    float profileLocalToneBias = 0.0f;

    // Lightroom-style Presence plus a temporary live RGB contrast offset. 0.00 is neutral.
    float profileColorSaturation = 0.0f;
    float profileColorContrast = 0.0f;
    float profilePresenceVibrance = 0.0f;
    float profilePresencePop = 0.0f;
    float profileColorRecovery = 0.0f;

    // Lightroom-style Detail > Sharpening is the only profile sharpening authority.
    float profileDetailAmount = bncam::profile_defaults::kDetailAmount;
    float profileDetailRadius = bncam::profile_defaults::kDetailRadius;
    float profileDetailDetail = bncam::profile_defaults::kDetailDetail;
    float profileDetailMasking = bncam::profile_defaults::kDetailMasking;

    // Post-HAL YUV white-balance compensation. RAW uses effectiveWbGains before
    // demosaic; these ratios make the same profile AWB setting effective for YUV.
    float profileYuvWbRed = 1.0f;
    float profileYuvWbGreen = 1.0f;
    float profileYuvWbBlue = 1.0f;
};

// Shared utility – inline definition so both native-lib.cpp and IspCore.cpp can use it.
inline float smoothstepIsp(float edge0, float edge1, float value) {
    const float u = std::clamp(
            (value - edge0) / std::max(edge1 - edge0, 1.0e-6f),
            0.0f,
            1.0f
    );
    return u * u * (3.0f - 2.0f * u);
}
