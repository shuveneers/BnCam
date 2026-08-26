package com.bncam.core.runners

import com.bncam.core.buffer.ZslFramePair
import org.junit.Assert.assertEquals
import org.junit.Test

class FrameSelectionQualityTest {
    @Test
    fun clippingScoreBreaksOtherwiseEqualCandidateTie() {
        val clipped = candidate(index = 0, clippingScore = 0.05)
        val protected = candidate(index = 1, clippingScore = 0.95)

        val result = FrameSelectionEngine.selectBestCandidate(
            candidates = listOf(clipped, protected),
            freshnessWindowMs = 350.0
        )

        assertEquals(1, result.selected.candidate.index)
    }

    private fun candidate(index: Int, clippingScore: Double) = HeuristicCandidate(
        frame = ZslFramePair(),
        index = index,
        timestampNs = 1_000L + index,
        deltaMs = -20.0,
        sharpnessScore = 0.75,
        motionScore = 0.8,
        evScore = 0.75,
        alignabilityScore = 0.8,
        clippingScore = clippingScore,
        isStable = true,
        metadataComplete = true,
        aeState = 2,
        awbState = 2,
        focusState = 2
    )
}
