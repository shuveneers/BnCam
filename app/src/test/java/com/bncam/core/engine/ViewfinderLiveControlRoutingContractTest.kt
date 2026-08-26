package com.bncam.core.engine

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ViewfinderLiveControlRoutingContractTest {
    private val manager = File("src/main/java/com/bncam/core/engine/BnCameraManager.kt").readText()

    @Test
    fun `focus and zoom reuse immutable camera characteristics during live drags`() {
        assertTrue(manager.contains("liveControlCharacteristicsCache"))
        assertTrue(manager.contains("fun liveControlCharacteristics(cameraId: String)"))

        val focus = manager.substringAfter("private fun setFocusOwned(")
            .substringBefore("// NIEUW: Veilige Digitale Zoom Functie")
        assertTrue(focus.contains("liveControlCharacteristics(cameraId)"))
        assertFalse(focus.contains("cameraManager.getCameraCharacteristics(cameraId)"))

        val zoom = manager.substringAfter("private fun setZoomOwned(")
            .substringBefore("// LIVE FLITSER CONTROLS")
        assertTrue(zoom.contains("liveControlCharacteristics(zoomGeometryCameraId)"))
        assertFalse(zoom.contains("cameraManager.getCameraCharacteristics(zoomGeometryCameraId)"))
    }
    @Test
    fun `metering and exposure policies reuse the live control characteristics cache`() {
        val metering = manager.substringAfter("fun applyMeteringPolicy(builder: CaptureRequest.Builder)")
            .substringBefore("private fun resolveExposurePlan")
        assertTrue(metering.contains("liveControlCharacteristics(deviceId)"))
        assertFalse(metering.contains("cameraManager.getCameraCharacteristics(deviceId)"))

        val exposure = manager.substringAfter("private fun applyExposurePolicy(builder: CaptureRequest.Builder)")
            .substringBefore("fun setFocus(focusFraction")
        assertTrue(exposure.contains("liveControlCharacteristics(deviceId)"))
        assertFalse(exposure.contains("cameraManager.getCameraCharacteristics(deviceId)"))
    }

}
