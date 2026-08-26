package com.bncam.core.quality

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Phase3SpectraContextFusionCfaSourceContractTest {
    private fun appDir(): File = sequenceOf(File("."), File("app"))
        .firstOrNull { File(it, "src/main/cpp/IspCore.cpp").isFile }
        ?: error("Cannot locate app module")

    @Test
    fun `cfa chroma structure is normalized against physical sensor noise`() {
        val app = appDir()
        val shader = File(app, "src/main/cpp/vulkan/shaders/spectra_chroma_resident.comp").readText()
        assertTrue(shader.contains("contextStructureWeight"))
        assertTrue(shader.contains("expectedGreenNoiseGradient = 2.26 * singleGreenSigma"))
        assertTrue(shader.contains("expectedMidNoiseGradient = 3.40 * sqrt"))
        assertTrue(shader.contains("structureWeight = contextStructureWeight"))
        assertFalse(shader.contains("greenGradient / max(0.02, abs(centerGreen))"))
        assertFalse(shader.contains("greenStructure / max(0.025, abs(centerGreen))"))
    }

    @Test
    fun `context fusion remains vulkan resident and raises only chroma authorities`() {
        val app = appDir()
        val core = File(app, "src/main/cpp/IspCore.cpp").readText()
        val block = core.substringAfter("SpectraIsoAdaptiveState IspCore::resolveSpectraIsoAdaptiveState")
            .substringBefore("SpectraProvenanceField IspCore::buildSpectraProvenanceField")
        assertTrue(block.contains("SPECTRA Context Fusion"))
        assertTrue(block.contains("0.28f + 1.08f * state.combinedNoisePressure"))
        assertTrue(block.contains("0.10f + 0.95f * high"))
        assertTrue(core.contains("tryApplySpectraPass2Vulkan"))
        assertTrue(core.contains("tryApplySpectraPass3Vulkan"))
    }
}
