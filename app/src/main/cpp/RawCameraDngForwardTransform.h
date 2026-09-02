#pragma once

#include "RawCameraColorProfileRegistry.h"

#include <array>
#include <string>

namespace bncam::color {

struct RawCameraDngForwardTransformRequest {
    const RawCameraNativeHueSatProfile* profile = nullptr;
    float weightFirst = 1.0f;
    float weightSecond = 0.0f;
    std::array<float, 3> currentWbRgb{1.0f, 1.0f, 1.0f};
};

struct RawCameraDngForwardTransformResult {
    bool ready = false;
    std::array<float, 9> cameraToXyzD50{};
    // Matrix consumed after BnCam's existing diagonal AWB multiplication.
    std::array<float, 9> postWbToLinearSrgb{};
    float neutralD50Error = 0.0f;
    std::string profileId;
    std::string status = "NO_PROFILE";
};

RawCameraDngForwardTransformResult resolveRawCameraDngForwardTransform(
        const RawCameraDngForwardTransformRequest& request) noexcept;

} // namespace bncam::color
