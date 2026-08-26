package com.bncam.core.engine

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FocusTrackRestorationSourceContractTest {
    private val appDir: File = sequenceOf(File("."), File("app"))
        .firstOrNull { File(it, "src/main/java/com/bncam/core/engine/BnCameraManager.kt").isFile }
        ?: error("Cannot locate app module")

    @Test
    fun viewfinderSettingEnablesRealTrackingAnalysis() {
        val repo = File(appDir, "src/main/java/com/bncam/data/settings/SettingsRepository.kt").readText()
        val settings = File(appDir, "src/main/java/com/bncam/ui/screens/settings/ViewfinderScreen.kt").readText()
        val screen = File(appDir, "src/main/java/com/bncam/ui/screens/capture/CameraScreen.kt").readText()
        assertTrue(repo.contains("focusTrackingFlow"))
        assertTrue(settings.contains("\"Focus Track\""))
        assertTrue(screen.contains("objectTracking = focusTracking"))
        assertFalse(screen.contains("setFocusTrackingTarget(null, null)"))
    }

    @Test
    fun tapAndLongPressHaveBoundedAndPinnedTrackingSemantics() {
        val screen = File(appDir, "src/main/java/com/bncam/ui/screens/capture/CameraScreen.kt").readText()
        assertTrue(screen.contains("pinned = false"))
        assertTrue(screen.contains("reason = \"focus_lock_timeout\""))
        assertTrue(screen.contains("pinned = true"))
        assertTrue(screen.contains("tapToFocusAndStartTracking("))
    }

    @Test
    fun trackerIsLatestWinsAndSuppressesCompetingFocusVisuals() {
        val manager = File(appDir, "src/main/java/com/bncam/core/engine/BnCameraManager.kt").readText()
        val screen = File(appDir, "src/main/java/com/bncam/ui/screens/capture/CameraScreen.kt").readText()
        assertTrue(manager.contains("enqueuePreviewControl(\"focus_tracking\""))
        assertTrue(manager.contains("selectTrackingCandidate("))
        assertTrue(manager.contains("reacquireTrackingCandidate("))
        assertTrue(manager.contains("FocusTrackingPolicy.HARDWARE_UPDATE_INTERVAL_NS"))
        assertTrue(manager.contains("FocusTrackingPolicy.adaptiveAfRegionPct"))
        assertTrue(screen.contains("showRing = focusRing && !focusTrackingActive && !faceOwnsFocusVisual"))
        assertTrue(screen.contains("showFaces = faceDetection && !focusTrackingActive"))
    }
}
