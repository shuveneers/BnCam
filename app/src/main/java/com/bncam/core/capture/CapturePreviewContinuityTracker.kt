package com.bncam.core.capture

/**
 * Measures whether displayed preview frames continue while a still-capture attempt is alive.
 * The tracker is intentionally independent from Camera2/Vulkan so its timing semantics are unit
 * testable and diagnostics never become capture authority.
 */
data class CapturePreviewContinuitySnapshot(
    val captureAttemptId: Long,
    val previewFramesDuringCapture: Long,
    val previewLongestFrameGapMs: Double,
    val repeatingRequestActiveDuringCapture: Boolean,
    val cameraSessionReconfigured: Boolean,
    val captureOutstandingImageCount: Int,
    val previewOutstandingImageCount: Int?,
    val captureGpuWaitMs: Double?,
    val previewGpuWaitMs: Double?
) {
    fun diagnosticSummary(): String =
        "previewFramesDuringCapture=$previewFramesDuringCapture " +
            "previewLongestFrameGapMs=${format(previewLongestFrameGapMs)} " +
            "repeatingRequestActiveDuringCapture=$repeatingRequestActiveDuringCapture " +
            "captureOutstandingImageCount=$captureOutstandingImageCount " +
            "previewOutstandingImageCount=${previewOutstandingImageCount ?: "UNAVAILABLE"} " +
            "captureGpuWaitMs=${captureGpuWaitMs?.let(::format) ?: "UNAVAILABLE"} " +
            "previewGpuWaitMs=${previewGpuWaitMs?.let(::format) ?: "UNAVAILABLE"} " +
            "cameraSessionReconfigured=$cameraSessionReconfigured"

    private fun format(value: Double): String =
        String.format(java.util.Locale.US, "%.3f", value)
}

class CapturePreviewContinuityTracker(
    private val clockNs: () -> Long = System::nanoTime
) {
    private data class Active(
        val attemptId: Long,
        val startedNs: Long,
        val sessionEpochAtStart: Long,
        var lastPreviewNs: Long,
        var previewFrames: Long = 0L,
        var longestGapNs: Long = 0L,
        var repeatingRequestStayedActive: Boolean
    )

    private var active: Active? = null

    @Synchronized
    fun begin(
        attemptId: Long,
        sessionEpoch: Long,
        repeatingRequestActive: Boolean
    ) {
        val now = clockNs()
        active = Active(
            attemptId = attemptId,
            startedNs = now,
            sessionEpochAtStart = sessionEpoch,
            lastPreviewNs = now,
            repeatingRequestStayedActive = repeatingRequestActive
        )
    }

    @Synchronized
    fun previewFrame() {
        val state = active ?: return
        val now = clockNs()
        val gap = (now - state.lastPreviewNs).coerceAtLeast(0L)
        state.longestGapNs = maxOf(state.longestGapNs, gap)
        state.lastPreviewNs = now
        state.previewFrames++
    }

    @Synchronized
    fun repeatingRequestState(activeNow: Boolean) {
        val state = active ?: return
        if (!activeNow) state.repeatingRequestStayedActive = false
    }

    @Synchronized
    fun finish(
        attemptId: Long,
        sessionEpoch: Long,
        captureOutstandingImageCount: Int,
        previewOutstandingImageCount: Int? = null,
        captureGpuWaitMs: Double? = null,
        previewGpuWaitMs: Double? = null
    ): CapturePreviewContinuitySnapshot? {
        val state = active ?: return null
        if (state.attemptId != attemptId) return null
        val now = clockNs()
        state.longestGapNs = maxOf(
            state.longestGapNs,
            (now - state.lastPreviewNs).coerceAtLeast(0L)
        )
        active = null
        return CapturePreviewContinuitySnapshot(
            captureAttemptId = attemptId,
            previewFramesDuringCapture = state.previewFrames,
            previewLongestFrameGapMs = state.longestGapNs / 1_000_000.0,
            repeatingRequestActiveDuringCapture = state.repeatingRequestStayedActive,
            cameraSessionReconfigured = sessionEpoch != state.sessionEpochAtStart,
            captureOutstandingImageCount = captureOutstandingImageCount.coerceAtLeast(0),
            previewOutstandingImageCount = previewOutstandingImageCount,
            captureGpuWaitMs = captureGpuWaitMs,
            previewGpuWaitMs = previewGpuWaitMs
        )
    }

    @Synchronized
    fun reset() {
        active = null
    }
}
