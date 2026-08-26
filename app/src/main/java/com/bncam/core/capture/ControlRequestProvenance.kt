package com.bncam.core.capture

import java.util.Collections
import java.util.LinkedHashMap

data class ImmutableRectSnapshot(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
)

data class ImmutableMeteringRegionSnapshot(
    val rect: ImmutableRectSnapshot,
    val weight: Int
)

/**
 * Immutable values for the Camera2 controls that materially identify metering/exposure/focus
 * request state. Mutable Camera2 arrays and rectangles are converted before construction.
 */
class ControlRequestState private constructor(
    aeRegions: Collection<ImmutableMeteringRegionSnapshot>,
    afRegions: Collection<ImmutableMeteringRegionSnapshot>,
    val controlMode: Int?,
    val controlSceneMode: Int?,
    val captureIntent: Int?,
    val aeMode: Int?,
    val aeLock: Boolean?,
    val aeExposureCompensation: Int?,
    val aePrecaptureTrigger: Int?,
    val aeAntibandingMode: Int?,
    val afMode: Int?,
    val afTrigger: Int?,
    val flashMode: Int?,
    val sensorSensitivityIso: Int?,
    val sensorExposureTimeNs: Long?,
    val sensorFrameDurationNs: Long?,
    val lensFocusDistance: Float?,
    val lensOpticalStabilizationMode: Int?,
    val videoStabilizationMode: Int?,
    val aeTargetFpsLower: Int?,
    val aeTargetFpsUpper: Int?,
    val cropRegion: ImmutableRectSnapshot?,
    val zoomRatio: Float?
) {
    val aeRegions: List<ImmutableMeteringRegionSnapshot> =
        Collections.unmodifiableList(ArrayList(aeRegions))
    val afRegions: List<ImmutableMeteringRegionSnapshot> =
        Collections.unmodifiableList(ArrayList(afRegions))

    private val equalityKey = EqualityKey(
        aeRegions = this.aeRegions,
        afRegions = this.afRegions,
        controlMode = controlMode,
        controlSceneMode = controlSceneMode,
        captureIntent = captureIntent,
        aeMode = aeMode,
        aeLock = aeLock,
        aeExposureCompensation = aeExposureCompensation,
        aePrecaptureTrigger = aePrecaptureTrigger,
        aeAntibandingMode = aeAntibandingMode,
        afMode = afMode,
        afTrigger = afTrigger,
        flashMode = flashMode,
        sensorSensitivityIso = sensorSensitivityIso,
        sensorExposureTimeNs = sensorExposureTimeNs,
        sensorFrameDurationNs = sensorFrameDurationNs,
        lensFocusDistance = lensFocusDistance,
        lensOpticalStabilizationMode = lensOpticalStabilizationMode,
        videoStabilizationMode = videoStabilizationMode,
        aeTargetFpsLower = aeTargetFpsLower,
        aeTargetFpsUpper = aeTargetFpsUpper,
        cropRegion = cropRegion,
        zoomRatio = zoomRatio
    )

    override fun equals(other: Any?): Boolean =
        other is ControlRequestState && equalityKey == other.equalityKey

    override fun hashCode(): Int = equalityKey.hashCode()

    override fun toString(): String = equalityKey.toString()

    private data class EqualityKey(
        val aeRegions: List<ImmutableMeteringRegionSnapshot>,
        val afRegions: List<ImmutableMeteringRegionSnapshot>,
        val controlMode: Int?,
        val controlSceneMode: Int?,
        val captureIntent: Int?,
        val aeMode: Int?,
        val aeLock: Boolean?,
        val aeExposureCompensation: Int?,
        val aePrecaptureTrigger: Int?,
        val aeAntibandingMode: Int?,
        val afMode: Int?,
        val afTrigger: Int?,
        val flashMode: Int?,
        val sensorSensitivityIso: Int?,
        val sensorExposureTimeNs: Long?,
        val sensorFrameDurationNs: Long?,
        val lensFocusDistance: Float?,
        val lensOpticalStabilizationMode: Int?,
        val videoStabilizationMode: Int?,
        val aeTargetFpsLower: Int?,
        val aeTargetFpsUpper: Int?,
        val cropRegion: ImmutableRectSnapshot?,
        val zoomRatio: Float?
    )

    companion object {
        fun create(
            aeRegions: Collection<ImmutableMeteringRegionSnapshot> = emptyList(),
            afRegions: Collection<ImmutableMeteringRegionSnapshot> = emptyList(),
            controlMode: Int? = null,
            controlSceneMode: Int? = null,
            captureIntent: Int? = null,
            aeMode: Int? = null,
            aeLock: Boolean? = null,
            aeExposureCompensation: Int? = null,
            aePrecaptureTrigger: Int? = null,
            aeAntibandingMode: Int? = null,
            afMode: Int? = null,
            afTrigger: Int? = null,
            flashMode: Int? = null,
            sensorSensitivityIso: Int? = null,
            sensorExposureTimeNs: Long? = null,
            sensorFrameDurationNs: Long? = null,
            lensFocusDistance: Float? = null,
            lensOpticalStabilizationMode: Int? = null,
            videoStabilizationMode: Int? = null,
            aeTargetFpsLower: Int? = null,
            aeTargetFpsUpper: Int? = null,
            cropRegion: ImmutableRectSnapshot? = null,
            zoomRatio: Float? = null
        ): ControlRequestState = ControlRequestState(
            aeRegions = aeRegions,
            afRegions = afRegions,
            controlMode = controlMode,
            controlSceneMode = controlSceneMode,
            captureIntent = captureIntent,
            aeMode = aeMode,
            aeLock = aeLock,
            aeExposureCompensation = aeExposureCompensation,
            aePrecaptureTrigger = aePrecaptureTrigger,
            aeAntibandingMode = aeAntibandingMode,
            afMode = afMode,
            afTrigger = afTrigger,
            flashMode = flashMode,
            sensorSensitivityIso = sensorSensitivityIso,
            sensorExposureTimeNs = sensorExposureTimeNs,
            sensorFrameDurationNs = sensorFrameDurationNs,
            lensFocusDistance = lensFocusDistance,
            lensOpticalStabilizationMode = lensOpticalStabilizationMode,
            videoStabilizationMode = videoStabilizationMode,
            aeTargetFpsLower = aeTargetFpsLower,
            aeTargetFpsUpper = aeTargetFpsUpper,
            cropRegion = cropRegion,
            zoomRatio = zoomRatio
        )
    }
}

