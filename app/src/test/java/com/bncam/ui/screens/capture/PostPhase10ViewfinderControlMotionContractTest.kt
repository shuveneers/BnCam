package com.bncam.ui.screens.capture

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PostPhase10ViewfinderControlMotionContractTest {
    private fun source(): String {
        val root = requireNotNull(System.getProperty("user.dir"))
        return File(root, "src/main/java/com/bncam/ui/screens/capture/CameraScreen.kt").readText()
    }

    @Test
    fun focusEvAndZoomUseInterpolatedActuatorPaths() {
        val source = source()
        assertTrue(source.contains("focusActuator.animateTo"))
        assertTrue(source.contains("evActuator.animateTo"))
        assertTrue(source.contains("zoomActuator.animateTo"))
        assertFalse(source.contains("currentZoomLevel = (currentZoomLevel * zoomMultiplier)"))
        assertFalse(source.contains("bnCameraManager.setFocus(newValue, activeLens.id)"))
    }

    @Test
    fun viewfinderUsesFullWidthExposureAndFocalDials() {
        val source = source()
        assertTrue(source.contains("FullWidthExposureDial"))
        assertTrue(source.contains("FullWidthFocalLengthDial"))
        assertTrue(source.contains("14f, 18f, 23f, 28f, 35f, 50f, 70f, 85f"))
        assertTrue(source.contains("pendingPinchLensId"))
        assertTrue(source.contains("onLensSelected(targetLens)"))
    }
}
