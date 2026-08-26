package com.bncam.ui.screens.capture

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ExposureDialRingContractTest {
    private fun source(path: String): String {
        val roots = listOf(File("."), File("app"))
        return roots.asSequence().map { File(it, path) }.firstOrNull { it.isFile }?.readText()
            ?: error("Missing source file: $path")
    }

    @Test
    fun dedicatedExposureControlsUseDialRingAndKeepLiveCallbacks() {
        val camera = source("src/main/java/com/bncam/ui/screens/capture/CameraScreen.kt")
        val dial = source("src/main/java/com/bncam/ui/screens/capture/ExposureDialRing.kt")

        assertTrue(camera.contains("ExposureDialSide.SHUTTER_LEFT"))
        assertTrue(camera.contains("ExposureDialSide.ISO_RIGHT"))
        assertFalse(camera.contains("fun InteractiveHaloButton("))
        assertTrue(dial.contains("currentValue: () -> String"))
        assertTrue(camera.contains("currentValue = { assignedShutterNsState.value"))
        assertTrue(camera.contains("currentValue = { assignedIsoValueState.value"))
        assertTrue(dial.contains("onValueChange(latestValues[newIndex])"))
        assertTrue(dial.contains("verticalRemainderPx -= dragAmount.y"))
        assertTrue(dial.contains("onReset()"))
        assertTrue(dial.contains("HapticFeedbackType.TextHandleMove"))
        assertFalse(camera.contains("assignedIsoFraction"))
        assertFalse(camera.contains("assignedShutterFraction"))
        assertFalse(camera.contains("private fun logarithmicFraction("))
    }
}
