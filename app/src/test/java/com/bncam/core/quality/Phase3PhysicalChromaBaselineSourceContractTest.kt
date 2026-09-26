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
        assertTrue(isp.contains("residualNoiseState.postDemosaic, physicalNoiseStatisticsActive"))
        assertTrue(isp.contains("request.baselinePhysicalChroma = baselinePhysicalChroma"))
        assertTrue(isp.indexOf("residualNoiseState.postDemosaic =") <
            isp.indexOf("const auto baselinePhysicalChroma ="))
        val engine = File(appDir(), "src/main/cpp/PhysicalChromaDenoise.h").readText()
        org.junit.Assert.assertFalse(engine.contains("spectraProcessingMode"))
        org.junit.Assert.assertFalse(engine.contains("DemosaicAlgorithm"))
        val shader = File(appDir(), "src/main/cpp/vulkan/shaders/spectra_demosaic_resident.comp").readText()
        assertTrue(shader.indexOf("vec3 rawForWb = physicalChromaDenoise") <
            shader.indexOf("vec3 legacyWb = rawForWb"))
        assertTrue(engine.contains("if (!physicalAvailable || !(n.confidence > 0)) return {}"))
    }
}
