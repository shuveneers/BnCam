package com.bncam.core.engine

/** Pure capability policy used by OisResolver. */
object OisRoutePolicy {
    enum class Route {
        DIRECT_STANDARD,
        PROBED_DIRECT_STANDARD,
        HIDDEN_STANDARD,
        DIRECT_VENDOR_OPTICAL,
        HIDDEN_PHYSICAL_KEY,
        UNAVAILABLE
    }

    fun resolve(
        userRequested: Boolean,
        directDeviceRoute: Boolean,
        hiddenPhysicalRoute: Boolean,
        selectedLensSupportsOis: Boolean,
        openedCameraSupportsStandardOis: Boolean,
        probedDirectStandardOisWritable: Boolean,
        approvedVendorKeyAvailable: Boolean
    ): Route {
        if (!userRequested) return Route.UNAVAILABLE

        // A directly opened camera ID owns its own lens controls. This applies equally to main,
        // ultra-wide and tele IDs; there is deliberately no special casing for a primary camera.
        if (directDeviceRoute) {
            if (selectedLensSupportsOis && openedCameraSupportsStandardOis) {
                return Route.DIRECT_STANDARD
            }
            // A hidden camera that was qualified by a successful direct open can under-report
            // the same lens capability metadata it hid from cameraIdList. Permit a standards-only
            // runtime probe when the opened CameraDevice exposes the standard request key. The
            // capture-result validator decides whether the mode remains active.
            if (probedDirectStandardOisWritable) {
                return Route.PROBED_DIRECT_STANDARD
            }
            if (approvedVendorKeyAvailable) {
                return Route.DIRECT_VENDOR_OPTICAL
            }
            return Route.UNAVAILABLE
        }

        // Hidden physical lenses on some OEMs under-report OIS in CameraCharacteristics. The supplied
        // validated hardware ground truth is exactly such a case: static metadata reports has_ois=0 while
        // HAL3 capture results for the same physical tele route report
        // android.lens.opticalStabilizationMode=ON. Therefore capability metadata must not be used as
        // a gate that actively writes OIS OFF for a pinned physical output. For a verified logical ->
        // physical route, request the standard OIS control on the opened logical CameraDevice and let
        // the HAL accept/ignore it. This mirrors the working validated camera route behavior and preserves EIS=OFF.
        if (hiddenPhysicalRoute) {
            return Route.HIDDEN_STANDARD
        }

        // Not directly opened and not resolved as a physical child: do not guess a route.
        return Route.UNAVAILABLE
    }
}
