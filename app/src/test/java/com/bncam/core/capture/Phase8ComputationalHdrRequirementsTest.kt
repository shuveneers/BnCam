package com.bncam.core.capture

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Phase8ComputationalHdrRequirementsTest {
    private val appDir: File = sequenceOf(File("."), File("app"))
        .firstOrNull { File(it, "src/main/cpp/vulkan/shaders/raw_multiframe_fusion.comp").isFile }
        ?: error("Cannot locate app module")

    @Test fun `phase 8 production path covers acquisition alignment source selection and reconstruction`() {
        val manager = File(appDir, "src/main/java/com/bncam/core/engine/BnCameraManager.kt").readText()
        val rawBackend = File(appDir, "src/main/cpp/vulkan/VulkanRawMultiFrameBackend.cpp").readText()
        val rawShader = File(appDir, "src/main/cpp/vulkan/shaders/raw_multiframe_fusion.comp").readText()
        val yuvBackend = File(appDir, "src/main/cpp/vulkan/VulkanYuvMultiFrameBackend.cpp").readText()
        val yuvShader = File(appDir, "src/main/cpp/vulkan/shaders/yuv_multiframe_fusion.comp").readText()

        // HDR Enhanced is an exact deliberate post-shutter Camera2 RAW burst, not warm-buffer HDR.
        val acquisition = manager.substringAfter("private suspend fun acquireHdrEnhancedBurst(")
            .substringBefore("private suspend fun acquireComputationalHdrBracket(")
        assertTrue(acquisition.contains("submitBurstWithSharedProvenance("))
        assertTrue(acquisition.contains("awaitAndLeaseExactRequestFrame"))
        assertTrue(acquisition.contains("HDR_ENHANCED_MAIN_"))
        assertFalse(acquisition.contains("stopRepeating("))

        // Frame-level motion rejection/alignment and warping remain GPU-owned.
        assertTrue(rawBackend.contains("VULKAN_RAW_NCC_EXHAUSTIVE_FINE"))
        assertTrue(rawBackend.contains("request.spectra.enabled || request.computationalHdr"))
        assertTrue(rawBackend.contains("hdr_forward_backward_inconsistent"))
        assertTrue(yuvBackend.contains("VULKAN_YUV_NCC_COMPACT_SCOREGRID"))
        assertTrue(yuvShader.contains("sampleSupportBilinear"))

        // Pixel-level confidence masks choose highlight/shadow authority and suppress ghosts.
        assertTrue(rawShader.contains("highlightAuthority"))
        assertTrue(rawShader.contains("shadowAuthority"))
        assertTrue(rawShader.contains("ghostConfidence"))
        assertTrue(yuvShader.contains("highlightAuthority"))
        assertTrue(yuvShader.contains("shadowAuthority"))
        assertTrue(yuvShader.contains("ghostConfidence"))

        // Local source authority feeds a restrained global reconstruction rather than an HDR-look preset.
        assertTrue(rawShader.contains("Global reconstruction keeps black fixed"))
        assertTrue(rawShader.contains("leaves normal midtones nearly unchanged"))
        assertTrue(yuvShader.contains("hdrReconstruct"))
    }

    @Test fun `phase 8 computational hdr has no cpu pseudo hdr escape route`() {
        val merger = File(appDir, "src/main/cpp/DngMerger.cpp").readText()
        val nativeLib = File(appDir, "src/main/cpp/native-lib.cpp").readText()
        assertTrue(merger.contains("HDR_VULKAN_REQUIRED_"))
        assertTrue(nativeLib.contains("preserving anchor instead of CPU pseudo-HDR"))
        val rawHdrFailure = merger.substringAfter("if (computationalHdr) {")
            .substringBefore("// Explicit fallback/reference path")
        assertFalse(rawHdrFailure.contains("cpuFallbackUsed = true"))
    }

    @Test fun `raw provenance identifies hdr support role and exposure scale`() {
        val merger = File(appDir, "src/main/cpp/DngMerger.cpp").readText()
        assertTrue(merger.contains("exposureScaleToAnchor="))
        assertTrue(merger.contains("hdrRole="))
        assertTrue(merger.contains("support.hdrHighlightAuthority ? \"highlight\""))
        assertTrue(merger.contains("support.hdrShadowAuthority ? \"shadow\""))
    }
}
