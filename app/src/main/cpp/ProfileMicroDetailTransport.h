#pragma once

#include <algorithm>
#include <cmath>

namespace bncam::profile_microdetail_transport {

// Phase 3 compatibility transport.
//
// Retired RAW publication/preview consumers still interpret profileDetailDetail as the old
// unsigned Lightroom Detail value and clamp it to [0, 1]. Every encoded signed value therefore
// stays <= 0 so those consumers resolve to their existing neutral value (0), while the production
// Detail owner can recover the complete user range [-1, +1]. Exact transport 0 is reserved for
// user-neutral 0, which also makes default-constructed/native legacy-neutral callers safe.
//
// Negative user values: [-1, 0) -> [-1, -0.5)
// Neutral user value:     0    ->  0
// Positive user values: (0, +1] -> (-0.5, 0), with +1 -> -0.5
inline float sanitizeUser(float value) noexcept {
    return std::clamp(std::isfinite(value) ? value : 0.0f, -1.0f, 1.0f);
}

inline float encode(float userValue) noexcept {
    const float user = sanitizeUser(userValue);
    if (std::abs(user) <= 1.0e-7f) return 0.0f;
    return user < 0.0f
            ? (-0.5f + 0.5f * user)
            : (-0.5f * user);
}

inline float decode(float transportValue) noexcept {
    if (!std::isfinite(transportValue)) return 0.0f;
    // Positive values belong to the retired unsigned contract, never to Phase 3 transport.
    if (transportValue > 0.0f) return 0.0f;
    const float transport = std::clamp(transportValue, -1.0f, 0.0f);
    if (std::abs(transport) <= 1.0e-7f) return 0.0f;
    return transport < -0.5f
            ? std::clamp(2.0f * (transport + 0.5f), -1.0f, 0.0f)
            : std::clamp(-2.0f * transport, 0.0f, 1.0f);
}

} // namespace bncam::profile_microdetail_transport
