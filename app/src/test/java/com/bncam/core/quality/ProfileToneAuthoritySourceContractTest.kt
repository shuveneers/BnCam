package com.bncam.core.quality

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileToneAuthoritySourceContractTest {
    private val appDir: File = sequenceOf(File("."), File("app"))
        .firstOrNull { File(it, "src/main/java/com/bncam/core/quality/RenderQualityConfig.kt").isFile }
        ?: error("Cannot locate app module")

    private fun source(relative: String): String = File(appDir, relative).readText()

    @Test
    fun `profile tone keys are portable and snapshotted at shutter authority`() {
        val keys = source("src/main/java/com/bncam/data/settings/ProfileLensTuningSettings.kt")
        val resolver = source("src/main/java/com/bncam/core/quality/LibpatcherProfileResolver.kt")
        val quality = source("src/main/java/com/bncam/core/quality/RenderQualityConfig.kt")
        val required = listOf(
            "TONE_EXPOSURE", "TONE_HIGHLIGHTS", "TONE_SHADOWS", "TONE_WHITES",
            "TONE_BLACKS", "TONE_CONTRAST"
        )
        for (key in required) {
            assertTrue("missing key $key", keys.contains("const val $key"))
            assertTrue("portable contract missing $key", resolver.contains("ProfileIspKeys.$key"))
            assertTrue("snapshot missing $key", quality.contains("ProfileIspKeys.$key"))
        }
        assertTrue("legacy key must remain readable for migration", keys.contains("const val LOCAL_TONE_BIAS"))
        assertTrue("legacy local tone must not be portable", !resolver.contains("ProfileIspKeys.LOCAL_TONE_BIAS"))
        assertTrue("legacy local tone must not be read at runtime", !quality.contains("readProfileFloatOrFallback(repo, profileId, ProfileIspKeys.LOCAL_TONE_BIAS"))
        assertTrue("legacy local tone runtime authority must be neutral", quality.contains("localToneBias = 0f"))
        assertTrue(quality.contains("val toneTuning: ProfileToneTuning"))
        assertTrue(quality.contains("val profileToneTuning: ProfileToneTuning"))
        assertTrue(quality.contains("profileToneTuning = capturedPreferences.toneTuning"))
    }
}
