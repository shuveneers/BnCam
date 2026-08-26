package com.bncam.core.output

import android.net.Uri
import com.bncam.core.capture.OutputPolicy

enum class CapturePublicationResult {
    FULL_SUCCESS,
    PARTIAL_SUCCESS,
    FAILURE
}

enum class CaptureWarningCode {
    JPEG_PUBLICATION_FAILED,
    DNG_PUBLICATION_FAILED,
    REQUIRED_OUTPUT_MISSING,
    THUMBNAIL_UNAVAILABLE,
    CAPTURE_FALLBACK
}

enum class CaptureWarningSeverity { INFO, WARNING, ERROR }

data class CaptureWarning(
    val code: CaptureWarningCode,
    val message: String,
    val severity: CaptureWarningSeverity = CaptureWarningSeverity.WARNING,
    val reason: String? = null
)

data class CaptureArtifacts(
    val jpegUri: Uri? = null,
    val dngUri: Uri? = null,
    val smartAssetUri: Uri? = null,
    val thumbnailUri: Uri? = null,
    val publicationResult: CapturePublicationResult,
    val warnings: List<CaptureWarning> = emptyList(),
    val reason: String? = null
) {
    init {
        require(thumbnailUri == null || thumbnailUri == jpegUri || thumbnailUri == smartAssetUri) {
            "Thumbnail URI must point to a bitmap-compatible JPEG or smart preview artifact."
        }
        require(dngUri == null || dngUri != thumbnailUri) {
            "A DNG URI must never be used as the bitmap thumbnail URI."
        }
    }
}

data class StringCaptureArtifacts(
    val jpegUri: String? = null,
    val dngUri: String? = null,
    val smartAssetUri: String? = null,
    val thumbnailUri: String? = null,
    val publicationResult: CapturePublicationResult,
    val warnings: List<CaptureWarning> = emptyList(),
    val reason: String? = null
) {
    init {
        require(thumbnailUri == null || thumbnailUri == jpegUri || thumbnailUri == smartAssetUri) {
            "Thumbnail URI must point to a bitmap-compatible JPEG or smart preview artifact."
        }
        require(dngUri == null || dngUri != thumbnailUri) {
            "A DNG URI must never be used as the bitmap thumbnail URI."
        }
    }
}

typealias CapturePublishedOutputs = CaptureArtifacts
typealias StringPublicationOutputs = StringCaptureArtifacts

object PublicationPolicyResolver {

