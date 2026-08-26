package com.bncam.ui.screens.capture

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class Phase7QuickSettingsContractTest {
    private fun source(path: String): String = File(path).readText()

    @Test
    fun `quick overlay owns no repository or persisted shadow state`() {
        val overlay = source("src/main/java/com/bncam/ui/screens/capture/ViewfinderQuickSettingsOverlay.kt")
        assertFalse(overlay.contains("SettingsRepository("))
        assertFalse(overlay.contains("DataStore"))
        listOf(
            "Flash", "Timer", "Watermark", "Output", "Viewfinder stream", "Geotag",
            "Focus peaking", "Metering", "Histogram", "Focus track",
            "Horizon leveler", "Face detection"
        ).forEach { label -> assertTrue("\"$label\"" in overlay, label) }
    }

    @Test
    fun `camera screen writes quick controls through canonical settings repository setters`() {
        val camera = source("src/main/java/com/bncam/ui/screens/capture/CameraScreen.kt")
        listOf(
            "repository.setFlashMode(mode)",
            "repository.setTimerDuration(seconds)",
            "repository.setWatermarkEnabled(enabled)",
            "repository.setOutputPolicy(policy)",
            "repository.setViewfinderStream(stream)",
            "locationPermissionLauncher.launch(",
            "repository.setSaveLocationData(false)",
            "repository.setFocusPeak(enabled)",
            "repository.setMeteringStyle(mode.settingValue)",
            "repository.setHistogram(enabled)",
            "repository.setFocusTracking(enabled)",
            "repository.setHorizonLeveler(enabled)",
            "repository.setFaceDetection(enabled)"
        ).forEach { expected -> assertTrue(camera.contains(expected), expected) }
    }

    @Test
    fun `quick settings uses three columns and swipe down is observed before child consumers`() {
        val camera = source("src/main/java/com/bncam/ui/screens/capture/CameraScreen.kt")
        val overlay = source("src/main/java/com/bncam/ui/screens/capture/ViewfinderQuickSettingsOverlay.kt")
        assertTrue(overlay.contains("tiles.chunked(3)"))
        assertTrue(overlay.contains("viewfinderStream: ViewfinderStream"))
        assertTrue(overlay.contains("uiRotationDegrees: Float"))
        assertTrue(overlay.contains("Modifier.weight(1f)"))
        assertTrue(camera.contains("awaitEachGesture"))
        assertTrue(camera.contains("event.changes.count { it.pressed } > 1"))
        assertTrue(camera.contains("travel.y > abs(travel.x) * 1.15f"))
        assertTrue(camera.contains("travel.y >= openThresholdPx"))
        assertTrue(camera.contains("awaitPointerEvent(PointerEventPass.Initial)"))
    }
}
