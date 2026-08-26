package com.bncam.core.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraDeviceRouteTest {
    @Test
    fun `physical siblings share one logical CameraDevice`() {
        val main = CameraDeviceRoute("2", "0", "2", CameraRouteKind.LOGICAL_PHYSICAL)
        val tele = CameraDeviceRoute("3", "0", "3", CameraRouteKind.LOGICAL_PHYSICAL)

        assertTrue(main.isPhysicalChild)
        assertTrue(tele.isPhysicalChild)
        assertTrue(main.sharesLogicalCameraDeviceWith(tele))
    }

    @Test
    fun `different logical owners require hard CameraDevice transition`() {
        val rear = CameraDeviceRoute("2", "0", "2", CameraRouteKind.LOGICAL_PHYSICAL)
        val front = CameraDeviceRoute("1", "1", null, CameraRouteKind.PUBLIC_DIRECT)

        assertFalse(front.isPhysicalChild)
        assertFalse(rear.sharesLogicalCameraDeviceWith(front))
    }

    @Test
    fun `probed direct route is owned by selected camera`() {
        val tele = CameraDeviceRoute("5", "5", null, CameraRouteKind.PROBED_DIRECT)

        assertTrue(tele.isDirect)
        assertTrue(tele.isQualified)
        assertFalse(tele.isPhysicalChild)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `direct route rejects physical output id`() {
        CameraDeviceRoute("5", "5", "5", CameraRouteKind.PROBED_DIRECT)
    }
}
