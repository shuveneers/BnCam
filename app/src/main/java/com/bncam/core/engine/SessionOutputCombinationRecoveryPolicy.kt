package com.bncam.core.engine

/**
 * Deterministic classification for a Camera2 session configure failure.
 *
 * This is intentionally pure policy. Runtime Camera2 inspection lives in CameraSessionPreflight;
 * BnCameraManager consumes only this classification and keeps lifecycle ownership unchanged.
 */
enum class SessionOutputFailureKind {
    NONE,
    OPTIONAL_CUSTOM_RAW_OUTPUT,
    VENDOR_SESSION_MODE,
    PREVIEW_OUTPUT_REJECTED,
    CAPTURE_OUTPUT_REJECTED,
    CORE_OUTPUT_COMBINATION_REJECTED,
    RUNTIME_REJECTED_DESPITE_PREFLIGHT,
    PREFLIGHT_UNAVAILABLE,
    FIRST_PRODUCER_FRAME_TIMEOUT,
    UNKNOWN
}

enum class SessionOutputRecoveryAction {
    NONE,
    RETRY_WITHOUT_OPTIONAL_CUSTOM_RAW,
    ADVANCE_CAPTURE_GEOMETRY,
    DEFER_TO_VENDOR_SESSION_AUTHORITY,
    FAIL_WITHOUT_GEOMETRY_RETRY
}

data class SessionOutputFailureDiagnosis(
    val kind: SessionOutputFailureKind,
    val reason: String,
    val previewIndividuallySupported: Boolean? = null,
    val captureIndividuallySupported: Boolean? = null,
    val combinedSupported: Boolean? = null
) {
    val recoveryAction: SessionOutputRecoveryAction
        get() = SessionOutputCombinationRecoveryPolicy.actionFor(kind)

    val geometryRetryUseful: Boolean
        get() = recoveryAction == SessionOutputRecoveryAction.ADVANCE_CAPTURE_GEOMETRY
}

object SessionOutputCombinationRecoveryPolicy {
    fun classifyRegularSessionPreflight(
        previewSupported: Boolean,
        captureSupported: Boolean,
        combinedSupported: Boolean
    ): SessionOutputFailureKind = when {
        combinedSupported -> SessionOutputFailureKind.RUNTIME_REJECTED_DESPITE_PREFLIGHT
        !previewSupported -> SessionOutputFailureKind.PREVIEW_OUTPUT_REJECTED
        !captureSupported -> SessionOutputFailureKind.CAPTURE_OUTPUT_REJECTED
        else -> SessionOutputFailureKind.CORE_OUTPUT_COMBINATION_REJECTED
    }

    fun actionFor(kind: SessionOutputFailureKind): SessionOutputRecoveryAction = when (kind) {
        SessionOutputFailureKind.NONE -> SessionOutputRecoveryAction.NONE
        SessionOutputFailureKind.OPTIONAL_CUSTOM_RAW_OUTPUT ->
            SessionOutputRecoveryAction.RETRY_WITHOUT_OPTIONAL_CUSTOM_RAW
        SessionOutputFailureKind.VENDOR_SESSION_MODE ->
            SessionOutputRecoveryAction.DEFER_TO_VENDOR_SESSION_AUTHORITY
        SessionOutputFailureKind.CAPTURE_OUTPUT_REJECTED,
        SessionOutputFailureKind.CORE_OUTPUT_COMBINATION_REJECTED,
        SessionOutputFailureKind.RUNTIME_REJECTED_DESPITE_PREFLIGHT,
        SessionOutputFailureKind.PREFLIGHT_UNAVAILABLE,
        SessionOutputFailureKind.FIRST_PRODUCER_FRAME_TIMEOUT,
        SessionOutputFailureKind.UNKNOWN ->
            SessionOutputRecoveryAction.ADVANCE_CAPTURE_GEOMETRY
        SessionOutputFailureKind.PREVIEW_OUTPUT_REJECTED ->
            SessionOutputRecoveryAction.FAIL_WITHOUT_GEOMETRY_RETRY
    }
}
