package com.bncam.core.performance

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class Phase9RawNormalizeReuseSourceContractTest {
    private val appDir = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
        .firstOrNull { File(it, "src/main/cpp/vulkan/VulkanRawJpegNormalizeBackend.cpp").isFile }
        ?: error("Unable to locate app module")

    @Test
    fun `raw jpeg normalize reuses submission resources and quarantines unsafe timeout`() {
        val cpp = File(appDir, "src/main/cpp/vulkan/VulkanRawJpegNormalizeBackend.cpp").readText()
        val execute = cpp.substringAfter("RawJpegNormalizeResult VulkanRawJpegNormalizeBackend::execute(")
        assertTrue(cpp.contains("ensureSubmissionResourcesLocked"))
        assertTrue(cpp.contains("vkResetCommandBuffer(command, 0u)"))
        assertTrue(cpp.contains("vkResetFences(device, 1u, &reusableFence_)"))
        assertTrue(cpp.contains("submissionResourcesUnsafe_ = true"))
        assertTrue(cpp.contains("destroySubmissionResourcesLocked(device)"))
        assertEquals(0, Regex("vkAllocateCommandBuffers\\s*\\(").findAll(execute).count())
        assertEquals(0, Regex("vkCreateFence\\s*\\(").findAll(execute).count())
        assertEquals(0, Regex("vkFreeCommandBuffers\\s*\\(").findAll(execute).count())
        assertEquals(0, Regex("vkDestroyFence\\s*\\(").findAll(execute).count())
    }

    @Test
    fun `normalize descriptor writes are identity cached without changing shader work`() {
        val cpp = File(appDir, "src/main/cpp/vulkan/VulkanRawJpegNormalizeBackend.cpp").readText()
        assertTrue(cpp.contains("boundInputBuffer_ != inputInfo.buffer || boundInputRange_ != inputInfo.range"))
        assertTrue(cpp.contains("boundOutputBuffer_ != outputInfo.buffer || boundOutputRange_ != outputInfo.range"))
        assertTrue(cpp.contains("out.descriptorBindingsReused"))
        assertTrue(cpp.contains("vkCmdDispatch(command, (request.width + 15u) / 16u"))
        assertFalse(cpp.contains("request.width / 2u"))
        assertFalse(cpp.contains("request.height / 2u"))
    }
}
