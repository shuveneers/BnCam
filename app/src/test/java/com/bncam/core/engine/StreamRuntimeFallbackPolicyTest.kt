package com.bncam.core.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamRuntimeFallbackPolicyTest {
    @Test
    fun `explicit resolution override falls back to auto then conservative and stops`() {
        assertEquals(
            StreamRuntimeFallbackTier.AUTO_GEOMETRY,
            StreamRuntimeFallbackPolicy.nextTier(
                explicitResolutionOverrideApplied = true,
                currentTier = StreamRuntimeFallbackTier.NONE
            )
        )
        assertEquals(
            StreamRuntimeFallbackTier.CONSERVATIVE_FULL_FOV,
            StreamRuntimeFallbackPolicy.nextTier(
                explicitResolutionOverrideApplied = false,
                currentTier = StreamRuntimeFallbackTier.AUTO_GEOMETRY
            )
        )
        assertNull(
            StreamRuntimeFallbackPolicy.nextTier(
                explicitResolutionOverrideApplied = false,
                currentTier = StreamRuntimeFallbackTier.CONSERVATIVE_FULL_FOV
            )
        )
    }

    @Test
    fun `native auto uses one conservative retry only`() {
        assertEquals(
            StreamRuntimeFallbackTier.CONSERVATIVE_FULL_FOV,
            StreamRuntimeFallbackPolicy.nextTier(
                explicitResolutionOverrideApplied = false,
                currentTier = StreamRuntimeFallbackTier.NONE
            )
        )
        assertNull(
            StreamRuntimeFallbackPolicy.nextTier(
                explicitResolutionOverrideApplied = false,
                currentTier = StreamRuntimeFallbackTier.CONSERVATIVE_FULL_FOV
            )
        )
    }

    @Test
    fun `override is scoped to exact stream authority`() {
        val override = StreamRuntimeFallbackOverride(
            tier = StreamRuntimeFallbackTier.AUTO_GEOMETRY,
            authorityFingerprint = "REQ=37:EFF=37:RAW_INDEX=1",
            settingsFingerprint = "RAW_INDEX=1:RES_FIX=OFF",
            failureReason = "test",
            failureCount = 1
        )
        assertTrue(override.appliesTo("REQ=37:EFF=37:RAW_INDEX=1"))
        assertFalse(override.appliesTo("REQ=37:EFF=37:RAW_INDEX=2"))
    }
}