    fun resolveStrings(
        outputPolicy: OutputPolicy,
        jpegSucceeded: Boolean,
        jpegUri: String?,
        dngSucceeded: Boolean,
        dngUri: String?,
        jpegFailureReason: String? = null,
        dngFailureReason: String? = null
    ): StringCaptureArtifacts {
        val validJpegUri = if (jpegSucceeded && !jpegUri.isNullOrBlank()) jpegUri else null
        val validDngUri = if (dngSucceeded && !dngUri.isNullOrBlank()) dngUri else null

        return when (outputPolicy) {
            OutputPolicy.JPEG -> {
                if (validJpegUri != null) {
                    StringCaptureArtifacts(
                        jpegUri = validJpegUri,
                        thumbnailUri = validJpegUri,
                        publicationResult = CapturePublicationResult.FULL_SUCCESS
                    )
                } else {
                    val reason = jpegFailureReason ?: "JPEG output failed or empty"
                    StringCaptureArtifacts(
                        publicationResult = CapturePublicationResult.FAILURE,
                        warnings = listOf(
                            warning(CaptureWarningCode.JPEG_PUBLICATION_FAILED, reason, true)
                        ),
                        reason = reason
                    )
                }
            }
            OutputPolicy.JPEG_PLUS_RAW -> {
                when {
                    validJpegUri != null && validDngUri != null -> {
                        StringCaptureArtifacts(
                            jpegUri = validJpegUri,
                            dngUri = validDngUri,
                            thumbnailUri = validJpegUri,
                            publicationResult = CapturePublicationResult.FULL_SUCCESS
                        )
                    }
                    validJpegUri != null -> {
                        val reason = dngFailureReason ?: "DNG save failed"
                        StringCaptureArtifacts(
                            jpegUri = validJpegUri,
                            thumbnailUri = validJpegUri,
                            publicationResult = CapturePublicationResult.PARTIAL_SUCCESS,
                            warnings = listOf(
                                warning(CaptureWarningCode.DNG_PUBLICATION_FAILED, reason)
                            ),
                            reason = reason
                        )
                    }
                    validDngUri != null -> {
                        val reason = jpegFailureReason ?: "JPEG save failed; DNG preserved"
                        StringCaptureArtifacts(
                            dngUri = validDngUri,
                            publicationResult = CapturePublicationResult.PARTIAL_SUCCESS,
                            warnings = listOf(
                                warning(CaptureWarningCode.JPEG_PUBLICATION_FAILED, reason),
                                warning(
                                    CaptureWarningCode.THUMBNAIL_UNAVAILABLE,
                                    "No bitmap-compatible thumbnail was published."
                                )
                            ),
                            reason = reason
                        )
                    }
                    else -> {
                        val reason = "Both JPEG and DNG output failed"
                        StringCaptureArtifacts(
                            publicationResult = CapturePublicationResult.FAILURE,
                            warnings = listOf(
                                warning(
                                    CaptureWarningCode.REQUIRED_OUTPUT_MISSING,
                                    reason,
                                    true
                                )
                            ),
                            reason = reason
                        )
                    }
                }
            }
            OutputPolicy.RAW_ONLY -> {
                if (validDngUri != null) {
                    StringCaptureArtifacts(
                        dngUri = validDngUri,
                        publicationResult = CapturePublicationResult.FULL_SUCCESS,
                        warnings = listOf(
                            warning(
                                CaptureWarningCode.THUMBNAIL_UNAVAILABLE,
                                "RAW-only output has no bitmap thumbnail.",
                                isError = false,
                                severity = CaptureWarningSeverity.INFO
                            )
                        )
                    )
                } else {
                    val reason = dngFailureReason ?: "DNG output failed or empty"
                    StringCaptureArtifacts(
                        publicationResult = CapturePublicationResult.FAILURE,
                        warnings = listOf(
                            warning(CaptureWarningCode.DNG_PUBLICATION_FAILED, reason, true)
                        ),
                        reason = reason
                    )
                }
            }
        }
    }

    fun resolveUris(
        outputPolicy: OutputPolicy,
        jpegSucceeded: Boolean,
        jpegUri: Uri?,
        dngSucceeded: Boolean,
        dngUri: Uri?,
        jpegFailureReason: String? = null,
        dngFailureReason: String? = null
    ): CaptureArtifacts {
        val resolved = resolveStrings(
            outputPolicy = outputPolicy,
            jpegSucceeded = jpegSucceeded,
            jpegUri = jpegUri?.toString(),
            dngSucceeded = dngSucceeded,
            dngUri = dngUri?.toString(),
            jpegFailureReason = jpegFailureReason,
            dngFailureReason = dngFailureReason
        )
        return CaptureArtifacts(
            jpegUri = resolved.jpegUri?.let(Uri::parse),
            dngUri = resolved.dngUri?.let(Uri::parse),
            smartAssetUri = resolved.smartAssetUri?.let(Uri::parse),
            thumbnailUri = resolved.thumbnailUri?.let(Uri::parse),
            publicationResult = resolved.publicationResult,
            warnings = resolved.warnings,
            reason = resolved.reason
        )
    }

    private fun warning(
        code: CaptureWarningCode,
        reason: String,
        isError: Boolean = false,
        severity: CaptureWarningSeverity =
            if (isError) CaptureWarningSeverity.ERROR else CaptureWarningSeverity.WARNING
    ) = CaptureWarning(
        code = code,
        message = reason,
        severity = severity,
        reason = reason
    )
}
