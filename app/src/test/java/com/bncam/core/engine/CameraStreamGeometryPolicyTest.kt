package com.bncam.core.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraStreamGeometryPolicyTest {
    @Test
    fun `cropped preview recommendation cannot override standard full sensor aspect`() {
        val selected = CameraStreamGeometryPolicy.prioritizedFullFovPool(
            preferred = listOf(
                CameraStreamGeometryPolicy.Extent(1920, 1080),
                CameraStreamGeometryPolicy.Extent(1280, 720)
            ),
            standard = listOf(
                CameraStreamGeometryPolicy.Extent(1920, 1080),
                CameraStreamGeometryPolicy.Extent(1440, 1080),
                CameraStreamGeometryPolicy.Extent(4000, 3000)
            ),
            sensorWidth = 4000,
            sensorHeight = 3000
        )

        assertEquals(setOf(1440 to 1080, 4000 to 3000), selected.map { it.width to it.height }.toSet())
    }

    @Test
    fun `full fov optimized pool remains preferred when available`() {
        val selected = CameraStreamGeometryPolicy.prioritizedFullFovPool(
            preferred = listOf(
                CameraStreamGeometryPolicy.Extent(1440, 1080),
                CameraStreamGeometryPolicy.Extent(1920, 1080)
            ),
            standard = listOf(CameraStreamGeometryPolicy.Extent(4000, 3000)),
            sensorWidth = 4000,
            sensorHeight = 3000
        )

        assertEquals(listOf(1440 to 1080), selected.map { it.width to it.height })
    }

    @Test
    fun `orientation does not change full fov classification`() {
        val portrait = CameraStreamGeometryPolicy.fullFovCandidates(
            candidates = listOf(CameraStreamGeometryPolicy.Extent(1080, 1440)),
            sensorWidth = 4000,
            sensorHeight = 3000
        )
        assertTrue(portrait.isNotEmpty())
    }
}
