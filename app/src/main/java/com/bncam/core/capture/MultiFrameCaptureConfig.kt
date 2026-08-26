package com.bncam.core.capture

/**
 * Immutable capture configuration frozen at shutter time for multi-frame processing.
 * Preserves exact capture settings independently of post-shutter preference changes.
 * Processing/fusion and DNG master counts are independent shutter-time contracts.
 */
data class MultiFrameCaptureConfig(
    val profileId: String = "default_profile",
    val profileName: String = "Default Profile",
    val sourceFormat: Int,
    val shootingMode: CaptureMode,
    val alignmentMethod: String,
    val fusionMethod: String,
    val fusionFrameCount: Int = 8,
    val dngMasterFrameCount: Int = 1,
    val exposureStrategy: String,
    val configuredBufferCapacity: Int,
    val effectiveBufferCapacity: Int,
    val lensId: String,
    val demosaicMethod: String,
    val outputPolicy: OutputPolicy,
    val phoneAssistanceSensorsEnabled: Boolean = false,
    val captureConfigurationTimestampNs: Long = System.nanoTime()
) {
    val jpegFusionFrameCount: Int get() = fusionFrameCount
}
