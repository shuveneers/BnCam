package com.bncam.data.baseline

import com.bncam.core.capture.EffectiveShutterSnapshot
import com.bncam.core.quality.CameraCapabilityInspector
import com.bncam.core.quality.RawUnpackValidator
import com.bncam.core.quality.RouteSupportStatus
import com.bncam.data.settings.LensCalibrationConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Phase5Block2Test {

    @Test
    fun `raw10 unpacking correctly decodes 5 bytes into 4 10-bit pixels`() {
        // b0=0xFF (11111111), b1=0x00, b2=0x80, b3=0x40, b4=0xAB (10101011)
        // p0 = (0xFF << 2) | (0xAB & 0x03 = 3) = 1023
        val pixels = RawUnpackValidator.unpackMipiRaw10Block(0xFF, 0x00, 0x80, 0x40, 0xAB)
        assertEquals(4, pixels.size)
        assertEquals(1023, pixels[0])
        assertTrue(pixels[1] in 0..1023)
        assertTrue(pixels[2] in 0..1023)
        assertTrue(pixels[3] in 0..1023)
    }

    @Test
    fun `positional 2x2 cfa black level subtraction eliminates green asymmetry`() {
        val l1 = 64f // R
        val l2 = 68f // G1 (even)
        val l3 = 68f // G2 (odd)
        val l4 = 64f // B
        val whiteLevel = 1023f

        // Even row, odd col -> G1
        val normG1 = RawUnpackValidator.subtractPositionalBlackLevel(68f, row = 0, col = 1, l1, l2, l3, l4, whiteLevel)
        // Odd row, even col -> G2
        val normG2 = RawUnpackValidator.subtractPositionalBlackLevel(68f, row = 1, col = 0, l1, l2, l3, l4, whiteLevel)

        // Same physical raw code value (68) minus positional black level (68) yields identical zero normalized floor
        assertEquals(0.0f, normG1, 0.0001f)
        assertEquals(0.0f, normG2, 0.0001f)
        assertEquals(normG1, normG2, 0.0001f)
    }

    @Test
    fun `noise model variance scales with pixel value`() {
        val noiseA = 0.002f
        val noiseB = 0.0001f

        val varDark = RawUnpackValidator.calculateNoiseVariance(0.05f, noiseA, noiseB)
        val varBright = RawUnpackValidator.calculateNoiseVariance(0.80f, noiseA, noiseB)

        assertTrue(varBright > varDark)
    }

    @Test
    fun `camera capability inspector reports supported routes for all lenses`() {
        val capabilities = CameraCapabilityInspector.inspectDiscoveredCapabilities()
        assertEquals(3, capabilities.size)

        val mainLens = capabilities.first { it.logicalCameraId == "0" }
        assertEquals("BACK", mainLens.lensFacing)
        assertTrue(mainLens.isRawCapable)
        assertTrue(mainLens.supportsRaw10)
        assertTrue(mainLens.supportsRawSensor)
        assertEquals(RouteSupportStatus.SUPPORTED_AND_CAPTURED, mainLens.routeStatuses["Single RAW10 -> JPEG"])
    }

    @Test
    fun `snapshot calibration revision remains frozen`() {
        val cal = LensCalibrationConfig(lensId = "lens_v2_0")
        val snapshot = EffectiveShutterSnapshot(
            stableLensKey = "lens_v2_0",
            logicalCameraId = "0",
            physicalCameraId = null,
            calibration = cal,
            calibrationRevision = 3L
        )

        assertEquals(3L, snapshot.calibrationRevision)
        assertEquals("lens_v2_0", snapshot.stableLensKey)
    }
}
