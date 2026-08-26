package com.bncam.core.quality

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpectraMultiscaleResidualConsensusSourceContractTest {
    private fun appDir(): File = sequenceOf(File("."), File("app"))
        .firstOrNull { File(it, "src/main/cpp/IspCore.cpp").isFile }
        ?: error("Cannot locate app module")

    @Test
    fun `production pass1 uses two scale residual consensus before no regret`() {
        val app = appDir()
        val shader = File(app, "src/main/cpp/vulkan/shaders/spectra_pass1_resident.comp").readText()
        val core = File(app, "src/main/cpp/IspCore.cpp").readText()

        assertTrue(shader.contains("sameCfaRingEstimate(x, y, 4, S, O)"))
        assertTrue(shader.contains("sameCfaRingEstimate(x, y, 8, S, O)"))
        assertTrue(shader.contains("resolveMultiscaleResidualConsensus("))
        assertTrue(shader.contains("abs(midRing.target - coarseRing.target)"))
        assertTrue(shader.contains("target = mix(target, contextualTarget, residualConsensus.contextMix)"))
        assertTrue(shader.contains("if (x < 8 || y < 8"))

        assertTrue(core.contains("resolveMultiscaleResidualConsensus("))
        assertTrue(core.contains("ringEstimate(4, midTarget, midStructureZ)"))
        assertTrue(core.contains("ringEstimate(8, coarseTarget, coarseRingStructureZ)"))
        assertTrue(core.contains("residualConsensus.contextMix * (contextualTarget - target)"))

        // This feature is folded into the established resident Pass-1 shader;
        // it must not create a second CPU-first production denoise route.
        assertFalse(shader.contains("nonLocalCpu"))
    }
}
