package com.bncam.core.quality

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DemosaicModeTest {
    @Test
    fun `bridge values and explicit algorithms remain backward compatible`() {
        assertEquals(0, DemosaicMode.AUTO.bridgeValue)
        assertEquals(1, DemosaicMode.NORMAL.bridgeValue)
        assertEquals(2, DemosaicMode.QUALITY.bridgeValue)
        assertEquals(3, DemosaicMode.BILINEAR.bridgeValue)

        assertEquals(ResolvedDemosaicAlgorithm.MALVAR_INSPIRED,
            DemosaicMode.resolveForPhase4("Malvar Inspired").resolvedAlgorithm)
        assertEquals(ResolvedDemosaicAlgorithm.RCD_INSPIRED,
            DemosaicMode.resolveForPhase4("RCD Inspired").resolvedAlgorithm)
        assertEquals(ResolvedDemosaicAlgorithm.AMAZE_INSPIRED,
            DemosaicMode.resolveForPhase4("AMAZE Inspired").resolvedAlgorithm)
        assertEquals(ResolvedDemosaicAlgorithm.AUTO_SCENE_ADAPTIVE_NATIVE,
            DemosaicMode.resolveForPhase4("Auto").resolvedAlgorithm)
    }

    @Test
    fun `noise robust product default is malvar inspired`() {
        assertEquals(DemosaicMode.NORMAL, DemosaicMode.DEFAULT)
        val missing = DemosaicMode.resolveForPhase4(null)
        assertEquals(DemosaicMode.NORMAL, missing.requestedMode)
        assertEquals(ResolvedDemosaicAlgorithm.MALVAR_INSPIRED, missing.resolvedAlgorithm)
        assertFalse(missing.fallbackOccurred)

        val invalid = DemosaicMode.resolveForPhase4("not-a-demosaic")
        assertEquals(DemosaicMode.NORMAL, invalid.requestedMode)
        assertEquals(ResolvedDemosaicAlgorithm.MALVAR_INSPIRED, invalid.resolvedAlgorithm)
        assertTrue(invalid.fallbackOccurred)
    }

    @Test
    fun `user order puts auto fourth`() {
        assertEquals(
            listOf(
                DemosaicMode.NORMAL,
                DemosaicMode.BILINEAR,
                DemosaicMode.QUALITY,
                DemosaicMode.AUTO
            ),
            DemosaicMode.USER_ORDER
        )
        assertEquals(
            listOf("Malvar Inspired", "RCD Inspired", "AMAZE Inspired", "Auto"),
            DemosaicMode.USER_ORDER.map { it.displayName }
        )
    }

    @Test
    fun `legacy persisted names retain bridge semantics`() {
        assertEquals(DemosaicMode.NORMAL, DemosaicMode.fromPersisted("NORMAL"))
        assertEquals(DemosaicMode.QUALITY, DemosaicMode.fromPersisted("MENON_2007"))
        assertEquals(DemosaicMode.BILINEAR, DemosaicMode.fromPersisted("BILINEAR"))
        assertEquals(DemosaicMode.BILINEAR, DemosaicMode.fromPersisted("RCD"))
    }
}
