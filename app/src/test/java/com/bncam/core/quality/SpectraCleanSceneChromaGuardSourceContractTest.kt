package com.bncam.core.quality

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class SpectraCleanSceneChromaGuardSourceContractTest {
    private fun appDir(): File = sequenceOf(File("."), File("app"))
        .firstOrNull { File(it, "src/main/cpp/IspCore.cpp").isFile }
        ?: error("Cannot locate app module")

    @Test
    fun `spectra pre wb corrections cannot create a cloud in clean scenes`() {
        val isp = File(appDir(), "src/main/cpp/IspCore.cpp").readText()
        assertTrue(isp.contains("spectraPreWbNoiseAuthority"))
        assertTrue(isp.contains("isoState.combinedNoisePressure"))
        assertTrue(isp.contains("BYPASSED_CLEAN_SCENE_LOW_NOISE_PRESSURE"))
        assertTrue(isp.contains("preWbChromaCleanupRiskR * spectraPreWbNoiseAuthority"))
        assertTrue(isp.contains("preWbChromaCleanupRiskB * spectraPreWbNoiseAuthority"))
    }
}
