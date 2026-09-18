package com.bncam.core.isp.raw

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WhiteLevelFinalAuthoritySourceContractTest {
    private val appDir: File = sequenceOf(File("."), File("app"))
        .firstOrNull { File(it, "src/main/cpp/CMakeLists.txt").isFile }
        ?: error("Cannot locate app module from ${File(".").absolutePath}")

    @Test
    fun activeRawDomainFailsClosedInsteadOfInventingSensorWhite() {
        val contract = source("src/main/java/com/bncam/core/isp/raw/RawDomainContract.kt")
        val runner = source("src/main/java/com/bncam/core/runners/SingleFrameRunner.kt")

        assertTrue(contract.contains("UNSAFE_TO_PROCESS:WHITE_LEVEL_METADATA_UNAVAILABLE"))
        assertFalse(contract.contains("fallbackPayloadWhite"))
        assertTrue(runner.contains("UNSAFE_TO_PROCESS:WHITE_LEVEL_METADATA_UNAVAILABLE:CANDIDATE_ANALYSIS"))
        val sampleDomain = runner.substringAfter("fun nativeSampleDomain(").substringBefore("fun clockSafeCandidateDeltaMs")
        assertFalse(sampleDomain.contains("else 65535"))
    }

    @Test
    fun retiredFormatFallbackResolverAndRawSensorBitDepthInferenceAreGone() {
        val quality = source("src/main/java/com/bncam/core/quality/RenderQualityConfig.kt")
        val merger = source("src/main/cpp/DngMerger.cpp")

        assertFalse(quality.contains("resolveEffectiveRawLevels"))
        assertFalse(quality.contains("fallbackWhite = if (frameSourceFormat == ImageFormat.RAW_SENSOR) 65535 else 1023"))
        assertFalse(merger.contains("inferRawSensorNativeWhite"))
        assertFalse(merger.contains("RAW_SENSOR_16BIT_CONTAINER_WITH_RIGHT_JUSTIFIED_"))
    }

    @Test
    fun remainingRaw10AndUint16ConstantsAreFormatBoundsNotAutoSensorAuthority() {
        val readers = source("src/main/java/com/bncam/core/isp/raw/RawSampleReaders.kt")
        val contract = source("src/main/java/com/bncam/core/isp/raw/RawDomainContract.kt")
        val whitePolicy = source("src/main/java/com/bncam/core/isp/raw/RawWhiteAuthorityPolicy.kt")

        assertTrue(readers.contains("coerceIn(0, 1023)"))
        assertTrue(contract.contains("RawInputSource.RAW10 -> 1023"))
        assertTrue(whitePolicy.contains("setOf(1023, 4095, 16383, 65535)"))
        assertTrue(contract.contains("SensorAuthorityUnavailableException"))
    }

    private fun source(relative: String): String = File(appDir, relative).readText()
}
