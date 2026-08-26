package com.bncam.ui.screens.capture

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PostPhase10NightModeIntegrationContractTest {
    private val appDir = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
        .firstOrNull { File(it, "src/main/java/com/bncam/core/engine/BnCameraManager.kt").isFile }
        ?: error("Unable to locate app module")

    private fun read(path: String) = File(appDir, path).readText()

    @Test
    fun `Night is transient multi frame and does not replace the active profile render identity`() {
        val manager = read("src/main/java/com/bncam/core/engine/BnCameraManager.kt")
        val factory = read("src/main/java/com/bncam/core/capture/CaptureRecipeFactory.kt")
        assertTrue("viewfinderMode == ViewfinderMode.NIGHT -> CaptureStrategy.MULTI_FRAME_ZSL" in manager)
        assertTrue("requestedFrameCountOverride = nightCapturePlan?.requestedFrames" in manager)
        assertTrue("val nightOwnsAdaptiveMultiFrame = viewfinderMode == ViewfinderMode.NIGHT" in manager)
        assertTrue("nightOwnsAdaptiveMultiFrame -> \"night_mode_owns_adaptive_multi_frame\"" in manager)
        assertTrue("profileId = activeProfile.id" in manager)
        assertTrue("RenderQualityConfig.snapshotPreferences" in factory)
        assertTrue("profileId = request.profileId" in factory)
    }

    @Test
    fun `Video never silently routes through the still shutter`() {
        val camera = read("src/main/java/com/bncam/ui/screens/capture/CameraScreen.kt")
        assertTrue("viewfinderMode == ViewfinderMode.VIDEO" in camera)
        assertTrue("Video mode is not active yet" in camera)
    }

    @Test
    fun `lifecycle resume preserves the selected session mode`() {
        val camera = read("src/main/java/com/bncam/ui/screens/capture/CameraScreen.kt")
        assertTrue("Lifecycle.Event.ON_RESUME" in camera)
        val observerBlock = camera.substringAfter("DisposableEffect(lifecycleOwner)").substringBefore("// --- Sensor Flow")
        assertFalse("viewfinderMode = ViewfinderMode.PHOTO" in observerBlock)
        assertTrue("Preserve the current session mode" in observerBlock)
    }
}
