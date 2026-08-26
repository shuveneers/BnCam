package com.bncam.core.quality

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SpectraLowFrequencyConfidenceAuthoritySourceContractTest {
    @Test
    fun lowFrequencyConfidenceIsSupportNotLinearOpacity() {
        val root = File(System.getProperty("user.dir"))
        val header = File(root, "src/main/cpp/SpectraChromaMultiscale.h").readText()
        val shader = File(root, "src/main/cpp/vulkan/shaders/spectra_chroma_resident.comp").readText()
        assertTrue(header.contains("resolveNoiseProfileAuthorityConfidence(confidence)"))
        assertTrue(header.contains("0.55f + 0.45f * std::sqrt(decision.shadowWeight)"))
        assertTrue(header.contains("confidence < 0.10f"))
        assertTrue(shader.contains("0.55 + 0.45 * sqrt(confidence)"))
        assertTrue(shader.contains("0.55 + 0.45 * sqrt(spatialConfidence)"))
        assertTrue(shader.contains("confidence < 0.10"))
    }
}
