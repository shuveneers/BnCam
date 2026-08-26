package com.bncam.core.vulkan

import java.io.File
import org.junit.Test
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

class Phase13RawNormalizeResidencySourceContractTest {
    private val appDir = File(System.getProperty("user.dir"))

    private fun source(path: String): String = File(appDir, path).readText()

    @Test
    fun residentNormalizeBackendDoesNotMaterializeFullFramePixelsOnCpu() {
        val header = source("src/main/cpp/vulkan/VulkanRawJpegNormalizeBackend.h")
        val cpp = source("src/main/cpp/vulkan/VulkanRawJpegNormalizeBackend.cpp")
        val shader = source("src/main/cpp/vulkan/shaders/raw_jpeg_normalize.comp")

        assertTrue(header.contains("VkBuffer canonicalRawBuffer"))
        assertTrue(header.contains("residentOutputProduced"))
        assertTrue(header.contains("residentOutputGeneration"))
        assertTrue(cpp.contains("out.fullFrameCpuUploadBytes = 0u") || header.contains("fullFrameCpuUploadBytes = 0u"))
        assertTrue(cpp.contains("out.fullFrameGpuReadbackBytes = 0u") || header.contains("fullFrameGpuReadbackBytes = 0u"))
        assertFalse(cpp.contains("vkCmdCopyBuffer(command, output_.buffer"))
        assertFalse(cpp.contains("vmaMapMemory"))
        assertFalse(cpp.contains("memcpy("))
        assertFalse(cpp.contains("cv::Mat"))
        assertFalse(cpp.contains("cv::parallel_for_"))
        assertTrue(shader.contains("clamp((raw - black) * inverseRange, 0.0, 1.0)"))
    }

    @Test
    fun runtimeResolvesAndConsumesRawMultiFrameGenerationUnderOneSubmissionLock() {
        val runtime = source("src/main/cpp/vulkan/VulkanRuntime.cpp")
        val bridge = runtime.substringAfter(
            "RawJpegNormalizeResult VulkanRuntime::executeRawJpegNormalizeFromMultiFrame"
        ).substringBefore(
            "YuvMultiFrameAlignmentResult VulkanRuntime::executeYuvMultiFrameAlignment"
        )

        val lockIndex = bridge.indexOf("std::lock_guard<std::mutex> submitLock(submissionMutex_)")
        val resolveIndex = bridge.indexOf("rawMultiFrameBackend_.resolveResidentOutput")
        val executeIndex = bridge.indexOf("rawJpegNormalizeBackend_.execute")
        assertTrue(lockIndex >= 0)
        assertTrue(resolveIndex > lockIndex)
        assertTrue(executeIndex > resolveIndex)
        assertTrue(bridge.contains("RAW_JPEG_NORMALIZE_RESIDENT_MULTIFRAME_GENERATION_INVALID"))
        assertTrue(bridge.contains("markGpuStalled(\"RAW_JPEG_NORMALIZE_RESIDENT_MULTIFRAME\")"))
    }

    @Test
    fun backendIsStillNotSilentlyWiredThroughLegacyCpuNormalization() {
        val nativeLib = source("src/main/cpp/native-lib.cpp")
        val normalizeCall = nativeLib.indexOf("normalizeRawForJpeg(")
        assertTrue("Checkpoint still expects the legacy CPU normalizer to exist before the production caller switch", normalizeCall >= 0)
        assertFalse(nativeLib.contains("silentRawNormalizeFallback"))
    }
}
