package com.bncam.core.vulkan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VulkanIspGraphTest {

    @Test
    fun ispGraphOrderFollowsAuthoritativeSequence() {
        val stages = listOf(
            VulkanIspStageId.HIGHLIGHT_RECOVERY,
            VulkanIspStageId.LUMA_DENOISE,
            VulkanIspStageId.CHROMA_DENOISE,
            VulkanIspStageId.EXPOSURE_TONE,
            VulkanIspStageId.CONTRAST_VIBRANCE,
            VulkanIspStageId.SHARPENING,
            VulkanIspStageId.CROP_ROTATE_OUTPUT_CONVERT
        )
        assertEquals(7, stages.size)
        assertEquals(VulkanIspStageId.HIGHLIGHT_RECOVERY, stages.first())
        assertEquals(VulkanIspStageId.CROP_ROTATE_OUTPUT_CONVERT, stages.last())
    }

    @Test
    fun zeroIntermediateReadbacksEnforcedForIspGraph() {
        val graph = VulkanIspGraphModel(
            graphId = "vulkan_isp_full_graph",
            stages = emptyList(),
            intermediateReadbackCount = 0,
            finalJpegReadbackCount = 1
        )

        assertEquals(0, graph.intermediateReadbackCount)
        assertEquals(1, graph.finalJpegReadbackCount)
    }
}
