#pragma once

#include <cstdint>
#include <string>
#include <vector>

namespace bncam::ultrahdr {

struct GainmapPayload {
    std::uint32_t width = 0;
    std::uint32_t height = 0;
    std::uint32_t rowStrideBytes = 0;
    float minContentBoost = 1.0f;
    float maxContentBoost = 1.0f;
    float gamma = 1.0f;
    float offsetSdr = 1.0f / 64.0f;
    float offsetHdr = 1.0f / 64.0f;
    std::vector<std::uint8_t> pixels;
};

struct PackageResult {
    bool success = false;
    bool iccInjected = false;
    std::vector<std::uint8_t> jpeg;
    std::string failureReason;
};

PackageResult packageUltraHdrJpeg(
        const std::vector<std::uint8_t>& baseJpeg,
        const GainmapPayload& gainmap,
        int gainmapJpegQuality = 90);

bool validateUltraHdrJpeg(const std::vector<std::uint8_t>& jpeg);

}  // namespace bncam::ultrahdr
