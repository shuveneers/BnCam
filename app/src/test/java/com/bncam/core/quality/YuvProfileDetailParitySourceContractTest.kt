package com.bncam.core.quality

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class YuvProfileDetailParitySourceContractTest {
    private val appDir: File = sequenceOf(File("."), File("app"))
        .firstOrNull { File(it, "src/main/cpp/vulkan/VulkanYuvSingleFrameBackend.cpp").isFile }
        ?: error("Cannot locate app module from ${File(".").absolutePath}")

    private fun source(relative: String): String = File(appDir, relative).readText()

    @Test
    fun `yuv consumes the same four Lightroom Detail controls`() {
        val imageUtils = source("src/main/java/com/bncam/core/engine/ImageUtils.kt")
        val native = source("src/main/cpp/native-lib.cpp")
        val header = source("src/main/cpp/vulkan/VulkanYuvSingleFrameBackend.h")
        val backend = source("src/main/cpp/vulkan/VulkanYuvSingleFrameBackend.cpp")
        val shader = source("src/main/cpp/vulkan/shaders/yuv_single_frame_isp.comp")
        listOf("profileDetailAmount", "profileDetailRadius", "profileDetailDetail", "profileDetailMasking").forEach { control ->
            assertTrue(imageUtils.contains(control))
            assertTrue(native.contains(control))
            assertTrue(header.contains(control))
            assertTrue(backend.contains(control))
            assertTrue(shader.contains(control))
        }
        assertTrue(shader.contains("profileDetailedYAtOutput"))
        assertTrue(shader.contains("if (amount <= 1.0e-6) return center"))
        assertTrue(native.contains("applyYuvProfileDetailCpuFallback"))
    }

    @Test
    fun `yuv detail remains luma only and bounded`() {
        val shader = source("src/main/cpp/vulkan/shaders/yuv_single_frame_isp.comp")
        assertTrue(shader.contains("float highPass = center - localMean"))
        assertTrue(shader.contains("float maskGate"))
        assertTrue(shader.contains("float tonalGate"))
        assertTrue(shader.contains("float cap = 0.040"))
        assertFalse(shader.contains("profileDetailChroma"))
    }

    @Test
    fun `yuv push constants stay within Vulkan minimum guarantee`() {
        val backend = source("src/main/cpp/vulkan/VulkanYuvSingleFrameBackend.cpp")
        assertTrue(backend.contains("static_assert(sizeof(IspPush) == 112u)"))
    }
}
