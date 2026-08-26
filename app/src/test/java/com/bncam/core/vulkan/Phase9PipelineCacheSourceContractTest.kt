package com.bncam.core.vulkan

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class Phase9PipelineCacheSourceContractTest {
    private val appDir = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
        .firstOrNull { File(it, "src/main/cpp/vulkan/VulkanRuntimeBootstrap.cpp").isFile }
        ?: error("Unable to locate app module")

    private val productionBackends = listOf(
        "VulkanRawCaptureBackend.cpp",
        "VulkanRawJpegNormalizeBackend.cpp",
        "VulkanRawMultiFrameBackend.cpp",
        "VulkanRawPreviewBackend.cpp",
        "VulkanSpectraOpponentBackend.cpp",
        "VulkanSpectraPass3PlannerBackend.cpp",
        "VulkanSpectraRawFinalizeBackend.cpp",
        "VulkanSpectraResidentChromaBackend.cpp",
        "VulkanSpectraResidentDemosaicBackend.cpp",
        "VulkanSpectraResidentPostDemosaicBackend.cpp",
        "VulkanSpectraResidentPreDemosaicBackend.cpp",
        "VulkanSpectraResidentToneBackend.cpp",
        "VulkanSpectraTemporalObserverBackend.cpp",
        "VulkanSpectraVisibleChromaBackend.cpp",
        "VulkanYuvExposureStatisticsBackend.cpp",
        "VulkanYuvMultiFrameBackend.cpp",
        "VulkanYuvSingleFrameBackend.cpp"
    )

    @Test
    fun `authoritative lifecycle cache is published and cleared by bootstrap owner`() {
        val bootstrap = File(appDir, "src/main/cpp/vulkan/VulkanRuntimeBootstrap.cpp").readText()
        assertTrue(bootstrap.contains("vkCreatePipelineCache"))
        assertTrue(bootstrap.contains("VulkanPipelineCacheRegistry::publish"))
        assertTrue(bootstrap.contains("VulkanPipelineCacheRegistry::clear"))
        assertTrue(bootstrap.indexOf("VulkanPipelineCacheRegistry::clear") < bootstrap.indexOf("vkDestroyPipelineCache"))
    }

    @Test
    fun `all production backend compute pipeline creation uses authoritative cache wrapper`() {
        var cachedCreateCalls = 0
        productionBackends.forEach { name ->
            val source = File(appDir, "src/main/cpp/vulkan/$name").readText()
            assertFalse(
                Regex("vkCreateComputePipelines\\s*\\(\\s*device\\s*,\\s*VK_NULL_HANDLE").containsMatchIn(source),
                "$name still bypasses the runtime pipeline cache"
            )
            cachedCreateCalls += Regex("VulkanPipelineCacheRegistry::createComputePipelines\\s*\\(")
                .findAll(source).count()
        }
        assertEquals(18, cachedCreateCalls)
    }

    @Test
    fun `cache host access is externally synchronized and non owning`() {
        val registry = File(appDir, "src/main/cpp/vulkan/VulkanPipelineCacheRegistry.h").readText()
        assertTrue(registry.contains("std::lock_guard<std::mutex>"))
        assertTrue(registry.contains("device_ == device ? cache_ : VK_NULL_HANDLE"))
        assertFalse(registry.contains("vkDestroyPipelineCache"))
        assertFalse(registry.contains("vkCreatePipelineCache"))
    }
}
