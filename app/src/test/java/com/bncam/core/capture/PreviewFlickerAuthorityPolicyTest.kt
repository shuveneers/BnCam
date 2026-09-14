package com.bncam.core.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PreviewFlickerAuthorityPolicyTest {
    @Test
    fun rawWarmProducerOwnsDynamicSceneFlicker() {
        assertEquals(
            PreviewFlickerAuthorityOwner.BNCAM_RAW,
            PreviewFlickerAuthorityPolicy.owner(isRawWarmProducer = true)
        )
        assertTrue(PreviewFlickerAuthorityPolicy.shouldObserveSceneFlicker(true))
        assertTrue(PreviewFlickerAuthorityPolicy.shouldResubmitForFlickerAuthorityChange(true))
    }

    @Test
    fun yuvLeavesAntibandingAndAeWithCamera2() {
        assertEquals(
            PreviewFlickerAuthorityOwner.CAMERA2_HAL,
            PreviewFlickerAuthorityPolicy.owner(isRawWarmProducer = false)
        )
        assertFalse(PreviewFlickerAuthorityPolicy.shouldObserveSceneFlicker(false))
        assertFalse(PreviewFlickerAuthorityPolicy.shouldResubmitForFlickerAuthorityChange(false))
    }
}
