#pragma once

namespace bncam::raw_black {

struct SceneBlackAuthorityDecision {
    bool metadataAuthoritative = false;
    bool imageDerivedMutationAllowed = true;
    const char* mode = "IMAGE_DERIVED_FALLBACK_PLUS_RESIDUAL";
};

/**
 * Camera2 dynamic/static black metadata owns the electronic black-level baseline.
 *
 * A scene-derived estimate is never allowed to replace that baseline. It may,
 * however, remove a *residual* CFA pedestal measured after metadata subtraction.
 * Treating metadata as a ban on residual correction left a measured common-green
 * offset in the normalised RAW data and propagated it through WB/CCM/tone.
 */
constexpr SceneBlackAuthorityDecision resolveSceneBlackAuthority(
        bool dynamicBlackLevelUsed,
        bool staticBlackLevelUsed) noexcept {
    const bool metadata = dynamicBlackLevelUsed || staticBlackLevelUsed;
    return {
        metadata,
        true,
        metadata
            ? "METADATA_BASELINE_PLUS_CONFIDENCE_GATED_RESIDUAL"
            : "IMAGE_DERIVED_FALLBACK_PLUS_RESIDUAL"
    };
}

} // namespace bncam::raw_black
