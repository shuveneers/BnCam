package com.bncam.core.engine

/**
 * Pure route policy for a user-selected Camera2 lens ID.
 *
 * If CameraManager exposes the selected ID in cameraIdList, opening that CameraDevice directly is
 * the authoritative route: it preserves the selected sensor's own crop/zoom coordinate space and
 * standard lens controls. A logical-parent + physical-output route is reserved for hidden physical
 * IDs that cannot be opened as CameraDevices themselves.
 */
object CameraDeviceRoutePolicy {
    fun shouldOpenDirectly(requestedLensId: String, directlyOpenableIds: Set<String>): Boolean =
        requestedLensId in directlyOpenableIds
}
