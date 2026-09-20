package com.bncam.core.engine

import java.util.ArrayDeque
import java.util.Locale

/**
 * Coherent runtime truth for the currently resolved Camera2 stream topology.
 *
 * This intentionally keeps session/stream decisions separate from CaptureRequest cadence. The
 * snapshot records both, but requested FPS never becomes part of Stream Configuration authority.
 */
enum class StreamTelemetrySessionState {
    UNRESOLVED,
    RESOLVED,
    CONFIGURED,
    CONFIGURE_FAILED,
    CLOSED
}

data class StreamRoleTelemetrySnapshot(
    val roleId: String,
    val kind: StreamRoleKind,
    val logicalCameraId: String,
    val physicalCameraId: String?,
    val formatCode: Int,
    val extent: StreamExtent,
    val outputKind: StreamOutputKind,
    val lifetime: StreamRoleLifetime,
    val maxImages: Int?,
    val runtimeName: String?,
    val sessionOutput: Boolean,
    val repeatingTarget: Boolean,
    val captureTarget: Boolean
)

data class StreamProducerCadenceSnapshot(
    val roleId: String,
    val sampleCount: Int,
    val sensorFps: Double?,
    val arrivalFps: Double?,
    val lastSensorTimestampNs: Long,
    val lastArrivalElapsedNs: Long
)

data class StreamRuntimeTelemetrySnapshot(
    val pipelineGeneration: Int,
    val sessionEpoch: Long,
    val sessionState: StreamTelemetrySessionState,
    val sessionIdentityHash: Int?,
    val logicalCameraId: String?,
    val advertisedPhysicalCameraIds: Set<String>,
    val operationModePolicy: OperationModePolicyKind?,
    val operationMode: Int?,
    val streamMode: StreamSessionMode?,
    val operationModeEvidence: String?,
    val roles: List<StreamRoleTelemetrySnapshot>,
    val sessionRoleIds: Set<String>,
    val repeatingRoleIds: Set<String>,
    val captureRoleIds: Set<String>,
    val requestGraphReason: String?,
    val requestedRepeatingFpsLower: Int?,
    val requestedRepeatingFpsUpper: Int?,
    val requestedRepeatingFpsReason: String?,
    val captureResultReportedFpsLower: Int?,
    val captureResultReportedFpsUpper: Int?,
    val captureResultSensorFps: Double?,
    val captureResultSampleCount: Int,
    val producerCadence: List<StreamProducerCadenceSnapshot>,
    val lastUpdatedElapsedNs: Long
) {
    fun report(): String = buildString {
        appendLine(
            "STREAM_RUNTIME_TELEMETRY generation=$pipelineGeneration sessionEpoch=$sessionEpoch " +
                "state=$sessionState sessionIdentity=${sessionIdentityHash ?: "none"}"
        )
        appendLine(
            "logicalCamera=${logicalCameraId ?: "none"} " +
                "advertisedPhysical=${advertisedPhysicalCameraIds.sorted()}"
        )
        appendLine(
            "operationModePolicy=${operationModePolicy ?: "none"} " +
                "operationMode=${operationMode ?: "none"} " +
                "operationModeHex=${operationMode?.let { "0x${it.toString(16).uppercase()}" } ?: "none"} " +
                "streamMode=${streamMode ?: "none"}"
        )
        appendLine("operationModeEvidence=${operationModeEvidence ?: "none"}")
        appendLine("sessionOutputs=${sessionRoleIds.sorted()}")
        appendLine("repeatingTargets=${repeatingRoleIds.sorted()} requestGraphReason=${requestGraphReason ?: "none"}")
        appendLine("captureTargets=${captureRoleIds.sorted()}")
        appendLine(
            "requestedRepeatingFps=${formatRange(requestedRepeatingFpsLower, requestedRepeatingFpsUpper)} " +
                "reason=${requestedRepeatingFpsReason ?: "none"} " +
                "captureResultReportedFps=${formatRange(captureResultReportedFpsLower, captureResultReportedFpsUpper)} " +
                "captureResultSensorFps=${formatFps(captureResultSensorFps)} " +
                "captureResultSamples=$captureResultSampleCount"
        )
        appendLine(
            "roles=" + roles.sortedBy { it.roleId }.joinToString(separator = ";") { role ->
                "${role.roleId}{kind=${role.kind},runtime=${role.runtimeName ?: "none"}," +
                    "format=${role.formatCode},extent=${role.extent.width}x${role.extent.height}," +
                    "output=${role.outputKind},lifetime=${role.lifetime},maxImages=${role.maxImages ?: "none"}," +
                    "logical=${role.logicalCameraId},physical=${role.physicalCameraId ?: "logical"}," +
                    "session=${role.sessionOutput},repeating=${role.repeatingTarget},capture=${role.captureTarget}}"
            }
        )
        appendLine(
            "producerCadence=" + producerCadence.sortedBy { it.roleId }.joinToString(separator = ";") { cadence ->
                "${cadence.roleId}{samples=${cadence.sampleCount},sensorFps=${formatFps(cadence.sensorFps)}," +
                    "arrivalFps=${formatFps(cadence.arrivalFps)},lastSensorTimestampNs=${cadence.lastSensorTimestampNs}," +
                    "lastArrivalElapsedNs=${cadence.lastArrivalElapsedNs}}"
            }
        )
        append("lastUpdatedElapsedNs=$lastUpdatedElapsedNs")
    }

    private fun formatRange(lower: Int?, upper: Int?): String =
        if (lower == null || upper == null) "none" else "$lower:$upper"

    private fun formatFps(value: Double?): String =
        value?.takeIf { it.isFinite() && it >= 0.0 }
            ?.let { String.format(Locale.US, "%.3f", it) }
            ?: "none"
}

