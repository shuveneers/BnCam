package com.bncam.core.quality

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NumericalColorChainTest {

    @Test
    fun testOptionBEquivalence() {
        val mTotal = floatArrayOf(
            1.5f, -0.3f, -0.1f,
            -0.2f, 1.4f, -0.1f,
            -0.05f, -0.15f, 1.3f
        )
        val w3 = floatArrayOf(1.8f, 1.0f, 1.4f)
        val invW3 = RawColorTransformEngine.diagonal3x3(floatArrayOf(1.0f / 1.8f, 1.0f, 1.0f / 1.4f))
        val mPost = RawColorTransformEngine.multiply3x3(mTotal, invW3)!!

        val sampleP = floatArrayOf(0.4f, 0.5f, 0.3f)
        val isEquivalent = RawColorTransformEngine.verifyOptionBEquivalence(
            mTotal = mTotal,
            mPost = mPost,
            w3 = w3,
            sampleVector = sampleP,
            tolerance = 1.0e-4f
        )
        assertTrue("Option B matrix compensation must be mathematically equivalent to uncompensated M_total * P", isEquivalent)
    }

    @Test
    fun testPlanckianBoundaryContinuityAt2222K() {
        val belowBoundary = RawColorTransformEngine.calculateChromaticity(2222, "PLANCKIAN_BLACKBODY")
        val aboveBoundary = RawColorTransformEngine.calculateChromaticity(2223, "PLANCKIAN_BLACKBODY")

        assertEquals(belowBoundary.x, aboveBoundary.x, 0.005f)
        assertEquals(belowBoundary.y, aboveBoundary.y, 0.005f)
    }

    @Test
    fun testPlanckianBoundaryContinuityAt4000K() {
        val boundaryP = RawColorTransformEngine.calculateChromaticity(4000, "PLANCKIAN_BLACKBODY")
        val boundaryD = RawColorTransformEngine.calculateChromaticity(4000, "CIE_DAYLIGHT")

        assertTrue("4000K chromaticity x must be finite", boundaryP.x.isFinite())
        assertTrue("4000K chromaticity y must be finite", boundaryP.y.isFinite())
        assertTrue("4000K daylight x must be finite", boundaryD.x.isFinite())
        assertTrue("4000K daylight y must be finite", boundaryD.y.isFinite())
    }

    @Test
    fun testD50WhiteConvertsToNeutralLinearSrgb() {
        // ICC/CIE D50 white (Y=1). ForwardMatrix output is defined in this XYZ D50 domain.
        val d50 = floatArrayOf(0.96422f, 1.0f, 0.82521f)
        val rgb = RawColorTransformEngine.multiplyMatrixVector(
            RawColorTransformEngine.xyzD50ToLinearSrgbMatrix(),
            d50
        )

        assertEquals(1.0f, rgb[0], 0.0025f)
        assertEquals(1.0f, rgb[1], 0.0025f)
        assertEquals(1.0f, rgb[2], 0.0025f)
    }

    @Test
    fun testD50ToLinearSrgbMatrixIsFinite() {
        val m = RawColorTransformEngine.xyzD50ToLinearSrgbMatrix()
        assertEquals(9, m.size)
        assertTrue(m.all { it.isFinite() })
    }

    @Test
    fun testReferenceIlluminantKelvinMapping() {
        assertEquals(2856, RawColorTransformEngine.mapExifIlluminantToKelvin(17)) // Standard Light A
        assertEquals(6504, RawColorTransformEngine.mapExifIlluminantToKelvin(21)) // D65
        assertEquals(5000, RawColorTransformEngine.mapExifIlluminantToKelvin(23)) // D50
        assertEquals(3200, RawColorTransformEngine.mapExifIlluminantToKelvin(24)) // ISO Studio Tungsten
    }

    @Test
    fun testCfaArrangementDescriptors() {
        val rggb = CfaArrangementDescriptor.from(0) // RGGB
        assertTrue(rggb is CfaArrangementDescriptor.Bayer)
        assertEquals("RGGB", rggb.cfaName)
        assertEquals(4, (rggb as CfaArrangementDescriptor.Bayer).channels.size)
        assertEquals(ColorPlane.RED, rggb.channels[0].colorPlane)
        assertEquals(ColorPlane.BLUE, rggb.channels[3].colorPlane)

        val mono = CfaArrangementDescriptor.from(5) // MONO
        assertTrue(mono is CfaArrangementDescriptor.Monochrome)
        assertEquals("MONO_NON_BAYER", mono.cfaName)
        assertEquals(ColorPlane.MONO, (mono as CfaArrangementDescriptor.Monochrome).channel.colorPlane)

        val rgb = CfaArrangementDescriptor.from(4) // RGB
        assertTrue(rgb is CfaArrangementDescriptor.Unsupported)
        assertFalse(rgb.isSupportedBayer)
        assertFalse(rgb.isSupportedMono)
    }
}
