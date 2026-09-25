package com.bncam.core.isp.raw

import java.util.Locale

enum class RawBlackAuthorityMode {
    SYSTEM,
    DYNAMIC,
    MANUAL,
    /** Source compatibility only; production/UI use SYSTEM. */
    AUTO
}

data class RawBlackAuthorityDecision(
    val canonicalLevels: List<Float>,
    val source: String,
    val metadataAuthoritative: Boolean,
    val fallbackReason: String,
    val mode: RawBlackAuthorityMode = RawBlackAuthorityMode.SYSTEM,
    val manualOverrideActive: Boolean = false,
    val systemMetadataAvailable: Boolean = false,
    val dynamicMetadataAvailable: Boolean = false,
    val dynamicStrength: Float = 1.0f
)

/**
 * Single developed-RAW black authority.
 *
 * Camera2 black metadata arrives in sensor mosaic order [00, 10, 01, 11]. Developed RAW consumes
 * canonical [R, Gr, Gb, B].
 *
 * SYSTEM:
 *   use the camera's exact-frame SENSOR_DYNAMIC_BLACK_LEVEL when available. This is the
 *   physically preferred zero-light reference for developed RAW because sensor black can move
 *   with capture state/ISO. Fall back to SENSOR_BLACK_LEVEL_PATTERN, then the controlled fallback.
 *
 * DYNAMIC:
 *   linearly blend each CFA site from the SYSTEM baseline toward the same-frame
 *   SENSOR_DYNAMIC_BLACK_LEVEL:
 *
 *     effective[c] = system[c] + strength * (dynamic[c] - system[c])
 *
 *   strength=0.00 is exactly SYSTEM, strength=1.00 is full same-frame dynamic metadata.
 *   This is not the retired legacy `base * percentage` behavior.
 *
 * MANUAL:
 *   exact user-provided floating-point CFA values are authoritative for developed RAW JPEG.
 *   Physical RAW/DNG metadata remains untouched elsewhere in the RAW-domain contract.
 */
object RawBlackAuthorityPolicy {
    fun parseMode(modeName: String?): RawBlackAuthorityMode {
        val normalized = modeName?.trim()?.lowercase().orEmpty()
        return when {
            normalized == "manual" -> RawBlackAuthorityMode.MANUAL
            normalized == "dynamic" -> RawBlackAuthorityMode.DYNAMIC
            else -> RawBlackAuthorityMode.SYSTEM // "Auto" is compatibility-mapped to System.
        }
    }

    fun sanitizeDynamicStrength(value: Float): Float =
        value.takeIf { it.isFinite() }?.coerceIn(0.0f, 1.0f) ?: 1.0f

