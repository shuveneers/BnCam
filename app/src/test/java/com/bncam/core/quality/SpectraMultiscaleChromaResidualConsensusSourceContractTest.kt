package com.bncam.core.quality

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SpectraMultiscaleChromaResidualConsensusSourceContractTest {
    @Test
    fun pass2FineUsesActualMultiscaleOpponentResidualConsensus() {
        val root = File(System.getProperty("user.dir"))
        val isp = File(root, "src/main/cpp/IspCore.cpp").readText()
        val shader = File(root, "src/main/cpp/vulkan/shaders/spectra_chroma_resident.comp").readText()
        assertTrue(isp.contains("resolveMultiscaleChromaResidualConsensus"))
        assertTrue(isp.contains("robustRingResidual(4)"))
        assertTrue(isp.contains("robustRingResidual(8)"))
        assertTrue(shader.contains("resolveChromaResidualConsensus"))
        assertTrue(shader.contains("robustResidualRingInput(x, y, 4)"))
        assertTrue(shader.contains("robustResidualRingInput(x, y, 8)"))
        assertTrue(shader.contains("x < 8 || y < 8"))
    }
}
