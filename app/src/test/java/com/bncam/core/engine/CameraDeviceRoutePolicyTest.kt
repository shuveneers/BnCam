package com.bncam.core.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraDeviceRoutePolicyTest {
    @Test
    fun directlyOpenablePhysicalChildPrefersOwnCameraDevice() {
        assertTrue(
            CameraDeviceRoutePolicy.shouldOpenDirectly(
                requestedLensId = "3",
                directlyOpenableIds = setOf("0", "1", "3")
            )
        )
    }

    @Test
    fun hiddenPhysicalChildStillNeedsLogicalParentRoute() {
        assertFalse(
            CameraDeviceRoutePolicy.shouldOpenDirectly(
                requestedLensId = "7",
                directlyOpenableIds = setOf("0", "1")
            )
        )
    }
}
