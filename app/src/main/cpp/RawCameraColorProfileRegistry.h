#pragma once

#include <array>
#include <cstdint>
#include <mutex>
#include <string>
#include <vector>

namespace bncam::color {

struct RawCameraNativeHueSatProfile {
    std::string profileId;
    int sourcePriority = 0;
    int calibrationIlluminant1 = 0;
    int calibrationIlluminant2 = 0;
    std::array<float, 9> colorMatrix1{};
    std::array<float, 9> colorMatrix2{};
    std::array<float, 9> cameraCalibration1{};
    std::array<float, 9> cameraCalibration2{};
    std::array<float, 9> forwardMatrix1{};
    std::array<float, 9> forwardMatrix2{};
    std::array<float, 3> analogBalance{1.0f, 1.0f, 1.0f};
    std::array<float, 9> discoveryEffectiveCcm{};
    bool hasColorMatrix2 = false;
    bool hasCameraCalibration1 = false;
    bool hasCameraCalibration2 = false;
    bool hasForwardMatrix1 = false;
    bool hasForwardMatrix2 = false;
    int hueDivisions = 0;
    int saturationDivisions = 0;
    int valueDivisions = 0;
    int encoding = 0;
    std::vector<float> hueSatData1;
    std::vector<float> hueSatData2;
    std::uint64_t installGeneration = 0;

    bool valid() const noexcept;
    bool dualIlluminant() const noexcept { return !hueSatData2.empty(); }
};

struct RawCameraProfileRegistrySnapshot {
    std::vector<RawCameraNativeHueSatProfile> profiles;
    std::uint64_t generation = 0;
};

class RawCameraColorProfileRegistry final {
public:
    static RawCameraColorProfileRegistry& instance() noexcept;
    bool install(RawCameraNativeHueSatProfile profile) noexcept;
    RawCameraProfileRegistrySnapshot snapshot() const;
    void clear() noexcept;

private:
    mutable std::mutex mutex_;
    std::vector<RawCameraNativeHueSatProfile> profiles_;
    std::uint64_t generation_ = 0;
};

} // namespace bncam::color
