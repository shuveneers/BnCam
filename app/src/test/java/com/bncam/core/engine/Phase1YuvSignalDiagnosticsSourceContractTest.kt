package com.bncam.core.engine

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Phase1YuvSignalDiagnosticsSourceContractTest {
    private val appDir: File = sequenceOf(File("."), File("app"))
        .firstOrNull { File(it, "src/main/cpp/native-lib.cpp").isFile }
        ?: error("Cannot locate app module")

    @Test
    fun yuvObjectiveTelemetryIsReadOnlyAndNamesNeighbourEnergyHonestly() {
        val native = File(appDir, "src/main/cpp/native-lib.cpp").readText()
        val header = File(appDir, "src/main/cpp/YuvSignalDiagnostics.h").readText()
        assertTrue(native.contains("summarizeNv21Signal"))
        assertTrue(native.contains("yuvInputSignalSampleCount"))
        assertTrue(native.contains("yuvInputYStdDev"))
        assertTrue(native.contains("yuvInputUStdDev"))
        assertTrue(native.contains("yuvInputVStdDev"))
        assertTrue(native.contains("yuvInputLumaNeighbourDeltaMean"))
        assertTrue(native.contains("yuvInputChromaNeighbourDeltaMean"))
        assertTrue(native.contains("SCENE_DETAIL_PLUS_NOISE_NOT_NOISE_ESTIMATE"))
        assertFalse(header.contains("noiseEstimate"))
    }

    @Test
    fun diagnosticsExposeCurrentConversionTruthWithoutChangingIt() {
        val native = File(appDir, "src/main/cpp/native-lib.cpp").readText()
        assertTrue(native.contains("yuvDefaultCamera2ColorContract=JFIF_REC601_FULL_RANGE"))
        assertTrue(native.contains("yuvCurrentBncamConversionMatrix=REC601"))
        assertTrue(native.contains("yuvCurrentBncamConversionRange=LIMITED_16_235_240"))
    }
}
