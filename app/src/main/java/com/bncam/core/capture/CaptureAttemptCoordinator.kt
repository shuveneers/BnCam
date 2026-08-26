package com.bncam.core.capture

import java.util.concurrent.atomic.AtomicLong

enum class CaptureAttemptResult { SUCCESS, CAPTURE_FAILURE, PROCESSING_FAILURE, SAVE_FAILURE, REJECTED, CANCELLED }

data class CaptureAttemptContext(
    val cameraId: String,
    val physicalCameraId: String?,
    val lensName: String,
    val format: String,
    val bufferMode: String,
    val captureMode: String,
    val generationId: Int,
    val imageReaderMaxImages: Int,
    val ringBufferFrameCount: Int
)

data class CaptureAttemptSnapshot(
    val captureAttemptId: Long? = null,
    val captureInProgress: Boolean = false,
    val processingInProgress: Boolean = false,
    val saveInProgress: Boolean = false,
    val pendingCapture: Boolean = false,
    val inFlightImageCount: Int = 0,
    val stateReset: Boolean = true,
    val nextCaptureAllowed: Boolean = true
)

data class CaptureTimingBreakdown(
    val timeShutterToRequestMs: Double,
    val timeRequestToImageMs: Double,
    val timeImageToProcessingStartMs: Double,
    val timeProcessingMs: Double,
    val timeSaveMs: Double,
    val timeTotalShotMs: Double,
    val timeUntilNextCaptureAllowedMs: Double
) {
    fun asLogString(): String =
        "timeShutterToRequestMs=${fmt(timeShutterToRequestMs)} " +
            "timeRequestToImageMs=${fmt(timeRequestToImageMs)} " +
            "timeImageToProcessingStartMs=${fmt(timeImageToProcessingStartMs)} " +
            "timeProcessingMs=${fmt(timeProcessingMs)} timeSaveMs=${fmt(timeSaveMs)} " +
            "timeTotalShotMs=${fmt(timeTotalShotMs)} " +
            "timeUntilNextCaptureAllowedMs=${fmt(timeUntilNextCaptureAllowedMs)}"

    private fun fmt(value: Double): String = "%.3f".format(java.util.Locale.US, value)
}

data class CompletedCaptureAttempt(
    val captureAttemptId: Long,
    val result: CaptureAttemptResult,
    val reason: String,
    val timings: CaptureTimingBreakdown,
    val stateReset: Boolean,
    val nextCaptureAllowed: Boolean
)

interface CaptureStageListener {
    fun captureRequestSubmitted() = Unit
    fun captureResultReceived() = Unit
    fun sourceImageAcquired() = Unit
    fun nativeProcessingStart() = Unit
    fun nativeProcessingEnd(success: Boolean) = Unit
    fun saveStart() = Unit
    fun saveEnd(success: Boolean) = Unit

    companion object {
        val NONE: CaptureStageListener = object : CaptureStageListener {}
    }
}

/**
 * The single authority for capture-attempt state. Every terminal path must call [finish].
 * The class is Android-free so lifecycle reset behaviour can be covered by local tests.
 */
