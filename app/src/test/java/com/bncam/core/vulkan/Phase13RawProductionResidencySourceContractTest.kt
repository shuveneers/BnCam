package com.bncam.core.vulkan

import java.io.File
import org.junit.Test
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

class Phase13RawProductionResidencySourceContractTest {
    private val appDir = File(System.getProperty("user.dir"))
    private fun source(path: String): String = File(appDir, path).readText()

    @Test
    fun singleAndMultiFramePublicationBuffersCarryOpaqueResidentGeneration() {
        val merger = source("src/main/cpp/DngMerger.cpp")
        assertTrue(merger.contains("singleFrameResidentGeneration = gpu.residentOutputGeneration"))
        assertTrue(merger.contains("trackNativeRaw16VectorAllocation(vectorOwner, residentGeneration)"))
        assertTrue(merger.contains("trackNativeRaw16Allocation(")) // single-frame/native fallback owner
        assertTrue(merger.contains("residentGenerationForPublication"))
        assertTrue(merger.contains("nativeRaw16ResidentGeneration(void* address)"))
        assertTrue(merger.contains("gNativeRaw16ResidentGenerations.erase(address)"))
    }

    @Test
    fun productionJpegEntryPrefersResidentNormalizeAndCpuNormalizeIsExplicitFallbackOnly() {
        val nativeLib = source("src/main/cpp/native-lib.cpp")
        val render = nativeLib.substringAfter(
            "Java_com_bncam_core_engine_ImageUtils_renderJpegFromMasterNative"
        ).substringBefore("#if 0  // Retired giant RAW ISP diagnostic block.")

        val generationIndex = render.indexOf("nativeRaw16ResidentGeneration(nativeRawAddress)")
        val residentNormalizeIndex = render.indexOf("executeRawJpegNormalizeFromResidentRaw")
        val residentRenderIndex = render.indexOf("ResidentRawRenderInput residentRender")
        val cpuFallbackGateIndex = render.indexOf("if (!rawResidentEntryUsed)")
        val cpuNormalizeIndex = render.lastIndexOf("normalizeRawForJpeg(")

        assertTrue(generationIndex >= 0)
        assertTrue(residentNormalizeIndex > generationIndex)
        assertTrue(residentRenderIndex > residentNormalizeIndex)
        assertTrue(cpuFallbackGateIndex > residentRenderIndex)
        assertTrue(cpuNormalizeIndex > cpuFallbackGateIndex)
        assertTrue(render.contains("NO_RESIDENT_RAW_PRODUCER_GENERATION"))
        assertTrue(render.contains("RAW_JPEG_RESIDENT_NORMALIZE_FAILED"))
        assertFalse(render.contains("if (gpuSlower"))
        assertFalse(render.contains("silentRawNormalizeFallback"))
    }

    @Test
    fun residentIspEntryDoesNotRequireFullFrameFloatMosaicOnSuccess() {
        val isp = source("src/main/cpp/IspCore.cpp")
        val render = isp.substringAfter("std::vector<uint8_t> IspCore::renderRawBaselineJpeg(")
        assertTrue(render.contains("const bool residentEntry"))
        assertTrue(render.contains("sampleNoiseModelFromResidentRaw"))
        assertTrue(render.contains("buildSpectraProvenanceFieldCompact"))
        assertTrue(render.contains("computePass0StateCompact"))
        assertTrue(render.contains("executeSpectraRawFinalizeFromRawNormalize"))
        assertTrue(render.contains("rawResidentCpuFallbackUsed="))
        assertTrue(render.contains("residentCpuFallbackReason"))
    }

    @Test
    fun residentNormalizePreservesCropAndCfaDomainWithoutReadback() {
        val backend = source("src/main/cpp/vulkan/VulkanRawJpegNormalizeBackend.cpp")
        val shader = source("src/main/cpp/vulkan/shaders/raw_jpeg_normalize.comp")
        assertTrue(backend.contains("request.cropLeft"))
        assertTrue(backend.contains("request.cropTop"))
        assertTrue(shader.contains("pc.cropLeft"))
        assertTrue(shader.contains("pc.cropTop"))
        assertTrue(shader.contains("pc.cfaOffsetX"))
        assertTrue(shader.contains("pc.cfaOffsetY"))
        assertFalse(backend.contains("vmaMapMemory"))
        assertFalse(backend.contains("cv::Mat"))
    }
}
