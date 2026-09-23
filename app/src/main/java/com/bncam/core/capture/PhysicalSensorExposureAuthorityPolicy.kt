package com.bncam.core.capture

/**
 * Single-owner exposure policy shared by every camera/lens/stream.
 *
 * Standard Auto is intentionally format- and lens-agnostic: Camera2 AE owns the complete
 * sensor exposure tuple (time, sensitivity and frame duration) for YUV, RAW10 and RAW_SENSOR.
 * BnCam may observe the physical sensor metadata, but it must not silently replace the HAL AE
 * decision merely because a RAW producer is active.
 *
 * Only an explicit user/profile request may move ownership away from Camera2 AE.
 */
enum class PhysicalSensorExposureOwner {
    CAMERA2_HAL_AE,
    PROFILE_EXPLICIT_PRIORITY,
    USER_MANUAL_SENSOR
}

data class PhysicalSensorExposureAuthorityDecision(
    val owner: PhysicalSensorExposureOwner,
    val reason: String
) {
    val camera2OwnsCompleteExposure: Boolean
        get() = owner == PhysicalSensorExposureOwner.CAMERA2_HAL_AE
}

object PhysicalSensorExposureAuthorityPolicy {
    fun resolve(
        explicitManualSensorRequest: Boolean,
        profileRequiresExplicitExposurePriority: Boolean
    ): PhysicalSensorExposureAuthorityDecision = when {
        explicitManualSensorRequest -> PhysicalSensorExposureAuthorityDecision(
            owner = PhysicalSensorExposureOwner.USER_MANUAL_SENSOR,
            reason = "explicit_user_manual_sensor_request"
        )
        profileRequiresExplicitExposurePriority -> PhysicalSensorExposureAuthorityDecision(
            owner = PhysicalSensorExposureOwner.PROFILE_EXPLICIT_PRIORITY,
            reason = "explicit_profile_exposure_priority"
        )
        else -> PhysicalSensorExposureAuthorityDecision(
            owner = PhysicalSensorExposureOwner.CAMERA2_HAL_AE,
            reason = "standard_auto_single_owner_camera2_ae"
        )
    }
}
