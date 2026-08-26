package com.bncam.core.capture

import com.bncam.core.engine.CaptureStrategy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureAuthorityMatrixRegressionTest {

    @Test
    fun `Test 1 - Single Photo uses SingleFrameRunner without HDR authority`() {
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
        assertFalse(res.computationalHdrRouteEnabled)
        assertFalse(res.ultraHdrPackagingEnabled)
        assertFalse(res.portraitRequested)
        assertFalse(res.nightPlanActive)
    }

    @Test
    fun `Test 2 - Multi Photo uses MultiFrameRunner without HDR authority`() {
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
        assertFalse(res.computationalHdrRouteEnabled)
        assertFalse(res.ultraHdrPackagingEnabled)
        assertFalse(res.portraitRequested)
        assertFalse(res.nightPlanActive)
    }

    @Test
    fun `Test 3 - Single + Ultra HDR preserves SingleFrameRunner and does NOT activate Computational HDR`() {
        val res = CaptureAuthorityResolver.resolve(
            profileStrategy = CaptureStrategy.SINGLE_FRAME_ZSL,
            viewfinderMode = ViewfinderMode.PHOTO,
            computationalHdrUserRequested = false,
            ultraHdrRequested = true,
            dedicatedFlashStill = false,
            producesJpeg = true
        )
        // Hard Invariants: Ultra HDR != Computational HDR
        // Ultra HDR must NOT change Single to Multi or activate Comp HDR route
        assertEquals(CaptureStrategy.SINGLE_FRAME_ZSL, res.effectiveCaptureStrategy)
        assertEquals("SingleFrameRunner", res.actualRunner)
        assertFalse(res.computationalHdrRequested)
        assertFalse(res.computationalHdrRouteEnabled)
        assertTrue(res.ultraHdrPackagingEnabled)
        assertFalse(res.portraitRequested)
    }

    @Test
    fun `Test 4 - Multi + Ultra HDR uses MultiFrameRunner with separate Ultra HDR packaging`() {
        val res = CaptureAuthorityResolver.resolve(
            profileStrategy = CaptureStrategy.MULTI_FRAME_ZSL,
            viewfinderMode = ViewfinderMode.PHOTO,
            computationalHdrUserRequested = false,
            ultraHdrRequested = true,
            dedicatedFlashStill = false,
            producesJpeg = true
        )
        assertEquals(CaptureStrategy.MULTI_FRAME_ZSL, res.effectiveCaptureStrategy)
        assertEquals("MultiFrameRunner", res.actualRunner)
        assertFalse(res.computationalHdrRequested)
        assertFalse(res.computationalHdrRouteEnabled)
        assertTrue(res.ultraHdrPackagingEnabled)
    }

    @Test
    fun `Test 5 - Single + Computational HDR resolves to Comp HDR route and MultiFrameRunner`() {
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
        assertEquals("computational_hdr_user_requested", res.overrideReason)
    }

    @Test
    fun `Test 6 - Multi + Computational HDR resolves to Comp HDR route`() {
        val res = CaptureAuthorityResolver.resolve(
            profileStrategy = CaptureStrategy.MULTI_FRAME_ZSL,
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
    fun `Test 7 - Single + Portrait preserves SingleFrameRunner and sets portraitRequested`() {
        val res = CaptureAuthorityResolver.resolve(
            profileStrategy = CaptureStrategy.SINGLE_FRAME_ZSL,
            viewfinderMode = ViewfinderMode.PORTRAIT,
            computationalHdrUserRequested = false,
            ultraHdrRequested = false,
            dedicatedFlashStill = false,
            producesJpeg = true
        )
        assertEquals(CaptureStrategy.SINGLE_FRAME_ZSL, res.effectiveCaptureStrategy)
        assertEquals("SingleFrameRunner", res.actualRunner)
        assertTrue(res.portraitRequested)
        assertFalse(res.computationalHdrRequested)
    }

    @Test
    fun `Test 8 - Multi + Portrait uses MultiFrameRunner and sets portraitRequested`() {
        val res = CaptureAuthorityResolver.resolve(
            profileStrategy = CaptureStrategy.MULTI_FRAME_ZSL,
            viewfinderMode = ViewfinderMode.PORTRAIT,
            computationalHdrUserRequested = false,
            ultraHdrRequested = false,
            dedicatedFlashStill = false,
            producesJpeg = true
        )
        assertEquals(CaptureStrategy.MULTI_FRAME_ZSL, res.effectiveCaptureStrategy)
        assertEquals("MultiFrameRunner", res.actualRunner)
        assertTrue(res.portraitRequested)
    }

    @Test
    fun `Test 9 - Night mode activates Night plan and MultiFrameRunner`() {
        val res = CaptureAuthorityResolver.resolve(
            profileStrategy = CaptureStrategy.SINGLE_FRAME_ZSL,
            viewfinderMode = ViewfinderMode.NIGHT,
            computationalHdrUserRequested = false,
            ultraHdrRequested = false,
            dedicatedFlashStill = false,
            producesJpeg = true
        )
        assertEquals(CaptureStrategy.MULTI_FRAME_ZSL, res.effectiveCaptureStrategy)
        assertEquals("MultiFrameRunner", res.actualRunner)
        assertTrue(res.nightPlanActive)
        assertFalse(res.computationalHdrRouteEnabled)
    }

    @Test
    fun `Dedicated flash overrides strategy to SingleFrameRunner`() {
        val res = CaptureAuthorityResolver.resolve(
            profileStrategy = CaptureStrategy.MULTI_FRAME_ZSL,
            viewfinderMode = ViewfinderMode.NIGHT,
            computationalHdrUserRequested = true,
            ultraHdrRequested = true,
            dedicatedFlashStill = true,
            producesJpeg = true
        )
        assertEquals(CaptureStrategy.SINGLE_FRAME_ZSL, res.effectiveCaptureStrategy)
        assertEquals("SingleFrameRunner", res.actualRunner)
        assertEquals("dedicated_flash_still", res.overrideReason)
        assertFalse(res.computationalHdrRouteEnabled)
        assertFalse(res.nightPlanActive)
    }
}
