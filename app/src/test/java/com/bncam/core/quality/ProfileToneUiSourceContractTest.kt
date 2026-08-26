package com.bncam.core.quality

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ProfileToneUiSourceContractTest {
    private fun source(root: File, path: String) = File(root, path).readText()

    @Test
    fun lightAndShadowIsTheSingleV3OwnerOfExistingToneKeys() {
        val root = File(System.getProperty("user.dir"))
        val edit = source(root, "src/main/java/com/bncam/ui/screens/settings/lens_profiles/ProfileEditScreen.kt")
        val screens = source(root, "src/main/java/com/bncam/ui/screens/settings/lens_profiles/ProfileToneSettingsScreens.kt")
        val nav = source(root, "src/main/java/com/bncam/ui/navigation/AppNavigation.kt")

        assertTrue(edit.contains("title = \"Light & Shadow\""))
        assertFalse(edit.contains("title = \"Tonal Range\""))
        assertFalse(edit.contains("title = \"Contrast & Local Tone\""))
        assertTrue(screens.contains("SettingsTopicScaffold(\"Light & Shadow\""))
        listOf(
            "ProfileIspKeys.TONE_EXPOSURE",
            "ProfileIspKeys.TONE_HIGHLIGHTS",
            "ProfileIspKeys.TONE_SHADOWS",
            "ProfileIspKeys.TONE_WHITES",
            "ProfileIspKeys.TONE_BLACKS",
            "ProfileIspKeys.TONE_CONTRAST"
        ).forEach { key -> assertTrue("tone UI must bind $key", screens.contains(key)) }
        assertFalse(screens.substringAfter("fun ProfileLightShadowSettingsScreen").substringBefore("// Legacy deep-link").contains("ProfileIspKeys.LOCAL_TONE_BIAS"))
        assertTrue(nav.contains("PROFILE_LIGHT_SHADOW"))
        // Old routes remain valid for external/deep-link compatibility and land on the V3 owner.
        listOf("PROFILE_EXPOSURE", "PROFILE_TONAL_RANGE", "PROFILE_CONTRAST_LOCAL_TONE").forEach {
            assertTrue(nav.contains(it))
        }
        assertTrue(screens.contains("without changing physical sensor exposure"))
    }
}
