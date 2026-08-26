package com.bncam.core.engine

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BehaviorRestorationSourceContractTest {
    private fun appRoot(): File = sequenceOf(File("."), File("app"))
        .firstOrNull { File(it, "src/main/java/com/bncam/core/engine/BnCameraManager.kt").isFile }
        ?: error("Cannot locate app module")

    private fun source(relative: String): String = File(appRoot(), relative).readText()

    @Test
    fun `viewfinder mode enters reset transaction without forcing producer recreation`() {
        val camera = source("src/main/java/com/bncam/ui/screens/capture/CameraScreen.kt")

        assertTrue(camera.contains("normalized.contains(\"RAW_SENSOR\") -> \"RAW_SENSOR\""))
        assertTrue(camera.contains("normalized.contains(\"RAW10\") -> \"RAW10\""))
        assertTrue(camera.contains("else -> \"YUV\""))
        assertTrue(camera.contains("val initialFormat = preferredFormat ?: \"YUV\""))
        assertFalse(camera.contains("preferredFormat ?: activeProfile.captureStrategy.name"))
        assertTrue(camera.contains("val bufferChanged = requestedPipelineKey != activePipelineKey"))
        assertTrue(camera.contains("if (!bufferChanged && !viewfinderStreamChanged)"))
        assertTrue(camera.contains("forceSessionRebuild = false"))
        assertFalse(camera.contains("forceSessionRebuild = viewfinderStreamChanged"))
        assertTrue(camera.contains("VIEWFINDER_STREAM_MODE_CHANGED_"))
        assertTrue(camera.contains("PROFILE_BUFFER_CHANGED_"))
        assertFalse(camera.contains("activeVendorConfigRevision"))
        assertFalse(camera.contains("startupVendorRevisionPending"))
    }

    @Test
    fun `long press profile editor promotes edited profile to active profile`() {
        val navigation = source("src/main/java/com/bncam/ui/navigation/AppNavigation.kt")
        val handler = navigation.substringAfter("onNavigateToProfileSettings = { profileId ->")
            .substringBefore("onCapture =")

        assertTrue(handler.contains("visibleProfiles.firstOrNull { it.id == profileId }"))
        assertTrue(handler.contains("activeProfile = selectedProfile"))
        assertTrue(handler.contains("settingsRepo.setActiveProfileId(selectedProfile.id)"))
        assertTrue(handler.indexOf("activeProfile = selectedProfile") < handler.indexOf("navController.navigate(route)"))
    }

    @Test
    fun `settings to settings transitions never fade persistent viewfinder through`() {
        val navigation = source("src/main/java/com/bncam/ui/navigation/AppNavigation.kt")
        assertTrue(navigation.contains("initialState.destination.route == Routes.CAMERA"))
        assertTrue(navigation.contains("targetState.destination.route == Routes.CAMERA"))
        assertTrue(navigation.contains("slide + fadeIn(animationSpec = tween(300)) else slide"))
        assertTrue(navigation.contains("slide + fadeOut(animationSpec = tween(300)) else slide"))
    }
}
