package com.bncam.core.runtime

/**
 * Keeps the normal RAW viewfinder on the shortest GPU-resident path.
 *
 * Exposure statistics and default RAW motion metering already have dedicated sensor/GPU-domain
 * sources, so neither requires a compact NV21 side image. NV21 is requested only for consumers
 * that actually need an image-domain analysis surface (QR, active object tracking, portrait).
 */
data class RawPreviewAnalysisDemand(
    val qrEnabled: Boolean,
    val objectTrackingEnabled: Boolean,
    val focusTrackingActive: Boolean,
    val portraitEnabled: Boolean,
    val capturing: Boolean
)

enum class RawPreviewFastPathKind {
    /** RAW AHardwareBuffer stays device-resident through native import and GPU presentation. */
    DIRECT_AHB_GPU_RESIDENT,

    /** Native preview stays GPU-resident at output, but input required a host-visible import path. */
    HOST_INPUT_GPU_RESIDENT,

    /** GPU-resident output succeeded but native input provenance used another compatible import. */
    GPU_RESIDENT_COMPATIBILITY,

    /** Final RGBA presentation is CPU-visible and uploaded by GLES as the compatibility fallback. */
    CPU_VISIBLE_RGBA_FALLBACK
}

object RawPreviewFastPathPolicy {
    fun needsCompactNv21(demand: RawPreviewAnalysisDemand): Boolean {
        if (demand.capturing) return demand.qrEnabled
        return demand.qrEnabled ||
            (demand.objectTrackingEnabled && demand.focusTrackingActive) ||
            demand.portraitEnabled
    }

    fun classify(
        gpuResidentOutputUsed: Boolean,
        directHardwareBufferInputUsed: Boolean,
        directHostInputUsed: Boolean
    ): RawPreviewFastPathKind = when {
        gpuResidentOutputUsed && directHardwareBufferInputUsed ->
            RawPreviewFastPathKind.DIRECT_AHB_GPU_RESIDENT
        gpuResidentOutputUsed && directHostInputUsed ->
            RawPreviewFastPathKind.HOST_INPUT_GPU_RESIDENT
        gpuResidentOutputUsed ->
            RawPreviewFastPathKind.GPU_RESIDENT_COMPATIBILITY
        else -> RawPreviewFastPathKind.CPU_VISIBLE_RGBA_FALLBACK
    }
}