/**
 * Small synchronized rolling telemetry owner. It never changes camera policy or recovery state.
 */
class StreamRuntimeTelemetryTracker(
    private val cadenceWindowSize: Int = DEFAULT_CADENCE_WINDOW_SIZE
) {
    private data class CadenceAccumulator(
        val sensorTimestampsNs: ArrayDeque<Long> = ArrayDeque(),
        val arrivalElapsedNs: ArrayDeque<Long> = ArrayDeque()
    )

    init {
        require(cadenceWindowSize >= 2) { "cadenceWindowSize must be >= 2" }
    }

    private var pipelineGeneration: Int = -1
    private var sessionEpoch: Long = -1L
    private var sessionState: StreamTelemetrySessionState = StreamTelemetrySessionState.UNRESOLVED
    private var sessionIdentityHash: Int? = null
    private var logicalCameraId: String? = null
    private var advertisedPhysicalCameraIds: Set<String> = emptySet()
    private var operationModePolicy: OperationModePolicyKind? = null
    private var operationMode: Int? = null
    private var streamMode: StreamSessionMode? = null
    private var operationModeEvidence: String? = null
    private var roles: List<StreamRoleTelemetrySnapshot> = emptyList()
    private var sessionRoleIds: Set<String> = emptySet()
    private var repeatingRoleIds: Set<String> = emptySet()
    private var captureRoleIds: Set<String> = emptySet()
    private var requestGraphReason: String? = null
    private var requestedRepeatingFpsLower: Int? = null
    private var requestedRepeatingFpsUpper: Int? = null
    private var requestedRepeatingFpsReason: String? = null
    private var captureResultReportedFpsLower: Int? = null
    private var captureResultReportedFpsUpper: Int? = null
    private val captureResultSensorTimestampsNs = ArrayDeque<Long>()
    private val producerCadence = linkedMapOf<String, CadenceAccumulator>()
    private var lastUpdatedElapsedNs: Long = 0L

    @Synchronized
    fun resolvedConfiguration(
        generation: Int,
        epoch: Long,
        logicalCameraId: String,
        advertisedPhysicalCameraIds: Set<String>,
        configuration: ResolvedStreamConfiguration,
        runtimeNamesByRoleId: Map<String, String> = emptyMap(),
        nowElapsedNs: Long
    ) {
        require(generation >= 0) { "pipeline generation must be non-negative" }
        require(epoch >= 0L) { "session epoch must be non-negative" }
        require(logicalCameraId.isNotBlank()) { "logical camera id must not be blank" }

        pipelineGeneration = generation
        sessionEpoch = epoch
        sessionState = StreamTelemetrySessionState.RESOLVED
        sessionIdentityHash = null
        this.logicalCameraId = logicalCameraId
        this.advertisedPhysicalCameraIds = advertisedPhysicalCameraIds.toSet()
        operationModePolicy = configuration.operationMode.policy
        operationMode = configuration.operationMode.operationMode
        streamMode = configuration.streamMode
        operationModeEvidence = configuration.operationMode.evidence
        sessionRoleIds = configuration.sessionGraph.roleIds.toSet()
        repeatingRoleIds = configuration.requestGraph.repeatingRoleIds.toSet()
        captureRoleIds = configuration.requestGraph.captureRoleIds.toSet()
        requestGraphReason = "SESSION_RESOLVE"
        roles = configuration.roleGraph.roles.map { role ->
            StreamRoleTelemetrySnapshot(
                roleId = role.id,
                kind = role.kind,
                logicalCameraId = role.logicalCameraId,
                physicalCameraId = role.physicalCameraId,
                formatCode = role.formatCode,
                extent = role.extent,
                outputKind = role.outputKind,
                lifetime = role.lifetime,
                maxImages = role.maxImages,
                runtimeName = runtimeNamesByRoleId[role.id],
                sessionOutput = role.id in sessionRoleIds,
                repeatingTarget = role.id in repeatingRoleIds,
                captureTarget = role.id in captureRoleIds
            )
        }.sortedBy { it.roleId }

        requestedRepeatingFpsLower = null
        requestedRepeatingFpsUpper = null
        requestedRepeatingFpsReason = null
        captureResultReportedFpsLower = null
        captureResultReportedFpsUpper = null
        captureResultSensorTimestampsNs.clear()
        producerCadence.clear()
        lastUpdatedElapsedNs = nowElapsedNs
    }

    @Synchronized
    fun sessionConfigured(
        generation: Int,
        epoch: Long,
        identityHash: Int,
        nowElapsedNs: Long
    ) {
        if (!matches(generation, epoch)) return
        sessionState = StreamTelemetrySessionState.CONFIGURED
        sessionIdentityHash = identityHash
        lastUpdatedElapsedNs = nowElapsedNs
    }

    @Synchronized
    fun sessionConfigureFailed(
        generation: Int,
        epoch: Long,
        identityHash: Int,
        nowElapsedNs: Long
    ) {
        if (!matches(generation, epoch)) return
        sessionState = StreamTelemetrySessionState.CONFIGURE_FAILED
        sessionIdentityHash = identityHash
        lastUpdatedElapsedNs = nowElapsedNs
    }

    @Synchronized
    fun sessionCreationFailed(
        generation: Int,
        epoch: Long,
        nowElapsedNs: Long
    ) {
        if (!matches(generation, epoch)) return
        sessionState = StreamTelemetrySessionState.CONFIGURE_FAILED
        sessionIdentityHash = null
        lastUpdatedElapsedNs = nowElapsedNs
    }

    @Synchronized
    fun sessionClosed(
        generation: Int,
        epoch: Long,
        identityHash: Int,
        nowElapsedNs: Long
    ) {
        if (!matches(generation, epoch)) return
        sessionState = StreamTelemetrySessionState.CLOSED
        sessionIdentityHash = identityHash
        lastUpdatedElapsedNs = nowElapsedNs
    }

    @Synchronized
    fun requestGraphUpdated(
        generation: Int,
        epoch: Long,
        requestGraph: RequestTargetGraph,
        reason: String,
        nowElapsedNs: Long
    ) {
        if (!matches(generation, epoch)) return
        require(requestGraph.repeatingRoleIds.all { it in sessionRoleIds }) {
            "telemetry repeating targets must remain configured session outputs"
        }
        require(requestGraph.captureRoleIds.all { it in sessionRoleIds }) {
            "telemetry capture targets must remain configured session outputs"
        }
        repeatingRoleIds = requestGraph.repeatingRoleIds.toSet()
        captureRoleIds = requestGraph.captureRoleIds.toSet()
        requestGraphReason = reason
        roles = roles.map { role ->
            role.copy(
                repeatingTarget = role.roleId in repeatingRoleIds,
                captureTarget = role.roleId in captureRoleIds
            )
        }
        lastUpdatedElapsedNs = nowElapsedNs
    }

    @Synchronized
    fun repeatingRequestSubmitted(
        generation: Int,
        lowerFps: Int?,
        upperFps: Int?,
        reason: String,
        nowElapsedNs: Long
    ) {
        if (generation != pipelineGeneration) return
        val valid = lowerFps != null && upperFps != null && lowerFps > 0 && upperFps >= lowerFps
        requestedRepeatingFpsLower = if (valid) lowerFps else null
        requestedRepeatingFpsUpper = if (valid) upperFps else null
        requestedRepeatingFpsReason = reason
        lastUpdatedElapsedNs = nowElapsedNs
    }

    @Synchronized
    fun captureResult(
        generation: Int,
        sensorTimestampNs: Long,
        reportedFpsLower: Int?,
        reportedFpsUpper: Int?,
        nowElapsedNs: Long
    ) {
        if (generation != pipelineGeneration || sensorTimestampNs <= 0L) return
        appendMonotonic(captureResultSensorTimestampsNs, sensorTimestampNs)
        val valid = reportedFpsLower != null && reportedFpsUpper != null &&
            reportedFpsLower > 0 && reportedFpsUpper >= reportedFpsLower
        if (valid) {
            captureResultReportedFpsLower = reportedFpsLower
            captureResultReportedFpsUpper = reportedFpsUpper
        }
        lastUpdatedElapsedNs = nowElapsedNs
    }

    @Synchronized
    fun producerFrameArrived(
        generation: Int,
        roleId: String,
        sensorTimestampNs: Long,
        arrivalElapsedNs: Long
    ) {
        if (generation != pipelineGeneration || sensorTimestampNs <= 0L || arrivalElapsedNs <= 0L) return
        if (roleId !in sessionRoleIds) return
        val accumulator = producerCadence.getOrPut(roleId) { CadenceAccumulator() }
        appendMonotonic(accumulator.sensorTimestampsNs, sensorTimestampNs)
        appendMonotonic(accumulator.arrivalElapsedNs, arrivalElapsedNs)
        lastUpdatedElapsedNs = arrivalElapsedNs
    }

    @Synchronized
    fun snapshot(): StreamRuntimeTelemetrySnapshot = StreamRuntimeTelemetrySnapshot(
        pipelineGeneration = pipelineGeneration,
        sessionEpoch = sessionEpoch,
        sessionState = sessionState,
        sessionIdentityHash = sessionIdentityHash,
        logicalCameraId = logicalCameraId,
        advertisedPhysicalCameraIds = advertisedPhysicalCameraIds.toSet(),
        operationModePolicy = operationModePolicy,
        operationMode = operationMode,
        streamMode = streamMode,
        operationModeEvidence = operationModeEvidence,
        roles = roles.toList(),
        sessionRoleIds = sessionRoleIds.toSet(),
        repeatingRoleIds = repeatingRoleIds.toSet(),
        captureRoleIds = captureRoleIds.toSet(),
        requestGraphReason = requestGraphReason,
        requestedRepeatingFpsLower = requestedRepeatingFpsLower,
        requestedRepeatingFpsUpper = requestedRepeatingFpsUpper,
        requestedRepeatingFpsReason = requestedRepeatingFpsReason,
        captureResultReportedFpsLower = captureResultReportedFpsLower,
        captureResultReportedFpsUpper = captureResultReportedFpsUpper,
        captureResultSensorFps = fpsFrom(captureResultSensorTimestampsNs),
        captureResultSampleCount = captureResultSensorTimestampsNs.size,
        producerCadence = producerCadence.map { (roleId, accumulator) ->
            StreamProducerCadenceSnapshot(
                roleId = roleId,
                sampleCount = accumulator.sensorTimestampsNs.size,
                sensorFps = fpsFrom(accumulator.sensorTimestampsNs),
                arrivalFps = fpsFrom(accumulator.arrivalElapsedNs),
                lastSensorTimestampNs = accumulator.sensorTimestampsNs.peekLast() ?: 0L,
                lastArrivalElapsedNs = accumulator.arrivalElapsedNs.peekLast() ?: 0L
            )
        },
        lastUpdatedElapsedNs = lastUpdatedElapsedNs
    )

    private fun matches(generation: Int, epoch: Long): Boolean =
        generation == pipelineGeneration && epoch == sessionEpoch

    private fun appendMonotonic(queue: ArrayDeque<Long>, value: Long) {
        val last = queue.peekLast()
        if (last != null && value <= last) return
        queue.addLast(value)
        while (queue.size > cadenceWindowSize) queue.removeFirst()
    }

    private fun fpsFrom(queue: ArrayDeque<Long>): Double? {
        if (queue.size < 2) return null
        val first = queue.peekFirst() ?: return null
        val last = queue.peekLast() ?: return null
        val spanNs = last - first
        if (spanNs <= 0L) return null
        return (queue.size - 1).toDouble() * 1_000_000_000.0 / spanNs.toDouble()
    }

    companion object {
        const val DEFAULT_CADENCE_WINDOW_SIZE = 64
    }
}

