package com.bncam.core.quality

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfilePresenceSingleAuthoritySourceContractTest {
    private val appDir: File = sequenceOf(File("."), File("app"))
        .firstOrNull { File(it, "src/main/java/com/bncam/core/quality/RenderQualityConfig.kt").isFile }
        ?: error("Cannot locate app module")
    private fun source(relative: String): String = File(appDir, relative).readText()

    @Test
    fun `color manager owns portable vibrance and saturation`() {
        val keys = source("src/main/java/com/bncam/data/settings/ProfileLensTuningSettings.kt")
        val ui = source("src/main/java/com/bncam/ui/screens/settings/lens_profiles/ProfileTuningSubScreens.kt")
        val editor = source("src/main/java/com/bncam/ui/screens/settings/lens_profiles/ProfileEditScreen.kt")
        val resolver = source("src/main/java/com/bncam/core/quality/LibpatcherProfileResolver.kt")
        val render = source("src/main/java/com/bncam/core/quality/RenderQualityConfig.kt")
        val nav = source("src/main/java/com/bncam/ui/navigation/AppNavigation.kt")

        assertTrue(keys.contains("PRESENCE_VIBRANCE"))
        assertTrue(keys.contains("PRESENCE_SATURATION"))
        assertFalse(keys.contains("COLOR_HUE_SHIFT"))
        // Function name remains as a deep-link compatibility wrapper; user-facing owner is Color Manager.
        assertTrue(ui.contains("fun ProfilePresenceSettingsScreen("))
        assertTrue(ui.contains("SettingsTopicScaffold(\"Color Manager\""))
        assertFalse(ui.contains("Hue shift"))
        assertTrue(editor.contains("title = \"Color Manager\""))
        assertFalse(editor.contains("title = \"Presence\""))
        assertTrue(resolver.contains("ProfileIspKeys.PRESENCE_VIBRANCE"))
        assertTrue(resolver.contains("ProfileIspKeys.PRESENCE_SATURATION"))
        assertTrue(render.contains("ProfileIspKeys.PRESENCE_VIBRANCE"))
        assertFalse(render.contains("hueShift"))
        assertTrue(nav.contains("PROFILE_COLOR_MANAGER"))
        assertTrue(nav.contains("PROFILE_PRESENCE")) // legacy route retained
    }
}
