package com.bncam.core.capture

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Phase8HdrUiSingleSourceTest {
    private val appDir: File = sequenceOf(File("."), File("app"))
        .firstOrNull { File(it, "src/main/java/com/bncam/data/settings/SettingsRepository.kt").isFile }
        ?: error("Cannot locate app module")

    @Test fun `app settings and quick overlay share the repository hdr state`() {
        val repository = File(appDir, "src/main/java/com/bncam/data/settings/SettingsRepository.kt").readText()
        val appSettings = File(appDir, "src/main/java/com/bncam/ui/screens/settings/AppSettingsScreen.kt").readText()
        val camera = File(appDir, "src/main/java/com/bncam/ui/screens/capture/CameraScreen.kt").readText()

        assertTrue(repository.contains("computationalHdrEnabledFlow"))
        assertTrue(repository.contains("setComputationalHdrEnabled"))
        assertTrue(appSettings.contains("repository.computationalHdrEnabledFlow.collectAsState(initial = false)"))
        assertTrue(appSettings.contains("repository.setComputationalHdrEnabled(enabled)"))
        assertTrue(camera.contains("repository.computationalHdrEnabledFlow.collectAsState(initial = false)"))
        assertTrue(camera.contains("hdrEnabled = computationalHdrEnabled"))
        assertTrue(camera.contains("repository.setComputationalHdrEnabled(enabled)"))
        assertFalse(camera.contains("hdrEnabled = null"))
        assertFalse(camera.contains("onHdrEnabledChange = null"))
    }

    @Test fun `settings disclose raw and dng applicability rather than pretending hdr touches raw truth`() {
        val appSettings = File(appDir, "src/main/java/com/bncam/ui/screens/settings/AppSettingsScreen.kt").readText()
        assertTrue(appSettings.contains("RAW-only has no computational JPEG target"))
        assertTrue(appSettings.contains("DNG remains the pristine anchor frame"))
        assertTrue(appSettings.contains("Deliberate same/similar-exposure RAW stacking"))
        assertTrue(appSettings.contains("HDR Enhanced frames"))
    }
}
