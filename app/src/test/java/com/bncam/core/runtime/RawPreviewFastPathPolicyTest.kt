package com.bncam.core.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RawPreviewFastPathPolicyTest {
    @Test
    fun `plain raw viewfinder needs no compact nv21 side image`() {
        assertFalse(
            RawPreviewFastPathPolicy.needsCompactNv21(
                RawPreviewAnalysisDemand(false, false, false, false, false)
            )
        )
    }

    @Test
    fun `object tracking allocates compact analysis only while tracking is active`() {
        assertFalse(
            RawPreviewFastPathPolicy.needsCompactNv21(
                RawPreviewAnalysisDemand(false, true, false, false, false)
            )
        )
        assertTrue(
            RawPreviewFastPathPolicy.needsCompactNv21(
                RawPreviewAnalysisDemand(false, true, true, false, false)
            )
        )
    }

    @Test
    fun `qr and portrait request image domain analysis`() {
        assertTrue(
            RawPreviewFastPathPolicy.needsCompactNv21(
                RawPreviewAnalysisDemand(true, false, false, false, false)
            )
        )
        assertTrue(
            RawPreviewFastPathPolicy.needsCompactNv21(
                RawPreviewAnalysisDemand(false, false, false, true, false)
            )
        )
    }

    @Test
    fun `capture suppresses tracking and portrait side image but keeps qr`() {
        assertFalse(
            RawPreviewFastPathPolicy.needsCompactNv21(
                RawPreviewAnalysisDemand(false, true, true, true, true)
            )
        )
        assertTrue(
            RawPreviewFastPathPolicy.needsCompactNv21(
                RawPreviewAnalysisDemand(true, true, true, true, true)
            )
        )
    }

    @Test
    fun `direct ahb input plus gpu resident output is primary fast path`() {
        assertEquals(
            RawPreviewFastPathKind.DIRECT_AHB_GPU_RESIDENT,
            RawPreviewFastPathPolicy.classify(true, true, false)
        )
    }

    @Test
    fun `gpu output with host input remains a measured fallback`() {
        assertEquals(
            RawPreviewFastPathKind.HOST_INPUT_GPU_RESIDENT,
            RawPreviewFastPathPolicy.classify(true, false, true)
        )
    }


    @Test
    fun `gpu resident output never reports cpu fallback when input provenance is unknown`() {
        assertEquals(
            RawPreviewFastPathKind.GPU_RESIDENT_COMPATIBILITY,
            RawPreviewFastPathPolicy.classify(true, false, false)
        )
    }

    @Test
    fun `non resident output is cpu visible fallback`() {
        assertEquals(
            RawPreviewFastPathKind.CPU_VISIBLE_RGBA_FALLBACK,
            RawPreviewFastPathPolicy.classify(false, true, false)
        )
    }
}
