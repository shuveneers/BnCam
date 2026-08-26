package com.bncam.core.quality

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileToneTuningTest {
    @Test
    fun `neutral tone profile is render neutral`() {
        val tone = ProfileToneTuning()
        assertEquals(0f, tone.exposureEv, 0f)
        assertEquals(0f, tone.highlights, 0f)
        assertEquals(0f, tone.shadows, 0f)
        assertEquals(0f, tone.localToneBias, 0f)
    }

    @Test
    fun `profile exposure is bounded render ev not camera acquisition`() {
        assertEquals(2f, ProfileToneTuning(exposure = 1f).exposureEv, 0f)
        assertEquals(-2f, ProfileToneTuning(exposure = -1f).exposureEv, 0f)
        assertTrue(ProfileToneTuning(exposure = 0.5f).debugPairs().any {
            it.first == "Profile Tone Authority" && it.second.contains("never Camera2 shutter/ISO")
        })
    }

    @Test
    fun `tone controls sanitize invalid and out of range values`() {
        val safe = ProfileToneTuning(
            exposure = Float.NaN,
            highlights = 2f,
            shadows = -2f,
            whites = Float.POSITIVE_INFINITY,
            blacks = -0.25f,
            contrast = 0.4f,
            localToneBias = 3f
        ).sanitized()
        assertEquals(0f, safe.exposure, 0f)
        assertEquals(1f, safe.highlights, 0f)
        assertEquals(-1f, safe.shadows, 0f)
        assertEquals(0f, safe.whites, 0f)
        assertEquals(-0.25f, safe.blacks, 0f)
        assertEquals(0.4f, safe.contrast, 0f)
        assertEquals(1f, safe.localToneBias, 0f)
    }
}
