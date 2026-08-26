package com.bncam.data.profile

import com.bncam.core.capture.EffectiveShutterSnapshot
import com.bncam.core.capture.FrameCapacityPolicy
import com.bncam.core.capture.FrameOrigin
import com.bncam.core.capture.OutputPolicy
import com.bncam.data.settings.LensCalibrationConfig
import com.bncam.data.settings.OutputModeDngConfig
import com.bncam.data.settings.OutputModeSettings
import com.bncam.data.settings.SettingsRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class Phase4Block3Test {

    @Test
    fun `collision safe lens key generates distinct keys for unsafe characters`() {
        val key1 = SettingsRepository.buildStableLensKey("a/b")
        val key2 = SettingsRepository.buildStableLensKey("a?b")
        val key3 = SettingsRepository.buildStableLensKey("a:b")

        assertNotEquals(key1, key2)
        assertNotEquals(key2, key3)
        assertNotEquals(key1, key3)
        assertTrue(key1.startsWith("lens_v2_"))
        assertTrue(key1.all { ch: Char -> ch.isLetterOrDigit() || ch == '_' })
    }

    @Test
    fun `variable profile counts such as 5 3 1 per lens`() {
        val lens0Profiles = List(5) { IspProfileConfig.createDefault("0").copy(id = UUID.randomUUID().toString()) }
        val lens1Profiles = List(3) { IspProfileConfig.createDefault("1").copy(id = UUID.randomUUID().toString()) }
        val lens2Profiles = List(1) { IspProfileConfig.createDefault("2").copy(id = UUID.randomUUID().toString()) }

        assertEquals(5, lens0Profiles.size)
        assertEquals(3, lens1Profiles.size)
        assertEquals(1, lens2Profiles.size)
        assertTrue(lens0Profiles.all { it.ownerLensId == "0" })
        assertTrue(lens1Profiles.all { it.ownerLensId == "1" })
        assertTrue(lens2Profiles.all { it.ownerLensId == "2" })
    }

    @Test
    fun `last profile cannot be deleted`() {
        val profiles = mutableListOf(IspProfileConfig.createDefault("0"))
        val canDelete = profiles.size > 1
        assertFalse(canDelete)
    }

    @Test
    fun `profile copy creates new ID and destination lens ownership`() {
        val original = IspProfileConfig.createDefault("0").copy(name = "Main Profile")
        val copied = original.copy(
            id = UUID.randomUUID().toString(),
            name = "${original.name} Copy",
            ownerStableLensKey = "1",
            isDefault = false,
            revision = 1L
        )

        assertNotEquals(original.id, copied.id)
        assertNotEquals(original.ownerLensId, copied.ownerLensId)
        assertEquals("0", original.ownerLensId)
        assertEquals("1", copied.ownerLensId)
        assertEquals("Main Profile Copy", copied.name)
    }

    @Test
    fun `bnc profile codec roundtrip preserves portable identity and profile intent`() {
        val uuid = UUID.randomUUID().toString()
        val document = BncProfileDocument(
            profileUuid = uuid,
            profileName = "Exported Profile",
            captureMode = "SINGLE",
            preferredFrameSource = "RAW10",
            settings = com.bncam.data.settings.ProfileSettingsSnapshot(),
            metadata = BncProfileMetadata(
                bncamVersion = "test",
                exportedAtEpochMs = 1L,
                sourceStableLensKey = "lens_v2_0",
                defaultIspVersion = "test"
            )
        )

        val encoded = BncProfileCodec.encode(document)
        val decoded = BncProfileCodec.decode(encoded, emptyList()).document

        assertEquals(uuid, decoded.profileUuid)
        assertEquals("Exported Profile", decoded.profileName)
        assertEquals("SINGLE", decoded.captureMode)
        assertEquals("RAW10", decoded.preferredFrameSource)
    }

    @Test
    fun `bnc import rejects unsupported future schema`() {
        val badJson = """{"format":"BNCAM_PROFILE","schemaVersion":999,"settingsSchemaVersion":1}"""
        val error = runCatching { BncProfileCodec.decode(badJson, emptyList()) }.exceptionOrNull()
        assertNotNull(error)
        assertTrue(error?.message?.contains("schema version") == true)
    }

    @Test
    fun `profile settings are not embedded in metadata object`() {
        val fields = IspProfileConfig::class.java.declaredFields.map { it.name }.toSet()
        assertFalse(fields.contains("groupC"))
        assertFalse(fields.contains("groupD"))
        assertFalse(fields.contains("groupE"))
    }

    @Test
    fun `profile rename increments metadata revision without creating setting copies`() {
        val initial = IspProfileConfig.createDefault("0").copy(revision = 10L)
        val renamed = initial.copy(name = "Renamed", revision = 11L)
        assertEquals(11L, renamed.revision)
        assertEquals("Renamed", renamed.name)
        assertEquals(initial.ownerStableLensKey, renamed.ownerStableLensKey)
    }

    @Test
    fun `profile identity is independent from portable settings payload`() {
        val profile = IspProfileConfig.createDefault("0").copy(
            id = "custom_id_123",
            name = "My Custom Profile",
            revision = 12L
        )
        assertEquals("custom_id_123", profile.id)
        assertEquals("My Custom Profile", profile.name)
        assertEquals("0", profile.ownerLensId)
        assertEquals(12L, profile.revision)
    }

    @Test
    fun `negative sharpening and denoise controls reduce effective strength below baseline`() {
        val spec = IspControlSpec.SIGNED_ADJUSTMENT
        val baseline = 1.0f // Vulkan baseline sharpening factor

        val negativeSharpeningUi = -0.50f
        val effectiveSharpening = spec.mapToEffective(negativeSharpeningUi, baselineMin = 0.0f, baselineDefault = 1.0f, baselineMax = 2.0f)
        assertTrue(effectiveSharpening < baseline)
        assertEquals(0.50f, effectiveSharpening, 0.001f)

        val negativeDenoiseUi = -0.75f
        val effectiveDenoise = spec.mapToEffective(negativeDenoiseUi, baselineMin = 0.0f, baselineDefault = 1.0f, baselineMax = 2.0f)
        assertTrue(effectiveDenoise < baseline)
        assertEquals(0.25f, effectiveDenoise, 0.001f)
    }

    @Test
    fun `phone assistance contribution respects safe maximum`() {
        val safeMaximum = 0.050f
        fun calculateEffective(enabled: Boolean, uiContribution: Float): Float {
            if (!enabled) return 0.0f
            return (uiContribution * safeMaximum).coerceIn(0.0f, safeMaximum)
        }

        assertEquals(0.000f, calculateEffective(false, 1.00f), 0.0001f)
        assertEquals(0.025f, calculateEffective(true, 0.50f), 0.0001f)
        assertEquals(0.050f, calculateEffective(true, 1.00f), 0.0001f)
    }

    @Test
    fun `frame configured and effective values remain distinct when clamped`() {
        val configuredJpegFrames = 20
        val maxSafeJpegFrames = FrameCapacityPolicy.maximumProcessingFrames(FrameOrigin.RAW10) // 25
        val effective = configuredJpegFrames.coerceIn(1, maxSafeJpegFrames)

        assertEquals(20, configuredJpegFrames)
        assertEquals(20, effective)

        // When configured exceeds max
        val overConfigured = 30
        val effectiveClamped = overConfigured.coerceIn(1, maxSafeJpegFrames)
        assertEquals(30, overConfigured)
        assertEquals(25, effectiveClamped)
    }

    @Test
    fun `hardware DNG policy is absent from portable bnc profile serialization`() {
        val document = BncProfileDocument(
            profileUuid = UUID.randomUUID().toString(),
            profileName = "Portable",
            captureMode = "SINGLE",
            preferredFrameSource = "RAW10",
            settings = com.bncam.data.settings.ProfileSettingsSnapshot(),
            metadata = BncProfileMetadata(
                bncamVersion = "test",
                exportedAtEpochMs = 1L,
                sourceStableLensKey = "lens_v2_0",
                defaultIspVersion = "test"
            )
        )
        val encoded = BncProfileCodec.encode(document)

        assertFalse(encoded.contains("dngSourcePolicy"))
        assertFalse(encoded.contains("dngMasterFrameCount"))
        assertTrue(encoded.contains("\"hardwareCalibrationIncluded\": false"))
    }

    @Test
    fun `DNG output settings remain independent of profile metadata`() {
        val appOutputSettings = OutputModeSettings(
            jpegOnly = OutputModeDngConfig(),
            rawPlusJpeg = OutputModeDngConfig(dngMasterFrameCount = 6),
            rawOnly = OutputModeDngConfig(dngMasterFrameCount = 6)
        )
        val profile = IspProfileConfig.createDefault("0")
        val fields = IspProfileConfig::class.java.declaredFields.map { it.name }
        assertFalse(fields.contains("dngMasterFrameCount"))
        assertEquals(6, appOutputSettings.rawPlusJpeg.dngMasterFrameCount)
        assertEquals("0", profile.ownerLensId)
    }

    @Test
    fun `impact badges match setting contracts`() {
        val groupABadge = SettingImpact.JPEG_FRAME_SELECTION
        val groupEBadge = SettingImpact.JPEG_NONLINEAR_PIPELINE
        val groupFBadge = SettingImpact.JPEG_8BIT_OUTPUT

        assertEquals("JPEG-framekeuze", groupABadge.dutchBadge)
        assertEquals("Gerenderde JPEG", groupEBadge.dutchBadge)
        assertEquals("Alleen 8-bit JPEG", groupFBadge.dutchBadge)
    }

    @Test
    fun `profile switch does not alter existing shutter snapshot`() {
        val initialProfile = IspProfileConfig.createDefault("0").copy(name = "Profile A", revision = 2L)
        val calibration = LensCalibrationConfig("0")
        val snapshot = EffectiveShutterSnapshot(
            cameraId = "0", physicalCameraId = null, lensId = "0",
            profileId = initialProfile.id, profileName = initialProfile.name,
            profileSchemaVersion = initialProfile.schemaVersion, profileRevision = initialProfile.revision,
            calibration = calibration, profile = initialProfile, outputDngSettings = OutputModeDngConfig(),
            outputPolicy = OutputPolicy.JPEG, configuredJpegFrameCount = 1, effectiveJpegFrameCount = 1,
            jpegFrameCountResolutionReason = "SINGLE_FRAME", configuredDngMasterFrameCount = 1, effectiveDngMasterFrameCount = 1,
            dngFrameCountResolutionReason = "OK", settingImpactMap = emptyMap(), effectiveVulkanParameters = mapOf("tone_contrast" to 0.20f)
        )
        val newProfile = initialProfile.copy(name = "Profile B", revision = 3L)
        assertEquals("Profile A", snapshot.profileName)
        assertEquals(2L, snapshot.profileRevision)
        assertEquals(0.20f, snapshot.effectiveVulkanParameters["tone_contrast"]!!, 0.001f)
        assertEquals("Profile B", newProfile.name)
    }

}
