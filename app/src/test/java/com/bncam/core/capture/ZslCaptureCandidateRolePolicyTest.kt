package com.bncam.core.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class ZslCaptureCandidateRolePolicyTest {
    private fun evidence(
        frameVersion: Long,
        ageMs: Double,
        afTransitioning: Boolean = false,
        afUnfocused: Boolean = false,
        lensMoving: Boolean = false,
        aeTransitioning: Boolean = false,
        awbTransitioning: Boolean = false
    ) = ZslCaptureCandidateEvidence(
        frameVersion = frameVersion,
        physicalAgeMs = ageMs,
        afTransitioning = afTransitioning,
        afExplicitlyUnfocused = afUnfocused,
        lensMoving = lensMoving,
        aeTransitioning = aeTransitioning,
        awbTransitioning = awbTransitioning
    )

    @Test
    fun newestUsableCandidateWins() {
        val selected = ZslCaptureCandidateRolePolicy.selectBest(
            listOf(evidence(1, 80.0), evidence(2, 12.0), evidence(3, 45.0))
        )
        assertEquals(2L, selected?.frameVersion)
    }

    @Test
    fun usableCandidateBeatsNewerAfScanningCandidate() {
        val selected = ZslCaptureCandidateRolePolicy.selectBest(
            listOf(evidence(1, 22.0), evidence(2, 3.0, afTransitioning = true))
        )
        assertEquals(1L, selected?.frameVersion)
    }

    @Test
    fun usableCandidateBeatsNewerMovingLensCandidate() {
        val selected = ZslCaptureCandidateRolePolicy.selectBest(
            listOf(evidence(5, 28.0), evidence(6, 2.0, lensMoving = true))
        )
        assertEquals(5L, selected?.frameVersion)
    }

    @Test
    fun aeAndAwbTransitionsAreExactFrameDemotions() {
        val selected = ZslCaptureCandidateRolePolicy.selectBest(
            listOf(
                evidence(10, 25.0),
                evidence(11, 2.0, aeTransitioning = true),
                evidence(12, 1.0, awbTransitioning = true)
            )
        )
        assertEquals(10L, selected?.frameVersion)
    }

    @Test
    fun explicitUnfocusedStateIsDemoted() {
        val selected = ZslCaptureCandidateRolePolicy.selectBest(
            listOf(evidence(20, 30.0), evidence(21, 1.0, afUnfocused = true))
        )
        assertEquals(20L, selected?.frameVersion)
    }

    @Test
    fun allTransitionalCandidatesStillReturnNewestWithoutBlocking() {
        val selected = ZslCaptureCandidateRolePolicy.selectBest(
            listOf(
                evidence(30, 40.0, afTransitioning = true),
                evidence(31, 5.0, aeTransitioning = true),
                evidence(32, 20.0, lensMoving = true)
            )
        )
        assertEquals(31L, selected?.frameVersion)
        assertEquals(ZslCaptureCandidateState.TRANSITIONING, selected?.state)
    }

    @Test
    fun frameVersionBreaksExactAgeTieDeterministically() {
        val selected = ZslCaptureCandidateRolePolicy.selectBest(
            listOf(evidence(40, 12.0), evidence(41, 12.0))
        )
        assertEquals(41L, selected?.frameVersion)
    }

    @Test
    fun invalidAgeNeverBecomesCandidate() {
        val selected = ZslCaptureCandidateRolePolicy.selectBest(
            listOf(evidence(50, Double.NaN), evidence(51, 8.0))
        )
        assertNotNull(selected)
        assertEquals(51L, selected?.frameVersion)
    }
}
