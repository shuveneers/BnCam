package com.bncam

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bncam.core.capture.OutputPolicy
import com.bncam.core.output.CapturePublicationResult
import com.bncam.core.output.PublicationPolicyResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PublicationPolicyDeviceTest {

    @Test
    fun verifyPublicationPolicyOnDeviceContext() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertNotNull(context)

        // 1. YUV + JPEG
        val yuvJpegOutputs = PublicationPolicyResolver.resolveStrings(
            outputPolicy = OutputPolicy.JPEG,
            jpegSucceeded = true,
            jpegUri = "content://media/external/images/media/device_yuv_jpeg",
            dngSucceeded = false,
            dngUri = null
        )
        assertEquals(CapturePublicationResult.FULL_SUCCESS, yuvJpegOutputs.publicationResult)
        assertEquals("content://media/external/images/media/device_yuv_jpeg", yuvJpegOutputs.thumbnailUri)

        // 2. RAW10 + JPEG_PLUS_RAW
        val raw10JpegPlusRawOutputs = PublicationPolicyResolver.resolveStrings(
            outputPolicy = OutputPolicy.JPEG_PLUS_RAW,
            jpegSucceeded = true,
            jpegUri = "content://media/external/images/media/device_raw10_jpeg",
            dngSucceeded = true,
            dngUri = "content://media/external/images/media/device_raw10_dng"
        )
        assertEquals(CapturePublicationResult.FULL_SUCCESS, raw10JpegPlusRawOutputs.publicationResult)
        assertEquals("content://media/external/images/media/device_raw10_jpeg", raw10JpegPlusRawOutputs.thumbnailUri)
        assertEquals("content://media/external/images/media/device_raw10_dng", raw10JpegPlusRawOutputs.dngUri)

        // 3. RAW_SENSOR + JPEG_PLUS_RAW
        val rawSensorJpegPlusRawOutputs = PublicationPolicyResolver.resolveStrings(
            outputPolicy = OutputPolicy.JPEG_PLUS_RAW,
            jpegSucceeded = true,
            jpegUri = "content://media/external/images/media/device_rawsensor_jpeg",
            dngSucceeded = true,
            dngUri = "content://media/external/images/media/device_rawsensor_dng"
        )
        assertEquals(CapturePublicationResult.FULL_SUCCESS, rawSensorJpegPlusRawOutputs.publicationResult)
        assertEquals("content://media/external/images/media/device_rawsensor_jpeg", rawSensorJpegPlusRawOutputs.thumbnailUri)
        assertEquals("content://media/external/images/media/device_rawsensor_dng", rawSensorJpegPlusRawOutputs.dngUri)

        // 4. RAW10 + RAW_ONLY
        val raw10RawOnlyOutputs = PublicationPolicyResolver.resolveStrings(
            outputPolicy = OutputPolicy.RAW_ONLY,
            jpegSucceeded = false,
            jpegUri = null,
            dngSucceeded = true,
            dngUri = "content://media/external/images/media/device_raw10_dng_only"
        )
        assertEquals(CapturePublicationResult.FULL_SUCCESS, raw10RawOnlyOutputs.publicationResult)
        assertNull(raw10RawOnlyOutputs.thumbnailUri)
        assertEquals("content://media/external/images/media/device_raw10_dng_only", raw10RawOnlyOutputs.dngUri)

        // 5. RAW_SENSOR + RAW_ONLY
        val rawSensorRawOnlyOutputs = PublicationPolicyResolver.resolveStrings(
            outputPolicy = OutputPolicy.RAW_ONLY,
            jpegSucceeded = false,
            jpegUri = null,
            dngSucceeded = true,
            dngUri = "content://media/external/images/media/device_rawsensor_dng_only"
        )
        assertEquals(CapturePublicationResult.FULL_SUCCESS, rawSensorRawOnlyOutputs.publicationResult)
        assertNull(rawSensorRawOnlyOutputs.thumbnailUri)
        assertEquals("content://media/external/images/media/device_rawsensor_dng_only", rawSensorRawOnlyOutputs.dngUri)
    }
}