class CaptureAttemptCoordinator(
    private val clockNs: () -> Long = System::nanoTime,
    private val trace: (event: String, detail: String) -> Unit = { _, _ -> }
) : CaptureStageListener {
    private val nextAttemptId = AtomicLong(0L)
    private val attempts = linkedMapOf<Long, ActiveAttempt>()
    private var activeAcquisitionId: Long? = null
    private var snapshotValue = CaptureAttemptSnapshot()
    private var lastCompletedValue: CompletedCaptureAttempt? = null
    private var inFlightImageCount = 0
    private var pendingCapture = false

    private val workToAttemptMap = java.util.concurrent.ConcurrentHashMap<Long, Long>()

    val snapshot: CaptureAttemptSnapshot
        @Synchronized get() = snapshotValue

    val lastCompleted: CompletedCaptureAttempt?
        @Synchronized get() = lastCompletedValue

    /**
     * Returns a listener permanently bound to one capture attempt. Async workers may outlive the
     * acquisition slot and a later shutter press, so they must never report stages through the
     * mutable "current attempt" listener.
     */
    fun listenerFor(attemptId: Long): CaptureStageListener = object : CaptureStageListener {
        override fun captureRequestSubmitted() = captureRequestSubmitted(attemptId)
        override fun captureResultReceived() = captureResultReceived(attemptId)
        override fun sourceImageAcquired() = sourceImageAcquired(attemptId)
        override fun nativeProcessingStart() = nativeProcessingStart(attemptId)
        override fun nativeProcessingEnd(success: Boolean) = nativeProcessingEnd(attemptId, success)
        override fun saveStart() = saveStart(attemptId)
        override fun saveEnd(success: Boolean) = saveEnd(attemptId, success)
    }

    @Synchronized
    fun markSubmitted(attemptId: Long, workId: Long) {
        val attempt = attempts[attemptId]
        if (attempt == null) {
            trace("captureSubmissionMappingIgnored", "attemptId=$attemptId workId=$workId reason=attempt_not_found")
            return
        }
        workToAttemptMap[workId] = attemptId
        attempt.detachedWork = true
        if (attempt.nextCaptureAllowedNs == 0L) attempt.nextCaptureAllowedNs = clockNs()
        if (activeAcquisitionId == attemptId) activeAcquisitionId = null
        pendingCapture = false
        refreshSnapshot(preferredAttemptId = attemptId)
        trace(
            "captureSubmittedAsynchronously",
            "attemptId=$attemptId workId=$workId acquisitionReleased=true ${stateString()}"
        )
        trace("nextCaptureAllowed", "captureAttemptId=$attemptId allowed=true reason=async_submission_detached")
    }

    @Synchronized
    fun findByWorkId(workId: Long): Long? = workToAttemptMap[workId]

    @Synchronized
    fun ownsAcquisition(attemptId: Long): Boolean = activeAcquisitionId == attemptId

    @Synchronized
    fun begin(context: CaptureAttemptContext): Long? {
        val active = activeAcquisition()
        if (active != null) {
            pendingCapture = true
            refreshSnapshot(preferredAttemptId = active.id)
            trace("captureRejected", "reason=previous_acquisition_running ${stateString()}")
            return null
        }
        val id = nextAttemptId.incrementAndGet()
        val now = clockNs()
        attempts[id] = ActiveAttempt(id, context, now)
        activeAcquisitionId = id
        pendingCapture = false
        refreshSnapshot(preferredAttemptId = id)
        trace("captureStart", "captureAttemptId=$id ${contextString(context)} ${stateString()}")
        return id
    }

    @Synchronized
    override fun captureRequestSubmitted() {
        activeAcquisitionId?.let(::captureRequestSubmitted)
    }

    @Synchronized
    fun captureRequestSubmitted(attemptId: Long) = mark(attemptId, "captureRequestSubmitted") {
        if (it.requestNs == 0L) it.requestNs = clockNs()
    }

    @Synchronized
    override fun captureResultReceived() {
        activeAcquisitionId?.let(::captureResultReceived)
    }

    @Synchronized
    fun captureResultReceived(attemptId: Long) {
        val attempt = attempts[attemptId] ?: return
        attempt.captureResultCount++
        if (attempt.captureResultCount != 1) return
        attempt.resultNs = clockNs()
        trace(
            "captureResultReceived",
            "captureAttemptId=${attempt.id} ${contextString(attempt.context)} ${stateString()}"
        )
    }

    @Synchronized
    override fun sourceImageAcquired() {
        activeAcquisitionId?.let(::sourceImageAcquired)
    }

    @Synchronized
    fun sourceImageAcquired(attemptId: Long) = mark(attemptId, "imageAcquired") {
        if (it.imageNs == 0L) it.imageNs = clockNs()
    }

    @Synchronized
    override fun nativeProcessingStart() {
        activeAcquisitionId?.let(::nativeProcessingStart)
    }

    @Synchronized
    fun nativeProcessingStart(attemptId: Long) = mark(attemptId, "nativeProcessingStart") {
        if (it.processingStartNs == 0L) it.processingStartNs = clockNs()
        it.processingEnded = false
        refreshSnapshot(preferredAttemptId = attemptId)
    }

    @Synchronized
    override fun nativeProcessingEnd(success: Boolean) {
        activeAcquisitionId?.let { nativeProcessingEnd(it, success) }
    }

    @Synchronized
    fun nativeProcessingEnd(attemptId: Long, success: Boolean) = mark(attemptId, "nativeProcessingEnd") {
        it.processingEndNs = clockNs()
        it.processingSucceeded = success
        it.processingEnded = true
        refreshSnapshot(preferredAttemptId = attemptId)
    }

    @Synchronized
    override fun saveStart() {
        activeAcquisitionId?.let(::saveStart)
    }

    @Synchronized
    fun saveStart(attemptId: Long) = mark(attemptId, "saveStart") {
        if (it.saveStartNs == 0L) it.saveStartNs = clockNs()
        it.saveEnded = false
        refreshSnapshot(preferredAttemptId = attemptId)
    }

    @Synchronized
    override fun saveEnd(success: Boolean) {
        activeAcquisitionId?.let { saveEnd(it, success) }
    }

    @Synchronized
    fun saveEnd(attemptId: Long, success: Boolean) = mark(attemptId, "saveEnd") {
        it.saveEndNs = clockNs()
        it.saveSucceeded = success
        it.saveEnded = true
        refreshSnapshot(preferredAttemptId = attemptId)
    }

    @Synchronized
    fun terminalResult(attemptId: Long, outputProduced: Boolean): CaptureAttemptResult {
        val attempt = attempts[attemptId] ?: return CaptureAttemptResult.CAPTURE_FAILURE
        return when {
            outputProduced -> CaptureAttemptResult.SUCCESS
            attempt.processingSucceeded == false -> CaptureAttemptResult.PROCESSING_FAILURE
            attempt.saveSucceeded == false -> CaptureAttemptResult.SAVE_FAILURE
            else -> CaptureAttemptResult.CAPTURE_FAILURE
        }
    }

    @Synchronized
    fun terminalResult(outputProduced: Boolean): CaptureAttemptResult {
        val attemptId = activeAcquisitionId ?: attempts.keys.lastOrNull()
            ?: return CaptureAttemptResult.CAPTURE_FAILURE
        return terminalResult(attemptId, outputProduced)
    }

    @Synchronized
    fun imageAvailable() {
        activeAcquisition()?.let {
            it.imageAvailableCount++
            if (it.imageAvailableCount <= 3 || it.imageAvailableCount % 30 == 0) {
                trace("imageAvailable", "captureAttemptId=${it.id} count=${it.imageAvailableCount} ${contextString(it.context)} ${stateString()}")
            }
        }
    }

    @Synchronized
    fun imageReaderAcquired() {
        val attempt = activeAcquisition() ?: return
        attempt.readerAcquireCount++
        inFlightImageCount++
        refreshSnapshot(preferredAttemptId = attempt.id)
        if (attempt.readerAcquireCount <= 3 || attempt.readerAcquireCount % 30 == 0) {
            trace("imageAcquired", "captureAttemptId=${attempt.id} reader=true count=${attempt.readerAcquireCount} ${contextString(attempt.context)} ${stateString()}")
        }
    }

    @Synchronized
    fun imageReaderClosed() {
        inFlightImageCount = (inFlightImageCount - 1).coerceAtLeast(0)
        val attempt = activeAcquisition()
        refreshSnapshot(preferredAttemptId = attempt?.id)
        if (attempt == null || attempt.readerAcquireCount <= 3 || attempt.readerAcquireCount % 30 == 0) {
            trace("imageClosed", "captureAttemptId=${attempt?.id ?: snapshotValue.captureAttemptId ?: "none"} ${stateString()}")
        }
    }

    @Synchronized
    fun finish(attemptId: Long, result: CaptureAttemptResult, reason: String): CaptureTimingBreakdown? {
        val attempt = attempts[attemptId]
        if (attempt == null) {
            trace(
                "captureFinalizationIgnored",
                "captureAttemptId=$attemptId active=$activeAcquisitionId result=$result reason=$reason"
            )
            return null
        }
        val finishedNs = clockNs()
        if (attempt.nextCaptureAllowedNs == 0L) attempt.nextCaptureAllowedNs = finishedNs
        val timings = attempt.timings(finishedNs)
        trace(
            if (result == CaptureAttemptResult.SUCCESS) "captureFinished" else "captureFailed",
            "captureAttemptId=$attemptId result=$result reason=$reason ${timings.asLogString()} ${stateString()}"
        )
        if (activeAcquisitionId == attemptId) activeAcquisitionId = null
        attempts.remove(attemptId)
        workToAttemptMap.entries.removeIf { it.value == attemptId }
        pendingCapture = false
        lastCompletedValue = CompletedCaptureAttempt(
            captureAttemptId = attemptId,
            result = result,
            reason = reason,
            timings = timings,
            stateReset = true,
            nextCaptureAllowed = true
        )
        refreshSnapshot(preferredAttemptId = attemptId)
        trace("captureStateReset", "captureAttemptId=$attemptId attemptReleased=true ${stateString()}")
        trace(
            "nextCaptureAllowed",
            "captureAttemptId=$attemptId allowed=${snapshotValue.nextCaptureAllowed}"
        )
        return timings
    }

    @Synchronized
    fun forceReset(reason: String) {
        val ids = attempts.keys.toList()
        attempts.clear()
        activeAcquisitionId = null
        workToAttemptMap.clear()
        inFlightImageCount = 0
        pendingCapture = false
        refreshSnapshot(preferredAttemptId = null)
        trace(
            "captureStateReset",
            "captureAttemptId=${ids.joinToString(prefix = "[", postfix = "]")} forced=true reason=$reason ${stateString()}"
        )
    }

    private fun mark(attemptId: Long, event: String, update: (ActiveAttempt) -> Unit) {
        val attempt = attempts[attemptId] ?: return
        update(attempt)
        trace(event, "captureAttemptId=${attempt.id} ${contextString(attempt.context)} ${stateString()}")
    }

    private fun activeAcquisition(): ActiveAttempt? = activeAcquisitionId?.let(attempts::get)

    private fun refreshSnapshot(preferredAttemptId: Long?) {
        val active = activeAcquisition()
        val outstandingDetached = attempts.values.any { it.detachedWork }
        val processing = attempts.values.any { attempt ->
            attempt.processingStartNs > 0L && !attempt.processingEnded ||
                (attempt.detachedWork && attempt.processingStartNs == 0L)
        }
        val saving = attempts.values.any { it.saveStartNs > 0L && !it.saveEnded }
        val shownId = active?.id
            ?: preferredAttemptId?.takeIf { attempts.containsKey(it) }
            ?: attempts.keys.lastOrNull()
            ?: lastCompletedValue?.captureAttemptId
        snapshotValue = CaptureAttemptSnapshot(
            captureAttemptId = shownId,
            captureInProgress = active != null,
            processingInProgress = processing || (outstandingDetached && !saving),
            saveInProgress = saving,
            pendingCapture = pendingCapture,
            inFlightImageCount = inFlightImageCount,
            stateReset = attempts.isEmpty(),
            nextCaptureAllowed = active == null
        )
    }

    private fun contextString(context: CaptureAttemptContext): String =
        "format=${context.format} cameraId=${context.cameraId} physicalCameraId=${context.physicalCameraId ?: "none"} " +
            "lens=${context.lensName} bufferMode=${context.bufferMode} captureMode=${context.captureMode} " +
            "generationId=${context.generationId} imageReaderMaxImages=${context.imageReaderMaxImages} " +
            "ringBufferFrameCount=${context.ringBufferFrameCount}"

    private fun stateString(): String =
        "captureInProgress=${snapshotValue.captureInProgress} " +
            "processingInProgress=${snapshotValue.processingInProgress} saveInProgress=${snapshotValue.saveInProgress} " +
            "pendingCapture=${snapshotValue.pendingCapture} inFlightImageCount=${snapshotValue.inFlightImageCount} " +
            "activeAcquisitionId=${activeAcquisitionId ?: "none"} outstandingAttempts=${attempts.size}"

    private data class ActiveAttempt(
        val id: Long,
        val context: CaptureAttemptContext,
        val shutterNs: Long,
        var requestNs: Long = 0L,
        var resultNs: Long = 0L,
        var imageNs: Long = 0L,
        var processingStartNs: Long = 0L,
        var processingEndNs: Long = 0L,
        var saveStartNs: Long = 0L,
        var saveEndNs: Long = 0L,
        var nextCaptureAllowedNs: Long = 0L,
        var processingSucceeded: Boolean? = null,
        var saveSucceeded: Boolean? = null,
        var imageAvailableCount: Int = 0,
        var readerAcquireCount: Int = 0,
        var captureResultCount: Int = 0,
        var detachedWork: Boolean = false,
        var processingEnded: Boolean = false,
        var saveEnded: Boolean = false
    ) {
        fun timings(finishedNs: Long): CaptureTimingBreakdown {
            val request = requestNs.takeIf { it > 0L } ?: shutterNs
            val image = imageNs.takeIf { it > 0L } ?: resultNs.takeIf { it > 0L } ?: request
            val processingStart = processingStartNs.takeIf { it > 0L } ?: image
            val processingEnd = processingEndNs.takeIf { it > 0L } ?: processingStart
            val saveStart = saveStartNs.takeIf { it > 0L } ?: processingEnd
            val saveEnd = saveEndNs.takeIf { it > 0L } ?: saveStart
            val nextAllowed = nextCaptureAllowedNs.takeIf { it > 0L } ?: finishedNs
            fun ms(start: Long, end: Long) = (end - start).coerceAtLeast(0L) / 1_000_000.0
            return CaptureTimingBreakdown(
                timeShutterToRequestMs = ms(shutterNs, request),
                timeRequestToImageMs = ms(request, image),
                timeImageToProcessingStartMs = ms(image, processingStart),
                timeProcessingMs = ms(processingStart, processingEnd),
                timeSaveMs = ms(saveStart, saveEnd),
                timeTotalShotMs = ms(shutterNs, finishedNs),
                timeUntilNextCaptureAllowedMs = ms(shutterNs, nextAllowed)
            )
        }
    }
}