data class CameraRequestIdentity(
    val pipelineGeneration: Int,
    val controlRequestEpoch: Long
)

enum class CameraRequestSubmissionType {
    REPEATING,
    ONE_SHOT,
    BURST
}

data class ControlRequestSnapshot(
    val identity: CameraRequestIdentity,
    val state: ControlRequestState,
    val submissionType: CameraRequestSubmissionType,
    val submissionReason: String,
    val meteringPolicySummary: String,
    val exposurePolicySummary: String,
    val submittedElapsedRealtimeNs: Long,
    val focusOwner: String = "UNKNOWN"
) {
    fun diagnosticSummary(): String {
        fun List<ImmutableMeteringRegionSnapshot>.formatRegions(): String =
            if (isEmpty()) {
                "none"
            } else {
                joinToString(prefix = "[", postfix = "]") { region ->
                    val rect = region.rect
                    "(${rect.left},${rect.top},${rect.right},${rect.bottom},w=${region.weight})"
                }
            }

        return buildString {
            append("pipelineGeneration=${identity.pipelineGeneration} ")
            append("controlRequestEpoch=${identity.controlRequestEpoch} ")
            append("submissionType=${submissionType.name} ")
            append("submissionReason=$submissionReason ")
            append("focusOwner=$focusOwner ")
            append("submittedElapsedRealtimeNs=$submittedElapsedRealtimeNs ")
            append("aeRegions=${state.aeRegions.formatRegions()} ")
            append("afRegions=${state.afRegions.formatRegions()} ")
            append("controlMode=${state.controlMode} ")
            append("controlSceneMode=${state.controlSceneMode} ")
            append("captureIntent=${state.captureIntent} ")
            append("aeMode=${state.aeMode} ")
            append("aeLock=${state.aeLock} ")
            append("aeExposureCompensation=${state.aeExposureCompensation} ")
            append("aePrecaptureTrigger=${state.aePrecaptureTrigger} ")
            append("aeAntibandingMode=${state.aeAntibandingMode} ")
            append("afMode=${state.afMode} ")
            append("afTrigger=${state.afTrigger} ")
            append("flashMode=${state.flashMode} ")
            append("sensorSensitivityIso=${state.sensorSensitivityIso} ")
            append("sensorExposureTimeNs=${state.sensorExposureTimeNs} ")
            append("sensorFrameDurationNs=${state.sensorFrameDurationNs} ")
            append("lensFocusDistance=${state.lensFocusDistance} ")
            append("lensOpticalStabilizationMode=${state.lensOpticalStabilizationMode} ")
            append("videoStabilizationMode=${state.videoStabilizationMode} ")
            append("aeTargetFps=${state.aeTargetFpsLower}:${state.aeTargetFpsUpper} ")
            append("cropRegion=${state.cropRegion} ")
            append("zoomRatio=${state.zoomRatio} ")
            append("meteringPolicySummary=$meteringPolicySummary ")
            append("exposurePolicySummary=$exposurePolicySummary")
        }
    }
}

