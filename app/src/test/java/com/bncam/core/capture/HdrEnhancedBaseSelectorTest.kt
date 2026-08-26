package com.bncam.core.capture

import com.bncam.core.buffer.FrameLease
import com.bncam.core.buffer.ZslFramePair
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HdrEnhancedBaseSelectorTest {

    private fun mockFrame(
        index: Int,
        sharpness: Float?,
        motion: Float?,
        focusConfidence: Float?,
        clipping: Float?
    ): HdrEnhancedFrame {
        val lease = FrameLease(ZslFramePair()) { }
        return HdrEnhancedFrame(
            index = index,
            semanticTag = "HDR_ENHANCED_MAIN_${index.toString().padStart(2, '0')}",
            lease = lease,
            actualExposureTimeNs = 20_000_000L,
            actualSensitivityIso = 100,
            timestampNs = 1000L + index * 33_000_000L,
            requestSubmittedElapsedRealtimeNs = 2_000L,
            captureSequenceId = 7,
            captureFrameNumber = 100L + index,
            sharpnessScore = sharpness,
            motionScore = motion,
            clippingFraction = clipping,
            focusConfidence = focusConfidence,
            provenanceValid = true,
            isAuxiliary = false
        )
    }

    @Test
    fun selectsSharpestAndBestFocusedCandidateOverFrameZero() {
        val f0 = mockFrame(0, sharpness = 0.40f, motion = 0.3f, focusConfidence = 0.6f, clipping = 0.01f)
        val f1 = mockFrame(1, sharpness = 0.50f, motion = 0.2f, focusConfidence = 0.7f, clipping = 0.01f)
        val f2 = mockFrame(2, sharpness = 0.95f, motion = 0.01f, focusConfidence = 0.98f, clipping = 0.0f)
        val f3 = mockFrame(3, sharpness = 0.60f, motion = 0.15f, focusConfidence = 0.8f, clipping = 0.01f)

        val selection = HdrEnhancedBaseSelector.selectBestBase(listOf(f0, f1, f2, f3))

        assertEquals(2, selection.selectedBaseIndex)
        assertTrue(selection.measuredEvidenceAvailable)
    }

    @Test
    fun unavailableQualityDoesNotCreateSyntheticScores() {
        val frames = (0..3).map { index ->
            mockFrame(index, sharpness = null, motion = null, focusConfidence = null, clipping = null)
        }

        val selection = HdrEnhancedBaseSelector.selectBestBase(frames)

        assertEquals(1, selection.selectedBaseIndex)
        assertFalse(selection.measuredEvidenceAvailable)
        assertEquals("measured_quality_unavailable_temporal_middle", selection.selectionReason)
        assertTrue(selection.candidateScores.all { it.compositeScore == null && it.evidenceWeight == 0f })
    }
}
