package com.bncam.core.capture

data class RepeatedCaptureShotResult(
    val shotIndex: Int,
    val captureAttemptId: Long,
    val success: Boolean,
    val jpegProduced: Boolean,
    val debugProduced: Boolean,
    val stateReset: Boolean,
    val nextCaptureAllowed: Boolean,
    val timeToCaptureMs: Double,
    val timeToProcessMs: Double,
    val timeToSaveMs: Double
)

/** Deterministic debug runner used by device-side validation paths for five-shot sequences. */
object RepeatedCaptureValidator {
    suspend fun runFiveShots(capture: suspend (shotIndex: Int) -> RepeatedCaptureShotResult): List<RepeatedCaptureShotResult> =
        (1..5).map { shotIndex -> capture(shotIndex) }

    fun isValid(results: List<RepeatedCaptureShotResult>): Boolean =
        results.size == 5 && results.all {
            it.success && it.jpegProduced && it.stateReset && it.nextCaptureAllowed
        }
}
