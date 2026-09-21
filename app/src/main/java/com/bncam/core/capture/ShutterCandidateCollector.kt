package com.bncam.core.capture

import android.hardware.camera2.CaptureResult
import android.os.SystemClock
import android.util.Log
import com.bncam.core.buffer.FrameRingBuffer
import com.bncam.core.buffer.FrameRingBufferObservabilitySnapshot
import com.bncam.core.buffer.FrameRingEvent
import com.bncam.core.buffer.FrameRingEventType
import com.bncam.core.buffer.StreamTimingEstimate
import com.bncam.core.buffer.ZslFramePair
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil

internal enum class ShutterCollectorCloseReason {
    RESERVED_ANCHOR,
    NEAR_SHUTTER_CANDIDATE_ACQUIRED,
    TEMPORAL_WINDOW_SUFFICIENT,
    NEXT_FRAME_NOT_WORTH_WAITING,
    HARD_DEADLINE_REACHED,
    PIPELINE_GENERATION_CHANGED,
    CAPTURE_ABORTED
}

internal data class ShutterCandidateConsideration(
    val origin: String,
    val sensorTimestampNs: Long,
    val candidateSensorDeltaMs: Double?,
    val pairCompleteElapsedNs: Long,
    val candidatePairCompletedRelativeToShutterMs: Double?,
    val pipelineGeneration: Int,
    val controlRequestEpoch: Long,
    val requestProvenanceStatus: String,
    val format: Int,
    val accepted: Boolean,
    val rejectionReason: String
)

internal data class ShutterCandidateCollectionResult(
    val frames: List<ZslFramePair>,
    val initialRingSnapshot: FrameRingBufferObservabilitySnapshot,
    val considerations: List<ShutterCandidateConsideration>,
    val closeReason: ShutterCollectorCloseReason,
    val collectionDurationMs: Double,
    val hardDeadlineMs: Long,
    val candidateCount: Int,
    val newestCandidateDeltaMs: Double?,
    val closestCandidateAbsoluteDeltaMs: Double?,
    val postShutterCompletedPairCount: Int,
    val initialCandidateLimit: Int,
    val maxCandidates: Int,
    val timingEstimateAtStart: StreamTimingEstimate,
    val timingEstimateAtClose: StreamTimingEstimate
)

/**
 * Pure policy helpers kept separate from Image/HardwareBuffer ownership so the shutter timing
 * decisions can be unit-tested without constructing Android Image objects.
 */
internal object ShutterCandidateCollectionPolicy {
    const val DEFAULT_HARD_DEADLINE_MS = 85L
    const val MAX_CANDIDATES = 4
    const val NEAR_SHUTTER_THRESHOLD_MS = 35.0
    const val POST_SHUTTER_COVERAGE_LIMIT_MS = 70.0
    const val MIN_ESTIMATOR_SAMPLES = 3

    fun isNearShutter(deltaMs: Double): Boolean =
        abs(deltaMs) <= NEAR_SHUTTER_THRESHOLD_MS

    fun isMeaningfulCandidate(
        candidateDeltaMs: Double,
        currentAbsoluteDeltasMs: List<Double>,
        hasPostShutterSensorCandidate: Boolean
    ): Boolean {
        if (currentAbsoluteDeltasMs.isEmpty()) return true
        val improvesProximity =
            abs(candidateDeltaMs) < currentAbsoluteDeltasMs.minOrNull()!!
        val addsUsefulPostShutterCoverage =
            candidateDeltaMs >= 0.0 &&
                    !hasPostShutterSensorCandidate &&
                    abs(candidateDeltaMs) <= POST_SHUTTER_COVERAGE_LIMIT_MS
        return improvesProximity || addsUsefulPostShutterCoverage
    }

    fun nextFrameWouldCompleteBeforeDeadline(
        latestSensorTimestampNs: Long,
        deadlineElapsedNs: Long,
        estimate: StreamTimingEstimate
    ): Boolean? {
        val frameDurationMs = estimate.frameDurationMedianMs ?: return null
        val pairCompletionLagMs = estimate.pairCompletionLagMedianMs ?: return null
        if (
            estimate.frameDurationSampleCount < MIN_ESTIMATOR_SAMPLES ||
            estimate.pairCompletionLagSampleCount < MIN_ESTIMATOR_SAMPLES
        ) {
            return null
        }
        val predictedCompletionElapsedNs =
            latestSensorTimestampNs +
                    (frameDurationMs * 1_000_000.0).toLong() +
                    (pairCompletionLagMs * 1_000_000.0).toLong()
        return predictedCompletionElapsedNs <= deadlineElapsedNs
    }
}

