package com.bncam.core.quality

import org.junit.Assert.assertEquals
import org.junit.Test

class NoiseModelAuthorityPolicyTest {
    @Test
    fun `spectra off retains valid camera physical model without enabling spectra`() {
        val decision = NoiseModelAuthorityPolicy.resolve(
            lensNoiseMode = "Off",
            spectraRequested = false,
            cameraNoiseProfileAvailable = true,
            manualNoiseProfileAvailable = false
        )
        assertEquals("Auto", decision.physicalNoiseMode)
        assertEquals("Off", decision.spectraProcessingMode)
    }

    @Test
    fun `spectra on layers context fusion over same camera physical model`() {
        val decision = NoiseModelAuthorityPolicy.resolve(
            lensNoiseMode = "Off",
            spectraRequested = true,
            cameraNoiseProfileAvailable = true,
            manualNoiseProfileAvailable = false
        )
        assertEquals("Auto", decision.physicalNoiseMode)
        assertEquals("Auto", decision.spectraProcessingMode)
    }

    @Test
    fun `explicit manual physical model stays manual while spectra may remain off`() {
        val decision = NoiseModelAuthorityPolicy.resolve(
            lensNoiseMode = "Manual",
            spectraRequested = false,
            cameraNoiseProfileAvailable = true,
            manualNoiseProfileAvailable = true
        )
        assertEquals("Manual", decision.physicalNoiseMode)
        assertEquals("Off", decision.spectraProcessingMode)
    }

    @Test
    fun `invalid explicit manual model reports manual physical request but cannot run spectra`() {
        val decision = NoiseModelAuthorityPolicy.resolve(
            lensNoiseMode = "Manual",
            spectraRequested = true,
            cameraNoiseProfileAvailable = true,
            manualNoiseProfileAvailable = false
        )
        assertEquals("Manual", decision.physicalNoiseMode)
        assertEquals("Off", decision.spectraProcessingMode)
    }

    @Test
    fun `missing physical model cannot fake spectra activation`() {
        val decision = NoiseModelAuthorityPolicy.resolve(
            lensNoiseMode = "Off",
            spectraRequested = true,
            cameraNoiseProfileAvailable = false,
            manualNoiseProfileAvailable = false
        )
        assertEquals("Off", decision.physicalNoiseMode)
        assertEquals("Off", decision.spectraProcessingMode)
    }
}
