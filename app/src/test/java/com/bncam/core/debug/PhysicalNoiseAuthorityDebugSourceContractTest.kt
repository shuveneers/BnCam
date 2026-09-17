package com.bncam.core.debug

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PhysicalNoiseAuthorityDebugSourceContractTest {
    private val appDir = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
        .firstOrNull { File(it, "src/main/java/com/bncam/core/debug/ShotLogger.kt").isFile }
        ?: error("Unable to locate app module")

    private fun read(path: String) = File(appDir, path).readText()

    @Test
    fun `physical noise authority trace is high signal and has no manual iso policy`() {
        val state = read("src/main/java/com/bncam/core/quality/PhysicalNoiseState.kt")
        listOf(
            "\"Selected Source\"",
            "\"Effective Source\"",
            "\"Preset Name / ID\"",
            "\"Capture ISO\"",
            "\"Effective Noise ISO\"",
            "\"Dynamic ISO Mode\"",
            "\"Dynamic ISO Coefficient\"",
            "\"S R\"",
            "\"S Gr\"",
            "\"S Gb\"",
            "\"S B\"",
            "\"O R\"",
            "\"O Gr\"",
            "\"O Gb\"",
            "\"O B\"",
            "\"Model Valid\"",
            "\"Confidence\"",
            "\"Fallback Reason\"",
            "\"Frozen Capture State\""
        ).forEach { key -> assertTrue(key in state, "Missing Physical Noise Model debug key $key") }

        assertFalse("\"Manual ISO" in state)
        assertFalse("Manual ISO Value" in state)
    }

    @Test
    fun `neural authority trace separates profile request from native execution truth`() {
        val tuning = read("src/main/java/com/bncam/core/quality/RenderQualityConfig.kt")
        val logger = read("src/main/java/com/bncam/core/debug/ShotLogger.kt")
        val single = read("src/main/java/com/bncam/core/runners/SingleFrameRunner.kt")
        val multi = read("src/main/java/com/bncam/core/runners/MultiFrameRunner.kt")

        listOf(
            "\"Enabled\"",
            "\"Strength\"",
            "\"Luma\"",
            "\"Chroma\"",
            "\"Detail Protection\"",
            "\"Low Frequency Cleanup\"",
            "\"Adaptive Response\""
        ).forEach { key -> assertTrue(key in tuning, "Missing Neural Denoise profile debug key $key") }

        listOf(
            "\"Model Package Available\"",
            "\"Physical Noise Model Consumed\"",
            "\"Conditioning Valid\"",
            "\"Inference Attempted\"",
            "\"Inference Published\"",
            "\"Pixel Mutation\"",
            "\"Bypass Reason\"",
            "\"Input Noise Estimate\"",
            "\"Posterior Noise Estimate\"",
            "\"Correction RMS\"",
            "\"Noise Reduction Ratio\""
        ).forEach { key -> assertTrue(key in logger, "Missing Neural Denoise runtime debug key $key") }

        assertTrue("recordNoiseAuthorityNativeStats(masterIspStatsMap)" in single)
        assertTrue("recordNoiseAuthorityNativeStats(masterRawIspStatsMap)" in multi)
        assertTrue("profileNoiseTuning.authorityDebugPairs()" in single)
        assertTrue("profileNoiseTuning.authorityDebugPairs()" in multi)
    }

    @Test
    fun `legacy physical noise labels are not presented as current authority`() {
        val calibration = read("src/main/java/com/bncam/core/quality/SensorCalibration.kt")
        val logger = read("src/main/java/com/bncam/core/debug/ShotLogger.kt")
        val multi = read("src/main/java/com/bncam/core/runners/MultiFrameRunner.kt")
        val isp = read("src/main/cpp/IspCore.cpp")

        assertFalse("pairs.add(\"Noise Model Mode\"" in calibration)
        assertFalse("pairs.add(\"Sensor Noise Profile Applied\"" in calibration)
        assertFalse("RAW_DOMAIN_NATIVE_ISP_DENOISE_SCALING" in calibration)

        assertTrue("Telemetry Only" in calibration)
        assertTrue("Physical Noise Model Authority:" in logger)
        assertTrue("Neural Denoise Authority:" in logger)
        assertFalse("\"Noise Profile Source\"" in multi)
        assertFalse("\"Noise Profile Present\"" in multi)
        assertFalse("\"Noise Profile Applied\"" in multi)
        assertFalse("; physicalNoiseModelPixelAuthority=false" in isp)
    }

    @Test
    fun `native trace proves frozen physical payload reaches neural conditioning`() {
        val nativeConfig = read("src/main/cpp/NativeRenderQualityConfig.h")
        val nativeLib = read("src/main/cpp/native-lib.cpp")
        val isp = read("src/main/cpp/IspCore.cpp")

        assertTrue("physicalNoiseJniPayloadReceived" in nativeConfig)
        assertTrue("physicalNoiseJniPayloadReceived" in nativeLib)
        assertTrue("physicalNoiseUsedForNeuralConditioning" in isp)
        assertTrue("spectraNeuralConditioningValid" in isp)
        assertTrue("; physicalNoiseJniPayloadReceived=" in isp)
        assertTrue("; physicalNoiseUsedForNeuralConditioning=" in isp)
    }
}
