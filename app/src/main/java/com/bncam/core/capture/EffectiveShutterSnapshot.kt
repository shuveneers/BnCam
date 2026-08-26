package com.bncam.core.capture

import com.bncam.data.profile.IspProfileConfig
import com.bncam.data.profile.SettingImpact
import com.bncam.data.settings.LensCalibrationConfig
import com.bncam.data.settings.OutputModeDngConfig
import java.util.UUID

data class EffectiveShutterSnapshot(
    val snapshotSchemaVersion: Int = SNAPSHOT_SCHEMA_VERSION,
    val snapshotId: String = UUID.randomUUID().toString(),
    val timestamp: Long = System.currentTimeMillis(),
    val snapshotTimestampNs: Long = System.nanoTime(),

    val stableLensKey: String = "0",
    val logicalCameraId: String = "0",
    val physicalCameraId: String? = null,

    val calibrationSchemaVersion: Int = 1,
    val calibrationRevision: Long = 1L,
    val profileSchemaVersion: Int = IspProfileConfig.SCHEMA_VERSION,
    val profileId: String = "default",
    val profileName: String = "Default Profile",
    val profileRevision: Long = 1L,
    val outputSettingsRevision: Long = 1L,

    val configuredJpegFrameCount: Int = 8,
    val effectiveJpegFrameCount: Int = 8,
    val jpegFrameCountResolutionReason: String = "EXACT_PROFILE",

    val configuredDngMasterFrameCount: Int = 1,
    val effectiveDngMasterFrameCount: Int = 1,
    val dngFrameCountResolutionReason: String = "EXACT_CONFIGURED",

    val calibration: LensCalibrationConfig = LensCalibrationConfig(lensId = "0"),
    val profile: IspProfileConfig = IspProfileConfig.createDefault("0"),
    val outputDngSettings: OutputModeDngConfig = OutputModeDngConfig(),
    val outputPolicy: OutputPolicy = OutputPolicy.JPEG,
    val dngSourcePolicy: String = "ANCHOR_RAW",

    val phoneSensorSource: String = "NONE",
    val normalizedSensorContribution: Float = 0.00f,
    val effectiveBoundedSensorContribution: Float = 0.00f,

    val effectiveNormalizedProfileValues: Map<String, Float> = emptyMap(),
    val effectiveVulkanParameters: Map<String, Float> = emptyMap(),
    val settingImpactMap: Map<String, List<SettingImpact>> = emptyMap(),

    val cameraId: String = logicalCameraId,
    val lensId: String = stableLensKey
) {
    companion object {
        const val SNAPSHOT_SCHEMA_VERSION = 1
    }
}
