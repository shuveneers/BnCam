package com.bncam.core.capture

import com.bncam.core.buffer.StreamTimingEstimate
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShutterCandidateCollectorPolicyTest {
    @Test
    fun nearShutterThresholdUsesAbsoluteSensorDelta() {
        assertTrue(ShutterCandidateCollectionPolicy.isNearShutter(-35.0))
        assertTrue(ShutterCandidateCollectionPolicy.isNearShutter(20.0))
        assertFalse(ShutterCandidateCollectionPolicy.isNearShutter(-35.1))
    }

    @Test
    fun closerFrameIsMeaningful() {
        assertTrue(
            ShutterCandidateCollectionPolicy.isMeaningfulCandidate(
                candidateDeltaMs = -42.0,
                currentAbsoluteDeltasMs = listOf(108.0, 141.0, 174.0),
                hasPostShutterSensorCandidate = false
            )
        )
    }

    @Test
    fun firstNearPostShutterFrameAddsTemporalCoverage() {
        assertTrue(
            ShutterCandidateCollectionPolicy.isMeaningfulCandidate(
                candidateDeltaMs = 28.0,
                currentAbsoluteDeltasMs = listOf(10.0, 43.0, 76.0),
                hasPostShutterSensorCandidate = false
            )
        )
        assertFalse(
            ShutterCandidateCollectionPolicy.isMeaningfulCandidate(
                candidateDeltaMs = 28.0,
                currentAbsoluteDeltasMs = listOf(10.0, 43.0, 76.0),
                hasPostShutterSensorCandidate = true
            )
        )
    }

    @Test
    fun robustEstimatePredictsWhetherNextPairCanBeatDeadline() {
        val estimate = estimate(
            frameDurationMedianMs = 33.3,
            pairCompletionLagMedianMs = 87.0,
            sampleCount = 5
        )
        val latestSensorTimestampNs = 1_000_000_000L

        assertTrue(
            ShutterCandidateCollectionPolicy.nextFrameWouldCompleteBeforeDeadline(
                latestSensorTimestampNs = latestSensorTimestampNs,
                deadlineElapsedNs = 1_125_000_000L,
                estimate = estimate
            ) == true
        )
        assertFalse(
            ShutterCandidateCollectionPolicy.nextFrameWouldCompleteBeforeDeadline(
                latestSensorTimestampNs = latestSensorTimestampNs,
                deadlineElapsedNs = 1_110_000_000L,
                estimate = estimate
            ) == true
        )
    }

    @Test
    fun estimatorIsOptionalUntilEnoughSamplesExist() {
        assertNull(
            ShutterCandidateCollectionPolicy.nextFrameWouldCompleteBeforeDeadline(
                latestSensorTimestampNs = 1_000_000_000L,
                deadlineElapsedNs = 1_200_000_000L,
                estimate = estimate(
                    frameDurationMedianMs = 33.3,
                    pairCompletionLagMedianMs = 87.0,
                    sampleCount = 2
                )
            )
        )
    }

    private fun estimate(
        frameDurationMedianMs: Double,
        pairCompletionLagMedianMs: Double,
        sampleCount: Int
    ) = StreamTimingEstimate(
        frameDurationMedianMs = frameDurationMedianMs,
        imageDeliveryLagMedianMs = 80.0,
        metadataDeliveryLagMedianMs = 40.0,
        pairCompletionLagMedianMs = pairCompletionLagMedianMs,
        frameDurationSampleCount = sampleCount,
        imageDeliveryLagSampleCount = sampleCount,
        metadataDeliveryLagSampleCount = sampleCount,
        pairCompletionLagSampleCount = sampleCount
    )
}
