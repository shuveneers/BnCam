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
    fun `three rear lenses own quarter arc and selfie extends vertically`() {
        val points = (0 until 4).map {
            lensSelectorPoint(
                index = it,
                rearCount = 3,
                hasSelfie = true,
                rearRadius = 100f,
                selfieGap = 50f
            )
        }
        assertTrue(abs(points[0].x + 100f) < 0.001f)
        assertTrue(abs(points[0].y) < 0.001f)
        assertTrue(abs(points[1].x + 70.71068f) < 0.01f)
        assertTrue(abs(points[1].y + 70.71068f) < 0.01f)
        assertTrue(abs(points[2].x) < 0.001f)
        assertTrue(abs(points[2].y + 100f) < 0.001f)
        assertTrue(abs(points[3].x) < 0.001f)
        assertTrue(abs(points[3].y + 150f) < 0.001f)
    }


    @Test
    fun `selfie joins arc one when only two rear lenses are visible`() {
        val points = (0 until 3).map {
            lensSelectorPoint(
                index = it,
                rearCount = 2,
                hasSelfie = true,
                rearRadius = 100f,
                selfieGap = 50f
            )
        }
        assertTrue(abs(points[0].x + 100f) < 0.001f)
        assertTrue(abs(points[0].y) < 0.001f)
        assertTrue(abs(points[1].x + 70.71068f) < 0.01f)
        assertTrue(abs(points[1].y + 70.71068f) < 0.01f)
        assertTrue(abs(points[2].x) < 0.001f)
        assertTrue(abs(points[2].y + 100f) < 0.001f)
    }

    @Test
    fun `selfie joins arc one when only one rear lens is visible`() {
        val rear = lensSelectorPoint(0, 1, true, 100f, 50f)
        val selfie = lensSelectorPoint(1, 1, true, 100f, 50f)
        assertTrue(abs(rear.x + 100f) < 0.001f)
        assertTrue(abs(rear.y) < 0.001f)
        assertTrue(abs(selfie.x) < 0.001f)
        assertTrue(abs(selfie.y + 100f) < 0.001f)
    }

    @Test
    fun `drag targeting uses separated selfie stop`() {
        assertNull(
            nearestLensSelectorIndex(
                dragX = 0f,
                dragY = -10f,
                rearCount = 3,
                hasSelfie = true,
                rearRadius = 100f,
                selfieGap = 50f,
                minimumDistance = 20f
            )
        )
        assertEquals(0, nearestLensSelectorIndex(-90f, 0f, 3, true, 100f, 50f, 20f))
        assertEquals(1, nearestLensSelectorIndex(-70f, -70f, 3, true, 100f, 50f, 20f))
        assertEquals(2, nearestLensSelectorIndex(0f, -100f, 3, true, 100f, 50f, 20f))
        assertEquals(3, nearestLensSelectorIndex(0f, -150f, 3, true, 100f, 50f, 20f))
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
