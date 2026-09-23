package com.bncam.core.quality

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PhysicalLensParitySourceContractTest {
    private fun appDir(): File = sequenceOf(File("."), File("app"))
        .firstOrNull {
            File(it, "src/main/java/com/bncam/core/engine/BnCameraManager.kt").isFile
        }
        ?: error("Unable to locate app module")

    private fun source(path: String): String = File(appDir(), path).readText()

    @Test
    fun `raw metadata support is resolved from active physical sensor`() {
        val manager = source("src/main/java/com/bncam/core/engine/BnCameraManager.kt")

        assertTrue("val rawMetadataCharacteristicsId = synchronized(pipelineLock)" in manager)
        assertTrue("identity.physicalCameraId ?: identity.logicalCameraId" in manager)
        assertTrue("getCachedCameraCharacteristics(rawMetadataCharacteristicsId)" in manager)
        assertTrue("SENSOR_INFO_LENS_SHADING_APPLIED" in manager)
        assertTrue("characteristics = chars" in manager)
        assertFalse(
            "characteristics = cameraManager.getCameraCharacteristics(camera.id)," in
                manager.substringAfter("RawMetadataCaptureRequestPolicy.applyHotPixelMapRequest(")
                    .substringBefore("HARDWARE/SESSION SETTINGS")
        )
    }

    @Test
    fun `raw domain keeps frozen calibration physical id`() {
        val contract = source("src/main/java/com/bncam/core/isp/raw/RawDomainContract.kt")

        assertTrue("val physicalCameraId = finalCal?.base?.physicalCameraId" in contract)
        assertFalse("physicalCameraResults\n            ?.keys\n            ?.firstOrNull()" in contract)
    }

    @Test
    fun `exact frame Camera2 ccm has dedicated outlier gate before forward matrix fallback`() {
        val calibration = source("src/main/java/com/bncam/core/quality/SensorCalibration.kt")
        val engine = source("src/main/java/com/bncam/core/quality/RawColorTransformEngine.kt")

        assertTrue("validateExactFrameCamera2ColorTransform(values)" in calibration)
        assertTrue("sourcePriority = 2" in calibration)
        assertTrue("sourcePriority = 3" in calibration)
        assertTrue("camera2_direct_neutral_axis_outlier" in engine)
        assertTrue("camera2_direct_coefficient_outlier" in engine)
        assertTrue("camera2_direct_excessive_row_cancellation" in engine)
    }
}
