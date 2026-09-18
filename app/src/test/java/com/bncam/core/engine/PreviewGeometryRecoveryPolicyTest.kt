package com.bncam.core.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PreviewGeometryRecoveryPolicyTest {
    @Test
    fun lowerBandwidthFullFovCandidateWinsFirst() {
        val failed = PreviewGeometryRecoveryPolicy.Extent(1920, 1440)
        val ranked = PreviewGeometryRecoveryPolicy.rankedAlternatives(
            fullFovCandidates = listOf(
                PreviewGeometryRecoveryPolicy.Extent(2560, 1920),
                failed,
                PreviewGeometryRecoveryPolicy.Extent(1600, 1200),
                PreviewGeometryRecoveryPolicy.Extent(1280, 960)
            ),
            failed = failed,
            alreadyAttempted = emptySet()
        )
        assertEquals(PreviewGeometryRecoveryPolicy.Extent(1600, 1200), ranked.first())
        assertEquals(PreviewGeometryRecoveryPolicy.Extent(1280, 960), ranked[1])
        assertEquals(PreviewGeometryRecoveryPolicy.Extent(2560, 1920), ranked[2])
    }

    @Test
    fun attemptedAndFailedSizesAreNeverReturned() {
        val failed = PreviewGeometryRecoveryPolicy.Extent(1600, 1200)
        val ranked = PreviewGeometryRecoveryPolicy.rankedAlternatives(
            fullFovCandidates = listOf(
                failed,
                PreviewGeometryRecoveryPolicy.Extent(1280, 960),
                PreviewGeometryRecoveryPolicy.Extent(1024, 768)
            ),
            failed = failed,
            alreadyAttempted = setOf("1280x960")
        )
        assertEquals(listOf(PreviewGeometryRecoveryPolicy.Extent(1024, 768)), ranked)
    }

    @Test
    fun retryBudgetIsBounded() {
        val failed = PreviewGeometryRecoveryPolicy.Extent(4000, 3000)
        val all = (1..10).map { i -> PreviewGeometryRecoveryPolicy.Extent(4000 - i * 200, 3000 - i * 150) }
        val ranked = PreviewGeometryRecoveryPolicy.rankedAlternatives(all, failed, emptySet())
        assertTrue(ranked.size <= PreviewGeometryRecoveryPolicy.MAX_ATTEMPTS_PER_GENERATION)
    }
}
