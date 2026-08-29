#pragma once

namespace bncam::raw_black {

struct SceneBlackAuthorityDecision {
    bool metadataAuthoritative = false;
    bool imageDerivedMutationAllowed = true;
    const char* mode = "IMAGE_DERIVED_FALLBACK_ELIGIBLE";
};

/**
 * P0 black authority.
 *
 * Camera2 dynamic black is first authority; static SENSOR_BLACK_LEVEL_PATTERN is the
 * metadata fallback. Once either has actually been selected by RawDomainContract,
 * a scene-derived dark-tile scan may validate/telemeter the result but must not
 * silently become a second black-level authority.
 */
constexpr SceneBlackAuthorityDecision resolveSceneBlackAuthority(
        bool dynamicBlackLevelUsed,
        bool staticBlackLevelUsed) noexcept {
    const bool metadata = dynamicBlackLevelUsed || staticBlackLevelUsed;
    return {
        metadata,
        !metadata,
        metadata
            ? "METADATA_BLACK_AUTHORITATIVE_VALIDATOR_ONLY"
            : "IMAGE_DERIVED_FALLBACK_ELIGIBLE"
    };
}

} // namespace bncam::raw_black
