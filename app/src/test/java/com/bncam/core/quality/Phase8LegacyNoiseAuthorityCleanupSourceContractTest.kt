package com.bncam.core.quality

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class Phase8LegacyNoiseAuthorityCleanupSourceContractTest {
    private fun source(path: String): String = File(path).readText()

    @Test
    fun temporalPolicyCannotFallBackToLegacySo() {
        val text = source("app/src/main/java/com/bncam/core/quality/TemporalNoiseModelAuthorityPolicy.kt")
        assertTrue(text.contains("PHYSICAL_SHUTTER_SNAPSHOT_REQUIRED"))
        assertFalse(text.contains("PHYSICAL_CAMERA2_FIXED_SO_LEGACY"))
        assertFalse(text.contains("SPECTRA_ADDON_CONSUMES_LEGACY_PHYSICAL_SO"))
        assertFalse(text.contains("val profileS"))
        assertFalse(text.contains("val profileO"))
    }

    @Test
    fun bridgeScrubsLegacyManualSoFromCaptureLocalOverride() {
        val text = source("app/src/main/java/com/bncam/core/quality/PhysicalNoiseCalibrationBridge.kt")
        assertTrue(text.contains("manualNoiseValues = null"))
        assertTrue(text.contains("noiseMode = \"PhysicalNoiseState\""))
        assertTrue(text.contains("manualNoiseSingleAnchorScaled = false"))
        assertTrue(text.contains("legacyManualSoAuthority=false"))
    }

    @Test
    fun traceStatePublishesNewPhysicalFieldsAndMarksLegacyCarriersRetired() {
        val text = source("app/src/main/java/com/bncam/core/quality/CaptureNoiseState.kt")
        assertTrue(text.contains("physicalNoiseDynamicIsoCoefficientRange"))
        assertTrue(text.contains("ISO_NM=trunc(50+k*(ISO_capture-50))"))
        assertTrue(text.contains("legacyManualSoAuthority"))
        assertTrue(text.contains("legacyTemporalSoFallback"))
        assertTrue(text.contains("legacyDynamicIsoRuntimeCarrier"))
    }
    @Test
    fun legacyPolicyCannotSelectManualPhysicalMode() {
        val text = source("app/src/main/java/com/bncam/core/quality/NoiseModelAuthorityPolicy.kt")
        assertTrue(text.contains("val physicalMode = if (cameraNoiseProfileAvailable) AUTO else OFF"))
        assertFalse(text.contains("requestedLensMode == MANUAL"))
        assertFalse(text.contains("physicalMode = MANUAL"))
    }

    @Test
    fun unresolvedLegacyArraysCannotMasqueradeAsResolvedPhysicalModel() {
        val text = source("app/src/main/java/com/bncam/core/quality/NoiseModelSnapshotV3.kt")
        assertTrue(text.contains("physicalNoiseEffectiveSource.uppercase() in setOf(\"OEM\", \"SYSTEM\", \"MANUAL\", \"PRESET\")"))
        assertTrue(text.contains("physicalNoiseEffectiveSource: String = \"UNRESOLVED\""))
        assertFalse(text.contains("physicalNoiseEffectiveSource: String = \"LEGACY\""))
    }

}
