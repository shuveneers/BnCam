package com.bncam.core.engine

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MainThreadHygieneSourceContractTest {
    private fun appRoot(): File = sequenceOf(File("."), File("app"))
        .firstOrNull { File(it, "src/main/java/com/bncam/core/engine/BnCameraManager.kt").isFile }
        ?: error("Cannot locate app module")

    private fun source(relative: String): String = File(appRoot(), relative).readText()

    @Test
    fun `volume key path uses cached setting and never blocks on DataStore`() {
        val activity = source("src/main/java/com/bncam/MainActivity.kt")
        val onKeyDown = activity.substring(activity.indexOf("override fun onKeyDown"))

        assertTrue(activity.contains("volumeButtonActionFlow.collectLatest"))
        assertTrue(onKeyDown.contains("val action = volumeButtonAction"))
        assertFalse(onKeyDown.contains("runBlocking"))
        assertFalse(onKeyDown.contains(".first()"))
    }

    @Test
    fun `raw preview idle barrier suspends instead of Future get`() {
        val renderer = source("src/main/java/com/bncam/ui/screens/capture/RawPreviewRenderer.kt")

        assertTrue(renderer.contains("suspend fun pauseAndAwaitIdle"))
        assertTrue(renderer.contains("withTimeoutOrNull(timeoutMs)"))
        assertTrue(renderer.contains("idleBarrier.await()"))
        val idleFunction = renderer.substring(renderer.indexOf("suspend fun pauseAndAwaitIdle"))
        assertFalse(idleFunction.contains(".get(timeoutMs"))
        assertFalse(idleFunction.contains("Future.get"))
    }

    @Test
    fun `interactive Camera2 controls are serialized on camera background epoch`() {
        val manager = source("src/main/java/com/bncam/core/engine/BnCameraManager.kt")

        assertTrue(manager.contains("private fun enqueuePreviewControl("))
        assertTrue(manager.contains("command.generation != pipelineGeneration"))
        assertTrue(manager.contains("command.sessionEpoch != activeConfiguredSessionEpoch"))
        assertTrue(manager.contains("activeConfiguredSessionEpoch = sessionEpoch"))
        assertTrue(manager.contains("activeConfiguredSessionEpoch = -1L"))
        assertTrue(
            manager.indexOf("currentCaptureRequest = requestBuilder") >
                manager.indexOf("override fun onConfigured(session: CameraCaptureSession)")
        )
        assertTrue(manager.contains("previewControlDrainHandler !== ownerHandler"))
        assertTrue(manager.contains("enqueuePreviewControl(\"digital_zoom\""))
        assertTrue(manager.contains("enqueuePreviewControl(\"focus_distance\""))
        assertTrue(manager.contains("enqueuePreviewControl(\"tap_focus\""))
        assertTrue(manager.contains("enqueuePreviewControl(\"preview_policy\""))
        assertFalse(manager.contains("runBlocking"))
    }

    @Test
    fun `camera transitions leave Compose dispatcher before hardware work`() {
        val screen = source("src/main/java/com/bncam/ui/screens/capture/CameraScreen.kt")

        assertTrue(screen.contains("withContext(kotlinx.coroutines.Dispatchers.IO)"))
        assertTrue(screen.contains("bnCameraManager.startCameraAndZsl("))
        assertTrue(screen.contains("bnCameraManager.softResetPipeline("))
        assertFalse(screen.contains("while (isFirstLaunch)"))
        assertFalse(screen.contains("delay(100)\n                bnCameraManager.softResetPipeline"))
    }

    @Test
    fun `settings camera metadata probes are off main`() {
        val viewfinder = source("src/main/java/com/bncam/ui/screens/settings/ViewfinderScreen.kt")
        val blackLevel = source("src/main/java/com/bncam/ui/screens/settings/lens_profiles/BlackLevelSettingsScreen.kt")
        val rawBinding = source("src/main/java/com/bncam/ui/screens/settings/lens_profiles/RawStreamBindingSettingsScreen.kt")
        val profile = source("src/main/java/com/bncam/ui/screens/settings/lens_profiles/ProfileEditScreen.kt")
        val lens = source("src/main/java/com/bncam/ui/screens/settings/lens_profiles/LensDetailScreen.kt")

        assertTrue(viewfinder.contains("withContext(Dispatchers.IO)"))
        assertTrue(blackLevel.contains("withContext(Dispatchers.IO) { readLensStaticSensorInfo"))
        assertTrue(rawBinding.contains("withContext(Dispatchers.IO) { enumerateCameraOutputFormatCodes"))
        assertTrue(profile.contains("withContext(Dispatchers.IO) { scanFrameSourceSupport"))
        assertTrue(lens.contains("withContext(Dispatchers.IO) { resolveLensHardwareSummary"))
    }

    @Test
    fun `interactive import export file IO is dispatched away from main`() {
        val config = source("src/main/java/com/bncam/ui/screens/settings/ConfigScreen.kt")
        val profileTransfer = source("src/main/java/com/bncam/ui/screens/settings/lens_profiles/ProfileTuningSubScreens.kt")
        val interop = source("src/main/java/com/bncam/ui/screens/capture/RawPreviewInteropCapabilities.kt")

        assertTrue(config.contains("withContext(Dispatchers.IO)"))
        assertTrue(profileTransfer.contains("withContext(Dispatchers.IO)"))
        assertTrue(interop.contains("reportScope.launch"))
        assertTrue(interop.contains("SupervisorJob() + Dispatchers.IO"))
    }
}
