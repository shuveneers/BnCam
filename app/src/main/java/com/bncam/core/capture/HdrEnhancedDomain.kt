package com.bncam.core.capture

/**
 * Typed domain enums for HDR Enhanced decision making and diagnostics.
 * Replaces unstructured strings with explicit type-safe contracts.
 */

enum class HdrEnhancedMotionClass {
    LOW_MOTION,
    MODERATE_MOTION,
    HIGH_MOTION,
    UNKNOWN
}

enum class HdrEnhancedStatus {
    REQUESTED,
    PLANNED,
    CAPTURING,
    BURST_ACQUIRED,
    BASE_SELECTED,
    ALIGNED,
    MERGED,
    RENDERED,
    PUBLISHED,
    FALLBACK,
    FAILED
}

enum class HdrEnhancedFallbackReason {
    NONE,
    INSUFFICIENT_FRAMES,
    ALIGNMENT_FAILURE,
    BURST_TIMEOUT,
    HARDWARE_LIMITATION,
    EXCEPTIONAL_FAILURE
}

enum class HdrEnhancedAuxRole {
    NONE,
    HIGHLIGHT_INSURANCE,
    SHADOW_SUPPORT
}

enum class HdrEnhancedOverrideReason {
    NONE,
    COMPUTATIONAL_HDR_USER_REQUESTED,
    SCENE_REQUIREMENT,
    DEDICATED_FLASH_STILL,
    NIGHT_MODE_ACTIVE
}

enum class HdrExposureAuthority {
    RAW_ETTR_HEADROOM,
    RAW_HISTOGRAM,
    SENSOR_CLIP_ESTIMATE,
    AE_FALLBACK
}

data class HdrEnhancedAuxPlan(
    val role: HdrEnhancedAuxRole,
    val exposureTimeNs: Long,
    val sensitivityIso: Int,
    val exposureScaleToMain: Float,
    val reason: String
)

data class HdrEnhancedFrameBounds(
    val hdrEnhancedFrameSetting: String = "Auto",
    val userFrameLimit: Int? = null,
    val hardwareFrameCapacity: Int = 15,
    val plannerMaximumFrames: Int = 15,
    val budgetLimitedMaximumFrames: Int = 15,
    val motionLimitedMaximumFrames: Int = 15,
    val memoryLimitedMaximumFrames: Int = 15,
    val finalMainFrameCount: Int = 15,
    val limitingConstraint: String = ""
)
