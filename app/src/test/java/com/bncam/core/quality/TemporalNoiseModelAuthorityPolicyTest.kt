package com.bncam.core.quality

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TemporalNoiseModelAuthorityPolicyTest {
    private val profile = doubleArrayOf(
        1e-4, 1e-6,
        1.1e-4, 1.1e-6,
        1.2e-4, 1.2e-6,
        1.3e-4, 1.3e-6
    )

    @Test
    fun spectraOffStillUsesValidPhysicalCamera2Model() {
        val decision = TemporalNoiseModelAuthorityPolicy.resolve(
            spectraProcessingEnabled = false,
            spectraModeName = "Off",
            snapshotEffectiveS = null,
            snapshotEffectiveO = null,
            snapshotConfidence = null,
            effectiveNoiseProfile = profile,
            effectiveNoiseProfileApplied = true,
            cameraNoiseProfilePresent = true,
            cameraNoiseProfileValid = true,
            normalizationCalibrationValid = true,
            cfaSupportedForBayerNoiseModel = true
        )
        assertTrue(decision.enabled)
        assertFalse(decision.adaptiveSpectraCalibration)
        assertEquals(0, decision.spectraMode)
        assertEquals("PHYSICAL_CAMERA2_FIXED_SO", decision.authoritySource)
        assertContentEquals(doubleArrayOf(1e-4, 1.1e-4, 1.2e-4, 1.3e-4), decision.effectiveS)
        assertContentEquals(doubleArrayOf(1e-6, 1.1e-6, 1.2e-6, 1.3e-6), decision.effectiveO)
    }

    @Test
    fun physicalPathRejectsMissingCameraMetadataAuthority() {
        val decision = TemporalNoiseModelAuthorityPolicy.resolve(
            spectraProcessingEnabled = false,
            spectraModeName = "Off",
            snapshotEffectiveS = doubleArrayOf(1e-4, 1e-4, 1e-4, 1e-4),
            snapshotEffectiveO = doubleArrayOf(1e-6, 1e-6, 1e-6, 1e-6),
            snapshotConfidence = 1f,
            effectiveNoiseProfile = profile,
            effectiveNoiseProfileApplied = true,
            cameraNoiseProfilePresent = false,
            cameraNoiseProfileValid = false,
            normalizationCalibrationValid = true,
            cfaSupportedForBayerNoiseModel = true
        )
        assertFalse(decision.enabled)
        assertEquals("NO_VALID_CAMERA2_PHYSICAL_SO", decision.authoritySource)
    }

    @Test
    fun spectraAutoRetainsAdaptiveOwnership() {
        val s = doubleArrayOf(2e-4, 2.1e-4, 2.2e-4, 2.3e-4)
        val o = doubleArrayOf(2e-6, 2.1e-6, 2.2e-6, 2.3e-6)
        val decision = TemporalNoiseModelAuthorityPolicy.resolve(
            spectraProcessingEnabled = true,
            spectraModeName = "Auto",
            snapshotEffectiveS = s,
            snapshotEffectiveO = o,
            snapshotConfidence = 0.8f,
            effectiveNoiseProfile = profile,
            effectiveNoiseProfileApplied = true,
            cameraNoiseProfilePresent = true,
            cameraNoiseProfileValid = true,
            normalizationCalibrationValid = true,
            cfaSupportedForBayerNoiseModel = true
        )
        assertTrue(decision.enabled)
        assertTrue(decision.adaptiveSpectraCalibration)
        assertEquals(1, decision.spectraMode)
        assertEquals("SPECTRA_ADAPTIVE_SO", decision.authoritySource)
        assertContentEquals(s, decision.effectiveS)
        assertContentEquals(o, decision.effectiveO)
    }

    @Test
    fun spectraManualCompatibilityDoesNotRequireCamera2BaseProfile() {
        val s = doubleArrayOf(3e-4, 3e-4, 3e-4, 3e-4)
        val o = doubleArrayOf(3e-6, 3e-6, 3e-6, 3e-6)
        val decision = TemporalNoiseModelAuthorityPolicy.resolve(
            spectraProcessingEnabled = true,
            spectraModeName = "Manual",
            snapshotEffectiveS = s,
            snapshotEffectiveO = o,
            snapshotConfidence = 0.7f,
            effectiveNoiseProfile = null,
            effectiveNoiseProfileApplied = true,
            cameraNoiseProfilePresent = false,
            cameraNoiseProfileValid = false,
            normalizationCalibrationValid = true,
            cfaSupportedForBayerNoiseModel = true
        )
        assertTrue(decision.enabled)
        assertTrue(decision.adaptiveSpectraCalibration)
        assertEquals(2, decision.spectraMode)
    }
}
