package com.bncam.core.quality

import java.io.File
import org.junit.Test
import org.junit.Assert.assertTrue

class Phase3LowLightPresentationSourceContractTest {
    @Test
    fun `physical noise pressure limits low light presentation amplification`() {
        val isp = File("src/main/cpp/IspCore.cpp").readText()
        val policy = File("src/main/cpp/LowLightNoisePresentationPolicy.h").readText()

        assertTrue(isp.contains("resolveLowLightPresentationPlan"))
        assertTrue(isp.contains("isoState.combinedNoisePressure"))
        assertTrue(isp.contains("lowLightPresentationPlan.rawBaseVibrance"))
        assertTrue(policy.contains("if (!input.lowLightScene)"))
        assertTrue(policy.contains("out.rawBaseVibrance = 1.16f"))
        assertTrue(policy.contains("legacyLowLightVibrance"))
        assertTrue(policy.contains("noiseLimitedVibranceFloor"))
        assertTrue(policy.contains("automaticMidtoneLiftAttenuation"))
    }
}
