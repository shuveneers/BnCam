package com.bncam.data.settings

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamConfigurationSourceContractTest {
    private fun appRoot(): File = sequenceOf(File("."), File("app"))
        .firstOrNull { File(it, "src/main/java/com/bncam/ui/screens/settings/lens_profiles/LensDetailScreen.kt").isFile }
        ?: error("Cannot locate app module from ${File(".").absolutePath}")

    private fun source(path: String): String = File(appRoot(), path).readText()

    @Test
    fun `stream configuration entry is directly after color matrix`() {
        val lensDetail = source("src/main/java/com/bncam/ui/screens/settings/lens_profiles/LensDetailScreen.kt")
        val colorMatrix = lensDetail.indexOf("title = \"Color matrix\"")
        val streamConfiguration = lensDetail.indexOf("title = \"Stream configuration\"")

        assertTrue(colorMatrix >= 0)
        assertTrue(streamConfiguration > colorMatrix)
        assertTrue(lensDetail.substring(colorMatrix, streamConfiguration).count { it == '}' } < 4)
        assertTrue(lensDetail.contains("onClick = onNavigateToRawStreamBinding"))
    }

    @Test
    fun `existing raw stream route now opens stream configuration authority`() {
        val navigation = source("src/main/java/com/bncam/ui/navigation/AppNavigation.kt")
        val screen = source("src/main/java/com/bncam/ui/screens/settings/lens_profiles/RawStreamBindingSettingsScreen.kt")

        assertTrue(navigation.contains("Routes.LENS_RAW_STREAM_BINDING"))
        assertTrue(navigation.contains("RawStreamBindingSettingsScreen("))
        assertTrue(screen.contains("SettingsTopicScaffold(\"Stream Configuration\""))
        assertTrue(screen.contains("StreamConfigurationClass.PHOTO"))
        assertTrue(screen.contains("StreamConfigurationClass.VIDEO"))
    }

    @Test
    fun `stream settings share the per lens hardware reset namespace`() {
        val store = source("src/main/java/com/bncam/data/settings/StreamConfigurationSettingsStore.kt")
        val repository = source("src/main/java/com/bncam/data/settings/SettingsRepository.kt")

        assertTrue(store.contains("hardware_lens_\${lensPart}_stream_v1_"))
        assertTrue(repository.contains("val prefix = \"hardware_lens_\${safeLensKeyPart(lensId)}_\""))
    }

    @Test
    fun `logical parent fallback cannot masquerade as physical sensor reported candidates`() {
        val scanner = source("src/main/java/com/bncam/core/engine/CameraStreamCapabilityCatalog.kt")

        assertTrue(scanner.contains("CameraStreamCatalogSource.LOGICAL_PARENT_FALLBACK"))
        assertTrue(scanner.contains("it.validationStatus == StreamCandidateValidationStatus.SESSION_VALIDATED"))
        assertTrue(scanner.contains("Video preflight is not implemented yet"))
    }
}
