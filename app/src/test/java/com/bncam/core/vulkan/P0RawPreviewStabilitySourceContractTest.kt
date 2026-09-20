package com.bncam.core.vulkan

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class P0RawPreviewStabilitySourceContractTest {
    private val appDir: File = sequenceOf(File("."), File("app"))
        .firstOrNull { File(it, "src/main/cpp/vulkan/VulkanRuntime.cpp").isFile }
        ?: error("Cannot locate app module from ${File(".").absolutePath}")

    private fun source(relative: String): String = File(appDir, relative).readText()

    @Test
    fun `P0 RAW preview completes on its dedicated worker before publication`() {
        val renderer = source("src/main/java/com/bncam/ui/screens/capture/RawPreviewRenderer.kt")

        assertTrue(renderer.contains("awaitSubmittedVulkanCompletionOnWorker("))
        assertTrue(renderer.contains("P0_VULKAN_COMPLETION_TIMEOUT_MS = 250L"))
        assertTrue(renderer.contains("P0_NATIVE_VULKAN_SLOT_ID = 0"))
        assertTrue(renderer.contains("frameSlotIndex = P0_NATIVE_VULKAN_SLOT_ID"))
        assertTrue(renderer.contains("OUTPUT_SLOT_COUNT = 2"))
        assertTrue(renderer.contains("MAX_PENDING_REQUESTS = 1"))
    }

    @Test
    fun `P0 RAW preview and capture share the production compute queue`() {
        val runtime = source("src/main/cpp/vulkan/VulkanRuntime.cpp")
        val executePreview = runtime
            .substringAfter("RawPreviewGpuResult VulkanRuntime::executeRawPreview(")
            .substringBefore("RawPreviewGpuResult VulkanRuntime::pollRawPreview(")

        assertTrue(executePreview.contains("dedicatedPreviewQueue = false"))
        assertTrue(executePreview.contains("queue = handles_.computeQueue"))
        assertTrue(
            executePreview.contains(
                "dedicatedPreviewQueue ? &previewSubmissionMutex_ : &submissionMutex_"
            )
        )
    }
}
