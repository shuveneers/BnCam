package com.bncam.core.capture

import com.bncam.core.engine.CaptureStrategy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HdrEnhancedMatrixRegressionTest {

    @Test
    fun singlePhotoNearZslBaselineRemainsUnaffected() {
        val res = CaptureAuthorityResolver.resolve(
            profileStrategy = CaptureStrategy.SINGLE_FRAME_ZSL,
            viewfinderMode = ViewfinderMode.PHOTO,
            computationalHdrUserRequested = false,
            ultraHdrRequested = false,
            dedicatedFlashStill = false,
            producesJpeg = true
        )

        assertEquals(CaptureStrategy.SINGLE_FRAME_ZSL, res.effectiveCaptureStrategy)
        assertEquals("SingleFrameRunner", res.actualRunner)
        assertFalse(res.computationalHdrRequested)
    }

    @Test
    fun multiPhotoNearZslBaselineRemainsUnaffected() {
        val res = CaptureAuthorityResolver.resolve(
            profileStrategy = CaptureStrategy.MULTI_FRAME_ZSL,
            viewfinderMode = ViewfinderMode.PHOTO,
            computationalHdrUserRequested = false,
            ultraHdrRequested = false,
            dedicatedFlashStill = false,
            producesJpeg = true
        )

        assertEquals(CaptureStrategy.MULTI_FRAME_ZSL, res.effectiveCaptureStrategy)
        assertEquals("MultiFrameRunner", res.actualRunner)
        assertFalse(res.computationalHdrRequested)
    }

    @Test
    fun computationalHdrUserRequestedRoutesToHdrEnhanced() {
        val res = CaptureAuthorityResolver.resolve(
            profileStrategy = CaptureStrategy.SINGLE_FRAME_ZSL,
            viewfinderMode = ViewfinderMode.PHOTO,
            computationalHdrUserRequested = true,
            ultraHdrRequested = false,
            dedicatedFlashStill = false,
            producesJpeg = true
        )

        assertEquals(CaptureStrategy.HDR_ENHANCED, res.effectiveCaptureStrategy)
        assertEquals("HdrEnhancedRunner", res.actualRunner)
        assertTrue(res.computationalHdrRequested)
        assertTrue(res.computationalHdrRouteEnabled)
    }

    @Test
    fun ultraHdrAloneDoesNotActivateHdrEnhanced() {
        val res = CaptureAuthorityResolver.resolve(
            profileStrategy = CaptureStrategy.SINGLE_FRAME_ZSL,
            viewfinderMode = ViewfinderMode.PHOTO,
            computationalHdrUserRequested = false,
            ultraHdrRequested = true,
            dedicatedFlashStill = false,
            producesJpeg = true
        )

        assertEquals(CaptureStrategy.SINGLE_FRAME_ZSL, res.effectiveCaptureStrategy)
        assertEquals("SingleFrameRunner", res.actualRunner)
        assertFalse(res.computationalHdrRequested)
        assertTrue(res.ultraHdrPackagingEnabled)
    }
}
