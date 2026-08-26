package com.bncam.core.quality

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SpectraChromaBandConfidenceSemanticsSourceContractTest {
    @Test
    fun fineAndMidConfidenceUseSameBoundedAuthoritySemanticsAsOtherSpectraStages() {
        val root = File(System.getProperty("user.dir"))
        val header = File(root, "src/main/cpp/SpectraChromaMultiscale.h").readText()
        val shader = File(root, "src/main/cpp/vulkan/shaders/spectra_chroma_resident.comp").readText()
        assertTrue(header.contains("resolveNoiseProfileAuthorityConfidence(confidence)"))
        assertTrue(header.contains("confidence >= 0.10f"))
        assertTrue(shader.contains("authorityConfidence = confidence >= 0.10 ? 0.55 + 0.45 * sqrt(confidence)"))
    }
}
