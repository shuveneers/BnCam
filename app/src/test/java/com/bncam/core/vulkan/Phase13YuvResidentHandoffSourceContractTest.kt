package com.bncam.core.vulkan

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Phase13YuvResidentHandoffSourceContractTest {
    private val appDir: File = sequenceOf(File("."), File("app"))
        .firstOrNull { File(it, "src/main/cpp/CMakeLists.txt").isFile }
        ?: error("Cannot locate app module from ${File(".").absolutePath}")

    private fun source(relative: String): String = File(appDir, relative).readText()

    @Test
    fun `multi-frame fusion can publish an opaque resident luma generation without readback`() {
        val header = source("src/main/cpp/vulkan/VulkanYuvMultiFrameBackend.h")
        val backend = source("src/main/cpp/vulkan/VulkanYuvMultiFrameBackend.cpp")

        assertTrue(header.contains("bool deferFullFrameReadback = false"))
        assertTrue(header.contains("bool residentFusedLumaProduced = false"))
        assertTrue(header.contains("std::uint64_t residentOutputGeneration = 0u"))
        assertTrue(header.contains("bool resolveResidentOutput("))
        assertTrue(backend.contains("const bool publicationReadback = !request.deferFullFrameReadback"))
        assertTrue(backend.contains("residentOutputGeneration_ = request.generationId"))
        assertTrue(backend.contains("out.fullFrameGpuReadbackBytes = 0u"))
        assertTrue(backend.contains("out.fullFrameReadbackDeferred = true"))
    }

    @Test
    fun `runtime resolves resident generation under the same submission lock as ISP consume`() {
        val runtime = source("src/main/cpp/vulkan/VulkanRuntime.cpp")
        val start = runtime.indexOf("YuvSingleFrameIspResult VulkanRuntime::executeYuvSingleFrameIsp(")
        val end = runtime.indexOf("RawPreviewGpuResult VulkanRuntime::executeRawPreview(", start)
        assertTrue(start >= 0 && end > start)
        val body = runtime.substring(start, end)

        val lock = body.indexOf("std::lock_guard<std::mutex> submitLock(submissionMutex_)")
        val resolve = body.indexOf("yuvMultiFrameBackend_.resolveResidentOutput(")
        val execute = body.indexOf("yuvSingleFrameBackend_.execute(", resolve)
        assertTrue(lock >= 0 && resolve > lock && execute > resolve)
        assertTrue(body.contains("YUV_SINGLE_FRAME_RESIDENT_LUMA_GENERATION_INVALID"))
    }

    @Test
    fun `single-frame backend consumes resident Y device-to-device and uploads only chroma`() {
        val backend = source("src/main/cpp/vulkan/VulkanYuvSingleFrameBackend.cpp")

        assertTrue(backend.contains("const bool useResidentLuma = request.residentLumaBuffer != VK_NULL_HANDLE"))
        assertTrue(backend.contains("vkCmdCopyBuffer(command, request.residentLumaBuffer, inputDevice_.buffer"))
        assertTrue(backend.contains("request.nv21 + lumaBytes"))
        assertTrue(backend.contains("out.fullFrameCpuUploadBytes = chromaBytes"))
        assertTrue(backend.contains("out.residentLumaConsumed = true"))
    }

    @Test
    fun `production multi-frame success contains no fused luma CPU bridge`() {
        val native = source("src/main/cpp/native-lib.cpp")
        val start = native.indexOf("if (!yuvAlignmentCpuFallbackUsed && gpuAlignment.success")
        val fallback = native.indexOf("} else {", start)
        assertTrue(start >= 0 && fallback > start)
        val success = native.substring(start, fallback)

        assertTrue(native.contains("alignmentRequest.deferFullFrameReadback = true"))
        assertTrue(success.contains("gpuAlignment.residentFusedLumaProduced"))
        assertTrue(success.contains("yuvResidentLumaGeneration = gpuAlignment.residentOutputGeneration"))
        assertFalse(success.contains("gpuAlignment.outputLuma"))
        assertFalse(success.contains("std::memcpy(anchorNv21.data(), gpuAlignment"))
    }

    @Test
    fun `resident handoff failure cannot silently publish anchor-only output`() {
        val native = source("src/main/cpp/native-lib.cpp")
        val encodeStart = native.indexOf("bool encodeNv21ToJpeg(\n")
        val cpuFallback = native.indexOf("return encodeNv21ToJpegCpuFallback(", encodeStart)
        assertTrue(encodeStart >= 0 && cpuFallback > encodeStart)
        val production = native.substring(encodeStart, cpuFallback)

        assertTrue(production.contains("if (residentLumaGeneration != 0u)"))
        assertTrue(production.contains("refusing anchor-only publication"))
        assertTrue(production.contains("return false"))
        assertTrue(production.contains("VULKAN_RESIDENT_HANDOFF_FAILED"))
    }

    @Test
    fun `telemetry proves resident producer consumer identity and publication boundary`() {
        val native = source("src/main/cpp/native-lib.cpp")
        val single = source("src/main/cpp/vulkan/VulkanYuvSingleFrameBackend.cpp")

        assertTrue(native.contains("yuvFusionGpuReadbackBytes="))
        assertTrue(native.contains("yuvFusionResidentGeneration="))
        assertTrue(native.contains("yuvSingleFrameResidentLumaConsumed="))
        assertTrue(native.contains("yuvSingleFrameResidentLumaGeneration="))
        assertTrue(single.contains("out.fullFrameGpuReadbackBytes = bgrBytes"))
        assertTrue(native.contains("cv::imencode(\".jpg\", bgrMat"))
    }
}
