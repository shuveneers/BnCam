package com.bncam.core.quality

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileSettingsArchitectureSourceContractTest {
    private fun appDir(): File = sequenceOf(File("."), File("app"))
        .firstOrNull { File(it, "src/main/java/com/bncam/ui/navigation/AppNavigation.kt").isFile }
        ?: error("Cannot locate app module from ${File(".").absolutePath}")

    @Test
    fun lensHardwareNoiseModelRemainsOutsidePortableProfileV3() {
        val app = appDir()
        val screen = File(app, "src/main/java/com/bncam/ui/screens/settings/lens_profiles/NoiseModelSettingsScreen.kt").readText()
        val resolver = File(app, "src/main/java/com/bncam/core/quality/LibpatcherProfileResolver.kt").readText()
        assertTrue(screen.contains("Sensor Noise Model"))
        assertTrue(screen.contains("Manual noise model"))
        assertFalse(resolver.contains("lens_noise_a"))
        assertFalse(resolver.contains("lens_noise_b"))
    }

    @Test
    fun profileV3OverviewUsesTheRequestedOwnershipOrder() {
        val app = appDir()
        val editorSource = File(app, "src/main/java/com/bncam/ui/screens/settings/lens_profiles/ProfileEditScreen.kt").readText()
        val editor = editorSource.substringAfter("private fun LibpatcherProfileOverview(")

        val ordered = listOf(
            "title = profileName",
            "title = \"Multi-frame processing\"",
            "title = \"RAW Processing\"",
            "title = \"ISP Tuning\"",
            "SettingsCard(title = \"Others\""
        )
        var previous = -1
        ordered.forEach { marker ->
            val index = editor.indexOf(marker)
            assertTrue("Missing or unordered V3 marker: $marker", index > previous)
            previous = index
        }
        assertTrue(editor.contains("title = \"Profile title\""))
        assertTrue(editor.contains("title = \"Capture mode\""))
        assertTrue(editor.contains("title = \"Buffer Type / Pipeline\""))
        assertTrue(editor.contains("title = \"Shot Bias\""))
        assertTrue(editor.contains("title = \"Demosaic\""))
        assertTrue(editor.contains("title = \"Neural Denoise\""))
        assertFalse(editor.contains("title = \"SPECTRA\""))
        assertTrue(editor.contains("title = \"Light & Shadow\""))
        assertTrue(editor.contains("title = \"Curves\""))
        assertTrue(editor.contains("title = \"Color Manager\""))
        assertTrue(editor.contains("title = \"Sharpness\""))
        assertFalse(editor.contains("title = \"Exposure strategy\""))
        assertFalse(editor.contains("title = \"Tonal Range\""))
        assertFalse(editor.contains("title = \"Presence\""))
    }

    @Test
    fun v3SubsectionsHaveRoutesAndCurvesHaveThreeTabsAndDirectManipulation() {
        val app = appDir()
        val nav = File(app, "src/main/java/com/bncam/ui/navigation/AppNavigation.kt").readText()
        val subScreens = File(app, "src/main/java/com/bncam/ui/screens/settings/lens_profiles/ProfileTuningSubScreens.kt").readText()
        val editor = File(app, "src/main/java/com/bncam/ui/screens/settings/lens_profiles/ProfileEditScreen.kt").readText()

        listOf(
            "PROFILE_SPECTRA", "PROFILE_DENOISE", "PROFILE_LIGHT_SHADOW", "PROFILE_CURVES",
            "PROFILE_COLOR_MANAGER", "PROFILE_SHARPNESS", "PROFILE_TRANSFER", "PROFILE_OTHER_SETTINGS",
            "PROFILE_MULTI_FRAME"
        ).forEach { assertTrue("missing $it", nav.contains(it)) }
        assertFalse(subScreens.contains("fun ProfileSpectraSettingsScreen"))
        assertTrue(subScreens.contains("fun ProfileDenoiseSettingsScreen"))
        assertTrue(subScreens.contains("fun ProfileCurveSettingsScreen"))
        assertTrue(subScreens.contains("fun ProfileSharpnessSettingsScreen"))
        assertTrue(subScreens.contains("fun ProfileMultiFrameSettingsScreen"))
        assertTrue(editor.contains("ProfileCurveGridPreview"))
        assertTrue(editor.contains("PersistedDecimalField"))
        assertTrue(editor.contains("detectDragGestures"))
        assertTrue(editor.contains("change.consume()"))
        assertTrue(subScreens.contains("ProfileCurveDefaults.TYPE_TONE to \"tone\""))
        assertTrue(subScreens.contains("ProfileCurveDefaults.TYPE_GAMMA to \"gamma\""))
        assertTrue(subScreens.contains("ProfileCurveDefaults.TYPE_SECT to \"sect\""))
    }

    @Test
    fun neuralDenoiseIsTheSingleProfileNoisePage() {
        val app = appDir()
        val keys = File(app, "src/main/java/com/bncam/data/settings/ProfileLensTuningSettings.kt").readText()
        val config = File(app, "src/main/java/com/bncam/core/quality/RenderQualityConfig.kt").readText()
        val resolver = File(app, "src/main/java/com/bncam/core/quality/LibpatcherProfileResolver.kt").readText()
        val ui = File(app, "src/main/java/com/bncam/ui/screens/settings/lens_profiles/ProfileTuningSubScreens.kt").readText()

        assertTrue(keys.contains("SPECTRA_STRENGTH")) // legacy decode key only
        assertFalse(resolver.contains("ProfileIspKeys.SPECTRA_STRENGTH"))
        assertTrue(config.contains("MASTER_AUTHORITY"))
        assertTrue(config.contains("get() = if (spectraEnabled) SpectraProfileDefaults.MASTER_AUTHORITY else 0f"))
        val neuralBlock = ui.substringAfter("fun ProfileDenoiseSettingsScreen").substringBefore("fun ProfileMultiFrameSettingsScreen")
        assertTrue(neuralBlock.contains("SettingsTopicScaffold(\"Neural Denoise\""))
        assertFalse(neuralBlock.contains("key = ProfileIspKeys.NEURAL_DENOISE_STRENGTH"))
        assertFalse(neuralBlock.contains("title = \"Strength\""))
        assertFalse(neuralBlock.contains("ProfileIspKeys.NEURAL_ADAPTIVE_RESPONSE"))
        assertFalse(neuralBlock.contains("title = \"Adaptive Response\""))
        assertTrue(neuralBlock.contains("ProfileIspKeys.SPECTRA_LUMA"))
        assertTrue(neuralBlock.contains("ProfileIspKeys.SPECTRA_CHROMA"))
        assertTrue(neuralBlock.contains("ProfileIspKeys.SPECTRA_DETAIL"))
        assertTrue(neuralBlock.contains("ProfileIspKeys.SPECTRA_LOW_FREQUENCY"))
        assertFalse(neuralBlock.contains("NoiseModelSource"))
        assertFalse(neuralBlock.contains("Manual ISO"))
        assertFalse(neuralBlock.contains("title = \"Dynamic ISO\""))
        assertFalse(ui.contains("fun ProfileSpectraSettingsScreen"))
    }

    @Test
    fun profileExportIncludesAwbMultiFrameAllCurvesButNotAppDngOwnership() {
        val app = appDir()
        val resolver = File(app, "src/main/java/com/bncam/core/quality/LibpatcherProfileResolver.kt").readText()
        val repo = File(app, "src/main/java/com/bncam/data/settings/SettingsRepository.kt").readText()
        val awb = File(app, "src/main/java/com/bncam/ui/screens/settings/lens_profiles/ProfileAwbSettingsScreen.kt").readText()
        val subScreens = File(app, "src/main/java/com/bncam/ui/screens/settings/lens_profiles/ProfileTuningSubScreens.kt").readText()

        assertTrue(awb.contains("AWB intensity"))
        assertTrue(awb.contains("0f..1.5f"))
        assertTrue(resolver.contains("add(s(\"awb_mode\", ProfileAwbModes.SYSTEM_AUTO))"))
        assertTrue(resolver.contains("CaptureSettingKeys.ALIGNMENT_METHOD"))
        assertTrue(resolver.contains("CaptureSettingKeys.FUSION_FRAMES_RAW10"))
        assertTrue(resolver.contains("ProfileCurveDefaults.TYPE_TONE"))
        assertTrue(resolver.contains("ProfileCurveDefaults.TYPE_GAMMA"))
        assertTrue(resolver.contains("ProfileCurveDefaults.TYPE_SECT"))
        assertFalse(resolver.contains("CaptureSettingKeys.DNG_MASTER_FRAMES_RAW10"))
        assertFalse(resolver.contains("CaptureSettingKeys.DNG_MASTER_FRAMES_RAW_SENSOR"))
        assertTrue(repo.contains("readPortableEffectiveProfileSnapshot"))
        assertTrue(repo.contains("includePortableDefaults = true"))
        assertTrue(subScreens.contains("title = \"JPEG quality\""))
        assertTrue(subScreens.contains("valueRange = 80f..100f"))
        assertFalse(subScreens.contains("jpegQualityToSignedAdjustment"))
    }

    @Test
    fun yuvIspSettingsRemainConsumedInFastAndComputePaths() {
        val app = appDir()
        val imageUtils = File(app, "src/main/java/com/bncam/core/engine/ImageUtils.kt").readText()
        val bridge = File(app, "src/main/cpp/native-lib.cpp").readText()
        val nativeConfig = File(app, "src/main/cpp/NativeRenderQualityConfig.h").readText()

        assertTrue(imageUtils.contains("resolveYuvProfileAwbCompensation"))
        assertTrue(imageUtils.contains("profileYuvWbRed = yuvAwbCompensation[0]"))
        assertTrue(bridge.contains("applyYuvProfileRgbAdjustments(bgrMat, qualityConfig)"))
        assertFalse(bridge.contains("applyYuvProfileRgbAdjustments(bgrMat, rawCfg)"))
        assertTrue(bridge.contains("profileColorSaturation"))
        assertTrue(nativeConfig.contains("profileYuvWbRed"))
    }
}
