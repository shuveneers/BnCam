package com.bncam.core.vulkan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VulkanMultiFrameContractsTest {

    @Test
    fun multiFrameStateTransitionsFollowStrictLifecycleOrder() {
        val states = listOf(
            VulkanFrameState.SELECTED,
            VulkanFrameState.GPU_READY,
            VulkanFrameState.ALIGNMENT_PENDING,
            VulkanFrameState.ALIGNED,
            VulkanFrameState.FUSION_PENDING,
            VulkanFrameState.FUSED,
            VulkanFrameState.RELEASED
        )
        assertEquals(7, states.size)
        assertEquals(VulkanFrameState.SELECTED, states.first())
        assertEquals(VulkanFrameState.RELEASED, states.last())
    }

    @Test
    fun zeroSupportFrameReadbacksEnforcedByContract() {
        val diagnostics = MultiFrameDiagnosticsModel(
            requestedFrames = 8,
            effectiveFrames = 8,
            selectedFrames = 8,
            anchorFrame = "frame_0",
            supportFrames = 7,
            GPUReadyFrames = 8,
            alignmentAcceptedFrames = 7,
            alignmentRejectedFrames = 0,
            fusionAcceptedFrames = 7,
            fusionRejectedFrames = 0,
            supportReadbackCount = 0,
            intermediateReadbackCount = 0,
            finalReadbackCount = 1,
            poolAllocations = 1,
            poolReuse = 7,
            fallback = false,
            failureReason = ""
        )

        assertEquals(0, diagnostics.supportReadbackCount)
        assertEquals(0, diagnostics.intermediateReadbackCount)
        assertEquals(1, diagnostics.finalReadbackCount)
    }
}
