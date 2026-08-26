package com.bncam.core.quality

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class YuvLightroomNoiseReductionParitySourceContractTest {
    private fun appDir(): File = File("app").takeIf { it.isDirectory }
        ?: error("Cannot locate app module")

    @Test
    fun `all six Lightroom NR controls reach YUV JNI`() {
        val app = appDir()
        val imageUtils = File(app, "src/main/java/com/bncam/core/engine/ImageUtils.kt").readText()
        val bridge = File(app, "src/main/cpp/native-lib.cpp").readText()
        listOf(
            "profileNrLuminance", "profileNrLuminanceDetail", "profileNrLuminanceContrast",
            "profileNrColor", "profileNrColorDetail", "profileNrColorSmoothness"
        ).forEach { key ->
            assertTrue("Kotlin YUV bridge missing $key", imageUtils.contains("$key = qualityConfig?.profileNoiseReductionTuning"))
            assertTrue("native YUV bridge missing $key", bridge.contains("jfloat $key"))
        }
    }

    @Test
    fun `YUV Vulkan NR stays inside 128 byte push constant contract`() {
        val app = appDir()
        val backend = File(app, "src/main/cpp/vulkan/VulkanYuvSingleFrameBackend.cpp").readText()
        val shader = File(app, "src/main/cpp/vulkan/shaders/yuv_single_frame_isp.comp").readText()
        assertTrue(backend.contains("static_assert(sizeof(IspPush) == 128u)"))
        listOf("profileNrLumaBlend", "profileNrLumaProtection", "profileNrChromaBlend", "profileNrChromaProtection").forEach { key ->
            assertTrue("backend missing $key", backend.contains(key))
            assertTrue("shader missing $key", shader.contains(key))
        }
        assertTrue(shader.contains("denoisedInputYAtOutput"))
        assertTrue(shader.contains("denoisedUvAtOutput"))
    }

    @Test
    fun `YUV NR cooperates with physical ISO baseline without SPECTRA`() {
        val app = appDir()
        val bridge = File(app, "src/main/cpp/native-lib.cpp").readText()
        assertTrue(bridge.contains("const float physicalYuvNr"))
        assertTrue(bridge.contains("combineWithResidualHeadroom"))
        assertTrue(bridge.contains("profileNrPlan.lumaCreativeBlend"))
        assertTrue(bridge.contains("profileNrPlan.chromaCreativeBlend"))
        assertFalse("YUV must not pretend to run SPECTRA", bridge.contains("yuvSpectraNoiseActive"))
    }
}
