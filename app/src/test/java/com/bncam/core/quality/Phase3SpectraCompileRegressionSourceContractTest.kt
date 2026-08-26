package com.bncam.core.quality

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Phase3SpectraCompileRegressionSourceContractTest {
    private fun appDir(): File = sequenceOf(File("."), File("app"))
        .firstOrNull { File(it, "src/main/cpp/IspCore.cpp").isFile }
        ?: error("Cannot locate app module")

    @Test
    fun `sensor calibration uses primitive array availability checks`() {
        val source = File(appDir(), "src/main/java/com/bncam/core/quality/SensorCalibration.kt").readText()

        assertTrue(source.contains("cameraNoiseCanonical?.isNotEmpty() == true"))
        assertTrue(source.contains("manualNoise?.isNotEmpty() == true"))
        assertFalse(source.contains("cameraNoiseCanonical.isNullOrEmpty()"))
        assertFalse(source.contains("manualNoise.isNullOrEmpty()"))
    }

    @Test
    fun `resident pass1 does not use reserved flat identifier`() {
        val shader = File(appDir(), "src/main/cpp/vulkan/shaders/spectra_pass1_resident.comp").readText()

        assertFalse(Regex("\\bfloat\\s+flat\\s*=").containsMatchIn(shader))
        assertTrue(shader.contains("float flatContextWeight ="))
        assertTrue(shader.contains("0.78 * flatContextWeight"))
    }
}
