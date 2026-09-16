package com.bncam.core.quality

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TemporalNoiseModelAuthorityPolicyTest {
    private val legacyProfile = doubleArrayOf(
        1e-4, 1e-6,
        1.1e-4, 1.1e-6,
        1.2e-4, 1.2e-6,
        1.3e-4, 1.3e-6
    )

    @Test
    fun legacySoCannotCreatePhysicalAuthorityWhenSnapshotIsAbsent() {
        val decision = TemporalNoiseModelAuthorityPolicy.resolve(
            spectraProcessingEnabled = false,
            spectraModeName = "Auto",
            snapshotEffectiveS = null,
            snapshotEffectiveO = null,
            snapshotConfidence = 1.0f,
            effectiveNoiseProfile = legacyProfile,
            effectiveNoiseProfileApplied = true,
            cameraNoiseProfilePresent = true,
            cameraNoiseProfileValid = true,
            normalizationCalibrationValid = true,
            cfaSupportedForBayerNoiseModel = true
        )
        assertFalse(decision.enabled)
        assertEquals("PHYSICAL_SHUTTER_SNAPSHOT_REQUIRED", decision.authoritySource)
    }

    @Test
    fun shutterSnapshotIsOnlyPhysicalTemporalAuthority() {
        val s = doubleArrayOf(2e-4, 2.1e-4, 2.2e-4, 2.3e-4)
        val o = doubleArrayOf(2e-6, 2.1e-6, 2.2e-6, 2.3e-6)
        val decision = TemporalNoiseModelAuthorityPolicy.resolve(
            spectraProcessingEnabled = false,
            spectraModeName = "LegacyManualThatMustNotMatter",
            snapshotEffectiveS = s,
            snapshotEffectiveO = o,
            snapshotConfidence = 0.0f,
            effectiveNoiseProfile = legacyProfile,
            effectiveNoiseProfileApplied = false,
            cameraNoiseProfilePresent = false,
            cameraNoiseProfileValid = false,
            normalizationCalibrationValid = false,
            cfaSupportedForBayerNoiseModel = false
        )
        assertTrue(decision.enabled)
        assertFalse(decision.adaptiveSpectraCalibration)
        assertEquals(0, decision.spectraMode)
        assertEquals("PHYSICAL_SHUTTER_SNAPSHOT_FIXED_SO", decision.authoritySource)
        assertContentEquals(s, decision.effectiveS)
        assertContentEquals(o, decision.effectiveO)
        assertEquals(1.0f, decision.confidence)
    }

    @Test
    fun spectraAddonConsumesSnapshotWithoutChangingPhysicalVectorsOrConfidence() {
        val s = doubleArrayOf(3e-4, 3.1e-4, 3.2e-4, 3.3e-4)
        val o = doubleArrayOf(3e-6, 3.1e-6, 3.2e-6, 3.3e-6)
        val decision = TemporalNoiseModelAuthorityPolicy.resolve(
            spectraProcessingEnabled = true,
            spectraModeName = "Off",
            snapshotEffectiveS = s,
            snapshotEffectiveO = o,
            snapshotConfidence = 0.05f,
            effectiveNoiseProfile = legacyProfile,
            effectiveNoiseProfileApplied = true,
            cameraNoiseProfilePresent = true,
            cameraNoiseProfileValid = true,
            normalizationCalibrationValid = true,
            cfaSupportedForBayerNoiseModel = true
        )
        assertTrue(decision.enabled)
        assertTrue(decision.adaptiveSpectraCalibration)
        assertEquals(1, decision.spectraMode)
        assertEquals("SPECTRA_ADDON_CONSUMES_PHYSICAL_SHUTTER_SO", decision.authoritySource)
        assertContentEquals(s, decision.effectiveS)
        assertContentEquals(o, decision.effectiveO)
        assertEquals(1.0f, decision.confidence)
    }

    @Test
    fun zeroEnergySnapshotCannotBeRescuedByLegacyProfile() {
        val decision = TemporalNoiseModelAuthorityPolicy.resolve(
            spectraProcessingEnabled = true,
            spectraModeName = "On",
            snapshotEffectiveS = DoubleArray(4),
            snapshotEffectiveO = DoubleArray(4),
            snapshotConfidence = 1.0f,
            effectiveNoiseProfile = legacyProfile,
            effectiveNoiseProfileApplied = true,
            cameraNoiseProfilePresent = true,
            cameraNoiseProfileValid = true,
            normalizationCalibrationValid = true,
            cfaSupportedForBayerNoiseModel = true
        )
        assertFalse(decision.enabled)
        assertEquals("PHYSICAL_SHUTTER_SNAPSHOT_REQUIRED", decision.authoritySource)
    }
}
