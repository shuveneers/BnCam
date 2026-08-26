package com.bncam.core.vulkan

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class YuvMultiFrameGpuResidencySourceContractTest {
    private val appDir: File = sequenceOf(File("."), File("app"))
        .firstOrNull { File(it, "src/main/cpp/CMakeLists.txt").isFile }
        ?: error("Cannot locate app module from ${File(".").absolutePath}")

    private fun source(relative: String): String = File(appDir, relative).readText()

    @Test
    fun `runtime owns the YUV multi-frame Vulkan backend and both shaders are built`() {
        val header = source("src/main/cpp/vulkan/VulkanRuntime.h")
        val runtime = source("src/main/cpp/vulkan/VulkanRuntime.cpp")
        val cmake = source("src/main/cpp/CMakeLists.txt")

        assertTrue(header.contains("VulkanYuvMultiFrameBackend yuvMultiFrameBackend_"))
        assertTrue(runtime.contains("yuvMultiFrameBackend_.align("))
        assertTrue(runtime.contains("yuvMultiFrameBackend_.destroy(handles_.device)"))
        assertTrue(runtime.contains("markGpuStalled(\"YUV_MULTIFRAME_ALIGNMENT\")"))
        assertTrue(cmake.contains("yuv_multiframe_alignment.comp"))
        assertTrue(cmake.contains("yuv_multiframe_fusion.comp"))
        assertTrue(cmake.contains("BNCAM_YUV_MULTIFRAME_ALIGNMENT_SHADER_AVAILABLE"))
        assertTrue(cmake.contains("BNCAM_YUV_MULTIFRAME_FUSION_SHADER_AVAILABLE"))
    }

    @Test
    fun `normal multi-frame YUV success path contains no OpenCV alignment warp or float accumulator`() {
        val native = source("src/main/cpp/native-lib.cpp")
        val start = native.indexOf("if (!yuvAlignmentCpuFallbackUsed && gpuAlignment.success")
        val fallback = native.indexOf("} else {", start)
        assertTrue(start >= 0 && fallback > start)
        val success = native.substring(start, fallback)

        assertFalse(success.contains("cv::phaseCorrelate"))
        assertFalse(success.contains("cv::warpAffine"))
        assertFalse(success.contains("accum32f"))
        assertFalse(success.contains("gpuAlignment.outputLuma"))
        assertTrue(success.contains("gpuAlignment.residentFusedLumaProduced"))
        assertTrue(success.contains("yuvResidentLumaGeneration = gpuAlignment.residentOutputGeneration"))
    }

    @Test
    fun `OpenCV multi-frame implementation is restricted to explicit Vulkan failure fallback`() {
        val native = source("src/main/cpp/native-lib.cpp")
        val start = native.indexOf("if (!yuvAlignmentCpuFallbackUsed && gpuAlignment.success")
        val fallbackStart = native.indexOf("} else {", start)
        val fallbackEnd = native.indexOf("if (!anchorNv21.empty()", fallbackStart)
        assertTrue(fallbackStart > start && fallbackEnd > fallbackStart)
        val fallback = native.substring(fallbackStart, fallbackEnd)

        assertTrue(fallback.contains("Explicit bounded Vulkan failure fallback/reference path"))
        assertTrue(fallback.contains("cv::phaseCorrelate"))
        assertTrue(fallback.contains("cv::warpAffine"))
        assertTrue(fallback.contains("accum32f"))
        assertTrue(fallback.contains("warpDx = -static_cast<float>(measuredShift.x)"))
        assertTrue(fallback.contains("warpDy = -static_cast<float>(measuredShift.y)"))
        assertTrue(native.contains("yuvAlignmentCpuFallbackUsed = true"))
        assertTrue(native.contains("yuvFusionCpuFallbackUsed = true"))
    }

    @Test
    fun `fusion is streaming and bounded rather than allocating all support frames`() {
        val header = source("src/main/cpp/vulkan/VulkanYuvMultiFrameBackend.h")
        val backend = source("src/main/cpp/vulkan/VulkanYuvMultiFrameBackend.cpp")

        assertTrue(header.contains("PersistentBuffer support_"))
        assertTrue(header.contains("PersistentBuffer accumulator_"))
        assertFalse(header.contains("std::vector<PersistentBuffer>"))
        assertTrue(backend.contains("uploadStagingTo(support_.buffer"))
        assertTrue(backend.contains("YUV_MULTIFRAME_FUSION_ACCUMULATE"))
        assertTrue(backend.contains("YUV_MULTIFRAME_FUSION_FINALIZE"))
        assertTrue(backend.contains("const bool publicationReadback = !request.deferFullFrameReadback"))
        assertTrue(backend.contains("out.fullFrameGpuReadbackBytes = 0u"))
        assertTrue(backend.contains("out.residentFusedLumaProduced = true"))
    }

    @Test
    fun `alignment uses GPU zero-mean NCC with only compact score readback`() {
        val shader = source("src/main/cpp/vulkan/shaders/yuv_multiframe_alignment.comp")
        val backend = source("src/main/cpp/vulkan/VulkanYuvMultiFrameBackend.cpp")
        val native = source("src/main/cpp/native-lib.cpp")

        listOf("sumA", "sumB", "sumAA", "sumBB", "sumAB", "covariance", "varA", "varB")
            .forEach { assertTrue("missing NCC term $it", shader.contains(it)) }
        assertTrue(shader.contains("scores[candidate] = score"))
        assertTrue(backend.contains("compactGpuReadbackBytes += scoreBytes"))
        assertTrue(backend.contains("std::clamp(request.sampleStep, 1u, 16u)"))
        assertTrue(native.contains("alignmentRequest.sampleStep = 16u"))
    }

    @Test
    fun `fusion shader has inverse bilinear warp and one writer per packed output word`() {
        val shader = source("src/main/cpp/vulkan/shaders/yuv_multiframe_fusion.comp")

        assertTrue(shader.contains("sampleSupportBilinear"))
        assertTrue(shader.contains("float(x) - pc.dx"))
        assertTrue(shader.contains("float(y) - pc.dy"))
        assertTrue(shader.contains("uint pixelBase = wordIndex * 4u"))
        assertTrue(shader.contains("fusedWords[wordIndex] = packed"))
        assertFalse(shader.contains("atomicOr"))
    }

    @Test
    fun `Vulkan submission timeout does not free possibly live resources`() {
        val backend = source("src/main/cpp/vulkan/VulkanYuvMultiFrameBackend.cpp")

        assertTrue(backend.contains("vkWaitForFences(device, 1u, &fence, VK_TRUE, 1'500'000'000ull)"))
        assertTrue(backend.contains("out.submissionMayRemainInFlight = true"))
        assertTrue(backend.contains("if (!out.submissionMayRemainInFlight) vkFreeCommandBuffers"))
    }

    @Test
    fun `production telemetry exposes YUV alignment fusion residency and explicit fallback`() {
        val native = source("src/main/cpp/native-lib.cpp")

        listOf(
            "yuvAlignmentBackend=",
            "yuvAlignmentCpuFallbackUsed=",
            "yuvAlignmentFallbackReason=",
            "yuvAlignmentGpuMs=",
            "yuvAlignmentCpuUploadBytes=",
            "yuvAlignmentCompactReadbackBytes=",
            "yuvFusionBackend=",
            "yuvFusionCpuFallbackUsed=",
            "yuvFusionFallbackReason=",
            "yuvFusionGpuMs=",
            "yuvFusionGpuReadbackBytes="
        ).forEach { assertTrue("missing telemetry $it", native.contains(it)) }
        assertTrue(native.contains("alignmentRequest.fuseLuma = true"))
        assertTrue(native.contains("alignmentRequest.deferFullFrameReadback = true"))
        assertTrue(native.contains("yuvSingleFrameResidentLumaConsumed="))
        assertTrue(native.contains("yuvFusionResidentGeneration="))
    }
}
