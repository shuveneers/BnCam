package com.bncam.core.capture

/**
 * Separates BnCam's RAW-only scene-flicker authority from normal Camera2 auto exposure.
 *
 * RAW manual/shutter-priority routes need BnCam to turn stabilized scene-flicker evidence into
 * acquisition constraints. YUV preview keeps Camera2 AE + CONTROL_AE_ANTIBANDING_MODE authoritative;
 * feeding YUV result flicker changes back into repeating-request resubmission creates a second AE
 * control loop with no additional exposure authority.
 */
enum class PreviewFlickerAuthorityOwner {
    BNCAM_RAW,
    CAMERA2_HAL
}

object PreviewFlickerAuthorityPolicy {
    fun owner(isRawWarmProducer: Boolean): PreviewFlickerAuthorityOwner =
        if (isRawWarmProducer) PreviewFlickerAuthorityOwner.BNCAM_RAW
        else PreviewFlickerAuthorityOwner.CAMERA2_HAL

    fun shouldObserveSceneFlicker(isRawWarmProducer: Boolean): Boolean =
        owner(isRawWarmProducer) == PreviewFlickerAuthorityOwner.BNCAM_RAW

    fun shouldResubmitForFlickerAuthorityChange(isRawWarmProducer: Boolean): Boolean =
        shouldObserveSceneFlicker(isRawWarmProducer)
}