/** Process-scoped telemetry owner used by Camera2 runtime and RAW producer diagnostics. */
object StreamRuntimeTelemetry {
    private val tracker = StreamRuntimeTelemetryTracker()

    fun resolvedConfiguration(
        generation: Int,
        epoch: Long,
        logicalCameraId: String,
        advertisedPhysicalCameraIds: Set<String>,
        configuration: ResolvedStreamConfiguration,
        runtimeNamesByRoleId: Map<String, String>,
        nowElapsedNs: Long
    ) = tracker.resolvedConfiguration(
        generation,
        epoch,
        logicalCameraId,
        advertisedPhysicalCameraIds,
        configuration,
        runtimeNamesByRoleId,
        nowElapsedNs
    )

    fun sessionConfigured(generation: Int, epoch: Long, identityHash: Int, nowElapsedNs: Long) =
        tracker.sessionConfigured(generation, epoch, identityHash, nowElapsedNs)

    fun sessionConfigureFailed(generation: Int, epoch: Long, identityHash: Int, nowElapsedNs: Long) =
        tracker.sessionConfigureFailed(generation, epoch, identityHash, nowElapsedNs)

    fun sessionCreationFailed(generation: Int, epoch: Long, nowElapsedNs: Long) =
        tracker.sessionCreationFailed(generation, epoch, nowElapsedNs)

