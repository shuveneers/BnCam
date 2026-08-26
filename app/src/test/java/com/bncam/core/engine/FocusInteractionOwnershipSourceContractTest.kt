package com.bncam.core.engine

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FocusInteractionOwnershipSourceContractTest {
    private fun source(path: String): String = File(path).readText()

    @Test
    fun timingAndTrackingOwnershipLiveInCameraEngine() {
        val manager = source("src/main/java/com/bncam/core/engine/BnCameraManager.kt")
        val screen = source("src/main/java/com/bncam/ui/screens/capture/CameraScreen.kt")
        assertTrue(manager.contains("fun holdTapFocusFor"))
        assertTrue(manager.contains("awaitMonotonicDeadline"))
        assertTrue(manager.contains("fun focusAndLockAt"))
        assertTrue(manager.contains("TAP_TO_FOCUS_AND_TRACK"))
        assertTrue(manager.contains("FOCUS_TRACK_STANDARD_AE_RESTORE"))
        assertFalse(screen.contains("focusLockJob"))
        assertTrue(screen.contains("tapToFocusAndStartTracking("))
    }

    @Test
    fun facePriorityIsAVisibleFaceDetectionChild() {
        val settings = source("src/main/java/com/bncam/ui/screens/settings/ViewfinderScreen.kt")
        val screen = source("src/main/java/com/bncam/ui/screens/capture/CameraScreen.kt")
        assertTrue(settings.contains("if (faceDetection)"))
        assertTrue(settings.contains("Nearest face priority focus"))
        assertTrue(screen.contains("facePriorityFocus = faceDetection && facePriorityFocus"))
    }
}
