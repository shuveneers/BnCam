package com.bncam.ui.screens.capture

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

class ViewfinderDoubleTapZoomToggleSourceContractTest {
    private fun source(path: String): String {
        val roots = listOf(File("."), File("app"))
        return roots.asSequence().map { File(it, path) }.firstOrNull { it.isFile }?.readText()
            ?: error("Missing source file: $path")
    }

    @Test
    fun doubleTapZoomTogglesBackToNativeAndRawDisplayReceivesLiveZoom() {
        val camera = source("src/main/java/com/bncam/ui/screens/capture/CameraScreen.kt")
        val view = source("src/main/java/com/bncam/ui/screens/capture/FocusPeakingView.kt")

        assertTrue(camera.contains("val currentZoomLevelState = rememberUpdatedState(currentZoomLevel)"))
        assertTrue(camera.contains("\"2x Zoom\" -> if (currentZoom > 1.01f) 1f else min(2f, safeMaxZoom)"))
        assertTrue(camera.contains("digitalZoom = { currentZoomLevelState.value }"))
        assertTrue(camera.contains("view.setRawDisplayZoom(digitalZoom())"))
        assertTrue(view.contains("digitalZoom = rawDisplayZoom"))
    }
}
