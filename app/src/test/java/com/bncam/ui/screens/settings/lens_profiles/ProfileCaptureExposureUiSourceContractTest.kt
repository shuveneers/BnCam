package com.bncam.ui.screens.settings.lens_profiles

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileCaptureExposureUiSourceContractTest {
    private val screen = File("src/main/java/com/bncam/ui/screens/settings/lens_profiles/ProfileCaptureExposureSettingsScreen.kt").readText()
    private val editor = File("src/main/java/com/bncam/ui/screens/settings/lens_profiles/ProfileEditScreen.kt").readText()
    private val navigation = File("src/main/java/com/bncam/ui/navigation/AppNavigation.kt").readText()

    @Test
    fun shotBiasIsSeparateFromPostCaptureLightAndShadow() {
        assertTrue(screen.contains("SettingsTopicScaffold(\"Shot Bias\""))
        assertTrue(screen.contains("title = \"Max exposure for a frame\""))
        assertTrue(screen.contains("Capture EV Bias"))
        assertTrue(screen.contains("post-capture ISP Exposure"))
        assertTrue(editor.contains("title = \"Shot Bias\""))
        assertTrue(editor.contains("title = \"Light & Shadow\""))
        assertFalse(editor.contains("title = \"Exposure strategy\""))
    }

    @Test
    fun uiUsesOnlyV3AuthoritativeShotBiasKeys() {
        assertTrue(screen.contains("CaptureSettingKeys.SHOT_BIAS_EXPOSURE"))
        assertTrue(screen.contains("CaptureSettingKeys.SHOT_BIAS_MAX_FRAME_EXPOSURE"))
        assertTrue(screen.contains("CaptureSettingKeys.CAPTURE_EV_BIAS"))
        assertFalse(screen.contains("ChoiceSettingRow(\n                title = \"Exposure priority\""))
        assertFalse(screen.contains("Shutter multiplier"))
        assertFalse(screen.contains("ISO multiplier"))
    }

    @Test
    fun existingRouteIsRetainedAndWiredFromProfileEditor() {
        assertTrue(navigation.contains("PROFILE_CAPTURE_EXPOSURE"))
        assertTrue(navigation.contains("profileCaptureExposure"))
        assertTrue(navigation.contains("ProfileCaptureExposureSettingsScreen("))
        assertTrue(navigation.contains("onNavigateToShotBias"))
    }
}
