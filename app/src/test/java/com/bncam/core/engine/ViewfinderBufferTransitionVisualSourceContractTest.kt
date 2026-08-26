package com.bncam.core.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ViewfinderBufferTransitionVisualSourceContractTest {
    private val appDir = File(System.getProperty("user.dir"))

    @Test
    fun `visual transition is manager-owned and ends only on exact display commit`() {
        val manager = source("src/main/java/com/bncam/core/engine/BnCameraManager.kt")
        val camera = source("src/main/java/com/bncam/ui/screens/capture/CameraScreen.kt")

        assertTrue(manager.contains("val viewfinderRebuildVisualState = _viewfinderRebuildVisualState.asStateFlow()"))
        assertTrue(manager.contains("beginViewfinderRebuildVisualTransition(resetGeneration, reason)"))
        assertTrue(manager.contains("reason = \"DISPLAY_COMMITTED:\$reason\""))
        assertTrue(manager.contains("abortViewfinderRebuildVisualTransition(\n                                        \"PIPELINE_RESET_FAILED:"))
        assertTrue(camera.contains("viewfinderProducerRebuildBlackTransition"))
        assertTrue(camera.contains("targetValue = if (viewfinderRebuildVisualState.active) 1f else 0f"))
    }

    @Test
    fun `same producer and same display source remains strict no-op`() {
        val camera = source("src/main/java/com/bncam/ui/screens/capture/CameraScreen.kt")
        val softReset = camera.substringAfter("// 2. SOFT RESET")
        assertTrue(softReset.contains("if (!bufferChanged && !viewfinderStreamChanged)"))
        assertTrue(softReset.contains("return@LaunchedEffect"))
        assertFalse(softReset.substringBefore("activePipelineKey = requestedPipelineKey").contains("delay("))
    }

    private fun source(relative: String): String = File(appDir, relative).readText()
}
