package com.bncam.core.vulkan

import java.io.File
import org.junit.Test
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

class Phase13RawResidentOutputSourceContractTest {
    private val appDir = File(System.getProperty("user.dir"))

    private fun source(path: String): String = File(appDir, path).readText()

    @Test
    fun rawMultiFramePublishesOpaqueResidentOutputOnlyAfterSuccessfulTransaction() {
        val header = source("src/main/cpp/vulkan/VulkanRawMultiFrameBackend.h")
        val cpp = source("src/main/cpp/vulkan/VulkanRawMultiFrameBackend.cpp")

        assertTrue(header.contains("bool residentOutputProduced = false"))
        assertTrue(header.contains("std::uint64_t residentOutputGeneration = 0u"))
        assertTrue(header.contains("bool resolveResidentOutput("))
        assertTrue(cpp.contains("residentOutputGeneration_=0u"))
        assertTrue(cpp.contains("out.residentOutputProduced=true"))
        assertTrue(cpp.contains("out.residentOutputGeneration=request.generationId"))

        val readbackCompleteIndex = cpp.indexOf("out.finalReadbackPerformed=true")
        val publishIndex = cpp.indexOf("out.residentOutputProduced=true")
        val successIndex = cpp.indexOf("out.success=true")
        assertTrue(readbackCompleteIndex >= 0)
        assertTrue("Resident output must not be published before the existing RAW transaction has completed its publication readback", publishIndex > readbackCompleteIndex)
        assertTrue("The terminal success flag must follow resident generation publication", successIndex > publishIndex)
    }

    @Test
    fun residentResolverIsGenerationAndDimensionGuarded() {
        val cpp = source("src/main/cpp/vulkan/VulkanRawMultiFrameBackend.cpp")
        val resolver = cpp.substringAfter("bool VulkanRawMultiFrameBackend::resolveResidentOutput(")
            .substringBefore("RawMultiFrameResult VulkanRawMultiFrameBackend::execute(")

        assertTrue(resolver.contains("generation==0u"))
        assertTrue(resolver.contains("generation!=residentOutputGeneration_"))
        assertTrue(resolver.contains("finalRaw_.buffer==VK_NULL_HANDLE"))
        assertTrue(resolver.contains("residentOutputBytes_==0u"))
        assertTrue(resolver.contains("residentOutputWidth_==0u"))
        assertTrue(resolver.contains("residentOutputHeight_==0u"))
    }

    @Test
    fun dngPublicationReadbackIsStillExplicitlyRetainedAtThisCheckpoint() {
        val cpp = source("src/main/cpp/vulkan/VulkanRawMultiFrameBackend.cpp")
        assertTrue(cpp.contains("vkCmdCopyBuffer(cmd,finalRaw_.buffer,readback_.buffer"))
        assertTrue(cpp.contains("out.fullFrameGpuReadbackBytes=rawBytes"))
        assertTrue(cpp.contains("out.finalReadbackPerformed=true"))
        assertFalse(cpp.contains("out.outputRaw16.clear(); // Phase13 production no-readback"))
    }
}
