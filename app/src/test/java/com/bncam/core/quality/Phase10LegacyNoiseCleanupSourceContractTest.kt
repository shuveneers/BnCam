package com.bncam.core.quality

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class Phase10LegacyNoiseCleanupSourceContractTest {
    private fun source(path: String): String = File(path).readText()

    @Test
    fun retiredNoiseAuthoritiesArePhysicallyRemoved() {
        assertFalse(File("app/src/main/java/com/bncam/core/quality/CaptureNoiseState.kt").exists())
        assertFalse(File("app/src/main/java/com/bncam/core/quality/NoiseModelAuthorityPolicy.kt").exists())
        assertFalse(File("app/src/main/java/com/bncam/core/quality/TemporalNoiseModelAuthorityPolicy.kt").exists())
        assertTrue(File("app/src/main/java/com/bncam/core/quality/PhysicalTemporalNoisePolicy.kt").exists())
    }

    @Test
    fun sensorCalibrationCannotSelectLegacyManualOrAutoAuthority() {
        val text = source("app/src/main/java/com/bncam/core/quality/SensorCalibration.kt")
        assertFalse(text.contains("NoiseModelAuthorityPolicy.resolve("))
        assertFalse(text.contains("singleAnchorManualNoiseScaling"))
        assertTrue(text.contains("PhysicalNoiseState is the sole physical noise authority"))
    }

    @Test
    fun traceDoesNotRecreateRetiredNoiseAuthoritySchema() {
        val text = source("app/src/main/java/com/bncam/core/quality/NoiseModelTrace.kt")
        assertFalse(text.contains("\"captureNoiseState\""))
        assertFalse(text.contains("\"finalRenderOutput\""))
        assertFalse(text.contains("appliedNoiseModelMode"))
        assertFalse(text.contains("soValuesSentToJni"))
    }
}
