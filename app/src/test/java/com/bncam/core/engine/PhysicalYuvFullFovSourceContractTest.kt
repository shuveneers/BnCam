package com.bncam.core.engine

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class PhysicalYuvFullFovSourceContractTest {
    private fun appRoot(): File {
        val cwd = File(System.getProperty("user.dir"))
        return if (File(cwd, "src/main").isDirectory) cwd else File(cwd, "app")
    }

    private fun managerSource(): String =
        File(appRoot(), "src/main/java/com/bncam/core/engine/BnCameraManager.kt").readText()

    @Test
    fun `warm yuv request applies physical ultra wide zoom out after vendor injection`() {
        val manager = managerSource()
        val sessionBlock = manager.substringAfter("private fun createCaptureSession(")
            .substringBefore("private fun closeCaptureSession")

        val vendorIndex = sessionBlock.indexOf("VendorInjectionEngine.applyPreparedTags")
        val fovIndex = sessionBlock.indexOf("reason = \"WARM_REPEATING_SESSION\"")
        val meteringIndex = sessionBlock.indexOf("applyMeteringPolicy(requestBuilder)", fovIndex)

        assertTrue(vendorIndex >= 0)
        assertTrue(fovIndex > vendorIndex)
        assertTrue(meteringIndex > fovIndex)
        assertTrue(sessionBlock.contains("applyPhysicalYuvFullFovZoom"))
    }

    @Test
    fun `physical ultra wide digital zoom uses control zoom ratio not second crop`() {
        val manager = managerSource()
        val zoomBlock = manager.substringAfter("private fun setZoomOwned(zoomLevel: Float)")
            .substringBefore("// ========================================================\n    // LIVE FLITSER CONTROLS")

        assertTrue(zoomBlock.contains("physicalYuvDecision.enabled"))
        assertTrue(zoomBlock.contains("CaptureRequest.CONTROL_ZOOM_RATIO, effectiveZoom"))
        assertTrue(zoomBlock.contains("CaptureRequest.SCALER_CROP_REGION, Rect(activeRect)"))
        assertTrue(zoomBlock.contains("PHYSICAL_ULTRA_WIDE_YUV_ZOOM_AND_METERING_UPDATE"))
    }

    @Test
    fun `flash still capture inherits physical ultra wide zoom geometry`() {
        val manager = managerSource()
        val flashBlock = manager.substringAfter("ROBUST ACTIVE FLASH SEQUENCE")
            .substringBefore("shutterTimestampNs = android.os.SystemClock.elapsedRealtimeNanos()")

        assertTrue(flashBlock.contains("reason = \"FLASH_STILL_CAPTURE\""))
        assertTrue(flashBlock.contains("previewBuilder.get(CaptureRequest.CONTROL_ZOOM_RATIO)"))
        assertTrue(flashBlock.contains("applyPhysicalYuvFullFovZoom"))
    }
}