    fun resolveSystemDynamic(
        systemMosaicLevels: List<Float>?,
        systemSource: String,
        dynamicMosaicLevels: List<Float>?,
        dynamicSource: String,
        cfaPattern: Int,
        fallbackCanonicalLevels: List<Float>,
        fallbackSource: String,
        requestedMode: RawBlackAuthorityMode,
        dynamicStrength: Float = 1.0f
    ): RawBlackAuthorityDecision {
        val mode = if (requestedMode == RawBlackAuthorityMode.AUTO) RawBlackAuthorityMode.SYSTEM else requestedMode
        val systemMosaic = validLevels(systemMosaicLevels)
        val dynamicMosaic = validLevels(dynamicMosaicLevels)
        val fallbackCanonical = validLevels(fallbackCanonicalLevels)
        val systemCanonical = systemMosaic?.let { RawLevelOrder.mosaicToCanonical(it, cfaPattern) }
        val dynamicCanonical = dynamicMosaic?.let { RawLevelOrder.mosaicToCanonical(it, cfaPattern) }
        val strength = sanitizeDynamicStrength(dynamicStrength)

        if (mode == RawBlackAuthorityMode.MANUAL) {
            if (fallbackCanonical != null) {
                return RawBlackAuthorityDecision(
                    canonicalLevels = fallbackCanonical,
                    source = "MANUAL_OVERRIDE: $fallbackSource",
                    metadataAuthoritative = false,
                    fallbackReason = "manual_override_selected",
                    mode = mode,
                    manualOverrideActive = true,
                    systemMetadataAvailable = systemCanonical != null,
                    dynamicMetadataAvailable = dynamicCanonical != null,
                    dynamicStrength = strength
                )
            }

            val metadataFallback = systemCanonical ?: dynamicCanonical
            if (metadataFallback != null) {
                return RawBlackAuthorityDecision(
                    canonicalLevels = metadataFallback,
                    source = if (systemCanonical != null) {
                        "MANUAL_INVALID_SYSTEM_METADATA_FALLBACK: $systemSource"
                    } else {
                        "MANUAL_INVALID_DYNAMIC_METADATA_FALLBACK: $dynamicSource"
                    },
                    metadataAuthoritative = true,
                    fallbackReason = "manual_black_override_invalid_camera2_metadata_used",
                    mode = mode,
                    manualOverrideActive = false,
                    systemMetadataAvailable = systemCanonical != null,
                    dynamicMetadataAvailable = dynamicCanonical != null,
                    dynamicStrength = strength
                )
            }

            return RawBlackAuthorityDecision(
                canonicalLevels = List(4) { 0f },
                source = "MANUAL_INVALID_CONTROLLED_ZERO_FALLBACK: $fallbackSource",
                metadataAuthoritative = false,
                fallbackReason = "manual_black_override_invalid_and_camera2_metadata_unavailable",
                mode = mode,
                manualOverrideActive = false,
                systemMetadataAvailable = false,
                dynamicMetadataAvailable = false,
                dynamicStrength = strength
            )
        }

        if (mode == RawBlackAuthorityMode.SYSTEM) {
            // Exact-frame dynamic black is preferred for developed RAW. The static pattern is a
            // characteristics-level baseline and can be stale for the actual sensor/ISO state.
            if (dynamicCanonical != null) {
                return RawBlackAuthorityDecision(
                    canonicalLevels = dynamicCanonical,
                    source = "SYSTEM_EXACT_FRAME_DYNAMIC: $dynamicSource",
                    metadataAuthoritative = true,
                    fallbackReason = "none",
                    mode = mode,
                    systemMetadataAvailable = systemCanonical != null,
                    dynamicMetadataAvailable = true,
                    dynamicStrength = 1.0f
                )
            }
            if (systemCanonical != null) {
                return RawBlackAuthorityDecision(
                    canonicalLevels = systemCanonical,
                    source = "SYSTEM_STATIC_METADATA_FALLBACK: $systemSource",
                    metadataAuthoritative = true,
                    fallbackReason = "same_frame_dynamic_black_unavailable_static_pattern_used",
                    mode = mode,
                    systemMetadataAvailable = true,
                    dynamicMetadataAvailable = false,
                    dynamicStrength = strength
                )
            }
            return RawBlackAuthorityDecision(
                canonicalLevels = fallbackCanonical ?: List(4) { 0f },
                source = "SYSTEM_CONTROLLED_FALLBACK: $fallbackSource",
                metadataAuthoritative = false,
                fallbackReason = systemFallbackReason(systemMosaicLevels),
                mode = mode,
                systemMetadataAvailable = false,
                dynamicMetadataAvailable = false,
                dynamicStrength = strength
            )
        }

        // DYNAMIC. The base is the exact SYSTEM baseline when available. If the static pattern is
        // unavailable, use the controlled canonical fallback as the interpolation base. As a final
        // safety net, a valid dynamic vector may seed both endpoints so no invalid black is created.
        val base = systemCanonical ?: fallbackCanonical ?: dynamicCanonical ?: List(4) { 0f }
        if (dynamicCanonical == null) {
            return RawBlackAuthorityDecision(
                canonicalLevels = base,
                source = if (systemCanonical != null) {
                    "DYNAMIC_METADATA_UNAVAILABLE_SYSTEM_FALLBACK: $systemSource"
                } else {
                    "DYNAMIC_METADATA_UNAVAILABLE_CONTROLLED_FALLBACK: $fallbackSource"
                },
                metadataAuthoritative = systemCanonical != null,
                fallbackReason = dynamicFallbackReason(dynamicMosaicLevels),
                mode = mode,
                systemMetadataAvailable = systemCanonical != null,
                dynamicMetadataAvailable = false,
                dynamicStrength = strength
            )
        }

        val blended = List(4) { index ->
            val b = base[index]
            val d = dynamicCanonical[index]
            (b + strength * (d - b)).takeIf { it.isFinite() } ?: b
        }
        val strengthText = String.format(Locale.US, "%.2f", strength)
        val baseSource = if (systemCanonical != null) systemSource else fallbackSource
        return RawBlackAuthorityDecision(
            canonicalLevels = blended,
            source = "DYNAMIC_BLEND strength=$strengthText base=$baseSource target=$dynamicSource",
            metadataAuthoritative = true,
            fallbackReason = if (systemCanonical != null) "none" else "system_black_pattern_unavailable_controlled_base_used",
            mode = mode,
            systemMetadataAvailable = systemCanonical != null,
            dynamicMetadataAvailable = true,
            dynamicStrength = strength
        )
    }

