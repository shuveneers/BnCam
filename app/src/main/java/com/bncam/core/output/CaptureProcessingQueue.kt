package com.bncam.core.output

import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.util.LinkedHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

enum class CaptureWorkState {
    QUEUED,
    PROCESSING,
    SAVING,
    PUBLISHED,
    FAILED
}

data class CaptureWorkSnapshot(
    val workId: Long,
    val shotSequenceId: Long,
    val route: String,
    val state: CaptureWorkState,
    val progress: Float,
    val captureStartedNs: Long,
    val stateChangedNs: Long,
    val temporaryPreviewPath: String? = null,
    val failureReason: String? = null,
    val publishedUri: String? = null,
    val jpegUri: String? = null,
    val dngUri: String? = null,
    val thumbnailUri: String? = null,
    val publicationResult: CapturePublicationResult? = null,
    val publicationWarnings: List<CaptureWarning> = emptyList()
)

internal class BoundedCaptureWorkAdmission(private val maximumInFlight: Int) {
    init {
        require(maximumInFlight > 0)
    }

    private val inFlight = AtomicInteger(0)

    fun tryAcquire(): Boolean {
        while (true) {
            val current = inFlight.get()
            if (current >= maximumInFlight) return false
            if (inFlight.compareAndSet(current, current + 1)) return true
        }
    }

    fun release() {
        while (true) {
            val current = inFlight.get()
            if (current == 0) return
            if (inFlight.compareAndSet(current, current - 1)) return
        }
    }

    fun count(): Int = inFlight.get()
}

