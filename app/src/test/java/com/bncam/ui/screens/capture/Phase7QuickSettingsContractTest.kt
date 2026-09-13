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
    fun `camera screen writes quick controls and layout through canonical settings repository setters`() {
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
            "repository.setFaceDetection(enabled)",
            "repository.setQuickSettingsAssignments(normalized.take(9))",
            "repository.setUltraHdrGainmapEnabled(!turnOff)",
            "repository.setUltraHdrGainmapEnabled(false)"
        ).forEach { expected -> assertTrue(camera.contains(expected), expected) }
    }

    @Test
    fun `quick settings exposes nine assignable slots pencil editor and three shot modes`() {
        val camera = source("src/main/java/com/bncam/ui/screens/capture/CameraScreen.kt")
        val overlay = source("src/main/java/com/bncam/ui/screens/capture/ViewfinderQuickSettingsOverlay.kt")
        assertTrue(overlay.contains("normalizedAssignments.take(9).chunked(3)"))
        assertTrue(overlay.contains("Icons.Default.Edit"))
        assertTrue(overlay.contains("Icons.AutoMirrored.Filled.ArrowBack"))
        assertTrue(overlay.contains("BackHandler(enabled = editMode || editingSlot != null)"))
        assertFalse(overlay.contains("compact = true"))
        assertTrue(overlay.contains(".height(70.dp)"))
        assertFalse(overlay.contains("\"Close\""))
        assertTrue(overlay.contains("onQuickSettingAssigned"))
        assertTrue(overlay.contains("ViewfinderQuickSettingIds.all"))
        assertTrue(overlay.contains("QuickShotMode.PORTRAIT"))
        assertTrue(overlay.contains("QuickShotMode.ULTRA_HDR"))
        assertTrue(overlay.contains("QuickShotMode.NIGHT"))
        assertTrue(overlay.contains("ShotModeGlyph.PORTRAIT"))
        assertTrue(overlay.contains("ShotModeGlyph.HDR"))
        assertTrue(overlay.contains("ShotModeGlyph.NIGHT"))
        assertTrue(overlay.contains("text = if (selected) \"On\" else \"Off\""))
        assertTrue(camera.contains("text = \"Photo\""))
        assertTrue(camera.contains("text = \"Video\""))
        assertFalse(camera.contains("listOf(ViewfinderMode.NIGHT, ViewfinderMode.PHOTO, ViewfinderMode.PORTRAIT, ViewfinderMode.VIDEO)"))
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
