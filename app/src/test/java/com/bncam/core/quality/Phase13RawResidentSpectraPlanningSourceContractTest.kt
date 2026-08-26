package com.bncam.core.quality

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Phase13RawResidentSpectraPlanningSourceContractTest {
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
    fun `raw16 compact planning view is read only exact and bounded per sample`() {
        val header = source("src/main/cpp/RawDomain.h")
        val cpp = source("src/main/cpp/RawDomain.cpp")
        assertTrue(header.contains("struct RawNormalizedSampleView"))
        assertTrue(header.contains("float sample(int x, int y) const noexcept"))
        val sample = between(
            cpp,
            "float RawNormalizedSampleView::sample(int x, int y) const noexcept",
            "RawNormalizedSampleView makeRawNormalizedSampleView("
        )
        assertTrue(sample.contains("raw16"))
        assertTrue(sample.contains("blackLevels"))
        assertTrue(sample.contains("whiteLevel"))
        assertTrue(sample.contains("std::clamp"))
        assertFalse(sample.contains("for ("))
        assertFalse(sample.contains("while ("))
        assertFalse(sample.contains("parallel_for_"))
        assertFalse(sample.contains("cv::Mat"))
    }

    @Test
    fun `spectra compact planners do not require a full float mosaic`() {
        val cpp = source("src/main/cpp/IspCore.cpp")
        val pass0 = between(cpp, "SpectraPass0State IspCore::computePass0StateCompact(", "void IspCore::applySpectraPass0(")
        val provenance = between(cpp, "SpectraProvenanceField IspCore::buildSpectraProvenanceFieldCompact(", "SpectraNoRegretResult IspCore::applySpectraNoRegretGate(")
        val tensor = between(cpp, "SpectraStructureTensorField buildSpectraStructureTensorFieldCompact(", "SpectraStructureTensorField buildSpectraStructureTensorField(")

        for (body in listOf(pass0, provenance, tensor)) {
            assertFalse(body.contains("raw.mosaic"))
            assertFalse(body.contains("cv::Mat::clone"))
        }
        assertTrue(pass0.contains("planningSampleCount"))
        assertTrue(provenance.contains("raw.sample("))
        assertTrue(tensor.contains("raw.sample("))
    }

    @Test
    fun `pass1 and pass2 state selection is descriptor only on resident path`() {
        val cpp = source("src/main/cpp/IspCore.cpp")
        val pass1 = between(cpp, "SpectraPass1State IspCore::computePass1StateCompact(", "void IspCore::applySpectraPass1(")
        val pass2 = between(cpp, "SpectraPass2State IspCore::computePass2StateCompact(", "void IspCore::applySpectraPass2(")
        assertFalse(pass1.contains("raw.mosaic"))
        assertFalse(pass1.contains("cv::Mat"))
        assertFalse(pass2.contains("raw.mosaic"))
        assertFalse(pass2.contains("cv::Mat"))
        assertTrue(pass1.contains("raw.valid"))
        assertTrue(pass2.contains("raw.valid"))
    }

    @Test
    fun `pass3 compact gpu planner accepts resident descriptor without host mosaic`() {
        val cpp = source("src/main/cpp/IspCore.cpp")
        val residentOverload = between(
            cpp,
            "bool IspCore::computePass3StateVulkanCompact(\n        const RawNormalizedSampleView& raw",
            "void IspCore::applySpectraPass3("
        )
        assertFalse(residentOverload.contains("raw.mosaic"))
        assertFalse(residentOverload.contains("cv::Mat"))
        assertTrue(residentOverload.contains("raw.info.width"))
        assertTrue(residentOverload.contains("residentGeneration"))
    }

    @Test
    fun `all four resident spectra dispatch helpers forbid host mosaic input`() {
        val cpp = source("src/main/cpp/IspCore.cpp")
        val markers = listOf(
            "bool tryApplySpectraPass0VulkanResident(" to "std::uint64_t spectraLensMapGenerationId(",
            "bool tryApplySpectraPass1VulkanResident(" to "void appendSpectraGpuBeforeTiles(",
            "bool tryApplySpectraPass2VulkanResident(" to "bool tryApplySpectraPass3Vulkan(",
            "bool tryApplySpectraPass3VulkanResident(" to "std::string SpectraPass2State::formatDebugString()"
        )
        for ((start, end) in markers) {
            val body = between(cpp, start, end)
            assertFalse("$start must not read a host mosaic", body.contains("raw.mosaic"))
            assertFalse("$start must not allocate cv Mat", body.contains("cv::Mat"))
            assertTrue("$start must use opaque generation", body.contains("residentInputGeneration") || body.contains("rawNormalizeGeneration"))
            assertTrue("$start must defer full frame readback", body.contains("deferFullFrameReadback = true"))
            assertTrue("$start must not submit a host mosaic", body.contains("mosaicData = nullptr"))
        }
    }

    @Test
    fun `raw normalize to pass0 runtime bridge stays inside runtime ownership`() {
        val runtime = source("src/main/cpp/vulkan/VulkanRuntime.cpp")
        val bridge = between(
            runtime,
            "SpectraResidentPreDemosaicResult VulkanRuntime::executeSpectraResidentPreDemosaicPass0FromRawNormalize(",
            "SpectraResidentPreDemosaicResult VulkanRuntime::executeSpectraResidentPreDemosaicPass1("
        )
        assertTrue(bridge.contains("submissionMutex_"))
        assertTrue(bridge.contains("rawJpegNormalizeBackend_.resolveResidentOutput("))
        assertTrue(bridge.contains("externalResidentInputBuffer"))
        assertFalse(bridge.contains("vkMapMemory"))
        assertFalse(bridge.contains("outputMosaic"))
    }
}
