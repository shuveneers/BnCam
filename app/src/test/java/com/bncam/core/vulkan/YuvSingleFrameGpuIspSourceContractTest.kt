package com.bncam.core.vulkan

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class YuvSingleFrameGpuIspSourceContractTest {
    private val appDir: File = sequenceOf(File("."), File("app"))
        .firstOrNull { File(it, "src/main/cpp/CMakeLists.txt").isFile }
        ?: error("Cannot locate app module from ${File(".").absolutePath}")

    private fun source(relative: String): String = File(appDir, relative).readText()

    @Test
    fun `runtime owns the phase 12 backend and shader build`() {
        val header = source("src/main/cpp/vulkan/VulkanRuntime.h")
        val runtime = source("src/main/cpp/vulkan/VulkanRuntime.cpp")
        val cmake = source("src/main/cpp/CMakeLists.txt")

        assertTrue(header.contains("VulkanYuvSingleFrameBackend yuvSingleFrameBackend_"))
        assertTrue(runtime.contains("yuvSingleFrameBackend_.execute("))
        assertTrue(runtime.contains("yuvSingleFrameBackend_.destroy(handles_.device)"))
        assertTrue(runtime.contains("markGpuStalled(\"YUV_SINGLE_FRAME_ISP\")"))
        assertTrue(cmake.contains("vulkan/VulkanYuvSingleFrameBackend.cpp"))
        assertTrue(cmake.contains("yuv_single_frame_isp.comp"))
        assertTrue(cmake.contains("BNCAM_YUV_SINGLE_FRAME_ISP_SHADER_AVAILABLE"))
    }

    @Test
    fun `normal yuv encoder success path contains no full frame OpenCV ISP`() {
        val native = source("src/main/cpp/native-lib.cpp")
        val start = native.indexOf("bool encodeNv21ToJpeg(\n")
        val fallbackCall = native.indexOf("return encodeNv21ToJpegCpuFallback(", start)
        assertTrue(start >= 0 && fallbackCall > start)
        val production = native.substring(start, fallbackCall)

        assertTrue(production.contains("executeYuvSingleFrameIsp(request)"))
        assertTrue(production.contains("cv::imencode(\".jpg\""))
        assertFalse(production.contains("cv::cvtColor"))
        assertFalse(production.contains("cv::parallel_for_"))
        assertFalse(production.contains("cv::rotate"))
        assertFalse(production.contains("cv::bilateralFilter"))
        assertFalse(production.contains("applyYuvProfileRgbAdjustments"))
    }

    @Test
    fun `OpenCV yuv ISP is explicit typed failure fallback only`() {
        val native = source("src/main/cpp/native-lib.cpp")
        val fallbackStart = native.indexOf("bool encodeNv21ToJpegCpuFallback(")
        val productionStart = native.indexOf("bool encodeNv21ToJpeg(\n", fallbackStart)
        assertTrue(fallbackStart >= 0 && productionStart > fallbackStart)
        val fallback = native.substring(fallbackStart, productionStart)

        assertTrue(fallback.contains("cv::parallel_for_"))
        assertTrue(fallback.contains("cv::cvtColor"))
        assertTrue(fallback.contains("cv::rotate"))
        assertTrue(fallback.contains("applyYuvProfileRgbAdjustments"))
        assertTrue(fallback.contains("fallbackNv21"))
        assertFalse(fallback.contains("const_cast<uint8_t*>(nv21Ptr)"))

        val production = native.substring(productionStart)
        assertTrue(production.contains("explicit CPU failsafe"))
        assertTrue(production.contains("yuvIspFallbackReason"))
        assertTrue(production.contains("return encodeNv21ToJpegCpuFallback("))
    }

    @Test
    fun `shader owns tone yuv conversion profile color and rotation`() {
        val shader = source("src/main/cpp/vulkan/shaders/yuv_single_frame_isp.comp")

        listOf(
            "toneLut.values[readByte(yIndex)]",
            "sourceCoordinate",
            "rotationDegrees == 90u",
            "rotationDegrees == 180u",
            "rotationDegrees == 270u",
            "298 * c + 409 * v",
            "298 * c - 100 * u - 208 * v",
            "298 * c + 516 * u",
            "pc.wbRed",
            "pc.wbGreen",
            "pc.wbBlue",
            "contrastCurve",
            "saturationControl",
            "hueControl"
        ).forEach { assertTrue("missing Vulkan YUV ISP term $it", shader.contains(it)) }
    }

    @Test
    fun `BGR24 output has one writer per four pixels with no atomics`() {
        val shader = source("src/main/cpp/vulkan/shaders/yuv_single_frame_isp.comp")
        assertTrue(shader.contains("uint firstPixel = blockIndex * 4u"))
        assertTrue(shader.contains("uint wordBase = blockIndex * 3u"))
        assertTrue(shader.contains("outputBuffer.words[wordBase + 0u] = pack4"))
        assertTrue(shader.contains("outputBuffer.words[wordBase + 1u] = pack4"))
        assertTrue(shader.contains("outputBuffer.words[wordBase + 2u] = pack4"))
        assertFalse(shader.contains("atomic"))
    }

    @Test
    fun `backend has one controlled encoder boundary readback`() {
        val backend = source("src/main/cpp/vulkan/VulkanYuvSingleFrameBackend.cpp")
        val native = source("src/main/cpp/native-lib.cpp")

        assertTrue(backend.contains("vkCmdCopyBuffer(command, outputDevice_.buffer, outputReadback_.buffer"))
        assertTrue(backend.contains("out.fullFrameGpuReadbackBytes = bgrBytes"))
        assertTrue(backend.contains("out.backend = \"VULKAN_YUV_SINGLE_FRAME_ISP\""))
        assertTrue(native.contains("CV_8UC3"))
        assertTrue(native.contains("cv::imencode(\".jpg\", bgrMat"))
    }

    @Test
    fun `unknown GPU completion is quarantined rather than resources being reused`() {
        val backend = source("src/main/cpp/vulkan/VulkanYuvSingleFrameBackend.cpp")
        val runtime = source("src/main/cpp/vulkan/VulkanRuntime.cpp")

        assertTrue(backend.contains("vkWaitForFences(device, 1u, &fence, VK_TRUE, 1'500'000'000ull)"))
        assertTrue(backend.contains("out.submissionMayRemainInFlight = true"))
        assertTrue(backend.contains("deliberately retain fence/command and persistent resources"))
        assertTrue(runtime.contains("markGpuStalled(\"YUV_SINGLE_FRAME_ISP\")"))
    }

    @Test
    fun `phase 12 telemetry proves backend residency transfer and fallback`() {
        val native = source("src/main/cpp/native-lib.cpp")
        listOf(
            "yuvSingleFrameIspBackend=",
            "yuvSingleFrameCpuFallbackUsed=",
            "yuvSingleFrameFallbackReason=",
            "yuvSingleFrameCpuUploadBytes=",
            "yuvSingleFrameGpuReadbackBytes=",
            "yuvSingleFrameInputUploadMs=",
            "yuvSingleFrameGpuExecutionWallMs=",
            "yuvSingleFrameGpuSyncMs=",
            "yuvSingleFramePublicationReadbackMs="
        ).forEach { assertTrue("missing Phase-12 telemetry $it", native.contains(it)) }
    }
}
