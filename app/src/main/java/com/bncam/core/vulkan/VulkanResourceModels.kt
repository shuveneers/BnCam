package com.bncam.core.vulkan

enum class VulkanResourceType {
    EXTERNAL_AHARDWAREBUFFER,
    VMA_OWNED_BUFFER,
    VMA_OWNED_IMAGE,
    PERSISTENT_STAGING_BUFFER,
    BORROWED_CAPTURE_RESOURCE;

    companion object {
        fun fromNativeName(name: String): VulkanResourceType = when (name.uppercase()) {
            "EXTERNAL_AHARDWAREBUFFER" -> EXTERNAL_AHARDWAREBUFFER
            "VMA_OWNED_BUFFER" -> VMA_OWNED_BUFFER
            "VMA_OWNED_IMAGE" -> VMA_OWNED_IMAGE
            "PERSISTENT_STAGING_BUFFER" -> PERSISTENT_STAGING_BUFFER
            else -> BORROWED_CAPTURE_RESOURCE
        }
    }
}

enum class VulkanResourceState {
    ACQUIRED,
    IMPORTING,
    VULKAN_READY,
    IN_FLIGHT,
    RELEASE_PENDING,
    RELEASED,
    FAILED;

    companion object {
        fun fromNativeName(name: String): VulkanResourceState = when (name.uppercase()) {
            "ACQUIRED" -> ACQUIRED
            "IMPORTING" -> IMPORTING
            "VULKAN_READY" -> VULKAN_READY
            "IN_FLIGHT" -> IN_FLIGHT
            "RELEASE_PENDING" -> RELEASE_PENDING
            "RELEASED" -> RELEASED
            else -> FAILED
        }
    }
}

enum class VulkanImportPath {
    DIRECT_AHARDWAREBUFFER_IMPORT,
    NATIVE_LOCK_TO_PERSISTENT_STAGING,
    UNSUPPORTED;

    companion object {
        fun fromNativeName(name: String): VulkanImportPath = when (name.uppercase()) {
            "DIRECT_AHARDWAREBUFFER_IMPORT" -> DIRECT_AHARDWAREBUFFER_IMPORT
            "NATIVE_LOCK_TO_PERSISTENT_STAGING" -> NATIVE_LOCK_TO_PERSISTENT_STAGING
            else -> UNSUPPORTED
        }
    }
}

enum class VulkanDemosaicMethod {
    BILINEAR,
    MALVAR_2004,
    MENON_2007,
    AUTO;

    companion object {
        fun fromNativeName(name: String): VulkanDemosaicMethod = when (name.uppercase()) {
            "BILINEAR" -> BILINEAR
            "MALVAR_2004" -> MALVAR_2004
            "MENON_2007" -> MENON_2007
            else -> AUTO
        }
    }
}

data class VulkanResourceIdentity(
    val resourceId: String,
    val generationId: Long,
    val type: VulkanResourceType,
    val state: VulkanResourceState,
    val path: VulkanImportPath
)

data class SourceImportDecisionModel(
    val sourceName: String,
    val androidFormat: Int,
    val resolvedPath: VulkanImportPath,
    val fallbackPath: VulkanImportPath,
    val ownership: VulkanResourceType,
    val directImportEligible: Boolean,
    val requiredAcquireFence: Boolean,
    val requiredReleaseFence: Boolean,
    val knownBlocker: String
)
