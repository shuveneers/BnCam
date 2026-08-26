package com.bncam.core.engine

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class YuvNativeToneContractTest {
    private val appDir: File = sequenceOf(File("."), File("app"))
        .firstOrNull { File(it, "src/main/cpp/native-lib.cpp").isFile }
        ?: error("Cannot locate app module")

    @Test
    fun nativeYuvUsesNeutralLumaOnlyMapper() {
        val source = File(appDir, "src/main/cpp/native-lib.cpp").readText()
        val yuvPost = source.substringAfter("void applyYuvPostProcessing").substringBefore("struct YuvEncodeTiming")
        assertTrue(yuvPost.contains("NeutralYuvToneMapper::buildLut"))
        assertTrue(yuvPost.contains("cv::COLOR_BGR2YCrCb"))
        assertTrue(yuvPost.contains("cv::COLOR_YCrCb2BGR"))
        assertTrue(yuvPost.contains("raw_tone_defaults_bypassed"))
        assertFalse(yuvPost.contains("rawCfg.softBlackPointAnchor"))
        assertFalse(yuvPost.contains("rawCfg.shadowLift"))
        assertFalse(yuvPost.contains("rawCfg.toeExponent"))
    }

    @Test
    fun nativeStatsExposeRequestedBeforeAfterMetrics() {
        val source = File(appDir, "src/main/cpp/native-lib.cpp").readText()
        listOf(
            "yuvInputNativeYP0_1", "yuvInputNativeYP1", "yuvInputNativeYP5",
            "yuvInputNativeYP50", "yuvInputNativeYP95", "yuvInputNativeYP99",
            "yuvInputNativeYP99_9", "yuvInputBlackClippedFraction",
            "yuvInputWhiteClippedFraction", "yuvInputContrastRatio",
            "yuvOutputBgrLumaP0_1", "yuvOutputBgrLumaP1", "yuvOutputBgrLumaP5",
            "yuvOutputBgrLumaP50", "yuvOutputBgrLumaP95", "yuvOutputBgrLumaP99",
            "yuvOutputBgrLumaP99_9", "yuvOutputBlackClippedFraction",
            "yuvOutputWhiteClippedFraction", "yuvOutputContrastRatio",
            "toneMode=NEUTRAL_YUV_LUMA", "legacyNativeToneLaneRemoved=true",
            "highlightRolloffApplied=true", "postContrast="
        ).forEach { key -> assertTrue("Missing native YUV stat $key", source.contains(key)) }
    }
}
