package com.bncam.core.vulkan

enum class VulkanLifecycleState {
    UNINITIALIZED,
    INITIALIZING,
    READY,
    CAPTURE_ACTIVE,
    PROCESSING,
    DRAINING,
    SUSPENDED,
    RECOVERING,
    FAILED,
    SHUTDOWN
}

data class ExplicitVulkanFailureModel(
    val captureId: String,
    val generationId: Long,
    val stage: String,
    val operation: String,
    val vkResultCode: Int,
    val resourceIdentity: String,
    val runtimeIdentity: String,
    val outputPolicy: String,
    val jpegResult: String,
    val dngResult: String,
    val recoveryAction: String
)
