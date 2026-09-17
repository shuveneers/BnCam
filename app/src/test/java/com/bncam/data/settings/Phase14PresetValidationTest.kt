package com.bncam.data.settings

import com.bncam.core.quality.NoiseModelCfaPattern
import com.bncam.core.quality.NoiseModelSource
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Phase-14 golden validation for built-in AGC noise-model presets. */
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
    fun `preset 80 OV64B follows stable golden curve from low to very high ISO`() {
        val expected = listOf(
            100 to Pair(
                doubleArrayOf(8.441137034709491e-05, 6.471180800926591e-05, 6.444604785655064e-05, 8.458442754355936e-05),
                doubleArrayOf(2.1223791208976275e-07, 1.5677278237432856e-07, 1.5580558717399613e-07, 0.0)
            ),
            800 to Pair(
                doubleArrayOf(5.933707994705203e-04, 3.946267902730134e-04, 3.9148906317914894e-04, 5.27086373618439e-04),
                doubleArrayOf(2.1623681642263606e-06, 4.142469622083064e-06, 4.1768588327960944e-06, 1.6111829327241503e-06)
            ),
            1600 to Pair(
                doubleArrayOf(1.1750387184687207e-03, 7.716724842887248e-04, 7.652525092621185e-04, 1.0328028834183014e-03),
                doubleArrayOf(8.107218966975969e-06, 1.629017882253886e-05, 1.6432307427520047e-05, 6.5353400591791005e-06)
            ),
            6400 to Pair(
                doubleArrayOf(4.6650462324579235e-03, 3.0339466483829936e-03, 3.0078331857599353e-03, 4.067101942217476e-03),
                doubleArrayOf(1.297155034716155e-04, 2.6064286116062176e-04, 2.6291691884032075e-04, 1.0456544094686561e-04)
            )
        )

        var previousMeanS = -1.0
        var previousMeanO = -1.0
        expected.forEach { (iso, expectedSo) ->
            val result = resolve("agc_v12:80", iso)
            assertTrue(result.available)
            assertFalse(result.usedFallback)
            assertEquals(NoiseModelSource.PRESET, result.effectiveSource)
            assertEquals("80. NubiaZ50Ultra_OV64B", result.resolvedPresetName)
            val resolved = result.resolved!!
            assertEquals(iso.toDouble(), resolved.effectiveNoiseModelIso!!, 0.0)
            assertArrayEquals(expectedSo.first, resolved.effectiveS, 1e-15)
            assertArrayEquals(expectedSo.second, resolved.effectiveO, 1e-15)

            val meanS = resolved.effectiveS.average()
            val meanO = resolved.effectiveO.average()
            assertTrue("shot-noise prediction must grow across the validation ISO sweep", meanS > previousMeanS)
            assertTrue("read-noise prediction must grow across the validation ISO sweep", meanO > previousMeanO)
            previousMeanS = meanS
            previousMeanO = meanO
        }
    }

    @Test
    fun `preset 80 remains continuous across its digital gain transition`() {
        val below = resolve("agc_v12:80", 1592).resolved!!
        val at = resolve("agc_v12:80", 1593).resolved!!
        val above = resolve("agc_v12:80", 1594).resolved!!

        fun relativeStep(a: Double, b: Double): Double = kotlin.math.abs(b - a) / kotlin.math.max(kotlin.math.abs(a), 1e-18)
        for (channel in 0 until 4) {
            assertTrue(relativeStep(below.effectiveS[channel], at.effectiveS[channel]) < 0.01)
            assertTrue(relativeStep(at.effectiveS[channel], above.effectiveS[channel]) < 0.01)
            if (at.effectiveO[channel] > 0.0) {
                assertTrue(relativeStep(below.effectiveO[channel], at.effectiveO[channel]) < 0.02)
                assertTrue(relativeStep(at.effectiveO[channel], above.effectiveO[channel]) < 0.02)
            }
        }
    }

    @Test
    fun `dynamic ISO applies once before preset 80 evaluation`() {
        val dynamic = resolve(
            presetId = "agc_v12:80",
            captureIso = 6400,
            dynamicIsoEnabled = true,
            coefficient = 0.27
        ).resolved!!
        val directAtEffectiveIso = resolve(
            presetId = "agc_v12:80",
            captureIso = 1764,
            dynamicIsoEnabled = false
        ).resolved!!

        assertEquals(1764.0, dynamic.effectiveNoiseModelIso!!, 0.0)
        assertTrue(dynamic.dynamicIsoCoefficientApplied)
        assertEquals(0.27, dynamic.dynamicIsoCoefficient!!, 0.0)
        assertArrayEquals(directAtEffectiveIso.effectiveS, dynamic.effectiveS, 0.0)
        assertArrayEquals(directAtEffectiveIso.effectiveO, dynamic.effectiveO, 0.0)
    }

    @Test
    fun `different built in presets produce different physical predictions at same ISO`() {
        val ov64b = resolve("agc_v12:80", 1600).resolved!!
        val imx787 = resolve("agc_v12:78", 1600).resolved!!

        assertNotEquals(ov64b.provenance, imx787.provenance)
        assertFalse(ov64b.effectiveS.contentEquals(imx787.effectiveS))
        assertFalse(ov64b.effectiveO.contentEquals(imx787.effectiveO))
        assertTrue(kotlin.math.abs(ov64b.effectiveS.average() - imx787.effectiveS.average()) > 1e-5)
    }
}
