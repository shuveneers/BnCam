package com.bncam.core.quality

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpectraVulkanVisibleCandidateTraceTest {
    @Test
    fun `native stats preserve bounded gpu candidate and cpu no regret scope`() {
        val trace = SpectraVulkanVisibleCandidateTrace.fromNativeStats(
            mapOf(
                "spectraVisibleChromaVulkanCandidateKernelConnected" to "true",
                "spectraVisibleChromaVulkanCandidateAttempted" to "true",
                "spectraVisibleChromaVulkanCandidateExecutionSucceeded" to "true",
                "spectraVisibleChromaVulkanCandidateUsedForOutput" to "false",
                "spectraVisibleChromaVulkanCandidateBenchmarkPerformed" to "true",
                "spectraVisibleChromaVulkanCandidateShaderEquivalencePassed" to "true",
                "spectraVisibleChromaVulkanCandidateFinalDecisionCompatibilityPassed" to "false",
                "spectraVisibleChromaVulkanCandidateBenchmarkSampleCount" to "5",
                "spectraVisibleChromaVulkanCandidateFinalDecisionComparedPixelCount" to "12480",
                "spectraVisibleChromaVulkanCandidateCurrentShaderMaximumAbsoluteDelta" to "0.000001",
                "spectraVisibleChromaVulkanCandidateTransferFraction" to "0.21",
                "spectraVisibleChromaVulkanCandidateRouteKey" to "route-m8g",
                "spectraVisibleChromaVulkanCandidateNoRegretExecution" to
                    "CPU_AUTHORITATIVE_NO_REGRET_AFTER_VULKAN_CANDIDATE"
            )
        )

        assertTrue(trace.kernelConnected)
        assertTrue(trace.attempted)
        assertTrue(trace.shaderEquivalencePassed)
        assertFalse(trace.finalDecisionCompatibilityPassed)
        assertFalse(trace.usedForOutput)
        assertEquals(5, trace.benchmarkSampleCount)
        assertEquals(12480L, trace.finalDecisionComparedPixelCount)
        assertEquals("route-m8g", trace.routeKey)
        assertEquals(
            "CPU_AUTHORITATIVE_NO_REGRET_AFTER_VULKAN_CANDIDATE",
            trace.noRegretExecution
        )
        assertEquals(
            "GPU_FILTER_CANDIDATE_ONLY_CPU_NO_REGRET_REMAINS_AUTHORITATIVE",
            trace.toTraceMap()["scopeStatus"]
        )
    }

    @Test
    fun `non finite and negative native values are safely bounded`() {
        val trace = SpectraVulkanVisibleCandidateTrace.fromNativeStats(
            mapOf(
                "spectraVisibleChromaVulkanCandidateCurrentGpuTotalMs" to "Infinity",
                "spectraVisibleChromaVulkanCandidateTransferFraction" to "NaN",
                "spectraVisibleChromaVulkanCandidateInputBytes" to "-4",
                "spectraVisibleChromaVulkanCandidateStripCount" to "-2"
            )
        )
        val map = trace.toTraceMap()
        @Suppress("UNCHECKED_CAST")
        val benchmark = map["benchmark"] as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val strips = map["stripExecution"] as Map<String, Any?>
        assertEquals(0.0, benchmark["currentGpuTotalMs"])
        assertEquals(1.0, benchmark["transferFraction"])
        assertEquals(0L, strips["inputBytes"])
        assertEquals(0, strips["stripCount"])
    }
}
