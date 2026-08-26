package com.bncam.core.quality

/**
 * Single source of truth for the current technical ISP baseline.
 *
 * Disabled profiles always resolve to this baseline. Normal profiles inherit this baseline until a
 * user explicitly writes an override key for a setting or curve.
 */
object DefaultIspProfile {
    const val VERSION = "DefaultIspProfile.v1-current-technical-isp"
    const val NAME = "Current technical ISP baseline"

    fun isDisabledProfileId(profileId: String): Boolean {
        val normalized = profileId.trim().lowercase()
        return normalized.isBlank() ||
            normalized == "default" ||
            normalized == "disabled" ||
            normalized.endsWith("_disabled")
    }

    fun curvePreset(type: String): String = ProfileCurveDefaults.PRESET_DEFAULT

    fun curveNodes(type: String): List<Float> = ProfileCurveDefaults.pointsForPreset(
        type = type,
        preset = ProfileCurveDefaults.PRESET_DEFAULT
    )
}
