#pragma once

#include "RawCameraColorProfileRegistry.h"

#include <array>
#include <cstddef>
#include <limits>
#include <string>

namespace bncam::color {

struct RawCameraColorProfileResolveRequest {
    std::array<float, 9> currentEffectiveCcm{};
    std::array<float, 3> currentWbRgb{1.0f, 1.0f, 1.0f};
};

struct RawCameraColorProfileResolution {
    bool ready = false;
    std::size_t profileIndex = std::numeric_limits<std::size_t>::max();
    std::string profileId;
    int sourcePriority = 0;
    float matrixShapeDistance = std::numeric_limits<float>::infinity();
    float secondBestMatrixShapeDistance = std::numeric_limits<float>::infinity();
    float sceneCctKelvin = 0.0f;
    float wbFitLogRmse = std::numeric_limits<float>::infinity();
    float hueSatWeightFirst = 1.0f;
    float hueSatWeightSecond = 0.0f;
    std::string status = "NO_PROFILE";
};

/**
 * Resolve one already-validated camera profile for the current RAW colour route.
 *
 * Safety rules:
 * - profile identity is inferred from the actual effective CCM shape, never from recency alone;
 * - an ambiguous or materially different matrix fails closed;
 * - a dual-illuminant HSM requires a physically consistent WB fit to the paired DNG
 *   ColorMatrix/CameraCalibration characterization;
 * - HSM weights are linear in reciprocal temperature, matching the DNG profile model.
 */
RawCameraColorProfileResolution resolveRawCameraColorProfile(
        const RawCameraProfileRegistrySnapshot& registry,
        const RawCameraColorProfileResolveRequest& request) noexcept;

/** Test/telemetry helper. Predicts normalized [R,G,B] WB gains at a candidate temperature. */
bool rawCameraPredictProfileWbRgb(
        const RawCameraNativeHueSatProfile& profile,
        float sceneCctKelvin,
        std::array<float, 3>& wbRgbOut) noexcept;

/** Scale-insensitive matrix-shape distance used only for profile identity gating. */
float rawCameraColorMatrixShapeDistance(
        const std::array<float, 9>& a,
        const std::array<float, 9>& b) noexcept;

} // namespace bncam::color
