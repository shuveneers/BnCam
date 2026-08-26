package com.bncam.core.quality

import java.io.File
import org.junit.Test
import org.junit.Assert.assertTrue

class Phase3PhysicalChromaBaselineSourceContractTest {
    private fun appDir(): File = sequenceOf(File("."), File("app"))
        .firstOrNull { File(it, "src/main/cpp/IspCore.cpp").isFile }
        ?: error("Cannot locate app module")

    @Test
    fun `physical chroma baseline remains active with spectra profile off`() {
        val isp = File(appDir(), "src/main/cpp/IspCore.cpp").readText()
        assertTrue(isp.contains("physicalChromaNoiseRequested"))
        assertTrue(isp.contains("meta.calibration.noiseModelMode != 0 && chromaNrStrength > 0.005f"))
        assertTrue(isp.contains("spectraNoiseActive || profileNoiseReductionRequested ||"))
        assertTrue(isp.contains("physicalChromaNoiseRequested;"))
        assertTrue(isp.contains("physicalVisibleChromaRequested"))
        assertTrue(isp.contains("visibleChromaProcessingEnabled"))
        assertTrue(isp.contains("spectraNoiseActive || physicalVisibleChromaRequested"))
        assertTrue(isp.contains("PHYSICAL_NOISE_BASELINE"))
    }
}
