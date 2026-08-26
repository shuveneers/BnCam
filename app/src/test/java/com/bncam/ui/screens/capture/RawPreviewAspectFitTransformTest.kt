package com.bncam.ui.screens.capture

import org.junit.Assert.assertArrayEquals
import org.junit.Test

class RawPreviewAspectFitTransformTest {
    @Test
    fun rotatedFourByThreeRawUsesFullQuadInMatchingThreeByFourViewport() {
        assertArrayEquals(
            floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f),
            RawPreviewAspectFitTransform.vertices(
                clockwiseRotationDegrees = 90,
                sourceWidth = 4096,
                sourceHeight = 3072,
                viewportWidth = 1080,
                viewportHeight = 1440
            ),
            0.0001f
        )
    }

    @Test
    fun portraitPhoneShowsCompleteRawWithLetterboxInsteadOfTextureCrop() {
        assertArrayEquals(
            floatArrayOf(-1f, -0.6f, 1f, -0.6f, -1f, 0.6f, 1f, 0.6f),
            RawPreviewAspectFitTransform.vertices(
                clockwiseRotationDegrees = 90,
                sourceWidth = 4096,
                sourceHeight = 3072,
                viewportWidth = 1080,
                viewportHeight = 2400
            ),
            0.0001f
        )
    }

    @Test
    fun landscapeWideViewportPillarboxesNarrowSourceWithoutStretching() {
        assertArrayEquals(
            floatArrayOf(-0.421875f, -1f, 0.421875f, -1f, -0.421875f, 1f, 0.421875f, 1f),
            RawPreviewAspectFitTransform.vertices(
                clockwiseRotationDegrees = 90,
                sourceWidth = 4096,
                sourceHeight = 3072,
                viewportWidth = 1920,
                viewportHeight = 1080
            ),
            0.0001f
        )
    }
}
