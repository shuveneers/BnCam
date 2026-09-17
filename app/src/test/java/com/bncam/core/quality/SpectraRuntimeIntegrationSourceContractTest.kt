package com.bncam.core.quality

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpectraRuntimeIntegrationSourceContractTest {
    private fun appDir(): File = sequenceOf(File("."), File("app"))
        .firstOrNull { File(it, "src/main/cpp/DngMerger.cpp").isFile }
        ?: error("Cannot locate app module from ${File(".").absolutePath}")

    @Test
    fun temporalPhysicalNoiseIsIndependentFromSpectraToggle() {
        val app = appDir()
        val imageUtils = File(app, "src/main/java/com/bncam/core/engine/ImageUtils.kt").readText()
        val policy = File(app, "src/main/java/com/bncam/core/quality/TemporalNoiseModelAuthorityPolicy.kt").readText()

        assertTrue(imageUtils.contains("toPhysicalTemporalNoisePayload"))
        assertTrue(imageUtils.contains("spectraMode = 0"))
        assertTrue(imageUtils.contains("spectraAdaptiveCalibrationEnabled = false"))
        assertFalse(imageUtils.contains("toSpectraMergePayload"))
        assertFalse(policy.contains("spectraProcessingEnabled"))
        assertFalse(policy.contains("adaptiveSpectraCalibration"))
        assertFalse(policy.contains("SPECTRA_ADDON_CONSUMES_PHYSICAL_SHUTTER_SO"))
        assertTrue(policy.contains("PHYSICAL_SHUTTER_SNAPSHOT_FIXED_SO"))
    }

    @Test
    fun spectraAdapterCannotRewriteFrozenPhysicalSnapshot() {
        val app = appDir()
        val adapter = File(app, "src/main/java/com/bncam/core/quality/SpectraNoiseAdapter.kt").readText()

        assertTrue(adapter.contains("SpectraNoiseInputState.from"))
        assertTrue(adapter.contains("spectraProcessingEnabled = input.enabled"))
        assertFalse(adapter.contains("noiseSnapshot = adaptedSnapshot"))
        assertFalse(adapter.contains("snapshot.copy("))
    }

    @Test
    fun legacySpectraDynamicIsoIsNotReadByRuntimeCaptureOrProfileOverview() {
        val app = appDir()
        val config = File(app, "src/main/java/com/bncam/core/quality/RenderQualityConfig.kt").readText()
        val edit = File(app, "src/main/java/com/bncam/ui/screens/settings/lens_profiles/ProfileEditScreen.kt").readText()
        val screen = File(app, "src/main/java/com/bncam/ui/screens/settings/lens_profiles/ProfileTuningSubScreens.kt").readText()

        assertFalse(config.contains("ProfileIspKeys.SPECTRA_DYNAMIC_ISO"))
        assertFalse(edit.contains("ProfileIspKeys.SPECTRA_DYNAMIC_ISO"))
        assertTrue(edit.contains("ProfileIspKeys.NEURAL_ADAPTIVE_RESPONSE"))
        assertTrue(edit.contains("ProfileIspKeys.NEURAL_DENOISE_STRENGTH"))
        assertTrue(screen.contains("ProfileIspKeys.NEURAL_ADAPTIVE_RESPONSE"))
        assertFalse(screen.contains("val legacyAdaptive"))
    }

    @Test
    fun legacySpectraPhysicalMergeMutatorIsGone() {
        val app = appDir()
        val calibration = File(app, "src/main/java/com/bncam/core/quality/SensorCalibration.kt").readText()
        val master = File(app, "src/main/java/com/bncam/core/isp/raw/MasterRawFrame.kt").readText()
        val rawInput = File(app, "src/main/java/com/bncam/core/isp/raw/Raw16RenderInput.kt").readText()

        assertFalse(calibration.contains("withSpectraMergeStats"))
        assertFalse(calibration.contains("SPECTRA_CAPTURE_FUSION"))
        assertTrue(master.contains("withPhysicalMergeStats"))
        assertTrue(rawInput.contains("withPhysicalMergeStats"))
    }
}
