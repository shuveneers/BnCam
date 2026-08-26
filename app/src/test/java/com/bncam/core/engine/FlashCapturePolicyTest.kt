package com.bncam.core.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FlashCapturePolicyTest {
    @Test
    fun autoFlashUsesZslOnlyWhenCurrentAutoAeExplicitlyConverged() {
        val decision = FlashCapturePolicy.decide(
            mode = "Auto",
            manualExposureActive = false,
            flashHardwareAvailable = true,
            autoFlashAeModeSupported = true,
            currentPipelineGeneration = 7,
            observationGeneration = 7,
            observationUsesAutoFlashAe = true,
            observedAeState = FlashAeState.CONVERGED
        )
        assertEquals(FlashCaptureRoute.ZSL, decision.route)
        assertFalse(decision.autoDecisionUncertain)
    }

    @Test
    fun autoFlashReservesDedicatedStillWhenFlashRequiredOrObservationUncertain() {
        val required = FlashCapturePolicy.decide(
            "Auto", false, true, true, 7, 7, true, FlashAeState.FLASH_REQUIRED
        )
        assertTrue(required.requiresDedicatedStill)

        val stale = FlashCapturePolicy.decide(
            "Auto", false, true, true, 8, 7, true, FlashAeState.CONVERGED
        )
        assertTrue(stale.requiresDedicatedStill)
        assertTrue(stale.autoDecisionUncertain)

        val torchAeObservation = FlashCapturePolicy.decide(
            "Auto", false, true, true, 8, 8, false, FlashAeState.CONVERGED
        )
        assertTrue(torchAeObservation.requiresDedicatedStill)
    }

    @Test
    fun flashOnAlwaysUsesDedicatedStillWhileManualAndUnavailableStayZsl() {
        assertTrue(
            FlashCapturePolicy.decide(
                "On", false, true, true, 1, 1, false, FlashAeState.UNKNOWN
            ).requiresDedicatedStill
        )
        assertFalse(
            FlashCapturePolicy.decide(
                "On", true, true, true, 1, 1, false, FlashAeState.UNKNOWN
            ).requiresDedicatedStill
        )
        assertFalse(
            FlashCapturePolicy.decide(
                "On", false, false, true, 1, 1, false, FlashAeState.UNKNOWN
            ).requiresDedicatedStill
        )
    }
}
