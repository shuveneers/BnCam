package com.bncam.ui.screens.capture

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ViewfinderSelectorGeometryTest {
    @Test
    fun `first lens is horizontal left and final lens is vertical above master`() {
        val points = (0 until 4).map { radialSelectorPoint(it, 4, 100f) }
        assertTrue(abs(points.first().x + 100f) < 0.001f)
        assertTrue(abs(points.first().y) < 0.001f)
        assertTrue(abs(points.last().x) < 0.001f)
        assertTrue(abs(points.last().y + 100f) < 0.001f)
        assertTrue(points.drop(1).all { it.x <= 0.001f && it.y < 0f })
    }

    @Test
    fun `long press swipe follows the same left to upper arc`() {
        assertNull(nearestRadialSelectorIndex(0f, -10f, 4, minimumDistance = 20f))
        assertEquals(0, nearestRadialSelectorIndex(-80f, 0f, 4, minimumDistance = 20f))
        assertEquals(1, nearestRadialSelectorIndex(-70f, -35f, 4, minimumDistance = 20f))
        assertEquals(3, nearestRadialSelectorIndex(0f, -80f, 4, minimumDistance = 20f))
    }

    @Test
    fun opticalRatiosUseCameraStyleLabels() {
        assertEquals("0.6×", formatLensZoomRatio(0.56f))
        assertEquals("0.6×", formatLensZoomRatio(0.6f))
        assertEquals("1.0×", formatLensZoomRatio(1f))
        assertEquals("3.7×", formatLensZoomRatio(3.7f))
        assertEquals("Lens", formatLensZoomRatio(Float.NaN))
    }
}
