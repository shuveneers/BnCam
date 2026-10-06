package com.bncam.core.quality

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DemosaicModeTest {
    @Test
    fun `bridge values and explicit algorithms remain backward compatible`() {
        assertEquals(0, DemosaicMode.AUTO_HYBRID.bridgeValue)
        assertEquals(1, DemosaicMode.MALVAR.bridgeValue)
        assertEquals(2, DemosaicMode.AMAZE.bridgeValue)
        assertEquals(3, DemosaicMode.BNC_NEURAL.bridgeValue)

        assertEquals(ResolvedDemosaicAlgorithm.MALVAR_2004,
            DemosaicMode.resolveForPhase4("Malvar Inspired").resolvedAlgorithm)
        assertEquals(ResolvedDemosaicAlgorithm.MALVAR_2004,
            DemosaicMode.resolveForPhase4("RCD Inspired").resolvedAlgorithm)
        assertEquals(ResolvedDemosaicAlgorithm.AMAZE,
            DemosaicMode.resolveForPhase4("AMAZE Inspired").resolvedAlgorithm)
        assertEquals(ResolvedDemosaicAlgorithm.AUTO_HYBRID,
            DemosaicMode.resolveForPhase4("Auto").resolvedAlgorithm)
    }

    @Test
    fun `missing settings use auto hybrid without migrating explicit choices`() {
        assertEquals(DemosaicMode.AUTO_HYBRID, DemosaicMode.DEFAULT)
        listOf(null, "", "   ").forEach { value ->
            val missing = DemosaicMode.resolveForPhase4(value)
            assertEquals(DemosaicMode.AUTO_HYBRID, missing.requestedMode)
            assertEquals(ResolvedDemosaicAlgorithm.AUTO_HYBRID, missing.resolvedAlgorithm)
            assertFalse(missing.fallbackOccurred)
        }

        val invalid = DemosaicMode.resolveForPhase4("not-a-demosaic")
        assertEquals(DemosaicMode.AUTO_HYBRID, invalid.requestedMode)
        assertEquals(ResolvedDemosaicAlgorithm.AUTO_HYBRID, invalid.resolvedAlgorithm)
        assertTrue(invalid.fallbackOccurred)
    }

    @Test
    fun `explicit product choices keep their routes`() {
        mapOf(
            "Malvar" to ResolvedDemosaicAlgorithm.MALVAR_2004,
            "AMaZE" to ResolvedDemosaicAlgorithm.AMAZE,
            "Auto Hybrid" to ResolvedDemosaicAlgorithm.AUTO_HYBRID
        ).forEach { (stored, actual) ->
            val selection = DemosaicMode.resolveForPhase4(stored)
            assertEquals(DemosaicMode.fromPersisted(stored), selection.requestedMode)
            assertEquals(actual, selection.resolvedAlgorithm)
            assertFalse(selection.fallbackOccurred)
        }
    }

    @Test
    fun `user order puts auto fourth`() {
        assertEquals(
            listOf(
                DemosaicMode.MALVAR,
                DemosaicMode.AMAZE,
                DemosaicMode.BNC_NEURAL,
                DemosaicMode.AUTO_HYBRID
            ),
            DemosaicMode.USER_ORDER
        )
        assertEquals(
            listOf("Malvar", "AMaZE", "BnC Neural", "Auto Hybrid"),
            DemosaicMode.USER_ORDER.map { it.displayName }
        )
    }

    @Test
    fun `legacy persisted names retain bridge semantics`() {
        assertEquals(DemosaicMode.MALVAR, DemosaicMode.fromPersisted("NORMAL"))
        assertEquals(DemosaicMode.AMAZE, DemosaicMode.fromPersisted("MENON_2007"))
        assertEquals(DemosaicMode.BNC_NEURAL, DemosaicMode.fromPersisted("BILINEAR"))
        assertEquals(DemosaicMode.BNC_NEURAL, DemosaicMode.fromPersisted("RCD"))
    }

    @Test
    fun `current enum names and numeric profiles round trip without changing bridge ids`() {
        DemosaicMode.entries.forEach {
            assertEquals(it, DemosaicMode.fromPersisted(it.name))
            assertEquals(it, DemosaicMode.fromPersisted(it.bridgeValue.toString()))
            assertEquals(it, DemosaicMode.fromPersisted(it.displayName))
        }
    }

    @Test
    fun `neural selection reports the current malvar fallback without changing requested slot`() {
        val selection = DemosaicMode.resolveForPhase4("Neural JDD")
        assertEquals(3, selection.requestedMode.bridgeValue)
        assertEquals("MALVAR_2004", selection.resolvedDebugName)
        assertTrue(selection.fallbackOccurred)
        assertEquals("BNC_NEURAL_BACKEND_UNAVAILABLE", selection.fallbackReason)
        assertEquals(DemosaicMode.BNC_NEURAL, DemosaicMode.fromPersisted("NEURAL_JDD"))
        assertEquals("false", selection.debugPairs.toMap()["bncNeuralAvailable"])
        assertEquals("BNC_NEURAL", selection.debugPairs.toMap()["requestedDemosaicMode"])
    }
    @Test
    fun `capture schema and product choices share the automatic default`() {
        val entry = com.bncam.data.settings.CaptureSettingsSchema.definitions.single {
            it.stableKey == DemosaicMode.PROFILE_KEY
        }
        assertEquals("Auto Hybrid", entry.defaultValue)
        assertEquals(DemosaicMode.USER_ORDER.map { it.displayName }, entry.validRangeOrOptions.split("|"))
    }
}
