package com.bncam.core.vulkan

import java.io.File
import org.junit.Test
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

class Phase13RawNormalizeSpectraBridgeSourceContractTest {
    private val appDir = File(System.getProperty("user.dir"))
    private fun source(path: String): String = File(appDir, path).readText()

    @Test
    fun spectraPreDemosaicAcceptsExternalResidentInputWithoutHostPixels() {
        val header = source("src/main/cpp/vulkan/VulkanSpectraResidentPreDemosaicBackend.h")
        val cpp = source("src/main/cpp/vulkan/VulkanSpectraResidentPreDemosaicBackend.cpp")
        assertTrue(header.contains("VkBuffer externalResidentInputBuffer = VK_NULL_HANDLE"))
        assertTrue(header.contains("std::uint64_t externalResidentInputBytes = 0u"))
        assertTrue(header.contains("bool externalResidentInputConsumed = false"))
        assertTrue(cpp.contains("AMBIGUOUS_INTERNAL_AND_EXTERNAL_RESIDENT_INPUT"))
        assertTrue(cpp.contains("EXTERNAL_RESIDENT_INPUT_SIZE_MISMATCH"))
        assertTrue(cpp.contains("sourceBuffer = request.externalResidentInputBuffer"))
        assertTrue(cpp.contains("result.externalResidentInputConsumed = true"))
    }

    @Test
    fun runtimeNormalizeToPass0BridgeKeepsProducerResolveAndConsumerDispatchAtomic() {
        val runtime = source("src/main/cpp/vulkan/VulkanRuntime.cpp")
        val bridge = runtime.substringAfter(
            "SpectraResidentPreDemosaicResult VulkanRuntime::executeSpectraResidentPreDemosaicPass0FromRawNormalize"
        ).substringBefore(
            "SpectraResidentPreDemosaicResult VulkanRuntime::executeSpectraResidentPreDemosaicPass1"
        )
        val lock = bridge.indexOf("std::lock_guard<std::mutex> submitLock(submissionMutex_)")
        val resolve = bridge.indexOf("rawJpegNormalizeBackend_.resolveResidentOutput")
        val assign = bridge.indexOf("resolved.externalResidentInputBuffer = normalizedMosaic")
        val execute = bridge.indexOf("spectraResidentPreDemosaicBackend_.executePass1")
        assertTrue(lock >= 0)
        assertTrue(resolve > lock)
        assertTrue(assign > resolve)
        assertTrue(execute > assign)
        assertTrue(bridge.contains("resolved.mosaicData = nullptr"))
        assertTrue(bridge.contains("RAW_JPEG_NORMALIZE_RESIDENT_GENERATION_INVALID"))
    }

    @Test
    fun externalResidentContractDoesNotExposeVulkanHandleThroughJni() {
        val imageUtils = source("src/main/java/com/bncam/core/engine/ImageUtils.kt")
        assertFalse(imageUtils.contains("VkBuffer"))
        assertFalse(imageUtils.contains("externalResidentInputBuffer"))
    }
}
