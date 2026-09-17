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
        listOf(
            "MASTER_STRENGTH", "ADAPTIVE_RESPONSE", "LUMA", "CHROMA", "DETAIL_PROTECTION", "LOW_FREQUENCY"
        ).forEach { suffix ->
            assertTrue(render.contains("SpectraProfileDefaults.$suffix"))
            assertTrue(tuningUi.contains("SpectraProfileDefaults.$suffix"))
        }
        assertTrue(resolver.contains("ProfileIspKeys.NEURAL_ADAPTIVE_RESPONSE, SpectraProfileDefaults.ADAPTIVE_RESPONSE"))
        assertFalse(render.contains("ProfileIspKeys.SPECTRA_DYNAMIC_ISO"))
        assertTrue(resolver.contains("ProfileIspKeys.SPECTRA_CHROMA, SpectraProfileDefaults.CHROMA"))
        assertTrue(resolver.contains("ProfileIspKeys.SPECTRA_LOW_FREQUENCY, SpectraProfileDefaults.LOW_FREQUENCY"))
        assertTrue(profileUi.contains("Off · \$spectraCharacter latent"))
    }
}
