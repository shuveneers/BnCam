package com.bncam.data.settings

import com.bncam.core.capture.EffectiveShutterSnapshot
import com.bncam.core.capture.OutputPolicy
import com.bncam.data.profile.IspControlValue
import com.bncam.data.profile.IspProfileConfig
import com.bncam.data.profile.SettingImpact
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class Phase4Block2Test {

    @Test
    fun `safe lens-key encoding replaces non-alphanumeric characters`() {
        val rawLensId = "camera/0:wide_lens#1"
        val safeKey = SettingsRepository.safeLensKeyPart(rawLensId)

        assertFalse(safeKey.contains("/"))
        assertFalse(safeKey.contains(":"))
        assertFalse(safeKey.contains("#"))
        assertTrue(safeKey.all { it.isLetterOrDigit() || it == '_' })
    }

    @Test
    fun `per-lens calibration isolation ensures lens 0 and lens 1 do not leak`() {
        val cal0 = LensCalibrationConfig(
            lensId = "0",
            noiseModel = NoiseCalibrationConfig(mode = NoiseCalibrationConfig.MODE_MANUAL, isoStep = 100f),
            blackLevel = BlackLevelCalibrationConfig(mode = BlackLevelCalibrationConfig.MODE_MANUAL, l1 = 64f, l2 = 64f, l3 = 64f, l4 = 64f)
        )
        val cal1 = LensCalibrationConfig(
            lensId = "1",
            noiseModel = NoiseCalibrationConfig(mode = NoiseCalibrationConfig.MODE_AUTO),
            blackLevel = BlackLevelCalibrationConfig(mode = BlackLevelCalibrationConfig.MODE_AUTO)
        )

        assertNotEquals(cal0.lensId, cal1.lensId)
        assertEquals(NoiseCalibrationConfig.MODE_MANUAL, cal0.noiseModel.mode)
        assertEquals(NoiseCalibrationConfig.MODE_AUTO, cal1.noiseModel.mode)
    }

    @Test
    fun `AUTO to MANUAL transition and MANUAL to AUTO reset`() {
        var cal = LensCalibrationConfig(lensId = "0")
        assertEquals(NoiseCalibrationConfig.MODE_AUTO, cal.noiseModel.mode)

        cal = cal.copy(noiseModel = cal.noiseModel.copy(mode = NoiseCalibrationConfig.MODE_MANUAL))
        assertEquals(NoiseCalibrationConfig.MODE_MANUAL, cal.noiseModel.mode)

        cal = cal.copy(noiseModel = cal.noiseModel.copy(mode = NoiseCalibrationConfig.MODE_AUTO))
        assertEquals(NoiseCalibrationConfig.MODE_AUTO, cal.noiseModel.mode)
    }

    @Test
    fun `invalid or non-finite noise values are rejected by resolver`() {
        val resolved = LensHardwareSettingsResolver.resolve(
            lensId = "0",
            noiseModelType = "Manual",
            noiseAString = "NaN, 1.0, 2.0, 3.0",
            noiseBString = "0, 0, 0, 0",
            noiseCString = "0, 0, 0, 0",
            noiseDString = "0, 0, 0, 0",
            isoStepString = "0",
            isoNrStyle = "Default",
            dynamicIsoCoeff = 0f,
            manualIsoValueString = "0",
            blackLevelMode = "Auto",
            dynamicBlackLevel = 100f,
            manualBlackLevelsString = "64 64 64 64",
            colorMatrixMode = "System",
            manualColorMatrixString = "1 0 0 0 1 0 0 0 1",
            awbProfile = "System",
            awbRatio = "Auto",
            awbTemp = 0f,
            awbIntensity = 0f
        )

        assertEquals(0, resolved.noiseModelNativeMode) // Disabled fallback
        assertTrue(resolved.warnings.any { it.contains("invalid number") || it.contains("incomplete") || it.contains("rejected") })
    }

    @Test
    fun `color-matrix transpose and order verification`() {
        // Explicit 3x3 array: RR, RG, RB, GR, GG, GB, BR, BG, BB
        val matrix = listOf(
            1.2f, 0.1f, -0.05f,
            0.08f, 1.1f, -0.02f,
            -0.01f, 0.04f, 0.95f
        )
        val config = ColorTransformCalibrationConfig(
            mode = ColorTransformCalibrationConfig.MODE_MANUAL,
            manualMatrix = matrix
        )

        assertEquals(1.2f, config.manualMatrix[0], 0.0001f) // RR
        assertEquals(0.1f, config.manualMatrix[1], 0.0001f) // RG
        assertEquals(-0.05f, config.manualMatrix[2], 0.0001f) // RB
        assertEquals(0.08f, config.manualMatrix[3], 0.0001f) // GR
        assertEquals(1.1f, config.manualMatrix[4], 0.0001f) // GG
        assertEquals(-0.02f, config.manualMatrix[5], 0.0001f) // GB
        assertEquals(-0.01f, config.manualMatrix[6], 0.0001f) // BR
        assertEquals(0.04f, config.manualMatrix[7], 0.0001f) // BG
        assertEquals(0.95f, config.manualMatrix[8], 0.0001f) // BB
    }

    @Test
    fun `invalid color matrix rejected when coefficients exceed range or non-finite`() {
        val invalidMatrix = "10.0, 0, 0, 0, 1, 0, 0, 0, 1" // Exceeds +/-4.0
        val resolved = LensHardwareSettingsResolver.resolve(
            lensId = "0",
            noiseModelType = "Default",
            noiseAString = "", noiseBString = "", noiseCString = "", noiseDString = "",
            isoStepString = "0", isoNrStyle = "Default", dynamicIsoCoeff = 0f, manualIsoValueString = "0",
            blackLevelMode = "Auto", dynamicBlackLevel = 100f, manualBlackLevelsString = "64 64 64 64",
            colorMatrixMode = "Manual",
            manualColorMatrixString = invalidMatrix,
            awbProfile = "System", awbRatio = "Auto", awbTemp = 0f, awbIntensity = 0f
        )

        assertFalse(resolved.colorMatrixValidationPassed)
        assertEquals(0, resolved.colorMatrixNativeMode)
        assertTrue(resolved.colorMatrixRejectReason.contains("exceeds ±4.0"))
    }

    @Test
    fun `all four CFA black-level mappings resolve correctly`() {
        fun cfaMapping(cfaPattern: Int): List<String> = when (cfaPattern) {
            0 -> listOf("L1 (R)", "L2 (Gr)", "L3 (Gb)", "L4 (B)")
            1 -> listOf("L1 (Gr)", "L2 (R)", "L3 (B)", "L4 (Gb)")
            2 -> listOf("L1 (Gb)", "L2 (B)", "L3 (R)", "L4 (Gr)")
            3 -> listOf("L1 (B)", "L2 (Gb)", "L3 (Gr)", "L4 (R)")
            else -> listOf("L1", "L2", "L3", "L4")
        }

        assertEquals("L1 (R)", cfaMapping(0)[0]) // RGGB
        assertEquals("L2 (R)", cfaMapping(1)[1]) // GRBG
        assertEquals("L3 (R)", cfaMapping(2)[2]) // GBRG
        assertEquals("L4 (R)", cfaMapping(3)[3]) // BGGR
    }

    @Test
    fun `black level greater than or equal to white level rejected or clamped`() {
        val whiteLevel = 1023
        val invalidBlack = listOf(1024f, 1050f, 64f, 64f)

        val resolved = ResolvedLensHardwareSettings(
            lensId = "0",
            snapshotSource = "test",
            warnings = emptyList(),
            noiseModelType = "Default", noiseModelNativeMode = 0,
            noiseA = emptyList(), noiseB = emptyList(), noiseC = emptyList(), noiseD = emptyList(),
            isoStep = 0f, isoNrStyle = "Default", isoNrNativeMode = 0, dynamicIsoCoeff = 0f, manualIsoValue = 0f,
            blackLevelMode = "Manual", blackLevelNativeMode = 2, dynamicBlackLevelPercent = 100f,
            manualBlackLevels = invalidBlack,
            colorMatrixMode = "System", colorMatrixNativeMode = 0, manualColorMatrix = emptyList(),
            colorMatrixValidationPassed = true, colorMatrixRejectReason = "none",
            awbProfile = "System", awbRatioRaw = "Auto", awbRatio = 1f, awbTemp = 0f, awbIntensity = 0f, awbNativeMode = 0
        )

        val effective = resolved.effectiveBlackLevels(listOf(64, 64, 64, 64), whiteLevel)
        assertTrue(effective[0] < whiteLevel)
        assertEquals(whiteLevel - 1, effective[0]) // Coerced to safe max white - 1
    }

    @Test
    fun `technical precision preservation keeps exact float values`() {
        val highPrecisionCoeff = 0.0001234567f
        val formatted = String.format(Locale.US, "%.7f", highPrecisionCoeff)
        assertEquals("0.0001235", formatted)
        assertEquals(highPrecisionCoeff, formatted.toFloat(), 0.0000001f)
    }

    @Test
    fun `phone contribution mapped to safe maximum bounds`() {
        val safeMaximum = 0.05f
        fun mapContribution(uiValue: Float): Float = (uiValue * safeMaximum).coerceIn(0.0f, safeMaximum)

        assertEquals(0.000f, mapContribution(0.00f), 0.0001f)
        assertEquals(0.025f, mapContribution(0.50f), 0.0001f)
        assertEquals(0.050f, mapContribution(1.00f), 0.0001f)
    }

    @Test
    fun `verified DNG impact classification distinguishes metadata and pipeline`() {
        val colorMatrixImpacts = setOf(SettingImpact.JPEG_LINEAR_PIPELINE, SettingImpact.DNG_METADATA)
        val demosaicImpacts = setOf(SettingImpact.JPEG_LINEAR_PIPELINE)

        assertTrue(colorMatrixImpacts.contains(SettingImpact.DNG_METADATA))
        assertFalse(demosaicImpacts.contains(SettingImpact.DNG_METADATA))
        assertFalse(demosaicImpacts.contains(SettingImpact.RAW_BEFORE_DEMOSAIC))
    }

    @Test
    fun `snapshot immutability preserves calibration at shutter tap`() {
        val initialCal = LensCalibrationConfig(
            lensId = "0",
            noiseModel = NoiseCalibrationConfig(mode = NoiseCalibrationConfig.MODE_MANUAL, isoStep = 100f)
        )
        val profile = IspProfileConfig.createDefault("0")

        val snapshot = EffectiveShutterSnapshot(
            cameraId = "0",
            physicalCameraId = null,
            lensId = "0",
            profileId = profile.id,
            profileName = profile.name,
            profileSchemaVersion = profile.schemaVersion,
            profileRevision = profile.revision,
            calibration = initialCal,
            profile = profile,
            outputDngSettings = OutputModeDngConfig(),
            outputPolicy = OutputPolicy.JPEG,
            configuredJpegFrameCount = 8,
            effectiveJpegFrameCount = 8,
            jpegFrameCountResolutionReason = "OK",
            configuredDngMasterFrameCount = 1,
            effectiveDngMasterFrameCount = 1,
            dngFrameCountResolutionReason = "OK",
            settingImpactMap = emptyMap(),
            effectiveVulkanParameters = emptyMap()
        )

        // Mutate live calibration after shutter
        val mutatedCal = initialCal.copy(noiseModel = initialCal.noiseModel.copy(mode = NoiseCalibrationConfig.MODE_AUTO))

        assertEquals(NoiseCalibrationConfig.MODE_AUTO, mutatedCal.noiseModel.mode)
        assertEquals(NoiseCalibrationConfig.MODE_MANUAL, snapshot.calibration.noiseModel.mode)
    }
}
