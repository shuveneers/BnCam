package com.bncam.core.engine

import com.bncam.data.settings.StreamConfigurationMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamRuntimeFallbackPolicyTest {
    @Test
    fun `validated falls back to auto then conservative and stops`() {
        assertEquals(
            StreamRuntimeFallbackTier.AUTO_GEOMETRY,
            StreamRuntimeFallbackPolicy.nextTier(
                StreamConfigurationMode.VALIDATED,
                StreamRuntimeFallbackTier.NONE
            )
        )
        assertEquals(
            StreamRuntimeFallbackTier.CONSERVATIVE_FULL_FOV,
            StreamRuntimeFallbackPolicy.nextTier(
                StreamConfigurationMode.VALIDATED,
                StreamRuntimeFallbackTier.AUTO_GEOMETRY
            )
        )
        assertNull(
            StreamRuntimeFallbackPolicy.nextTier(
                StreamConfigurationMode.VALIDATED,
                StreamRuntimeFallbackTier.CONSERVATIVE_FULL_FOV
            )
        )
    }

    @Test
    fun `auto uses one conservative retry only`() {
        assertEquals(
            StreamRuntimeFallbackTier.CONSERVATIVE_FULL_FOV,
            StreamRuntimeFallbackPolicy.nextTier(
                StreamConfigurationMode.AUTO,
                StreamRuntimeFallbackTier.NONE
            )
        )
        assertNull(
            StreamRuntimeFallbackPolicy.nextTier(
                StreamConfigurationMode.AUTO,
                StreamRuntimeFallbackTier.CONSERVATIVE_FULL_FOV
            )
        )
    }

    @Test
    fun `validated override is scoped to exact stored candidate`() {
        val override = StreamRuntimeFallbackOverride(
            tier = StreamRuntimeFallbackTier.AUTO_GEOMETRY,
            configuredMode = StreamConfigurationMode.VALIDATED,
            candidateId = "PHOTO:37:4080x3072",
            settingsFingerprint = "VALIDATED:PHOTO:37:4080x3072",
            failureReason = "test",
            failureCount = 1
        )
        assertTrue(override.appliesTo(StreamConfigurationMode.VALIDATED, "PHOTO:37:4080x3072"))
        assertFalse(override.appliesTo(StreamConfigurationMode.VALIDATED, "PHOTO:37:2040x1536"))
        assertFalse(override.appliesTo(StreamConfigurationMode.AUTO, null))
    }
}
