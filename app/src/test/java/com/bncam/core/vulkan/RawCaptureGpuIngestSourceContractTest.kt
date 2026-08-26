package com.bncam.core.vulkan

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RawCaptureGpuIngestSourceContractTest {
    private fun source(path: String): String = File(path).readText()

    @Test
    fun processedRawDecodeIsVulkanPrimaryWithExplicitCpuReferenceFallbackOnly() {
        val merger = source("src/main/cpp/DngMerger.cpp")
        assertTrue(merger.contains("executeRawCaptureCanonicalize(request)"))
        val backend = source("src/main/cpp/vulkan/VulkanRawCaptureBackend.cpp")
        assertTrue(backend.contains("VULKAN_RAW_CAPTURE_CANONICALIZE"))
        assertTrue(merger.contains("CPU_REFERENCE_FALLBACK"))
        assertTrue(merger.contains("cpuFullFrameRawUnpack = true"))
        assertEquals(1, Regex("\\blockFn\\(").findAll(merger).count())
        assertEquals(1, Regex("\\bunpackFn\\(").findAll(merger).count())
        assertFalse(merger.contains("lockFn(anchor"))
        assertFalse(merger.contains("lockFn(support"))
    }

    @Test
    fun rawCanonicalizationShaderUsesAndroidRaw10LaneOrderAndSingleWriterWords() {
        val shader = source("src/main/cpp/vulkan/shaders/raw_capture_canonicalize.comp")
        assertTrue(shader.contains("(packedLow >> (lane * 2u)) & 0x03u"))
        assertTrue(shader.contains("uint wordIndex = gl_GlobalInvocationID.x"))
        assertTrue(shader.contains("outputWords[wordIndex] = lo | hi"))
        assertFalse(shader.contains("outputWords[pixel"))
    }

    @Test
    fun runtimeOwnsCaptureBackendAndTimeoutCannotMasqueradeAsCompletion() {
        val runtimeHeader = source("src/main/cpp/vulkan/VulkanRuntime.h")
        val runtime = source("src/main/cpp/vulkan/VulkanRuntime.cpp")
        val backend = source("src/main/cpp/vulkan/VulkanRawCaptureBackend.cpp")
        assertTrue(runtimeHeader.contains("VulkanRawCaptureBackend rawCaptureBackend_"))
        assertTrue(runtime.contains("rawCaptureBackend_.destroy(handles_.device)"))
        assertTrue(runtime.contains("result.submissionMayRemainInFlight"))
        assertTrue(runtime.contains("markGpuStalled(\"RAW_CAPTURE_CANONICALIZE\")"))
        assertTrue(backend.contains("result.submissionMayRemainInFlight = true"))
        assertTrue(backend.contains("RAW_CAPTURE_GPU_TIMEOUT"))
    }

    @Test
    fun migrationTelemetryDistinguishesPixelBackendFromTransferBoundaries() {
        val stats = source("src/main/cpp/DngMerger.h")
        val formatted = source("src/main/cpp/DngMerger.cpp")
        listOf(
            "inputImportPath",
            "rawUnpackBackend",
            "cpuFullFrameRawUnpack",
            "cpuFallbackUsed",
            "cpuFallbackReason",
            "fullFrameCpuUploadBytes",
            "fullFrameGpuReadbackBytes",
            "rawUnpackGpuMs"
        ).forEach { field ->
            assertTrue("missing telemetry field $field", stats.contains(field))
            assertTrue("telemetry not formatted $field", formatted.contains(";$field="))
        }
    }

    @Test
    fun shaderIsGeneratedAndBackendIsPartOfNativeTarget() {
        val cmake = source("src/main/cpp/CMakeLists.txt")
        assertTrue(cmake.contains("vulkan/VulkanRawCaptureBackend.cpp"))
        assertTrue(cmake.contains("raw_capture_canonicalize.comp"))
        assertTrue(cmake.contains("getRawCaptureCanonicalizeSpirv"))
        assertTrue(cmake.contains("bncam_raw_capture_canonicalize_shader"))
        assertTrue(cmake.contains("BNCAM_RAW_CAPTURE_CANONICALIZE_SHADER_AVAILABLE"))
    }
}
