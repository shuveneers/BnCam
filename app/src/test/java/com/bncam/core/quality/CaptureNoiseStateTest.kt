package com.bncam.core.quality

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CaptureNoiseStateTest {
    private fun resolvedSnapshot(
        effectiveS: DoubleArray,
        effectiveO: DoubleArray,
        source: String = "PRESET",
        effectiveModelIso: Double? = 155.0,
        dynamicEnabled: Boolean = true,
        coefficient: Double? = 0.30,
        isoStep: Double? = 1600.0,
        presetName: String? = "JN1"
    ) = NoiseModelSnapshotV3(
        lensKey = "lens_v2_main",
        sourceFormat = "RAW10",
        iso = 401,
        exposureTimeNs = 10_000_000L,
        postRawSensitivityBoost = 100,
        cfaPattern = 0,
        cfaName = "RGGB",
        whiteLevel = 1023,
        blackLevel = floatArrayOf(64f, 64f, 64f, 64f),
        cameraS = doubleArrayOf(0.1, 0.1, 0.1, 0.1),
        cameraO = doubleArrayOf(0.01, 0.01, 0.01, 0.01),
        effectiveS = effectiveS,
        effectiveO = effectiveO,
        chromaUserScale = 1.0f,
        lumaUserScale = 1.0f,
        spectraMode = "Off",
        signalModelConfidence = 0.05f,
        physicalAuthorityLocked = true,
        physicalNoiseRequestedSource = source,
        physicalNoiseEffectiveSource = source,
        physicalNoiseProvenance = "AGC V12 preset",
        physicalNoiseEffectiveModelIso = effectiveModelIso,
        physicalNoiseDynamicIsoEnabled = dynamicEnabled,
        physicalNoiseDynamicIsoCoefficient = coefficient,
        physicalNoiseIsoStep = isoStep,
        physicalNoisePresetName = presetName,
        physicalNoiseSettingsReady = true
    )

    @Test
    fun captureStateReadsPhysicalAuthorityNotLegacySignalConfidence() {
        val snapshot = resolvedSnapshot(
            effectiveS = doubleArrayOf(1.0, 2.0, 3.0, 4.0),
            effectiveO = doubleArrayOf(5.0, 6.0, 7.0, 8.0)
        )
        val state = CaptureNoiseState.from(
            snapshot = snapshot,
            profileNoiseTuning = ProfileNoiseTuning(spectraEnabled = true, spectraChroma = 0.5f),
            lensShadingAlreadyApplied = true,
            lensShadingMapFromMetadata = true,
            lensShadingMapColumns = 17,
            lensShadingMapRows = 13
        )

        val external = state.effectiveSCanonical
        external[0] = 99.0

        assertEquals(1.0, state.effectiveSCanonical[0], 0.0)
        assertEquals(1.0f, state.modelConfidence, 0.0f)
        assertTrue(state.physicalNoiseModelAvailable)
        assertTrue(state.spectraProcessingEnabled)
        assertEquals("R,Gr,Gb,B", state.toTraceMap()["canonicalSoOrder"])
        assertEquals(false, state.toTraceMap()["spectraMayMutatePhysicalSo"])
    }

    @Test
    fun neutralSnapshotCannotActivateSpectra() {
        val state = CaptureNoiseState.from(
            snapshot = NoiseModelSnapshotV3.createNeutral(),
            profileNoiseTuning = ProfileNoiseTuning(spectraEnabled = true),
            lensShadingAlreadyApplied = false
        )
        assertFalse(state.physicalNoiseModelAvailable)
        assertFalse(state.spectraProcessingEnabled)
        assertEquals(0.0f, state.modelConfidence, 0.0f)
    }

    @Test
    fun spectraOffDoesNotEraseResolvedPhysicalModel() {
        val state = CaptureNoiseState.from(
            snapshot = resolvedSnapshot(
                effectiveS = doubleArrayOf(0.1, 0.1, 0.1, 0.1),
                effectiveO = doubleArrayOf(0.01, 0.01, 0.01, 0.01)
            ),
            profileNoiseTuning = ProfileNoiseTuning(spectraEnabled = false),
            lensShadingAlreadyApplied = false
        )
        assertTrue(state.physicalNoiseModelAvailable)
        assertFalse(state.spectraProcessingEnabled)
        assertEquals(1.0f, state.modelConfidence, 0.0f)
    }

    @Test
    fun tracePublishesModernPhysicalDynamicIsoAndRetirementFields() {
        val state = CaptureNoiseState.from(
            snapshot = resolvedSnapshot(
                effectiveS = doubleArrayOf(0.2, 0.2, 0.2, 0.2),
                effectiveO = doubleArrayOf(0.02, 0.02, 0.02, 0.02)
            ),
            profileNoiseTuning = ProfileNoiseTuning(spectraEnabled = false),
            lensShadingAlreadyApplied = false
        )
        val trace = state.toTraceMap()
        assertEquals("PRESET", trace["physicalNoiseEffectiveSource"])
        assertEquals(155.0, trace["physicalNoiseEffectiveModelIso"])
        assertEquals(0.30, trace["physicalNoiseDynamicIsoCoefficient"])
        assertEquals("ISO_NM=trunc(50+k*(ISO_capture-50))", trace["physicalNoiseDynamicIsoFormula"])
        assertEquals(false, trace["legacyManualSoAuthority"])
        assertEquals(false, trace["legacyTemporalSoFallback"])
        assertEquals("RETIRED_NEUTRAL_0", trace["legacyDynamicIsoRuntimeCarrier"])
    }
}
