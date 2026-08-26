package com.bncam.data.profile

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BncProfileRepositorySourceContractTest {
    private fun appDir(): File = sequenceOf(File("."), File("app"))
        .firstOrNull { File(it, "src/main/java/com/bncam/data/settings/SettingsRepository.kt").isFile }
        ?: error("Cannot locate app module")

    @Test
    fun `bnc repository export materializes complete profile and never reads hardware calibration`() {
        val repo = File(appDir(), "src/main/java/com/bncam/data/settings/SettingsRepository.kt").readText()
        val exportStart = repo.indexOf("suspend fun exportProfileBnc(")
        val importStart = repo.indexOf("suspend fun importProfileBnc(")
        val exportBlock = repo.substring(exportStart, importStart)

        assertTrue(exportBlock.contains("includePortableDefaults = true"))
        assertTrue(exportBlock.contains("snapshot.totalCount == safeSpecs.size"))
        assertTrue(exportBlock.contains("getOrCreatePortableProfileUuid"))
        assertTrue(exportBlock.contains("hardwareCalibrationIncluded = false"))
        assertTrue(exportBlock.contains("readProfileCaptureModeName"))
        assertTrue(exportBlock.contains("readProfileFrameSource"))
        assertFalse(exportBlock.contains("readLensHardwareSettingsSnapshot"))
        assertFalse(exportBlock.contains("blackLevel"))
        assertFalse(exportBlock.contains("noiseCalibration"))
    }

    @Test
    fun `bnc import validates before one atomic datastore transaction and preserves target hardware`() {
        val repo = File(appDir(), "src/main/java/com/bncam/data/settings/SettingsRepository.kt").readText()
        val importStart = repo.indexOf("suspend fun importProfileBnc(")
        val sanitizerStart = repo.indexOf("private fun sanitizeImportedPortableSnapshot")
        val block = repo.substring(importStart, sanitizerStart)

        assertTrue(block.indexOf("BncProfileCodec.decode") < block.indexOf("context.dataStore.edit"))
        assertTrue(block.contains("BncProfileCompatibility.resolveFrameSource"))
        assertTrue(block.contains("preferences.remove(overrideSetKey(targetProfileId))"))
        assertTrue(block.contains("profile_portable_uuid") || repo.contains("portableProfileUuidKey"))
        assertTrue(block.contains("profile_name_\$targetProfileId"))
        assertTrue(block.contains("profile_mode_\$targetProfileId"))
        assertTrue(block.contains("profile_frame_source_\$targetProfileId"))
        assertFalse(block.contains("lensFloatKey"))
        assertFalse(block.contains("lensStringKey"))
        assertFalse(block.contains("resetLensHardwareSettings"))
    }
}
