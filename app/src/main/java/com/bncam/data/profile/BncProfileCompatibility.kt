package com.bncam.data.profile

data class BncTargetCapabilities(
    val supportedFrameSources: Set<String>
) {
    val normalizedFrameSources: Set<String> = supportedFrameSources.map(BncProfileCodec::normalizeFrameSource).toSet()

    companion object {
        val UNKNOWN = BncTargetCapabilities(emptySet())
    }
}

data class BncFrameSourceResolution(
    val requested: String,
    val effective: String,
    val fallbackOccurred: Boolean,
    val reason: String
)

object BncProfileCompatibility {
    fun resolveFrameSource(requested: String, target: BncTargetCapabilities): BncFrameSourceResolution {
        val normalized = BncProfileCodec.normalizeFrameSource(requested)
        val supported = target.normalizedFrameSources
        if (supported.isEmpty()) {
            return BncFrameSourceResolution(
                requested = normalized,
                effective = normalized,
                fallbackOccurred = false,
                reason = "target_capabilities_unknown_preserve_requested"
            )
        }
        if (normalized in supported) {
            return BncFrameSourceResolution(normalized, normalized, false, "requested_source_supported")
        }
        val preferenceOrder = when (normalized) {
            "RAW_SENSOR" -> listOf("RAW10", "YUV")
            "RAW10" -> listOf("RAW_SENSOR", "YUV")
            else -> listOf("RAW10", "RAW_SENSOR")
        }
        val fallback = preferenceOrder.firstOrNull { it in supported } ?: normalized
        return BncFrameSourceResolution(
            requested = normalized,
            effective = fallback,
            fallbackOccurred = fallback != normalized,
            reason = if (fallback != normalized) {
                "requested_source_unavailable_fallback_to_$fallback"
            } else {
                "no_known_supported_source_preserve_requested"
            }
        )
    }
}
