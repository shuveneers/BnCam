package com.bncam.core.quality

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class CaptureNoiseStateTest {
    @Test
    fun captureStateClonesAllArrayInputsAndPreservesOrderingLabels() {
        val snapshot = NoiseModelSnapshotV3.createNeutral().copy(
            spectraMode = "On",
            signalModelConfidence = 0.65f,
            effectiveS = doubleArrayOf(1.0, 2.0, 3.0, 4.0),
            effectiveO = doubleArrayOf(5.0, 6.0, 7.0, 8.0)
        )
        val state = CaptureNoiseState.from(
            snapshot = snapshot,
            profileNoiseTuning = ProfileNoiseTuning(spectraChroma = 0.5f),
            lensShadingAlreadyApplied = true,
            lensShadingMapFromMetadata = true,
            lensShadingMapColumns = 17,
            lensShadingMapRows = 13,
            lensShadingGainP10 = 1.0f,
            lensShadingGainP50 = 1.2f,
            lensShadingGainP90 = 1.8f
        )

        val external = state.effectiveSCanonical
        external[0] = 99.0

        assertEquals(1.0, state.effectiveSCanonical[0], 0.0)
        assertEquals("R,G1,G2,B", state.toTraceMap()["canonicalSoOrder"])
        assertEquals("camera2_2x2_mosaic_order", state.toTraceMap()["blackLevelOrder"])
        assertEquals(17, state.toTraceMap()["lensShadingMapColumns"])
        assertEquals("OWNED_BY_NATIVE_CAPTURE_METADATA", state.toTraceMap()["lensShadingMapPayload"])
        assertEquals(0.65f, state.modelConfidence, 0.0001f)
    }

    @Test
    fun neutralSnapshotDoesNotClaimPerfectModelConfidence() {
        val snapshot = NoiseModelSnapshotV3.createNeutral()
        assertFalse(snapshot.isSpectraActive())
        val state = CaptureNoiseState.from(
            snapshot = snapshot,
            profileNoiseTuning = ProfileNoiseTuning(),
            lensShadingAlreadyApplied = false
        )
        assertEquals(0.0f, state.modelConfidence, 0.0f)
    }
}
