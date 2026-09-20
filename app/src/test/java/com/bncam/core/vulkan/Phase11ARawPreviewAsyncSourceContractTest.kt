package com.bncam.core.vulkan

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Phase11ARawPreviewAsyncSourceContractTest {
    private val appDir: File = sequenceOf(File("."), File("app"))
        .firstOrNull { File(it, "src/main/cpp/vulkan/VulkanRawPreviewBackend.cpp").isFile }
        ?: error("Cannot locate app module from ${File(".").absolutePath}")

    private fun source(relative: String): String = File(appDir, relative).readText()

    @Test
    fun `display submission never blocks on a Vulkan fence`() {
        val backend = source("src/main/cpp/vulkan/VulkanRawPreviewBackend.cpp")
        val execute = backend.substringAfter("RawPreviewGpuResult VulkanRawPreviewBackend::execute(")
            .substringBefore("RawPreviewGpuResult VulkanRawPreviewBackend::pollCompletion(")
        val poll = backend.substringAfter("RawPreviewGpuResult VulkanRawPreviewBackend::pollCompletion(")
            .substringBefore("bool VulkanRawPreviewBackend::waitForIdle(")

        assertTrue(execute.contains("vkQueueSubmit"))
        assertFalse(execute.contains("vkWaitForFences(device"))
        assertTrue(execute.contains("result.completionPending = true"))
        assertTrue(execute.contains("const VkResult orphanStatus = vkGetFenceStatus(device, slot.fence)"))
        assertTrue(execute.contains("result.completionPending = false"))
        assertFalse(execute.contains("result.submissionId = slot.submissionId"))
        assertTrue(poll.contains("vkGetFenceStatus"))
        assertFalse(poll.contains("vkWaitForFences(device"))
        assertTrue(poll.contains("GPU_PREVIEW_COMPLETION_PENDING"))
    }

    @Test
    fun `full frame mutable resources stay slot local with one barrier protected scalar exposure state`() {
        val header = source("src/main/cpp/vulkan/VulkanRawPreviewBackend.h")
        val backend = source("src/main/cpp/vulkan/VulkanRawPreviewBackend.cpp")

        listOf(
            "PersistentBuffer inputStaging;",
            "PersistentBuffer deviceInput;",
            "PersistentBuffer toneLutBuffer;",
            "PersistentBuffer deviceStatistics;",
            "PersistentBuffer localToneBase;",
            "PersistentBuffer hueSatProfile;"
        ).forEach { assertTrue("missing slot-local resource $it", header.contains(it)) }
        assertTrue(backend.contains("queryInfo.queryCount = 2u * RAW_PREVIEW_FRAMES_IN_FLIGHT"))
        assertTrue(backend.contains("const std::uint32_t queryBase = slotIdx * 2u"))
        assertTrue(backend.contains("const std::uint32_t queryBase = frameSlotIndex * 2u"))
        assertFalse(header.contains("PersistentBuffer inputStaging_"))
        assertFalse(header.contains("PersistentBuffer deviceStatistics_"))
        // Phase 11C adds exactly one intentional cross-frame mutable scalar: the four-byte
        // exposure EMA state. It is not a frame/image resource and is explicitly GPU-barriered.
        assertTrue(header.contains("PersistentBuffer previewExposureState_;"))
        assertTrue(backend.contains("VkBufferMemoryBarrier exposureStateBarrier"))
        assertTrue(backend.contains("exposureStateBarrier.buffer = previewExposureState_.buffer"))
        assertFalse(header.contains("previousExposureGain_"))
    }

    @Test
    fun `statistics readback is a low cadence sidecar and not a display prerequisite`() {
        val backend = source("src/main/cpp/vulkan/VulkanRawPreviewBackend.cpp")
        val rawPreview = source("src/main/cpp/RawPreview.cpp")
        val renderer = source("src/main/java/com/bncam/ui/screens/capture/RawPreviewRenderer.kt")

        assertTrue(backend.contains("request.analysisReadbackRequested ? statisticsBytes : 0u"))
        assertTrue(backend.contains("if (request.analysisReadbackRequested)"))
        assertTrue(backend.contains("result.analysisReadbackPerformed = false"))
        assertTrue(renderer.contains("ANALYSIS_SIDECAR_INTERVAL_NS = 100_000_000L"))
        assertTrue(renderer.contains("analysisReadbackRequested = analysisReadbackRequested"))
        assertTrue(renderer.contains("analysisReadbackFrames"))
        assertTrue(renderer.contains("ASYNC_ANALYSIS_READBACK_INDEX = 625"))
        assertTrue(rawPreview.contains("previewGpu.analysisReadbackPerformed && awbEstimate.dataReady"))
    }

    @Test
    fun `Kotlin owns pending Vulkan submissions until a non blocking completion probe resolves them`() {
        val renderer = source("src/main/java/com/bncam/ui/screens/capture/RawPreviewRenderer.kt")
        val imageUtils = source("src/main/java/com/bncam/core/engine/ImageUtils.kt")
        val native = source("src/main/cpp/native-lib.cpp")

        assertTrue(renderer.contains("pendingVulkanFrames"))
        assertTrue(renderer.contains("pollVulkanCompletions()"))
        assertTrue(renderer.contains("RAW_PREVIEW_ASYNC_SUBMITTED_MAGIC"))
        assertTrue(renderer.contains("RAW_PREVIEW_ASYNC_PENDING_MAGIC"))
        assertTrue(renderer.contains("COMPLETION_POLL_INTERVAL_MS = 1L"))
        assertTrue(renderer.contains("else if (pendingVulkanFrames.isNotEmpty()) scheduleDrain(COMPLETION_POLL_INTERVAL_MS)"))
        assertTrue(imageUtils.contains("fun pollRawPreview("))
        assertTrue(imageUtils.contains("pollRawPreviewNative("))
        assertTrue(native.contains("Java_com_bncam_core_engine_ImageUtils_pollRawPreviewNative"))
        assertTrue(native.contains("RAW_PREVIEW_ASYNC_SUBMITTED_MAGIC"))
        assertTrue(native.contains("RAW_PREVIEW_ASYNC_PENDING_MAGIC"))
    }

    @Test
    fun `shutdown performs one bounded preview GPU drain outside display paths`() {
        val backend = source("src/main/cpp/vulkan/VulkanRawPreviewBackend.cpp")
        val runtime = source("src/main/cpp/vulkan/VulkanRuntime.cpp")
        val waitForIdle = backend.substringAfter("bool VulkanRawPreviewBackend::waitForIdle(")
            .substringBefore("void VulkanRawPreviewBackend::destroyLocked(")
        val destroy = backend.substringAfter("void VulkanRawPreviewBackend::destroyLocked(")

        assertTrue(waitForIdle.contains("vkWaitForFences"))
        assertTrue(waitForIdle.contains("deadline"))
        assertFalse(destroy.contains("vkWaitForFences"))
        assertTrue(runtime.contains("rawPreviewBackend_.waitForIdle(handles_.device, 500'000'000ull)"))
        assertTrue(runtime.contains("RAW_PREVIEW_SHUTDOWN_IN_FLIGHT_TIMEOUT"))
    }
}
