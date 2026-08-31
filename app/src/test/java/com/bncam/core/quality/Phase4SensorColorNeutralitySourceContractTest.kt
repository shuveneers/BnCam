package com.bncam.core.quality

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Phase4SensorColorNeutralitySourceContractTest {
    private val appDir = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
        .firstOrNull { File(it, "src/main/java/com/bncam/core/quality/RawColorTransformEngine.kt").isFile }
        ?: error("Unable to locate app module")

    @Test
    fun `camera2 matrix extraction has one canonical row major owner`() {
        val manager = File(appDir, "src/main/java/com/bncam/core/engine/BnCameraManager.kt").readText()
        val engine = File(appDir, "src/main/java/com/bncam/core/quality/RawColorTransformEngine.kt").readText()
        val warmBuffer = File(appDir, "src/main/java/com/bncam/core/capture/WarmBufferPairingCoordinator.kt").readText()
        val production = File(appDir, "src/main/java").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .associateWith { it.readText() }

        assertTrue("transform?.let(RawColorTransformEngine::colorSpaceTransformToArray)" in manager)
        assertTrue("RawColorTransformEngine.colorSpaceTransformToArray(it)" in warmBuffer)
        assertTrue("transform.getElement(column, row)" in engine)
        assertFalse("transform.getElement(row, column)" in engine)
        assertFalse("getElement(index / 3, index % 3)" in manager)
        assertTrue(production.filterValues { "getElement(" in it }.keys == setOf(
            File(appDir, "src/main/java/com/bncam/core/quality/RawColorTransformEngine.kt")
        ))
    }

    @Test
    fun `sensor calibration resolver is the only render config color metadata owner`() {
        val config = File(appDir, "src/main/java/com/bncam/core/quality/RenderQualityConfig.kt").readText()
        assertTrue("SensorCalibrationResolver.resolve(" in config)
        assertTrue("RAW color metadata resolution is intentionally owned by SensorCalibrationResolver" in config)
        assertFalse("private fun resolveColorCorrectionMatrix(" in config)
        assertFalse("ColorMatrixCandidate" in config)
    }

    @Test
    fun `forward matrix fallback bridges actual sensor through inverse calibration`() {
        val engine = File(appDir, "src/main/java/com/bncam/core/quality/RawColorTransformEngine.kt").readText()
        val calibration = File(appDir, "src/main/java/com/bncam/core/quality/SensorCalibration.kt").readText()

        assertTrue("calibration_transform_missing" in engine)
        assertTrue("val actualSensorToReference = invert3x3(calibration)" in engine)
        assertTrue("multiply3x3(forward, actualSensorToReference)" in engine)
        assertTrue("resolveActualSensorForwardMatrixToLinearSrgb(" in calibration)
        assertFalse("inverse(SENSOR_COLOR_TRANSFORM1) ->" in calibration)
        assertFalse("inverse(SENSOR_COLOR_TRANSFORM2) ->" in calibration)
    }

    @Test
    fun `neutral axis is diagnostic and camera matrix rows are never normalized`() {
        val engine = File(appDir, "src/main/java/com/bncam/core/quality/RawColorTransformEngine.kt").readText()
        val calibration = File(appDir, "src/main/java/com/bncam/core/quality/SensorCalibration.kt").readText()

        assertTrue("neutralAxisDeviation" in engine)
        assertTrue("neutralNormalizationApplied = false" in calibration)
        assertTrue("legacyNeutralNormalizedValues = legacyCounterfactual" in calibration)
        assertFalse("neutralNormalizationApplied = true" in calibration)
    }

    @Test
    fun `gpu consumers interpret the canonical matrix as row major`() {
        val preview = File(appDir, "src/main/cpp/vulkan/shaders/raw_preview.comp").readText()
        val capture = File(appDir, "src/main/cpp/vulkan/shaders/spectra_demosaic_resident.comp").readText()
        assertTrue("pc.ccm0 * wb.r + pc.ccm1 * wb.g + pc.ccm2 * wb.b" in preview)
        assertTrue("pc.ccm3 * wb.r + pc.ccm4 * wb.g + pc.ccm5 * wb.b" in preview)
        assertTrue("pc.ccm6 * wb.r + pc.ccm7 * wb.g + pc.ccm8 * wb.b" in preview)
        assertTrue("pc.ccm0 * legacyWb.r + pc.ccm1 * legacyWb.g + pc.ccm2 * legacyWb.b" in capture)
    }
    @Test
    fun `all raw preview color pair gates use the central matrix validator`() {
        val renderer = File(appDir, "src/main/java/com/bncam/ui/screens/capture/RawPreviewRenderer.kt").readText()
        val wbState = File(appDir, "src/main/java/com/bncam/core/quality/WhiteBalanceStateEngine.kt").readText()
        val calibration = File(appDir, "src/main/java/com/bncam/core/quality/SensorCalibration.kt").readText()
        assertTrue("validateSensorToLinearSrgbMatrix(colorMatrix)" in renderer)
        assertTrue("validateSensorToLinearSrgbMatrix(colorMatrix.copyOf(9))" in renderer)
        assertTrue("validateSensorToLinearSrgbMatrix(it).valid" in wbState)
        assertTrue("validateSensorToLinearSrgbMatrix(matrix.copyOf(9)).valid" in calibration)
        assertFalse("MAX_MATRIX_ELEMENT" in wbState)
    }

    @Test
    fun `manual profile white balance publishes a coherent wb and matrix pair`() {
        val manager = File(appDir, "src/main/java/com/bncam/core/engine/BnCameraManager.kt").readText()
        val renderer = File(appDir, "src/main/java/com/bncam/ui/screens/capture/RawPreviewRenderer.kt").readText()
        val calibration = File(appDir, "src/main/java/com/bncam/core/quality/SensorCalibration.kt").readText()
        assertTrue("val targetColorMatrix = targetColorSolution?.mPostCompensated" in manager)
        assertTrue("updateWhiteBalanceColorPair(targetSensorGains, targetColorMatrix)" in manager)
        assertTrue("liveColorPair?.colorMatrix" in renderer)
        assertTrue("sensorAwareProfileAwb?.mPostCompensated" in calibration)
        assertTrue("Profile Kelvin uses coherent Camera2 calibration-derived WB + post-WB sensor-to-linear-sRGB matrix" in calibration)
    }

}