internal class CaptureWorkStateTracker(
    private val clockNs: () -> Long = SystemClock::elapsedRealtimeNanos,
    private val retainedTerminalCount: Int = 32
) {
    private val nextWorkId = AtomicLong(0L)
    private val nextSequenceId = AtomicLong(0L)
    private val snapshots = LinkedHashMap<Long, CaptureWorkSnapshot>()

    @Synchronized
    fun begin(
        route: String,
        captureStartedNs: Long,
        temporaryPreviewPath: String? = null
    ): CaptureWorkSnapshot {
        val seqId = nextSequenceId.incrementAndGet()
        val snapshot = CaptureWorkSnapshot(
            workId = nextWorkId.incrementAndGet(),
            shotSequenceId = seqId,
            route = route,
            state = CaptureWorkState.QUEUED,
            progress = 0.05f,
            captureStartedNs = captureStartedNs,
            stateChangedNs = clockNs(),
            temporaryPreviewPath = temporaryPreviewPath
        )
        snapshots[snapshot.workId] = snapshot
        trimTerminalHistory()
        return snapshot
    }

    @Synchronized
    fun transition(
        workId: Long,
        state: CaptureWorkState,
        progressOverride: Float? = null,
        failureReason: String? = null,
        publishedUri: String? = null,
        publishedOutputs: StringPublicationOutputs? = null
    ): CaptureWorkSnapshot? {
        val current = snapshots[workId] ?: return null
        if (current.state == CaptureWorkState.PUBLISHED || current.state == CaptureWorkState.FAILED) {
            return current
        }
        val allowed = when (current.state) {
            CaptureWorkState.QUEUED ->
                state == CaptureWorkState.PROCESSING || state == CaptureWorkState.FAILED
            CaptureWorkState.PROCESSING ->
                state == CaptureWorkState.SAVING ||
                    state == CaptureWorkState.PUBLISHED ||
                    state == CaptureWorkState.FAILED
            CaptureWorkState.SAVING ->
                state == CaptureWorkState.PUBLISHED || state == CaptureWorkState.FAILED
            CaptureWorkState.PUBLISHED,
            CaptureWorkState.FAILED -> false
        }
        require(allowed) {
            "Invalid capture work transition ${current.state} -> $state for workId=$workId"
        }

        val baseProgress = when (state) {
            CaptureWorkState.QUEUED -> 0.05f
            CaptureWorkState.PROCESSING -> 0.10f
            CaptureWorkState.SAVING -> 0.95f
            CaptureWorkState.PUBLISHED -> 1.00f
            CaptureWorkState.FAILED -> 0.00f
        }
        val finalProgress = (progressOverride ?: baseProgress).coerceAtLeast(current.progress)

        val updated = current.copy(
            state = state,
            progress = finalProgress,
            stateChangedNs = clockNs(),
            failureReason = if (state == CaptureWorkState.FAILED) {
                publishedOutputs?.reason ?: failureReason ?: "unspecified_failure"
            } else {
                publishedOutputs?.reason
            },
            publishedUri = if (state == CaptureWorkState.PUBLISHED) (publishedOutputs?.thumbnailUri ?: publishedUri) else null,
            jpegUri = publishedOutputs?.jpegUri,
            dngUri = publishedOutputs?.dngUri,
            thumbnailUri = publishedOutputs?.thumbnailUri,
            publicationResult = publishedOutputs?.publicationResult ?: if (state == CaptureWorkState.PUBLISHED) CapturePublicationResult.FULL_SUCCESS else if (state == CaptureWorkState.FAILED) CapturePublicationResult.FAILURE else null,
            publicationWarnings = publishedOutputs?.warnings.orEmpty()
        )
        snapshots[workId] = updated

        // Delete temporary preview file on terminal state
        if (state == CaptureWorkState.PUBLISHED || state == CaptureWorkState.FAILED) {
            current.temporaryPreviewPath?.let { path ->
                try {
                    val file = File(path)
                    if (file.exists()) file.delete()
                } catch (e: Exception) {
                    Log.w("BnCamProcessingQueue", "Failed to clean up temp preview $path: ${e.message}")
                }
            }
        }

        trimTerminalHistory()
        return updated
    }

    @Synchronized
    fun updateProgress(workId: Long, progress: Float): CaptureWorkSnapshot? {
        val current = snapshots[workId] ?: return null
        if (current.state != CaptureWorkState.PROCESSING) return current
        val newProgress = progress.coerceIn(current.progress, 0.90f)
        if (newProgress <= current.progress) return current

        val updated = current.copy(progress = newProgress, stateChangedNs = clockNs())
        snapshots[workId] = updated
        return updated
    }

    @Synchronized
    fun snapshot(workId: Long): CaptureWorkSnapshot? = snapshots[workId]

    @Synchronized
    fun snapshotForCaptureStartedNs(captureStartedNs: Long): CaptureWorkSnapshot? =
        snapshots.values.lastOrNull { it.captureStartedNs == captureStartedNs }

    @Synchronized
    fun attachTemporaryPreview(workId: Long, path: String): CaptureWorkSnapshot? {
        val current = snapshots[workId] ?: return null
        if (current.state == CaptureWorkState.PUBLISHED || current.state == CaptureWorkState.FAILED) {
            return null
        }
        if (current.temporaryPreviewPath == path) return current
        if (current.temporaryPreviewPath != null) return null

        val updated = current.copy(
            temporaryPreviewPath = path,
            stateChangedNs = clockNs()
        )
        snapshots[workId] = updated
        return updated
    }

    @Synchronized
    fun allSnapshots(): List<CaptureWorkSnapshot> = snapshots.values.toList()

    @Synchronized
    fun latestSnapshot(): CaptureWorkSnapshot? = snapshots.values.maxByOrNull { it.shotSequenceId }

    private fun trimTerminalHistory() {
        val terminalIds = snapshots.values
            .filter { it.state == CaptureWorkState.PUBLISHED || it.state == CaptureWorkState.FAILED }
            .map { it.workId }
        val removeCount = (terminalIds.size - retainedTerminalCount).coerceAtLeast(0)
        terminalIds.take(removeCount).forEach(snapshots::remove)
    }
}

/**
 * Bounded RAW processing owner. Admission covers the complete processing + save lifetime, so an
 * immutable RAW16 payload remains bounded even after it has moved from processing to publication.
 */
object CaptureProcessingQueue {
    internal const val MAX_IN_FLIGHT_RAW_WORK = 3

    private val admission = BoundedCaptureWorkAdmission(MAX_IN_FLIGHT_RAW_WORK)
    private val tracker = CaptureWorkStateTracker(clockNs = SystemClock::elapsedRealtimeNanos)
    private val jobs = Channel<ProcessingJob>(capacity = MAX_IN_FLIGHT_RAW_WORK)

    private val mutableSnapshotsFlow = MutableStateFlow<List<CaptureWorkSnapshot>>(emptyList())
    val snapshotsFlow: StateFlow<List<CaptureWorkSnapshot>> = mutableSnapshotsFlow.asStateFlow()

    private val mutableLatestSnapshotFlow = MutableStateFlow<CaptureWorkSnapshot?>(null)
    val latestSnapshotFlow: StateFlow<CaptureWorkSnapshot?> = mutableLatestSnapshotFlow.asStateFlow()

