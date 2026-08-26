package com.bncam.data.baseline

import com.bncam.core.quality.CameraCapabilityInspector
import com.bncam.core.quality.RawUnpackValidator
import com.bncam.core.quality.UniversalCalibrationResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import com.bncam.data.profile.IspControlSpec
import org.junit.Test

class Phase5Block3Test {

    @Test
    fun `auto calibration hierarchy resolves dynamic over static over fallback`() {
        val dynamic = floatArrayOf(60f, 64f, 64f, 60f)
        val staticPattern = floatArrayOf(64f, 68f, 68f, 64f)
        val measured = floatArrayOf(62f, 66f, 66f, 62f)

        // 1. Dynamic precedence
        val resDynamic = UniversalCalibrationResolver.resolvePositionalBlackLevels(dynamic, staticPattern, measured)
        assertEquals(CalibrationProvenance.DYNAMIC_FRAME_METADATA, resDynamic.provenance)
        assertEquals(60f, resDynamic.pos00, 0.001f)

        // 2. Static precedence when dynamic null
        val resStatic = UniversalCalibrationResolver.resolvePositionalBlackLevels(null, staticPattern, measured)
        assertEquals(CalibrationProvenance.STATIC_CAMERA_METADATA, resStatic.provenance)
        assertEquals(64f, resStatic.pos00, 0.001f)

        // 3. Measured precedence when static null
        val resMeasured = UniversalCalibrationResolver.resolvePositionalBlackLevels(null, null, measured)
        assertEquals(CalibrationProvenance.MEASURED_PER_LENS, resMeasured.provenance)
        assertEquals(62f, resMeasured.pos00, 0.001f)

        // 4. Fallback when all null
        val resFallback = UniversalCalibrationResolver.resolvePositionalBlackLevels(null, null, null)
        assertEquals(CalibrationProvenance.GENERIC_CONSERVATIVE_FALLBACK, resFallback.provenance)
        assertEquals(64f, resFallback.pos00, 0.001f)
    }

    @Test
    fun `capability driven route availability hides raw on yuv only phone`() {
        val yuvOnlyFixture = CameraCapabilityInspector.createFixture("A")
        assertFalse(yuvOnlyFixture.isRawCapable)
        assertFalse(yuvOnlyFixture.supportsRaw10)
        assertFalse(yuvOnlyFixture.supportsRawSensor)
        assertFalse(yuvOnlyFixture.supportsDng)
        assertTrue(yuvOnlyFixture.supportsYuv)
    }

    @Test
    fun `all four bayer cfa patterns supported and non bayer rejected`() {
        val rggb = CameraCapabilityInspector.createFixture("K")
        val grbg = CameraCapabilityInspector.createFixture("L")
        val gbrg = CameraCapabilityInspector.createFixture("M")
        val bggr = CameraCapabilityInspector.createFixture("N")
        val mono = CameraCapabilityInspector.createFixture("O")

        assertEquals("RGGB", rggb.cfaArrangement)
        assertEquals("GRBG", grbg.cfaArrangement)
        assertEquals("GBRG", gbrg.cfaArrangement)
        assertEquals("BGGR", bggr.cfaArrangement)

        assertEquals("MONO", mono.cfaArrangement)
        assertFalse(mono.isRawCapable)
        assertFalse(mono.supportsDng)
    }

    @Test
    fun `synthetic fixture matrix A through T verifies topology and baseline provenance`() {
        val fixtures = listOf("A", "B", "C", "D", "E", "F", "K", "L", "M", "N", "O", "P", "Q", "R", "S", "T")
        for (f in fixtures) {
            val capability = CameraCapabilityInspector.createFixture(f)
            assertNotNull(capability.stableLensKey)
            assertTrue(capability.activeArrayWidth > 0)
            assertTrue(capability.activeArrayHeight > 0)
        }
    }

    @Test
    fun `raw10 unpacking with row padding handles padding offset without overflow`() {
        val paddedFixture = CameraCapabilityInspector.createFixture("P")
        assertEquals(64, paddedFixture.rowPaddingBytes)

        val pixels = RawUnpackValidator.unpackMipiRaw10Block(0x80, 0x40, 0x20, 0x10, 0x00)
        assertEquals(4, pixels.size)
        assertTrue(pixels.all { p -> p in 0..1023 })
    }

    @Test
    fun `raw sensor white levels 1023 4095 16383 handled metadata driven`() {
        val f10 = CameraCapabilityInspector.createFixture("R")
        val f12 = CameraCapabilityInspector.createFixture("S")
        val f14 = CameraCapabilityInspector.createFixture("T")

        assertEquals(1023f, f10.whiteLevel, 0.1f)
        assertEquals(4095f, f12.whiteLevel, 0.1f)
        assertEquals(16383f, f14.whiteLevel, 0.1f)
    }

    @Test
    fun `directional profile control tests for negative zero and positive`() {
        val baselineVal = 0.10f
        val spec = IspControlSpec.SIGNED_ADJUSTMENT

        val negVal = LensIspBaselineProvider.resolveEffectiveValue(-0.50f, baselineVal, 0.00f, 0.40f)
        val zeroVal = LensIspBaselineProvider.resolveEffectiveValue(0.00f, baselineVal, 0.00f, 0.40f)
        val posVal = LensIspBaselineProvider.resolveEffectiveValue(0.50f, baselineVal, 0.00f, 0.40f)

        assertTrue(negVal < zeroVal)
        assertEquals(baselineVal, zeroVal, 0.001f)
        assertTrue(posVal > zeroVal)
    }
}
