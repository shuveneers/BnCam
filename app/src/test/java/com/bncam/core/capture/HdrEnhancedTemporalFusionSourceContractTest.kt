package com.bncam.core.capture

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HdrEnhancedTemporalFusionSourceContractTest {
    private val appDir: File = sequenceOf(File("."), File("app"))
        .firstOrNull { File(it, "src/main/cpp/vulkan/VulkanRawMultiFrameBackend.cpp").isFile }
        ?: error("Cannot locate app module")

    @Test fun `same exposure hdr main supports have temporal fusion authority`() {
        val header = File(appDir, "src/main/cpp/vulkan/VulkanRawMultiFrameBackend.h").readText()
        val backend = File(appDir, "src/main/cpp/vulkan/VulkanRawMultiFrameBackend.cpp").readText()
        val shader = File(appDir, "src/main/cpp/vulkan/shaders/raw_multiframe_fusion.comp").readText()

        assertTrue(header.contains("bool hdrTemporalMainAuthority = false"))
        assertTrue(header.contains("fusionContributedPixels"))
        assertTrue(backend.contains("sr.hdrTemporalMainAuthority = request.computationalHdr"))
        assertTrue(shader.contains("bool hdrTemporalMainSupport = hdr && !hdrExposureBracketSupport"))
        assertTrue(shader.contains("if (hdrExposureBracketSupport)"))
        val bracketAuthority = shader.substringAfter("if (hdrExposureBracketSupport) {")
            .substringBefore("} else {")
        assertTrue(bracketAuthority.contains("sourceAuthority"))
        assertTrue(bracketAuthority.contains("if (sourceAuthority < 0.01) return false"))
        assertTrue(shader.contains("temporalMismatchGate"))
        assertTrue(shader.contains("if (!hdr || hdrTemporalMainSupport)"))
        assertFalse(
            shader.substringBefore("if (hdrExposureBracketSupport) {")
                .contains("sourceAuthority")
        )
    }

    @Test fun `a support only counts as merged after weighted pixels actually contributed`() {
        val backend = File(appDir, "src/main/cpp/vulkan/VulkanRawMultiFrameBackend.cpp").readText()
        val merger = File(appDir, "src/main/cpp/DngMerger.cpp").readText()
        val runner = File(appDir, "src/main/java/com/bncam/core/runners/MultiFrameRunner.kt").readText()

        assertTrue(backend.contains("supportStatsWords[3] = 0u"))
        assertTrue(backend.contains("sr.fusionContributedPixels = supportStatsWords[3]"))
        assertTrue(backend.contains("if (sr.fusionContributedPixels == 0u)"))
        assertTrue(backend.contains("sr.acceptedForFusion=true"))
        assertTrue(backend.contains("VULKAN_RAW_HDR_SAME_EXPOSURE_TEMPORAL_CONFIDENCE_FUSION"))
        assertTrue(merger.contains("hdrRole=\" + (support.hdrHighlightAuthority"))
        assertTrue(merger.contains("main_temporal"))
        assertTrue(merger.contains("fusionContributedPixels="))
        assertTrue(runner.contains("expectedTemporalFusionBackendMatched"))
        assertTrue(runner.contains("VULKAN_RAW_HDR_SAME_EXPOSURE_TEMPORAL_CONFIDENCE_FUSION"))
    }
}
