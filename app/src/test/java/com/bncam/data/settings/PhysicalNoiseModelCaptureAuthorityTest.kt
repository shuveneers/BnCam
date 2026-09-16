package com.bncam.data.settings

import com.bncam.core.quality.NoiseModelCfaPattern
import com.bncam.core.quality.NoiseModelSource
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhysicalNoiseModelCaptureAuthorityTest {
    private val cameraS = doubleArrayOf(1.0, 2.0, 3.0, 4.0)
    private val cameraO = doubleArrayOf(10.0, 20.0, 30.0, 40.0)

    private fun model(isoStep: Double = 100.0) = PersistedParametricNoiseModel(
        a = listOf(0.01, 0.02, 0.03, 0.04),
        b = listOf(-1.0, -2.0, -3.0, -4.0),
        c = listOf(0.0001, 0.0002, 0.0003, 0.0004),
        d = listOf(0.5, 0.6, 0.7, 0.8),
        isoStep = isoStep
    )

    @Test
    fun oemUsesDirectCamera2CanonicalValues() {
        val result = PhysicalNoiseModelCaptureResolver.resolve(
            lensId = "lens_v2_main",
            captureIso = 400,
            cfaPattern = NoiseModelCfaPattern.GRBG,
            cameraS = cameraS,
            cameraO = cameraO,
            settings = LensPhysicalNoiseModelSettings(source = NoiseModelSource.OEM),
            userPresets = emptyList()
        )
        assertEquals(NoiseModelSource.OEM, result.effectiveSource)
        assertArrayEquals(cameraS, result.resolved!!.effectiveS, 0.0)
        assertArrayEquals(cameraO, result.resolved!!.effectiveO, 0.0)
        assertFalse(result.usedFallback)
        assertNull(result.resolved!!.effectiveNoiseModelIso)
    }

    @Test
    fun manualEvaluatesAbcdAtCaptureIsoWithoutSingleAnchorScaling() {
        val result = PhysicalNoiseModelCaptureResolver.resolve(
            lensId = "lens_v2_main",
            captureIso = 400,
            cfaPattern = NoiseModelCfaPattern.RGGB,
            cameraS = cameraS,
            cameraO = cameraO,
            settings = LensPhysicalNoiseModelSettings(
                source = NoiseModelSource.MANUAL,
                manualModel = model(),
                dynamicIsoEnabled = false,
                dynamicIsoCoefficient = 2.0
            ),
            userPresets = emptyList()
        )
        assertEquals(NoiseModelSource.MANUAL, result.effectiveSource)
        assertEquals(400.0, result.resolved!!.effectiveNoiseModelIso!!, 0.0)
        // S0=max(0, .01*400-1)=3.0. This is direct A/B evaluation, not S@100 * 4.
        assertEquals(3.0, result.resolved!!.effectiveS[0], 1e-12)
        assertFalse(result.resolved!!.dynamicIsoCoefficientApplied)
    }

    @Test
    fun dynamicIsoOnlyChangesParametricEvaluationIso() {
        val result = PhysicalNoiseModelCaptureResolver.resolve(
            lensId = "lens_v2_main",
            captureIso = 1600,
            cfaPattern = NoiseModelCfaPattern.RGGB,
            cameraS = cameraS,
            cameraO = cameraO,
            settings = LensPhysicalNoiseModelSettings(
                source = NoiseModelSource.SYSTEM,
                systemModel = model(),
                dynamicIsoEnabled = true,
                dynamicIsoCoefficient = 0.5
            ),
            userPresets = emptyList()
        )
        assertEquals(825.0, result.resolved!!.effectiveNoiseModelIso!!, 0.0)
        assertTrue(result.resolved!!.dynamicIsoCoefficientApplied)
        assertEquals(0.5, result.resolved!!.dynamicIsoCoefficient!!, 0.0)
    }

    @Test
    fun missingSystemFallsBackToOemWithoutFabricatingCoefficients() {
        val result = PhysicalNoiseModelCaptureResolver.resolve(
            lensId = "lens_v2_main",
            captureIso = 800,
            cfaPattern = NoiseModelCfaPattern.BGGR,
            cameraS = cameraS,
            cameraO = cameraO,
            settings = LensPhysicalNoiseModelSettings(source = NoiseModelSource.SYSTEM),
            userPresets = emptyList()
        )
        assertEquals(NoiseModelSource.SYSTEM, result.requestedSource)
        assertEquals(NoiseModelSource.OEM, result.effectiveSource)
        assertTrue(result.fallbackReason!!.contains("SYSTEM_MODEL_UNAVAILABLE"))
        assertArrayEquals(cameraS, result.resolved!!.effectiveS, 0.0)
    }

    @Test
    fun selectedUserPresetWinsAndCarriesName() {
        val preset = NoiseModelPreset(
            id = "user:test",
            displayName = "Imported test",
            origin = NoiseModelPresetOrigin.USER,
            model = model(711.0),
            importedAtEpochMs = 1L
        )
        val result = PhysicalNoiseModelCaptureResolver.resolve(
            lensId = "lens_v2_main",
            captureIso = 711,
            cfaPattern = NoiseModelCfaPattern.GBRG,
            cameraS = cameraS,
            cameraO = cameraO,
            settings = LensPhysicalNoiseModelSettings(
                source = NoiseModelSource.PRESET,
                selectedPresetId = preset.id
            ),
            userPresets = listOf(preset)
        )
        assertEquals(NoiseModelSource.PRESET, result.effectiveSource)
        assertEquals("Imported test", result.resolvedPresetName)
        assertEquals(711.0, result.resolved!!.isoStep!!, 0.0)
    }

    @Test
    fun missingPresetAndMissingOemIsExplicitlyUnavailable() {
        val result = PhysicalNoiseModelCaptureResolver.resolve(
            lensId = "lens_v2_main",
            captureIso = 400,
            cfaPattern = NoiseModelCfaPattern.RGGB,
            cameraS = DoubleArray(4),
            cameraO = DoubleArray(4),
            settings = LensPhysicalNoiseModelSettings(
                source = NoiseModelSource.PRESET,
                selectedPresetId = "user:missing"
            ),
            userPresets = emptyList()
        )
        assertFalse(result.available)
        assertTrue(result.fallbackReason!!.contains("AND_OEM_UNAVAILABLE"))
    }
}
