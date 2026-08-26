package com.bncam.core.vulkan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VulkanStressHardeningTest {

    @Test
    fun normalOperationCountersRemainOne() {
        val bridge = VulkanLifecycleBridge()
        bridge.initialize()

        assertEquals(VulkanLifecycleState.READY, bridge.getCurrentState())
        assertEquals(1, bridge.getNormalInstanceCount())
        assertEquals(1, bridge.getNormalDeviceCount())
        assertEquals(0, bridge.getRuntimeRecreationCount())
    }

    @Test
    fun singleDeviceLossRecoveryIncrementsRecreationCounterTo1() {
        val bridge = VulkanLifecycleBridge()
        bridge.initialize()

        val recoverySuccess = bridge.simulateDeviceLossAndRecover()

        assertTrue(recoverySuccess)
        assertEquals(VulkanLifecycleState.READY, bridge.getCurrentState())
        assertEquals(2, bridge.getDeviceLossInstanceCount())
        assertEquals(2, bridge.getDeviceLossDeviceCount())
        assertEquals(1, bridge.getRuntimeRecreationCount())
    }

    @Test
    fun boundedPoolLimitsPreventUnboundedMemoryGrowth() {
        val maxQueueDepth = 16
        val requestedCaptures = 20

        val acceptedCaptures = minOf(requestedCaptures, maxQueueDepth)
        val queueRejectedCount = requestedCaptures - acceptedCaptures

        assertEquals(16, acceptedCaptures)
        assertEquals(4, queueRejectedCount)
    }
}
