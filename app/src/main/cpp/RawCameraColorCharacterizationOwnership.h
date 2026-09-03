#pragma once

#include <cstdint>
#include <string>

namespace bncam::color {

enum class RawCameraColorCharacterizationOwner : std::uint8_t {
    NONE = 0,
    CALIBRATED_DNG_PROFILE = 1,
    LEGACY_HUE_PRESERVING_PRESENTATION = 2
};

struct RawCameraColorCharacterizationInput {
    bool calibratedProfileConfigured = false;
    bool calibratedProfileResolved = false;
    bool calibratedProfileProductEligible = false;
    bool forwardTransformReady = false;
    bool hueSatMapAvailable = false;
    bool hueSatMapProductEligible = false;
    bool legacyPresentationAvailable = false;
};

struct RawCameraColorCharacterizationPlan {
    RawCameraColorCharacterizationOwner owner = RawCameraColorCharacterizationOwner::NONE;
    bool calibratedMatrixApply = false;
    bool calibratedHueSatMapApply = false;
    bool legacyPresentationApply = false;
    bool failClosed = false;
    std::string reason = "NO_COLOR_CHARACTERIZATION";
};

/**
 * Single-owner contract for camera-specific colour characterization.
 *
 * A DNG ForwardMatrix characterization is the physical camera-colour owner and is valid without a
 * ProfileHueSatMap. A genuine HueSatMap, when present, augments that same owner. The legacy OKLab
 * renderer is presentation fallback only and must never stack with either calibrated route.
 */
inline RawCameraColorCharacterizationPlan resolveRawCameraColorCharacterizationOwnership(
        const RawCameraColorCharacterizationInput& in) noexcept {
    RawCameraColorCharacterizationPlan out{};

    if (in.calibratedProfileConfigured) {
        if (!in.calibratedProfileResolved) {
            out.failClosed = true;
            out.reason = "CALIBRATED_PROFILE_CONFIGURED_BUT_UNRESOLVED";
            return out;
        }
        if (!in.calibratedProfileProductEligible) {
            out.failClosed = true;
            out.reason = "CALIBRATED_PROFILE_NOT_PRODUCT_ELIGIBLE";
            return out;
        }
        if (!in.forwardTransformReady) {
            out.failClosed = true;
            out.reason = "CALIBRATED_DNG_FORWARD_TRANSFORM_NOT_READY";
            return out;
        }
        if (in.hueSatMapAvailable && !in.hueSatMapProductEligible) {
            out.failClosed = true;
            out.reason = "CALIBRATED_HUESATMAP_PRESENT_BUT_NOT_PRODUCT_ELIGIBLE";
            return out;
        }
        out.owner = RawCameraColorCharacterizationOwner::CALIBRATED_DNG_PROFILE;
        out.calibratedMatrixApply = true;
        out.calibratedHueSatMapApply = in.hueSatMapAvailable;
        out.reason = in.hueSatMapAvailable
                ? "CALIBRATED_DNG_PROFILE_WITH_HUESATMAP_SINGLE_OWNER"
                : "CALIBRATED_DNG_MATRIX_PROFILE_NO_HUESATMAP_SINGLE_OWNER";
        return out;
    }

    if (in.legacyPresentationAvailable) {
        out.owner = RawCameraColorCharacterizationOwner::LEGACY_HUE_PRESERVING_PRESENTATION;
        out.legacyPresentationApply = true;
        out.reason = "NO_CALIBRATED_PROFILE_LEGACY_PRESENTATION_FALLBACK";
        return out;
    }

    out.reason = "NO_CALIBRATED_PROFILE_NO_PRESENTATION_FALLBACK";
    return out;
}

} // namespace bncam::color
