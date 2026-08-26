package com.bncam.core.quality

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpectraMilestone8PerformanceSourceContractTest {
    private fun appDir(): File = sequenceOf(File("."), File("app"))
        .firstOrNull { File(it, "src/main/cpp/IspCore.cpp").isFile }
        ?: error("Cannot locate app module")

    @Test
    fun `m8a deterministic fused tiled statistics remain active`() {
        val app = appDir()
        val core = File(app, "src/main/cpp/IspCore.cpp").readText()
        val helper = File(app, "src/main/cpp/SpectraPerformanceBackend.h").readText()

        assertTrue(helper.contains("collectFusedTiledRawStatistics"))
        assertTrue(helper.contains("Deterministic tile-order reduction"))
        assertTrue(core.contains("spectraPerformance.initialStatistics"))
        assertTrue(core.contains("spectraPerformance.postPass1Statistics"))
        assertTrue(core.contains("spectraPerformance.postPass2Statistics"))
        assertTrue(core.contains("budgetState.initialResidualEnergy = spectraPerformance.initialStatistics.residualEnergy"))
        assertTrue(core.contains("budgetState.pass2ChromaResidualEnergy"))
    }

    @Test
    fun `m8b contains real neon kernels and runtime scalar equivalence fallback`() {
        val app = appDir()
        val backend = File(app, "src/main/cpp/SpectraPerformanceBackend.h").readText()
        val neon = File(app, "src/main/cpp/SpectraPerformanceNeon.h").readText()
        val core = File(app, "src/main/cpp/IspCore.cpp").readText()

        assertTrue(neon.contains("#include <arm_neon.h>"))
        assertTrue(neon.contains("vld1_f32"))
        assertTrue(neon.contains("vadd_f32"))
        assertTrue(neon.contains("vmul_n_f32"))
        assertTrue(backend.contains("processTileSimd"))
        assertTrue(backend.contains("runSimdEquivalenceSelfTest"))
        assertTrue(backend.contains("PASSED_RUNTIME_NEON_SCALAR_EQUIVALENCE"))
        assertTrue(backend.contains("PerformanceBackendKind::FusedTiledSimd"))
        assertTrue(backend.contains("M8B_SCALAR_FALLBACK_NEON_SELF_TEST_FAILED"))
        assertTrue(core.contains("spectraPerformanceSimdValidationPassed"))
        assertTrue(core.contains("spectraPerformanceSimdVectorizedLaneCount"))
    }

    @Test
    fun `schema 13 exports simd validation dispatch lane memory and timing observability`() {
        val app = appDir()
        val trace = File(app, "src/main/java/com/bncam/core/quality/NoiseModelTrace.kt").readText()
        val schemaVersion = Regex("CURRENT_SCHEMA_VERSION\\s*=\\s*(\\d+)")
            .find(trace)?.groupValues?.get(1)?.toInt() ?: 0

        assertTrue(schemaVersion >= 13)
        assertTrue(trace.contains("\"performanceBackend\" to performanceBackendMap(stats)"))
        assertTrue(trace.contains("spectraPerformanceSimdValidationMaximumAbsoluteDelta"))
        assertTrue(trace.contains("spectraPerformanceSimdFallbackReason"))
        assertTrue(trace.contains("spectraPerformanceSimdDispatchCount"))
        assertTrue(trace.contains("spectraPerformanceSimdVectorizedLaneCount"))
        assertTrue(trace.contains("spectraPerformanceStatisticsBytesRead"))
        assertTrue(trace.contains("spectraPerformanceTimingAccounting"))
    }

    @Test
    fun `vulkan and mixed precision are still not falsely selected`() {
        val app = appDir()
        val helper = File(app, "src/main/cpp/SpectraPerformanceBackend.h").readText()

        assertTrue(helper.contains("selection.vulkanSelected = false"))
        assertTrue(helper.contains("FP16_DISABLED_CRITICAL_MATH_FP32"))
        assertTrue(helper.contains("VULKAN_FP32_NOT_QUALIFIED"))
        assertFalse(helper.contains("selection.selected = PerformanceBackendKind::VulkanFp32;"))
    }

    @Test
    fun `m8b does not alter dng fusion jni or ui routes`() {
        val app = appDir()
        val merger = File(app, "src/main/cpp/DngMerger.cpp").readText()
        val nativeBridge = File(app, "src/main/cpp/native-lib.cpp").readText()

        assertFalse(merger.contains("SpectraPerformanceBackend"))
        assertFalse(merger.contains("SpectraPerformanceNeon"))
        assertFalse(nativeBridge.contains("SpectraPerformanceBackend"))
        assertFalse(nativeBridge.contains("SpectraPerformanceNeon"))
    }
    @Test
    fun `m8c activates fused opponent tiles with guarded pixel neon and deterministic reduction`() {
        val app = appDir()
        val core = File(app, "src/main/cpp/IspCore.cpp").readText()
        val backend = File(app, "src/main/cpp/SpectraPixelBackend.h").readText()
        val neon = File(app, "src/main/cpp/SpectraPixelBackendNeon.h").readText()

        assertTrue(neon.contains("vld3q_f32"))
        assertTrue(neon.contains("opponentQuad"))
        assertTrue(backend.contains("runPixelKernelSelfTest"))
        assertTrue(backend.contains("PIXEL_NEON_EQUIVALENCE_AND_LATENCY_QUALIFIED"))
        assertTrue(backend.contains("PixelKernelBackendKind::TiledNeon"))
        assertTrue(backend.contains("benchmarkSpeedup >= 1.03f"))
        assertTrue(backend.contains("selfTest.passed && selfTest.latencyBenchmarkPassed"))
        assertTrue(backend.contains("PIXEL_NEON_LATENCY_NOT_BETTER_SCALAR_FALLBACK"))
        assertTrue(backend.contains("BNCAM_SPECTRA_PIXEL_NEON_FORCE_LATENCY_FAIL"))
        assertTrue(core.contains("buildOpponentTile"))
        assertTrue(core.contains("cv::Range(0, static_cast<int>(tileCount))"))
        assertTrue(core.contains("mutexFreeTileReduction = true"))
        assertTrue(core.contains("M8C_SHALLOW_SOURCE_ONE_WRITABLE_CLONE_PER_ACTIVE_BAND"))
        assertFalse(core.substringAfter("SpectraVisibleChromaState applySpectraVisibleChromaPass")
            .substringBefore("std::string SpectraVisibleChromaState::formatDebugString")
            .contains("std::mutex mergeMutex"))
    }

    @Test
    fun `schema 14 exports pixel backend clone and tile observability`() {
        val app = appDir()
        val trace = File(app, "src/main/java/com/bncam/core/quality/NoiseModelTrace.kt").readText()
        val visible = File(app, "src/main/java/com/bncam/core/quality/SpectraVisibleChroma.kt").readText()
        val core = File(app, "src/main/cpp/IspCore.cpp").readText()
        val schemaVersion = Regex("CURRENT_SCHEMA_VERSION\\s*=\\s*(\\d+)")
            .find(trace)?.groupValues?.get(1)?.toInt() ?: 0

        assertTrue(schemaVersion >= 14)
        assertTrue(visible.contains("spectraVisibleChromaPixelBackend"))
        assertTrue(visible.contains("mutexFreeDeterministicReduction"))
        assertTrue(visible.contains("opponentVectorizedPixelCount"))
        assertTrue(core.contains("spectraPass2PixelWorkingCloneCount"))
        assertTrue(core.contains("spectraPass2AvoidedFullFrameCloneCount"))
        assertTrue(core.contains("M8C_SHALLOW_SOURCE_ONE_WRITABLE_CLONE_PER_ACTIVE_BAND"))
    }

    @Test
    fun `m8c ships deterministic latency fallback regression fixture`() {
        val app = appDir()
        val fallback = File(app, "src/test/cpp/SpectraPixelBackendFallbackTest.cpp").readText()
        val neon = File(app, "src/test/cpp/SpectraPixelNeonTest.cpp")
        val visibleEquivalence = File(app, "src/test/cpp/SpectraPixelVisibleEquivalenceTest.cpp")

        assertTrue(neon.isFile)
        assertTrue(visibleEquivalence.isFile)
        assertTrue(fallback.contains("selfTestPassed"))
        assertTrue(fallback.contains("!selection.latencyBenchmarkPassed"))
        assertTrue(fallback.contains("PixelKernelBackendKind::TiledCpu"))
        assertTrue(fallback.contains("PIXEL_NEON_LATENCY_NOT_BETTER_SCALAR_FALLBACK"))
    }

    @Test
    fun `m8d records warm device evidence without persisting calibration`() {
        val app = appDir()
        val profiler = File(app, "src/main/cpp/SpectraDeviceBackendProfiler.h").readText()
        val core = File(app, "src/main/cpp/IspCore.cpp").readText()

        assertTrue(profiler.contains("class DeviceBackendProfiler"))
        assertTrue(profiler.contains("warmupDiscardedCount"))
        assertTrue(profiler.contains("thermalDriftRatio"))
        assertTrue(profiler.contains("DEVICE_PROFILE_READY_THROTTLING_DETECTED"))
        assertTrue(profiler.contains("VULKAN_QUALIFICATION_MAY_RUN_IF_PRODUCTION_KERNEL_EXISTS"))
        assertTrue(core.contains("deviceBackendProfiler().record"))
        assertTrue(core.contains("spectraPerformance.deviceProfile"))
        assertFalse(core.contains("persistDeviceBackendProfile"))
        assertFalse(core.contains("writeDeviceBackendProfile"))
    }

    @Test
    fun `m8d requires every vulkan fp32 qualification gate before selection`() {
        val app = appDir()
        val profiler = File(app, "src/main/cpp/SpectraDeviceBackendProfiler.h").readText()
        val core = File(app, "src/main/cpp/IspCore.cpp").readText()

        assertTrue(profiler.contains("evaluateVulkanFp32Qualification"))
        assertTrue(profiler.contains("NO_PRODUCTION_SPECTRA_VULKAN_FP32_KERNEL_CONNECTED"))
        assertTrue(profiler.contains("CPU_VULKAN_NUMERICAL_EQUIVALENCE_FAILED"))
        assertTrue(profiler.contains("VULKAN_TOTAL_PATH_NOT_AT_LEAST_1_15X_FASTER"))
        assertTrue(profiler.contains("TRANSFER_AND_SYNC_DOMINATE_VULKAN_PATH"))
        assertTrue(profiler.contains("THERMAL_STABILITY_GATE_FAILED"))
        assertTrue(profiler.contains("VULKAN_MEMORY_BUDGET_GATE_FAILED"))
        assertTrue(profiler.contains("out.selected = true"))
        assertTrue(core.contains("spectraVulkanProductionKernelConnected"))
        assertTrue(core.contains("compareOpponentFeatureFrameToScalar"))
        assertTrue(core.contains("spectraPerformance.vulkanQualification.selected"))
    }

    @Test
    fun `schema 15 exports device profile and vulkan gate evidence`() {
        val app = appDir()
        val trace = File(app, "src/main/java/com/bncam/core/quality/NoiseModelTrace.kt").readText()
        val core = File(app, "src/main/cpp/IspCore.cpp").readText()
        val schemaVersion = Regex("CURRENT_SCHEMA_VERSION\\s*=\\s*(\\d+)")
            .find(trace)?.groupValues?.get(1)?.toInt() ?: 0

        assertTrue(schemaVersion >= 15)
        assertTrue(trace.contains("\"deviceProfile\" to linkedMapOf"))
        assertTrue(trace.contains("spectraPerformanceDeviceProfileRouteKey"))
        assertTrue(trace.contains("spectraPerformanceDeviceProfileThermalDriftRatio"))
        assertTrue(trace.contains("spectraPerformanceDeviceProfileThrottlingDetected"))
        assertTrue(trace.contains("\"vulkanQualification\" to linkedMapOf"))
        assertTrue(trace.contains("spectraPerformanceVulkanProductionKernelConnected"))
        assertTrue(trace.contains("spectraPerformanceVulkanNumericalGatePassed"))
        assertTrue(trace.contains("spectraPerformanceVulkanBlockedReason"))
        assertTrue(trace.contains("spectraPerformanceDeviceQualificationMs"))
        assertTrue(core.contains("POST_TOTAL_RAW_ISP_DIAGNOSTIC_OVERHEAD_NOT_IN_PIXEL_PROCESSING"))
    }

}
