package com.bncam.core.quality

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraCapabilityPolicyTest {
    private fun camera(minimumFocus: Float? = 2.0f) = CameraCapabilitySnapshot(
        awbModes = IntArray(0), edgeModes = IntArray(0), noiseReductionModes = IntArray(0),
        hotPixelModes = IntArray(0), shadingModes = IntArray(0), aberrationModes = IntArray(0),
        supportsLensShadingMap = true, supportsTonemapCurve = true, tonemapMaxCurvePoints = 64,
        supportsDistortionCorrection = true, supportsRaw = true,
        pipelineMaxDepth = 8, partialResultCount = 1, maxDigitalZoom = 4f,
        hyperfocalDistanceDiopters = 0.5f, minimumFocusDistanceDiopters = minimumFocus,
        focalLengthsMm = floatArrayOf(5f), apertures = floatArrayOf(1.8f),
        sensitivityRange = 50..6400, exposureTimeRangeNs = 1000L..1_000_000_000L
    )

    private fun implementation(
        raw: Boolean = true,
        shading: Boolean = true,
        distortion: Boolean = true,
        tonemap: Boolean = true,
        focus: Boolean = true
    ) = BnCamCapabilityImplementation(raw, shading, distortion, tonemap, focus)

    @Test
    fun `eligibility requires camera fact and explicit BnCam implementation`() {
        val policy = CameraCapabilityPolicy.resolve(camera(), implementation())
        CameraCapabilityFeature.values().forEach { assertTrue(policy.decision(it).eligible) }
    }

    @Test
    fun `camera capability absence wins before implementation availability`() {
        val missingRaw = camera().copy(supportsRaw = false)
        val decision = CameraCapabilityPolicy.resolve(missingRaw, implementation()).rawPipeline
        assertFalse(decision.eligible)
        assertEquals("CAMERA_CAPABILITY_UNAVAILABLE", decision.reason)
    }

    @Test
    fun `BnCam implementation absence cannot be inferred from camera support`() {
        val decision = CameraCapabilityPolicy.resolve(
            camera(), implementation(distortion = false)
        ).distortionCorrection
        assertTrue(decision.cameraFactAvailable)
        assertFalse(decision.bnCamImplementationAvailable)
        assertFalse(decision.eligible)
        assertEquals("BNCAM_IMPLEMENTATION_UNAVAILABLE", decision.reason)
    }

    @Test
    fun `fixed focus camera does not become manual focus capable`() {
        val zero = CameraCapabilityPolicy.resolve(camera(minimumFocus = 0f), implementation()).manualFocus
        val absent = CameraCapabilityPolicy.resolve(camera(minimumFocus = null), implementation()).manualFocus
        assertFalse(zero.eligible)
        assertFalse(absent.eligible)
    }

    @Test
    fun `policy exposes explicit authority and reason for diagnostics`() {
        val decision = CameraCapabilityPolicy.resolve(camera(), implementation()).rawPipeline
        assertEquals(CameraCapabilityPolicy.AUTHORITY, decision.authority)
        assertEquals("ELIGIBLE_CAMERA_FACT_AND_BNCAM_IMPLEMENTATION", decision.reason)
        assertTrue(CameraCapabilityPolicy.resolve(camera(), implementation()).debugPairs().isNotEmpty())
    }
}
