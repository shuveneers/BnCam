#pragma once

#include <cstddef>
#include <cstdint>
#include <string>
#include <vector>
#include <algorithm>
#include <array>
#include <cmath>

// Enum matching Kotlin RawDomain
enum class NativeRawDomain {
    RAW10_PACKED_10BIT = 0,
    RAW_SENSOR_16BIT = 1,
    MASTER_RAW16_NORMALIZED = 2,
    UNKNOWN = 3
};

// Capture-local calibration transport. Physical sensor-noise source identity lives in the
// Kotlin PhysicalNoiseState snapshot; native only receives frozen physical S/O plus availability.
struct FrozenPhysicalNoiseModelNative {
    std::array<float, 4> shotS{{0.0f, 0.0f, 0.0f, 0.0f}};
    std::array<float, 4> readO{{0.0f, 0.0f, 0.0f, 0.0f}};
    bool available = false;
};

struct FinalSensorCalibrationNative {
    int effectiveWhiteLevel = 65535;
    float effectiveBlackLevels[4] = {0.0f, 0.0f, 0.0f, 0.0f};
    float blackLevelScaleFactor = 1.0f;

    // Compatibility JNI ingress for physical shutter S/O flattened as
    // S0,O0,S1,O1... in canonical R,Gr,Gb,B order. Native consumers must not treat this
    // array as an independent authority: native-lib validates it once and copies it into
    // effectiveS/effectiveO below, which are the sole native physical S/O truth.
    double effectiveNoiseProfile[16] = {0.0};

    // Physical model availability is derived from the validated frozen S/O payload below.
    // OEM/System/Manual/Preset source identity remains Kotlin snapshot metadata and is never
    // re-encoded as a native mode integer.

    // SPECTRA is an optional consumer. 0=off, non-zero=requested/eligible; it is not a physical
    // model source selector. The exact enable decision additionally requires valid physical S/O.
    int spectraProcessingMode = 0;
    bool hasNoiseProfile = false;
    bool noiseProfileValid = false;
    bool noiseProfileApplied = false;
    std::string noiseProfileNotAppliedReason = "none";

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

    // Optional SPECTRA-consumer metadata. cameraS/cameraO are OEM evidence/telemetry only.
    // effectiveS/effectiveO are populated exclusively from the validated PhysicalNoiseState
    // JNI payload and are the sole native physical shutter-noise authority.
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

    bool physicalNoiseModelAvailable() const noexcept {
        if (!(hasNoiseProfile && noiseProfileApplied && noiseProfileValid &&
              noiseProfilePairCount == 4 && noiseProfileChannelCount == 4)) {
            return false;
        }
        bool hasEnergy = false;
        for (int ch = 0; ch < 4; ++ch) {
            if (!std::isfinite(effectiveS[ch]) || !std::isfinite(effectiveO[ch]) ||
                effectiveS[ch] < 0.0 || effectiveO[ch] < 0.0) {
                return false;
            }
            hasEnergy = hasEnergy || effectiveS[ch] > 0.0 || effectiveO[ch] > 0.0;
        }
        return hasEnergy;
    }

    // Phase 3: the only native export into Neural conditioning. This is deliberately all-or-none:
    // no Camera2/OEM fallback, no ISO synthesis, no clamping and no partial-channel recovery.
    // The float cast is the sole representation change between frozen JNI physical S/O and the
    // Vulkan/Neural conditioning contract. Invalid physical state exports a zero unavailable model.
    FrozenPhysicalNoiseModelNative frozenPhysicalNoiseModel() const noexcept {
        FrozenPhysicalNoiseModelNative out{};
        if (!physicalNoiseModelAvailable()) return out;
        for (std::size_t ch = 0; ch < out.shotS.size(); ++ch) {
            out.shotS[ch] = static_cast<float>(effectiveS[ch]);
            out.readO[ch] = static_cast<float>(effectiveO[ch]);
        }
        out.available = true;
        return out;
    }
};

inline int resolveSpectraProcessingMode(
        bool spectraRequested,
        const FinalSensorCalibrationNative& calibration) noexcept {
    return spectraRequested && calibration.physicalNoiseModelAvailable() ? 1 : 0;
}

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

    // Legacy tone-shape ABI fields. Runtime-neutral: Log-LLF-Khronos + profile tone own tone shape.
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

    // Capture ISO is contextual telemetry only. Physical Dynamic ISO has already been
    // resolved into the immutable shutter S/O snapshot before native processing begins.
    int captureSensitivityIso = 0;

    // Neural SPECTRA control transport.
    float profileSpectraStrength = 0.70f;
    float profileNeuralAdaptiveResponse = 0.45f;
    float profileSpectraLuma = 0.0f;
    float profileSpectraChroma = 0.0f;
    float profileSpectraDetailProtection = 0.0f;
    float profileSpectraLowFrequency = 0.0f;

    // Legacy Profile-NR ABI slots. They no longer own RAW or YUV pixels.
    float profileNrLuminance = 0.0f;
    float profileNrLuminanceDetail = 0.5f;
    float profileNrLuminanceContrast = 0.0f;
    float profileNrColor = 0.0f;
    float profileNrColorDetail = 0.5f;
    float profileNrColorSmoothness = 0.5f;

    // Profile-owned render tone. These controls are ISP-only and never Camera2 authority.
    float profileToneExposure = 0.0f;
    float profileToneHighlights = 0.0f;
    float profileToneShadows = 0.0f;
    float profileToneWhites = 0.0f;
    float profileToneBlacks = 0.0f;
    float profileToneContrast = 0.0f;
    float profileLocalToneBias = 0.0f;

    // Presence / color controls.
    float profileColorSaturation = 0.0f;
    float profileColorContrast = 0.0f;
    float profilePresenceVibrance = 0.0f;
    float profilePresencePop = 0.0f;
    float profileColorRecovery = 0.0f;

    // Profile sharpening authority.
    float profileDetailAmount = bncam::profile_defaults::kDetailAmount;
    float profileDetailRadius = bncam::profile_defaults::kDetailRadius;
    float profileDetailDetail = bncam::profile_defaults::kDetailDetail;
    float profileDetailMasking = bncam::profile_defaults::kDetailMasking;

    // Post-HAL YUV white-balance compensation. RAW uses effectiveWbGains before demosaic.
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
