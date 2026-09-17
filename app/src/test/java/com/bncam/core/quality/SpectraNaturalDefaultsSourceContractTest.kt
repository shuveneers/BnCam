package com.bncam.core.quality

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpectraNaturalDefaultsSourceContractTest {
    @Test
    fun `natural defaults are one source of truth while spectra toggle stays off`() {
        val render = File("src/main/java/com/bncam/core/quality/RenderQualityConfig.kt").readText()
        val resolver = File("src/main/java/com/bncam/core/quality/LibpatcherProfileResolver.kt").readText()
        val tuningUi = File("src/main/java/com/bncam/ui/screens/settings/lens_profiles/ProfileTuningSubScreens.kt").readText()
        val profileUi = File("src/main/java/com/bncam/ui/screens/settings/lens_profiles/ProfileEditScreen.kt").readText()

        assertFalse(SpectraProfileDefaults.ENABLED)
        assertTrue(render.contains("SpectraProfileDefaults.MASTER_AUTHORITY"))
        assertTrue(render.contains("SpectraProfileDefaults.ADAPTIVE_RESPONSE"))
        assertTrue(SpectraProfileDefaults.ADAPTIVE_RESPONSE == 1.0f)
        listOf(
            "LUMA", "CHROMA", "DETAIL_PROTECTION", "LOW_FREQUENCY"
        ).forEach { suffix ->
            assertTrue(render.contains("SpectraProfileDefaults.$suffix"))
            assertTrue(tuningUi.contains("SpectraProfileDefaults.$suffix"))
        }
        val runtime = resolver.substringAfter("fun runtimeProfileSettingSpecs").substringBefore("private fun legacyNoiseCompatibilitySpecs")
        val legacy = resolver.substringAfter("private fun legacyNoiseCompatibilitySpecs").substringBefore("private fun plannedProfileSettingSpecs")
        assertFalse(runtime.contains("ProfileIspKeys.NEURAL_ADAPTIVE_RESPONSE"))
        assertTrue(legacy.contains("ProfileIspKeys.NEURAL_ADAPTIVE_RESPONSE"))
        assertFalse(tuningUi.substringAfter("fun ProfileDenoiseSettingsScreen").substringBefore("fun ProfileMultiFrameSettingsScreen").contains("Adaptive Response"))
        assertFalse(render.contains("ProfileIspKeys.SPECTRA_DYNAMIC_ISO"))
        assertTrue(resolver.contains("ProfileIspKeys.SPECTRA_CHROMA, SpectraProfileDefaults.CHROMA"))
        assertTrue(resolver.contains("ProfileIspKeys.SPECTRA_LOW_FREQUENCY, SpectraProfileDefaults.LOW_FREQUENCY"))
        assertTrue(profileUi.contains("Off · \$neuralCharacter latent"))
    }
}
