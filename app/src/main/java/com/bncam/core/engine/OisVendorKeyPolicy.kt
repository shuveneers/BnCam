package com.bncam.core.engine

/**
 * Strict optical-only vendor key classifier.
 *
 * This policy deliberately excludes every digital/video/preview stabilization spelling so the
 * user-facing Optical Stabilization switch can never silently become EIS and crop the frame.
 */
object OisVendorKeyPolicy {
    private val knownOpticalKeys = setOf(
        "com.samsung.android.control.ois_mode",
        "org.codeaurora.qcamera3.optstabilization.enable",
        "xiaomi.device.opticalStabilization"
    )

    fun isOpticalOnlyKey(name: String): Boolean {
        if (knownOpticalKeys.any { it.equals(name, ignoreCase = true) }) return true
        val lower = name.lowercase()
        if (listOf("eis", "video", "preview", "digital", "electronic").any(lower::contains)) {
            return false
        }
        val explicitlyOis = lower.contains("ois")
        val explicitlyOpticalStabilization =
            lower.contains("optical") && (lower.contains("stabil") || lower.contains("shake"))
        val looksWritableControl =
            lower.contains("mode") || lower.contains("enable") || lower.contains("control") ||
                lower.contains("stabil") || lower.contains("ois")
        return (explicitlyOis || explicitlyOpticalStabilization) && looksWritableControl
    }
}
