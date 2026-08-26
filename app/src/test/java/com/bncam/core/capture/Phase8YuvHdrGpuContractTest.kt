package com.bncam.core.capture

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Phase8YuvHdrGpuContractTest {
    private val appDir: File = sequenceOf(File("."), File("app"))
        .firstOrNull { File(it, "src/main/cpp/vulkan/VulkanYuvMultiFrameBackend.cpp").isFile }
        ?: error("Cannot locate app module")

    @Test fun `yuv hdr fusion is exposure aware confidence weighted and vulkan resident`() {
        val header = File(appDir, "src/main/cpp/vulkan/VulkanYuvMultiFrameBackend.h").readText()
        val backend = File(appDir, "src/main/cpp/vulkan/VulkanYuvMultiFrameBackend.cpp").readText()
        val shader = File(appDir, "src/main/cpp/vulkan/shaders/yuv_multiframe_fusion.comp").readText()

        assertTrue(header.contains("std::vector<float> exposureScaleToAnchor"))
        assertTrue(header.contains("bool computationalHdr = false"))
        assertTrue(backend.contains("VULKAN_YUV_HDR_BILINEAR_CONFIDENCE_LUMA_FUSION"))
        assertTrue(shader.contains("supportNative / scale"))
        assertTrue(shader.contains("highlightAuthority"))
        assertTrue(shader.contains("shadowAuthority"))
        assertTrue(shader.contains("ghostConfidence"))
        assertTrue(shader.contains("weights[index] += authority"))
        assertTrue(shader.contains("displaced/moving detail loses authority"))
        assertFalse(shader.contains("mix(exp(-18.0 * mismatch * mismatch), 1.0"))
        assertTrue(shader.contains("hdrReconstruct"))
    }

    @Test fun `yuv hdr cannot silently use cpu equal exposure fallback`() {
        val nativeLib = File(appDir, "src/main/cpp/native-lib.cpp").readText()
        val hdrGuard = nativeLib.substringAfter("} else if (computationalHdr) {")
            .substringBefore("} else {", missingDelimiterValue = "")
        assertTrue(nativeLib.contains("VULKAN_HDR_REQUIRED_ANCHOR_FAILSAFE"))
        assertTrue(nativeLib.contains("minimumCorrelation = computationalHdr ? 0.12f : 0.08f"))
        assertTrue(nativeLib.contains("preserving anchor instead of CPU pseudo-HDR"))
        assertTrue(hdrGuard.contains("yuvAlignmentCpuFallbackUsed = false"))
        assertTrue(hdrGuard.contains("yuvFusionCpuFallbackUsed = false"))
        assertFalse(hdrGuard.contains("CPU_OPENCV"))
    }

    @Test fun `jni and kotlin yuv hdr signatures carry the same hdr inputs`() {
        val imageUtils = File(appDir, "src/main/java/com/bncam/core/engine/ImageUtils.kt").readText()
        val nativeLib = File(appDir, "src/main/cpp/native-lib.cpp").readText()
        val jniSignature = nativeLib.substringAfter("Java_com_bncam_core_engine_ImageUtils_processNativeYuv(")
            .substringBefore(") {")
        assertTrue(imageUtils.contains("exposureScaleToAnchor: FloatArray"))
        assertTrue(imageUtils.contains("computationalHdr: Boolean"))
        assertTrue(jniSignature.contains("jfloatArray exposureScaleToAnchorArray"))
        assertTrue(jniSignature.contains("jboolean computationalHdrFlag"))
    }
}
