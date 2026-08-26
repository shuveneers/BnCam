package com.bncam.core.capture

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Phase8RawHdrGpuContractTest {
    private val appDir: File = sequenceOf(File("."), File("app"))
        .firstOrNull { File(it, "src/main/cpp/vulkan/VulkanRawMultiFrameBackend.cpp").isFile }
        ?: error("Cannot locate app module")

    @Test fun `raw hdr fusion is exposure aware and vulkan resident`() {
        val header = File(appDir, "src/main/cpp/vulkan/VulkanRawMultiFrameBackend.h").readText()
        val backend = File(appDir, "src/main/cpp/vulkan/VulkanRawMultiFrameBackend.cpp").readText()
        val shader = File(appDir, "src/main/cpp/vulkan/shaders/raw_multiframe_fusion.comp").readText()
        assertTrue(header.contains("std::vector<float> exposureScaleToAnchor"))
        assertTrue(header.contains("bool computationalHdr = false"))
        assertTrue(backend.contains("VULKAN_RAW_HDR_EXPOSURE_AWARE_CONFIDENCE_FUSION"))
        assertTrue(shader.contains("supportNativeSignal / exposureScale"))
        assertTrue(shader.contains("highlightAuthority"))
        assertTrue(shader.contains("shadowAuthority"))
        assertTrue(shader.contains("ghostConfidence"))
        assertTrue(shader.contains("hdrReconstruct"))
        assertTrue(backend.contains("request.spectra.enabled || request.computationalHdr"))
        assertTrue(backend.contains("hdr_forward_backward_inconsistent"))
        assertTrue(shader.contains("unrelated moving detail does not get a special saturation bypass"))
        assertFalse(shader.contains("mix(exp(-20.0 * mismatch * mismatch), 1.0"))
    }

    @Test fun `hdr does not silently fall through to cpu equal exposure fusion`() {
        val merger = File(appDir, "src/main/cpp/DngMerger.cpp").readText()
        assertTrue(merger.contains("HDR_VULKAN_REQUIRED_"))
        val guard = merger.substringAfter("if (computationalHdr) {")
            .substringBefore("// Explicit fallback/reference path")
        assertTrue(guard.contains("return nullptr"))
        assertFalse(guard.contains("cpuFallbackUsed = true"))
    }
}
