package com.bncam.core.capture

import com.bncam.core.buffer.ZslFramePair
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ControlRequestProvenanceTest {

    private fun prepareAndCommit(
        tracker: ControlRequestEpochTracker,
        pipelineGeneration: Int,
        state: ControlRequestState,
        reason: String = "TEST"
    ): PreparedControlRequest {
        val prepared = tracker.prepare(
            pipelineGeneration = pipelineGeneration,
            state = state,
            submissionType = CameraRequestSubmissionType.REPEATING,
            submissionReason = reason,
            meteringPolicySummary = "metering",
            exposurePolicySummary = "exposure",
            submittedElapsedRealtimeNs = 123L
        )
        tracker.commit(prepared)
        return prepared
    }

    @Test
    fun equivalentSubmittedStateDoesNotChurnControlEpoch() {
        FrameGenerationId.reset()
        val pipelineGeneration = FrameGenerationId.get()
        val tracker = ControlRequestEpochTracker()
        val state = ControlRequestState.create(aeExposureCompensation = 1)

        val first = prepareAndCommit(tracker, pipelineGeneration, state, "FIRST")
        val equivalent = prepareAndCommit(
            tracker,
            pipelineGeneration,
            ControlRequestState.create(aeExposureCompensation = 1),
            "EQUIVALENT_REBUILD"
        )

        assertTrue(first.advancesEpoch)
        assertFalse(equivalent.advancesEpoch)
        assertEquals(
            first.tag.controlRequestEpoch,
            equivalent.tag.controlRequestEpoch
        )
        assertEquals(pipelineGeneration, FrameGenerationId.get())
    }

    @Test
    fun meaningfulSubmittedControlChangeAdvancesEpochButKeepsPipelineGeneration() {
        FrameGenerationId.reset()
        val pipelineGeneration = FrameGenerationId.get()
        val tracker = ControlRequestEpochTracker()

        val first = prepareAndCommit(
            tracker,
            pipelineGeneration,
            ControlRequestState.create(aeExposureCompensation = 0)
        )
        val changed = prepareAndCommit(
            tracker,
            pipelineGeneration,
            ControlRequestState.create(aeExposureCompensation = 2)
        )

        assertTrue(changed.advancesEpoch)
        assertEquals(
            first.tag.controlRequestEpoch + 1L,
            changed.tag.controlRequestEpoch
        )
        assertEquals(
            first.tag.pipelineGeneration,
            changed.tag.pipelineGeneration
        )
        assertEquals(pipelineGeneration, FrameGenerationId.get())
    }

    @Test
    fun preparedButUnsubmittedStateDoesNotAdvanceCommittedEpoch() {
        val tracker = ControlRequestEpochTracker()
        val initial = prepareAndCommit(
            tracker,
            pipelineGeneration = 4,
            state = ControlRequestState.create(aeExposureCompensation = 0)
        )
        val notSubmitted = tracker.prepare(
            pipelineGeneration = 4,
            state = ControlRequestState.create(aeExposureCompensation = 3),
            submissionType = CameraRequestSubmissionType.REPEATING,
            submissionReason = "NOT_SUBMITTED",
            meteringPolicySummary = "metering",
            exposurePolicySummary = "exposure",
            submittedElapsedRealtimeNs = 124L
        )

        assertTrue(notSubmitted.advancesEpoch)
        assertEquals(
            initial.tag.controlRequestEpoch,
            tracker.currentSubmittedEpoch()
        )
    }

    @Test
    fun immutableRequestSnapshotDoesNotAliasCallerCollections() {
        val mutableAeRegions = mutableListOf(
            ImmutableMeteringRegionSnapshot(
                rect = ImmutableRectSnapshot(10, 20, 30, 40),
                weight = 800
            )
        )
        val state = ControlRequestState.create(
            aeRegions = mutableAeRegions,
            aeExposureCompensation = 1
        )

        mutableAeRegions.clear()

        assertEquals(1, state.aeRegions.size)
        assertEquals(10, state.aeRegions.single().rect.left)
        assertEquals(800, state.aeRegions.single().weight)
    }

    @Test
    fun oldBufferedFrameRetainsItsProducingEpochAfterNewSubmission() {
        val tracker = ControlRequestEpochTracker()
        val first = prepareAndCommit(
            tracker,
            pipelineGeneration = 7,
            state = ControlRequestState.create(aeExposureCompensation = 0)
        )
        val firstResolution = tracker.resolveTag(first.tag, 7)
        val frame = ZslFramePair().apply {
            timestamp = 1_000L
            generationId = 7
            controlRequestEpoch = first.tag.controlRequestEpoch
            requestProvenance = firstResolution.provenance
        }

        val second = prepareAndCommit(
            tracker,
            pipelineGeneration = 7,
            state = ControlRequestState.create(aeExposureCompensation = 2)
        )

        assertNotEquals(
            first.tag.controlRequestEpoch,
            second.tag.controlRequestEpoch
        )
        assertEquals(first.tag.controlRequestEpoch, frame.controlRequestEpoch)
        assertSame(first.tag.snapshot, frame.requestProvenance?.snapshot)
    }

    @Test
    fun exactFrameResultRequestChainRequiresAllMatchingIdentitiesAndTimestamp() {
        val tracker = ControlRequestEpochTracker()
        val submitted = prepareAndCommit(
            tracker,
            pipelineGeneration = 9,
            state = ControlRequestState.create(aeExposureCompensation = -1)
        )
        val provenance = tracker.resolveTag(submitted.tag, 9).provenance

        val exact = SelectedFrameProvenanceValidator.verify(
            framePipelineGeneration = 9,
            frameControlRequestEpoch = submitted.tag.controlRequestEpoch,
            frameTimestampNs = 2_000L,
            metadataTimestampNs = 2_000L,
            provenance = provenance
        )
        val wrongTimestamp = SelectedFrameProvenanceValidator.verify(
            framePipelineGeneration = 9,
            frameControlRequestEpoch = submitted.tag.controlRequestEpoch,
            frameTimestampNs = 2_000L,
            metadataTimestampNs = 2_001L,
            provenance = provenance
        )
        val missing = SelectedFrameProvenanceValidator.verify(
            framePipelineGeneration = 9,
            frameControlRequestEpoch = submitted.tag.controlRequestEpoch,
            frameTimestampNs = 2_000L,
            metadataTimestampNs = 2_000L,
            provenance = null
        )

        assertTrue(exact.exact)
        assertSame(submitted.tag.snapshot, exact.snapshot)
        assertFalse(wrongTimestamp.exact)
        assertNull(wrongTimestamp.snapshot)
        assertFalse(missing.exact)
        assertNull(missing.snapshot)
    }

    @Test
    fun pipelineResetRejectsOldRequestTagWithoutChangingEquivalentControlEpoch() {
        val tracker = ControlRequestEpochTracker()
        val state = ControlRequestState.create(aeExposureCompensation = 0)
        val oldPipeline = prepareAndCommit(tracker, 11, state)
        val newPipeline = prepareAndCommit(tracker, 12, state)

        assertEquals(
            oldPipeline.tag.controlRequestEpoch,
            newPipeline.tag.controlRequestEpoch
        )
        assertFalse(tracker.resolveTag(oldPipeline.tag, 12).exact)
        assertTrue(tracker.resolveTag(newPipeline.tag, 12).exact)
    }

    @Test
    fun staleOrAmbiguousProvenanceCanNeverReportMeteringAppliedExact() {
        assertFalse(
            MeteringExactTruth.canReportAppliedExact(
                exactFrameRequestResultProvenance = false,
                meteringRequested = true,
                requestRegionsExactlyMatchedByResult = true,
                requestedEvCompensation = 1,
                resultEvCompensation = 1
            )
        )
        assertFalse(
            MeteringExactTruth.canReportAppliedExact(
                exactFrameRequestResultProvenance = true,
                meteringRequested = true,
                requestRegionsExactlyMatchedByResult = true,
                requestedEvCompensation = null,
                resultEvCompensation = 1
            )
        )
        assertTrue(
            MeteringExactTruth.canReportAppliedExact(
                exactFrameRequestResultProvenance = true,
                meteringRequested = true,
                requestRegionsExactlyMatchedByResult = true,
                requestedEvCompensation = 1,
                resultEvCompensation = 1
            )
        )
    }

    @Test
    fun boundedHistoryDoesNotMakeDelayedTaggedResultAmbiguous() {
        val tracker = ControlRequestEpochTracker(retainedSnapshotLimit = 8)
        val oldest = prepareAndCommit(
            tracker,
            pipelineGeneration = 15,
            state = ControlRequestState.create(aeExposureCompensation = 0)
        )

        for (value in 1..10) {
            prepareAndCommit(
                tracker,
                pipelineGeneration = 15,
                state = ControlRequestState.create(aeExposureCompensation = value)
            )
        }

        assertTrue(tracker.retainedSnapshotCount() <= 8)
        assertNull(tracker.retainedSnapshot(oldest.tag.identity))
        val delayedResolution = tracker.resolveTag(oldest.tag, 15)
        assertTrue(delayedResolution.exact)
        assertSame(oldest.tag.snapshot, delayedResolution.provenance?.snapshot)
    }
}
