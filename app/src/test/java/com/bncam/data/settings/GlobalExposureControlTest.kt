package com.bncam.data.settings

import com.bncam.core.capture.SensorExposureMode
import org.junit.Assert.assertEquals
import org.junit.Test

class GlobalExposureControlTest {
    @Test fun onlyAutomaticControllersCanBeDecodedGlobally() {
        listOf(null, "", "invalid", "MANUAL", "Manual").forEach {
            assertEquals(SensorExposureMode.STANDARD_AUTO, automaticExposureControl(it))
        }
        listOf("BN_AUTO", "Bn Auto", "BnC Auto").forEach {
            assertEquals(SensorExposureMode.BN_AUTO, automaticExposureControl(it))
        }
        assertEquals("BnC Auto", SensorExposureMode.BN_AUTO.label)
    }
}