    /**
     * Compatibility overload retained for older callers. A missing explicit mode is always SYSTEM;
     * legacy source strings are never allowed to reactivate Dynamic/Manual authority. New
     * production binding calls [resolveSystemDynamic] with static and dynamic vectors separately.
     */
    fun resolve(
        metadataMosaicLevels: List<Float>?,
        metadataSource: String,
        cfaPattern: Int,
        fallbackCanonicalLevels: List<Float>,
        fallbackSource: String,
        requestedMode: RawBlackAuthorityMode? = null
    ): RawBlackAuthorityDecision {
        val mode = requestedMode ?: RawBlackAuthorityMode.SYSTEM
        val dynamic = isDynamicMetadataSource(metadataSource)
        return resolveSystemDynamic(
            systemMosaicLevels = if (dynamic) null else metadataMosaicLevels,
            systemSource = if (dynamic) "unavailable" else metadataSource,
            dynamicMosaicLevels = if (dynamic) metadataMosaicLevels else null,
            dynamicSource = if (dynamic) metadataSource else "unavailable",
            cfaPattern = cfaPattern,
            fallbackCanonicalLevels = fallbackCanonicalLevels,
            fallbackSource = fallbackSource,
            requestedMode = mode,
            dynamicStrength = 1.0f
        )
    }

    private fun validLevels(levels: List<Float>?): List<Float>? = levels
        ?.takeIf { it.size >= 4 }
        ?.take(4)
        ?.takeIf { candidate -> candidate.all { it.isFinite() && it >= 0f } }

    private fun isDynamicMetadataSource(source: String): Boolean =
        source.contains("SENSOR_DYNAMIC_BLACK_LEVEL", ignoreCase = true) ||
            source.contains("dynamic black", ignoreCase = true)

    private fun systemFallbackReason(levels: List<Float>?): String = when {
        levels == null -> "system_black_pattern_unavailable_controlled_fallback"
        levels.size < 4 -> "system_black_pattern_incomplete_controlled_fallback"
        else -> "system_black_pattern_invalid_controlled_fallback"
    }

    private fun dynamicFallbackReason(levels: List<Float>?): String = when {
        levels == null -> "dynamic_black_metadata_unavailable_system_fallback"
        levels.size < 4 -> "dynamic_black_metadata_incomplete_system_fallback"
        else -> "dynamic_black_metadata_invalid_system_fallback"
    }
}
