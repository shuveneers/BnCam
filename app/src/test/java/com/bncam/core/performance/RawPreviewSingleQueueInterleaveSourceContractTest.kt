package com.bncam.core.performance

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class RawPreviewSingleQueueInterleaveSourceContractTest {
    private fun source(path: String): String = File(path).readText()

    @Test
    fun previewHasIndependentCommandPoolEvenWhenSecondQueueIsUnavailable() {
        val bootstrap = source("src/main/cpp/vulkan/VulkanRuntimeBootstrap.cpp")
        assertTrue(bootstrap.contains("preview_command_pool"))
        assertTrue(bootstrap.contains("isolated_preview_pool"))
        assertTrue(bootstrap.contains("raw_multiframe_command_pool"))
        assertTrue(bootstrap.contains("isolated_capture_pool"))
        assertFalse(
            "Preview command-pool creation must not be conditional on a second queue",
            Regex("if \\(result\\.handles\\.previewQueue != VK_NULL_HANDLE\\) \\{\\s*const VkResult previewCmdPoolRes")
                .containsMatchIn(bootstrap)
        )
    }

    @Test
    fun rawMultiFrameLocksSharedQueuePerSubmissionInsteadOfWholeBackend() {
        val runtime = source("src/main/cpp/vulkan/VulkanRuntime.cpp")
        val backend = source("src/main/cpp/vulkan/VulkanRawMultiFrameBackend.cpp")
        assertTrue(runtime.contains("rawMultiFrameCommandPoolIsolated"))
        assertTrue(runtime.contains("*allocator, &submissionMutex_"))
        assertTrue(backend.contains("queueSubmissionMutex != nullptr"))
        assertTrue(backend.contains("queueSubmitLock(*queueSubmissionMutex)"))
        assertTrue(backend.contains("submit = vkQueueSubmit"))
    }

    @Test
    fun safeFullySerializedFallbackRemainsAvailable() {
        val runtime = source("src/main/cpp/vulkan/VulkanRuntime.cpp")
        assertTrue(runtime.contains("*allocator, nullptr"))
        assertTrue(runtime.contains("Safe fallback when a dedicated RAW multi-frame command pool could not be created"))
    }
}
