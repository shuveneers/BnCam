#pragma once

#include <array>

namespace bncam::raw {

// Android CameraMetadata SENSOR_INFO_COLOR_FILTER_ARRANGEMENT values.
// Values 0..3 are the only single-sample Bayer mosaics supported by the
// classical Malvar/AMaZE reconstruction frontend.
constexpr int CFA_RGGB = 0;
constexpr int CFA_GRBG = 1;
constexpr int CFA_GBRG = 2;
constexpr int CFA_BGGR = 3;
constexpr int CFA_RGB = 4;
constexpr int CFA_MONO = 5;
constexpr int CFA_NIR = 6;
constexpr int CFA_UNSUPPORTED = -1;

enum class RawCfaContractKind {
    STANDARD_BAYER = 0,
    RGB_INTERLEAVED = 1,
    MONOCHROME = 2,
    NEAR_INFRARED = 3,
    UNSUPPORTED_OR_UNKNOWN = 4
};

struct RawCfaContract {
    int sensorArrangement = CFA_UNSUPPORTED;
    int effectiveBayerPattern = CFA_UNSUPPORTED;
    RawCfaContractKind kind = RawCfaContractKind::UNSUPPORTED_OR_UNKNOWN;
    bool standardBayerMosaic = false;
    bool classicalBayerReconstructionAllowed = false;
    const char* reason = "unresolved";
};

inline bool isStandardBayerArrangement(int arrangement) noexcept {
    return arrangement >= CFA_RGGB && arrangement <= CFA_BGGR;
}

inline const char* cfaArrangementName(int arrangement) noexcept {
    switch (arrangement) {
        case CFA_RGGB: return "RGGB";
        case CFA_GRBG: return "GRBG";
        case CFA_GBRG: return "GBRG";
        case CFA_BGGR: return "BGGR";
        case CFA_RGB: return "RGB";
        case CFA_MONO: return "MONO";
        case CFA_NIR: return "NIR";
        default: return "UNKNOWN";
    }
}

inline const char* cfaContractKindName(RawCfaContractKind kind) noexcept {
    switch (kind) {
        case RawCfaContractKind::STANDARD_BAYER: return "STANDARD_BAYER";
        case RawCfaContractKind::RGB_INTERLEAVED: return "RGB_INTERLEAVED";
        case RawCfaContractKind::MONOCHROME: return "MONOCHROME";
        case RawCfaContractKind::NEAR_INFRARED: return "NEAR_INFRARED";
        case RawCfaContractKind::UNSUPPORTED_OR_UNKNOWN: return "UNSUPPORTED_OR_UNKNOWN";
        default: return "UNSUPPORTED_OR_UNKNOWN";
    }
}

inline int bayerColorAt(int pattern, int x, int y) noexcept {
    // Color ids: 0=R, 1=G, 2=B. Gr/Gb identity is recovered from the
    // resolved Bayer phase by the canonical CFA mapping used downstream.
    const int px = x & 1;
    const int py = y & 1;
    switch (pattern) {
        case CFA_RGGB:
            return py == 0 ? (px == 0 ? 0 : 1) : (px == 0 ? 1 : 2);
        case CFA_GRBG:
            return py == 0 ? (px == 0 ? 1 : 0) : (px == 0 ? 2 : 1);
        case CFA_GBRG:
            return py == 0 ? (px == 0 ? 1 : 2) : (px == 0 ? 0 : 1);
        case CFA_BGGR:
            return py == 0 ? (px == 0 ? 2 : 1) : (px == 0 ? 1 : 0);
        default:
            return -1;
    }
}

inline int effectiveBayerPatternAtOrigin(
        int sensorArrangement,
        int cfaOffsetX,
        int cfaOffsetY
) noexcept {
    if (!isStandardBayerArrangement(sensorArrangement)) {
        return CFA_UNSUPPORTED;
    }

    const std::array<int, 4> colors{{
            bayerColorAt(sensorArrangement, cfaOffsetX, cfaOffsetY),
            bayerColorAt(sensorArrangement, cfaOffsetX + 1, cfaOffsetY),
            bayerColorAt(sensorArrangement, cfaOffsetX, cfaOffsetY + 1),
            bayerColorAt(sensorArrangement, cfaOffsetX + 1, cfaOffsetY + 1)
    }};

    static constexpr std::array<std::array<int, 4>, 4> patterns{{
            {{0, 1, 1, 2}}, // RGGB
            {{1, 0, 2, 1}}, // GRBG
            {{1, 2, 0, 1}}, // GBRG
            {{2, 1, 1, 0}}  // BGGR
    }};

    for (int pattern = CFA_RGGB; pattern <= CFA_BGGR; ++pattern) {
        if (patterns[static_cast<std::size_t>(pattern)] == colors) {
            return pattern;
        }
    }
    return CFA_UNSUPPORTED;
}

inline RawCfaContract resolveCfaContract(
        int sensorArrangement,
        int cfaOffsetX,
        int cfaOffsetY
) noexcept {
    RawCfaContract out{};
    out.sensorArrangement = sensorArrangement;

    if (isStandardBayerArrangement(sensorArrangement)) {
        out.kind = RawCfaContractKind::STANDARD_BAYER;
        out.standardBayerMosaic = true;
        out.effectiveBayerPattern = effectiveBayerPatternAtOrigin(
                sensorArrangement, cfaOffsetX, cfaOffsetY);
        out.classicalBayerReconstructionAllowed =
                isStandardBayerArrangement(out.effectiveBayerPattern);
        out.reason = out.classicalBayerReconstructionAllowed
                ? "standard_bayer_metadata_and_phase_resolved"
                : "standard_bayer_metadata_but_phase_resolution_failed";
        return out;
    }

    out.effectiveBayerPattern = CFA_UNSUPPORTED;
    out.standardBayerMosaic = false;
    out.classicalBayerReconstructionAllowed = false;
    switch (sensorArrangement) {
        case CFA_RGB:
            out.kind = RawCfaContractKind::RGB_INTERLEAVED;
            out.reason = "android_rgb_is_not_a_single_sample_bayer_mosaic";
            break;
        case CFA_MONO:
            out.kind = RawCfaContractKind::MONOCHROME;
            out.reason = "monochrome_stream_requires_non_bayer_reconstruction";
            break;
        case CFA_NIR:
            out.kind = RawCfaContractKind::NEAR_INFRARED;
            out.reason = "nir_stream_requires_non_bayer_reconstruction";
            break;
        default:
            out.kind = RawCfaContractKind::UNSUPPORTED_OR_UNKNOWN;
            out.reason = "unknown_or_unrepresented_cfa_geometry";
            break;
    }
    return out;
}

} // namespace bncam::raw
