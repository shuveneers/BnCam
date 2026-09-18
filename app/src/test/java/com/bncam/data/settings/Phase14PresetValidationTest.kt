package com.bncam.data.settings

import com.bncam.core.quality.NoiseModelCfaPattern
import com.bncam.core.quality.NoiseModelSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Golden validation for the BnCam built-in sensor-class noise presets. */
class Phase14PresetValidationTest {
    private val cameraS = doubleArrayOf(0.001, 0.002, 0.003, 0.004)
    private val cameraO = doubleArrayOf(0.00001, 0.00002, 0.00003, 0.00004)

    private fun resolve(
        presetId: String,
        captureIso: Int,
        dynamicIsoEnabled: Boolean = false,
        coefficient: Double = 1.0
    ) = PhysicalNoiseModelCaptureResolver.resolve(
        lensId = "lens_v2_main",
        captureIso = captureIso,
        cfaPattern = NoiseModelCfaPattern.RGGB,
        cameraS = cameraS,
        cameraO = cameraO,
        settings = LensPhysicalNoiseModelSettings(
            source = NoiseModelSource.PRESET,
            selectedPresetId = presetId,
            dynamicIsoEnabled = dynamicIsoEnabled,
            dynamicIsoCoefficient = coefficient
        ),
        userPresets = emptyList()
    )

    @Test
    fun `all twenty builtins resolve without fallback`() {
        BnCamNoiseModelPresets.all.forEach { preset ->
            val result = resolve(preset.id, 800)
            assertTrue(result.available)
            assertFalse(result.usedFallback)
            assertEquals(NoiseModelSource.PRESET, result.effectiveSource)
            assertEquals(preset.displayName, result.resolvedPresetName)
        }
    }

    @Test
    fun `each lens group increases monotonically from low to high noise`() {
        NoiseModelPresetGroup.entries.forEach { group ->
            val resolved = BnCamNoiseModelPresets.inGroup(group).map { preset ->
                requireNotNull(resolve(preset.id, 1600).resolved)
            }
            val s = resolved.map { it.effectiveS.average() }
            val o = resolved.map { it.effectiveO.average() }
            assertTrue(s.zipWithNext().all { (a, b) -> b > a })
            assertTrue(o.zipWithNext().all { (a, b) -> b >= a })
        }
    }

    @Test
    fun `dynamic ISO is applied exactly once before model evaluation`() {
        val dynamic = resolve("bncam:main:3", 6400, true, 0.27).resolved!!
        val direct = resolve("bncam:main:3", 1764, false).resolved!!
        assertEquals(1764.0, dynamic.effectiveNoiseModelIso!!, 0.0)
        assertTrue(dynamic.dynamicIsoCoefficientApplied)
        assertTrue(dynamic.effectiveS.contentEquals(direct.effectiveS))
        assertTrue(dynamic.effectiveO.contentEquals(direct.effectiveO))
    }

    @Test
    fun `lens classes are not aliases of one model`() {
        val main = resolve("bncam:main:3", 1600).resolved!!
        val tele = resolve("bncam:tele:3", 1600).resolved!!
        assertFalse(main.effectiveS.contentEquals(tele.effectiveS))
        assertFalse(main.effectiveO.contentEquals(tele.effectiveO))
    }
}
