package com.bncam.ui.screens.capture

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

class QuickZoomPillContractTest {
    private fun source(path: String): String {
        val roots = listOf(File("."), File("app"))
        return roots.asSequence().map { File(it, path) }.firstOrNull { it.isFile }?.readText()
            ?: error("Missing source file: $path")
    }

    @Test
    fun quickZoomPillIsConnectedToLiveCameraZoom() {
        val camera = source("src/main/java/com/bncam/ui/screens/capture/CameraScreen.kt")
        val pill = source("src/main/java/com/bncam/ui/screens/capture/QuickZoomPill.kt")
        val settings = source("src/main/java/com/bncam/data/settings/SettingsRepository.kt")

        assertTrue(camera.contains("QuickZoomPill("))
        assertTrue(camera.contains("if (!exposureDialState.visible && !pinchZoomDialVisible)"))
        assertTrue(camera.contains("abs(zoomMultiplier - 1f) < 0.002f"))
        assertTrue(camera.contains("requestSmoothZoom(target, durationMs = 180)"))
        assertTrue(camera.contains("currentZoom = { requestedZoomLevel }"))
        assertTrue(camera.contains("bnCameraManager.setZoom(value)"))
        assertTrue(camera.contains("activeLens.id,\n                    maxDigitalZoom\n                ) {"))
        assertTrue(camera.contains("onDoubleTap = { _ ->"))
        assertTrue(camera.contains("val currentZoom = currentZoomLevelState.value.coerceIn(1f, safeMaxZoom)"))
        assertTrue(camera.contains("\"2x Zoom\" -> if (currentZoom > 1.01f) 1f else min(2f, safeMaxZoom)"))
        assertTrue(pill.contains("currentZoom: () -> Float"))
        assertTrue(pill.contains("label = \"1x\""))
        assertTrue(pill.contains("label = \"2x\""))
        assertTrue(settings.contains("null, \"None\" -> \"2x Zoom\""))
    }
}
