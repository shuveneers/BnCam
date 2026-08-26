package com.bncam.data.profile

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileMetadataSingleAuthoritySourceContractTest {
    private val root = File(System.getProperty("user.dir"))

    @Test
    fun `profile identity model contains no duplicate ISP setting groups`() {
        val source = File(root, "app/src/main/java/com/bncam/data/profile/IspProfileConfig.kt").readText()
        listOf(
            "GroupAFramesConfig",
            "GroupBColorConfig",
            "GroupCDenoiseConfig",
            "GroupDToneConfig",
            "GroupEDetailConfig",
            "GroupFJpegConfig",
            "ProfileGroup"
        ).forEach { legacy -> assertFalse("legacy profile model returned: $legacy", source.contains(legacy)) }
        assertTrue(source.contains("Persistent profile identity/ownership metadata"))
        assertTrue(source.contains("const val SCHEMA_VERSION = 5"))
    }

    @Test
    fun `obsolete parallel profile engines stay removed`() {
        assertFalse(File(root, "app/src/main/java/com/bncam/data/profile/ProfileImportExportEngine.kt").exists())
        assertFalse(File(root, "app/src/main/java/com/bncam/data/profile/ProfileMigrationEngine.kt").exists())
    }

    @Test
    fun `effective shutter snapshot reads current profile key authorities`() {
        val repo = File(root, "app/src/main/java/com/bncam/data/settings/SettingsRepository.kt").readText()
        assertFalse(repo.contains("activeProfile.group"))
        assertTrue(repo.contains("getProfileMultiFrameFusionFramesRaw10Flow(activeProfile.id).first()"))
        assertTrue(repo.contains("ProfileIspKeys.TONE_CONTRAST"))
        assertTrue(repo.contains("ProfileIspKeys.PRESENCE_VIBRANCE"))
        assertTrue(repo.contains("CaptureSettingKeys.JPEG_QUALITY"))
    }
}
