package com.bncam.core.vulkan

enum class VulkanFrameRole {
    ANCHOR,
    SUPPORT;

    companion object {
        fun fromNativeName(name: String): VulkanFrameRole = when (name.uppercase()) {
            "ANCHOR" -> ANCHOR
            else -> SUPPORT
        }
    }
}

enum class VulkanFrameState {
    SELECTED,
    GPU_READY,
    ALIGNMENT_PENDING,
    ALIGNED,
    REJECTED,
    FUSION_PENDING,
    FUSED,
    RELEASE_PENDING,
    RELEASED,
    FAILED;

    companion object {
        fun fromNativeName(name: String): VulkanFrameState = when (name.uppercase()) {
            "SELECTED" -> SELECTED
            "GPU_READY" -> GPU_READY
            "ALIGNMENT_PENDING" -> ALIGNMENT_PENDING
            "ALIGNED" -> ALIGNED
            "REJECTED" -> REJECTED
            "FUSION_PENDING" -> FUSION_PENDING
            "FUSED" -> FUSED
            "RELEASE_PENDING" -> RELEASE_PENDING
            "RELEASED" -> RELEASED
            else -> FAILED
        }
    }
}

data class VulkanMotionVectorModel(
    val dx: Float,
    val dy: Float,
    val alignmentResponse: Float,
    val cfaParityPreserved: Boolean,
    val accepted: Boolean,
    val rejectionReason: String
)

data class VulkanFrameResourceModel(
    val frameId: String,
    val generationId: Long,
    val timestampNs: Long,
    val width: Int,
    val height: Int,
    val rowStrideBytes: Int,
    val bayerPattern: String,
    val role: VulkanFrameRole,
    val state: VulkanFrameState,
    val selectionScore: Float,
    val vulkanResourceIdentity: String
)

data class MultiFrameDiagnosticsModel(
    val requestedFrames: Int,
    val effectiveFrames: Int,
    val selectedFrames: Int,
    val anchorFrame: String,
    val supportFrames: Int,
    val GPUReadyFrames: Int,
    val alignmentAcceptedFrames: Int,
    val alignmentRejectedFrames: Int,
    val fusionAcceptedFrames: Int,
    val fusionRejectedFrames: Int,
    val supportReadbackCount: Int,     // Must be 0
    val intermediateReadbackCount: Int,// Must be 0
    val finalReadbackCount: Int,       // 1 for RGB output
    val poolAllocations: Int,
    val poolReuse: Int,
    val fallback: Boolean,
    val failureReason: String
)
