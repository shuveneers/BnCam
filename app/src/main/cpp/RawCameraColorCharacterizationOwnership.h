#pragma once

#include "RawCameraHueSatMap.h"
#include "RawCameraHueSatMapColorDomain.h"

#include <cstdint>
#include <string>

namespace bncam::color {

enum class RawCameraColorCharacterizationOwner : std::uint8_t {
    NONE = 0,
    CALIBRATED_HUESATMAP = 1,
    LEGACY_HUE_PRESERVING_PRESENTATION = 2
};

struct RawCameraColorCharacterizationInput {
    bool calibratedProfileConfigured = false;
    bool hueSatMapResolved = false;
    bool hueSatMapProductEligible = false;
    bool domainPlanReady = false;
    bool legacyPresentationAvailable = false;
};

struct RawCameraColorCharacterizationPlan {
    RawCameraColorCharacterizationOwner owner = RawCameraColorCharacterizationOwner::NONE;
    bool calibratedHueSatMapApply = false;
    bool legacyPresentationApply = false;
    bool failClosed = false;
    std::string reason = "NO_COLOR_CHARACTERIZATION";
};

/**
 * Single-owner contract for camera-specific non-linear color characterization.
 * A calibrated HueSatMap is physical/profile characterization. The older hue-preserving OKLab
 * chroma transport is presentation fallback only and must never stack with it.
 */
inline RawCameraColorCharacterizationPlan resolveRawCameraColorCharacterizationOwnership(
        const RawCameraColorCharacterizationInput& in) noexcept {
    RawCameraColorCharacterizationPlan out{};

    if (in.calibratedProfileConfigured) {
        if (!in.hueSatMapResolved) {
            out.failClosed = true;
            out.reason = "CALIBRATED_PROFILE_CONFIGURED_BUT_HUESATMAP_UNRESOLVED";
            return out;
        }
        if (!in.hueSatMapProductEligible) {
            out.failClosed = true;
            out.reason = "CALIBRATED_HUESATMAP_NOT_PRODUCT_ELIGIBLE";
            return out;
        }
        if (!in.domainPlanReady) {
            out.failClosed = true;
            out.reason = "CALIBRATED_HUESATMAP_PCS_PAIRING_NOT_READY";
            return out;
        }
        out.owner = RawCameraColorCharacterizationOwner::CALIBRATED_HUESATMAP;
        out.calibratedHueSatMapApply = true;
        out.reason = "CALIBRATED_HUESATMAP_SINGLE_OWNER";
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
