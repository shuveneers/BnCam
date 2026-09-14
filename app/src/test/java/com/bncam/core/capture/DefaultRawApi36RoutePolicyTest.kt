package com.bncam.core.capture

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultRawApi36RoutePolicyTest {
    @Test
    fun `supported and trusted priority selects hybrid ae`() {
        val decision = DefaultRawApi36RoutePolicy.resolve(
            priorityModeSupported = true,
            authorityAllowed = true
        )
        assertTrue(decision.useExposureTimePriority)
        assertTrue(decision.aeOwnsSensorSensitivity)
        assertTrue(decision.aeOwnsSensorFrameDuration)
    }

    @Test
    fun `unsupported priority selects fallback authority`() {
        val decision = DefaultRawApi36RoutePolicy.resolve(
            priorityModeSupported = false,
            authorityAllowed = true
        )
        assertFalse(decision.useExposureTimePriority)
        assertFalse(decision.aeOwnsSensorSensitivity)
        assertFalse(decision.aeOwnsSensorFrameDuration)
    }

    @Test
    fun `runtime rejected priority selects fallback authority`() {
        val decision = DefaultRawApi36RoutePolicy.resolve(
            priorityModeSupported = true,
            authorityAllowed = false
        )
        assertFalse(decision.useExposureTimePriority)
        assertFalse(decision.aeOwnsSensorSensitivity)
        assertFalse(decision.aeOwnsSensorFrameDuration)
    }
}
