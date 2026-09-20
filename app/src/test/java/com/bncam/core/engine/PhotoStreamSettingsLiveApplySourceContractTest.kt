package com.bncam.core.engine

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhotoStreamSettingsLiveApplySourceContractTest {
    private fun appRoot(): File = sequenceOf(File("."), File("app"))
        .firstOrNull { File(it, "src/main/java/com/bncam/core/engine/BnCameraManager.kt").isFile }
        ?: error("Cannot locate app module from ${File(".").absolutePath}")

    private fun source(path: String): String = File(appRoot(), path).readText()

    @Test
    fun `runtime effective photo settings use serialized soft reset authority`() {
        val manager = source("src/main/java/com/bncam/core/engine/BnCameraManager.kt")

        assertTrue(manager.contains("bindActivePhotoStreamSettingsObserver(cameraId)"))
        assertTrue(manager.contains("PhotoStreamSettingsStore(appContext)"))
        assertTrue(manager.contains("PhotoStreamRuntimeFingerprint.create("))
        assertTrue(manager.contains("requestActivePhotoStreamSettingsRefresh("))
        assertTrue(manager.contains("softResetPipeline("))
        assertTrue(manager.contains("forceSessionRebuild = false"))
        assertTrue(manager.contains("PHOTO_STREAM_SETTINGS_CHANGED"))
        assertTrue(manager.contains("PHOTO_STREAM_SETTINGS_REFRESH_REQUEST"))
        assertTrue(manager.contains("stopActivePhotoStreamSettingsObserver(\"camera_close:${'$'}reason\")"))
    }

    @Test
    fun `first observer emission is baseline only`() {
        val manager = source("src/main/java/com/bncam/core/engine/BnCameraManager.kt")
        val observer = manager.substringAfter("private fun bindActivePhotoStreamSettingsObserver")
            .substringBefore("private fun stopActivePhotoStreamSettingsObserver")

        assertTrue(observer.contains("var previousSignature: String? = null"))
        assertTrue(observer.contains("if (previous == null)"))
        assertTrue(observer.contains("return@collectLatest"))
        assertFalse(observer.contains("StreamConfigurationMode"))
    }

    @Test
    fun `custom raw binding is explicit and no manual mode gate remains`() {
        val manager = source("src/main/java/com/bncam/core/engine/BnCameraManager.kt")
        val binding = manager.substringAfter("private fun resolveCustomRawPreviewBinding")
            .substringBefore("fun resolveCameraDeviceRoute")

        assertTrue(binding.contains("customBindingAllowed"))
        assertTrue(binding.contains("ViewfinderStream.SELECTED_BUFFER"))
        assertTrue(binding.contains("CUSTOM_VENDOR_UNVERIFIED"))
        assertFalse(binding.contains("manualStreamConfigurationActive"))
        assertFalse(manager.contains("StreamConfigurationMode.MANUAL"))
    }
}
