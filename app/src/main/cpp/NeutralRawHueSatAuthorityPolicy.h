#pragma once

namespace bncam::color {

// A matched, physically validated DNG ForwardMatrix may participate in neutral RAW
// instead of being universally bypassed. With a true HueSatMap, the paired
// ForwardMatrix MUST have full authority (not blended with a Camera2 CCM).
// No profile/no forward matrix => preserve the exact Camera2 per-frame pair.
struct NeutralRawColorAuthorityInput {
    bool neutralSingleFrameRaw = false;
    bool exactCamera2PairPreserved = false;
    bool matchedProfile = false;
    bool profileResolved = false;
    bool profileValid = false;
    bool pairedForwardTransformReady = false;
    bool genuineHueSatMapAvailable = false;
};

inline bool useCalibratedDngMatrixForNeutralRaw(
        const NeutralRawColorAuthorityInput& in) noexcept {
    return in.neutralSingleFrameRaw && in.exactCamera2PairPreserved &&
           in.matchedProfile && in.profileResolved && in.profileValid &&
           in.pairedForwardTransformReady;
}

inline bool usePairedDngHueSatForNeutralRaw(
        const NeutralRawColorAuthorityInput& in) noexcept {
    return useCalibratedDngMatrixForNeutralRaw(in) && in.genuineHueSatMapAvailable;
}

} // namespace bncam::color
