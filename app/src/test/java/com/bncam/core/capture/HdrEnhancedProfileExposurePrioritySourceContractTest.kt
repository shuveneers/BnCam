package com.bncam.core.capture

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HdrEnhancedProfileExposurePrioritySourceContractTest {
    private val managerSource = File("src/main/java/com/bncam/core/engine/BnCameraManager.kt").readText()
    private val plannerSource = File("src/main/java/com/bncam/core/capture/HdrEnhancedCapturePlan.kt").readText()

    @Test
    fun hdrUsesShutterTimeProfileModeWithoutReapplyingProfileMultipliers() {
        val acquisition = managerSource.substringAfter("private suspend fun acquireHdrEnhancedBurst(")
            .substringBefore("private fun")

        assertTrue(acquisition.contains("exposurePriorityMode = recipe.executionSettings.captureExposurePreferences.effectivePriorityMode()"))
        assertTrue(acquisition.contains("exposureBounds = hdrExposureBounds"))
        assertFalse(acquisition.contains("recipe.executionSettings.captureExposurePreferences.shutterMultiplier"))
        assertFalse(acquisition.contains("recipe.executionSettings.captureExposurePreferences.isoMultiplier"))
        assertFalse(acquisition.contains("recipe.executionSettings.captureExposurePreferences.captureEvBias"))
        assertFalse(acquisition.contains("ProfileExposurePriorityPlanner.initialPlan"))
    }

    @Test
    fun shutterPriorityProtectsHighlightsWithIsoBeforeOverridingShutter() {
        assertTrue(plannerSource.contains("CaptureExposurePriorityMode.SHUTTER_PRIORITY"))
        assertTrue(plannerSource.contains("ISO_FLOOR_REQUIRES_SHUTTER_OVERRIDE"))
        assertTrue(plannerSource.contains("profileExposurePriorityHonored"))
        assertTrue(plannerSource.contains("profileExposurePriorityConstraint"))
    }

    @Test
    fun hdrRemainsAnAppLevelStrategyNotAProfileCaptureMode() {
        val profileUi = File("src/main/java/com/bncam/ui/screens/settings/lens_profiles/ProfileEditScreen.kt")
        if (profileUi.exists()) {
            val ui = profileUi.readText()
            assertFalse(ui.contains("CaptureModeOption.HDR_ENHANCED"))
        }
        assertTrue(managerSource.contains("CaptureStrategy.HDR_ENHANCED"))
    }
}
