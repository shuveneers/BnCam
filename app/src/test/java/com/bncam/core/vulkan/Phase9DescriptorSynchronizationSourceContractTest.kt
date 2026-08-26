package com.bncam.core.vulkan

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class Phase9DescriptorSynchronizationSourceContractTest {
    private val appDir = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
        .firstOrNull { File(it, "src/main/cpp/vulkan/VulkanYuvMultiFrameBackend.cpp").isFile }
        ?: error("Unable to locate app module")

    private fun source(path: String) = File(appDir, path).readText()

    @Test
    fun `yuv stable descriptors are bound once outside support loop`() {
        val cpp = source("src/main/cpp/vulkan/VulkanYuvMultiFrameBackend.cpp")
        val execute = cpp.substringAfter("YuvMultiFrameAlignmentResult VulkanYuvMultiFrameBackend::align(")
        val supportLoop = execute.substringAfter("for (std::size_t frameIndex = 1u;")
            .substringBefore("out.alignmentBackend =")

        assertEquals(2, Regex("bindBuffers\\(device,").findAll(execute).count())
        assertFalse(supportLoop.contains("bindBuffers(device"))
        assertTrue(execute.contains("out.descriptorSetUpdates"))
        assertTrue(execute.contains("out.descriptorWrites"))
    }

    @Test
    fun `yuv support upload and alignment share one submission`() {
        val cpp = source("src/main/cpp/vulkan/VulkanYuvMultiFrameBackend.cpp")
        val execute = cpp.substringAfter("YuvMultiFrameAlignmentResult VulkanYuvMultiFrameBackend::align(")
        val supportLoop = execute.substringAfter("for (std::size_t frameIndex = 1u;")
            .substringBefore("out.alignmentBackend =")

        assertFalse(execute.contains("YUV_MULTIFRAME_SUPPORT_UPLOAD"))
        assertTrue(supportLoop.contains("vkCmdCopyBuffer(command, staging_.buffer, support_.buffer"))
        assertTrue(supportLoop.contains("VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT"))
        assertTrue(supportLoop.contains("++out.combinedSupportUploadAlignmentSubmissions"))
    }

    @Test
    fun `raw fusion descriptor writes skip unchanged buffer identity and ranges`() {
        val cpp = source("src/main/cpp/vulkan/VulkanRawMultiFrameBackend.cpp")
        val execute = cpp.substringAfter("RawMultiFrameResult VulkanRawMultiFrameBackend::execute(")

        assertTrue(execute.contains("lastFusionDescriptorInfos"))
        assertTrue(execute.contains("a.buffer == b.buffer && a.offset == b.offset && a.range == b.range"))
        assertTrue(execute.contains("++out.fusionDescriptorSetUpdateSkips"))
        assertTrue(execute.contains("++out.fusionDescriptorSetUpdates"))
    }
}