/**
 * This is the only tag shape installed by BnCameraManager. The snapshot is embedded so a delayed
 * CaptureResult remains self-describing even after bounded diagnostic history is trimmed.
 */
data class CameraRequestTag(
    val pipelineGeneration: Int,
    val controlRequestEpoch: Long,
    val snapshot: ControlRequestSnapshot
) {
    val identity: CameraRequestIdentity
        get() = CameraRequestIdentity(pipelineGeneration, controlRequestEpoch)
}

data class FrameRequestProvenance(
    val identity: CameraRequestIdentity,
    val snapshot: ControlRequestSnapshot,
    val associationStatus: String = "EXACT_CAPTURE_CALLBACK_REQUEST_TAG"
)

data class SelectedFrameProvenanceProof(
    val exact: Boolean,
    val status: String,
    val snapshot: ControlRequestSnapshot?
)

object SelectedFrameProvenanceValidator {
    fun verify(
        framePipelineGeneration: Int,
        frameControlRequestEpoch: Long,
        frameTimestampNs: Long,
        metadataTimestampNs: Long?,
        provenance: FrameRequestProvenance?
    ): SelectedFrameProvenanceProof {
        val failure = when {
            provenance == null -> "UNPROVEN_MISSING_FRAME_REQUEST_PROVENANCE"
            framePipelineGeneration < 0 -> "UNPROVEN_INVALID_FRAME_PIPELINE_GENERATION"
            frameControlRequestEpoch <= 0L -> "UNPROVEN_INVALID_FRAME_CONTROL_REQUEST_EPOCH"
            provenance.identity.pipelineGeneration != framePipelineGeneration ->
                "UNPROVEN_FRAME_PIPELINE_TAG_MISMATCH"
            provenance.identity.controlRequestEpoch != frameControlRequestEpoch ->
                "UNPROVEN_FRAME_CONTROL_EPOCH_TAG_MISMATCH"
            provenance.snapshot.identity != provenance.identity ->
                "UNPROVEN_FRAME_SNAPSHOT_IDENTITY_MISMATCH"
            frameTimestampNs <= 0L || metadataTimestampNs == null ||
                    metadataTimestampNs <= 0L ->
                "UNPROVEN_INVALID_FRAME_RESULT_TIMESTAMP"
            metadataTimestampNs != frameTimestampNs ->
                "UNPROVEN_FRAME_RESULT_TIMESTAMP_MISMATCH"
            else -> null
        }
        return if (failure == null) {
            SelectedFrameProvenanceProof(
                exact = true,
                status = "EXACT_FRAME_REQUEST_RESULT_PROVENANCE",
                snapshot = provenance!!.snapshot
            )
        } else {
            SelectedFrameProvenanceProof(
                exact = false,
                status = failure,
                snapshot = null
            )
        }
    }
}

object MeteringExactTruth {
    fun canReportAppliedExact(
        exactFrameRequestResultProvenance: Boolean,
        meteringRequested: Boolean,
        requestRegionsExactlyMatchedByResult: Boolean,
        requestedEvCompensation: Int?,
        resultEvCompensation: Int?
    ): Boolean =
        exactFrameRequestResultProvenance &&
                meteringRequested &&
                requestRegionsExactlyMatchedByResult &&
                requestedEvCompensation != null &&
                resultEvCompensation != null &&
                requestedEvCompensation == resultEvCompensation
}

internal data class CameraRequestTagResolution(
    val provenance: FrameRequestProvenance?,
    val status: String
) {
    val exact: Boolean get() = provenance != null
}

internal class PreparedControlRequest internal constructor(
    val tag: CameraRequestTag,
    val advancesEpoch: Boolean,
    internal val expectedPreviousEpoch: Long
)

/**
 * Tracks the last successfully submitted relevant Camera2 control state. Epoch preparation does
 * not mutate the committed epoch; callers commit only after Camera2 accepts the submission.
 */
