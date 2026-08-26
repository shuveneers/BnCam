package com.bncam.core.engine

/**
 * Owns the visual masking rule for viewfinder producer/display-source rebuilds.
 *
 * A black transition is intentional only when the user-visible producer/source is actually
 * replaced. Vendor-session probes, watchdog recoveries and physical lens handovers retain the
 * last-known-good texture instead of pretending that a producer format change occurred.
 */
object ViewfinderRebuildVisualPolicy {
    private val producerReplacementReasons = setOf(
        "FRAME_SOURCE_CHANGED",
        "BUFFER_FORMAT_CHANGED",
        "BACKEND_ROUTE_CHANGED"
    )

    fun requiresBlackTransition(
        decisionReasons: Collection<String>,
        requestReason: String
    ): Boolean {
        if (requestReason.startsWith("VIEWFINDER_STREAM_MODE_CHANGED_")) return true
        if (requestReason.startsWith("PROFILE_BUFFER_CHANGED_")) return true
        if (requestReason == "SAME_LOGICAL_PHYSICAL_LENS_HANDOVER") return false
        if (requestReason.startsWith("VENDOR_")) return false
        if (requestReason.startsWith("WARM_BUFFER_WATCHDOG_")) return false
        return decisionReasons.any { it in producerReplacementReasons }
    }
}
