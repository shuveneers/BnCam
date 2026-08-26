package com.bncam.core.runners

import android.hardware.camera2.CaptureResult
import com.bncam.core.buffer.ZslFramePair
import com.bncam.core.capture.FrameGenerationId
import com.bncam.core.capture.ShutterCandidateFreshnessPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SingleFrameSelectionPolicyTest {
    @Before
    fun forceWarmSelectionState() {
        FrameGenerationId.reset()
        FrameGenerationId.forceWarmState()
    }

    @Test
    fun motionScoreIsIndependentOfSharpnessAndRespondsToExposureAndMovement() {
        val shortStable = FrameMotionScorer.score(
            exposureTimeNs = 8_000_000L,
            oisMode = CaptureResult.LENS_OPTICAL_STABILIZATION_MODE_OFF,
            lensState = CaptureResult.LENS_STATE_STATIONARY,
            afState = CaptureResult.CONTROL_AF_STATE_PASSIVE_FOCUSED
        )
        val longStable = FrameMotionScorer.score(
            exposureTimeNs = 66_000_000L,
            oisMode = CaptureResult.LENS_OPTICAL_STABILIZATION_MODE_OFF,
            lensState = CaptureResult.LENS_STATE_STATIONARY,
            afState = CaptureResult.CONTROL_AF_STATE_PASSIVE_FOCUSED
        )
        val longWithOis = FrameMotionScorer.score(
            exposureTimeNs = 66_000_000L,
            oisMode = CaptureResult.LENS_OPTICAL_STABILIZATION_MODE_ON,
            lensState = CaptureResult.LENS_STATE_STATIONARY,
            afState = CaptureResult.CONTROL_AF_STATE_PASSIVE_FOCUSED
        )
        val moving = FrameMotionScorer.score(
            exposureTimeNs = 8_000_000L,
            oisMode = CaptureResult.LENS_OPTICAL_STABILIZATION_MODE_OFF,
            lensState = CaptureResult.LENS_STATE_MOVING,
            afState = CaptureResult.CONTROL_AF_STATE_ACTIVE_SCAN
        )

        assertTrue(shortStable.score > longStable.score)
        assertTrue(longWithOis.score > longStable.score)
        assertTrue(moving.score < shortStable.score)
        assertEquals("EXPOSURE_OIS_LENS_AF_V1", shortStable.model)
    }

    @Test
    fun hundredsOfMillisecondsOldFrameCannotCompeteWithGenuineNearZslFrame() {
        val oldPerfect = candidate(
            index = 0,
            deltaMs = -320.0,
            sharpness = 1.0,
            motion = 1.0
        )
        val nearShutter = candidate(
            index = 1,
            deltaMs = -70.0,
            sharpness = 0.45,
            motion = 0.45
        )

        val result = FrameSelectionEngine.selectBestCandidate(
            candidates = listOf(oldPerfect, nearShutter),
            freshnessWindowMs =
                ShutterCandidateFreshnessPolicy.GENUINE_NEAR_ZSL_WINDOW_MS
        )

        assertEquals(1, result.selected.candidate.index)
        assertTrue(result.selected.freshEnoughForSelection)
        assertTrue(
            result.ranked.first { it.candidate.index == 0 }
                .rejectedForStaleSelection
        )
        assertFalse(result.degradedFallbackUsed)
    }

    @Test
    fun noNearZslCandidateUsesClosestCompleteFrameButLabelsDegradedFallback() {
        val older = candidate(0, -410.0, 1.0, 1.0)
        val closest = candidate(1, -180.0, 0.40, 0.40)

        val result = FrameSelectionEngine.selectBestCandidate(
            candidates = listOf(older, closest),
            freshnessWindowMs =
                ShutterCandidateFreshnessPolicy.GENUINE_NEAR_ZSL_WINDOW_MS
        )

        assertEquals(1, result.selected.candidate.index)
        assertFalse(result.selected.freshEnoughForSelection)
        assertTrue(result.degradedFallbackUsed)
        assertTrue(result.anchorSelectionReason.startsWith("degraded_"))
    }

    @Test
    fun preferRecentOverridesSharpnessBiasAndChangesEffectiveWeights() {
        val sharpnessPolicy = SingleFrameSelectionSettingsResolver.resolve(
            baseBias = "Overall Best Score",
            frameBias = "Sharpness",
            acceptAll = false,
            alignableOnly = true,
            preferRecent = false
        )
        val recentPolicy = SingleFrameSelectionSettingsResolver.resolve(
            baseBias = "Sharpness",
            frameBias = "Sharpness",
            acceptAll = false,
            alignableOnly = true,
            preferRecent = true
        )
        val recent = candidate(0, -20.0, 0.50, 0.55)
        val olderHighQuality = candidate(1, -100.0, 1.0, 1.0)

        val sharpResult = FrameSelectionEngine.selectBestCandidate(
            candidates = listOf(recent, olderHighQuality),
            freshnessWindowMs = 120.0,
            settings = sharpnessPolicy
        )
        val recentResult = FrameSelectionEngine.selectBestCandidate(
            candidates = listOf(recent, olderHighQuality),
            freshnessWindowMs = 120.0,
            settings = recentPolicy
        )

        assertEquals(SingleFrameSelectionBias.SHARPNESS, sharpnessPolicy.effectiveBias)
        assertEquals(SingleFrameSelectionBias.RECENCY, recentPolicy.effectiveBias)
        assertEquals(1, sharpResult.selected.candidate.index)
        assertEquals(0, recentResult.selected.candidate.index)
        assertTrue(recentResult.weights.freshness > sharpResult.weights.freshness)
    }

    @Test
    fun nearZslFocusSelectionToggleChangesQualityVersusRecencyPreferenceWithoutHardRejectingFrames() {
        val recentSoft = candidate(0, -10.0, 0.40, 0.65)
        val olderSharp = candidate(1, -100.0, 0.95, 0.65)

        val qualityPreferred = FrameSelectionEngine.selectBestCandidate(
            candidates = listOf(recentSoft, olderSharp),
            freshnessWindowMs = 120.0,
            nearZslFocusSelectionEnabled = true
        )
        val recencyPreferred = FrameSelectionEngine.selectBestCandidate(
            candidates = listOf(recentSoft, olderSharp),
            freshnessWindowMs = 120.0,
            nearZslFocusSelectionEnabled = false
        )

        assertEquals(1, qualityPreferred.selected.candidate.index)
        assertEquals(0, recencyPreferred.selected.candidate.index)
        assertTrue(recencyPreferred.weights.freshness > qualityPreferred.weights.freshness)
        assertTrue(recencyPreferred.weights.sharpness < qualityPreferred.weights.sharpness)
        assertTrue(recencyPreferred.freshCandidateCount == qualityPreferred.freshCandidateCount)
    }

    @Test
    fun mergeOnlySingleFrameSettingsAreExplicitlyDeprecated() {
        val settings = SingleFrameSelectionSettingsResolver.resolve(
            baseBias = "Motion",
            frameBias = "Auto",
            acceptAll = true,
            alignableOnly = true,
            preferRecent = false
        )

        assertEquals(SingleFrameSelectionBias.MOTION, settings.effectiveBias)
        assertTrue(settings.baseBiasApplied)
        assertFalse(settings.frameBiasApplied)
        assertTrue(settings.acceptAllStatus.startsWith("DEPRECATED_"))
        assertTrue(settings.alignableOnlyStatus.startsWith("DEPRECATED_"))
        assertEquals(
            0.0,
            SingleFrameSelectionWeightPolicy.resolve(
                bias = settings.effectiveBias,
                coldStart = false
            ).alignability,
            0.0
        )
    }

    @Test
    fun nearZslPreShutterContractEnforcesFullExposureEndBeforeUserShutter() {
        val userShutterTimestampNs = 1_000_000_000L
        val preShutterSensorStartNs = 950_000_000L
        val exposureTimeNs = 30_000_000L
        val rollingShutterSkewNs = 10_000_000L
        val preShutterFullExposureEndNs = preShutterSensorStartNs + exposureTimeNs + rollingShutterSkewNs
        val isFullyPreShutter = preShutterFullExposureEndNs <= userShutterTimestampNs
        assertTrue(isFullyPreShutter)
        assertEquals(990_000_000L, preShutterFullExposureEndNs)

        val postShutterSensorStartNs = 970_000_000L
        val postShutterFullExposureEndNs = postShutterSensorStartNs + exposureTimeNs + rollingShutterSkewNs
        val isPostShutterFullyPreShutter = postShutterFullExposureEndNs <= userShutterTimestampNs
        assertFalse(isPostShutterFullyPreShutter)
        assertEquals(1_010_000_000L, postShutterFullExposureEndNs)
    }

    @Test
    fun postGateRankingEnsuresImageQualityAndMotionStabilityDominance() {
        val highQualityPreShutter = candidate(
            index = 0,
            deltaMs = -40.0,
            sharpness = 0.95,
            motion = 0.95
        )
        val newerBlurryPreShutter = candidate(
            index = 1,
            deltaMs = -10.0,
            sharpness = 0.40,
            motion = 0.40
        )

        val result = FrameSelectionEngine.selectBestCandidate(
            candidates = listOf(highQualityPreShutter, newerBlurryPreShutter),
            freshnessWindowMs = 120.0
        )

        assertEquals(0, result.selected.candidate.index)
    }

    private fun candidate(
        index: Int,
        deltaMs: Double,
        sharpness: Double,
        motion: Double
    ) = HeuristicCandidate(
        frame = ZslFramePair(),
        index = index,
        timestampNs = 1_000L + index,
        deltaMs = deltaMs,
        sharpnessScore = sharpness,
        motionScore = motion,
        evScore = if (index == 1) 0.95 else 0.60,
        alignabilityScore = if (index == 1) 0.95 else 0.60,
        clippingScore = if (index == 1) 0.95 else 0.60,
        isStable = true,
        metadataComplete = true,
        aeState = CaptureResult.CONTROL_AE_STATE_CONVERGED,
        awbState = CaptureResult.CONTROL_AWB_STATE_CONVERGED,
        focusState = CaptureResult.CONTROL_AF_STATE_PASSIVE_FOCUSED
    )
}
