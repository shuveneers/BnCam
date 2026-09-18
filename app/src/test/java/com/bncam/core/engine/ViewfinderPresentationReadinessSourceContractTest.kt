package com.bncam.core.engine

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ViewfinderPresentationReadinessSourceContractTest {
    private fun appRoot(): File = sequenceOf(File("."), File("app"))
        .firstOrNull { File(it, "src/main/java/com/bncam/core/engine/BnCameraManager.kt").isFile }
        ?: error("Cannot locate app module from ${File(".").absolutePath}")

    private fun source(path: String): String = File(appRoot(), path).readText()

    @Test
    fun `raw route requires first actual EGL presentation`() {
        val health = source("src/main/java/com/bncam/ui/screens/capture/RawPreviewHealthMonitor.kt")

        assertTrue(health.contains("firstGlDrawElapsedNs"))
        assertTrue(health.contains("!actualPresentationObserved && firstGlDrawElapsedNs > 0L"))
        assertTrue(health.contains("return RawPreviewHealthStage.EGL_PRESENTATION"))
    }

    @Test
    fun `staged raw recovery may repair GL without blanking retained YUV`() {
        val view = source("src/main/java/com/bncam/ui/screens/capture/FocusPeakingView.kt")

        assertTrue(view.contains("val stagedMatch = stagedTargetSource != null && stagedTargetSource != ViewfinderEffectiveSource.YUV"))
        assertTrue(view.contains("if (glDisplayed) displayReady = false"))
        assertTrue(view.contains("rebuilding RAW textures must never blank that healthy YUV texture"))
    }

    @Test
    fun `yuv recovery is SurfaceTexture local and never owns Camera2`() {
        val view = source("src/main/java/com/bncam/ui/screens/capture/FocusPeakingView.kt")
        val start = view.indexOf("private fun requestYuvDisplayRecoveryInternal(")
        val end = view.indexOf("fun setRawPreviewMirrored", start)
        assertTrue(start >= 0 && end > start)
        val recovery = view.substring(start, end)

        assertTrue(recovery.contains("setOnFrameAvailableListener(this@FocusPeakingView)"))
        assertTrue(recovery.contains("texture.updateTexImage()"))
        assertTrue(recovery.contains("onYuvFrameAvailable?.invoke(afterTimestampNs)"))
        assertFalse(recovery.contains("createCaptureSession"))
        assertFalse(recovery.contains("softResetPipeline"))
        assertFalse(recovery.contains("closeCamera"))
    }

    @Test
    fun `manager keeps yuv presentation recovery separate from stream fallback`() {
        val manager = source("src/main/java/com/bncam/core/engine/BnCameraManager.kt")
        val start = manager.indexOf("private fun recoverPendingYuvPresentationIfNeeded(")
        val end = manager.indexOf("private fun stopWarmBufferWatchdog", start)
        assertTrue(start >= 0 && end > start)
        val recovery = manager.substring(start, end)

        assertTrue(recovery.contains("FocusPeakingView.requestYuvDisplayRecovery"))
        assertTrue(recovery.contains("cameraSessionUntouched=true"))
        assertTrue(recovery.contains("captureAlive"))
        assertTrue(recovery.contains("imageReaderAlive"))
        assertFalse(recovery.contains("advanceStreamRuntimeFallback"))
        assertFalse(recovery.contains("softResetPipeline("))
        assertFalse(recovery.contains("createCaptureSession"))
    }
}
