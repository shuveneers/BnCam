package com.bncam.core.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhysicalYuvFullFovPolicyTest {
    @Test
    fun `hidden physical ultra wide yuv uses logical zoom out floor`() {
        val decision = PhysicalYuvFullFovPolicy.resolve(
            isYuv = true,
            physicalCameraId = "4",
            lensRole = "Ultra wide",
            minimumLogicalZoomRatio = 0.56f,
            maximumLogicalZoomRatio = 20f
        )

        assertTrue(decision.enabled)
        assertEquals(0.56f, decision.nativeZoomRatio, 0.0001f)
        assertEquals(0.56f, decision.resolveEffectiveZoom(1f), 0.0001f)
        assertEquals(1.12f, decision.resolveEffectiveZoom(2f), 0.0001f)
    }

    @Test
    fun `raw route is never modified by yuv full fov policy`() {
        val decision = PhysicalYuvFullFovPolicy.resolve(
            isYuv = false,
            physicalCameraId = "4",
            lensRole = "Ultra wide",
            minimumLogicalZoomRatio = 0.56f,
            maximumLogicalZoomRatio = 20f
        )

        assertFalse(decision.enabled)
        assertEquals("NON_YUV_ROUTE", decision.reason)
    }

    @Test
    fun `directly opened ultra wide stays at native camera one x`() {
        val decision = PhysicalYuvFullFovPolicy.resolve(
            isYuv = true,
            physicalCameraId = null,
            lensRole = "Ultra wide",
            minimumLogicalZoomRatio = 0.56f,
            maximumLogicalZoomRatio = 20f
        )

        assertFalse(decision.enabled)
        assertEquals("DIRECT_OR_LOGICAL_CAMERA_ROUTE", decision.reason)
    }

    @Test
    fun `physical tele route is not accidentally zoomed out`() {
        val decision = PhysicalYuvFullFovPolicy.resolve(
            isYuv = true,
            physicalCameraId = "7",
            lensRole = "Tele",
            minimumLogicalZoomRatio = 0.56f,
            maximumLogicalZoomRatio = 20f
        )

        assertFalse(decision.enabled)
        assertEquals("PHYSICAL_ROUTE_IS_NOT_ULTRA_WIDE", decision.reason)
    }
}
