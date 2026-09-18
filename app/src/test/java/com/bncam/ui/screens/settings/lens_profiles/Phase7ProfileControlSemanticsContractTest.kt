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
    fun neuralMasterAndAdaptiveResponseAreFixedBinaryRuntimeAuthorities() {
        val src = source("src/main/java/com/bncam/ui/screens/settings/lens_profiles/ProfileTuningSubScreens.kt")
        val block = src.substringAfter("fun ProfileDenoiseSettingsScreen").substringBefore("fun ProfileMultiFrameSettingsScreen")
        assertTrue(!block.contains("title = \"Strength\""))
        assertTrue(!block.contains("key = ProfileIspKeys.NEURAL_DENOISE_STRENGTH"))
        assertTrue(!block.contains("key = ProfileIspKeys.NEURAL_ADAPTIVE_RESPONSE"))
        assertTrue(!block.contains("title = \"Adaptive Response\""))
        assertTrue(block.contains("fixed at 100%"))
        assertTrue(block.contains("full sigma/SNR adaptation"))
    }

    @Test
    fun profileHasOneNeuralDenoisePageAndNoNoiseModelIsoControls() {
        val src = source("src/main/java/com/bncam/ui/screens/settings/lens_profiles/ProfileTuningSubScreens.kt")
        val editor = source("src/main/java/com/bncam/ui/screens/settings/lens_profiles/ProfileEditScreen.kt")
        val lensNoiseModel = source("src/main/java/com/bncam/ui/screens/settings/lens_profiles/NoiseModelSettingsScreen.kt")
        val neuralBlock = src.substringAfter("fun ProfileDenoiseSettingsScreen").substringBefore("fun ProfileMultiFrameSettingsScreen")

        assertTrue(!src.contains("fun ProfileSpectraSettingsScreen"))
        assertTrue(neuralBlock.contains("SettingsTopicScaffold(\"Neural Denoise\""))
        assertTrue(neuralBlock.contains("title = \"Enabled\""))
        assertTrue(!neuralBlock.contains("title = \"Strength\""))
        assertTrue(neuralBlock.contains("title = \"Luma\""))
        assertTrue(neuralBlock.contains("title = \"Chroma\""))
        assertTrue(neuralBlock.contains("title = \"Detail Protection\""))
        assertTrue(neuralBlock.contains("title = \"Low Frequency Cleanup\""))
        assertTrue(!neuralBlock.contains("title = \"Adaptive Response\""))
        assertTrue(!neuralBlock.contains("Manual ISO"))
        assertTrue(!neuralBlock.contains("title = \"Dynamic ISO\""))
        assertTrue(lensNoiseModel.contains("DynamicIsoCard("))
        assertTrue(!lensNoiseModel.contains("Manual ISO"))
        assertTrue(editor.contains("title = \"Neural Denoise\""))
        assertTrue(!editor.contains("title = \"SPECTRA\""))
    }

    @Test
    fun awbIsLensHardwareCalibrationAndNeverPortableProfileState() {
        val lensUi = source("src/main/java/com/bncam/ui/screens/settings/lens_profiles/LensDetailScreen.kt")
        val awbUi = source("src/main/java/com/bncam/ui/screens/settings/lens_profiles/AwbCalibrationSettingsScreen.kt")
        val catalog = source("src/main/java/com/bncam/core/quality/LibpatcherProfileResolver.kt")
        val repository = source("src/main/java/com/bncam/data/settings/SettingsRepository.kt")

        assertTrue(lensUi.contains("title = \"AWB\""))
        assertTrue(awbUi.contains("LensAwbCalibrationModes.AUTO"))
        assertTrue(awbUi.contains("Custom Import"))
        assertTrue(awbUi.contains("Import AWB calibration"))
        assertTrue(!catalog.contains("awb_reference_intensity"))
        assertTrue(!repository.contains("getProfileAwb"))
    }

}
