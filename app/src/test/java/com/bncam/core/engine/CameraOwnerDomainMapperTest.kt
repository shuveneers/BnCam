package com.bncam.core.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CameraOwnerDomainMapperTest {
    @Test
    fun `maps physical normalized position into logical crop`() {
        val mapped = CameraOwnerDomainMapper.mapCoordinates(
            physicalRegion = CameraOwnerDomainMapper.Bounds(3750, 2750, 4250, 3250),
            physicalBounds = CameraOwnerDomainMapper.Bounds(0, 0, 8000, 6000),
            logicalOwnerBounds = CameraOwnerDomainMapper.Bounds(1000, 750, 5000, 3750)
        )

        requireNotNull(mapped)
        assertEquals(CameraOwnerDomainMapper.Bounds(2875, 2125, 3125, 2375), mapped)
    }

    @Test
    fun `preserves edge containment and nonzero dimensions`() {
        val mapped = CameraOwnerDomainMapper.mapCoordinates(
            physicalRegion = CameraOwnerDomainMapper.Bounds(0, 0, 1, 1),
            physicalBounds = CameraOwnerDomainMapper.Bounds(0, 0, 8000, 6000),
            logicalOwnerBounds = CameraOwnerDomainMapper.Bounds(120, 80, 4120, 3080)
        )

        requireNotNull(mapped)
        assertEquals(CameraOwnerDomainMapper.Bounds(120, 80, 121, 81), mapped)
    }

    @Test
    fun `rejects invalid domains`() {
        assertNull(
            CameraOwnerDomainMapper.mapCoordinates(
                physicalRegion = CameraOwnerDomainMapper.Bounds(0, 0, 1, 1),
                physicalBounds = CameraOwnerDomainMapper.Bounds(0, 0, 0, 0),
                logicalOwnerBounds = CameraOwnerDomainMapper.Bounds(0, 0, 4000, 3000)
            )
        )
    }
}
