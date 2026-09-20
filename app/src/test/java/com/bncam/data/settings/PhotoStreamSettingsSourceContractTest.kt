package com.bncam.data.settings

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhotoStreamSettingsSourceContractTest {
    private fun appRoot(): File = sequenceOf(File("."), File("app"))
        .firstOrNull { File(it, "src/main/java/com/bncam/ui/screens/settings/lens_profiles/LensDetailScreen.kt").isFile }
        ?: error("Cannot locate app module from ${File(".").absolutePath}")

    private fun source(path: String): String = File(appRoot(), path).readText()

    @Test
    fun `stream configuration entry stays directly after color matrix`() {
        val lensDetail = source("src/main/java/com/bncam/ui/screens/settings/lens_profiles/LensDetailScreen.kt")
        val colorMatrix = lensDetail.indexOf("title = \"Color matrix\"")
        val streamConfiguration = lensDetail.indexOf("title = \"Stream configuration\"")

        assertTrue(colorMatrix >= 0)
        assertTrue(streamConfiguration > colorMatrix)
        assertTrue(lensDetail.contains("onClick = onNavigateToRawStreamBinding"))
        assertTrue(lensDetail.contains("Photo resolution policy"))
    }

    @Test
    fun `screen exposes only runtime effective controls and no candidate mode selector`() {
        val screen = source("src/main/java/com/bncam/ui/screens/settings/lens_profiles/RawStreamBindingSettingsScreen.kt")

        assertTrue(screen.contains("Specific RAW resolution"))
        assertTrue(screen.contains("Resolution Fix reference"))
        assertTrue(screen.contains("RAW viewfinder support"))
        assertTrue(screen.contains("PhotoStreamSettingsStore"))
        assertFalse(screen.contains("StreamConfigurationMode"))
        assertFalse(screen.contains("ValidatedCandidateSection"))
        assertFalse(screen.contains("StreamModeDialog"))
        assertFalse(screen.contains("CameraStreamCandidate"))
    }

    @Test
    fun `new settings live in per lens v2 hardware namespace`() {
        val store = source("src/main/java/com/bncam/data/settings/PhotoStreamSettingsStore.kt")

        assertTrue(store.contains("hardware_lens_${'$'}{lensPart}_stream_v2_"))
        assertTrue(store.contains("SettingsRepository.safeLensKeyPart(lensId)"))
    }

    @Test
    fun `legacy mode and candidate settings sources are deleted`() {
        val root = appRoot()
        assertFalse(File(root, "src/main/java/com/bncam/data/settings/StreamConfigurationSettings.kt").exists())
        assertFalse(File(root, "src/main/java/com/bncam/data/settings/StreamConfigurationSettingsStore.kt").exists())
    }

    @Test
    fun `capability screen does not enumerate synthetic stream candidates`() {
        val catalog = source("src/main/java/com/bncam/core/engine/CameraStreamCapabilityCatalog.kt")

        assertFalse(catalog.contains("data class CameraStreamCandidate("))
        assertFalse(catalog.contains("CameraStreamCandidateClass"))
        assertFalse(catalog.contains("buildPhotoCandidates("))
        assertFalse(catalog.contains("buildVideoCandidates("))
        assertFalse(catalog.contains("val candidates:"))
        assertTrue(catalog.contains("CameraCapabilityInventoryFactory.fromCharacteristics("))
    }

}
