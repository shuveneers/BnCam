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
    fun `gcam calibration constrains physical awb and preserves four channel gains`() {
        val manager = source("src/main/java/com/bncam/core/engine/BnCameraManager.kt")
        val engine = source("src/main/java/com/bncam/core/quality/GcamAwbCalibration.kt")
        val state = source("src/main/java/com/bncam/core/quality/WhiteBalanceStateEngine.kt")

        val calibration = source("src/main/java/com/bncam/core/quality/SensorCalibration.kt")
        assertTrue(manager.contains("LensAwbCalibrationRuntimeRegistry.resolve(scopeKey)"))
        assertTrue(manager.contains("GcamAwbCalibrationEngine.constrainPhysicalGains("))
        assertTrue(manager.contains("GcamAwbCalibrationEngine.applyToCamera2Prior("))
        assertTrue(manager.contains("developedCamera2Gains"))
        assertTrue(manager.contains("greenEvenOddRatio"))
        assertTrue(engine.contains("nearestPointOnLocus"))
        assertTrue(engine.contains("applyToCamera2Prior"))
        assertTrue(calibration.contains("calibratedExactFrameAwb"))
        assertTrue(calibration.contains("LensAwbCalibrationRuntimeRegistry.resolve(base.lensId)"))
        assertTrue(calibration.indexOf("calibratedExactFrameAwb != null -> calibratedExactFrameAwb.gains.copyOf()") <
            calibration.indexOf("exactFrameCamera2Wb -> base.baseWbGains.copyOf()"))
        assertTrue(state.contains("calibrationFingerprint"))
        assertTrue(state.contains("greenEvenOddRatio"))
    }

    @Test
    fun `green split is applied before demosaic in capture and both raw preview shaders`() {
        val capture = source("src/main/cpp/vulkan/shaders/spectra_raw_finalize.comp")
        val preview = source("src/main/cpp/vulkan/shaders/raw_preview.comp")
        val previewImage = source("src/main/cpp/vulkan/shaders/raw_preview_image.comp")
        val backend = source("src/main/cpp/vulkan/VulkanRawPreviewBackend.cpp")

        assertTrue(capture.contains("greenCalibrationScale"))
        assertTrue(capture.contains("greenCalibrationRatio"))
        assertTrue(preview.contains("greenRowCalibrationScale"))
        assertTrue(previewImage.contains("greenRowCalibrationScale"))
        assertTrue(backend.contains("packedGreenRatio"))
        assertTrue(backend.contains("sizeof(PushConstants) == 128u"))
    }

    @Test
    fun `agc v12 presets are lens hardware calibration and not profile state`() {
        val settings = source("src/main/java/com/bncam/data/settings/LensAwbCalibrationSettings.kt")
        val engine = source("src/main/java/com/bncam/core/quality/GcamAwbCalibration.kt")
        val ui = source("src/main/java/com/bncam/ui/screens/settings/lens_profiles/AwbCalibrationSettingsScreen.kt")
        val catalog = source("src/main/java/com/bncam/data/settings/AgcAwbPresetCatalog.kt")

        assertTrue(settings.contains("AGC_PRESET"))
        assertTrue(engine.contains("AGC_V12_PRESET"))
        assertTrue(ui.contains("AgcAwbPresetCatalog.all.map { it.selectionLabel }"))
        assertTrue(catalog.contains("require(all.size == 56)"))
    }

    @Test
    fun `developed lens awb never rewrites dng camera2 metadata`() {
        val dng = source("src/main/java/com/bncam/core/isp/raw10/DngWriter.kt")
        val calibration = source("src/main/java/com/bncam/core/quality/SensorCalibration.kt")

        assertTrue(dng.contains("DngCreator(characteristics, metadata)"))
        assertFalse(dng.contains("setColorCorrectionGains"))
        assertFalse(dng.contains("setColorCorrectionTransform"))
        assertTrue(calibration.contains("\"DNG Developed AWB Override\" to \"false\""))
        assertTrue(calibration.contains("\"DNG Developed AWB/CCM Overridden\" to \"false\""))
    }
    @Test
    fun `awb setting commit is published directly and explicit calibration owns developed wb`() {
        val store = source("src/main/java/com/bncam/data/settings/LensAwbCalibrationSettingsStore.kt")
        val registry = source("src/main/java/com/bncam/data/settings/LensAwbCalibrationRuntimeRegistry.kt")
        val calibration = source("src/main/java/com/bncam/core/quality/SensorCalibration.kt")
        val engine = source("src/main/java/com/bncam/core/quality/GcamAwbCalibration.kt")

        assertTrue(store.contains("LensAwbCalibrationRuntimeRegistry.publish(lensId, safe)"))
        assertTrue(registry.contains("fun publish(lensId: String, settings: LensAwbCalibrationSettings)"))
        assertTrue(engine.contains("fun hasExplicitDevelopedAuthority"))
        assertTrue(calibration.contains("explicitLensAwbAuthority && calibratedExactFrameAwb != null -> calibratedExactFrameAwb.gains.copyOf()"))
        assertTrue(calibration.contains("AWB Requested Mode"))
        assertTrue(calibration.contains("AWB Runtime Settings Ready"))
        assertTrue(calibration.contains("AWB Requested Fingerprint"))
    }

}
