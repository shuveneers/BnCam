package com.bncam.core.quality

import kotlin.test.Test
import kotlin.test.assertEquals

class NoiseModelAuthorityPolicyTest {
    @Test
    fun legacyManualRequestCannotSelectManualPhysicalAuthority() {
        val decision = NoiseModelAuthorityPolicy.resolve(
            lensNoiseMode = "Manual",
            spectraRequested = false,
            cameraNoiseProfileAvailable = true,
            manualNoiseProfileAvailable = true
        )
        assertEquals("Auto", decision.physicalNoiseMode)
        assertEquals("Off", decision.spectraProcessingMode)
    }

    @Test
    fun legacyManualWithoutCameraProfileFallsBackToOffNotSingleAnchorManual() {
        val decision = NoiseModelAuthorityPolicy.resolve(
            lensNoiseMode = "Manual",
            spectraRequested = false,
            cameraNoiseProfileAvailable = false,
            manualNoiseProfileAvailable = true
        )
        assertEquals("Off", decision.physicalNoiseMode)
        assertEquals("Off", decision.spectraProcessingMode)
    }

    @Test
    fun spectraRequestRemainsIndependentOfLegacyPhysicalAvailability() {
        val decision = NoiseModelAuthorityPolicy.resolve(
            lensNoiseMode = "Manual",
            spectraRequested = true,
            cameraNoiseProfileAvailable = false,
            manualNoiseProfileAvailable = false
        )
        assertEquals("Off", decision.physicalNoiseMode)
        assertEquals("On", decision.spectraProcessingMode)
    }
}
