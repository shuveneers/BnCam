package com.bncam.core.quality

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpectraMilestone8GVulkanVisibleCandidateSourceContractTest {
    private fun appDir(): File = sequenceOf(File("."), File("app"))
        .firstOrNull { File(it, "src/main/cpp/IspCore.cpp").isFile }
        ?: error("Cannot locate app module")

    @Test
    fun `m8g connects existing fp32 visible candidate shader through bounded strips`() {
        val app = appDir()
        val core = File(app, "src/main/cpp/IspCore.cpp").readText()
        val backend = File(
            app,
            "src/main/cpp/vulkan/VulkanSpectraVisibleChromaBackend.cpp"
        ).readText()
        val runtime = File(app, "src/main/cpp/vulkan/VulkanRuntime.cpp").readText()
        val cmake = File(app, "src/main/cpp/CMakeLists.txt").readText()

        assertTrue(cmake.contains("vulkan/VulkanSpectraVisibleChromaBackend.cpp"))
        assertTrue(backend.contains("getIspChromaDenoiseSpirv"))
        assertTrue(backend.contains("SPECTRA_FP32_VISIBLE_CHROMA_CANDIDATE_READY"))
        assertTrue(backend.contains("VMA_ALLOCATION_CREATE_MAPPED_BIT"))
        assertTrue(runtime.contains("executeSpectraVisibleChromaCandidate"))
        assertTrue(runtime.contains("SPECTRA_FP32_VISIBLE_CHROMA_CANDIDATE_STRIPED"))
        assertTrue(core.contains("executeStripedVulkanVisibleCandidate"))
        assertTrue(core.contains("kMaximumVulkanVisibleCandidateTransientBytes"))
        assertTrue(core.contains("visibleCandidateExecution.successfulStripCount =="))
        assertTrue(core.contains("visibleCandidateExecution.stripCount"))
        assertTrue(core.contains("VISIBLE_CANDIDATE_STRIP_EXECUTION_ORDER_ALLOCATION_FAILED"))
        assertTrue(core.contains("std::max_element("))
    }

    @Test
    fun `m8g requires shader and final decision equivalence before next capture activation`() {
        val app = appDir()
        val core = File(app, "src/main/cpp/IspCore.cpp").readText()
        val qualifier = File(
            app,
            "src/main/cpp/SpectraVulkanVisibleChromaQualification.h"
        ).readText()

        assertTrue(qualifier.contains("samples_.size() >= 5u"))
        assertTrue(qualifier.contains("candidateMax <= 3.0e-6f"))
        assertTrue(qualifier.contains("finalMax <= 3.0e-6f"))
        assertTrue(qualifier.contains("out.measuredSpeedup < 1.15f"))
        assertTrue(qualifier.contains("out.transferFraction > 0.35f"))
        assertTrue(core.contains("visibleCandidateDeviceQualifiedBeforeCapture &&"))
        assertTrue(core.contains("candidateNumericalEquivalencePassed &&"))
        assertTrue(core.contains("finalDecisionCompatibilityPassed &&"))
        assertTrue(core.contains("!useVulkanVisibleCandidateFrame"))
        assertTrue(core.contains("visibleCandidateFinalDecisionComparedPixelCount > 0u"))
        assertTrue(core.contains("spectraVisibleChromaVulkanCandidateFinalDecisionComparedPixelCount"))
        assertTrue(core.contains("M8G_VISIBLE_CANDIDATE_VULKAN_FP32_QUALIFIED_NEXT_CAPTURE"))
    }

    @Test
    fun `cpu no regret remains authoritative and scope is not overstated`() {
        val app = appDir()
        val core = File(app, "src/main/cpp/IspCore.cpp").readText()
        val telemetry = File(app, "src/main/cpp/SpectraVisibleChroma.h").readText()
        val trace = File(
            app,
            "src/main/java/com/bncam/core/quality/SpectraVulkanVisibleCandidateTrace.kt"
        ).readText()

        assertTrue(core.contains("Milestone-4 CPU No-Regret evaluator remains the final authority"))
        assertTrue(core.contains("resolveVisibleChromaDecision"))
        assertTrue(telemetry.contains("CPU_AUTHORITATIVE_NO_REGRET_AFTER_VULKAN_CANDIDATE"))
        assertTrue(trace.contains("GPU_FILTER_CANDIDATE_ONLY_CPU_NO_REGRET_REMAINS_AUTHORITATIVE"))
        assertFalse(trace.contains("FULL_VULKAN_NO_REGRET_ACTIVE"))
        assertFalse(core.contains("PerformanceBackendKind::VulkanFp32;"))
    }

    @Test
    fun `schema 18 exports candidate qualification without touching protected pipelines`() {
        val app = appDir()
        val noiseTrace = File(
            app,
            "src/main/java/com/bncam/core/quality/NoiseModelTrace.kt"
        ).readText()
        val merger = File(app, "src/main/cpp/DngMerger.cpp").readText()
        val nativeBridge = File(app, "src/main/cpp/native-lib.cpp").readText()
        val schemaVersion = Regex("CURRENT_SCHEMA_VERSION\\s*=\\s*(\\d+)")
            .find(noiseTrace)?.groupValues?.get(1)?.toInt() ?: 0

        assertTrue(schemaVersion >= 18)
        assertTrue(noiseTrace.contains("\"vulkanVisibleChromaCandidate\""))
        assertFalse(merger.contains("VulkanSpectraVisibleChromaBackend"))
        assertFalse(nativeBridge.contains("VulkanSpectraVisibleChromaBackend"))
        assertFalse(merger.contains("SpectraVulkanVisibleChromaQualification"))
        assertFalse(nativeBridge.contains("SpectraVulkanVisibleChromaQualification"))
    }
}
