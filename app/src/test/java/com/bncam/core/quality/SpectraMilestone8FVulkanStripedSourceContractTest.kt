package com.bncam.core.quality

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpectraMilestone8FVulkanStripedSourceContractTest {
    private fun appDir(): File = sequenceOf(File("."), File("app"))
        .firstOrNull { File(it, "src/main/cpp/IspCore.cpp").isFile }
        ?: error("Cannot locate app module")

    @Test
    fun `m8f uses bounded striped opponent execution with exact halo coverage`() {
        val app = appDir()
        val core = File(app, "src/main/cpp/IspCore.cpp").readText()
        val stripPlan = File(app, "src/main/cpp/SpectraVulkanOpponentStripPlan.h").readText()

        assertTrue(stripPlan.contains("buildVulkanOpponentStripPlan"))
        assertTrue(stripPlan.contains("copyVulkanOpponentStripInterior"))
        assertTrue(stripPlan.contains("M8F_STRIPED_OPPONENT_PLAN_READY"))
        assertTrue(stripPlan.contains("FULL_OUTPUT_FRAME_EXCEEDS_TRANSIENT_BUDGET"))
        assertTrue(stripPlan.contains("kStripSizedAllocationCount = 3u"))
        assertTrue(stripPlan.contains("stripOutputAndReadbackBytes"))
        assertTrue(core.contains("executeStripedVulkanOpponentFeatures"))
        assertTrue(core.contains("Prime the persistent VMA buffers with the largest strip"))
        assertTrue(core.contains("std::max_element("))
        assertTrue(core.contains("kVulkanOpponentTargetOutputRows = 256"))
        assertTrue(core.contains("kVulkanOpponentHaloRows = 2"))
        assertTrue(core.contains("320ull * 1024ull * 1024ull"))
        assertTrue(core.contains("execution.successfulStripCount == execution.stripCount"))
        assertTrue(core.contains("M8F_STRIPED_PERSISTENT_VULKAN_FP32_DEVICE_QUALIFIED_PREVIOUS_CAPTURE"))
        assertFalse(core.contains("VULKAN_OPPONENT_ESTIMATED_TRANSIENT_MEMORY_EXCEEDS_640_MIB"))
    }

    @Test
    fun `m8f reuses mapped vma buffers and destroys them before runtime teardown`() {
        val app = appDir()
        val backendHeader = File(
            app,
            "src/main/cpp/vulkan/VulkanSpectraOpponentBackend.h"
        ).readText()
        val backend = File(
            app,
            "src/main/cpp/vulkan/VulkanSpectraOpponentBackend.cpp"
        ).readText()
        val runtime = File(app, "src/main/cpp/vulkan/VulkanRuntime.cpp").readText()

        assertTrue(backendHeader.contains("ensurePersistentBuffersLocked"))
        assertTrue(backendHeader.contains("persistentBufferCapacityBytes_"))
        assertTrue(backend.contains("persistentBufferReuseHit = true"))
        assertTrue(backend.contains("persistentBufferReallocated = true"))
        assertTrue(backend.contains("destroyPersistentBuffersLocked"))
        assertTrue(backend.contains("vmaFlushAllocation(allocator, persistentInputAllocation_"))
        assertTrue(backend.contains("vmaInvalidateAllocation(allocator, persistentOutputAllocation_"))
        assertTrue(runtime.contains("SPECTRA_FP32_OPPONENT_FEATURES_STRIPED"))
        assertTrue(runtime.indexOf("spectraOpponentBackend_.destroy(handles_.device)") <
            runtime.indexOf("VulkanRuntimeBootstrap::destroy(handlesToDestroy)"))
    }

    @Test
    fun `schema 17 exports strip memory persistence and route evidence`() {
        val app = appDir()
        val trace = File(app, "src/main/java/com/bncam/core/quality/NoiseModelTrace.kt").readText()
        val core = File(app, "src/main/cpp/IspCore.cpp").readText()
        val schemaVersion = Regex("CURRENT_SCHEMA_VERSION\\s*=\\s*(\\d+)")
            .find(trace)?.groupValues?.get(1)?.toInt() ?: 0

        assertTrue(schemaVersion >= 17)
        assertTrue(trace.contains("\"stripedExecution\" to linkedMapOf"))
        assertTrue(trace.contains("\"persistentBuffers\" to linkedMapOf"))
        assertTrue(trace.contains("spectraVisibleChromaVulkanOpponentStripPlanStatus"))
        assertTrue(trace.contains("spectraVisibleChromaVulkanOpponentPersistentBufferReuseHitCount"))
        assertTrue(core.contains("spectraPerformanceMilestone="))
        assertTrue(core.contains("visibleChromaState.telemetry.vulkanOpponentRouteKey"))
    }

    @Test
    fun `m8f remains a hybrid stage and does not modify dng jni ui or no regret logic`() {
        val app = appDir()
        val core = File(app, "src/main/cpp/IspCore.cpp").readText()
        val merger = File(app, "src/main/cpp/DngMerger.cpp").readText()
        val nativeBridge = File(app, "src/main/cpp/native-lib.cpp").readText()

        assertTrue(core.contains("The visible-chroma filter and No-Regret decision remain"))
        assertFalse(core.contains("spectraPerformance.selection.selected = PerformanceBackendKind::VulkanFp32"))
        assertFalse(merger.contains("SpectraVulkanOpponentStripPlan"))
        assertFalse(nativeBridge.contains("SpectraVulkanOpponentStripPlan"))
        assertFalse(merger.contains("SPECTRA_FP32_OPPONENT_FEATURES_STRIPED"))
        assertFalse(nativeBridge.contains("SPECTRA_FP32_OPPONENT_FEATURES_STRIPED"))
    }
}
