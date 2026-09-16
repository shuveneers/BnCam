package com.bncam.core.quality

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SpectraNoiseAdapterTest {
    private fun resolvedSnapshot(s: DoubleArray, o: DoubleArray) = NoiseModelSnapshotV3(
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
        effectiveS = s,
        effectiveO = o,
        chromaUserScale = 1.0f,
        lumaUserScale = 1.0f,
        spectraMode = "Off",
        signalModelConfidence = 0.0f,
        physicalAuthorityLocked = true,
        physicalNoiseRequestedSource = "PRESET",
        physicalNoiseEffectiveSource = "PRESET",
        physicalNoiseProvenance = "test preset",
        physicalNoiseSettingsReady = true
    )

    @Test
    fun spectraOffLeavesPhysicalModelAvailableAndImmutable() {
        val snapshot = resolvedSnapshot(
            doubleArrayOf(1.0, 2.0, 3.0, 4.0),
            doubleArrayOf(5.0, 6.0, 7.0, 8.0)
        )
        val physical = snapshot.physicalNoiseState()
        val input = SpectraNoiseInputState.from(physical, requested = false)

        assertFalse(input.enabled)
        assertEquals("PROFILE_SPECTRA_DISABLED", input.disabledReason)
        assertTrue(physical.modelAvailable)
        assertContentEquals(doubleArrayOf(1.0, 2.0, 3.0, 4.0), physical.effectiveS)
        assertContentEquals(doubleArrayOf(5.0, 6.0, 7.0, 8.0), physical.effectiveO)
    }

    @Test
    fun spectraOnConsumesSamePhysicalSoWithoutChangingIt() {
        val snapshot = resolvedSnapshot(
            doubleArrayOf(0.01, 0.02, 0.03, 0.04),
            doubleArrayOf(0.001, 0.002, 0.003, 0.004)
        )
        val physical = snapshot.physicalNoiseState()
        val input = SpectraNoiseInputState.from(physical, requested = true)
        val mutatedExternal = input.effectiveS
        mutatedExternal[0] = 99.0

        assertTrue(input.enabled)
        assertEquals(1.0f, input.physicalConfidence)
        assertEquals(0.01, input.effectiveS[0], 0.0)
        assertEquals(0.01, physical.effectiveS[0], 0.0)
    }

    @Test
    fun spectraCannotRunWithoutResolvedPhysicalSource() {
        val physical = NoiseModelSnapshotV3.createNeutral().physicalNoiseState()
        val input = SpectraNoiseInputState.from(physical, requested = true)
        assertFalse(input.enabled)
        assertEquals("PHYSICAL_NOISE_MODEL_UNAVAILABLE", input.disabledReason)
    }
}
