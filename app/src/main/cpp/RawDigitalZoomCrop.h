#pragma once

#include <algorithm>
#include <array>

namespace bncam::raw {

struct RawDigitalZoomCrop {
    bool applied = false;
    int localLeft = 0;
    int localTop = 0;
    int width = 0;
    int height = 0;
};

// Camera2 SCALER_CROP_REGION is expressed in sensor/active-array coordinates while the RAW
// working view may already have had the invalid sensor border removed. Resolve the requested
// crop against that current sensor-space view instead of treating crop coordinates as local
// pixel indices. The returned view is read-only: the Master RAW16/DNG owner is never modified.
inline RawDigitalZoomCrop resolveRawDigitalZoomCrop(
        int workingOriginX,
        int workingOriginY,
        int workingWidth,
        int workingHeight,
        const std::array<int, 4>& requestedCrop) noexcept {
    RawDigitalZoomCrop out{};
    if (workingWidth < 4 || workingHeight < 4) return out;

    const int requestedLeft = requestedCrop[0];
    const int requestedTop = requestedCrop[1];
    const int requestedRight = requestedCrop[2];
    const int requestedBottom = requestedCrop[3];
    if (requestedRight <= requestedLeft || requestedBottom <= requestedTop) return out;

    const int workingRight = workingOriginX + workingWidth;
    const int workingBottom = workingOriginY + workingHeight;
    int left = std::max(workingOriginX, requestedLeft);
    int top = std::max(workingOriginY, requestedTop);
    int right = std::min(workingRight, requestedRight);
    int bottom = std::min(workingBottom, requestedBottom);

    if (right - left < 4 || bottom - top < 4) return out;

    // A crop covering the complete current RAW working view is metadata-only and should not
    // produce another ROI/submatrix. This also keeps 1x behavior byte-for-byte unchanged.
    if (left <= workingOriginX && top <= workingOriginY &&
        right >= workingRight && bottom >= workingBottom) {
        return out;
    }

    // Keep even extents for Bayer/GPU workgroup consumers. The origin itself may be odd: BnCam's
    // existing cfaOffset/effective-CFA contract explicitly carries that phase downstream.
    if (((right - left) & 1) != 0) --right;
    if (((bottom - top) & 1) != 0) --bottom;
    if (right - left < 4 || bottom - top < 4) return out;

    out.applied = true;
    out.localLeft = left - workingOriginX;
    out.localTop = top - workingOriginY;
    out.width = right - left;
    out.height = bottom - top;
    return out;
}

} // namespace bncam::raw
