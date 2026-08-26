package com.bncam.core.quality

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Phase13ResidentSpectraOrchestrationSourceContractTest {
    private val appDir: File = sequenceOf(File("."), File("app"))
        .firstOrNull { File(it, "src/main/cpp/CMakeLists.txt").isFile }
        ?: error("Cannot locate app module from ${File(".").absolutePath}")

    private fun source(relative: String): String = File(appDir, relative).readText()

    private fun between(text: String, start: String, end: String): String {
        val startIndex = text.indexOf(start)
        val endIndex = text.indexOf(end, startIndex + start.length)
        assertTrue("Missing start marker: $start", startIndex >= 0)
        assertTrue("Missing end marker after: $start", endIndex > startIndex)
        return text.substring(startIndex, endIndex)
    }

    @Test
    fun `resident spectra orchestration never materializes the full image plane`() {
        val cpp = source("src/main/cpp/IspCore.cpp")
        val body = between(
            cpp,
            "ResidentSpectraPreDemosaicChainResult IspCore::executeResidentSpectraPreDemosaicChain(",
            "void IspCore::applySpectraPass3("
        )

        assertFalse(body.contains("raw.mosaic"))
        assertFalse(body.contains("cv::Mat"))
        assertFalse(body.contains("LinearFloatRaw"))
        assertFalse(body.contains("normalizeRawForJpeg"))
        assertFalse(body.contains("std::memcpy"))
        assertFalse(body.contains("materialize"))
        assertFalse(body.contains("readbackGeneration"))
        assertFalse(body.contains("outputMosaic"))
        assertTrue(body.contains("fullFrameCpuReadbackUsed = false"))
    }

    @Test
    fun `resident spectra orchestration uses all four opaque generation dispatchers`() {
        val cpp = source("src/main/cpp/IspCore.cpp")
        val body = between(
            cpp,
            "ResidentSpectraPreDemosaicChainResult IspCore::executeResidentSpectraPreDemosaicChain(",
            "void IspCore::applySpectraPass3("
        )

        assertTrue(body.contains("tryApplySpectraPass0VulkanResident("))
        assertTrue(body.contains("tryApplySpectraPass1VulkanResident("))
        assertTrue(body.contains("tryApplySpectraPass2VulkanResident("))
        assertTrue(body.contains("tryApplySpectraPass3VulkanResident("))
        assertTrue(body.contains("residentOutputGeneration"))
        assertTrue(body.contains("currentGeneration"))
    }

    @Test
    fun `pass2 and pass3 quality policy remains explicit and external`() {
        val header = source("src/main/cpp/IspCore.h")
        val cpp = source("src/main/cpp/IspCore.cpp")
        val body = between(
            cpp,
            "ResidentSpectraPreDemosaicChainResult IspCore::executeResidentSpectraPreDemosaicChain(",
            "void IspCore::applySpectraPass3("
        )

        assertTrue(header.contains("struct ResidentSpectraPreDemosaicPolicy"))
        assertTrue(header.contains("bool pass2PlanProvided = false"))
        assertTrue(header.contains("SpectraPass2State pass2State{}"))
        assertTrue(header.contains("bool pass3LowBandPlanProvided = false"))
        assertTrue(header.contains("bncam::spectra2::ChromaBandPlan pass3LowBandPlan{}"))
        assertTrue(body.contains("policy.pass2PlanProvided"))
        assertTrue(body.contains("policy.pass3LowBandPlanProvided"))
        assertTrue(body.contains("policy.pass3LowBandPlan.enabled"))
    }

    @Test
    fun `orchestrator is not silently wired into jni before production switch delta`() {
        val nativeLib = source("src/main/cpp/native-lib.cpp")
        assertFalse(nativeLib.contains("executeResidentSpectraPreDemosaicChain("))
    }
}
