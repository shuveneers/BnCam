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

        assertTrue(manager.contains("bindActiveStreamConfigurationObserver(cameraId)"))
        assertTrue(manager.contains("StreamConfigurationSettingsStore(appContext)"))
        assertTrue(manager.contains("StreamConfigurationRuntimeFingerprint.photo("))
        assertTrue(manager.contains("requestActiveStreamConfigurationRefresh("))
        assertTrue(manager.contains("softResetPipeline("))
        assertTrue(manager.contains("forceSessionRebuild = false"))
        assertTrue(manager.contains("STREAM_CONFIGURATION_PREFERENCE_CHANGED"))
        assertTrue(manager.contains("STREAM_CONFIGURATION_REFRESH_REQUEST"))
        assertTrue(manager.contains("stopActiveStreamConfigurationObserver(\"camera_close:\$reason\")"))
    }

    @Test
    fun `first observer emission is baseline only and video is not a live photo owner`() {
        val manager = source("src/main/java/com/bncam/core/engine/BnCameraManager.kt")
        val model = source("src/main/java/com/bncam/data/settings/StreamConfigurationSettings.kt")
        val observer = manager.substringAfter("private fun bindActiveStreamConfigurationObserver")
            .substringBefore("private fun stopActiveStreamConfigurationObserver")

        assertTrue(observer.contains("var previousSignature: String? = null"))
        assertTrue(observer.contains("if (previous == null)"))
        assertTrue(observer.contains("return@collectLatest"))
        assertTrue(model.contains("fun photo("))
        assertFalse(observer.contains("settings.video"))
    }

    @Test
    fun `manual custom raw surface refresh is isolated from normal viewfinder switches`() {
        val manager = source("src/main/java/com/bncam/core/engine/BnCameraManager.kt")
        val configure = manager.substringAfter("fun configureViewfinderStream(")
            .substringBefore("fun setViewfinderStreamDirect")

        assertTrue(configure.contains("manualCustomRawPreviewBindingConfigured || customBindingWasActive"))
        assertTrue(configure.contains("MANUAL_RAW_BINDING_VIEWFINDER_"))
        assertFalse(configure.contains("forceSessionRebuild = true"))
    }
}