internal class ControlRequestEpochTracker(
    private val retainedSnapshotLimit: Int = 128
) {
    private var submittedEpoch: Long = 0L
    private var lastSubmittedState: ControlRequestState? = null
    private var lastSubmittedIdentity: CameraRequestIdentity? = null
    private val retainedSnapshots =
        LinkedHashMap<CameraRequestIdentity, ControlRequestSnapshot>()

    init {
        require(retainedSnapshotLimit >= 8)
    }

    @Synchronized
    fun prepare(
        pipelineGeneration: Int,
        state: ControlRequestState,
        submissionType: CameraRequestSubmissionType,
        submissionReason: String,
        meteringPolicySummary: String,
        exposurePolicySummary: String,
        submittedElapsedRealtimeNs: Long,
        focusOwner: String = "UNKNOWN"
    ): PreparedControlRequest {
        val advancesEpoch = submittedEpoch == 0L || lastSubmittedState != state
        val proposedEpoch = if (advancesEpoch) submittedEpoch + 1L else submittedEpoch
        val identity = CameraRequestIdentity(
            pipelineGeneration = pipelineGeneration,
            controlRequestEpoch = proposedEpoch
        )
        val snapshot = ControlRequestSnapshot(
            identity = identity,
            state = state,
            submissionType = submissionType,
            submissionReason = submissionReason,
            meteringPolicySummary = meteringPolicySummary,
            exposurePolicySummary = exposurePolicySummary,
            submittedElapsedRealtimeNs = submittedElapsedRealtimeNs,
            focusOwner = focusOwner
        )
        return PreparedControlRequest(
            tag = CameraRequestTag(
                pipelineGeneration = pipelineGeneration,
                controlRequestEpoch = proposedEpoch,
                snapshot = snapshot
            ),
            advancesEpoch = advancesEpoch,
            expectedPreviousEpoch = submittedEpoch
        )
    }

    @Synchronized
    fun commit(prepared: PreparedControlRequest) {
        check(submittedEpoch == prepared.expectedPreviousEpoch) {
            "Control request epoch changed between prepare and commit."
        }
        val snapshot = prepared.tag.snapshot
        check(snapshot.identity == prepared.tag.identity) {
            "Camera request tag and immutable snapshot identity disagree."
        }
        if (prepared.advancesEpoch) {
            submittedEpoch = prepared.tag.controlRequestEpoch
            lastSubmittedState = snapshot.state
        }
        lastSubmittedIdentity = prepared.tag.identity
        retainedSnapshots[prepared.tag.identity] = snapshot
        while (retainedSnapshots.size > retainedSnapshotLimit) {
            val oldest = retainedSnapshots.entries.iterator()
            if (!oldest.hasNext()) break
            oldest.next()
            oldest.remove()
        }
    }

    @Synchronized
    fun currentSubmittedEpoch(): Long = submittedEpoch

    @Synchronized
    fun currentSubmittedIdentity(): CameraRequestIdentity? = lastSubmittedIdentity

    @Synchronized
    fun retainedSnapshot(identity: CameraRequestIdentity): ControlRequestSnapshot? =
        retainedSnapshots[identity]

    @Synchronized
    fun retainedSnapshotCount(): Int = retainedSnapshots.size

    fun resolveTag(tag: Any?, expectedPipelineGeneration: Int): CameraRequestTagResolution {
        val requestTag = tag as? CameraRequestTag
            ?: return CameraRequestTagResolution(
                provenance = null,
                status = when (tag) {
                    null -> "UNPROVEN_MISSING_REQUEST_TAG"
                    is Int -> "UNPROVEN_LEGACY_PIPELINE_ONLY_TAG"
                    else -> "UNPROVEN_UNKNOWN_REQUEST_TAG_TYPE_${tag.javaClass.simpleName}"
                }
            )
        if (requestTag.pipelineGeneration != expectedPipelineGeneration) {
            return CameraRequestTagResolution(
                provenance = null,
                status = "UNPROVEN_PIPELINE_TAG_MISMATCH"
            )
        }
        if (requestTag.snapshot.identity != requestTag.identity) {
            return CameraRequestTagResolution(
                provenance = null,
                status = "UNPROVEN_TAG_SNAPSHOT_IDENTITY_MISMATCH"
            )
        }
        return CameraRequestTagResolution(
            provenance = FrameRequestProvenance(
                identity = requestTag.identity,
                snapshot = requestTag.snapshot
            ),
            status = "EXACT_CAPTURE_CALLBACK_REQUEST_TAG"
        )
    }
}
