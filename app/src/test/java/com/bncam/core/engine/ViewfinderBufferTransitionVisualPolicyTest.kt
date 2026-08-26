package com.bncam.core.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ViewfinderBufferTransitionVisualPolicyTest {
    @Test
    fun `profile buffer replacement is intentionally black masked`() {
        assertTrue(
            ViewfinderRebuildVisualPolicy.requiresBlackTransition(
                decisionReasons = listOf("BUFFER_FORMAT_CHANGED", "BACKEND_ROUTE_CHANGED"),
                requestReason = "PROFILE_BUFFER_CHANGED_YUV_TO_RAW_SENSOR"
            )
        )
    }

    @Test
    fun `viewfinder stream source switch is intentionally black masked`() {
        assertTrue(
            ViewfinderRebuildVisualPolicy.requiresBlackTransition(
                decisionReasons = listOf("FORCED_VENDOR_OR_SETTINGS_SESSION_REBUILD"),
                requestReason = "VIEWFINDER_STREAM_MODE_CHANGED_yuv_TO_selected_buffer"
            )
        )
    }

    @Test
    fun `same logical physical lens handover keeps last known good texture`() {
        assertFalse(
            ViewfinderRebuildVisualPolicy.requiresBlackTransition(
                decisionReasons = listOf("PHYSICAL_CAMERA_ID_CHANGED", "BUFFER_SIZE_CHANGED"),
                requestReason = "SAME_LOGICAL_PHYSICAL_LENS_HANDOVER"
            )
        )
    }

    @Test
    fun `vendor and watchdog rebuilds do not impersonate buffer changes`() {
        assertFalse(
            ViewfinderRebuildVisualPolicy.requiresBlackTransition(
                decisionReasons = listOf("SESSION_VENDOR_TAG_CONFIG_CHANGED"),
                requestReason = "VENDOR_OPERATION_MODE_PROBE_NEXT"
            )
        )
        assertFalse(
            ViewfinderRebuildVisualPolicy.requiresBlackTransition(
                decisionReasons = listOf("MAX_IMAGES_CHANGED"),
                requestReason = "WARM_BUFFER_WATCHDOG_TRANSPORT_STALLED_AFTER_RESUBMIT"
            )
        )
    }
}
