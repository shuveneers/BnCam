package com.bncam.core.vulkan

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RawMultiFrameGpuResidencySourceContractTest {
    private fun source(path: String): String = File(path).readText()

    @Test
    fun multiFrameProductionAttemptsResidentVulkanBeforeCpuMaterialization() {
        val merger = source("src/main/cpp/DngMerger.cpp")
        val gpuStart = merger.indexOf("executeRawMultiFrame(gpuRequest)")
        val cpuAnchor = merger.indexOf("decodeFrame(anchor, anchorBayer16, anchorLayout)")
        val fallback = merger.indexOf("Explicit fallback/reference path", gpuStart)
        assertTrue(gpuStart >= 0)
        assertTrue(cpuAnchor > gpuStart)
        assertTrue(fallback > gpuStart && fallback < cpuAnchor)

        val success = merger.substring(gpuStart, fallback)
        assertFalse(success.contains("cv::phaseCorrelate"))
        assertFalse(success.contains("cv::warpAffine"))
        assertFalse(success.contains("mergeAlignedSupport("))
        assertFalse(success.contains("computeRaw16FullStats("))
        assertFalse(success.contains("decodeFrame("))
        assertTrue(success.contains("return directBuffer"))
    }

    @Test
    fun runtimeOwnsResidentRawMultiFrameBackendAndQuarantinesUnknownCompletion() {
        val header = source("src/main/cpp/vulkan/VulkanRuntime.h")
        val runtime = source("src/main/cpp/vulkan/VulkanRuntime.cpp")
        val backend = source("src/main/cpp/vulkan/VulkanRawMultiFrameBackend.cpp")
        assertTrue(header.contains("VulkanRawMultiFrameBackend rawMultiFrameBackend_"))
        assertTrue(runtime.contains("rawMultiFrameBackend_.destroy(handles_.device)"))
        assertTrue(runtime.contains("rawMultiFrameBackend_.execute("))
        assertTrue(runtime.contains("markGpuStalled(\"RAW_MULTIFRAME_RESIDENT\")"))
        assertTrue(backend.contains("submissionMayRemainInFlight=true"))
        assertTrue(backend.contains("vkFreeCommandBuffers(device,commandPool,1,&cmd)"))
    }

    @Test
    fun alignmentIsGpuCompactReadbackAndCfaEven() {
        val backend = source("src/main/cpp/vulkan/VulkanRawMultiFrameBackend.cpp")
        val shader = source("src/main/cpp/vulkan/shaders/raw_multiframe_alignment.comp")
        assertTrue(backend.contains("VULKAN_RAW_NCC_EXHAUSTIVE_FINE"))
        assertTrue(backend.contains("compactGpuReadbackBytes+=resultBytes"))
        assertTrue(backend.contains("coarseRadius=(maxShift/2u)*2u"))
        assertTrue(backend.contains("fineRadius=(std::min(4u,maxShift)/2u)*2u"))
        assertTrue(backend.contains("rev.accepted?spectra_temporal::forwardBackwardConsistency"))
        assertTrue(backend.contains(":0.0f"))
        assertTrue(backend.contains("request.spectra.enabled && fb < 0.08f"))
        assertTrue(backend.contains("forward_backward_inconsistent"))
        assertTrue(backend.contains("obs.supportWeight=obs.supportWeight*(0.65f+0.35f*sr.repeatedSupportConfidence)"))
        assertFalse(backend.contains("std::clamp(obs.supportWeight"))
        assertTrue(shader.contains("((dx & 1) == 0) && ((dy & 1) == 0)"))
        assertTrue(shader.contains("scoreBuf.scores[candidate]"))
        assertTrue(shader.contains("void reduceBestCandidate()"))
        assertTrue(shader.contains("resultBuf.bestResult[0]"))
        assertFalse(shader.contains("imageStore"))
    }

    @Test
    fun fusionUsesSingleWriterFinalRawAndGpuFinalStatistics() {
        val shader = source("src/main/cpp/vulkan/shaders/raw_multiframe_fusion.comp")
        val backend = source("src/main/cpp/vulkan/VulkanRawMultiFrameBackend.cpp")
        assertTrue(shader.contains("uint pixel0 = wordIndex * 2u"))
        assertTrue(shader.contains("finalBuf.words[wordIndex] ="))
        assertTrue(shader.contains("atomicMin(finalStatsBuf.values[0], v0)"))
        assertTrue(shader.contains("atomicMax(finalStatsBuf.values[1], v0)"))
        assertTrue(shader.contains("atomicAdd(finalStatsBuf.values[2], 1u)"))
        assertTrue(backend.contains("finalRawSaturatedCount"))
        assertTrue(backend.contains("finalRawSaturatedPct"))
        assertTrue(backend.contains("cpuAlignment=false"))
        assertTrue(backend.contains("cpuFusion=false"))
        assertTrue(backend.contains("cpuFullFrameSupportMaterialization=false"))
    }

    @Test
    fun spectraTemporalObserverConsumesResidentCanonicalRawWithoutFullFrameUpload() {
        val backend = source("src/main/cpp/vulkan/VulkanRawMultiFrameBackend.cpp")
        val observerHeader = source("src/main/cpp/vulkan/VulkanSpectraTemporalObserverBackend.h")
        val observer = source("src/main/cpp/vulkan/VulkanSpectraTemporalObserverBackend.cpp")
        val shader = source("src/main/cpp/vulkan/shaders/spectra_temporal_observer.comp")
        assertTrue(backend.contains("orq.residentAnchorRaw16Buffer=anchor_.buffer"))
        assertTrue(backend.contains("orq.residentSupportRaw16Buffer=support_.buffer"))
        assertTrue(observerHeader.contains("residentAnchorRaw16Buffer"))
        assertTrue(observerHeader.contains("residentSupportRaw16Buffer"))
        assertTrue(observer.contains("result.residentRawInputsUsed = residentInputs"))
        assertTrue(observer.contains("result.fullFrameCpuUploadBytes = anchorU16Bytes + supportU16Bytes"))
        assertTrue(observerHeader.contains("submissionMayRemainInFlight"))
        assertTrue(observer.contains("result.submissionMayRemainInFlight = true"))
        assertTrue(shader.contains("readonly buffer AnchorBuffer { uint anchorPacked[]; }"))
        assertTrue(shader.contains("float anchorRaw(uint index)"))
    }

    @Test
    fun cmakeBuildsBothProductionShadersAndTelemetryExposesBackendChoice() {
        val cmake = source("src/main/cpp/CMakeLists.txt")
        val stats = source("src/main/cpp/DngMerger.h")
        val formatted = source("src/main/cpp/DngMerger.cpp")
        listOf(
            "raw_multiframe_alignment.comp",
            "raw_multiframe_fusion.comp",
            "BNCAM_RAW_MULTIFRAME_ALIGNMENT_SHADER_AVAILABLE",
            "BNCAM_RAW_MULTIFRAME_FUSION_SHADER_AVAILABLE"
        ).forEach { assertTrue("missing native build contract $it", cmake.contains(it)) }
        listOf(
            "alignmentBackend",
            "fusionBackend",
            "cpuAlignment",
            "cpuFusion",
            "cpuFullFrameSupportMaterialization",
            "residentFusedRawProduced",
            "rawAlignmentGpuMs",
            "rawFusionGpuMs",
            "compactGpuReadbackBytes"
        ).forEach { field ->
            assertTrue("missing telemetry field $field", stats.contains(field))
            assertTrue("telemetry not serialized $field", formatted.contains(";$field="))
        }
    }
    @Test
    fun invalidSupportSourceIsRejectedWithoutForcingWholeCaptureToCpuFallback() {
        val backend = source("src/main/cpp/vulkan/VulkanRawMultiFrameBackend.cpp")
        assertTrue(backend.contains("enum class CanonicalizeOutcome"))
        assertTrue(backend.contains("CanonicalizeOutcome::FRAME_REJECT"))
        assertTrue(backend.contains("supportCanonicalize == CanonicalizeOutcome::FRAME_REJECT"))
        assertTrue(backend.contains("++out.supportRejected"))
        assertTrue(backend.contains("supportCanonicalize == CanonicalizeOutcome::BACKEND_FAILURE"))
        assertTrue(backend.contains("source_geometry_mismatch"))
    }

}
