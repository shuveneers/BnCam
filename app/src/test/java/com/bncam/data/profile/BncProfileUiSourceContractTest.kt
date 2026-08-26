package com.bncam.data.profile

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BncProfileUiSourceContractTest {
    private fun appDir(): File = sequenceOf(File("."), File("app"))
        .firstOrNull { File(it, "src/main/java/com/bncam/data/profile/BncProfileCodec.kt").isFile }
        ?: error("Cannot locate app module from ${File(".").absolutePath}")

    @Test
    fun `both transfer entry points use one repository-backed bnc profile state`() {
        val app = appDir()
        val config = File(app, "src/main/java/com/bncam/ui/screens/settings/ConfigScreen.kt").readText()
        val profileTransfer = File(
            app,
            "src/main/java/com/bncam/ui/screens/settings/lens_profiles/ProfileTuningSubScreens.kt"
        ).readText()

        listOf(config, profileTransfer).forEach { source ->
            assertTrue(source.contains("ActivityResultContracts.CreateDocument(BncProfileCodec.MIME_TYPE)"))
            assertTrue(source.contains("repo.exportProfileBnc("))
            assertTrue(source.contains("repo.importProfileBnc("))
            assertTrue(source.contains("BuildConfig.VERSION_NAME"))
            assertTrue(source.contains("BncProfileDeviceCapabilities.inspect"))
            assertTrue(source.contains("Save .bnc"))
            assertTrue(source.contains("Load .bnc"))
            assertFalse(source.contains("repo.exportProfileSettingsJson("))
            assertFalse(source.contains("repo.importProfileSettingsJson("))
            assertFalse(source.contains("Save .json"))
            assertFalse(source.contains("Load .json"))
        }
    }

    @Test
    fun `target capability probe is limited to portable frame-source support`() {
        val app = appDir()
        val resolver = File(app, "src/main/java/com/bncam/data/profile/BncProfileDeviceCapabilities.kt").readText()

        assertTrue(resolver.contains("SCALER_STREAM_CONFIGURATION_MAP"))
        assertTrue(resolver.contains("REQUEST_AVAILABLE_CAPABILITIES_RAW"))
        assertTrue(resolver.contains("ImageFormat.YUV_420_888"))
        assertTrue(resolver.contains("ImageFormat.RAW10"))
        assertTrue(resolver.contains("ImageFormat.RAW_SENSOR"))
        assertTrue(resolver.contains("BncTargetCapabilities.UNKNOWN"))
        assertFalse(resolver.contains("blackLevel", ignoreCase = true))
        assertFalse(resolver.contains("sensorCalibration", ignoreCase = true))
    }
}
