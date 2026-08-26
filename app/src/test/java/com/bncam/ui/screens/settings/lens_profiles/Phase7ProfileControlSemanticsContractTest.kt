package com.bncam.ui.screens.settings.lens_profiles

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

class Phase7ProfileControlSemanticsContractTest {
    private fun source(path: String): String {
        val roots = listOf(File("."), File("app"))
        val file = roots.asSequence().map { File(it, path) }.firstOrNull { it.isFile }
            ?: error("Missing source file: $path")
        return file.readText()
    }

    @Test
    fun signedProfileControlsUseSignedSliderContract() {
        val src = source("src/main/java/com/bncam/ui/screens/settings/lens_profiles/ProfileTuningSubScreens.kt")
        val signedKeys = listOf(
            "ProfileIspKeys.SPECTRA_LUMA",
            "ProfileIspKeys.SPECTRA_CHROMA",
            "ProfileIspKeys.SPECTRA_DETAIL",
            "ProfileIspKeys.SPECTRA_LOW_FREQUENCY",
            "ProfileIspKeys.PRESENCE_SATURATION"
        )
        signedKeys.forEach { key ->
            val keyIndex = src.indexOf("key = $key")
            assertTrue(keyIndex >= 0, "Missing profile control for $key")
            val blockStart = src.lastIndexOf("ProfileSignedSlider(", keyIndex)
            val competingBoostStart = src.lastIndexOf("ProfileBoostSlider(", keyIndex)
            assertTrue(blockStart > competingBoostStart, "$key must use ProfileSignedSlider")
        }
    }

    @Test
    fun sharpnessUiUsesV3MethodOwnershipAndExplicitRanges() {
        val src = source("src/main/java/com/bncam/ui/screens/settings/lens_profiles/ProfileTuningSubScreens.kt")
        assertTrue(src.contains("title = \"Sharp choice\""))
        listOf(
            "ProfileIspKeys.DETAIL_SHARPENING_AMOUNT",
            "ProfileIspKeys.DETAIL_SHARPENING_EDGE",
            "ProfileIspKeys.DETAIL_SHARPENING_DETAIL",
            "ProfileIspKeys.DETAIL_SHARPENING_MASKING"
        ).forEach { key ->
            val keyIndex = src.indexOf("key = $key")
            assertTrue(keyIndex >= 0, "Missing V3 Normal Sharp control for $key")
            val rangeStart = src.lastIndexOf("ProfileRangeSlider(", keyIndex)
            val signedStart = src.lastIndexOf("ProfileSignedSlider(", keyIndex)
            assertTrue(rangeStart > signedStart, "$key must use ProfileRangeSlider")
        }
        assertTrue(src.contains("Polysharp Radius Small (not connected)"))
        assertTrue(src.contains("Polysharp Radius Medium (not connected)"))
        assertTrue(src.contains("Polysharp Radius Large (not connected)"))
    }

    @Test
    fun boostOnlyControlsKeepZeroToOneContract() {
        val src = source("src/main/java/com/bncam/ui/screens/settings/lens_profiles/ProfileTuningSubScreens.kt")
        val spectraKey = "ProfileIspKeys.SPECTRA_DYNAMIC_ISO"
        val spectraIndex = src.indexOf("key = $spectraKey")
        assertTrue(spectraIndex >= 0, "Missing boost control for $spectraKey")
        assertTrue(src.lastIndexOf("ProfileBoostSlider(", spectraIndex) > src.lastIndexOf("ProfileSignedSlider(", spectraIndex))

        listOf(
            "ProfileIspKeys.DETAIL_NR_LUMINANCE",
            "ProfileIspKeys.DETAIL_NR_LUMINANCE_DETAIL",
            "ProfileIspKeys.DETAIL_NR_LUMINANCE_CONTRAST",
            "ProfileIspKeys.DETAIL_NR_COLOR",
            "ProfileIspKeys.DETAIL_NR_COLOR_DETAIL",
            "ProfileIspKeys.DETAIL_NR_COLOR_SMOOTHNESS"
        ).forEach { key ->
            val keyIndex = src.indexOf(key)
            assertTrue(keyIndex >= 0, "Missing Lightroom Noise Reduction control for $key")
            val rangeStart = src.lastIndexOf("ProfileRangeSlider(", keyIndex)
            val signedStart = src.lastIndexOf("ProfileSignedSlider(", keyIndex)
            assertTrue(rangeStart > signedStart, "$key must use the explicit 0..100 Lightroom range")
        }
    }

    @Test
    fun awbIntensityIsPortableAndHasExplicitBoundedEndpoints() {
        val awbUi = source("src/main/java/com/bncam/ui/screens/settings/lens_profiles/ProfileAwbSettingsScreen.kt")
        val resolver = source("src/main/java/com/bncam/core/quality/ProfileAwbResolver.kt")
        val catalog = source("src/main/java/com/bncam/core/quality/LibpatcherProfileResolver.kt")
        val repository = source("src/main/java/com/bncam/data/settings/SettingsRepository.kt")

        assertTrue(awbUi.contains("title = \"AWB intensity\""))
        assertTrue(awbUi.contains("valueRange = 0f..1.5f"))
        assertTrue(awbUi.contains("100% is the normal resolved AWB"))
        assertTrue(resolver.contains("logBlendGains"))
        assertTrue(resolver.contains("safe.referenceIntensity.coerceIn(0f, 1.5f)"))
        assertTrue(catalog.contains("f(\"awb_reference_intensity\", 1f)"))
        assertTrue(repository.contains("\"awb_reference_intensity\""))
    }
}
