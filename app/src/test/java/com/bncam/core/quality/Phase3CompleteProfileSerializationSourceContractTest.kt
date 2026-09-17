package com.bncam.core.quality

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Phase3CompleteProfileSerializationSourceContractTest {
    private fun appDir(): File = sequenceOf(File("."), File("app"))
        .firstOrNull { File(it, "src/main/java/com/bncam/data/settings/SettingsRepository.kt").isFile }
        ?: error("Cannot locate app module")

    @Test
    fun `bnc export materializes every portable profile setting`() {
        val app = appDir()
        val repo = File(app, "src/main/java/com/bncam/data/settings/SettingsRepository.kt").readText()
        val resolver = File(app, "src/main/java/com/bncam/core/quality/LibpatcherProfileResolver.kt").readText()

        assertTrue(repo.contains("includePortableDefaults = true"))
        assertTrue(repo.contains("readPortableEffectiveProfileSnapshot"))
        assertTrue(repo.contains("missingPortableDefaults"))
        assertTrue(repo.contains("hardwareCalibrationIncluded"))

        // Eyecatcher/noise module.
        assertTrue(resolver.contains("add(i(ProfileIspKeys.SPECTRA_ENABLED, 0))"))
        assertTrue(resolver.contains("ProfileSettingSpec(ProfileIspKeys.NEURAL_ADAPTIVE_RESPONSE, ProfileSettingValueType.FLOAT, SpectraProfileDefaults.ADAPTIVE_RESPONSE.toString())"))
        val runtimeSpecs = resolver.substringAfter("fun runtimeProfileSettingSpecs").substringBefore("private fun legacyNoiseCompatibilitySpecs")
        assertFalse(runtimeSpecs.contains("ProfileIspKeys.NEURAL_ADAPTIVE_RESPONSE"))
        assertTrue(resolver.contains("add(f(ProfileIspKeys.SPECTRA_CHROMA, SpectraProfileDefaults.CHROMA))"))

        // Presence and the four Lightroom-style Detail sharpening authorities.
        assertTrue(resolver.contains("add(f(ProfileIspKeys.PRESENCE_SATURATION))"))
        assertFalse(resolver.contains("ProfileIspKeys.COLOR_CONTRAST"))
        assertFalse(resolver.contains("ProfileIspKeys.COLOR_HUE_SHIFT"))
        assertTrue(resolver.contains("add(f(ProfileIspKeys.DETAIL_SHARPENING_AMOUNT, ProfileDetailDefaults.AMOUNT))"))
        assertTrue(resolver.contains("add(f(ProfileIspKeys.DETAIL_SHARPENING_RADIUS, ProfileDetailDefaults.RADIUS))"))
        assertTrue(resolver.contains("add(f(ProfileIspKeys.DETAIL_SHARPENING_DETAIL, ProfileDetailDefaults.DETAIL))"))
        assertTrue(resolver.contains("add(f(ProfileIspKeys.DETAIL_SHARPENING_MASKING, ProfileDetailDefaults.MASKING))"))
        assertFalse(resolver.contains("ProfileIspKeys.SHARPNESS_GLOBAL"))

        // Tone/Gamma/Sect presets and every point are generated from the same complete spec list.
        assertTrue(resolver.contains("ProfileCurveDefaults.TYPE_TONE"))
        assertTrue(resolver.contains("ProfileCurveDefaults.TYPE_GAMMA"))
        assertTrue(resolver.contains("ProfileCurveDefaults.TYPE_SECT"))
        assertTrue(resolver.contains("ProfileCurveDefaults.pointKey(type, index)"))
        assertTrue(resolver.contains("DefaultIspProfile.curveNodes(type)"))
    }

    @Test
    fun `bnc import replaces stale portable slot state atomically but preserves hardware boundary`() {
        val app = appDir()
        val repo = File(app, "src/main/java/com/bncam/data/settings/SettingsRepository.kt").readText()
        val editor = File(app, "src/main/java/com/bncam/ui/screens/settings/lens_profiles/ProfileEditScreen.kt").readText()

        assertTrue(repo.contains("val decoded = BncProfileCodec.decode(raw, safeSpecs)"))
        assertTrue(repo.contains("context.dataStore.edit { preferences ->"))
        assertTrue(repo.contains("hardwareCalibrationIncluded = false"))
        assertTrue(!repo.contains("suspend fun importProfileSettingsJson("))
        assertTrue(editor.contains("repo.getProfileNameFlow(profileId, initialName)"))
        assertTrue(!editor.contains("repo.setProfileString(profileId, \"profile_name\", tempName)"))
    }
}
