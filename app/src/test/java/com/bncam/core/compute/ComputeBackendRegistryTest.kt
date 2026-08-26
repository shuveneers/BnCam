package com.bncam.core.compute

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ComputeBackendRegistryTest {
    @Test
    fun productionBackendIsCpuWhenUnattachedAndVulkanIsConnected() {
        assertEquals(
            "cpu_native_opencv",
            ComputeBackendRegistry.activeCaptureBackend().id
        )
        assertTrue(ComputeBackendRegistry.VULKAN.productionCaptureConnected)
        assertEquals(
            listOf("cpu_native_opencv", "vulkan"),
            ComputeBackendRegistry.descriptors.map { it.id }
        )
    }

    @Test
    fun vulkanIsActiveAndProductionConnected() {
        assertEquals(ComputeBackendStatus.ACTIVE, ComputeBackendRegistry.VULKAN.status)
        assertTrue(ComputeBackendRegistry.VULKAN.productionCaptureConnected)
        assertEquals("vulkan", ComputeBackendRegistry.VULKAN.id)
        assertTrue(
            ComputeCapability.AHARDWAREBUFFER_IMPORT in
                ComputeBackendRegistry.VULKAN.capabilities
        )
    }
}
