package com.bncam.core.quality

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Phase3SpectraContextFusionVisibleRecoverySourceContractTest {
    private fun appDir(): File = sequenceOf(File("."), File("app"))
        .firstOrNull { File(it, "src/main/cpp/IspCore.cpp").isFile }
        ?: error("Cannot locate app module")

    @Test
    fun `visible chroma uses robust context instead of max outlier veto`() {
        val app = appDir()
        val shader = File(app, "src/main/cpp/vulkan/shaders/spectra_post_demosaic_resident.comp").readText()
        assertTrue(shader.contains("robustLumaSigmaSum"))
        assertTrue(shader.contains("robustColourSigmaSum"))
        assertTrue(shader.contains("hardLumaEdge"))
        assertTrue(shader.contains("hardColourEdge"))
        assertTrue(shader.contains("luminanceReliability"))
        assertTrue(shader.contains("saturationProtection"))
        assertFalse(shader.contains("float structureWeight = 1.0 - smoothstepExact(0.85, 3.25, lumaEdgeSigma)"))
        assertFalse(shader.contains("float saturationWeight = 1.0 - smoothstepExact(0.28, 0.72, saturation)"))
        assertTrue(shader.contains("smoothstepExact(1.5, 3.5, normalizedEdge)"))
        assertFalse(shader.contains("smoothstepInv(1.5, 3.5, normalizedEdge)"))
        assertTrue(shader.contains("chromaOutlierRelease"))
        assertTrue(shader.contains("coherentNeighbourConfidence"))
    }

    @Test
    fun `visible chroma host plan and vulkan runtime share one authority contract`() {
        val app = appDir()
        val model = File(app, "src/main/cpp/SpectraVisibleChroma.h").readText()
        val core = File(app, "src/main/cpp/IspCore.cpp").readText()
        val backend = File(
            app,
            "src/main/cpp/vulkan/VulkanSpectraResidentPostDemosaicBackend.cpp"
        ).readText()
        assertTrue(model.contains("resolveVisibleChromaLumaGuardSigma"))
        assertTrue(model.contains("0.0f, 0.96f"))
        assertTrue(core.contains("resolveVisibleChromaLumaGuardSigma"))
        assertTrue(backend.contains("request.visibleAuthority, 0.0f, 0.96f"))
    }

    @Test
    fun `cpu failsafe mirrors context fusion policy`() {
        val app = appDir()
        val model = File(app, "src/main/cpp/SpectraVisibleChroma.h").readText()
        val core = File(app, "src/main/cpp/IspCore.cpp").readText()
        assertTrue(model.contains("SPECTRA_CONTEXT_FUSION_OPPONENT_5X5"))
        assertTrue(model.contains("ROBUST_NOISE_NORMALIZED_CONTEXT_HARD_EDGE_GUARD"))
        assertTrue(model.contains("maximumLumaEdgeSigma > 7.0f"))
        assertTrue(model.contains("luminanceReliability"))
        assertTrue(core.contains("robustLumaSigmaSum"))
        assertTrue(core.contains("robustColourSigmaSum"))
        assertTrue(core.contains("1.0f + 0.15f * opponentDistanceSquared"))
    }
}
