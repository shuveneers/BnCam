package com.bncam.core.quality

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SpectraLowFrequencyCorrectionCeilingSourceContractTest {
    @Test
    fun lowFrequencyCeilingIsPhysicalAndFieldAdaptiveOnCpuAndVulkan() {
        val root = File(System.getProperty("user.dir"))
        val policy = File(root, "src/main/cpp/SpectraLowFrequencyCorrectionCeiling.h").readText()
        val isp = File(root, "src/main/cpp/IspCore.cpp").readText()
        val shader = File(root, "src/main/cpp/vulkan/shaders/spectra_chroma_resident.comp").readText()
        assertTrue(policy.contains("2.15f * sigma"))
        assertTrue(policy.contains("0.55f * field"))
        assertTrue(isp.contains("resolveLowFrequencyCorrectionCeiling"))
        assertTrue(shader.contains("2.15 * sigma"))
        assertTrue(shader.contains("0.55 * abs(blotch)"))
        assertTrue(shader.contains("totalCorrectionCeiling"))
    }
}
