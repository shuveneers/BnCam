package com.bncam.core.engine

/**
 * Immutable Photo-side selection that feeds the final [ResolvedStreamConfiguration].
 *
 * Format and resolution are resolved before this layer. This layer no longer reads a persisted
 * Auto / Validated / Manual mode and no candidate may replace the already-resolved PRIMARY_BUFFER.
 * Its only policy job is applying bounded transient recovery to the resolved Photo stream.
 */
data class ResolvedPhotoStreamSelection(
    val requestedLensId: String,
    val requestedFormatCode: Int,
    val effectiveFormatCode: Int,
    val formatSelectionKind: CameraPhotoFormatSelectionKind,
    val formatFallbackApplied: Boolean,
    val resolutionSelectionKind: CameraPhotoResolutionSelectionKind,
    val specificRawSizeIndex: Int?,
    val resolutionFixReferenceFormatCode: Int?,
    val resolutionFixApplied: Boolean,
    val primaryRoleKind: StreamRoleKind,
    val primaryExtent: StreamExtent,
    val previewExtent: StreamExtent?,
    val authorityFingerprint: String,
    val runtimeFallbackTier: StreamRuntimeFallbackTier = StreamRuntimeFallbackTier.NONE,
    val reason: String
) {
    init {
        require(effectiveFormatCode >= 0) { "effectiveFormatCode must be non-negative" }
        require(authorityFingerprint.isNotBlank()) { "authorityFingerprint must not be blank" }
    }

    val resolutionOverrideApplied: Boolean
        get() = resolutionSelectionKind == CameraPhotoResolutionSelectionKind.SPECIFIC_RAW_SIZE ||
            resolutionSelectionKind == CameraPhotoResolutionSelectionKind.RESOLUTION_FIX_REMAP

    val signature: String = buildString {
        append(authorityFingerprint)
        append(":RS=").append(resolutionSelectionKind.name)
        append(":RF=").append(if (resolutionFixApplied) '1' else '0')
        append(":EXT=").append(primaryExtent.width).append('x').append(primaryExtent.height)
        previewExtent?.let { append(":P=").append(it.width).append('x').append(it.height) }
        append(":T=").append(runtimeFallbackTier.name)
    }

    fun toPrimaryStreamContract(): ResolvedPrimaryStreamContract =
        ResolvedPrimaryStreamContract(
            roleId = BnCamStreamRoleIds.PRIMARY_BUFFER,
            kind = primaryRoleKind,
            requestedFormatCode = requestedFormatCode,
            effectiveFormatCode = effectiveFormatCode,
            extent = primaryExtent,
            formatSelectionKind = formatSelectionKind,
            formatFallbackApplied = formatFallbackApplied,
            resolutionSelectionKind = resolutionSelectionKind,
            specificRawSizeIndex = specificRawSizeIndex,
            resolutionFixReferenceFormatCode = resolutionFixReferenceFormatCode,
            resolutionFixApplied = resolutionFixApplied,
            runtimeFallbackTier = runtimeFallbackTier,
            evidence = reason
        )
}

/** Stable key for the user/profile-owned Photo stream request, independent of recovery tier. */
object PhotoStreamAuthorityFingerprint {
    fun create(
        formatResolution: ResolvedCameraPhotoFormat,
        resolutionResolution: ResolvedCameraPhotoResolution
    ): String = buildString {
        append("REQ=").append(formatResolution.requestedFormatCode)
        append(":POL=").append(formatResolution.requestPolicy.name)
        append(":EFF=").append(formatResolution.effectiveFormatCode ?: "NONE")
        append(":FMT=").append(formatResolution.selectionKind.name)
        append(":RAW_INDEX=").append(resolutionResolution.specificRawSizeIndex ?: "AUTO")
        append(":RES_FIX=").append(resolutionResolution.resolutionFixReferenceFormatCode ?: "OFF")
    }
}

/**
 * Final Photo stream selection after capability, format and resolution policy.
 *
 * Authority order is strict:
 * CameraCapabilityInventory -> Photo format policy -> Photo resolution policy -> bounded recovery
 * -> final typed session graph. FPS, operation mode and physical routing remain separate decisions.
 */
