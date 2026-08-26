package com.bncam.core.quality

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RawPreviewLocalToneSourceContractTest {
    private val appDir: File = sequenceOf(File("."), File("app"))
        .firstOrNull { File(it, "src/main/cpp/vulkan/VulkanRawPreviewBackend.cpp").isFile }
        ?: error("Cannot locate app module from ${File(".").absolutePath}")

    private fun source(relative: String): String = File(appDir, relative).readText()

    @Test
    fun `raw preview local tone is gpu resident edge aware and bounded`() {
        val bufferShader = source("src/main/cpp/vulkan/shaders/raw_preview.comp")
        val imageShader = source("src/main/cpp/vulkan/shaders/raw_preview_image.comp")

        for (shader in listOf(bufferShader, imageShader)) {
            assertTrue(shader.contains("binding = 4) buffer LocalToneBase"))
            assertTrue(shader.contains("PREVIEW_LOCAL_TONE_DECIMATION = 8u"))
            assertTrue(shader.contains("buildPreviewLocalToneBase"))
            assertTrue(shader.contains("rangeWeight = exp(-1.55 * abs(neighborLog - centerLog))"))
            assertTrue(shader.contains("applyPreviewLocalTone(gid, rgb)"))
            assertTrue(shader.contains("deepBlackGuard"))
            assertTrue(shader.contains("localNeedGate = smoothstep(0.045, 0.16, abs(requestedEv))"))
            assertTrue(shader.contains("positiveHighlightGuard"))
            assertTrue(shader.contains("float highDrAuthority = dynamicRange * (0.65 + 0.35 * highlight)"))
            assertTrue(shader.contains("0.06 + 0.28 * highDrAuthority + 0.05 * shadow * highlight"))
            assertTrue(shader.contains("if (lowLight) strength *= 1.0 - 0.50 * noiseProxy"))
            assertTrue(shader.contains("0.16 + 0.38 * dynamicRange + 0.06 * shadow"))
            assertTrue(shader.contains("0.10 + 0.24 * highlight + 0.06 * dynamicRange"))
            assertTrue(shader.contains("floatBitsToUint(0.150)"))
            assertTrue(shader.contains("float baseMaxGain = lowLight ? (raw10 ? 1.80 : 2.00) : (raw10 ? 2.20 : 2.40)"))
            assertTrue(shader.contains("float extendedMaxGain = raw10 ? 2.80 : 3.20"))
            assertTrue(shader.contains("else if (mode == 3u) buildPreviewLocalToneBase"))
        }
    }

    @Test
    fun `preview backend builds local base before unchanged cached render pass`() {
        val backend = source("src/main/cpp/vulkan/VulkanRawPreviewBackend.cpp")
        val header = source("src/main/cpp/vulkan/VulkanRawPreviewBackend.h")

        assertTrue(backend.contains("VkDescriptorSetLayoutBinding bindings[5]"))
        assertTrue(backend.contains("imageDescriptorInfo.bindingCount = 5u"))
        assertTrue(backend.contains("9u * RAW_PREVIEW_FRAMES_IN_FLIGHT"))
        assertTrue(backend.contains("kPreviewLocalToneDecimation = 8u"))
        assertTrue(backend.contains("localToneBaseReady.buffer = localToneBase_.buffer"))
        assertTrue(backend.contains("(3u << 8u) | packedDemosaic"))
        assertTrue(backend.contains("(2u << 8u) | packedDemosaic"))
        assertTrue(header.contains("PersistentBuffer localToneBase_"))
        assertFalse(backend.contains("CPU_LOCAL_TONE"))
        assertFalse(backend.contains("localToneCpu"))
    }

    @Test
    fun `preview local tone does not disturb gtm statistics contract`() {
        val bufferShader = source("src/main/cpp/vulkan/shaders/raw_preview.comp")
        val imageShader = source("src/main/cpp/vulkan/shaders/raw_preview_image.comp")
        val backend = source("src/main/cpp/vulkan/VulkanRawPreviewBackend.cpp")

        for (shader in listOf(bufferShader, imageShader)) {
            assertTrue(shader.contains("GTM_DYNAMIC_RANGE_PRESSURE_INDEX = 550u"))
            assertFalse(shader.contains("USER_BLACK_ANCHOR_INDEX"))
            assertTrue(shader.contains("LTM_STRENGTH_INDEX = 559u"))
            assertTrue(shader.contains("LTM_MAX_LIFT_EV_INDEX = 560u"))
            assertTrue(shader.contains("LTM_MAX_COMPRESS_EV_INDEX = 561u"))
            assertTrue(shader.contains("ANALYSIS_NV21_START_INDEX = 572u"))
        }
        assertTrue(backend.contains("PREVIEW_STATS_BASE_WORDS = 572u"))
    }
}
