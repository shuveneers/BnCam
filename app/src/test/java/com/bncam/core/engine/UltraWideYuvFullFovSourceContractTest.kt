package com.bncam.core.engine

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UltraWideYuvFullFovSourceContractTest {
    private fun appRoot(): File {
        val cwd = File(System.getProperty("user.dir"))
        return if (File(cwd, "src/main").isDirectory) cwd else File(cwd, "app")
    }

    private fun source(relative: String): String = File(appRoot(), relative).readText()

    @Test
    fun `yuv pipeline cannot let cropped recommended streams hide standard full fov geometry`() {
        val manager = source("src/main/java/com/bncam/core/engine/BnCameraManager.kt")

        assertTrue(manager.contains("CameraStreamGeometryPolicy.prioritizedFullFovPool"))
        assertTrue(manager.contains("STANDARD_YUV_FULL_FOV_FALLBACK"))
        assertFalse(
            "YUV must not blindly replace the standard size list with PREVIEW recommendations",
            manager.contains("sizes = recSizes.toTypedArray()")
        )
    }

    @Test
    fun `surface texture retention is constrained to current lens full fov pool`() {
        val screen = source("src/main/java/com/bncam/ui/screens/capture/CameraScreen.kt")

        assertTrue(screen.contains("val previewCandidateSizes = if (fullFovKeys.isNotEmpty())"))
        assertTrue(
            screen.contains(
                "previewCandidateSizes.any { it.width == width && it.height == height }"
            )
        )
        assertFalse(
            "An arbitrary advertised size can retain a cropped main-lens geometry on ultra-wide",
            screen.contains("sizes?.any { it.width == width && it.height == height } == true")
        )
    }

    @Test
    fun `yuv full fov stream duration bounds fps before generic 60 fps preference`() {
        val manager = source("src/main/java/com/bncam/core/engine/BnCameraManager.kt")
        val fpsPolicy = manager.substringAfter("private fun applyOptimalAeTargetFpsRange")
            .substringBefore("private fun submitRepeatingRequestWithProvenance")

        assertTrue(fpsPolicy.contains("identity?.bufferFormat == ImageFormat.YUV_420_888"))
        assertTrue(
            fpsPolicy.contains(
                "getOutputMinFrameDuration(ImageFormat.YUV_420_888, selectedSize)"
            )
        )
        assertTrue(fpsPolicy.contains("android.graphics.SurfaceTexture::class.java"))
        assertTrue(fpsPolicy.contains("val minimumFrameDurationNs = maxOf("))
        assertTrue(fpsPolicy.contains("strategy=YUV_FULL_FOV_STREAM_MIN_FRAME_DURATION"))
        assertTrue(
            fpsPolicy.indexOf("strategy=YUV_FULL_FOV_STREAM_MIN_FRAME_DURATION") <
                fpsPolicy.indexOf("val variable60Range")
        )
    }
}