    private val scope =
        CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))

    init {
        scope.launch {
            for (job in jobs) {
                job.reservation.markProcessing()
                val progressTicker = launchProgressTicker(job.reservation)
                try {
                    job.block(job.reservation)
                    val state = tracker.snapshot(job.reservation.workId)?.state
                    if (state == CaptureWorkState.PROCESSING) {
                        job.reservation.fail("processing_completed_without_save_or_publication")
                    }
                } catch (failure: Throwable) {
                    Log.e(
                        "BnCamProcessingQueue",
                        "RAW processing failed workId=${job.reservation.workId} " +
                            "route=${job.reservation.route}: ${failure.message}",
                        failure
                    )
                    job.reservation.fail(
                        "${failure.javaClass.simpleName}:${failure.message ?: "no_message"}"
                    )
                } finally {
                    progressTicker.cancel()
                }
            }
        }
    }

    private fun launchProgressTicker(reservation: Reservation): Job {
        return scope.launch {
            val estimatedTotalMs = if (reservation.route.contains("Yuv")) 300L else 4200L
            val startTime = SystemClock.elapsedRealtime()
            while (true) {
                delay(100)
                val elapsed = SystemClock.elapsedRealtime() - startTime
                val fraction = (elapsed.toFloat() / estimatedTotalMs.toFloat()).coerceIn(0.0f, 1.0f)
                val estimatedProgress = 0.10f + (0.80f * fraction)
                reservation.updateProgress(estimatedProgress)
                if (estimatedProgress >= 0.90f) break
            }
        }
    }

    fun reserveAndSubmit(
        context: android.content.Context,
        route: String,
        captureStartedNs: Long,
        temporaryPreviewPath: String? = null,
        block: suspend (Reservation) -> Unit
    ): Reservation? {
        if (!admission.tryAcquire()) {
            Log.e(
                "BnCamProcessingQueue",
                "RAW work queue full route=$route inFlight=${admission.count()} " +
                    "maximum=$MAX_IN_FLIGHT_RAW_WORK"
            )
            temporaryPreviewPath?.let { path ->
                try {
                    val file = File(path)
                    if (file.exists()) file.delete()
                } catch (e: Exception) {
                    Log.w("BnCamProcessingQueue", "Failed to clean up temp preview on reservation reject $path: ${e.message}")
                }
            }
            return null
        }
        val snapshot = tracker.begin(route, captureStartedNs, temporaryPreviewPath)
        val reservation = Reservation(snapshot.workId, snapshot.shotSequenceId, route)
        if (!reservation.markSubmitted()) {
            admission.release()
            temporaryPreviewPath?.let { path ->
                try {
                    val file = File(path)
                    if (file.exists()) file.delete()
                } catch (e: Exception) {
                    Log.w("BnCamProcessingQueue", "Failed to clean up temp preview on markSubmitted reject $path: ${e.message}")
                }
            }
            return null
        }
        val offered = jobs.trySend(ProcessingJob(reservation, block))
        if (!offered.isSuccess) {
            reservation.fail("processing_channel_rejected")
            return null
        }
        Log.i(
            "BnCamProcessingQueue",
            "reserveAndSubmit workId=${snapshot.workId} seqId=${snapshot.shotSequenceId} route=$route state=QUEUED " +
                "inFlight=${admission.count()} maximum=$MAX_IN_FLIGHT_RAW_WORK"
        )
        emitStateUpdates()
        mutableSharedEvents.tryEmit(snapshot)
        CaptureProcessingService.ensureRunning(context.applicationContext)
        return reservation
    }

    fun tryReserve(
        route: String,
        captureStartedNs: Long,
        temporaryPreviewPath: String? = null
    ): Reservation? {
        if (!admission.tryAcquire()) {
            Log.e(
                "BnCamProcessingQueue",
                "RAW work queue full route=$route inFlight=${admission.count()} " +
                    "maximum=$MAX_IN_FLIGHT_RAW_WORK"
            )
            return null
        }
        val snapshot = tracker.begin(route, captureStartedNs, temporaryPreviewPath)
        Log.i(
            "BnCamProcessingQueue",
            "workId=${snapshot.workId} seqId=${snapshot.shotSequenceId} route=$route state=QUEUED " +
                "inFlight=${admission.count()} maximum=$MAX_IN_FLIGHT_RAW_WORK"
        )
        emitStateUpdates()
        mutableSharedEvents.tryEmit(snapshot)
        return Reservation(snapshot.workId, snapshot.shotSequenceId, route)
    }

    fun submit(
        context: android.content.Context,
        reservation: Reservation,
        block: suspend (Reservation) -> Unit
    ): Boolean {
        if (!reservation.markSubmitted()) return false
        val offered = jobs.trySend(ProcessingJob(reservation, block))
        if (offered.isSuccess) {
            CaptureProcessingService.ensureRunning(context.applicationContext)
            return true
        }
        reservation.fail("processing_channel_rejected")
        return false
    }

    fun snapshot(workId: Long): CaptureWorkSnapshot? = tracker.snapshot(workId)

    fun snapshotForCaptureStartedNs(captureStartedNs: Long): CaptureWorkSnapshot? =
        tracker.snapshotForCaptureStartedNs(captureStartedNs)

    fun snapshots(): List<CaptureWorkSnapshot> = tracker.allSnapshots()

    fun latestSnapshot(): CaptureWorkSnapshot? = tracker.latestSnapshot()

    /**
     * Attaches the lightweight UI preview only after shutter admission has already selected and
     * pinned its source frame(s). Thumbnail capture is deliberately not allowed to sit in front of
     * the camera transaction: it may take up to ~150 ms and previously widened the interval in
     * which a warm-buffer frame could be overwritten before MultiFrameRunner leased it.
     */
    fun attachTemporaryPreview(workId: Long, path: String): Boolean {
        if (path.isBlank()) return false
        val file = File(path)
        if (!file.exists() || file.length() <= 0L) return false
        val updated = tracker.attachTemporaryPreview(workId, path)
        if (updated == null) {
            runCatching { file.delete() }
            return false
        }
        emitStateUpdates()
        mutableSharedEvents.tryEmit(updated)
        return true
    }

    fun inFlightCount(): Int = admission.count()

    fun nonIdleCount(): Int = tracker.allSnapshots().count {
        it.state == CaptureWorkState.QUEUED ||
        it.state == CaptureWorkState.PROCESSING ||
        it.state == CaptureWorkState.SAVING
    }

    private fun emitStateUpdates() {
        mutableSnapshotsFlow.value = tracker.allSnapshots()
        mutableLatestSnapshotFlow.value = tracker.latestSnapshot()
    }

    val events: SharedFlow<CaptureWorkSnapshot> get() = mutableSharedEvents
    private val mutableSharedEvents = kotlinx.coroutines.flow.MutableSharedFlow<CaptureWorkSnapshot>(
        replay = 0,
        extraBufferCapacity = 32
    )

    class Reservation internal constructor(
        val workId: Long,
        val shotSequenceId: Long,
        val route: String
    ) {
        private val submitted = AtomicBoolean(false)
        private val terminal = AtomicBoolean(false)

        internal fun markSubmitted(): Boolean = submitted.compareAndSet(false, true)

        internal fun markProcessing() {
            transition(CaptureWorkState.PROCESSING)
        }

        fun updateProgress(progress: Float) {
            val updated = tracker.updateProgress(workId, progress)
            if (updated != null) {
                emitStateUpdates()
            }
        }

        fun markSaving() {
            transition(CaptureWorkState.SAVING)
        }

        fun markPublished(outputs: StringPublicationOutputs) {
            if (!terminal.compareAndSet(false, true)) return
            if (outputs.publicationResult == CapturePublicationResult.FAILURE) {
                transition(CaptureWorkState.FAILED, reason = outputs.reason ?: "Publication failed", publishedOutputs = outputs)
            } else {
                transition(CaptureWorkState.PUBLISHED, publishedOutputs = outputs)
            }
            admission.release()
        }

        fun markPublished(publishedUri: String? = null) {
            val outputs = StringPublicationOutputs(
                jpegUri = publishedUri,
                dngUri = null,
                thumbnailUri = publishedUri,
                publicationResult = CapturePublicationResult.FULL_SUCCESS
            )
            markPublished(outputs)
        }

        fun fail(reason: String) {
            if (!terminal.compareAndSet(false, true)) return
            transition(CaptureWorkState.FAILED, reason)
            admission.release()
        }

        private fun transition(
            state: CaptureWorkState,
            reason: String? = null,
            publishedUri: String? = null,
            publishedOutputs: StringPublicationOutputs? = null
        ) {
            val updated =
                tracker.transition(workId, state, failureReason = reason, publishedUri = publishedUri, publishedOutputs = publishedOutputs) ?: return
            emitStateUpdates()
            mutableSharedEvents.tryEmit(updated)
            Log.i(
                "BnCamProcessingQueue",
                "workId=$workId seqId=$shotSequenceId route=$route state=${updated.state} " +
                    "progress=${updated.progress} failure=${updated.failureReason ?: "none"} " +
                    "inFlight=${admission.count()}"
            )
        }
    }

    private data class ProcessingJob(
        val reservation: Reservation,
        val block: suspend (Reservation) -> Unit
    )
}
