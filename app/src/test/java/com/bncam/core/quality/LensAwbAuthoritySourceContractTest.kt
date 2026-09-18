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
    fun `awb has one persistent profile owner and is removed from lens hardware ui`() {
        val editor = source("src/main/java/com/bncam/ui/screens/settings/lens_profiles/ProfileEditScreen.kt")
        val lens = source("src/main/java/com/bncam/ui/screens/settings/lens_profiles/LensDetailScreen.kt")
        val catalog = source("src/main/java/com/bncam/core/quality/LibpatcherProfileResolver.kt")
        val profileStore = source("src/main/java/com/bncam/data/settings/ProfileAwbCalibrationSettingsStore.kt")
        assertTrue(editor.contains("title = \"AWB\""))
        assertTrue(editor.contains("onNavigateToAwb"))
        assertFalse(lens.contains("title = \"AWB\""))
        assertFalse(lens.contains("onNavigateToAwbCalibration"))
        assertTrue(profileStore.contains("class ProfileAwbCalibrationSettingsStore"))
        assertTrue(profileStore.contains("ProfileAwbSettingKeys"))
        assertTrue(catalog.contains("ProfileAwbSettingKeys.portableSpecs()"))
    }

    @Test
    fun `profile awb still constrains physical awb and preserves four channel gains`() {
        val manager = source("src/main/java/com/bncam/core/engine/BnCameraManager.kt")
        val engine = source("src/main/java/com/bncam/core/quality/AwbCalibration.kt")
        val calibration = source("src/main/java/com/bncam/core/quality/SensorCalibration.kt")
        val registry = source("src/main/java/com/bncam/data/settings/LensAwbCalibrationRuntimeRegistry.kt")
        assertTrue(manager.contains("AwbCalibrationEngine.constrainPhysicalGains("))
        assertTrue(manager.contains("AwbCalibrationEngine.applyToCamera2Prior("))
        assertTrue(engine.contains("nearestPointOnLocus"))
        assertTrue(calibration.contains("calibratedExactFrameAwb"))
        assertTrue(calibration.contains("LensAwbCalibrationRuntimeRegistry.resolve(base.lensId)"))
        assertTrue(registry.contains("ProfileAwbCalibrationSettingsStore(context)"))
    }

    @Test
    fun `fifteen bncam awb presets are profile state`() {
        val settings = source("src/main/java/com/bncam/data/settings/LensAwbCalibrationSettings.kt")
        val catalog = source("src/main/java/com/bncam/data/settings/BnCamAwbPresetCatalog.kt")
        val ui = source("src/main/java/com/bncam/ui/screens/settings/lens_profiles/AwbCalibrationSettingsScreen.kt")
        assertTrue(settings.contains("BNCAM_PRESET"))
        assertTrue(catalog.contains("BnCamAwbPreset(14"))
        assertTrue(ui.contains("BnCamAwbPresetGroup.entries"))
        assertTrue(ui.contains("ProfileAwbCalibrationSettingsStore"))
        assertTrue(settings.contains("val mode: String = LensAwbCalibrationModes.AUTO"))
    }

    @Test
    fun `authority status card is removed and signed trim mapping is used`() {
        val ui = source("src/main/java/com/bncam/ui/screens/settings/lens_profiles/AwbCalibrationSettingsScreen.kt")
        val settings = source("src/main/java/com/bncam/data/settings/LensAwbCalibrationSettings.kt")
        assertFalse(ui.contains("title = \"Authority status\""))
        assertTrue(ui.contains("valueRange = -1.00f..1.00f"))
        assertTrue(ui.contains("AwbCoefficientUiMapping.fromSigned"))
        assertTrue(settings.contains("object AwbCoefficientUiMapping"))
    }

    @Test
    fun `developed profile awb never rewrites dng camera2 metadata`() {
        val dng = source("src/main/java/com/bncam/core/isp/raw10/DngWriter.kt")
        val calibration = source("src/main/java/com/bncam/core/quality/SensorCalibration.kt")
        assertTrue(dng.contains("DngCreator(characteristics, metadata)"))
        assertFalse(dng.contains("setColorCorrectionGains"))
        assertFalse(dng.contains("setColorCorrectionTransform"))
        assertTrue(calibration.contains("\"DNG Developed AWB Override\" to \"false\""))
    }
}
