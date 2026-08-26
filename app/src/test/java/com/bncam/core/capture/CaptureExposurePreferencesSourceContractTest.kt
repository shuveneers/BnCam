package com.bncam.core.capture

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CaptureExposurePreferencesSourceContractTest {
    private fun source(root: File, path: String) = File(root, path).readText()

    @Test
    fun profileV3ShotBiasIsPortableAndCapturedAtShutter() {
        val root = File(System.getProperty("user.dir"))
        val schema = source(root, "src/main/java/com/bncam/data/settings/CaptureSettingsSchema.kt")
        val catalog = source(root, "src/main/java/com/bncam/core/quality/LibpatcherProfileResolver.kt")
        val factory = source(root, "src/main/java/com/bncam/core/capture/CaptureRecipeFactory.kt")
        val recipe = source(root, "src/main/java/com/bncam/core/capture/CaptureRecipe.kt")
        val preferences = source(root, "src/main/java/com/bncam/core/capture/CaptureExposurePreferences.kt")
        val planner = source(root, "src/main/java/com/bncam/core/capture/ProfileExposurePriorityPlanner.kt")

        val activeKeys = listOf("SHOT_BIAS_EXPOSURE", "SHOT_BIAS_MAX_FRAME_EXPOSURE", "CAPTURE_EV_BIAS")
        activeKeys.forEach { key ->
            assertTrue("capture schema missing $key", schema.contains(key))
            assertTrue("portable profile catalog missing $key", catalog.contains("CaptureSettingKeys.$key"))
            assertTrue("shutter factory missing $key", factory.contains("CaptureSettingKeys.$key"))
        }
        assertTrue(schema.contains("superseded(CaptureSettingKeys.EXPOSURE_PRIORITY_MODE"))
        assertTrue(schema.contains("superseded(CaptureSettingKeys.SHUTTER_PRIORITY_MULTIPLIER"))
        assertTrue(schema.contains("superseded(CaptureSettingKeys.ISO_PRIORITY_MULTIPLIER"))
        assertFalse(catalog.substringAfter("val captureProfileSpecs = listOf(").substringBefore(")\n\n        return").contains("EXPOSURE_PRIORITY_MODE"))
        assertTrue(preferences.contains("legacyPriorityIgnored"))
        assertTrue(planner.contains("prefs.maxFrameExposure.resolveNs"))
        assertTrue(recipe.contains("captureExposurePreferences: CaptureExposurePreferences"))
        assertTrue(recipe.contains("captureExposurePreferences.debugMap()"))
    }
}
