package com.bncam.data.profile

import com.bncam.core.capture.EffectiveShutterSnapshot
import com.bncam.core.capture.OutputPolicy
import com.bncam.data.settings.LensCalibrationConfig
import com.bncam.data.settings.OutputModeDngConfig
import com.bncam.data.settings.SettingsRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class Phase4Block4Test {

    @Test
    fun `stable lens key uses lens_v2 and 128-bit hash suffix`() {
        val key1 = SettingsRepository.buildStableLensKey("0", "physical_main")
        val key2 = SettingsRepository.buildStableLensKey("0", "physical_tele")

        assertNotEquals(key1, key2)
        assertTrue(key1.startsWith("lens_v2_"))
        // 32 hex chars suffix for 16 bytes (128-bit)
        val hashPart = key1.substringAfterLast("_")
        assertEquals(32, hashPart.length)
        assertTrue(hashPart.all { ch -> ch.isLetterOrDigit() })
    }

    @Test
    fun `ownerStableLensKey is preserved in profile config`() {
        val profile = IspProfileConfig.createDefault("lens_v2_main", "Test Profile")
        assertEquals("lens_v2_main", profile.ownerStableLensKey)
        assertEquals("lens_v2_main", profile.ownerLensId)
    }

    @Test
    fun `jpeg quality is not embedded in profile metadata`() {
        val fields = IspProfileConfig::class.java.declaredFields.map { it.name }
        assertFalse(fields.contains("jpegQuality"))
        assertFalse(fields.contains("groupF"))
    }

    @Test
    fun `effective shutter snapshot freezes immutable shutter state`() {
        val calibration = LensCalibrationConfig(lensId = "lens_v2_0")
        val profile = IspProfileConfig.createDefault("lens_v2_0")
        val outputDng = OutputModeDngConfig(dngSourcePolicy = com.bncam.data.settings.DngSourcePolicy.ANCHOR_RAW, dngMasterFrameCount = 1)
        val snapshot = EffectiveShutterSnapshot(
            stableLensKey = "lens_v2_0", logicalCameraId = "0", physicalCameraId = null,
            profileId = profile.id, profileName = profile.name, profileRevision = profile.revision,
            configuredJpegFrameCount = 1, effectiveJpegFrameCount = 1,
            jpegFrameCountResolutionReason = "SINGLE_FRAME",
            configuredDngMasterFrameCount = outputDng.dngMasterFrameCount,
            effectiveDngMasterFrameCount = outputDng.dngMasterFrameCount,
            dngFrameCountResolutionReason = "EXACT_CONFIGURED", calibration = calibration,
            profile = profile, outputDngSettings = outputDng, outputPolicy = OutputPolicy.JPEG,
            dngSourcePolicy = "ANCHOR_RAW", effectiveVulkanParameters = mapOf("tone_contrast" to 0.0f)
        )
        assertNotNull(snapshot.snapshotId)
        assertEquals(5, snapshot.profileSchemaVersion)
        val updatedProfile = profile.copy(name = "Edited", revision = profile.revision + 1L)
        assertNotEquals(updatedProfile.name, snapshot.profile.name)
        assertEquals(profile.revision, snapshot.profileRevision)
    }

    @Test
    fun `signed adjustment zero maps to authoritative baseline`() {
        val spec = IspControlSpec.SIGNED_ADJUSTMENT
        val baseline = 1.00f
        val effectiveZero = spec.mapToEffective(0.00f, 0.20f, baseline, 2.00f)
        assertEquals(baseline, effectiveZero, 0.001f)

        val effectiveNegative = spec.mapToEffective(-0.50f, 0.20f, baseline, 2.00f)
        assertTrue(effectiveNegative < baseline)

        val effectivePositive = spec.mapToEffective(0.50f, 0.20f, baseline, 2.00f)
        assertTrue(effectivePositive > baseline)
    }

    @Test
    fun `positive gain maps normalized value to safe maximum`() {
        val spec = IspControlSpec.POSITIVE_GAIN
        val safeMax = 0.050f
        val effectiveHalf = spec.mapToEffective(0.50f, 0.00f, 0.00f, safeMax)
        assertEquals(0.025f, effectiveHalf, 0.001f)

        val effectiveFull = spec.mapToEffective(1.00f, 0.00f, 0.00f, safeMax)
        assertEquals(safeMax, effectiveFull, 0.001f)
    }

    @Test
    fun `raw only mode keeps DNG policy independent from profile metadata`() {
        val profile = IspProfileConfig.createDefault("lens_v2_0")
        val rawOnlyDng = OutputModeDngConfig(dngSourcePolicy = com.bncam.data.settings.DngSourcePolicy.ANCHOR_RAW, dngMasterFrameCount = 1)
        val snapshot = EffectiveShutterSnapshot(
            stableLensKey = "lens_v2_0", logicalCameraId = "0", physicalCameraId = null,
            profileId = profile.id, profileName = profile.name, profileRevision = profile.revision,
            configuredJpegFrameCount = 0, effectiveJpegFrameCount = 0,
            jpegFrameCountResolutionReason = "RAW_ONLY",
            configuredDngMasterFrameCount = 1, effectiveDngMasterFrameCount = 1,
            dngFrameCountResolutionReason = "EXACT_CONFIGURED",
            calibration = LensCalibrationConfig(lensId = "lens_v2_0"), profile = profile,
            outputDngSettings = rawOnlyDng, outputPolicy = OutputPolicy.RAW_ONLY, dngSourcePolicy = "ANCHOR_RAW"
        )
        assertEquals(OutputPolicy.RAW_ONLY, snapshot.outputPolicy)
        assertEquals(0, snapshot.effectiveJpegFrameCount)
        assertEquals(1, snapshot.effectiveDngMasterFrameCount)
    }

    @Test
    fun `metadata edits preserve identity and explicitly advance revision`() {
        val original = IspProfileConfig.createDefault("lens_v2_0")
        val edited = original.copy(name = "Edited", revision = original.revision + 1L)
        assertEquals(original.revision + 1L, edited.revision)
        assertEquals(original.id, edited.id)
        assertEquals(original.ownerStableLensKey, edited.ownerStableLensKey)
    }

}
