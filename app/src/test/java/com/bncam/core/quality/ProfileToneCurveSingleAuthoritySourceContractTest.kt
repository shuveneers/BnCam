package com.bncam.core.quality

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileToneCurveSingleAuthoritySourceContractTest {
    private val appDir: File = sequenceOf(File("."), File("app"))
        .firstOrNull { File(it, "src/main/java/com/bncam/core/quality/RenderQualityConfig.kt").isFile }
        ?: error("Cannot locate app module")
    private fun source(relative: String): String = File(appDir, relative).readText()

    @Test
    fun `v3 curves expose tone gamma and sect through one runtime contract`() {
        val ui = source("src/main/java/com/bncam/ui/screens/settings/lens_profiles/ProfileTuningSubScreens.kt")
        val editor = source("src/main/java/com/bncam/ui/screens/settings/lens_profiles/ProfileEditScreen.kt")
        val resolver = source("src/main/java/com/bncam/core/quality/LibpatcherProfileResolver.kt")
        val render = source("src/main/java/com/bncam/core/quality/RenderQualityConfig.kt")
        val defaults = render

        assertTrue(ui.contains("SettingsTopicScaffold(\"Curves\""))
        assertTrue(ui.contains("ProfileCurveDefaults.TYPE_TONE to \"tone\""))
        assertTrue(ui.contains("ProfileCurveDefaults.TYPE_GAMMA to \"gamma\""))
        assertTrue(ui.contains("ProfileCurveDefaults.TYPE_SECT to \"sect\""))
        assertTrue(editor.contains("title = \"Curves\""))
        assertTrue(editor.contains("Tone, Gamma and Sect"))
        assertTrue(resolver.contains("ProfileCurveDefaults.TYPE_GAMMA"))
        assertTrue(resolver.contains("ProfileCurveDefaults.TYPE_SECT"))
        assertTrue(render.contains("loadCurvePreset(repo, profileId, ProfileCurveDefaults.TYPE_GAMMA)"))
        assertTrue(render.contains("loadCurvePreset(repo, profileId, ProfileCurveDefaults.TYPE_SECT)"))
        assertTrue(render.contains("ProfileCurveDefaults.TYPE_GAMMA,\n                        gammaPreset"))
        assertTrue(render.contains("ProfileCurveDefaults.TYPE_SECT,\n                        sectionPreset"))
        assertTrue(defaults.contains("fun nodeCount(type: String): Int = if (type == TYPE_SECT) 7 else 16"))
    }
}
