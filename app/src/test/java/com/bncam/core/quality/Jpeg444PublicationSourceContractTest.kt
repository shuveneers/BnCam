package com.bncam.core.quality

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Jpeg444PublicationSourceContractTest {
    private fun appDir(): File = sequenceOf(File("."), File("app"))
        .firstOrNull { File(it, "src/main/cpp/IspCore.cpp").isFile }
        ?: error("Cannot locate app module")

    @Test
    fun `raw and yuv jpeg publication share quality first 444 policy`() {
        val app = appDir()
        val policy = File(app, "src/main/cpp/JpegEncodingPolicy.h").readText()
        val isp = File(app, "src/main/cpp/IspCore.cpp").readText()
        val native = File(app, "src/main/cpp/native-lib.cpp").readText()

        assertTrue(policy.contains("cv::IMWRITE_JPEG_SAMPLING_FACTOR"))
        assertTrue(policy.contains("cv::IMWRITE_JPEG_SAMPLING_FACTOR_444"))
        assertTrue(policy.contains("cv::IMWRITE_JPEG_OPTIMIZE, 1"))
        assertTrue(isp.contains("bncam::jpeg444EncodingParameters"))
        assertEquals(2, "bncam::jpeg444EncodingParameters".toRegex().findAll(native).count())

        val rawEncode = isp.substringBefore("cv::imencode(\".jpg\", bgr8")
            .takeLast(800)
        assertTrue(rawEncode.contains("jpeg444EncodingParameters"))

        // Every current native JPEG publication call site must be governed by
        // the same helper; no accidental quality-only 4:2:0 call remains.
        assertFalse(native.contains("{cv::IMWRITE_JPEG_QUALITY, resolvedQuality.jpegQuality}"))
    }
}
