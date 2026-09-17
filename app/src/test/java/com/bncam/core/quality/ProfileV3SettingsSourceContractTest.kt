package com.bncam.core.quality

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileV3SettingsSourceContractTest {
    private val root = File(System.getProperty("user.dir"))
    private fun source(path: String) = File(root, path).readText()

    @Test
    fun `neural denoise profile page is separate from physical noise model ownership`() {
        val ui = source("src/main/java/com/bncam/ui/screens/settings/lens_profiles/ProfileTuningSubScreens.kt")
        val resolver = source("src/main/java/com/bncam/core/quality/LibpatcherProfileResolver.kt")
        val characters = source("src/main/java/com/bncam/core/quality/SpectraProfileCharacter.kt")
        val neuralBlock = ui.substringAfter("fun ProfileDenoiseSettingsScreen").substringBefore("fun ProfileMultiFrameSettingsScreen")

        assertFalse(ui.contains("fun ProfileSpectraSettingsScreen"))
        assertTrue(neuralBlock.contains("SettingsTopicScaffold(\"Neural Denoise\""))
        assertFalse(neuralBlock.contains("key = ProfileIspKeys.NEURAL_DENOISE_STRENGTH"))
        assertFalse(neuralBlock.contains("title = \"Strength\""))
        assertFalse(neuralBlock.contains("ProfileIspKeys.NEURAL_ADAPTIVE_RESPONSE"))
        assertFalse(neuralBlock.contains("title = \"Adaptive Response\""))
        assertFalse(neuralBlock.contains("NoiseModelSource"))
        assertFalse(neuralBlock.contains("Manual ISO"))
        assertFalse(neuralBlock.contains("title = \"Dynamic ISO\""))
        val runtimeSpecs = resolver.substringAfter("fun runtimeProfileSettingSpecs").substringBefore("private fun legacyNoiseCompatibilitySpecs")
        assertFalse(runtimeSpecs.contains("ProfileIspKeys.SPECTRA_STRENGTH"))
        assertFalse(runtimeSpecs.contains("ProfileIspKeys.NEURAL_ADAPTIVE_RESPONSE"))
        assertTrue(characters.contains("MASTER_AUTHORITY = 1.00f"))
        assertTrue(characters.contains("ADAPTIVE_RESPONSE = 1.00f"))
        assertFalse(characters.contains("masterStrength"))
    }

    @Test
    fun `dng master count is app output owned and not profile owned`() {
        val appSettings = source("src/main/java/com/bncam/ui/screens/settings/AppSettingsScreen.kt")
        val recipe = source("src/main/java/com/bncam/core/capture/CaptureRecipeFactory.kt")
        val resolver = source("src/main/java/com/bncam/core/quality/LibpatcherProfileResolver.kt")
        val multi = source("src/main/java/com/bncam/ui/screens/settings/lens_profiles/ProfileTuningSubScreens.kt")

        assertTrue(appSettings.contains("outputPolicy.producesRaw"))
        assertTrue(appSettings.contains("title = \"DNG master frame count\""))
        assertTrue(appSettings.contains("DngSourcePolicy.FUSED_RAW"))
        assertTrue(recipe.contains("repository.getOutputModeSettingsFlow().first().getConfigForOutputPolicy(outputPolicy)"))
        assertFalse(recipe.substringAfter("private suspend fun requestedDngMasterFrameCount(").substringBefore("private fun MultiFrameAlgorithmDescriptor").contains("DNG_MASTER_FRAMES_RAW"))
        assertFalse(resolver.contains("CaptureSettingKeys.DNG_MASTER_FRAMES_RAW10"))
        assertFalse(resolver.contains("CaptureSettingKeys.DNG_MASTER_FRAMES_RAW_SENSOR"))
        assertFalse(multi.substringAfter("fun ProfileMultiFrameSettingsScreen").substringBefore("fun ProfileCurveSettingsScreen").contains("DNG master frame count\","))
    }

    @Test
    fun `planned controls are visible but truthfully marked not connected`() {
        val multiUi = source("src/main/java/com/bncam/ui/screens/settings/lens_profiles/ProfileTuningSubScreens.kt")
        val tone = source("src/main/java/com/bncam/ui/screens/settings/lens_profiles/ProfileToneSettingsScreens.kt")
        val resolver = source("src/main/java/com/bncam/core/quality/LibpatcherProfileResolver.kt")
        val runner = source("src/main/java/com/bncam/core/runners/MultiFrameRunner.kt")

        assertTrue(multiUi.contains("Accept all frames (not connected)"))
        assertTrue(tone.contains("Gamma Contrast (not connected)"))
        assertTrue(tone.contains("Dehaze (not connected)"))
        assertTrue(tone.contains("Clarity (not connected)"))
        assertTrue(multiUi.contains("Color Fringe Suppression (not connected)"))
        assertTrue(multiUi.contains("Polysharp (not connected)"))
        assertTrue(runner.contains("UNAVAILABLE_PRESERVED_NOT_EXECUTED"))

        // Planned keys round-trip through .bnc but do not masquerade as connected runtime controls.
        assertTrue(resolver.contains("plannedProfileSettingSpecs"))
        val runtime = resolver.substringAfter("fun runtimeProfileSettingSpecs").substringBefore("private fun plannedProfileSettingSpecs")
        listOf("TONE_GAMMA_CONTRAST", "TONE_DEHAZE", "TONE_CLARITY", "PRESENCE_COLOR_FRINGE_SUPPRESSION", "POLYSHARP_GAIN").forEach { key ->
            assertFalse(runtime.contains("ProfileIspKeys.$key"))
        }
    }

    @Test
    fun `curve editor uses one snapshot and dark tabs`() {
        val editor = source("src/main/java/com/bncam/ui/screens/settings/lens_profiles/ProfileEditScreen.kt")
        val screens = source("src/main/java/com/bncam/ui/screens/settings/lens_profiles/ProfileTuningSubScreens.kt")
        val curveBlock = editor.substringAfter("fun ProfileCurveTopicContent").substringBefore("private fun displayTitleFor")
        assertTrue(curveBlock.contains("readProfileSettingsSnapshot"))
        assertFalse(curveBlock.contains("collectAsStateWithLifecycle"))
        assertTrue(curveBlock.contains("onValueChangeFinished"))
        assertTrue(screens.contains("containerColor = Color.Transparent"))
    }

    @Test
    fun `removed local tone bias cannot retain hidden runtime authority`() {
        val resolver = source("src/main/java/com/bncam/core/quality/LibpatcherProfileResolver.kt")
        val config = source("src/main/java/com/bncam/core/quality/RenderQualityConfig.kt")
        val toneUi = source("src/main/java/com/bncam/ui/screens/settings/lens_profiles/ProfileToneSettingsScreens.kt")

        assertFalse(resolver.contains("ProfileIspKeys.LOCAL_TONE_BIAS"))
        assertFalse(config.contains("readProfileFloatOrFallback(repo, profileId, ProfileIspKeys.LOCAL_TONE_BIAS"))
        assertTrue(config.contains("localToneBias = 0f"))
        assertFalse(toneUi.substringAfter("fun ProfileLightShadowSettingsScreen").substringBefore("// Legacy deep-link").contains("ProfileIspKeys.LOCAL_TONE_BIAS"))
    }

    @Test
    fun `awb intensity is universal and bounded in log gain space`() {
        val ui = source("src/main/java/com/bncam/ui/screens/settings/lens_profiles/ProfileAwbSettingsScreen.kt")
        val resolver = source("src/main/java/com/bncam/core/quality/ProfileAwbResolver.kt")
        val settings = source("src/main/java/com/bncam/data/settings/ProfileLensTuningSettings.kt")
        assertTrue(ui.contains("title = \"AWB intensity\""))
        assertTrue(ui.contains("valueRange = 0f..1.5f"))
        assertTrue(resolver.contains("logBlendGains"))
        assertTrue(settings.contains("coerceIn(0f, 1.5f)"))
    }
    @Test
    fun `long choice dialogs scroll and curve tabs inherit dark background`() {
        val components = source("src/main/java/com/bncam/ui/screens/settings/lens_profiles/TuningScreenComponents.kt")
        val screens = source("src/main/java/com/bncam/ui/screens/settings/lens_profiles/ProfileTuningSubScreens.kt")
        assertTrue(components.contains(".heightIn(max = 420.dp)"))
        assertTrue(components.contains(".verticalScroll(rememberScrollState())"))
        assertTrue(screens.contains("containerColor = Color.Transparent"))
        assertTrue(screens.contains("contentColor = Color.White"))
    }

}