/**
 * Collects a bounded, shutter-centered set of complete pairs and transfers ownership of only
 * those pairs to SingleFrameRunner. Waiting is driven by FrameRingBuffer completion/lifecycle
 * events; the hard deadline is only a safety bound.
 */
internal class ShutterCandidateCollector(
    private val ringBuffer: FrameRingBuffer,
    private val hardDeadlineMs: Long =
        ShutterCandidateCollectionPolicy.DEFAULT_HARD_DEADLINE_MS,
    private val maxCandidates: Int =
        ShutterCandidateCollectionPolicy.MAX_CANDIDATES
) {
    private val tag = "NearZslCollector"

    suspend fun collect(
        shutterTimestampNs: Long,
        shutterTimestampDomain: String,
        expectedGeneration: Int,
        expectedFormat: Int,
        requestedInitialCandidateCount: Int,
        reservedAnchor: com.bncam.core.buffer.FrameLease? = null
    ): ShutterCandidateCollectionResult {
        val collectorStartedElapsedNs = SystemClock.elapsedRealtimeNanos()
        val baselineEventSequence = ringBuffer.currentEventSequence()
        val initialSnapshot = ringBuffer.observabilitySnapshot()
        val timingEstimateAtStart = ringBuffer.streamTimingEstimate()
        // The caller keeps this lease alive through runner dispatch. Do not reselect a different
        // moment (or a neighboring repeating frame in place of a requested cold still).
        if (reservedAnchor != null) {
            val pair = reservedAnchor.pair
            check(pair.generationId == expectedGeneration && pair.format == expectedFormat)
            check(pair.image != null && pair.metadata != null && pair.hardwareBuffer != null)
            check(pair.image!!.timestamp == pair.timestamp)
            check((pair.sensorMetadataSnapshot?.sensorTimestampNs
                ?: pair.metadata!!.get(CaptureResult.SENSOR_TIMESTAMP)) == pair.timestamp)
            return ShutterCandidateCollectionResult(
                frames = listOf(pair), initialRingSnapshot = initialSnapshot,
                considerations = emptyList(), closeReason = ShutterCollectorCloseReason.RESERVED_ANCHOR,
                collectionDurationMs = 0.0, hardDeadlineMs = 0L, candidateCount = 1,
                newestCandidateDeltaMs = null, closestCandidateAbsoluteDeltaMs = null,
                postShutterCompletedPairCount = 0, initialCandidateLimit = 1, maxCandidates = 1,
                timingEstimateAtStart = timingEstimateAtStart, timingEstimateAtClose = timingEstimateAtStart
            )
        }
        val initialCandidateLimit =
            requestedInitialCandidateCount.coerceIn(1, maxCandidates - 1)
        val sensorDeltaClockDomainsComparable =
            shutterTimestampDomain == "SENSOR_TIMESTAMP" ||
                    (shutterTimestampDomain.startsWith("ELAPSED_REALTIME") &&
                            initialSnapshot.sensorTimestampComparableToElapsedRealtime)
        val completionAndShutterClockDomainsComparable =
            shutterTimestampDomain.startsWith("ELAPSED_REALTIME") ||
                    (shutterTimestampDomain == "SENSOR_TIMESTAMP" &&
                            initialSnapshot.sensorTimestampComparableToElapsedRealtime)
        val deadlineAnchorElapsedNs =
            if (completionAndShutterClockDomainsComparable) {
                shutterTimestampNs
            } else {
                collectorStartedElapsedNs
            }
        val deadlineElapsedNs =
            deadlineAnchorElapsedNs + hardDeadlineMs * 1_000_000L
        val initialNewestSensorTimestampNs =
            initialSnapshot.completeFrames.maxOfOrNull { it.sensorTimestampNs } ?: 0L
        val initialNewestDeltaMs =
            if (sensorDeltaClockDomainsComparable && initialNewestSensorTimestampNs > 0L) {
                (initialNewestSensorTimestampNs - shutterTimestampNs) / 1_000_000.0
            } else {
                null
            }

        Log.i(
            tag,
            "event=COLLECTOR_START shutterTimestampNs=$shutterTimestampNs " +
                    "shutterTimestampDomain=$shutterTimestampDomain " +
                    "pipelineGeneration=$expectedGeneration " +
                    "initialCompletePairCount=${initialSnapshot.completePairCount} " +
                    "initialNewestSensorTimestampNs=$initialNewestSensorTimestampNs " +
                    "initialNewestAnchorDeltaMs=${formatMs(initialNewestDeltaMs)} " +
                    "initialCandidateLimit=$initialCandidateLimit " +
                    "maxCandidates=$maxCandidates hardDeadlineMs=$hardDeadlineMs " +
                    "frameDurationMedianMs=${formatMs(timingEstimateAtStart.frameDurationMedianMs)} " +
                    "imageDeliveryLagMedianMs=${formatMs(timingEstimateAtStart.imageDeliveryLagMedianMs)} " +
                    "metadataDeliveryLagMedianMs=${formatMs(timingEstimateAtStart.metadataDeliveryLagMedianMs)} " +
                    "pairCompletionLagMedianMs=${formatMs(timingEstimateAtStart.pairCompletionLagMedianMs)}"
        )

        val ownedCandidates = mutableListOf<ZslFramePair>()
        val considerations = mutableListOf<ShutterCandidateConsideration>()
        val observedPostShutterCompletionKeys = mutableSetOf<String>()
        var eventSequence = baselineEventSequence

        fun frameKey(timestampNs: Long, generation: Int): String =
            "$generation:$timestampNs"

        fun sensorDeltaMs(timestampNs: Long): Double? =
            if (sensorDeltaClockDomainsComparable && timestampNs > 0L) {
                (timestampNs - shutterTimestampNs) / 1_000_000.0
            } else {
                null
            }

        fun completionRelativeToShutterMs(pairCompleteElapsedNs: Long): Double? =
            if (
                completionAndShutterClockDomainsComparable &&
                pairCompleteElapsedNs > 0L
            ) {
                (pairCompleteElapsedNs - shutterTimestampNs) / 1_000_000.0
            } else {
                null
            }

        fun registerPostShutterCompletion(
            sensorTimestampNs: Long,
            generation: Int,
            pairCompleteElapsedNs: Long
        ) {
            val relative = completionRelativeToShutterMs(pairCompleteElapsedNs)
            if (relative != null && relative > 0.0) {
                observedPostShutterCompletionKeys.add(
                    frameKey(sensorTimestampNs, generation)
                )
            }
        }

        fun recordConsideration(
            origin: String,
            sensorTimestampNs: Long,
            pairCompleteElapsedNs: Long,
            generation: Int,
            controlRequestEpoch: Long,
            requestProvenanceStatus: String,
            format: Int,
            accepted: Boolean,
            reason: String
        ) {
            registerPostShutterCompletion(
                sensorTimestampNs,
                generation,
                pairCompleteElapsedNs
            )
            val consideration = ShutterCandidateConsideration(
                origin = origin,
                sensorTimestampNs = sensorTimestampNs,
                candidateSensorDeltaMs = sensorDeltaMs(sensorTimestampNs),
                pairCompleteElapsedNs = pairCompleteElapsedNs,
                candidatePairCompletedRelativeToShutterMs =
                    completionRelativeToShutterMs(pairCompleteElapsedNs),
                pipelineGeneration = generation,
                controlRequestEpoch = controlRequestEpoch,
                requestProvenanceStatus = requestProvenanceStatus,
                format = format,
                accepted = accepted,
                rejectionReason = reason
            )
            considerations.add(consideration)
            val completedRelativeToShutter =
                formatMs(consideration.candidatePairCompletedRelativeToShutterMs)
            Log.i(
                tag,
                "event=COLLECTOR_CANDIDATE origin=$origin " +
                        "sensorTimestampNs=$sensorTimestampNs " +
                        "candidateSensorDeltaMs=${formatMs(consideration.candidateSensorDeltaMs)} " +
                        "pairCompleteElapsedNs=$pairCompleteElapsedNs " +
                        "candidatePairCompletedRelativeToShutterMs=$completedRelativeToShutter " +
                        "pipelineGeneration=$generation " +
                        "controlRequestEpoch=$controlRequestEpoch " +
                        "requestProvenanceStatus=$requestProvenanceStatus " +
                        "format=$format acceptedIntoCollector=$accepted " +
                        "rejectionReason=$reason"
            )
        }

        fun validateOwnedFrame(frame: ZslFramePair): String? {
            val metadataTimestamp =
                frame.metadata?.get(CaptureResult.SENSOR_TIMESTAMP)
            val imageTimestamp = try {
                frame.image?.timestamp
            } catch (_: IllegalStateException) {
                null
            }
            val provenance = frame.requestProvenance
            return when {
                frame.generationId != expectedGeneration ->
                    "PIPELINE_GENERATION_MISMATCH"
                frame.controlRequestEpoch <= 0L ->
                    "MISSING_CONTROL_REQUEST_EPOCH"
                provenance == null ->
                    "MISSING_EXACT_REQUEST_PROVENANCE"
                provenance.identity.pipelineGeneration != frame.generationId ->
                    "REQUEST_PROVENANCE_PIPELINE_MISMATCH"
                provenance.identity.controlRequestEpoch != frame.controlRequestEpoch ->
                    "REQUEST_PROVENANCE_EPOCH_MISMATCH"
                provenance.snapshot.identity != provenance.identity ->
                    "REQUEST_PROVENANCE_SNAPSHOT_MISMATCH"
                frame.format != expectedFormat ->
                    "FRAME_SOURCE_FORMAT_MISMATCH"
                frame.metadata == null ->
                    "INVALID_METADATA"
                metadataTimestamp == null || metadataTimestamp <= 0L ->
                    "INVALID_SENSOR_TIMESTAMP"
                frame.image == null || frame.hardwareBuffer == null ->
                    "INCOMPLETE_FRAME_PAIR"
                imageTimestamp == null || imageTimestamp <= 0L ->
                    "INVALID_IMAGE_TIMESTAMP"
                imageTimestamp != frame.timestamp ->
                    "IMAGE_PAIR_TIMESTAMP_MISMATCH"
                metadataTimestamp != frame.timestamp ||
                        metadataTimestamp != imageTimestamp ->
                    "IMAGE_METADATA_TIMESTAMP_MISMATCH"
                else -> null
            }
        }

        fun closeResult(
            reason: ShutterCollectorCloseReason,
            retainCandidates: Boolean = true
        ): ShutterCandidateCollectionResult {
            if (!retainCandidates) {
                ownedCandidates.clear()
            } else {
                ownedCandidates.sortByDescending { pair: ZslFramePair ->
                    val deltaMs = if (shutterTimestampNs > 0L && pair.timestamp > 0L) kotlin.math.abs(pair.timestamp - shutterTimestampNs) / 1_000_000.0 else 0.0
                    val recency = kotlin.math.max(0.0, 1.0 - (deltaMs / 100.0))
                    val focusConf = pair.focusConfidence.toDouble()
                    val afScore = when (pair.afState) {
                        CaptureResult.CONTROL_AF_STATE_PASSIVE_FOCUSED, CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED -> 1.0
                        CaptureResult.CONTROL_AF_STATE_PASSIVE_UNFOCUSED, CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED -> 0.1
                        CaptureResult.CONTROL_AF_STATE_PASSIVE_SCAN, CaptureResult.CONTROL_AF_STATE_ACTIVE_SCAN -> 0.0
                        else -> 0.5
                    }
                    val statePenalty = if (pair.confidenceState == com.bncam.core.quality.FocusConfidenceState.CONFIDENT_SOFT) -0.5 else 0.0
                    (recency * 0.35 + focusConf * 0.35 + afScore * 0.30 + statePenalty)
                }
            }
            val closedElapsedNs = SystemClock.elapsedRealtimeNanos()
            val durationMs =
                (closedElapsedNs - collectorStartedElapsedNs).coerceAtLeast(0L) /
                        1_000_000.0
            val deltas = ownedCandidates.mapNotNull { sensorDeltaMs(it.timestamp) }
            val newestDeltaMs = ownedCandidates.maxByOrNull { it.timestamp }
                ?.let { sensorDeltaMs(it.timestamp) }
            val closestAbsoluteDeltaMs =
                deltas.minOfOrNull(::abs)
            val timingEstimateAtClose = ringBuffer.streamTimingEstimate()
            Log.i(
                tag,
                "event=COLLECTOR_CLOSE closeReason=${reason.name} " +
                        "collectionDurationMs=${formatMs(durationMs)} " +
                        "hardDeadlineMs=$hardDeadlineMs " +
                        "candidateCount=${ownedCandidates.size} " +
                        "newestCandidateDeltaMs=${formatMs(newestDeltaMs)} " +
                        "closestCandidateAbsoluteDeltaMs=${formatMs(closestAbsoluteDeltaMs)} " +
                        "postShutterCompletedPairCount=${observedPostShutterCompletionKeys.size} " +
                        "pipelineGenerationAtClose=${ringBuffer.currentGeneration()} " +
                        "frameDurationMedianMs=${formatMs(timingEstimateAtClose.frameDurationMedianMs)} " +
                        "imageDeliveryLagMedianMs=${formatMs(timingEstimateAtClose.imageDeliveryLagMedianMs)} " +
                        "metadataDeliveryLagMedianMs=${formatMs(timingEstimateAtClose.metadataDeliveryLagMedianMs)} " +
                        "pairCompletionLagMedianMs=${formatMs(timingEstimateAtClose.pairCompletionLagMedianMs)}"
            )
            return ShutterCandidateCollectionResult(
                frames = ownedCandidates.toList(),
                initialRingSnapshot = initialSnapshot,
                considerations = considerations.toList(),
                closeReason = reason,
                collectionDurationMs = durationMs,
                hardDeadlineMs = hardDeadlineMs,
                candidateCount = ownedCandidates.size,
                newestCandidateDeltaMs = newestDeltaMs,
                closestCandidateAbsoluteDeltaMs = closestAbsoluteDeltaMs,
                postShutterCompletedPairCount =
                    observedPostShutterCompletionKeys.size,
                initialCandidateLimit = initialCandidateLimit,
                maxCandidates = maxCandidates,
                timingEstimateAtStart = timingEstimateAtStart,
                timingEstimateAtClose = timingEstimateAtClose
            )
        }

        fun considerTransferredFrame(frame: ZslFramePair, origin: String): Boolean {
            val validationFailure = validateOwnedFrame(frame)
            if (validationFailure != null) {
                recordConsideration(
                    origin = origin,
                    sensorTimestampNs = frame.timestamp,
                    pairCompleteElapsedNs = frame.pairCompleteElapsedNs,
                    generation = frame.generationId,
                    controlRequestEpoch = frame.controlRequestEpoch,
                    requestProvenanceStatus =
                        frame.requestProvenance?.associationStatus ?: "UNPROVEN",
                    format = frame.format,
                    accepted = false,
                    reason = validationFailure
                )
                return false
            }
            val duplicate =
                ownedCandidates.any { it.timestamp == frame.timestamp }
            if (duplicate) {
                recordConsideration(
                    origin = origin,
                    sensorTimestampNs = frame.timestamp,
                    pairCompleteElapsedNs = frame.pairCompleteElapsedNs,
                    generation = frame.generationId,
                    controlRequestEpoch = frame.controlRequestEpoch,
                    requestProvenanceStatus =
                        frame.requestProvenance?.associationStatus ?: "UNPROVEN",
                    format = frame.format,
                    accepted = false,
                    reason = "DUPLICATE_SENSOR_TIMESTAMP"
                )
                return false
            }
            ownedCandidates.add(frame)
            recordConsideration(
                origin = origin,
                sensorTimestampNs = frame.timestamp,
                pairCompleteElapsedNs = frame.pairCompleteElapsedNs,
                generation = frame.generationId,
                controlRequestEpoch = frame.controlRequestEpoch,
                requestProvenanceStatus =
                    frame.requestProvenance?.associationStatus ?: "UNPROVEN",
                format = frame.format,
                accepted = true,
                reason = "ACCEPTED_VALID_CURRENT_GENERATION_PAIR"
            )
            return true
        }

        try {
            var initialCandidates = ringBuffer.queryCandidates(
                userShutterTimestampNs = shutterTimestampNs,
                maxCount = initialCandidateLimit,
                shutterTimestampDomain = shutterTimestampDomain
            )
            if (initialCandidates.isEmpty()) {
                val coldFrame = ringBuffer.awaitColdStartPreShutterFrame(
                    userShutterTimestampNs = shutterTimestampNs,
                    maxWaitMs = 200L,
                    shutterTimestampDomain = shutterTimestampDomain
                )
                if (coldFrame != null) {
                    initialCandidates = listOf(coldFrame)
                }
            }
            initialCandidates.forEach { considerTransferredFrame(it, "INITIAL_COMPLETE_PAIR") }

            if (ringBuffer.currentGeneration() != expectedGeneration) {
                return closeResult(
                    ShutterCollectorCloseReason.PIPELINE_GENERATION_CHANGED,
                    retainCandidates = false
                )
            }

            val initialClosestDelta =
                ownedCandidates.mapNotNull { sensorDeltaMs(it.timestamp) }
                    .minByOrNull(::abs)
            if (
                initialClosestDelta != null &&
                ShutterCandidateCollectionPolicy.isNearShutter(initialClosestDelta)
            ) {
                return closeResult(
                    ShutterCollectorCloseReason.NEAR_SHUTTER_CANDIDATE_ACQUIRED
                )
            }

            fun nextFrameWorthWaiting(): Boolean? {
                if (!sensorDeltaClockDomainsComparable) return null
                val latestSensorTimestampNs =
                    ownedCandidates.maxOfOrNull { it.timestamp } ?: return null
                return ShutterCandidateCollectionPolicy
                    .nextFrameWouldCompleteBeforeDeadline(
                        latestSensorTimestampNs = latestSensorTimestampNs,
                        deadlineElapsedNs = deadlineElapsedNs,
                        estimate = ringBuffer.streamTimingEstimate()
                    )
            }

            fun processEvent(
                event: FrameRingEvent
            ): Pair<ShutterCollectorCloseReason, Boolean>? {
                if (
                    ringBuffer.currentGeneration() != expectedGeneration ||
                    event.type == FrameRingEventType.GENERATION_CHANGED
                ) {
                    return ShutterCollectorCloseReason.PIPELINE_GENERATION_CHANGED to
                            false
                }
                if (event.type == FrameRingEventType.BUFFER_CLEARED) {
                    return ShutterCollectorCloseReason.CAPTURE_ABORTED to false
                }
                if (event.type != FrameRingEventType.PAIR_COMPLETED) {
                    return null
                }
                if (
                    completionAndShutterClockDomainsComparable &&
                    event.pairCompleteElapsedNs > deadlineElapsedNs
                ) {
                    recordConsideration(
                        origin = "COMPLETED_DURING_COLLECTION",
                        sensorTimestampNs = event.sensorTimestampNs,
                        pairCompleteElapsedNs = event.pairCompleteElapsedNs,
                        generation = event.pipelineGeneration,
                        controlRequestEpoch = event.controlRequestEpoch,
                        requestProvenanceStatus = event.requestProvenanceStatus,
                        format = event.format,
                        accepted = false,
                        reason = "PAIR_COMPLETED_AFTER_HARD_DEADLINE"
                    )
                    return ShutterCollectorCloseReason.HARD_DEADLINE_REACHED to
                            true
                }

                val basicRejection = validateEvent(
                    event = event,
                    expectedGeneration = expectedGeneration,
                    expectedFormat = expectedFormat
                )
                if (basicRejection != null) {
                    recordConsideration(
                        origin = "COMPLETED_DURING_COLLECTION",
                        sensorTimestampNs = event.sensorTimestampNs,
                        pairCompleteElapsedNs = event.pairCompleteElapsedNs,
                        generation = event.pipelineGeneration,
                        controlRequestEpoch = event.controlRequestEpoch,
                        requestProvenanceStatus = event.requestProvenanceStatus,
                        format = event.format,
                        accepted = false,
                        reason = basicRejection
                    )
                    return null
                }

                val key = frameKey(
                    event.sensorTimestampNs,
                    event.pipelineGeneration
                )
                val alreadyOwned = ownedCandidates.any {
                    frameKey(it.timestamp, it.generationId) == key
                }
                if (alreadyOwned) {
                    recordConsideration(
                        origin = "COMPLETED_DURING_COLLECTION",
                        sensorTimestampNs = event.sensorTimestampNs,
                        pairCompleteElapsedNs = event.pairCompleteElapsedNs,
                        generation = event.pipelineGeneration,
                        controlRequestEpoch = event.controlRequestEpoch,
                        requestProvenanceStatus = event.requestProvenanceStatus,
                        format = event.format,
                        accepted = false,
                        reason = "ALREADY_COLLECTED_FROM_INITIAL_SET"
                    )
                    return null
                }

                val eventDeltaMs = sensorDeltaMs(event.sensorTimestampNs)
                val existingDeltas =
                    ownedCandidates.mapNotNull { sensorDeltaMs(it.timestamp) }
                val hasPostShutterSensorCandidate =
                    existingDeltas.any { it >= 0.0 }
                val meaningful = eventDeltaMs == null ||
                        ShutterCandidateCollectionPolicy.isMeaningfulCandidate(
                            candidateDeltaMs = eventDeltaMs,
                            currentAbsoluteDeltasMs = existingDeltas.map(::abs),
                            hasPostShutterSensorCandidate =
                                hasPostShutterSensorCandidate
                        )

                var accepted = false
                if (!meaningful) {
                    recordConsideration(
                        origin = "COMPLETED_DURING_COLLECTION",
                        sensorTimestampNs = event.sensorTimestampNs,
                        pairCompleteElapsedNs = event.pairCompleteElapsedNs,
                        generation = event.pipelineGeneration,
                        controlRequestEpoch = event.controlRequestEpoch,
                        requestProvenanceStatus = event.requestProvenanceStatus,
                        format = event.format,
                        accepted = false,
                        reason = "DOES_NOT_IMPROVE_SHUTTER_PROXIMITY_OR_TEMPORAL_COVERAGE"
                    )
                } else {
                    val transferred = ringBuffer.takeCompleteFrame(
                        timestampNs = event.sensorTimestampNs,
                        generationId = expectedGeneration,
                        expectedFormat = expectedFormat
                    )
                    if (transferred == null) {
                        recordConsideration(
                            origin = "COMPLETED_DURING_COLLECTION",
                            sensorTimestampNs = event.sensorTimestampNs,
                            pairCompleteElapsedNs = event.pairCompleteElapsedNs,
                            generation = event.pipelineGeneration,
                            controlRequestEpoch = event.controlRequestEpoch,
                            requestProvenanceStatus =
                                event.requestProvenanceStatus,
                            format = event.format,
                            accepted = false,
                            reason = "PAIR_NO_LONGER_RETAINED_BY_RING"
                        )
                    } else {
                        accepted = considerTransferredFrame(
                            transferred,
                            "COMPLETED_DURING_COLLECTION"
                        )
                        if (accepted && ownedCandidates.size > maxCandidates) {
                            val candidateToRemove =
                                ownedCandidates.maxByOrNull {
                                    abs(sensorDeltaMs(it.timestamp) ?: Double.MAX_VALUE)
                            }
                            if (candidateToRemove != null) {
                                ownedCandidates.remove(candidateToRemove)
                                recordConsideration(
                                    origin = "COLLECTOR_CAP_REPLACEMENT",
                                    sensorTimestampNs = candidateToRemove.timestamp,
                                    pairCompleteElapsedNs =
                                        candidateToRemove.pairCompleteElapsedNs,
                                    generation = candidateToRemove.generationId,
                                    controlRequestEpoch =
                                        candidateToRemove.controlRequestEpoch,
                                    requestProvenanceStatus =
                                        candidateToRemove.requestProvenance
                                            ?.associationStatus ?: "UNPROVEN",
                                    format = candidateToRemove.format,
                                    accepted = false,
                                    reason =
                                        "REPLACED_BY_CLOSER_SHUTTER_CENTERED_CANDIDATE"
                                )
                            }
                        }
                    }
                }

                if (
                    eventDeltaMs != null &&
                    ShutterCandidateCollectionPolicy.isNearShutter(eventDeltaMs) &&
                    accepted
                ) {
                    return ShutterCollectorCloseReason
                        .NEAR_SHUTTER_CANDIDATE_ACQUIRED to true
                }
                if (
                    eventDeltaMs != null &&
                    eventDeltaMs >= 0.0 &&
                    ownedCandidates.isNotEmpty()
                ) {
                    return ShutterCollectorCloseReason
                        .TEMPORAL_WINDOW_SUFFICIENT to true
                }
                if (
                    ownedCandidates.isNotEmpty() &&
                    nextFrameWorthWaiting() == false
                ) {
                    return ShutterCollectorCloseReason
                        .NEXT_FRAME_NOT_WORTH_WAITING to true
                }
                return null
            }

            while (true) {
                val remainingNs =
                    deadlineElapsedNs - SystemClock.elapsedRealtimeNanos()
                if (remainingNs <= 0L) {
                    return closeResult(
                        ShutterCollectorCloseReason.HARD_DEADLINE_REACHED
                    )
                }
                val timeoutMs =
                    ceil(remainingNs / 1_000_000.0).toLong().coerceAtLeast(1L)
                val wakeEvent = withTimeoutOrNull(timeoutMs) {
                    ringBuffer.awaitEventAfter(eventSequence)
                } ?: return closeResult(
                    ShutterCollectorCloseReason.HARD_DEADLINE_REACHED
                )
                val pendingEvents = ringBuffer.eventsAfter(eventSequence)
                if (pendingEvents.isEmpty()) {
                    eventSequence = wakeEvent.sequence
                    continue
                }
                for (event in pendingEvents) {
                    eventSequence = event.sequence
                    val closeDecision = processEvent(event)
                    if (closeDecision != null) {
                        return closeResult(
                            reason = closeDecision.first,
                            retainCandidates = closeDecision.second
                        )
                    }
                }
            }
        } catch (cancellation: CancellationException) {
            ownedCandidates.clear()
            Log.i(
                tag,
                "event=COLLECTOR_CLOSE closeReason=${ShutterCollectorCloseReason.CAPTURE_ABORTED.name} " +
                        "collectionDurationMs=${formatMs(
                            (SystemClock.elapsedRealtimeNanos() - collectorStartedElapsedNs) /
                                    1_000_000.0
                        )} hardDeadlineMs=$hardDeadlineMs candidateCount=0"
            )
            throw cancellation
        } catch (failure: Throwable) {
            ownedCandidates.clear()
            Log.e(
                tag,
                "event=COLLECTOR_CLOSE closeReason=${ShutterCollectorCloseReason.CAPTURE_ABORTED.name} " +
                        "hardDeadlineMs=$hardDeadlineMs candidateCount=0",
                failure
            )
            throw failure
        }
    }

    private fun validateEvent(
        event: FrameRingEvent,
        expectedGeneration: Int,
        expectedFormat: Int
    ): String? = when {
        event.pipelineGeneration != expectedGeneration ->
            "PIPELINE_GENERATION_MISMATCH"
        event.controlRequestEpoch <= 0L ->
            "MISSING_CONTROL_REQUEST_EPOCH"
        event.requestProvenanceStatus != "EXACT_CAPTURE_CALLBACK_REQUEST_TAG" ->
            "UNPROVEN_REQUEST_ASSOCIATION_${event.requestProvenanceStatus}"
        event.format != expectedFormat ->
            "FRAME_SOURCE_FORMAT_MISMATCH"
        event.sensorTimestampNs <= 0L ->
            "INVALID_SENSOR_TIMESTAMP"
        event.pairCompleteElapsedNs <= 0L ->
            "INVALID_PAIR_COMPLETION_TIMESTAMP"
        else -> null
    }

    private fun formatMs(value: Double?): String =
        value?.let { String.format(Locale.US, "%.3f", it) }
            ?: "unavailable_clock_domain"
}
