package com.bncam.ui.screens.capture

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class Phase7ViewfinderSliderGeometryTest {
    @Test
    fun `value round trip keeps indicator at the actual setting position`() {
        val height = 320f
        listOf(-1f, -0.5f, 0f, 0.5f, 1f).forEach { value ->
            val offset = ViewfinderSliderGeometry.trackOffsetForValue(value, -1f, 1f, height)
            val roundTrip = ViewfinderSliderGeometry.valueForTrackOffset(offset, -1f, 1f, height)
            assertEquals(value, roundTrip!!, 0.0001f)
        }
    }

    @Test
    fun `absolute pointer mapping tracks finger rather than accumulated drag distance`() {
        val trackTop = 22f
        val height = 320f
        val resetExtension = 48f
        assertEquals(0f, ViewfinderSliderGeometry.pointerToTrackOffset(22f, trackTop, height, resetExtension))
        assertEquals(160f, ViewfinderSliderGeometry.pointerToTrackOffset(182f, trackTop, height, resetExtension))
        assertEquals(320f, ViewfinderSliderGeometry.pointerToTrackOffset(342f, trackTop, height, resetExtension))
    }

    @Test
    fun `reset extension is outside the value domain`() {
        val height = 320f
        assertNull(ViewfinderSliderGeometry.valueForTrackOffset(330f, 0f, 1f, height))
        assertEquals(368f, ViewfinderSliderGeometry.pointerToTrackOffset(999f, 22f, height, 48f))
    }
}
