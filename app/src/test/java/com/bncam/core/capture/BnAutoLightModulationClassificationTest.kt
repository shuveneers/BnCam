package com.bncam.core.capture

import org.junit.Assert.assertEquals
import org.junit.Test

class BnAutoLightModulationClassificationTest {
    private val reference = DoubleArray(64 * 48) { i ->
        0.09 + (i % 31) * 0.004 + (i / 64 % 8) * 0.002
    }
    private val baseline = BnAutoObservation(
        timestampNs = 1_000_000_000L,
        exposureNs = 10_000_000L,
        iso = 200,
        p20 = 0.10, p80 = 0.19, p98 = 0.25,
        channelClipFractions = List(4) { 0.0 },
        sampleCount = 64 * 48,
        spatialHighlightFraction = 0.0,
        noiseSlope = 0.00001,
        noiseOffset = 0.00000001,
        sensorLumaSamples = reference
    )

    @Test fun `spatially coherent illumination change does not produce motion hold`() {
        val after = baseline.copy(
            timestampNs = 1_033_000_000L,
            sensorLumaSamples = DoubleArray(reference.size) { reference[it] * 1.12 }
        )
        assertEquals(BnAutoMotionEvidence.Status.LIGHT_MODULATION,
            BnAutoMotionEvidence.evaluate(baseline, after))
    }

    @Test fun `subject-only change retains motion classification`() {
        val altered = reference.clone()
        for (i in 0 until 1200) altered[i] *= 1.12
        val after = baseline.copy(timestampNs = 1_033_000_000L, sensorLumaSamples = altered)
        assertEquals(BnAutoMotionEvidence.Status.MOVING,
            BnAutoMotionEvidence.evaluate(baseline, after))
    }
}
