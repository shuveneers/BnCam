package com.bncam.core.engine

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class P0StabilityConsolidatedSourceContractTest {
    private fun appRoot(): File = sequenceOf(File("."), File("app"))
        .firstOrNull { File(it, "src/main/java/com/bncam/core/engine/BnCameraManager.kt").isFile }
        ?: error("Cannot locate app module")

    private fun source(relative: String): String = File(appRoot(), relative).readText()

    @Test
    fun `settings navigation keeps CameraScreen persistent and does not own Camera2 lifecycle`() {
        val navigation = source("src/main/java/com/bncam/ui/navigation/AppNavigation.kt")

        val persistentHostIndex = navigation.indexOf("CameraScreen(")
        val navHostIndex = navigation.indexOf("NavHost(")
        val cameraRouteIndex = navigation.indexOf("composable(Routes.CAMERA)")
        val settingsRouteIndex = navigation.indexOf("composable(Routes.SETTINGS_MAIN)")

        assertTrue(persistentHostIndex >= 0)
        assertTrue(navHostIndex > persistentHostIndex)
        assertTrue(cameraRouteIndex > navHostIndex)
        assertTrue(settingsRouteIndex > cameraRouteIndex)
        assertFalse(
            navigation.substring(cameraRouteIndex, settingsRouteIndex).contains("CameraScreen(")
        )
        assertFalse(navigation.contains("detachPreviewForInAppNavigation()"))
        assertFalse(navigation.contains("awaitCameraHardwareClosed("))
        assertTrue(navigation.contains("modifier = Modifier.fillMaxSize()"))
        assertTrue(navigation.contains("event=\$event route=\$route elapsedRealtimeNs="))
        assertTrue(navigation.contains("cameraSessionAction=none"))
    }

    @Test
    fun `viewfinder display is quiesced before GL surface texture destruction`() {
        val view = source("src/main/java/com/bncam/ui/screens/capture/FocusPeakingView.kt")
        val detach = view.substring(view.indexOf("override fun onDetachedFromWindow()"))
        assertTrue(detach.indexOf("quiesceForComposeRelease()") < detach.indexOf("super.onDetachedFromWindow()"))
        assertTrue(detach.indexOf("super.onDetachedFromWindow()") < detach.indexOf("surfaceTexture?.release()"))
    }

    @Test
    fun `soft reset and physical sibling handover are serialized`() {
        val manager = source("src/main/java/com/bncam/core/engine/BnCameraManager.kt")
        assertTrue(manager.contains("pipelineTransitionMutex.withLock"))
        assertTrue(manager.contains("private suspend fun performSoftResetPipeline"))
        assertTrue(manager.contains("PIPELINE_RESET_COALESCED"))
        assertTrue(manager.contains("suspend fun handoverToLensWithinOpenLogicalCamera"))
        assertTrue(manager.contains("PipelineTransitionState.LENS_SWITCHING"))
    }

    @Test
    fun `startup and lens state do not intentionally force redundant CameraDevice cycles`() {
        val navigation = source("src/main/java/com/bncam/ui/navigation/AppNavigation.kt")
        val settings = source("src/main/java/com/bncam/data/settings/SettingsRepository.kt")
        val manager = source("src/main/java/com/bncam/core/engine/BnCameraManager.kt")

        assertTrue(navigation.contains("initialCameraRouteReleased"))
        assertTrue(navigation.contains("setActiveLensAndProfile("))
        assertTrue(settings.contains("suspend fun setActiveLensAndProfile"))
        assertTrue(manager.contains("cameraDeviceRetained=true"))
        assertTrue(manager.contains("event=CAMERA_LIFETIME_COUNTS"))
    }

    @Test
    fun `automatic autofocus is not paired with manual infinity actuator command`() {
        val manager = source("src/main/java/com/bncam/core/engine/BnCameraManager.kt")
        assertTrue(manager.contains("idleAfMode == CaptureRequest.CONTROL_AF_MODE_OFF"))
        assertFalse(manager.contains("requestBuilder.set(CaptureRequest.CONTROL_AF_MODE, idleAfMode)\n                requestBuilder.set(CaptureRequest.LENS_FOCUS_DISTANCE, 0f)"))
    }
}
