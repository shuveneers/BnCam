package com.bncam.core.capture

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Phase2CContractsTest {
    private val appDir: File
        get() = sequenceOf(File("."), File("app"))
            .first { File(it, "src/main/java/com/bncam").isDirectory }

    @Test
    fun dngSourceContractDistinguishesPlannedAndNativeResults() {
        assertEquals(
            DngSource.NOT_APPLICABLE,
            DngSourceResolver.planned(OutputPolicy.JPEG, CaptureMode.MULTI, 8)
        )
        assertEquals(
            DngSource.ANCHOR_RAW,
            DngSourceResolver.planned(OutputPolicy.RAW_ONLY, CaptureMode.SINGLE, 8)
        )
        assertEquals(
            DngSource.ANCHOR_RAW,
            DngSourceResolver.planned(OutputPolicy.RAW_ONLY, CaptureMode.MULTI, 1)
        )
        assertEquals(
            DngSource.FUSED_RAW,
            DngSourceResolver.planned(OutputPolicy.RAW_ONLY, CaptureMode.MULTI, 4)
        )
        assertEquals(
            DngSource.ANCHOR_RAW,
            DngSourceResolver.executed(
                rawMasterAvailable = true,
                masterFrameCount = 4,
                nativeAnchorOnly = true
            )
        )
        assertEquals(
            DngSource.FUSED_RAW,
            DngSourceResolver.executed(
                rawMasterAvailable = true,
                masterFrameCount = 4,
                nativeAnchorOnly = false
            )
        )
    }

    @Test
    fun multiFrameConfigKeepsProcessingAndDngCountsIndependent() {
        val config = MultiFrameCaptureConfig(
            sourceFormat = 37,
            shootingMode = CaptureMode.MULTI,
            alignmentMethod = "auto",
            fusionMethod = "auto",
            fusionFrameCount = 8,
            dngMasterFrameCount = 1,
            exposureStrategy = "ETTR",
            configuredBufferCapacity = 30,
            effectiveBufferCapacity = 25,
            lensId = "0",
            demosaicMethod = "MALVAR_2004",
            outputPolicy = OutputPolicy.JPEG_PLUS_RAW
        )

        assertEquals(8, config.fusionFrameCount)
        assertEquals(8, config.jpegFusionFrameCount)
        assertEquals(1, config.dngMasterFrameCount)
    }

    @Test
    fun repositoryUsesIndependentCanonicalProfileKeysAndCopiesBothCounts() {
        val source =
            File(appDir, "src/main/java/com/bncam/data/settings/SettingsRepository.kt")
                .readText()
        val fusionRaw10Body = source.substring(
            source.indexOf("fun getProfileMultiFrameFusionFramesRaw10Flow"),
            source.indexOf("suspend fun setProfileMultiFrameFusionFramesRaw10")
        )
        val dngRaw10Body = source.substring(
            source.indexOf("fun getProfileMultiFrameDngMasterFramesRaw10Flow"),
            source.indexOf("suspend fun setProfileMultiFrameDngMasterFramesRaw10")
        )

        assertTrue(fusionRaw10Body.contains("CaptureSettingKeys.FUSION_FRAMES_RAW10"))
        assertFalse(fusionRaw10Body.contains("DNG_MASTER_FRAMES"))
        assertTrue(dngRaw10Body.contains("CaptureSettingKeys.DNG_MASTER_FRAMES_RAW10"))
        assertFalse(dngRaw10Body.contains("FUSION_FRAMES_RAW10"))
        assertTrue(source.contains("setProfileMultiFrameDngMasterFramesRaw10(toProfileId"))
        assertTrue(source.contains("setProfileMultiFrameDngMasterFramesRawSensor(toProfileId"))
    }

    @Test
    fun rawOnlyRoutesHaveExplicitNoRgbIspNoJpegInvariants() {
        listOf("SingleFrameRunner.kt", "MultiFrameRunner.kt").forEach { name ->
            val source =
                File(appDir, "src/main/java/com/bncam/core/runners/$name").readText()
            assertTrue(source.contains("\"rawOnlyRgbIspExecuted\""))
            assertTrue(source.contains("\"rawOnlyJpegEncoded\""))
            assertTrue(source.contains("\"rawOnlyHiddenJpegCreated\""))
            assertTrue(source.contains("jpegEncodeInvocationCount"))
            assertTrue(source.contains("jpegMediaStorePublicationCount"))
            assertTrue(source.contains("raw_only_stops_before_demosaic_rgb_isp_and_jpeg"))
        }
    }

    @Test
    fun benchmarkReceiverExistsOnlyInDebugSourceSet() {
        val mainActivity = File(appDir, "src/main/java/com/bncam/MainActivity.kt").readText()
        val debugController =
            File(appDir, "src/debug/java/com/bncam/BenchmarkDebugReceiverController.kt")
                .readText()
        val releaseController =
            File(appDir, "src/release/java/com/bncam/BenchmarkDebugReceiverController.kt")
                .readText()

        assertFalse(mainActivity.contains("com.bncam.SET_BENCHMARK_CONFIG"))
        assertFalse(mainActivity.contains("com.bncam.TRIGGER_CAPTURE"))
        assertTrue(debugController.contains("ContextCompat.RECEIVER_EXPORTED"))
        assertTrue(debugController.contains("com.bncam.SET_BENCHMARK_CONFIG"))
        assertTrue(debugController.contains("com.bncam.TRIGGER_CAPTURE"))
        assertFalse(releaseController.contains("registerReceiver"))
        assertFalse(releaseController.contains("com.bncam.SET_BENCHMARK_CONFIG"))
        assertFalse(releaseController.contains("com.bncam.TRIGGER_CAPTURE"))
    }
}
