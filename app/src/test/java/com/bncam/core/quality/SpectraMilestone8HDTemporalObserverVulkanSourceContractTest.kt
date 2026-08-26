package com.bncam.core.quality

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SpectraMilestone8HDTemporalObserverVulkanSourceContractTest {
    private fun source(path: String): String {
        val roots = listOf(File("."), File("app"), File(".."))
        val file = roots.asSequence().map { File(it, path) }.firstOrNull { it.exists() }
            ?: error("Source not found: $path")
        return file.readText()
    }

    @Test
    fun temporalObserverHasRealVulkanKernelAndRuntimeOwner() {
        val cmake = source("app/src/main/cpp/CMakeLists.txt")
        val shader = source("app/src/main/cpp/vulkan/shaders/spectra_temporal_observer.comp")
        val runtime = source("app/src/main/cpp/vulkan/VulkanRuntime.cpp")
        assertTrue(cmake.contains("spectra_temporal_observer.comp"))
        assertTrue(cmake.contains("getSpectraTemporalObserverSpirv"))
        assertTrue(shader.contains("runCoarse"))
        assertTrue(shader.contains("runObserver"))
        assertTrue(shader.contains("runStaticCells"))
        assertTrue(shader.contains("atomicAdd(histBuf.histogram"))
        assertTrue(runtime.contains("executeSpectraTemporalObserver"))
        assertTrue(runtime.contains("SPECTRA_FP32_TEMPORAL_OBSERVER_GPU_PRIMARY"))
    }

    @Test
    fun successfulGpuObserverSkipsLegacyFullFrameCpuObserver() {
        val merger = source("app/src/main/cpp/DngMerger.cpp")
        assertTrue(merger.contains("if (gpuResult.success)"))
        assertTrue(merger.contains("return observation;"))
        assertTrue(merger.contains("vulkanCpuFallbackUsed = gpuResult.attempted && !gpuResult.success"))
        assertFalse(merger.contains("gpuResult.success && gpuResult.valid" + ") {\n        // CPU shadow"))
    }

    @Test
    fun temporalTraceIsSchema22AndExposesGpuAuthority() {
        val noiseTrace = source("app/src/main/java/com/bncam/core/quality/NoiseModelTrace.kt")
        val trace = source("app/src/main/java/com/bncam/core/quality/SpectraVulkanTemporalObserverTrace.kt")
        val core = source("app/src/main/cpp/IspCore.cpp")
        assertTrue(noiseTrace.contains("CURRENT_SCHEMA_VERSION = 22"))
        assertTrue(noiseTrace.contains("vulkanTemporalObserver"))
        assertTrue(trace.contains("GPU_PIXEL_SCALE_OBSERVER_CPU_COMPACT_REDUCTION_ROBUST_FIT_AND_TYPED_FALLBACK_ONLY"))
        assertTrue(core.contains("spectraProductionBackend=VULKAN_GPU_PRIMARY_HYBRID_TRANSITION"))
    }
}
