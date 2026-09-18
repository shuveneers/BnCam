package com.bncam.core.engine

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ViewfinderWhiteBalanceRoutingContractTest {
    private val managerSource = File("src/main/java/com/bncam/core/engine/BnCameraManager.kt").readText()

    @Test
    fun `yuv display compensation follows viewfinder source not capture buffer format`() {
        val function = managerSource.substringAfter("private fun updateLiveWhiteBalanceDisplayCompensation")
            .substringBefore("private fun applyLiveWhiteBalancePolicy")
        assertTrue(function.contains("effectiveViewfinderSource == ViewfinderEffectiveSource.YUV"))
        assertTrue(function.contains("targetViewfinderSource == ViewfinderEffectiveSource.YUV"))
        assertFalse(function.contains("identity.bufferFormat != ImageFormat.YUV_420_888"))
    }

    @Test
    fun `live kelvin is a transient sensor calibrated override and never profile state`() {
        assertTrue(managerSource.contains("liveWhiteBalanceCharacteristics(cameraId)"))
        assertTrue(managerSource.contains("RawColorTransformEngine.computeManualWhiteBalance("))
        assertTrue(managerSource.contains("Live Kelvin is a temporary viewfinder/capture override"))
        assertFalse(managerSource.contains("ProfileAwbModes"))
        assertFalse(managerSource.contains("ProfileAwbSettings"))
    }
    @Test
    fun `live white balance ownership is scoped to the requested lens`() {
        assertTrue(managerSource.contains("liveWhiteBalanceRequestedCameraId"))
        assertTrue(managerSource.contains("liveWhiteBalanceTargetCameraId"))
        assertTrue(managerSource.contains("liveWhiteBalanceCameraMatchesActivePipeline(cameraId)"))
        assertTrue(managerSource.contains("rawPreviewRenderer.updateWhiteBalanceGains(null)"))
        assertTrue(managerSource.contains("!liveWhiteBalanceCameraMatchesIdentity(requestedCameraId, identity)"))
        assertTrue(managerSource.contains("rawLiveWhiteBalanceAppliedCameraId != liveTargetCameraId"))
    }

}
