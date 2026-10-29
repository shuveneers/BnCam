package com.bncam.core.capture

import org.junit.Assert.assertEquals
import org.junit.Test

class BnAutoFlickerSafetyPolicyTest {
    private fun resolve(period: Long?, stable: Long = 600_000_000L,
        moving: Boolean = false, camera: Long? = null, subject: Long? = null,
        frame: Long = 33_333_333L): Long = BnAutoFlickerSafetyPolicy.ceilingNs(
            5_000_000L, period, frame, 100_000_000L, stable, moving, camera, subject)

    @Test fun confirmed50HzMayUseOneTenMsLightPeriodAt30Fps() {
        assertEquals(10_000_000L, resolve(10_000_000L))
    }
    @Test fun confirmed60HzMayUseOneEightPointThreeMsPeriodAt30Fps() {
        assertEquals(8_333_333L, resolve(8_333_333L))
    }
    @Test fun unknownFrequencyNeverFabricatesAMainsLock() {
        assertEquals(5_000_000L, resolve(null))
    }
    @Test fun realMotionAndMeasuredShortMotionCeilingsAlwaysWin() {
        assertEquals(5_000_000L, resolve(10_000_000L, moving = true))
        assertEquals(5_000_000L, resolve(10_000_000L, camera = 8_000_000L))
        assertEquals(5_000_000L, resolve(10_000_000L, subject = 6_000_000L))
    }
    @Test fun insufficientNonmotionHistoryDoesNotGrantLongerShutter() {
        assertEquals(5_000_000L, resolve(10_000_000L, stable = 200_000_000L))
    }
    @Test fun previewFpsBudgetDoesNotGetSacrificedForFlicker() {
        assertEquals(5_000_000L, resolve(10_000_000L, frame = 10_000_000L))
    }
}
