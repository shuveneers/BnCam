package com.bncam.core.engine

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamConfigurationLiveApplySourceContractTest {
    private fun appRoot(): File = sequenceOf(File("."), File("app"))
        .firstOrNull { File(it, "src/main/java/com/bncam/core/engine/BnCameraManager.kt").isFile }
        ?: error("Cannot locate app module from ${File(".").absolutePath}")

    private fun source(path: String): String = File(appRoot(), path).readText()

    @Test
    fun `stream settings use existing serialized soft reset authority`() {
        val manager = source("src/main/java/com/bncam/core/engine/BnCameraManager.kt")

        assertTrue(manager.contains("bindActivePhotoStreamSettingsObserver(cameraId)"))
        assertTrue(manager.contains("PhotoStreamSettingsStore(appContext)"))
        assertTrue(manager.contains("PhotoStreamRuntimeFingerprint.create("))
        assertTrue(manager.contains("requestActivePhotoStreamSettingsRefresh("))
        assertTrue(manager.contains("softResetPipeline("))
        assertTrue(manager.contains("forceSessionRebuild = false"))
        assertTrue(manager.contains("PHOTO_STREAM_SETTINGS_CHANGED"))
        assertTrue(manager.contains("PHOTO_STREAM_SETTINGS_REFRESH_REQUEST"))
        assertTrue(manager.contains("stopActivePhotoStreamSettingsObserver(\"camera_close:\$reason\")"))
    }

    @Test
    fun `first observer emission is baseline only and video is not a live photo owner`() {
        val manager = source("src/main/java/com/bncam/core/engine/BnCameraManager.kt")
        val model = source("src/main/java/com/bncam/data/settings/StreamConfigurationSettings.kt")
        val observer = manager.substringAfter("private fun bindActivePhotoStreamSettingsObserver")
            .substringBefore("private fun stopActivePhotoStreamSettingsObserver")

        assertTrue(observer.contains("var previousSignature: String? = null"))
        assertTrue(observer.contains("if (previous == null)"))
        assertTrue(observer.contains("return@collectLatest"))
        assertTrue(model.contains("fun photo("))
        assertFalse(observer.contains("settings.video"))
    }

    @Test
    fun `custom raw support refresh is isolated from normal request graph viewfinder switches`() {
        val manager = source("src/main/java/com/bncam/core/engine/BnCameraManager.kt")
        val configure = manager.substringAfter("fun configureViewfinderStream(")
            .substringBefore("fun setViewfinderStreamDirect")

        assertTrue(configure.contains("customRawPreviewBindingConfigured || customBindingWasActive"))
        assertTrue(configure.contains("CUSTOM_RAW_BINDING_VIEWFINDER_"))
        assertTrue(configure.contains("requestActivePhotoStreamSettingsRefresh("))
        assertFalse(configure.contains("forceSessionRebuild = true"))
    }
}
