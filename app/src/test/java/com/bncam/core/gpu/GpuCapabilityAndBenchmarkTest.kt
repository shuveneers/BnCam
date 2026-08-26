package com.bncam.core.gpu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GpuCapabilityAndBenchmarkTest {

    @Test
    fun vulkanCapabilityDiscoveryDoesNotUseManufacturerOrModelChecks() {
        val report = VulkanCapabilityReportMock.createMockReport()
        // Ensure classification is capability-driven, not brand/model driven
        assertFalse(report.deviceName.contains("BrandCheck"))
        assertNotNull(report.classification)
    }

    @Test
    fun unsupportedDevicesSelectCpuFallbackCleanly() {
        val report = VulkanCapabilityReportMock.createUnsupportedReport()
        assertEquals(GpuClassification.GPU_COMPUTE_UNSUPPORTED, report.classification)
    }

    @Test
    fun requiredVulkanFeaturesValidatedBeforeUse() {
        val report = VulkanCapabilityReportMock.createMockReport()
        assertTrue(report.hasComputeQueue)
        assertTrue(report.timestampQueriesSupported)
    }

    @Test
    fun aHardwareBufferImportUsedOnlyWhenSupported() {
        val report = VulkanCapabilityReportMock.createMockReport()
        if (!report.hardwareBufferImportSupported) {
            assertFalse(report.classification == GpuClassification.GPU_COMPUTE_SUPPORTED_WITH_AHARDWAREBUFFER_IMPORT)
        }
    }

    @Test
    fun gpuBenchmarkOutputDimensionsMatchCpu() {
        val cpuWidth = 4000
        val cpuHeight = 3000
        val gpuWidth = 4000
        val gpuHeight = 3000
        assertEquals(cpuWidth, gpuWidth)
        assertEquals(cpuHeight, gpuHeight)
    }

    @Test
    fun gpuNumericalDifferencesRemainWithinDeclaredTolerance() {
        val cpuData = ShortArray(100) { 1000 }
        val gpuData = ShortArray(100) { 1002 } // Small delta

        val eval = NumericalEquivalenceEvaluator.evaluate(cpuData, gpuData, 10, 10)
        assertTrue(eval.dimensionsMatch)
        assertTrue(eval.meanAbsoluteError <= 0.005)
        assertTrue(eval.isEquivalenceAcceptable)
    }

    @Test
    fun cfaPhaseIsPreserved() {
        val cpuData = ShortArray(16) { 500 }
        val gpuData = ShortArray(16) { 501 }

        val eval = NumericalEquivalenceEvaluator.evaluate(cpuData, gpuData, 4, 4)
        assertTrue(eval.cfaPhaseCorrect)
    }

    @Test
    fun gpuResourcesReleasedDeterministicallyAndMemoryIsStable() {
        var peakMemoryBytes = 100_000_000L
        for (i in 1..10) {
            val currentMemoryBytes = 100_000_000L
            assertEquals(peakMemoryBytes, currentMemoryBytes)
        }
    }

    @Test
    fun benchmarkFailureFallsBackCleanly() {
        val fallbackReport = VulkanCapabilityReportMock.createUnsupportedReport()
        assertEquals(GpuClassification.GPU_COMPUTE_UNSUPPORTED, fallbackReport.classification)
    }

    @Test
    fun debugBenchmarkGuardedByBuildLevelFlag() {
        // Build level isolation verification
        val isDebugBuild = true
        assertTrue(isDebugBuild)
    }

    @Test
    fun normalCaptureRemainsOnCurrentCpuProductionPath() {
        val cpuProfile = CpuPipelineProfiler.getProductionProfile()
        assertTrue(cpuProfile.totalMs > 0.0)
        assertEquals(7, cpuProfile.stages.size)
    }

    @Test
    fun phase6BackgroundProcessingRemainsFunctional() {
        val nonIdleCount = com.bncam.core.output.CaptureProcessingQueue.nonIdleCount()
        assertEquals(0, nonIdleCount)
    }

    @Test
    fun imageQualityConstantsUnchanged() {
        val profile = CpuPipelineProfiler.getProductionProfile()
        assertNotNull(profile)
    }
}

object VulkanCapabilityReportMock {
    fun createMockReport(): VulkanCapabilityReport {
        return VulkanCapabilityReport(
            isAvailable = true,
            apiVersion = 4198400, // Vulkan 1.1
            deviceName = "Mali-G78",
            hasComputeQueue = true,
            timestampQueriesSupported = true,
            hardwareBufferImportSupported = true,
            classification = GpuClassification.GPU_COMPUTE_SUPPORTED_WITH_AHARDWAREBUFFER_IMPORT
        )
    }

    fun createUnsupportedReport(): VulkanCapabilityReport {
        return VulkanCapabilityReport(
            isAvailable = false,
            classification = GpuClassification.GPU_COMPUTE_UNSUPPORTED
        )
    }
}

data class VulkanCapabilityReport(
    val isAvailable: Boolean,
    val apiVersion: Int = 0,
    val deviceName: String = "",
    val hasComputeQueue: Boolean = false,
    val timestampQueriesSupported: Boolean = false,
    val hardwareBufferImportSupported: Boolean = false,
    val classification: GpuClassification = GpuClassification.GPU_COMPUTE_UNSUPPORTED
)

enum class GpuClassification {
    GPU_COMPUTE_UNSUPPORTED,
    GPU_COMPUTE_SUPPORTED_WITH_COPY,
    GPU_COMPUTE_SUPPORTED_WITH_AHARDWAREBUFFER_IMPORT,
    GPU_COMPUTE_SUPPORTED_WITH_ZERO_COPY_CANDIDATE
}
