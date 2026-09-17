package com.bncam.core.quality

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TemporalNoiseModelAuthorityPolicyTest {
    @Test
    fun snapshotIsRequired() {
        val decision = PhysicalTemporalNoisePolicy.resolve(
            snapshotEffectiveS = null,
            snapshotEffectiveO = null
        )
        assertFalse(decision.enabled)
        assertEquals("PHYSICAL_SHUTTER_SNAPSHOT_REQUIRED", decision.authoritySource)
    }

    @Test
    fun frozenSnapshotIsCopiedExactly() {
        val s = doubleArrayOf(2e-4, 2.1e-4, 2.2e-4, 2.3e-4)
        val o = doubleArrayOf(2e-6, 2.1e-6, 2.2e-6, 2.3e-6)
        val decision = PhysicalTemporalNoisePolicy.resolve(
            snapshotEffectiveS = s,
            snapshotEffectiveO = o
        )
        assertTrue(decision.enabled)
        assertEquals("PHYSICAL_SHUTTER_SNAPSHOT_FIXED_SO", decision.authoritySource)
        assertContentEquals(s, decision.effectiveS)
        assertContentEquals(o, decision.effectiveO)
        assertEquals(1.0f, decision.confidence)
    }

    @Test
    fun zeroEnergySnapshotIsRejected() {
        val decision = PhysicalTemporalNoisePolicy.resolve(
            snapshotEffectiveS = DoubleArray(4),
            snapshotEffectiveO = DoubleArray(4)
        )
        assertFalse(decision.enabled)
        assertEquals("PHYSICAL_SHUTTER_SNAPSHOT_REQUIRED", decision.authoritySource)
    }

    @Test
    fun returnedArraysDoNotAliasInput() {
        val s = doubleArrayOf(1.0, 2.0, 3.0, 4.0)
        val o = doubleArrayOf(5.0, 6.0, 7.0, 8.0)
        val decision = PhysicalTemporalNoisePolicy.resolve(s, o)
        s[0] = 99.0
        o[0] = 99.0
        assertEquals(1.0, decision.effectiveS[0], 0.0)
        assertEquals(5.0, decision.effectiveO[0], 0.0)
    }
}
