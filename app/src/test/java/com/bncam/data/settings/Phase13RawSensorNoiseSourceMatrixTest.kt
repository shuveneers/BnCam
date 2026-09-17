package com.bncam.data.settings

import com.bncam.core.quality.NoiseModelCfaPattern
import com.bncam.core.quality.NoiseModelSource
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase-13 RAW_SENSOR source matrix. Source resolution is deliberately container-neutral: the
 * exact same shutter-time result validated for RAW10 must be frozen before RAW_SENSOR ingest.
 */
class Phase13RawSensorNoiseSourceMatrixTest {
    private val cameraS = doubleArrayOf(0.001, 0.002, 0.003, 0.004)
    private val cameraO = doubleArrayOf(0.00001, 0.00002, 0.00003, 0.00004)

    private fun model() = PersistedParametricNoiseModel(
        a = listOf(0.0001, 0.0002, 0.0003, 0.0004),
        b = listOf(0.001, 0.002, 0.003, 0.004),
        c = listOf(0.000001, 0.000002, 0.000003, 0.000004),
        d = listOf(0.00001, 0.00002, 0.00003, 0.00004),
        isoStep = 100.0
    )

    private val preset = NoiseModelPreset(
        id = "user:phase13",
        displayName = "Phase 13 preset",
        origin = NoiseModelPresetOrigin.USER,
        model = model(),
        importedAtEpochMs = 1L
    )

    @Test
    fun `oem system manual preset all resolve before raw sensor ingest`() {
        val settingsBySource = mapOf(
            NoiseModelSource.OEM to LensPhysicalNoiseModelSettings(source = NoiseModelSource.OEM),
            NoiseModelSource.SYSTEM to LensPhysicalNoiseModelSettings(source = NoiseModelSource.SYSTEM, systemModel = model()),
            NoiseModelSource.MANUAL to LensPhysicalNoiseModelSettings(source = NoiseModelSource.MANUAL, manualModel = model()),
            NoiseModelSource.PRESET to LensPhysicalNoiseModelSettings(source = NoiseModelSource.PRESET, selectedPresetId = preset.id)
        )

        settingsBySource.forEach { (source, settings) ->
            val resolved = PhysicalNoiseModelCaptureResolver.resolve(
                lensId = "lens_v2_main",
                captureIso = 800,
                cfaPattern = NoiseModelCfaPattern.RGGB,
                cameraS = cameraS,
                cameraO = cameraO,
                settings = settings,
                userPresets = listOf(preset)
            )
            assertTrue("$source must be available", resolved.available)
            assertEquals(source, resolved.requestedSource)
            assertEquals(source, resolved.effectiveSource)
            assertFalse("$source must not fallback", resolved.usedFallback)
            assertNotNull(resolved.resolved)
        }
    }

    @Test
    fun `dynamic iso remains one upstream operation for raw sensor`() {
        listOf(NoiseModelSource.SYSTEM, NoiseModelSource.MANUAL, NoiseModelSource.PRESET).forEach { source ->
            val settings = LensPhysicalNoiseModelSettings(
                source = source,
                systemModel = if (source == NoiseModelSource.SYSTEM) model() else null,
                manualModel = if (source == NoiseModelSource.MANUAL) model() else null,
                selectedPresetId = if (source == NoiseModelSource.PRESET) preset.id else null,
                dynamicIsoEnabled = true,
                dynamicIsoCoefficient = 0.27
            )
            val resolved = PhysicalNoiseModelCaptureResolver.resolve(
                lensId = "lens_v2_main",
                captureIso = 557,
                cfaPattern = NoiseModelCfaPattern.RGGB,
                cameraS = cameraS,
                cameraO = cameraO,
                settings = settings,
                userPresets = listOf(preset)
            ).resolved!!

            assertEquals(186.0, resolved.effectiveNoiseModelIso!!, 0.0)
            assertTrue(resolved.dynamicIsoEnabled)
            assertTrue(resolved.dynamicIsoCoefficientApplied)
            assertEquals(0.27, resolved.dynamicIsoCoefficient!!, 0.0)
        }
    }

    @Test
    fun `oem raw sensor remains direct camera2 SO`() {
        val resolved = PhysicalNoiseModelCaptureResolver.resolve(
            lensId = "lens_v2_main",
            captureIso = 557,
            cfaPattern = NoiseModelCfaPattern.RGGB,
            cameraS = cameraS,
            cameraO = cameraO,
            settings = LensPhysicalNoiseModelSettings(
                source = NoiseModelSource.OEM,
                dynamicIsoEnabled = true,
                dynamicIsoCoefficient = 0.27
            ),
            userPresets = emptyList()
        ).resolved!!

        assertArrayEquals(cameraS, resolved.effectiveS, 0.0)
        assertArrayEquals(cameraO, resolved.effectiveO, 0.0)
        assertEquals(null, resolved.effectiveNoiseModelIso)
        assertFalse(resolved.dynamicIsoEnabled)
        assertFalse(resolved.dynamicIsoCoefficientApplied)
    }
}
