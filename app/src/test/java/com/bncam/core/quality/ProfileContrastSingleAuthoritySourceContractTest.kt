package com.bncam.core.quality

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileContrastSingleAuthoritySourceContractTest {
    private val appDir: File = sequenceOf(File("."), File("app"))
        .firstOrNull { File(it, "src/main/java/com/bncam/core/quality/RenderQualityConfig.kt").isFile }
        ?: error("Cannot locate app module")

    private fun source(relative: String): String = File(appDir, relative).readText()

    @Test
    fun `profile exposes one tonal contrast authority`() {
        val ui = source("src/main/java/com/bncam/ui/screens/settings/lens_profiles/ProfileTuningSubScreens.kt")
        val resolver = source("src/main/java/com/bncam/core/quality/LibpatcherProfileResolver.kt")
        val render = source("src/main/java/com/bncam/core/quality/RenderQualityConfig.kt")
        val toneUi = source("src/main/java/com/bncam/ui/screens/settings/lens_profiles/ProfileToneSettingsScreens.kt")
        val keys = source("src/main/java/com/bncam/data/settings/ProfileLensTuningSettings.kt")

        assertFalse(ui.contains("ProfileIspKeys.COLOR_CONTRAST"))
        assertFalse(keys.contains("COLOR_CONTRAST"))
        assertFalse(keys.contains("color_profile_contrast"))
        assertFalse(resolver.substringAfter("fun runtimeProfileSettingSpecs()").substringBefore("fun allProfileSettingSpecs()")
            .contains("ProfileIspKeys.COLOR_CONTRAST"))
        assertFalse(render.contains("readProfileFloatOrFallback(repo, profileId, ProfileIspKeys.COLOR_CONTRAST"))
        assertTrue(render.contains("contrast = 0f"))
        assertTrue(toneUi.contains("ProfileIspKeys.TONE_CONTRAST"))
        assertTrue(render.contains("val colorTuning = liveViewfinderTuning.applyColor(baseColorTuning)"))
    }
}
