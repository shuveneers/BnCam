package com.bncam.core.capture

import com.bncam.core.engine.CaptureStrategy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class HdrEnhancedRollbackTest {

    @Test
    fun subsequentSinglePhotoAfterInterruptedHdrEnhancedRestoresZslBaseline() {
        // Simulated state: After an interrupted or failed HDR Enhanced capture
        val fallbackResolution = CaptureAuthorityResolver.resolve(
            profileStrategy = CaptureStrategy.SINGLE_FRAME_ZSL,
            viewfinderMode = ViewfinderMode.PHOTO,
            computationalHdrUserRequested = false,
            ultraHdrRequested = false,
            dedicatedFlashStill = false,
            producesJpeg = true
        )

        assertEquals(CaptureStrategy.SINGLE_FRAME_ZSL, fallbackResolution.effectiveCaptureStrategy)
        assertEquals("SingleFrameRunner", fallbackResolution.actualRunner)
        assertFalse(fallbackResolution.computationalHdrRequested)
        assertFalse(fallbackResolution.computationalHdrRouteEnabled)
    }
}
