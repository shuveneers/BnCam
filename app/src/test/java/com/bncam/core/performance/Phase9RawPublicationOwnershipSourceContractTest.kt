package com.bncam.core.performance

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class Phase9RawPublicationOwnershipSourceContractTest {
    private val appDir = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
        .firstOrNull { File(it, "src/main/cpp/DngMerger.cpp").isFile }
        ?: error("Unable to locate app module")

    @Test
    fun `gpu multiframe raw publication transfers existing vector storage without second full frame copy`() {
        val cpp = File(appDir, "src/main/cpp/DngMerger.cpp").readText()
        val gpuSuccess = cpp.substringAfter("auto gpuMulti = bncam::vulkan::VulkanRuntime::instance().executeRawMultiFrame")
            .substringBefore("// Computational HDR is never reinterpreted")

        assertTrue(gpuSuccess.contains("std::move(gpuMulti.outputRaw16)"))
        assertTrue(gpuSuccess.contains("trackNativeRaw16VectorAllocation"))
        assertFalse(gpuSuccess.contains("std::memcpy(nativePayload, gpuMulti.outputRaw16.data()"))
        assertFalse(gpuSuccess.contains("new (std::nothrow) uint8_t[totalBytes]"))
    }

    @Test
    fun `vector backed direct buffer remains under existing deterministic release registry`() {
        val cpp = File(appDir, "src/main/cpp/DngMerger.cpp").readText()
        assertTrue(cpp.contains("gNativeRaw16VectorOwners"))
        assertTrue(cpp.contains("vectorOwner = vectorFound->second"))
        assertTrue(cpp.contains("delete vectorOwner"))
        assertTrue(cpp.contains("gNativeRaw16ResidentGenerations[address] = residentGeneration"))
        assertTrue(cpp.contains("gNativeRaw16Allocations.erase(found)"))
    }

    @Test
    fun `raw resident readback is retained because compact planning and cpu failsafe still require valid host raw`() {
        val rawBackend = File(appDir, "src/main/cpp/vulkan/VulkanRawMultiFrameBackend.cpp").readText()
        val isp = File(appDir, "src/main/cpp/IspCore.cpp").readText()
        assertTrue(rawBackend.contains("out.finalReadbackPerformed=true"))
        assertTrue(isp.contains("residentInput->sampleView.masterRaw16"))
        assertTrue(isp.contains("ensureCpuWorkingRaw"))
    }
}
