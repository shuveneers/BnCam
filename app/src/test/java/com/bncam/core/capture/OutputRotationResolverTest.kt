package com.bncam.core.capture

import org.junit.Assert.assertEquals
import org.junit.Test

class OutputRotationResolverTest {
    @Test
    fun backCameraSensor90ResolvesAllDisplayRotations() {
        assertEquals(90, OutputRotationResolver.resolve(90, 0, frontFacing = false))
        assertEquals(0, OutputRotationResolver.resolve(90, 90, frontFacing = false))
        assertEquals(270, OutputRotationResolver.resolve(90, 180, frontFacing = false))
        assertEquals(180, OutputRotationResolver.resolve(90, 270, frontFacing = false))
    }

    @Test
    fun frontCameraUsesMirroredRotationDirection() {
        assertEquals(90, OutputRotationResolver.resolve(90, 0, frontFacing = true))
        assertEquals(180, OutputRotationResolver.resolve(90, 90, frontFacing = true))
        assertEquals(270, OutputRotationResolver.resolve(90, 180, frontFacing = true))
        assertEquals(0, OutputRotationResolver.resolve(90, 270, frontFacing = true))
    }

    @Test
    fun arbitraryInputsAreNormalized() {
        assertEquals(270, OutputRotationResolver.resolve(-90, 0, frontFacing = false))
        assertEquals(0, OutputRotationResolver.resolve(450, 90, frontFacing = false))
    }
}
