package com.bncam.core.quality

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LightroomLegacyToneRawCaptureRemovalSourceContractTest {
    private val appDir: File = sequenceOf(File("."), File("app"))
        .firstOrNull { File(it, "src/main/cpp/IspCore.cpp").isFile }
        ?: error("Cannot locate app module")
    private fun source(relative: String): String = File(appDir, relative).readText()

    @Test
    fun `raw master render no longer accepts legacy shadow toe black controls`() {
        val imageUtils = source("src/main/java/com/bncam/core/engine/ImageUtils.kt")
        val native = source("src/main/cpp/native-lib.cpp")
        val isp = source("src/main/cpp/IspCore.cpp")

        val rawSafe = imageUtils.substringAfter("fun renderJpegFromRaw16InputSafe(")
            .substringBefore("): ByteArray?")
        assertFalse(rawSafe.contains("shadowLift"))
        assertFalse(rawSafe.contains("toeExponent"))
        assertFalse(rawSafe.contains("softBlackPointAnchor"))

        val nativeMaster = native.substringAfter("Java_com_bncam_core_engine_ImageUtils_renderJpegFromMasterNative(")
            .substringBefore("NativeRenderQualityConfig qualityConfig = makeQualityConfig")
        assertFalse(nativeMaster.contains("shadowLift"))
        assertFalse(nativeMaster.contains("toeExponent"))
        assertFalse(nativeMaster.contains("softBlackPointAnchor"))

        assertFalse(isp.contains("uiConfig.shadowLift"))
        assertFalse(isp.contains("uiConfig.toeExponent"))
        assertFalse(isp.contains("uiConfig.softBlackPointAnchor"))
        assertTrue(isp.contains("applyProfileTonalRanges"))
    }
}
