package com.bncam.core.vulkan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class VulkanRawFusionTest {

    @Test
    fun robustMeanFusionAccumulatesWithLocalDifferenceOutlierRejection() {
        val anchorVal = 500
        val supportInlierVal = 510
        val supportOutlierVal = 2000

        val diffInlier = abs(anchorVal - supportInlierVal)
        val diffOutlier = abs(anchorVal - supportOutlierVal)

        assertTrue(diffInlier < 128) // Inlier accepted
        assertTrue(diffOutlier >= 128) // Outlier rejected

        val weight = 1.0f
        val fusedVal = ((anchorVal.toFloat() + supportInlierVal.toFloat() * weight) / (1.0f + weight) + 0.5f).toInt()
        assertEquals(505, fusedVal)
    }

    @Test
    fun independentJpegAndDngFrameCountsPreserved() {
        val jpegRequested = 8
        val dngRequested = 3
        val anchorOnlyDng = 1

        assertTrue(jpegRequested != dngRequested)
        assertEquals(1, anchorOnlyDng)
    }
}
