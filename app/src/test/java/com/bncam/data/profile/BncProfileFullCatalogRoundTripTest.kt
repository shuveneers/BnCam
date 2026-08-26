package com.bncam.data.profile

import com.bncam.core.quality.LibpatcherProfileResolver
import com.bncam.core.quality.ProfileCurveDefaults
import com.bncam.data.settings.CaptureSettingKeys
import com.bncam.data.settings.ProfileIspKeys
import com.bncam.data.settings.ProfileSettingSpec
import com.bncam.data.settings.ProfileSettingValueType
import com.bncam.data.settings.ProfileSettingsSnapshot
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BncProfileFullCatalogRoundTripTest {
    private fun completeSpecs(): List<ProfileSettingSpec> =
        LibpatcherProfileResolver.allProfileSettingSpecs()

    @Test
    fun `complete production profile catalog has portable defaults and no duplicate typed keys`() {
        val specs = completeSpecs()
        assertTrue("Portable catalog unexpectedly small: ${specs.size}", specs.size >= 70)
        assertEquals(specs.size, specs.distinctBy { "${it.type}:${it.key}" }.size)
        assertTrue(specs.all { it.portableDefault != null })

        val exportedKeys = specs.map { it.key }.toSet()
        val compatibilityOnlyIspKeys = setOf(ProfileIspKeys.SPECTRA_STRENGTH, ProfileIspKeys.LOCAL_TONE_BIAS)
        val expectedPortableIspKeys = ProfileIspKeys::class.java.declaredFields
            .filter { it.type == String::class.java }
            .mapNotNull { it.get(null) as? String }
            .toSet() - compatibilityOnlyIspKeys
        assertTrue(
            "Missing Profile V3 ISP keys: ${expectedPortableIspKeys - exportedKeys}",
            exportedKeys.containsAll(expectedPortableIspKeys)
        )
        assertFalse(ProfileIspKeys.SPECTRA_STRENGTH in exportedKeys)

        val expectedPortableCaptureKeys = setOf(
            CaptureSettingKeys.ALIGNMENT_METHOD,
            CaptureSettingKeys.FUSION_METHOD,
            CaptureSettingKeys.FUSION_FRAMES_YUV,
            CaptureSettingKeys.FUSION_FRAMES_RAW10,
            CaptureSettingKeys.FUSION_FRAMES_RAW_SENSOR,
            CaptureSettingKeys.EXPOSURE_STRATEGY,
            CaptureSettingKeys.SHOT_BIAS_EXPOSURE,
            CaptureSettingKeys.SHOT_BIAS_MAX_FRAME_EXPOSURE,
            CaptureSettingKeys.CAPTURE_EV_BIAS,
            CaptureSettingKeys.DEMOSAIC_METHOD,
            CaptureSettingKeys.JPEG_QUALITY
        )
        assertTrue(
            "Missing portable CaptureSettingKeys: ${expectedPortableCaptureKeys - exportedKeys}",
            exportedKeys.containsAll(expectedPortableCaptureKeys)
        )
        listOf(
            CaptureSettingKeys.EXPOSURE_PRIORITY_MODE,
            CaptureSettingKeys.SHUTTER_PRIORITY_MULTIPLIER,
            CaptureSettingKeys.ISO_PRIORITY_MULTIPLIER,
            CaptureSettingKeys.DNG_MASTER_FRAMES_RAW10,
            CaptureSettingKeys.DNG_MASTER_FRAMES_RAW_SENSOR,
            CaptureSettingKeys.OUTPUT_POLICY,
            CaptureSettingKeys.PHONE_ASSISTANCE_SENSORS
        ).forEach { assertFalse("Non-profile setting leaked into .bnc: $it", it in exportedKeys) }

        listOf("awb_mode", "awb_brand", "awb_preset", "awb_kelvin", "awb_model", "awb_tint")
            .forEach { assertTrue("Missing AWB key $it", it in exportedKeys) }
        val type = ProfileCurveDefaults.TYPE_TONE
        assertTrue(ProfileCurveDefaults.presetKey(type) in exportedKeys)
        repeat(ProfileCurveDefaults.nodeCount(type)) { index ->
            assertTrue(ProfileCurveDefaults.pointKey(type, index) in exportedKeys)
        }
        assertTrue(ProfileCurveDefaults.presetKey(ProfileCurveDefaults.TYPE_GAMMA) in exportedKeys)
        assertTrue(ProfileCurveDefaults.presetKey(ProfileCurveDefaults.TYPE_SECT) in exportedKeys)
        listOf(ProfileCurveDefaults.TYPE_GAMMA, ProfileCurveDefaults.TYPE_SECT).forEach { curveType ->
            repeat(ProfileCurveDefaults.nodeCount(curveType)) { index ->
                assertTrue(ProfileCurveDefaults.pointKey(curveType, index) in exportedKeys)
            }
        }
    }

    @Test
    fun `full production settings snapshot round trips bit-for-bit through current bnc schema`() {
        val specs = completeSpecs()
        val snapshot = representativeSnapshot(specs)
        assertEquals(specs.size, snapshot.totalCount)

        val original = BncProfileDocument(
            profileUuid = UUID.randomUUID().toString(),
            profileName = "Complete Portable Profile",
            captureMode = "MULTI_FRAME_ZSL",
            preferredFrameSource = "RAW_SENSOR",
            settings = snapshot,
            metadata = BncProfileMetadata(
                bncamVersion = "1.0",
                exportedAtEpochMs = 987654321L,
                sourceStableLensKey = "lens_v2_source",
                defaultIspVersion = "isp-test",
                sourceFrameSources = setOf("YUV", "RAW10", "RAW_SENSOR")
            )
        )

        val decoded = BncProfileCodec.decode(BncProfileCodec.encode(original), specs)
        assertEquals(original, decoded.document)
        assertEquals(snapshot, decoded.document.settings)
        assertTrue(decoded.ignoredUnknownSettingKeys.isEmpty())
        assertTrue(decoded.fallbackSettingKeys.isEmpty())
    }

    @Test
    fun `same sensor transfer keeps source and full settings unchanged`() {
        val specs = completeSpecs()
        val document = portableDocument(specs, "RAW10")
        val decoded = BncProfileCodec.decode(BncProfileCodec.encode(document), specs).document
        val resolution = BncProfileCompatibility.resolveFrameSource(
            decoded.preferredFrameSource,
            BncTargetCapabilities(setOf("YUV", "RAW10", "RAW_SENSOR"))
        )

        assertFalse(resolution.fallbackOccurred)
        assertEquals("RAW10", resolution.effective)
        assertEquals(document.settings, decoded.settings)
        assertEquals(document.profileName, decoded.profileName)
        assertEquals(document.profileUuid, decoded.profileUuid)
    }

    @Test
    fun `main raw sensor profile imported on tele keeps settings and selects closest supported raw route`() {
        val specs = completeSpecs()
        val main = portableDocument(specs, "RAW_SENSOR")
        val decoded = BncProfileCodec.decode(BncProfileCodec.encode(main), specs).document
        val tele = BncTargetCapabilities(setOf("YUV", "RAW10"))
        val resolution = BncProfileCompatibility.resolveFrameSource(decoded.preferredFrameSource, tele)

        assertTrue(resolution.fallbackOccurred)
        assertEquals("RAW10", resolution.effective)
        assertEquals(main.settings, decoded.settings)
        assertEquals(main.profileName, decoded.profileName)
    }

    @Test
    fun `device A profile imported on YUV-only device B keeps configuration with explicit source fallback`() {
        val specs = completeSpecs()
        val deviceA = portableDocument(specs, "RAW10")
        val decoded = BncProfileCodec.decode(BncProfileCodec.encode(deviceA), specs).document
        val resolution = BncProfileCompatibility.resolveFrameSource(
            decoded.preferredFrameSource,
            BncTargetCapabilities(setOf("YUV"))
        )

        assertTrue(resolution.fallbackOccurred)
        assertEquals("YUV", resolution.effective)
        assertEquals(deviceA.settings, decoded.settings)
    }

    private fun portableDocument(specs: List<ProfileSettingSpec>, frameSource: String) = BncProfileDocument(
        profileUuid = UUID.randomUUID().toString(),
        profileName = "Portable Cross Lens",
        captureMode = "MULTI_FRAME_ZSL",
        preferredFrameSource = frameSource,
        settings = representativeSnapshot(specs),
        metadata = BncProfileMetadata(
            bncamVersion = "1.0",
            exportedAtEpochMs = 1L,
            sourceStableLensKey = "lens_v2_device_a_main",
            defaultIspVersion = "isp-test",
            sourceFrameSources = setOf("YUV", "RAW10", "RAW_SENSOR")
        )
    )

    private fun representativeSnapshot(specs: List<ProfileSettingSpec>): ProfileSettingsSnapshot {
        val floats = linkedMapOf<String, Float>()
        val ints = linkedMapOf<String, Int>()
        val booleans = linkedMapOf<String, Boolean>()
        val strings = linkedMapOf<String, String>()
        specs.forEachIndexed { index, spec ->
            when (spec.type) {
                ProfileSettingValueType.FLOAT -> floats[spec.key] = when {
                    spec.key == ProfileIspKeys.SPECTRA_DYNAMIC_ISO -> 0.55f
                    spec.key == ProfileIspKeys.DETAIL_NR_LUMINANCE -> 0.45f
                    spec.key == ProfileIspKeys.DETAIL_NR_LUMINANCE_DETAIL -> 0.60f
                    spec.key == ProfileIspKeys.DETAIL_NR_LUMINANCE_CONTRAST -> 0.35f
                    spec.key == ProfileIspKeys.DETAIL_NR_COLOR -> 0.50f
                    spec.key == ProfileIspKeys.DETAIL_NR_COLOR_DETAIL -> 0.55f
                    spec.key == ProfileIspKeys.DETAIL_NR_COLOR_SMOOTHNESS -> 0.65f
                    spec.key.startsWith("curve_") && spec.key.contains("_point_") -> ((index % 8) + 1) / 10f
                    spec.key == CaptureSettingKeys.MERGE_STRICTNESS -> 0.65f
                    else -> if (index % 2 == 0) 0.35f else -0.25f
                }
                ProfileSettingValueType.INT -> ints[spec.key] = when (spec.key) {
                    CaptureSettingKeys.JPEG_QUALITY -> 94
                    "awb_kelvin" -> 6100
                    ProfileIspKeys.SPECTRA_ENABLED -> 1
                    CaptureSettingKeys.FUSION_FRAMES_YUV -> 7
                    CaptureSettingKeys.FUSION_FRAMES_RAW10 -> 6
                    CaptureSettingKeys.FUSION_FRAMES_RAW_SENSOR -> 4
                    CaptureSettingKeys.DNG_MASTER_FRAMES_RAW10 -> 2
                    CaptureSettingKeys.DNG_MASTER_FRAMES_RAW_SENSOR -> 2
                    CaptureSettingKeys.BASE_CANDIDATES -> 4
                    CaptureSettingKeys.SELECTION_REQUESTED_FRAMES -> 6
                    CaptureSettingKeys.MERGE_MAX_SHIFT -> 128
                    else -> spec.portableDefault!!.toInt()
                }
                ProfileSettingValueType.BOOLEAN -> booleans[spec.key] = !spec.portableDefault!!.toBooleanStrict()
                ProfileSettingValueType.STRING -> strings[spec.key] = spec.portableDefault!!
            }
        }
        return ProfileSettingsSnapshot(floats, ints, booleans, strings)
    }
}
