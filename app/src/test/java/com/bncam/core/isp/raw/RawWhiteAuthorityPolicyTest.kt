package com.bncam.core.isp.raw

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RawWhiteAuthorityPolicyTest {
    @Test
    fun `auto prefers same-frame dynamic white over static white`() {
        val decision = RawWhiteAuthorityPolicy.resolve(
            dynamicWhiteLevel = 4012,
            staticWhiteLevel = 4095,
            requestedMode = RawWhiteAuthorityMode.AUTO
        )

        assertEquals(4012, decision.sourceWhiteLevel)
        assertTrue(decision.metadataAuthoritative)
        assertTrue(decision.dynamicMetadataAvailable)
        assertTrue(decision.staticMetadataAvailable)
        assertTrue(decision.source.startsWith("AUTO_DYNAMIC_METADATA"))
    }

    @Test
    fun `auto uses static white when dynamic is unavailable`() {
        val decision = RawWhiteAuthorityPolicy.resolve(
            dynamicWhiteLevel = null,
            staticWhiteLevel = 16383,
            requestedMode = RawWhiteAuthorityMode.AUTO
        )

        assertEquals(16383, decision.sourceWhiteLevel)
        assertEquals("dynamic_white_metadata_unavailable_static_used", decision.fallbackReason)
        assertFalse(decision.dynamicMetadataAvailable)
        assertTrue(decision.staticMetadataAvailable)
    }

    @Test
    fun `auto rejects invalid dynamic then uses valid static`() {
        val decision = RawWhiteAuthorityPolicy.resolve(
            dynamicWhiteLevel = 0,
            staticWhiteLevel = 1023,
            requestedMode = RawWhiteAuthorityMode.AUTO
        )

        assertEquals(1023, decision.sourceWhiteLevel)
        assertEquals("dynamic_white_metadata_invalid_static_used", decision.fallbackReason)
    }

    @Test
    fun `auto fabricates no sensor white when metadata is unavailable`() {
        val decision = RawWhiteAuthorityPolicy.resolve(
            dynamicWhiteLevel = null,
            staticWhiteLevel = null,
            requestedMode = RawWhiteAuthorityMode.AUTO
        )

        assertNull(decision.sourceWhiteLevel)
        assertFalse(decision.authorityAvailable)
        assertEquals("camera2_white_metadata_unavailable", decision.fallbackReason)
    }

    @Test
    fun `manual AGC preset wins over both metadata values`() {
        val decision = RawWhiteAuthorityPolicy.resolve(
            dynamicWhiteLevel = 4000,
            staticWhiteLevel = 4095,
            requestedMode = RawWhiteAuthorityMode.MANUAL,
            manualWhiteLevel = 16383
        )

        assertEquals(16383, decision.sourceWhiteLevel)
        assertTrue(decision.manualOverrideActive)
        assertFalse(decision.metadataAuthoritative)
    }

    @Test
    fun `every exposed AGC compatible preset is accepted exactly`() {
        listOf(1023, 4095, 16383, 65535).forEach { preset ->
            val decision = RawWhiteAuthorityPolicy.resolve(
                dynamicWhiteLevel = 4000,
                staticWhiteLevel = 4095,
                requestedMode = RawWhiteAuthorityMode.MANUAL,
                manualWhiteLevel = preset
            )
            assertEquals(preset, decision.sourceWhiteLevel)
            assertTrue(decision.manualOverrideActive)
        }
    }

    @Test
    fun `invalid manual value safely falls back to dynamic metadata`() {
        val decision = RawWhiteAuthorityPolicy.resolve(
            dynamicWhiteLevel = 3980,
            staticWhiteLevel = 4095,
            requestedMode = RawWhiteAuthorityMode.MANUAL,
            manualWhiteLevel = 12345
        )

        assertEquals(3980, decision.sourceWhiteLevel)
        assertFalse(decision.manualOverrideActive)
        assertTrue(decision.metadataAuthoritative)
        assertEquals("manual_white_override_invalid_dynamic_metadata_used", decision.fallbackReason)
    }

    @Test
    fun `invalid manual value and missing metadata leaves authority unavailable`() {
        val decision = RawWhiteAuthorityPolicy.resolve(
            dynamicWhiteLevel = null,
            staticWhiteLevel = null,
            requestedMode = RawWhiteAuthorityMode.MANUAL,
            manualWhiteLevel = 12345
        )

        assertNull(decision.sourceWhiteLevel)
        assertFalse(decision.authorityAvailable)
        assertEquals(
            "manual_white_override_invalid_and_camera2_white_metadata_unavailable",
            decision.fallbackReason
        )
    }
}
