package com.bncam.data.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StreamConfigurationSettingsModelTest {
    @Test
    fun `auto is default for photo and video`() {
        val settings = LensStreamConfigurationSettings().sanitized()
        assertEquals(StreamConfigurationMode.AUTO, settings.photo.mode)
        assertEquals(StreamConfigurationMode.AUTO, settings.video.mode)
    }

    @Test
    fun `validated candidate is lens class local`() {
        val base = LensStreamConfigurationSettings()
        val updated = base.withClass(
            StreamConfigurationClass.PHOTO,
            StreamClassConfiguration(
                mode = StreamConfigurationMode.VALIDATED,
                validatedCandidateId = " PHOTO:37:4080x3072 "
            )
        )

        assertEquals(StreamConfigurationMode.VALIDATED, updated.photo.mode)
        assertEquals("PHOTO:37:4080x3072", updated.photo.validatedCandidateId)
        assertEquals(StreamConfigurationMode.AUTO, updated.video.mode)
        assertNull(updated.video.validatedCandidateId)
    }

    @Test
    fun `blank candidate is removed`() {
        val value = StreamClassConfiguration(
            mode = StreamConfigurationMode.VALIDATED,
            validatedCandidateId = "   "
        ).sanitized()

        assertNull(value.validatedCandidateId)
    }
}
