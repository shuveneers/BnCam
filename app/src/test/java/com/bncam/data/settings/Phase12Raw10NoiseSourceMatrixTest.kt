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
 * Phase-12 RAW10 source matrix. Physical source resolution happens before RAW10 unpack, therefore
 * the resolver itself must be container-neutral. These cases lock the exact source/Dynamic-ISO
 * results that the RAW10 shutter snapshot subsequently freezes and sends through JNI.
 */
class Phase12Raw10NoiseSourceMatrixTest {
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
        id = "user:phase12",
        displayName = "Phase 12 preset",
        origin = NoiseModelPresetOrigin.USER,
        model = model(),
        importedAtEpochMs = 1L
    )

    @Test
    fun `oem system manual preset all resolve for raw10 shutter authority`() {
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
    fun `dynamic iso is applied once upstream for every parametric raw10 source`() {
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
    fun `oem remains direct camera2 SO and ignores dynamic iso`() {
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
