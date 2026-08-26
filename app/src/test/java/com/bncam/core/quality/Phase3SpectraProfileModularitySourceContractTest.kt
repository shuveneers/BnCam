package com.bncam.core.quality

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class Phase3SpectraProfileModularitySourceContractTest {
    private fun appDir(): File = sequenceOf(File("."), File("app"))
        .firstOrNull { File(it, "src/main/java/com/bncam/core/quality/LibpatcherProfileResolver.kt").isFile }
        ?: error("Cannot locate app module")

    @Test
    fun `complete profile serialization includes spectra switch and dynamic iso`() {
        val app = appDir()
        val resolver = File(app, "src/main/java/com/bncam/core/quality/LibpatcherProfileResolver.kt").readText()
        assertTrue(resolver.contains("add(i(ProfileIspKeys.SPECTRA_ENABLED, 0))"))
        assertTrue(resolver.contains("add(f(ProfileIspKeys.SPECTRA_DYNAMIC_ISO))"))
    }

    @Test
    fun `spectra characters atomically write existing controls without a preset key`() {
        val app = appDir()
        val ui = File(app, "src/main/java/com/bncam/ui/screens/settings/lens_profiles/ProfileTuningSubScreens.kt").readText()
        val repo = File(app, "src/main/java/com/bncam/data/settings/SettingsRepository.kt").readText()
        val keys = File(app, "src/main/java/com/bncam/data/settings/ProfileLensTuningSettings.kt").readText()
        assertTrue(ui.contains("SPECTRA · Context Fusion"))
        assertTrue(ui.contains("repo.setProfileOverrideBatch"))
        assertTrue(repo.contains("suspend fun setProfileOverrideBatch"))
        assertTrue(ui.contains("SpectraProfileCharacters.infer"))
        assertTrue(!keys.contains("SPECTRA_CHARACTER"))
    }
}
