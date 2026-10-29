package com.bncam.core.capture

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RawIlluminationChangePolicyTest {
    private val reference = DoubleArray(64 * 48) { i ->
        0.09 + (i % 31) * 0.004 + (i / 64 % 8) * 0.002
    }

    private fun modulation(current: DoubleArray): Boolean =
        RawIlluminationChangePolicy.consistentGlobalShift(
            previous = reference,
            current = current,
            exposureRatio = 1.0,
            previousSlope = 0.00001,
            previousOffset = 0.00000001,
            currentSlope = 0.00001,
            currentOffset = 0.00000001,
            previousQuantizationVariance = 1e-8,
            currentQuantizationVariance = 1e-8
        )

    @Test fun `spatially coherent light pulse is not subject motion`() {
        assertTrue(modulation(DoubleArray(reference.size) { reference[it] * 1.12 }))
    }

    @Test fun `localized subject change is not light modulation`() {
        val modified = reference.clone()
        for (i in 0 until 1200) modified[i] = reference[i] * 1.12
        assertFalse(modulation(modified))
    }

    @Test fun `stable scene is not a light pulse`() {
        assertFalse(modulation(reference))
    }

    @Test fun `insufficient raw signal never invents light evidence`() {
        assertFalse(modulation(DoubleArray(reference.size) { 0.005 }))
    }

    @Test fun `without full spatial coverage motion exclusion is prohibited`() {
        assertFalse(RawIlluminationChangePolicy.consistentGlobalShift(
            DoubleArray(256) { 0.1 }, DoubleArray(256) { 0.115 }, 1.0,
            0.00001, 0.00000001, 0.00001, 0.00000001, 1e-8, 1e-8
        ))
    }
}
