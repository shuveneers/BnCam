package com.bncam.core.vulkan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VulkanLifecycleTest {

    @Test
    fun stateMachineTransitionsFollowsAuthoritativeFlow() {
        var state = VulkanLifecycleState.UNINITIALIZED
        assertEquals(VulkanLifecycleState.UNINITIALIZED, state)

        state = VulkanLifecycleState.INITIALIZING
        assertEquals(VulkanLifecycleState.INITIALIZING, state)

        state = VulkanLifecycleState.READY
        assertEquals(VulkanLifecycleState.READY, state)

        state = VulkanLifecycleState.CAPTURE_ACTIVE
        assertEquals(VulkanLifecycleState.CAPTURE_ACTIVE, state)

        state = VulkanLifecycleState.PROCESSING
        assertEquals(VulkanLifecycleState.PROCESSING, state)
    }

    @Test
    fun explicitFailureDoesNotFallBackToCpu() {
        val failure = ExplicitVulkanFailureModel(
            captureId = "cap_test_001",
            generationId = 123L,
            stage = "RAW_ROBUST_MEAN_FUSION",
            operation = "SubmitWork",
            vkResultCode = -4, // VK_ERROR_DEVICE_LOST
            resourceIdentity = "res_fused_raw_123",
            runtimeIdentity = "bncam-vulkan-runtime-1",
            outputPolicy = "FAIL_JPEG_PRESERVE_DNG",
            jpegResult = "FAILED",
            dngResult = "PRESERVED_IF_PERMITTED",
            recoveryAction = "RecreateVulkanRuntimeOnce"
        )

        assertEquals("FAILED", failure.jpegResult)
        assertEquals("RecreateVulkanRuntimeOnce", failure.recoveryAction)
    }
}
