package com.bncam.core.quality

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraCapabilityInspectorTest {
    private fun inspect(
        lensShading: IntArray? = intArrayOf(0),
        distortion: IntArray? = intArrayOf(0),
        capabilities: IntArray? = intArrayOf(3),
        rawCapability: Int = 3,
        maxZoom: Float? = 4.0f,
        minimumFocus: Float? = 2.0f,
        sensitivityLower: Int? = 50,
        sensitivityUpper: Int? = 6400,
        exposureLower: Long? = 1000L,
        exposureUpper: Long? = 1_000_000_000L
    ) = CameraCapabilityInspector.inspect(
        awbModes = intArrayOf(0, 1),
        edgeModes = intArrayOf(0, 1),
        noiseReductionModes = intArrayOf(0, 1),
        hotPixelModes = intArrayOf(0, 1),
        shadingModes = intArrayOf(0, 1),
        aberrationModes = intArrayOf(0, 1),
        lensShadingMapModes = lensShading,
        tonemapMaxCurvePoints = 64,
        distortionCorrectionModes = distortion,
        capabilities = capabilities,
        pipelineMaxDepth = 8,
        partialResultCount = 2,
        maxDigitalZoom = maxZoom,
        hyperfocalDistanceDiopters = 0.5f,
        minimumFocusDistanceDiopters = minimumFocus,
        focalLengthsMm = floatArrayOf(5.0f),
        apertures = floatArrayOf(1.8f),
        sensitivityLower = sensitivityLower,
        sensitivityUpper = sensitivityUpper,
        exposureTimeLowerNs = exposureLower,
        exposureTimeUpperNs = exposureUpper,
        rawCapabilityConstant = rawCapability
    )

    @Test
    fun `off-only lens shading is not advertised as usable support`() {
        assertFalse(inspect(lensShading = intArrayOf(0)).supportsLensShadingMap)
        assertTrue(inspect(lensShading = intArrayOf(0, 1)).supportsLensShadingMap)
    }

    @Test
    fun `off-only distortion correction is not advertised as usable support`() {
        assertFalse(inspect(distortion = intArrayOf(0)).supportsDistortionCorrection)
        assertTrue(inspect(distortion = intArrayOf(0, 2)).supportsDistortionCorrection)
    }

    @Test
    fun `raw support requires explicit Camera2 capability membership`() {
        assertTrue(inspect(capabilities = intArrayOf(1, 3, 5), rawCapability = 3).supportsRaw)
        assertFalse(inspect(capabilities = intArrayOf(1, 5), rawCapability = 3).supportsRaw)
    }

    @Test
    fun `snapshot arrays do not alias caller-owned arrays`() {
        val awb = intArrayOf(0, 1)
        val focal = floatArrayOf(5.0f)
        val snapshot = CameraCapabilityInspector.inspect(
            awb, null, null, null, null, null,
            intArrayOf(1), 64, intArrayOf(1), intArrayOf(3),
            8, 2, 4f, 0.5f, 2f, focal, floatArrayOf(1.8f),
            50, 6400, 1000L, 1_000_000_000L, 3
        )
        awb[1] = 99
        focal[0] = 99f
        assertArrayEquals(intArrayOf(0, 1), snapshot.awbModes)
        assertArrayEquals(floatArrayOf(5.0f), snapshot.focalLengthsMm, 0.0f)
    }

    @Test
    fun `malformed scalar capabilities fail closed to safe facts`() {
        val snapshot = CameraCapabilityInspector.inspect(
            null, null, null, null, null, null,
            null, -4, null, null,
            -2, 0, Float.NaN, -1f, Float.NaN,
            floatArrayOf(-1f, Float.NaN), floatArrayOf(0f, Float.POSITIVE_INFINITY),
            6400, 50, 10_000L, 100L, 3
        )
        assertEquals(0, snapshot.pipelineMaxDepth)
        assertEquals(1, snapshot.partialResultCount)
        assertEquals(1.0f, snapshot.maxDigitalZoom, 0.0f)
        assertNull(snapshot.hyperfocalDistanceDiopters)
        assertNull(snapshot.minimumFocusDistanceDiopters)
        assertTrue(snapshot.focalLengthsMm.isEmpty())
        assertTrue(snapshot.apertures.isEmpty())
        assertNull(snapshot.sensitivityRange)
        assertNull(snapshot.exposureTimeRangeNs)
        assertFalse(snapshot.supportsTonemapCurve)
    }

    @Test
    fun `valid physical ranges remain unchanged`() {
        val snapshot = inspect()
        assertEquals(50..6400, snapshot.sensitivityRange)
        assertEquals(1000L..1_000_000_000L, snapshot.exposureTimeRangeNs)
        assertEquals(4.0f, snapshot.maxDigitalZoom, 0.0f)
    }
}
