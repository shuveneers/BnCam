package com.bncam.core.quality

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileSharpnessAuthoritySourceContractTest {
    private fun appDir(): File = sequenceOf(File("."), File("app"))
        .firstOrNull { File(it, "src/main/cpp/IspCore.cpp").isFile }
        ?: error("Cannot locate app module")

    @Test
    fun `sharpness method is explicit and mutually exclusive`() {
        val app = appDir()
        val settings = File(app, "src/main/java/com/bncam/data/settings/ProfileLensTuningSettings.kt").readText()
        val render = File(app, "src/main/java/com/bncam/core/quality/RenderQualityConfig.kt").readText()
        val ui = File(app, "src/main/java/com/bncam/ui/screens/settings/lens_profiles/ProfileTuningSubScreens.kt").readText()

        assertTrue(settings.contains("object ProfileSharpnessMethods"))
        assertTrue(settings.contains("const val NORMAL = \"Normal Sharpness\""))
        assertTrue(settings.contains("const val POLYSHARP = \"Polysharp\""))
        assertTrue(ui.contains("title = \"Sharp choice\""))
        assertTrue(ui.contains("if (method == ProfileSharpnessMethods.NORMAL)"))
        assertTrue(ui.contains("\"Polysharp (not connected)\""))

        // Selecting Polysharp must neutralize the existing Normal backend instead of stacking it.
        assertTrue(render.contains("sharpeningMethod == ProfileSharpnessMethods.POLYSHARP"))
        assertTrue(render.contains("normalDetailTuning.copy(amount = 0f, detail = 0f, masking = 1f)"))
    }

    @Test
    fun `normal sharp keeps production abi while edge and polysharp remain planned`() {
        val app = appDir()
        val settings = File(app, "src/main/java/com/bncam/data/settings/ProfileLensTuningSettings.kt").readText()
        val resolver = File(app, "src/main/java/com/bncam/core/quality/LibpatcherProfileResolver.kt").readText()
        val ui = File(app, "src/main/java/com/bncam/ui/screens/settings/lens_profiles/ProfileTuningSubScreens.kt").readText()
        val imageUtils = File(app, "src/main/java/com/bncam/core/engine/ImageUtils.kt").readText()
        val nativeConfig = File(app, "src/main/cpp/NativeRenderQualityConfig.h").readText()

        listOf(
            "DETAIL_SHARPENING_AMOUNT",
            "DETAIL_SHARPENING_RADIUS",
            "DETAIL_SHARPENING_DETAIL",
            "DETAIL_SHARPENING_MASKING"
        ).forEach { key -> assertTrue(settings.contains("const val $key")) }

        listOf("Global Sharpness", "Detail", "Sharp Mask").forEach { assertTrue(ui.contains(it)) }
        assertTrue(ui.contains("Edge Sharpness (not connected)"))
        listOf(
            "Sharp Gain (not connected)",
            "Sharp Macro Gain (not connected)",
            "Sharp Micro Gain (not connected)",
            "Sharpen Max Detail (not connected)",
            "Polysharp Radius Small (not connected)",
            "Polysharp Radius Medium (not connected)",
            "Polysharp Radius Large (not connected)"
        ).forEach { assertTrue(ui.contains(it)) }

        // Planned controls are portable, but not falsely classified as runtime pixel authorities.
        assertTrue(resolver.contains("plannedProfileSettingSpecs"))
        assertTrue(resolver.contains("ProfileIspKeys.POLYSHARP_GAIN"))
        val runtimeBlock = resolver.substringAfter("fun runtimeProfileSettingSpecs").substringBefore("private fun plannedProfileSettingSpecs")
        assertFalse(runtimeBlock.contains("ProfileIspKeys.POLYSHARP_GAIN"))
        assertFalse(runtimeBlock.contains("ProfileIspKeys.DETAIL_SHARPENING_EDGE"))

        listOf("profileDetailAmount", "profileDetailRadius", "profileDetailDetail", "profileDetailMasking").forEach { name ->
            assertTrue(imageUtils.contains(name))
            assertTrue(nativeConfig.contains(name))
        }
    }
}
