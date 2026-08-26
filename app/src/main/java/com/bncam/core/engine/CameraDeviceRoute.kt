package com.bncam.core.engine

enum class CameraRouteKind {
    /** The selected camera ID is advertised by CameraManager and owns its CameraDevice directly. */
    PUBLIC_DIRECT,

    /** A hidden selected camera ID was successfully opened and owns its CameraDevice directly. */
    PROBED_DIRECT,

    /** Direct opening failed; a logical owner supplies outputs from the selected physical child. */
    LOGICAL_PHYSICAL,

    /** Internal conservative state used only until a hidden ID can be probed after hardware close. */
    DIRECT_PROBE_PENDING
}

/**
 * Canonical ownership route for one user-selectable lens.
 *
 * For direct routes [logicalCameraId] is the selected camera ID and [physicalCameraId] is null.
 * Only [CameraRouteKind.LOGICAL_PHYSICAL] may retain a logical owner with physical outputs.
 */
data class CameraDeviceRoute(
    val requestedLensId: String,
    val logicalCameraId: String,
    val physicalCameraId: String?,
    val routeKind: CameraRouteKind
) {
    init {
        when (routeKind) {
            CameraRouteKind.LOGICAL_PHYSICAL -> require(!physicalCameraId.isNullOrBlank()) {
                "LOGICAL_PHYSICAL requires a physical camera ID"
            }
            CameraRouteKind.PUBLIC_DIRECT,
            CameraRouteKind.PROBED_DIRECT,
            CameraRouteKind.DIRECT_PROBE_PENDING -> require(physicalCameraId == null) {
                "$routeKind must be owned directly and cannot carry a physical output ID"
            }
        }
    }

    val isPhysicalChild: Boolean
        get() = physicalCameraId != null

    val isDirect: Boolean
        get() = physicalCameraId == null

    val isQualified: Boolean
        get() = routeKind != CameraRouteKind.DIRECT_PROBE_PENDING

    /**
     * True when switching routes can, in principle, retain the already-open CameraDevice.
     * Session/output compatibility is checked separately by the camera manager.
     */
    fun sharesLogicalCameraDeviceWith(other: CameraDeviceRoute): Boolean =
        logicalCameraId == other.logicalCameraId
}