object PhotoStreamConfigurationResolver {
    fun resolvePhoto(
        requestedLensId: String,
        formatResolution: ResolvedCameraPhotoFormat,
        requestedResolution: ResolvedCameraPhotoResolution,
        autoResolution: ResolvedCameraPhotoResolution,
        configuredPreviewExtent: StreamExtent?,
        runtimeFallback: StreamRuntimeFallbackOverride? = null
    ): ResolvedPhotoStreamSelection {
        val effectiveFormatCode = requireNotNull(formatResolution.effectiveFormatCode) {
            "Photo stream selection requires a resolved PRIMARY_BUFFER format"
        }
        require(requestedResolution.effectiveFormatCode == effectiveFormatCode) {
            "Requested resolution policy format ${requestedResolution.effectiveFormatCode} does not match PRIMARY_BUFFER format $effectiveFormatCode"
        }
        require(autoResolution.effectiveFormatCode == effectiveFormatCode) {
            "Auto resolution policy format ${autoResolution.effectiveFormatCode} does not match PRIMARY_BUFFER format $effectiveFormatCode"
        }

        val authorityFingerprint = PhotoStreamAuthorityFingerprint.create(
            formatResolution = formatResolution,
            resolutionResolution = requestedResolution
        )
        val applicableFallback = runtimeFallback?.takeIf {
            it.appliesTo(authorityFingerprint)
        }
        val tier = applicableFallback?.tier ?: StreamRuntimeFallbackTier.NONE

        val effectiveResolution = when (tier) {
            StreamRuntimeFallbackTier.NONE -> requestedResolution
            StreamRuntimeFallbackTier.AUTO_GEOMETRY,
            StreamRuntimeFallbackTier.CONSERVATIVE_FULL_FOV -> autoResolution
        }
        val baseExtent = requireNotNull(effectiveResolution.selectedExtent) {
            "Photo stream selection requires a resolved PRIMARY_BUFFER extent"
        }
        val selectedExtent = when (tier) {
            StreamRuntimeFallbackTier.NONE,
            StreamRuntimeFallbackTier.AUTO_GEOMETRY -> baseExtent
            StreamRuntimeFallbackTier.CONSERVATIVE_FULL_FOV ->
                autoResolution.conservativeSize?.extent ?: baseExtent
        }

        val reason = when (tier) {
            StreamRuntimeFallbackTier.NONE ->
                "Capability-driven Photo format and requested resolution policy remain authoritative."

            StreamRuntimeFallbackTier.AUTO_GEOMETRY ->
                "Runtime recovery disabled the explicit resolution override after ${applicableFallback?.failureReason}; native Auto geometry is used without rewriting saved settings."

            StreamRuntimeFallbackTier.CONSERVATIVE_FULL_FOV -> {
                val changed = selectedExtent != autoResolution.selectedExtent
                if (changed) {
                    "Runtime recovery selected the lower-bandwidth conservative full-FOV extent after ${applicableFallback?.failureReason}; saved settings remain unchanged."
                } else {
                    "Runtime recovery reached the conservative tier after ${applicableFallback?.failureReason}, but no smaller useful full-FOV extent is available."
                }
            }
        }

        return selection(
            requestedLensId = requestedLensId,
            formatResolution = formatResolution,
            effectiveResolution = effectiveResolution,
            primaryExtent = StreamExtent(selectedExtent.width, selectedExtent.height),
            configuredPreviewExtent = configuredPreviewExtent,
            authorityFingerprint = authorityFingerprint,
            runtimeFallbackTier = tier,
            reason = reason
        )
    }

    private fun selection(
        requestedLensId: String,
        formatResolution: ResolvedCameraPhotoFormat,
        effectiveResolution: ResolvedCameraPhotoResolution,
        primaryExtent: StreamExtent,
        configuredPreviewExtent: StreamExtent?,
        authorityFingerprint: String,
        runtimeFallbackTier: StreamRuntimeFallbackTier,
        reason: String
    ): ResolvedPhotoStreamSelection {
        val effectiveFormatCode = requireNotNull(formatResolution.effectiveFormatCode)
        val primaryRoleKind = when (formatResolution.effectiveFormat?.kind) {
            CameraCapabilityFormatKind.RAW10,
            CameraCapabilityFormatKind.RAW12,
            CameraCapabilityFormatKind.RAW_SENSOR -> StreamRoleKind.RAW_PRIMARY
            else -> StreamRoleKind.YUV_PRIMARY
        }
        return ResolvedPhotoStreamSelection(
            requestedLensId = requestedLensId,
            requestedFormatCode = formatResolution.requestedFormatCode,
            effectiveFormatCode = effectiveFormatCode,
            formatSelectionKind = formatResolution.selectionKind,
            formatFallbackApplied = formatResolution.fallbackApplied,
            resolutionSelectionKind = effectiveResolution.selectionKind,
            specificRawSizeIndex = effectiveResolution.specificRawSizeIndex,
            resolutionFixReferenceFormatCode = effectiveResolution.resolutionFixReferenceFormatCode,
            resolutionFixApplied = effectiveResolution.resolutionFixApplied,
            primaryRoleKind = primaryRoleKind,
            primaryExtent = primaryExtent,
            previewExtent = configuredPreviewExtent,
            authorityFingerprint = authorityFingerprint,
            runtimeFallbackTier = runtimeFallbackTier,
            reason = reason
        )
    }
}
