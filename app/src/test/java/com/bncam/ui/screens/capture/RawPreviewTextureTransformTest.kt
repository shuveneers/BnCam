package com.bncam.ui.screens.capture

import org.junit.Assert.assertArrayEquals
import org.junit.Test

class RawPreviewTextureTransformTest {
    @Test
    fun topLeftOriginBufferIsVerticallyCorrectedAtZeroDegrees() {
        assertArrayEquals(
            floatArrayOf(0f, 1f, 1f, 1f, 0f, 0f, 1f, 0f),
            RawPreviewTextureTransform.coordinates(0, mirrored = false),
            0f
        )
    }

    @Test
    fun clockwiseSensorRotationIsAppliedWithoutPixelCopy() {
        assertArrayEquals(
            floatArrayOf(1f, 1f, 1f, 0f, 0f, 1f, 0f, 0f),
            RawPreviewTextureTransform.coordinates(90, mirrored = false),
            0f
        )
    }

    @Test
    fun frontMirrorSwapsFinalDisplayColumns() {
        assertArrayEquals(
            floatArrayOf(1f, 1f, 0f, 1f, 1f, 0f, 0f, 0f),
            RawPreviewTextureTransform.coordinates(0, mirrored = true),
            0f
        )
    }

    @Test
    fun unityDigitalZoomKeepsExistingOrientationCoordinates() {
        assertArrayEquals(
            RawPreviewTextureTransform.coordinates(270, mirrored = true),
            RawPreviewTextureTransform.coordinates(270, mirrored = true, digitalZoom = 1f),
            0f
        )
    }

    @Test
    fun twoTimesDigitalZoomAppliesCenteredTextureCropAfterOrientation() {
        assertArrayEquals(
            floatArrayOf(0.25f, 0.75f, 0.75f, 0.75f, 0.25f, 0.25f, 0.75f, 0.25f),
            RawPreviewTextureTransform.coordinates(0, mirrored = false, digitalZoom = 2f),
            0f
        )
        assertArrayEquals(
            floatArrayOf(0.75f, 0.75f, 0.25f, 0.75f, 0.75f, 0.25f, 0.25f, 0.25f),
            RawPreviewTextureTransform.coordinates(0, mirrored = true, digitalZoom = 2f),
            0f
        )
    }

}