    fun sessionClosed(generation: Int, epoch: Long, identityHash: Int, nowElapsedNs: Long) =
        tracker.sessionClosed(generation, epoch, identityHash, nowElapsedNs)

    fun requestGraphUpdated(
        generation: Int,
        epoch: Long,
        requestGraph: RequestTargetGraph,
        reason: String,
        nowElapsedNs: Long
    ) = tracker.requestGraphUpdated(generation, epoch, requestGraph, reason, nowElapsedNs)

    fun repeatingRequestSubmitted(
        generation: Int,
        lowerFps: Int?,
        upperFps: Int?,
        reason: String,
        nowElapsedNs: Long
    ) = tracker.repeatingRequestSubmitted(generation, lowerFps, upperFps, reason, nowElapsedNs)

    fun captureResult(
        generation: Int,
        sensorTimestampNs: Long,
        reportedFpsLower: Int?,
        reportedFpsUpper: Int?,
        nowElapsedNs: Long
    ) = tracker.captureResult(
        generation,
        sensorTimestampNs,
        reportedFpsLower,
        reportedFpsUpper,
        nowElapsedNs
    )

    fun producerFrameArrived(
        generation: Int,
        roleId: String,
        sensorTimestampNs: Long,
        arrivalElapsedNs: Long
    ) = tracker.producerFrameArrived(generation, roleId, sensorTimestampNs, arrivalElapsedNs)

    fun snapshot(): StreamRuntimeTelemetrySnapshot = tracker.snapshot()

    fun latestReport(): String = snapshot().report()
}
