package com.bncam.core.quality

import java.io.File
import org.junit.Test
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

class Phase3LiveWhiteBalanceSourceContractTest {
    @Test
    fun `live wb drag bypasses camera2 rebuild and updates render targets directly`() {
        val manager = File("src/main/java/com/bncam/core/engine/BnCameraManager.kt").readText()
        val renderer = File("src/main/java/com/bncam/ui/screens/capture/RawPreviewRenderer.kt").readText()
        val screen = File("src/main/java/com/bncam/ui/screens/capture/CameraScreen.kt").readText()

        assertTrue(renderer.contains("fun updateWhiteBalanceColorPair(gains: FloatArray?, colorMatrix: FloatArray?)"))
        assertTrue(renderer.contains("liveWhiteBalanceColorPair.get()"))
        assertTrue(manager.contains("rawPreviewRenderer.updateWhiteBalanceColorPair(targetSensorGains, targetColorMatrix)"))
        assertTrue(manager.contains("lastLiveWhiteBalanceDisplayUpdateMs < 33L"))
        assertTrue(manager.contains("liveWhiteBalanceResolutionJob?.cancel()"))
        assertTrue(screen.contains("bnCameraManager.setViewfinderWhiteBalance("))
        assertTrue(screen.contains("LaunchedEffect(activeLens.id)"))
        assertTrue(screen.contains("liveKelvin = ViewfinderLiveTuning.snapshot().whiteBalanceKelvin"))
        assertTrue(screen.contains("pushLiveWhiteBalanceToPreview()"))
        assertFalse(screen.contains("activeProfileAwbSettings"))
        val wbFunction = manager.substring(
            manager.indexOf("fun setViewfinderWhiteBalance("),
            manager.indexOf("fun setLiveWhiteBalanceKelvin(")
        )
        assertFalse(wbFunction.contains("updatePreviewRepeatingRequest"))
        assertFalse(wbFunction.contains("createCaptureSession"))
    }
}
