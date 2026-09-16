package com.bncam.core.quality

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PhysicalNoiseModelTest {
    private fun model() = ParametricNoiseModel(
        a = doubleArrayOf(0.01, 0.02, 0.03, 0.04),
        b = doubleArrayOf(0.1, 0.2, 0.3, 0.4),
        c = doubleArrayOf(0.0001, 0.0002, 0.0003, 0.0004),
        d = doubleArrayOf(0.01, 0.02, 0.03, 0.04),
        isoStep = 100.0
    )

    @Test
    fun dynamicIsoUsesExactAgcEquation() {
        val captureIso = 1600
        val cases = listOf(
            0.00 to 50.0,
            0.20 to 360.0,
            0.50 to 825.0,
            1.00 to 1600.0,
            2.00 to 3150.0
        )

        cases.forEach { (coefficient, expectedIso) ->
            assertEquals(
                expectedIso,
                NoiseModelResolver.effectiveNoiseModelIso(captureIso, coefficient),
                0.0
            )
        }
    }

    @Test
    fun dynamicIsoTruncatesToIntegerBeforeParametricEvaluationLikeAgcV12() {
        assertEquals(155.0, NoiseModelResolver.effectiveNoiseModelIso(401, 0.30), 0.0)
        assertEquals(275.0, NoiseModelResolver.effectiveNoiseModelIso(800, 0.30), 0.0)

        val resolved = NoiseModelResolver.resolve(
            NoiseModelResolver.Request.Parametric(
                lensId = "main",
                captureIso = 401,
                cfaPattern = NoiseModelCfaPattern.RGGB,
                source = NoiseModelSource.PRESET,
                model = model(),
                dynamicIsoEnabled = true,
                dynamicIsoCoefficient = 0.30,
                provenance = "AGC integer ISO parity"
            )
        )

        assertEquals(155.0, resolved.effectiveNoiseModelIso!!, 0.0)
        assertArrayEquals(
            doubleArrayOf(1.65, 3.30, 4.95, 6.60),
            resolved.effectiveS,
            1.0e-12
        )
    }

    @Test
    fun disabledDynamicIsoUsesCaptureIsoForEveryParametricSource() {
        listOf(
            NoiseModelSource.SYSTEM,
            NoiseModelSource.MANUAL,
            NoiseModelSource.PRESET
        ).forEach { source ->
            val resolved = NoiseModelResolver.resolve(
                NoiseModelResolver.Request.Parametric(
                    lensId = "main",
                    captureIso = 1600,
                    cfaPattern = NoiseModelCfaPattern.RGGB,
                    source = source,
                    model = model(),
                    dynamicIsoEnabled = false,
                    dynamicIsoCoefficient = 0.20,
                    provenance = "unit-test $source"
                )
            )

            assertEquals(1600.0, resolved.effectiveNoiseModelIso!!, 0.0)
            assertFalse(resolved.dynamicIsoEnabled)
            assertFalse(resolved.dynamicIsoCoefficientApplied)
            assertEquals(0.20, resolved.dynamicIsoCoefficient!!, 0.0)
        }
    }

    @Test
    fun enabledDynamicIsoAppliesToSystemManualAndPreset() {
        listOf(
            NoiseModelSource.SYSTEM,
            NoiseModelSource.MANUAL,
            NoiseModelSource.PRESET
        ).forEach { source ->
            val resolved = NoiseModelResolver.resolve(
                NoiseModelResolver.Request.Parametric(
                    lensId = "main",
                    captureIso = 1600,
                    cfaPattern = NoiseModelCfaPattern.RGGB,
                    source = source,
                    model = model(),
                    dynamicIsoEnabled = true,
                    dynamicIsoCoefficient = 0.20,
                    provenance = "unit-test $source"
                )
            )

            assertEquals(360.0, resolved.effectiveNoiseModelIso!!, 0.0)
            assertTrue(resolved.dynamicIsoEnabled)
            assertTrue(resolved.dynamicIsoCoefficientApplied)
        }
    }

    @Test
    fun parametricModelResolvesHandCalculatedSoOnce() {
        val resolved = NoiseModelResolver.resolve(
            NoiseModelResolver.Request.Parametric(
                lensId = "main",
                captureIso = 200,
                cfaPattern = NoiseModelCfaPattern.RGGB,
                source = NoiseModelSource.MANUAL,
                model = model(),
                dynamicIsoEnabled = true,
                dynamicIsoCoefficient = 1.0,
                provenance = "unit-test manual"
            )
        )

        assertEquals(200.0, resolved.effectiveNoiseModelIso!!, 0.0)
        assertArrayEquals(doubleArrayOf(2.1, 4.2, 6.3, 8.4), resolved.effectiveS, 1.0e-12)
        assertArrayEquals(doubleArrayOf(4.04, 8.08, 12.12, 16.16), resolved.effectiveO, 1.0e-12)
    }

    @Test
    fun negativeCoefficientsAreAllowedButResolvedSoNeverNegative() {
        val negativeModel = ParametricNoiseModel(
            a = doubleArrayOf(-1.0, -2.0, -3.0, -4.0),
            b = doubleArrayOf(-1.0, -1.0, -1.0, -1.0),
            c = doubleArrayOf(-1.0, -2.0, -3.0, -4.0),
            d = doubleArrayOf(-1.0, -1.0, -1.0, -1.0),
            isoStep = 100.0
        )

        val resolved = NoiseModelResolver.resolve(
            NoiseModelResolver.Request.Parametric(
                lensId = "tele",
                captureIso = 800,
                cfaPattern = NoiseModelCfaPattern.BGGR,
                source = NoiseModelSource.PRESET,
                model = negativeModel,
                dynamicIsoEnabled = false,
                provenance = "unit-test preset"
            )
        )

        assertArrayEquals(DoubleArray(4), resolved.effectiveS, 0.0)
        assertArrayEquals(DoubleArray(4), resolved.effectiveO, 0.0)
    }

    @Test
    fun oemUsesCamera2SoDirectlyAndNeverAppliesDynamicIso() {
        val resolved = NoiseModelResolver.resolve(
            NoiseModelResolver.Request.Oem(
                lensId = "main",
                captureIso = 1600,
                cfaPattern = NoiseModelCfaPattern.GRBG,
                // Mosaic GRBG order = Gr, R, B, Gb.
                sensorNoiseProfileMosaicSo = doubleArrayOf(
                    2.0, 12.0,
                    1.0, 11.0,
                    4.0, 14.0,
                    3.0, 13.0
                )
            )
        )

        assertEquals(NoiseModelSource.OEM, resolved.source)
        assertNull(resolved.effectiveNoiseModelIso)
        assertFalse(resolved.dynamicIsoEnabled)
        assertNull(resolved.dynamicIsoCoefficient)
        assertFalse(resolved.dynamicIsoCoefficientApplied)
        assertNull(resolved.isoStep)
        assertArrayEquals(doubleArrayOf(1.0, 2.0, 3.0, 4.0), resolved.effectiveS, 0.0)
        assertArrayEquals(doubleArrayOf(11.0, 12.0, 13.0, 14.0), resolved.effectiveO, 0.0)
    }

    @Test
    fun allFourBayerPatternsCanonicalizeToRGrGbB() {
        val cases = mapOf(
            NoiseModelCfaPattern.RGGB to doubleArrayOf(
                1.0, 11.0, 2.0, 12.0, 3.0, 13.0, 4.0, 14.0
            ),
            NoiseModelCfaPattern.GRBG to doubleArrayOf(
                2.0, 12.0, 1.0, 11.0, 4.0, 14.0, 3.0, 13.0
            ),
            NoiseModelCfaPattern.GBRG to doubleArrayOf(
                3.0, 13.0, 4.0, 14.0, 1.0, 11.0, 2.0, 12.0
            ),
            NoiseModelCfaPattern.BGGR to doubleArrayOf(
                4.0, 14.0, 3.0, 13.0, 2.0, 12.0, 1.0, 11.0
            )
        )

        cases.forEach { (pattern, mosaicSo) ->
            val profile = pattern.canonicalizeSensorNoiseProfile(mosaicSo)
            assertArrayEquals(doubleArrayOf(1.0, 2.0, 3.0, 4.0), profile.s, 0.0)
            assertArrayEquals(doubleArrayOf(11.0, 12.0, 13.0, 14.0), profile.o, 0.0)
        }
    }

    @Test
    fun dynamicIsoRejectsValuesOutsidePublishedRangeEvenWhenDisabled() {
        assertThrows(IllegalArgumentException::class.java) {
            NoiseModelResolver.effectiveNoiseModelIso(1600, -0.01)
        }
        assertThrows(IllegalArgumentException::class.java) {
            NoiseModelResolver.effectiveNoiseModelIso(1600, 2.01)
        }
        assertThrows(IllegalArgumentException::class.java) {
            NoiseModelResolver.Request.Parametric(
                lensId = "main",
                captureIso = 1600,
                cfaPattern = NoiseModelCfaPattern.RGGB,
                source = NoiseModelSource.SYSTEM,
                model = model(),
                dynamicIsoEnabled = false,
                dynamicIsoCoefficient = 2.01,
                provenance = "invalid coefficient"
            )
        }
    }

    @Test
    fun resolvedArraysAreDefensivelyCopied() {
        val resolved = NoiseModelResolver.resolve(
            NoiseModelResolver.Request.Oem(
                lensId = "main",
                captureIso = 100,
                cfaPattern = NoiseModelCfaPattern.RGGB,
                sensorNoiseProfileMosaicSo = doubleArrayOf(
                    1.0, 10.0, 2.0, 20.0, 3.0, 30.0, 4.0, 40.0
                )
            )
        )

        val external = resolved.effectiveS
        external[0] = 999.0
        assertEquals(1.0, resolved.effectiveS[0], 0.0)
    }
    @Test
    fun dynamicIsoKnownPresetValidationCaseResolvesExactlyOnce() {
        assertEquals(186.0, NoiseModelResolver.effectiveNoiseModelIso(557, 0.27), 0.0)

        val model = ParametricNoiseModel(
            a = doubleArrayOf(1.0e-6, 1.0e-6, 1.0e-6, 1.0e-6),
            b = doubleArrayOf(0.0, 0.0, 0.0, 0.0),
            c = doubleArrayOf(1.0e-10, 1.0e-10, 1.0e-10, 1.0e-10),
            d = doubleArrayOf(0.0, 0.0, 0.0, 0.0),
            isoStep = 100.0
        )
        val resolved = NoiseModelResolver.resolve(
            NoiseModelResolver.Request.Parametric(
                lensId = "phase2_known_case",
                captureIso = 557,
                cfaPattern = NoiseModelCfaPattern.RGGB,
                source = NoiseModelSource.PRESET,
                model = model,
                dynamicIsoEnabled = true,
                dynamicIsoCoefficient = 0.27,
                provenance = "phase2 contract test"
            )
        )

        assertEquals(186.0, resolved.effectiveNoiseModelIso!!, 0.0)
        assertTrue(resolved.dynamicIsoCoefficientApplied)
        assertEquals(0.27, resolved.dynamicIsoCoefficient!!, 0.0)
        // S is evaluated at ISO_NM=186. Re-applying k downstream would produce a different value.
        assertEquals(186.0e-6, resolved.effectiveS[0], 1.0e-15)
    }

}
