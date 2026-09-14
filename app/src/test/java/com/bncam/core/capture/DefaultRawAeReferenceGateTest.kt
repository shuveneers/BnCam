package com.bncam.core.capture

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultRawAeReferenceGateTest {
    private fun metering(generation: Int, measurementNs: Long) = DefaultRawMeteringSnapshot(
        pipelineGeneration = generation,
        source = "RAW",
        controllerLuma = 0.03f,
        rawNearClipFraction = 0f,
        sampleCount = 1024,
        measurementElapsedRealtimeNs = measurementNs,
        ageNs = 0L,
        freshness = DefaultRawMeteringFreshness.FRESH,
        valid = true,
        reason = "test"
    )

    @Test
    fun `single converged result is insufficient`() {
        val gate = DefaultRawAeReferenceGate()
        gate.reset(4)
        gate.observeAeState(4, true, 1_000L)
        assertFalse(gate.canAccept(4, metering(4, 1_100L)))
    }

    @Test
    fun `metering from before stable ae is rejected`() {
        val gate = DefaultRawAeReferenceGate()
        gate.reset(4)
        gate.observeAeState(4, true, 1_000L)
        gate.observeAeState(4, true, 1_020L)
        assertFalse(gate.canAccept(4, metering(4, 999L)))
    }

    @Test
    fun `fresh metering produced after stable ae is accepted`() {
        val gate = DefaultRawAeReferenceGate()
        gate.reset(4)
        gate.observeAeState(4, true, 1_000L)
        gate.observeAeState(4, true, 1_020L)
        assertTrue(gate.canAccept(4, metering(4, 1_010L)))
    }

    @Test
    fun `searching ae revokes convergence window`() {
        val gate = DefaultRawAeReferenceGate()
        gate.reset(4)
        gate.observeAeState(4, true, 1_000L)
        gate.observeAeState(4, true, 1_020L)
        gate.observeAeState(4, false, 1_030L)
        assertFalse(gate.canAccept(4, metering(4, 1_040L)))
    }

    @Test
    fun `generation change revokes old metering`() {
        val gate = DefaultRawAeReferenceGate()
        gate.reset(4)
        gate.observeAeState(4, true, 1_000L)
        gate.observeAeState(4, true, 1_020L)
        assertFalse(gate.canAccept(5, metering(4, 1_030L)))
    }
}

class DefaultRawTargetContinuityTest {
    @Test
    fun `retained photometric target wins over darker route transition observation`() {
        val target = DefaultRawTargetContinuity.resolveFallbackTarget(
            fallbackTargetLuma = null,
            photometricTargetLuma = 0.035f,
            observedLuma = 0.012f
        )
        assertTrue(kotlin.math.abs(requireNotNull(target) - 0.035f) < 0.000001f)
    }

    @Test
    fun `existing fallback target remains authoritative`() {
        val target = DefaultRawTargetContinuity.resolveFallbackTarget(0.04f, 0.035f, 0.012f)
        assertTrue(kotlin.math.abs(requireNotNull(target) - 0.04f) < 0.000001f)
    }

    @Test
    fun `observation is only a last resort when no prior setpoint exists`() {
        val target = DefaultRawTargetContinuity.resolveFallbackTarget(null, null, 0.02f)
        assertTrue(kotlin.math.abs(requireNotNull(target) - 0.02f) < 0.000001f)
    }
}
