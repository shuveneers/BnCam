package com.bncam.core.capture

/**
 * Pure authority decision for the default RAW API-36 hybrid-AE route.
 *
 * Capability support and runtime trust are deliberately separate inputs: a camera must advertise
 * SENSOR_EXPOSURE_TIME_PRIORITY and the current pipeline generation must still be trusted before
 * BnCam lets Camera2 AE own sensitivity/frame-duration while BnCam owns exposure time.
 */
data class DefaultRawApi36RouteDecision(
    val useExposureTimePriority: Boolean,
    val aeOwnsSensorSensitivity: Boolean,
    val aeOwnsSensorFrameDuration: Boolean,
    val reason: String
) {
    fun summary(): String =
        "useExposureTimePriority=$useExposureTimePriority;" +
            "aeOwnsSensorSensitivity=$aeOwnsSensorSensitivity;" +
            "aeOwnsSensorFrameDuration=$aeOwnsSensorFrameDuration;reason=$reason"
}

object DefaultRawApi36RoutePolicy {
    fun resolve(
        priorityModeSupported: Boolean,
        authorityAllowed: Boolean
    ): DefaultRawApi36RouteDecision {
        if (!priorityModeSupported) {
            return DefaultRawApi36RouteDecision(
                useExposureTimePriority = false,
                aeOwnsSensorSensitivity = false,
                aeOwnsSensorFrameDuration = false,
                reason = "exposure_time_priority_not_advertised"
            )
        }
        if (!authorityAllowed) {
            return DefaultRawApi36RouteDecision(
                useExposureTimePriority = false,
                aeOwnsSensorSensitivity = false,
                aeOwnsSensorFrameDuration = false,
                reason = "exposure_time_priority_rejected_for_generation"
            )
        }
        return DefaultRawApi36RouteDecision(
            useExposureTimePriority = true,
            aeOwnsSensorSensitivity = true,
            aeOwnsSensorFrameDuration = true,
            reason = "exposure_time_priority_supported_and_trusted"
        )
    }
}
