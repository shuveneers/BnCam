package com.bncam.core.quality

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class Phase6APhysicalNoiseSeparationSourceContractTest {
    private fun source(path: String): String = File(path).readText()

    @Test
    fun rawRenderersMirrorPhysicalAuthorityBeforeNativeRender() {
        val single = source("app/src/main/java/com/bncam/core/isp/raw/Raw16RenderInput.kt")
        val master = source("app/src/main/java/com/bncam/core/isp/raw/MasterRawFrame.kt")
        for (text in listOf(single, master)) {
            assertTrue(text.contains("withPhysicalCaptureIdentity"))
            assertTrue(text.contains("finalCalibration = shutterCalibration"))
            assertTrue(text.contains("withPhysicalNoiseAuthority()"))
            assertTrue(text.contains("withPhysicalMergeStats"))
            assertFalse(text.contains("withSpectraMergeStats"))
        }
    }

    @Test
    fun compatibilityBridgeDoesNotOwnSpectraEnablement() {
        val bridge = source("app/src/main/java/com/bncam/core/quality/PhysicalNoiseCalibrationBridge.kt")
        assertTrue(bridge.contains("SPECTRA enablement is intentionally left untouched"))
        assertFalse(bridge.contains("spectraProcessingEnabled ="))
        assertTrue(bridge.contains("manualNoiseSingleAnchorScaled = false"))
        assertTrue(bridge.contains("SPECTRA fit coefficients ignored"))
        assertTrue(bridge.contains("frozenPhysicalSoUnchanged=true"))
        val mergeFunction = bridge.substringAfter("fun FinalSensorCalibration.withPhysicalMergeStats")
            .substringBefore("fun FinalSensorCalibration.withPhysicalCaptureIdentity")
        assertFalse(mergeFunction.contains("noiseSnapshot ="))
        assertFalse(mergeFunction.contains("effectiveNoiseProfile ="))
    }

    @Test
    fun snapshotDefinesPhysicalAuthorityIndependently() {
        val snapshot = source("app/src/main/java/com/bncam/core/quality/NoiseModelSnapshotV3.kt")
        assertTrue(snapshot.contains("SPECTRA is only an optional downstream consumer"))
        assertTrue(snapshot.contains("physicalNoiseModelConfidence"))
        assertTrue(snapshot.contains("SPECTRA Off"))
    }
}
