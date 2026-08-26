package com.bncam.core.vulkan

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class Phase9SubmissionReuseSourceContractTest {
    private val appDir = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
        .firstOrNull { File(it, "src/main/cpp/vulkan/VulkanRawMultiFrameBackend.cpp").isFile }
        ?: error("Unable to locate app module")

    private fun source(path: String) = File(appDir, path).readText()

    @Test
    fun `runtime command pool supports per submission command reset`() {
        val bootstrap = source("src/main/cpp/vulkan/VulkanRuntimeBootstrap.cpp")
        assertTrue(bootstrap.contains("VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT"))
    }

    @Test
    fun `raw multiframe amortizes command and fence creation across backend lifetime`() {
        val cpp = source("src/main/cpp/vulkan/VulkanRawMultiFrameBackend.cpp")
        val execute = cpp.substringAfter("RawMultiFrameResult VulkanRawMultiFrameBackend::execute(")

        assertEquals(0, Regex("vkAllocateCommandBuffers\\s*\\(").findAll(execute).count())
        assertEquals(0, Regex("vkCreateFence\\s*\\(").findAll(execute).count())
        assertTrue(cpp.contains("reusableCommandBuffer_"))
        assertTrue(cpp.contains("reusableFence_"))
        assertTrue(execute.contains("vkResetCommandBuffer"))
        assertTrue(execute.contains("vkResetFences"))
        assertTrue(execute.contains("submissionResourcesUnsafe_=true"))
        assertFalse(execute.contains("vkFreeCommandBuffers(device,commandPool"))
    }

    @Test
    fun `yuv multiframe amortizes command and fence creation across backend lifetime`() {
        val cpp = source("src/main/cpp/vulkan/VulkanYuvMultiFrameBackend.cpp")
        val execute = cpp.substringAfter("YuvMultiFrameAlignmentResult VulkanYuvMultiFrameBackend::align(")

        assertEquals(0, Regex("vkAllocateCommandBuffers\\s*\\(").findAll(execute).count())
        assertEquals(0, Regex("vkCreateFence\\s*\\(").findAll(execute).count())
        assertTrue(cpp.contains("reusableCommandBuffer_"))
        assertTrue(cpp.contains("reusableFence_"))
        assertTrue(execute.contains("vkResetCommandBuffer"))
        assertTrue(execute.contains("vkResetFences"))
        assertTrue(execute.contains("submissionResourcesUnsafe_ = true"))
        assertFalse(execute.contains("vkFreeCommandBuffers(device, commandPool"))
    }

    @Test
    fun `timeout resources are quarantined rather than reset or destroyed in place`() {
        val raw = source("src/main/cpp/vulkan/VulkanRawMultiFrameBackend.cpp")
        val yuv = source("src/main/cpp/vulkan/VulkanYuvMultiFrameBackend.cpp")
        val runtime = source("src/main/cpp/vulkan/VulkanRuntime.cpp")

        assertTrue(raw.contains("RAW_MULTIFRAME_SUBMISSION_RESOURCES_QUARANTINED"))
        assertTrue(yuv.contains("YUV_MULTIFRAME_SUBMISSION_RESOURCES_QUARANTINED"))
        assertTrue(runtime.contains("if (result.submissionMayRemainInFlight)"))
        assertTrue(runtime.contains("markGpuStalled(\"RAW_MULTIFRAME_RESIDENT\")"))
        assertTrue(runtime.contains("markGpuStalled(\"YUV_MULTIFRAME_ALIGNMENT\")"))
        assertTrue(runtime.contains("VULKAN_SHUTDOWN_IN_FLIGHT_TIMEOUT"))
    }
}
