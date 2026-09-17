package com.bncam.core.debug

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class Phase1SingleFrameCalibrationDiagnosticsSourceContractTest {
    private val appDir = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
        .firstOrNull { File(it, "src/main/java/com/bncam/core/debug/ShotLogger.kt").isFile }
        ?: error("Unable to locate app module")

    private fun read(path: String) = File(appDir, path).readText()

    @Test
    fun `single frame records the resolved physical calibration as a dedicated diagnostics group`() {
        val runner = read("src/main/java/com/bncam/core/runners/SingleFrameRunner.kt")
        assertTrue("renderQualityConfig.finalCalibration?.debugPairs()" in runner)
        assertTrue("recordPipelineEvent(\"Sensor Calibration Detail\"" in runner)
    }

    @Test
    fun `shot diagnostics never fabricate missing RAW calibration truth`() {
        val logger = read("src/main/java/com/bncam/core/debug/ShotLogger.kt")
        assertFalse("[64.0, 63.0, 60.0, 63.0]" in logger)
        assertFalse("?: \"1023\"" in logger)
        assertFalse("?: \"CaptureResult.COLOR_TRANSFORM\"" in logger)
        assertTrue("?: \"not recorded\"" in logger)
        assertTrue("Physical Noise Model Authority:" in logger)
        assertTrue("Neural Denoise Authority:" in logger)
        assertTrue("JNI Received" in logger)
        assertTrue("Native Available" in logger)
        assertFalse("Sensor Noise Profile Applied" in logger)
        assertFalse("Noise Model Mode" in logger)
    }
    @Test
    fun `white level application is derived from the actual RAW domain`() {
        val calibration = read("src/main/java/com/bncam/core/quality/SensorCalibration.kt")
        assertTrue("White Level Applied" in calibration)
        assertTrue("rawInputDomain == RawDomain.RAW10_PACKED_10BIT" in calibration)
        assertTrue("rawInputDomain == RawDomain.RAW_SENSOR_16BIT" in calibration)
        assertFalse("pairs.add(\"White Level Applied\" to \"true\")" in calibration)
    }

}
