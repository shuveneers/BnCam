package com.bncam.data.profile

import com.bncam.core.capture.EffectiveShutterSnapshot
import com.bncam.core.capture.OutputPolicy
import com.bncam.data.settings.DngSourcePolicy
import com.bncam.data.settings.LensCalibrationConfig
import com.bncam.data.settings.OutputModeDngConfig
import com.bncam.data.settings.OutputModeSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class Phase4Block1Test {

    @Test
    fun `signed adjustment accepts -1_00 0_00 and +1_00`() {
        val minSpec = IspControlValue.createSigned("sharpeningAmount", -1.00f, 0.0f, 1.0f, 3.0f, setOf(SettingImpact.JPEG_NONLINEAR_PIPELINE))
        val zeroSpec = IspControlValue.createSigned("sharpeningAmount", 0.00f, 0.0f, 1.0f, 3.0f, setOf(SettingImpact.JPEG_NONLINEAR_PIPELINE))
        val maxSpec = IspControlValue.createSigned("sharpeningAmount", 1.00f, 0.0f, 1.0f, 3.0f, setOf(SettingImpact.JPEG_NONLINEAR_PIPELINE))

        assertEquals("-1.00", minSpec.displayString)
        assertEquals("0.00", zeroSpec.displayString)
        assertEquals("1.00", maxSpec.displayString)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `signed adjustment rejects values below -1_00`() {
        IspControlValue.createSigned("test", -1.05f, 0.0f, 1.0f, 3.0f, emptySet())
    }

    @Test(expected = IllegalArgumentException::class)
    fun `signed adjustment rejects values above +1_00`() {
        IspControlValue.createSigned("test", 1.05f, 0.0f, 1.0f, 3.0f, emptySet())
    }

    @Test
    fun `gain control accepts 0_00 and 1_00`() {
        val minSpec = IspControlValue.createGain("edgeGain", 0.00f, 0.0f, 0.0f, 1.0f, setOf(SettingImpact.JPEG_NONLINEAR_PIPELINE))
        val maxSpec = IspControlValue.createGain("edgeGain", 1.00f, 0.0f, 0.0f, 1.0f, setOf(SettingImpact.JPEG_NONLINEAR_PIPELINE))

        assertEquals("0.00", minSpec.displayString)
        assertEquals("1.00", maxSpec.displayString)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `gain control rejects negative values`() {
        IspControlValue.createGain("edgeGain", -0.01f, 0.0f, 0.0f, 1.0f, emptySet())
    }

    @Test
    fun `all continuous values serialize to two-decimal precision`() {
        val spec = IspControlValue.createSigned("test", 0.12345f, 0.0f, 1.0f, 2.0f, emptySet())
        assertEquals("0.12", spec.displayString)
        assertEquals(0.12f, spec.uiValue, 0.0001f)
    }

    @Test
    fun `0_00 resolves to authoritative ISP baseline`() {
        val spec = IspControlValue.createSigned("sharpeningAmount", 0.00f, 0.0f, 1.0f, 3.0f, emptySet())
        assertEquals(1.00f, spec.effectiveVulkanValue, 0.001f)
    }

    @Test
    fun `negative sharpening reduces effective sharpening below baseline`() {
        val spec = IspControlValue.createSigned("sharpeningAmount", -0.50f, 0.0f, 1.0f, 3.0f, emptySet())
        assertEquals(0.50f, spec.effectiveVulkanValue, 0.001f)
        assertTrue(spec.effectiveVulkanValue < 1.00f)
    }

    @Test
    fun `negative denoise reduces effective denoise below baseline`() {
        val spec = IspControlValue.createSigned("rawLumaDenoise", -1.00f, 0.0f, 1.0f, 3.0f, emptySet())
        assertEquals(0.00f, spec.effectiveVulkanValue, 0.001f)
    }

    @Test
    fun `profile metadata contains no embedded ISP setting groups`() {
        val fields = IspProfileConfig::class.java.declaredFields.map { it.name }.toSet()
        assertFalse(fields.any { it.matches(Regex("group[A-F]")) })
        assertFalse(fields.contains("sharpeningAmount"))
        assertFalse(fields.contains("jpegQuality"))
    }

    @Test
    fun `profile metadata revision is explicit and independent from settings`() {
        val initial = IspProfileConfig.createDefault("lens0").copy(revision = 10L)
        val editedMetadata = initial.copy(name = "Renamed", revision = initial.revision + 1L)
        assertEquals(11L, editedMetadata.revision)
        assertEquals("Renamed", editedMetadata.name)
        assertEquals(initial.ownerStableLensKey, editedMetadata.ownerStableLensKey)
    }

    @Test
    fun `profile metadata ownership remains lens specific`() {
        val profileLens0 = IspProfileConfig.createDefault("0")
        val profileLens1 = IspProfileConfig.createDefault("1")
        assertEquals("0", profileLens0.targetLensId)
        assertEquals("1", profileLens1.targetLensId)
        assertNotEquals(profileLens0.id, profileLens1.id)
    }

    @Test
    fun `DNG fusion settings are not stored in profiles`() {
        val profile = IspProfileConfig.createDefault("0")
        // Verify IspProfileConfig does not contain dngMasterFrameCount or dngSourcePolicy
        val fields = IspProfileConfig::class.java.declaredFields.map { it.name }
        assertFalse(fields.contains("dngMasterFrameCount"))
        assertFalse(fields.contains("dngSourcePolicy"))
    }

    @Test
    fun `RAW+JPEG and RAW-only resolve independent DNG frame settings`() {
        val appSettings = OutputModeSettings(
            jpegOnly = OutputModeDngConfig(dngSourcePolicy = DngSourcePolicy.ANCHOR_RAW, dngMasterFrameCount = 1),
            rawPlusJpeg = OutputModeDngConfig(dngSourcePolicy = DngSourcePolicy.FUSED_RAW, dngMasterFrameCount = 6),
            rawOnly = OutputModeDngConfig(dngSourcePolicy = DngSourcePolicy.FUSED_RAW, dngMasterFrameCount = 4)
        )

        assertEquals(DngSourcePolicy.ANCHOR_RAW, appSettings.getConfigForOutputPolicy(OutputPolicy.JPEG).dngSourcePolicy)
        assertEquals(1, appSettings.getConfigForOutputPolicy(OutputPolicy.JPEG).dngMasterFrameCount)

        assertEquals(DngSourcePolicy.FUSED_RAW, appSettings.getConfigForOutputPolicy(OutputPolicy.JPEG_PLUS_RAW).dngSourcePolicy)
        assertEquals(6, appSettings.getConfigForOutputPolicy(OutputPolicy.JPEG_PLUS_RAW).dngMasterFrameCount)

        assertEquals(DngSourcePolicy.FUSED_RAW, appSettings.getConfigForOutputPolicy(OutputPolicy.RAW_ONLY).dngSourcePolicy)
        assertEquals(4, appSettings.getConfigForOutputPolicy(OutputPolicy.RAW_ONLY).dngMasterFrameCount)
    }

    @Test
    fun `in-flight snapshots remain immutable during metadata edits`() {
        val profile = IspProfileConfig.createDefault("0").copy(name = "Profile A", revision = 3L)
        val snapshot = EffectiveShutterSnapshot(
            cameraId = "0",
            physicalCameraId = null,
            lensId = "0",
            profileId = profile.id,
            profileName = profile.name,
            profileSchemaVersion = profile.schemaVersion,
            profileRevision = profile.revision,
            calibration = LensCalibrationConfig(lensId = "0"),
            profile = profile,
            outputDngSettings = OutputModeDngConfig(),
            outputPolicy = OutputPolicy.JPEG,
            configuredJpegFrameCount = 1,
            effectiveJpegFrameCount = 1,
            jpegFrameCountResolutionReason = "SINGLE_FRAME",
            configuredDngMasterFrameCount = 1,
            effectiveDngMasterFrameCount = 1,
            dngFrameCountResolutionReason = "OK",
            settingImpactMap = emptyMap(),
            effectiveVulkanParameters = mapOf("tone_contrast" to 0.50f)
        )
        val edited = profile.copy(name = "Profile B", revision = 4L)
        assertEquals("Profile A", snapshot.profileName)
        assertEquals(3L, snapshot.profileRevision)
        assertEquals(0.50f, snapshot.effectiveVulkanParameters["tone_contrast"]!!, 0.001f)
        assertEquals("Profile B", edited.name)
        assertEquals(4L, edited.revision)
    }

}
