package com.bncam.core.quality

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LensAwbAuthoritySourceContractTest {
    private val appDir = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
        .firstOrNull { File(it, "src/main/java/com/bncam/core/quality/SensorCalibration.kt").isFile }
        ?: error("Unable to locate app module")

    private fun source(path: String): String = File(appDir, path).readText()

    @Test
    fun `awb has one persistent lens hardware owner and no profile owner`() {
        val editor = source("src/main/java/com/bncam/ui/screens/settings/lens_profiles/ProfileEditScreen.kt")
        val lens = source("src/main/java/com/bncam/ui/screens/settings/lens_profiles/LensDetailScreen.kt")
        val repo = source("src/main/java/com/bncam/data/settings/SettingsRepository.kt")
        val catalog = source("src/main/java/com/bncam/core/quality/LibpatcherProfileResolver.kt")
        assertFalse(File(appDir, "src/main/java/com/bncam/ui/screens/settings/lens_profiles/ProfileAwbSettingsScreen.kt").exists())
        assertFalse(File(appDir, "src/main/java/com/bncam/core/quality/ProfileAwbResolver.kt").exists())
        assertFalse(editor.contains("title = \"AWB\""))
        assertTrue(lens.contains("title = \"AWB\""))
        assertTrue(lens.contains("onNavigateToAwbCalibration"))
        assertFalse(repo.contains("awb_reference_intensity"))
        assertFalse(catalog.contains("awb_reference_intensity"))
        assertFalse(catalog.contains("awb_mode"))
    }

    @Test
    fun `bncam calibration constrains physical awb and preserves four channel gains`() {
        val manager = source("src/main/java/com/bncam/core/engine/BnCameraManager.kt")
        val engine = source("src/main/java/com/bncam/core/quality/AwbCalibration.kt")
        val calibration = source("src/main/java/com/bncam/core/quality/SensorCalibration.kt")
        assertTrue(manager.contains("AwbCalibrationEngine.constrainPhysicalGains("))
        assertTrue(manager.contains("AwbCalibrationEngine.applyToCamera2Prior("))
        assertTrue(engine.contains("nearestPointOnLocus"))
        assertTrue(calibration.contains("calibratedExactFrameAwb"))
        assertTrue(calibration.contains("LensAwbCalibrationRuntimeRegistry.resolve(base.lensId)"))
    }

    @Test
    fun `fifteen bncam awb presets are lens hardware state`() {
        val settings = source("src/main/java/com/bncam/data/settings/LensAwbCalibrationSettings.kt")
        val catalog = source("src/main/java/com/bncam/data/settings/BnCamAwbPresetCatalog.kt")
        val ui = source("src/main/java/com/bncam/ui/screens/settings/lens_profiles/AwbCalibrationSettingsScreen.kt")
        assertTrue(settings.contains("BNCAM_PRESET"))
        assertTrue(catalog.contains("BnCamAwbPreset(14"))
        assertTrue(ui.contains("BnCamAwbPresetGroup.entries"))
        assertTrue(settings.contains("val mode: String = LensAwbCalibrationModes.AUTO"))
    }

    @Test
    fun `developed lens awb never rewrites dng camera2 metadata`() {
        val dng = source("src/main/java/com/bncam/core/isp/raw10/DngWriter.kt")
        val calibration = source("src/main/java/com/bncam/core/quality/SensorCalibration.kt")
        assertTrue(dng.contains("DngCreator(characteristics, metadata)"))
        assertFalse(dng.contains("setColorCorrectionGains"))
        assertFalse(dng.contains("setColorCorrectionTransform"))
        assertTrue(calibration.contains("\"DNG Developed AWB Override\" to \"false\""))
    }
}
