package com.bncam.ui.screens.capture

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ViewfinderControlDesignContractTest {
    private val camera = File("src/main/java/com/bncam/ui/screens/capture/CameraScreen.kt").readText()

    @Test
    fun `legacy focus and exposure overlay sliders are removed`() {
        val focusOverlay = camera.substringAfter("fun FocusOverlay(")
            .substringBefore("fun ExposureOverlay(")
        val exposureOverlay = camera.substringAfter("fun ExposureOverlay(")
            .substringBefore("fun ExpandableVerticalSlider(")

        assertFalse(focusOverlay.contains("showSlider"))
        assertFalse(focusOverlay.contains("focusDistance"))
        assertFalse(exposureOverlay.contains("showSlider"))
        assertFalse(exposureOverlay.contains("exposureValue"))
    }

    @Test
    fun `side slider assignments exclude dedicated exposure and zoom controls`() {
        val block = camera.substringAfter("private fun AssignedViewfinderSlider(")
            .substringBefore("fun FocusOverlay(")

        assertTrue(block.contains("ViewfinderSliderAssignment.FOCUS"))
        assertTrue(block.contains("ViewfinderSliderAssignment.EV"))
        assertTrue(block.contains("ViewfinderSliderAssignment.WHITE_BALANCE"))
        assertTrue(block.contains("ViewfinderSliderAssignment.SATURATION"))
        assertTrue(block.contains("ViewfinderSliderAssignment.CONTRAST"))
        assertFalse(block.contains("ViewfinderSliderAssignment.ISO"))
        assertFalse(block.contains("ViewfinderSliderAssignment.SHUTTER"))
        assertFalse(block.contains("ViewfinderSliderAssignment.ZOOM"))
    }
}
