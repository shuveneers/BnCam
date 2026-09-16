package com.bncam.core.capture

enum class BlackLevelLockRequestStage {
    SESSION,
    REPEATING_REQUEST,
    STILL_CAPTURE
}

enum class BlackLevelLockRequestState {
    REQUESTED,
    NOT_REQUESTED,
    UNSUPPORTED,
    APPLY_FAILED
}

enum class BlackLevelLockResultState {
    REPORTED_TRUE,
    REPORTED_FALSE,
    NOT_REPORTED
}

enum class BlackLevelLockFrameCoverage {
    WARM_REPEATING_FRAME,
    NEW_CAPTURE_REQUEST_FRAME,
    NOT_REQUESTED_FRAME,
    UNKNOWN
}

data class BlackLevelLockResolution(
    val requestState: BlackLevelLockRequestState,
    val resultState: BlackLevelLockResultState,
    val coverage: BlackLevelLockFrameCoverage,
    val actuallyLocked: Boolean,
    val reason: String
)

/**
 * Pure BLACK_LEVEL_LOCK truth resolver. It deliberately separates what BnCam requested from what
 * Camera2 reported for the exact frame. A warm-buffer frame is never retroactively considered
 * locked by a later shutter request: coverage is derived only from that frame's own request.
 */
object BlackLevelLockPolicy {
    const val SUBMISSION_REPEATING = "REPEATING"
    const val SUBMISSION_ONE_SHOT = "ONE_SHOT"
    const val SUBMISSION_BURST = "BURST"

    fun shouldRequest(
        stage: BlackLevelLockRequestStage,
        repeatingIntentIsStillOrZsl: Boolean
    ): Boolean = when (stage) {
        BlackLevelLockRequestStage.STILL_CAPTURE -> true
        BlackLevelLockRequestStage.REPEATING_REQUEST -> repeatingIntentIsStillOrZsl
        BlackLevelLockRequestStage.SESSION -> false
    }

    fun resolve(
        requestState: BlackLevelLockRequestState,
        resultReportedLocked: Boolean?,
        submissionType: String?
    ): BlackLevelLockResolution {
        val resultState = when (resultReportedLocked) {
            true -> BlackLevelLockResultState.REPORTED_TRUE
            false -> BlackLevelLockResultState.REPORTED_FALSE
            null -> BlackLevelLockResultState.NOT_REPORTED
        }
        val requested = requestState == BlackLevelLockRequestState.REQUESTED
        val coverage = when {
            !requested -> BlackLevelLockFrameCoverage.NOT_REQUESTED_FRAME
            submissionType == SUBMISSION_REPEATING -> BlackLevelLockFrameCoverage.WARM_REPEATING_FRAME
            submissionType == SUBMISSION_ONE_SHOT || submissionType == SUBMISSION_BURST ->
                BlackLevelLockFrameCoverage.NEW_CAPTURE_REQUEST_FRAME
            else -> BlackLevelLockFrameCoverage.UNKNOWN
        }
        val actuallyLocked = requested && resultReportedLocked == true
        val reason = when {
            requestState == BlackLevelLockRequestState.UNSUPPORTED ->
                "BLACK_LEVEL_LOCK is not a valid request key for this Camera2 request"
            requestState == BlackLevelLockRequestState.APPLY_FAILED ->
                "BLACK_LEVEL_LOCK request could not be applied to the builder"
            requestState == BlackLevelLockRequestState.NOT_REQUESTED ->
                "BLACK_LEVEL_LOCK was not requested for this frame"
            resultReportedLocked == true ->
                "Camera2 reports BLACK_LEVEL_LOCK=true for this exact frame"
            resultReportedLocked == false ->
                "BnCam requested BLACK_LEVEL_LOCK but Camera2 reports false for this exact frame"
            else ->
                "BnCam requested BLACK_LEVEL_LOCK but Camera2 did not report result state"
        }
        return BlackLevelLockResolution(
            requestState = requestState,
            resultState = resultState,
            coverage = coverage,
            actuallyLocked = actuallyLocked,
            reason = reason
        )
    }
}
