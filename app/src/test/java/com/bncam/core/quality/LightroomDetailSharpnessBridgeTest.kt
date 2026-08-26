package com.bncam.core.quality

import com.bncam.data.settings.ProfileDetailDefaults
import com.bncam.data.settings.ProfileDetailSettings
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Retains the historical test filename so downstream test discovery remains stable, but the
 * transitional six-lane bridge no longer exists. These assertions now guard the four authoritative
 * Lightroom Detail controls themselves.
 */
class LightroomDetailSharpnessBridgeTest {
    @Test
    fun `lightroom detail defaults remain stable`() {
        val settings = ProfileDetailSettings().sanitized()
        assertEquals(ProfileDetailDefaults.AMOUNT, settings.amount, 0f)
        assertEquals(ProfileDetailDefaults.RADIUS, settings.radius, 0f)
        assertEquals(ProfileDetailDefaults.DETAIL, settings.detail, 0f)
        assertEquals(ProfileDetailDefaults.MASKING, settings.masking, 0f)
    }

    @Test
    fun `amount zero remains a valid hard off request`() {
        val settings = ProfileDetailSettings(amount = 0f).sanitized()
        assertEquals(0f, settings.amount, 0f)
    }

    @Test
    fun `detail controls clamp to lightroom ranges`() {
        val settings = ProfileDetailSettings(
            amount = 2f,
            radius = 8f,
            detail = -2f,
            masking = 4f
        ).sanitized()
        assertEquals(1f, settings.amount, 0f)
        assertEquals(ProfileDetailDefaults.MAX_RADIUS, settings.radius, 0f)
        assertEquals(0f, settings.detail, 0f)
        assertEquals(1f, settings.masking, 0f)
    }

    @Test
    fun `non finite values sanitize to lightroom defaults`() {
        val settings = ProfileDetailSettings(
            amount = Float.NaN,
            radius = Float.POSITIVE_INFINITY,
            detail = Float.NEGATIVE_INFINITY,
            masking = Float.NaN
        ).sanitized()
        assertEquals(ProfileDetailDefaults.AMOUNT, settings.amount, 0f)
        assertEquals(ProfileDetailDefaults.RADIUS, settings.radius, 0f)
        assertEquals(ProfileDetailDefaults.DETAIL, settings.detail, 0f)
        assertEquals(ProfileDetailDefaults.MASKING, settings.masking, 0f)
    }
}
