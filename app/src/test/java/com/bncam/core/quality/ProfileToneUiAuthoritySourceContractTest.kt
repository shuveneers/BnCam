package com.bncam.core.quality

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileToneUiAuthoritySourceContractTest {
    private val appDir: File = sequenceOf(File("."), File("app"))
        .firstOrNull { File(it, "src/main/java/com/bncam/ui/screens/settings/lens_profiles/ProfileEditScreen.kt").isFile }
        ?: error("Cannot locate app module from ${File(".").absolutePath}")

    private fun source(relative: String): String = File(appDir, relative).readText()

    @Test
    fun `v3 exposes one light and shadow workspace without legacy native tone shape`() {
        val editor = source("src/main/java/com/bncam/ui/screens/settings/lens_profiles/ProfileEditScreen.kt")
        val toneScreens = source("src/main/java/com/bncam/ui/screens/settings/lens_profiles/ProfileToneSettingsScreens.kt")

        assertFalse(editor.contains("Native tone shape"))
        assertFalse(editor.contains("ProfileToneNativeControls"))
        assertFalse(editor.contains("ProfileToneSlider"))
        assertTrue(editor.contains("title = \"Light & Shadow\""))
        assertFalse(editor.contains("title = \"Tonal Range\""))
        assertFalse(editor.contains("title = \"Contrast & Local Tone\""))
        assertTrue(toneScreens.contains("SettingsTopicScaffold(\"Light & Shadow\""))
        assertTrue(toneScreens.contains("ProfileIspKeys.TONE_CONTRAST"))
        assertTrue(toneScreens.contains("ProfileIspKeys.TONE_HIGHLIGHTS"))
        assertFalse(toneScreens.substringAfter("fun ProfileLightShadowSettingsScreen").substringBefore("// Legacy deep-link").contains("ProfileIspKeys.LOCAL_TONE_BIAS"))
    }
}
