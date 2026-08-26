package com.bncam.core.output

import android.net.Uri

sealed interface CaptureSubmissionResult {
    data class Submitted(
        val attemptId: String,
        val workId: Long,
        val temporaryPreviewPath: String?
    ) : CaptureSubmissionResult

    data class CompletedSynchronously(
        val artifacts: CaptureArtifacts
    ) : CaptureSubmissionResult {
        val outputUri: Uri?
            get() = artifacts.thumbnailUri
    }

    data class Rejected(
        val reason: String
    ) : CaptureSubmissionResult
}
