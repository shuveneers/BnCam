package com.bncam.core.quality

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PhysicalNoiseStateTest {
    private fun snapshot(
        effectiveS: DoubleArray = doubleArrayOf(0.001, 0.002, 0.003, 0.004),
        effectiveO: DoubleArray = doubleArrayOf(0.00001, 0.00002, 0.00003, 0.00004),
        legacyConfidence: Float = 0.0f
    ) = NoiseModelSnapshotV3(
        lensKey = "lens_main",
        sourceFormat = "RAW_SENSOR",
        iso = 1600,
        exposureTimeNs = 10_000_000L,
        postRawSensitivityBoost = 100,
        cfaPattern = 0,
        cfaName = "RGGB",
        whiteLevel = 4095,
        blackLevel = floatArrayOf(64f, 64f, 64f, 64f),
        cameraS = doubleArrayOf(0.0005, 0.0005, 0.0005, 0.0005),
        cameraO = doubleArrayOf(0.000005, 0.000005, 0.000005, 0.000005),
        effectiveS = effectiveS,
        effectiveO = effectiveO,
        chromaUserScale = 1.0f,
        lumaUserScale = 1.0f,
        spectraMode = "Off",
        signalModelConfidence = legacyConfidence,
        physicalAuthorityLocked = true,
        physicalNoiseRequestedSource = "PRESET",
        physicalNoiseEffectiveSource = "PRESET",
        physicalNoiseProvenance = "AGC V12 preset 42",
        physicalNoiseEffectiveModelIso = 1600.0,
        physicalNoiseDynamicIsoEnabled = true,
        physicalNoiseDynamicIsoCoefficient = 1.0,
        physicalNoiseIsoStep = 711.0,
        physicalNoisePresetName = "42. example",
        physicalNoiseSettingsReady = true
    )

    @Test
    fun physicalModelRemainsAvailableWithSpectraOffAndLegacyConfidenceZero() {
        val snapshot = snapshot(legacyConfidence = 0.0f)
        assertTrue(snapshot.physicalNoiseModelAvailable)
        assertEquals(1.0f, snapshot.physicalNoiseModelConfidence)
        assertEquals(0.0f, snapshot.signalModelConfidence)
        assertFalse(snapshot.isSpectraActive())
    }

    @Test
    fun stateUsesEffectivePhysicalSoNotOemEvidence() {
        val state = snapshot().physicalNoiseState()
        assertEquals("PRESET", state.effectiveSource)
        assertTrue(state.modelAvailable)
        assertContentEquals(
            doubleArrayOf(0.001, 0.00001, 0.002, 0.00002, 0.003, 0.00003, 0.004, 0.00004),
            state.toInterleavedProfileOrNull()
        )
        assertEquals(0.001 * 0.5 + 0.00001, state.variance(0, 0.5), 1.0e-12)
    }

    @Test
    fun unavailableZeroModelHasZeroPhysicalConfidence() {
        val zero = snapshot(DoubleArray(4), DoubleArray(4), legacyConfidence = 1.0f)
        assertFalse(zero.physicalNoiseModelAvailable)
        assertEquals(0.0f, zero.physicalNoiseModelConfidence)
        assertEquals(1.0f, zero.signalModelConfidence)
        assertEquals(null, zero.physicalNoiseState().toInterleavedProfileOrNull())
    }
    @Test
    fun unresolvedNonzeroConstructorArraysCannotBecomePhysicalAuthority() {
        val unresolved = NoiseModelSnapshotV3(
            lensKey = "lens_main",
            sourceFormat = "RAW_SENSOR",
            iso = 800,
            exposureTimeNs = 10_000_000L,
            postRawSensitivityBoost = 100,
            cfaPattern = 0,
            cfaName = "RGGB",
            whiteLevel = 4095,
            blackLevel = floatArrayOf(64f, 64f, 64f, 64f),
            cameraS = doubleArrayOf(0.001, 0.001, 0.001, 0.001),
            cameraO = doubleArrayOf(0.0001, 0.0001, 0.0001, 0.0001),
            effectiveS = doubleArrayOf(9.0, 9.0, 9.0, 9.0),
            effectiveO = doubleArrayOf(9.0, 9.0, 9.0, 9.0),
            chromaUserScale = 1.0f,
            lumaUserScale = 1.0f,
            physicalAuthorityLocked = true
        )
        assertFalse(unresolved.physicalNoiseModelAvailable)
        assertEquals(0.0f, unresolved.physicalNoiseModelConfidence)
        assertEquals("UNRESOLVED", unresolved.physicalNoiseEffectiveSource)
    }

}
