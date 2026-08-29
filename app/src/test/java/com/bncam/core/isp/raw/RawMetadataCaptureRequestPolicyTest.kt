package com.bncam.core.isp.raw

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RawMetadataCaptureRequestPolicyTest {
    @Test
    fun `raw requests hot pixel map when true mode is advertised`() {
        val decision = RawMetadataCaptureRequestPolicy.resolveHotPixelMapRequest(
            rawPipeline = true,
            availableModes = booleanArrayOf(false, true)
        )
        assertTrue(decision.requested)
        assertTrue(decision.mapModeSupported)
        assertEquals("CAMERA2_HOT_PIXEL_MAP_ENABLED", decision.reason)
    }

    @Test
    fun `raw remains valid when optional capability key is missing`() {
        val decision = RawMetadataCaptureRequestPolicy.resolveHotPixelMapRequest(
            rawPipeline = true,
            availableModes = null
        )
        assertFalse(decision.requested)
        assertFalse(decision.capabilityReported)
        assertEquals("AVAILABLE_HOT_PIXEL_MAP_MODES_NOT_REPORTED", decision.reason)
    }

    @Test
    fun `raw does not invent support when only false is advertised`() {
        val decision = RawMetadataCaptureRequestPolicy.resolveHotPixelMapRequest(
            rawPipeline = true,
            availableModes = booleanArrayOf(false)
        )
        assertFalse(decision.requested)
        assertEquals("CAMERA2_HOT_PIXEL_MAP_UNAVAILABLE", decision.reason)
    }

    @Test
    fun `yuv never enables hot pixel map request`() {
        val decision = RawMetadataCaptureRequestPolicy.resolveHotPixelMapRequest(
            rawPipeline = false,
            availableModes = booleanArrayOf(false, true)
        )
        assertFalse(decision.requested)
        assertEquals("NON_RAW_PIPELINE", decision.reason)
    }
}
