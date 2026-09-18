package com.bncam.data.profile

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BncProfileCatalogSourceContractTest {
    private fun appDir(): File = sequenceOf(File("."), File("app"))
        .firstOrNull { File(it, "src/main/java/com/bncam/core/quality/LibpatcherProfileResolver.kt").isFile }
        ?: error("Cannot locate app module")

    @Test
    fun `portable catalog contains only Profile V3 user owned controls`() {
        val resolver = File(appDir(), "src/main/java/com/bncam/core/quality/LibpatcherProfileResolver.kt").readText()

        listOf(
            "SHOT_BIAS_EXPOSURE",
            "SHOT_BIAS_MAX_FRAME_EXPOSURE",
            "CAPTURE_EV_BIAS",
            "ALIGNMENT_METHOD",
            "FUSION_METHOD",
            "FUSION_FRAMES_RAW10",
            "EXPOSURE_STRATEGY",
            "SPECTRA_ENABLED",
            "SPECTRA_DYNAMIC_ISO",
            "DETAIL_NR_LUMINANCE",
            "DETAIL_NR_COLOR",
            "TONE_EXPOSURE",
            "TONE_HIGHLIGHTS",
            "TONE_SHADOWS",
            "TONE_WHITES",
            "TONE_BLACKS",
            "TONE_CONTRAST",
            "PRESENCE_VIBRANCE",
            "PRESENCE_SATURATION",
            "PRESENCE_COLOR_FRINGE_SUPPRESSION",
            "TONE_GAMMA_CONTRAST",
            "TONE_DEHAZE",
            "TONE_CLARITY",
            "DETAIL_SHARPENING_METHOD",
            "DETAIL_SHARPENING_AMOUNT",
            "DETAIL_SHARPENING_RADIUS",
            "DETAIL_SHARPENING_DETAIL",
            "DETAIL_SHARPENING_MASKING",
            "DETAIL_SHARPENING_EDGE",
            "POLYSHARP_GAIN",
            "POLYSHARP_MACRO_GAIN",
            "POLYSHARP_MICRO_GAIN",
            "POLYSHARP_MAX_DETAIL",
            "POLYSHARP_RADIUS_SMALL",
            "POLYSHARP_RADIUS_MEDIUM",
            "POLYSHARP_RADIUS_LARGE",
            "SELECTION_ACCEPT_ALL",
            "ProfileCurveDefaults.TYPE_GAMMA",
            "ProfileCurveDefaults.TYPE_SECT",
            "ProfileCurveDefaults.pointKey(type, index)"
        ).forEach { key -> assertTrue("Missing Profile V3 portable key $key", resolver.contains(key)) }

        listOf(
            "BASE_CANDIDATES",
            "BASE_POSITION",
            "SELECTION_REQUESTED_FRAMES",
            "MERGE_STRICTNESS",
            "EXPOSURE_PRIORITY_MODE",
            "SHUTTER_PRIORITY_MULTIPLIER",
            "ISO_PRIORITY_MULTIPLIER",
            "DNG_MASTER_FRAMES_RAW10",
            "DNG_MASTER_FRAMES_RAW_SENSOR",
            "ProfileIspKeys.SPECTRA_STRENGTH",
            "ProfileIspKeys.LOCAL_TONE_BIAS",
            "awb_mode",
            "awb_reference_intensity"
        ).forEach { key -> assertFalse("Legacy/non-profile key leaked into Profile V3 portable catalog: $key", resolver.contains(key)) }

        assertTrue(resolver.contains("Physical lens calibration, global"))
    }
}
