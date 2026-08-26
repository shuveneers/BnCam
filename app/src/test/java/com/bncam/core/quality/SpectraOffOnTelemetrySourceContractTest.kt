package com.bncam.core.quality

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpectraOffOnTelemetrySourceContractTest {
    @Test
    fun `physical model and spectra processing are logged separately`() {
        val isp = File("src/main/cpp/IspCore.cpp").readText()
        val calibration = File("src/main/cpp/SpectraNoiseCalibration.h").readText()
        val backend = File("src/main/cpp/vulkan/VulkanSpectraResidentDemosaicBackend.cpp").readText()
        val shader = File("src/main/cpp/vulkan/shaders/spectra_demosaic_resident.comp").readText()

        assertTrue(isp.contains(";physicalNoiseModelMode="))
        assertTrue(isp.contains(";spectraProcessingMode="))
        assertFalse(isp.contains(";spectraNativeMode="))
        assertTrue(calibration.contains("CLOUD_CORRECTION_PLAN_READY_FOR_PRE_WB_APPLICATION"))
        assertFalse(calibration.contains("CLOUD_CORRECTION_PLAN_READY_NOT_APPLIED"))
        assertTrue(backend.contains("result.cloudCorrectionApplied = result.success && cloudMapContractValid"))
        assertTrue(shader.contains("preWbCloudCorrect"))
    }
}
