#pragma once

namespace bncam::raw_black {

struct SceneBlackAuthorityDecision {
    bool metadataAuthoritative = false;
    bool imageDerivedMutationAllowed = false;
    const char* mode = "IMAGE_DERIVED_OBSERVER_ONLY";
};

/**
 * Black Level v2 / RawDomainInfo owns the developed RAW zero point before SPECTRA.
 *
 * The current pre-neural scene-black stage is diagnostic only: it may measure residual CFA/green
 * pedestal evidence, but it must not mutate pixels or replace the resolved System/Dynamic/Manual
 * baseline. This also guarantees that Manual remains an exact user override rather than being
 * silently shifted by a later image-derived pedestal correction.
 */
constexpr SceneBlackAuthorityDecision resolveSceneBlackAuthority(
        bool dynamicBlackLevelUsed,
        bool staticBlackLevelUsed) noexcept {
    const bool metadata = dynamicBlackLevelUsed || staticBlackLevelUsed;
    return {
        metadata,
        false,
        metadata ? "METADATA_BASELINE_OBSERVER_ONLY" : "IMAGE_DERIVED_OBSERVER_ONLY"
    };
}

} // namespace bncam::raw_black
