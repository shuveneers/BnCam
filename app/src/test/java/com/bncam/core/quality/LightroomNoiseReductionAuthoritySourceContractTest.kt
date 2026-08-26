package com.bncam.core.quality

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LightroomNoiseReductionAuthoritySourceContractTest {
    private val appDir: File = sequenceOf(File("."), File("app"))
        .firstOrNull { File(it, "src/main/java/com/bncam/data/settings/ProfileLensTuningSettings.kt").isFile }
        ?: error("Cannot locate app module")
    private fun source(path: String) = File(appDir, path).readText()

    @Test
    fun `spectra and Lightroom noise reduction have separate profile authorities`() {
        val keys = source("src/main/java/com/bncam/data/settings/ProfileLensTuningSettings.kt")
        val config = source("src/main/java/com/bncam/core/quality/RenderQualityConfig.kt")
        assertTrue(keys.contains("SPECTRA_LUMA"))
        assertTrue(keys.contains("SPECTRA_CHROMA"))
        assertTrue(keys.contains("DETAIL_NR_LUMINANCE"))
        assertTrue(keys.contains("DETAIL_NR_COLOR_SMOOTHNESS"))
        assertFalse(keys.contains("NOISE_LUMA_ONLY"))
        assertFalse(keys.contains("NOISE_CHROMA_ONLY"))
        assertFalse(keys.contains("NOISE_ARTIFACT_CLEANUP"))
        assertTrue(config.contains("data class ProfileNoiseTuning"))
        assertTrue(config.contains("data class ProfileNoiseReductionTuning"))
        assertTrue(config.contains("profileNoiseReductionTuning = capturedPreferences.noiseReductionTuning"))
    }

    @Test
    fun `noise reduction has its own Denoise page and old other-noise route is gone`() {
        val editor = source("src/main/java/com/bncam/ui/screens/settings/lens_profiles/ProfileEditScreen.kt")
        val subs = source("src/main/java/com/bncam/ui/screens/settings/lens_profiles/ProfileTuningSubScreens.kt")
        val nav = source("src/main/java/com/bncam/ui/navigation/AppNavigation.kt")
        assertFalse(editor.contains("Other noise controls"))
        assertFalse(subs.contains("ProfileNoiseOtherSettingsScreen"))
        assertFalse(nav.contains("PROFILE_NOISE_OTHERS"))
        assertTrue(subs.contains("\"Denoise\""))
        listOf("Luminance", "Luminance Detail", "Luminance Contrast", "Color / Chroma", "Color / Chroma Detail", "Color / Chroma Smoothness").forEach {
            assertTrue("Missing Lightroom NR control $it", subs.contains("\"$it\""))
        }
    }

    @Test
    fun `portable profile catalog exports spectra and Lightroom NR separately`() {
        val catalog = source("src/main/java/com/bncam/core/quality/LibpatcherProfileResolver.kt")
        val recipe = source("src/main/java/com/bncam/core/capture/CaptureRecipe.kt")
        assertFalse(catalog.contains("ProfileIspKeys.SPECTRA_STRENGTH"))
        assertTrue(catalog.contains("ProfileIspKeys.DETAIL_NR_LUMINANCE"))
        assertTrue(catalog.contains("ProfileIspKeys.DETAIL_NR_COLOR_SMOOTHNESS"))
        assertTrue(recipe.contains("\"spectraProfile\" to spectraProfileMap()"))
        assertTrue(recipe.contains("\"noiseReductionProfile\" to noiseReductionProfileMap()"))
    }
}
