package com.bncam.ui.screens.capture

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RawPreviewFixedResolutionSourceContractTest {
    private fun appRoot(): File {
        val cwd = File(System.getProperty("user.dir"))
        return if (File(cwd, "src/main").isDirectory) cwd else File(cwd, "app")
    }

    @Test
    fun `runtime pressure drops stale frames instead of lowering raw preview resolution`() {
        val renderer = File(
            appRoot(),
            "src/main/java/com/bncam/ui/screens/capture/RawPreviewRenderer.kt"
        ).readText()

        assertTrue(renderer.contains("val targetMaxWidth = PREVIEW_MAX_WIDTH"))
        assertTrue(renderer.contains("val targetMaxHeight = PREVIEW_MAX_HEIGHT"))
        assertTrue(renderer.contains("resolutionPolicy=FIXED_QUALITY"))
        assertTrue(renderer.contains("RAW_PREVIEW_DROPPED_BUSY"))
        assertFalse(renderer.contains("adaptiveResolutionScale"))
        assertFalse(renderer.contains("RAW_PREVIEW_ADAPTIVE_RESOLUTION"))
        assertFalse(renderer.contains("RAW_PREVIEW_FAILURE_PRESSURE"))
    }
}
