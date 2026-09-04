package com.bncam.core.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NearZslAnchorAdmissionPolicyTest {
    @Test
    fun coldStartSkipsImpossiblePreShutterPairingWait() {
        val budget = NearZslAnchorAdmissionPolicy.resolveBudget(
            frameDurationMedianMs = 50.0,
            pairCompletionLagMedianMs = 40.0,
            coldStartAtUserShutter = true
        )
        assertEquals(0L, budget.preShutterPairingGraceMs)
        assertTrue(budget.firstValidRepeatingFrameWaitMs >= 300L)
    }

    @Test
    fun warmRouteAllowsShortLatePairingGraceWithoutMultiSecondShutterDelay() {
        val budget = NearZslAnchorAdmissionPolicy.resolveBudget(
            frameDurationMedianMs = 50.0,
            pairCompletionLagMedianMs = 40.0,
            coldStartAtUserShutter = false
        )
        assertEquals(70L, budget.preShutterPairingGraceMs)
        assertTrue(budget.firstValidRepeatingFrameWaitMs in 300L..900L)
    }

    @Test
    fun staleSixHundredMillisecondFrameCannotBecomeGuaranteedNearZslAnchor() {
        assertFalse(NearZslAnchorAdmissionPolicy.isGenuinePreShutterAge(613.9))
        assertTrue(NearZslAnchorAdmissionPolicy.isGenuinePreShutterAge(55.3))
        assertTrue(NearZslAnchorAdmissionPolicy.isGenuinePreShutterAge(117.8))
    }


    @Test
    fun degradedPreShutterTierKeepsWarmCapturePreShutterButRejectsVeryStaleFrames() {
        val budget = NearZslAnchorAdmissionPolicy.resolveBudget(
            frameDurationMedianMs = 50.0,
            pairCompletionLagMedianMs = 40.0,
            coldStartAtUserShutter = false
        )
        assertTrue(budget.maximumDegradedPreShutterAgeMs in 180.0..320.0)
        assertTrue(
            NearZslAnchorAdmissionPolicy.isUsableDegradedPreShutterAge(180.0, budget.maximumDegradedPreShutterAgeMs)
        )
        assertFalse(
            NearZslAnchorAdmissionPolicy.isUsableDegradedPreShutterAge(613.9, budget.maximumDegradedPreShutterAgeMs)
        )
        assertFalse(
            NearZslAnchorAdmissionPolicy.isUsableDegradedPreShutterAge(null, budget.maximumDegradedPreShutterAgeMs)
        )
    }

    @Test
    fun firstValidFallbackMustCompleteAtOrAfterTheUserPressAndStayFresh() {
        val shutter = 1_000_000_000L
        val now = 1_120_000_000L
        assertTrue(
            NearZslAnchorAdmissionPolicy.isFreshFirstValidCompletion(
                pairCompleteElapsedNs = 1_050_000_000L,
                userShutterTimestampNs = shutter,
                nowElapsedNs = now,
                maximumTransportAgeMs = 200.0
            )
        )
        assertFalse(
            NearZslAnchorAdmissionPolicy.isFreshFirstValidCompletion(
                pairCompleteElapsedNs = 999_999_999L,
                userShutterTimestampNs = shutter,
                nowElapsedNs = now,
                maximumTransportAgeMs = 200.0
            )
        )
        assertFalse(
            NearZslAnchorAdmissionPolicy.isFreshFirstValidCompletion(
                pairCompleteElapsedNs = 700_000_000L,
                userShutterTimestampNs = shutter,
                nowElapsedNs = now,
                maximumTransportAgeMs = 200.0
            )
        )
    }

    @Test
    fun comparableSensorClockUsesFullExposureEndAsEffectiveFallbackShutter() {
        val result = NearZslAnchorAdmissionPolicy.effectiveShutterForFirstValidRepeatingFrame(
            userShutterTimestampNs = 1_000L,
            fullExposureEndNs = 1_150L,
            pairCompleteElapsedNs = 1_220L,
            sensorTimestampComparableToElapsedRealtime = true
        )
        assertEquals(1_150L, result.timestampNs)
        assertEquals("ELAPSED_REALTIME_FIRST_VALID_REPEATING_FRAME", result.domain)
    }

    @Test
    fun unrelatedSensorClockUsesPairCompletionForClockSafeFallback() {
        val result = NearZslAnchorAdmissionPolicy.effectiveShutterForFirstValidRepeatingFrame(
            userShutterTimestampNs = 1_000L,
            fullExposureEndNs = 99_999L,
            pairCompleteElapsedNs = 1_220L,
            sensorTimestampComparableToElapsedRealtime = false
        )
        assertEquals(1_220L, result.timestampNs)
        assertEquals("ELAPSED_REALTIME_FIRST_VALID_REPEATING_FRAME_CLOCK_SAFE", result.domain)
    }
    @Test
    fun firstValidRepeatingFallbackRejectsPhysicallyAncientLatePairWhenClockIsComparable() {
        assertTrue(
            NearZslAnchorAdmissionPolicy.isAdmissibleFirstValidPhysicalAge(
                physicalAgeAtUserShutterMs = -12.0,
                maximumDegradedPreShutterAgeMs = 320.0
            )
        )
        assertTrue(
            NearZslAnchorAdmissionPolicy.isAdmissibleFirstValidPhysicalAge(
                physicalAgeAtUserShutterMs = 180.0,
                maximumDegradedPreShutterAgeMs = 320.0
            )
        )
        assertFalse(
            NearZslAnchorAdmissionPolicy.isAdmissibleFirstValidPhysicalAge(
                physicalAgeAtUserShutterMs = 613.9,
                maximumDegradedPreShutterAgeMs = 320.0
            )
        )
        assertFalse(
            NearZslAnchorAdmissionPolicy.isAdmissibleFirstValidPhysicalAge(
                physicalAgeAtUserShutterMs = null,
                maximumDegradedPreShutterAgeMs = 320.0
            )
        )
    }

}
