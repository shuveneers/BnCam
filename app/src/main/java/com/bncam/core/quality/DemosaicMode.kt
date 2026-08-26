package com.bncam.core.quality

import java.util.Locale

enum class DemosaicMode(
    val bridgeValue: Int,
    val displayName: String,
    val available: Boolean
) {
    // Internal enum symbols retain their legacy names for binary/source compatibility while
    // bridge values 1/2/3 now represent the BnCam Inspired product algorithms below.
    AUTO(0, "Auto", true),
    NORMAL(1, "Malvar Inspired", true),
    QUALITY(2, "AMAZE Inspired", true),
    BILINEAR(3, "RCD Inspired", true);

    companion object {
        const val PROFILE_KEY = "demosaic_mode"

        // Product default is deliberately noise-robust. RCD and AMAZE remain available for
        // profiles that prioritise edge/detail reconstruction; Auto stays the fourth UI choice.
        val DEFAULT: DemosaicMode = NORMAL
        val USER_ORDER: List<DemosaicMode> = listOf(NORMAL, BILINEAR, QUALITY, AUTO)

        /**
         * Parses both the new product names and all legacy persisted values.
         * Legacy slot semantics intentionally migrate with their bridge value:
         * NORMAL/Malvar -> Malvar Inspired, QUALITY/Menon -> AMAZE Inspired,
         * BILINEAR -> RCD Inspired.
         */
        fun fromPersisted(value: String?): DemosaicMode? {
            val trimmed = value?.trim().orEmpty()
            if (trimmed.isEmpty()) return null
            trimmed.toIntOrNull()?.let { persistedId ->
                return entries.firstOrNull { it.bridgeValue == persistedId }
            }
            return when (trimmed.uppercase(Locale.US)) {
                "AUTO" -> AUTO
                "NORMAL", "MALVAR", "MALVAR_2004", "MALVAR 2004",
                "MALVAR_INSPIRED", "MALVAR INSPIRED" -> NORMAL

                "QUALITY", "MENON", "MENON_2007", "MENON 2007",
                "MENON_2007_DDFAPD", "AMAZE", "AMAZE_INSPIRED", "AMAZE INSPIRED" -> QUALITY

                "BILINEAR", "RCD", "RCD_INSPIRED", "RCD INSPIRED" -> BILINEAR
                else -> null
            }
        }

        fun resolveForPhase4(value: String?): DemosaicSelection {
            val stored = fromPersisted(value)
            val invalidRequested = value != null && value.isNotBlank() && stored == null
            return when (stored) {
                QUALITY -> DemosaicSelection(
                    requestedMode = QUALITY,
                    resolvedAlgorithm = ResolvedDemosaicAlgorithm.AMAZE_INSPIRED,
                    resolveReason = "legacy_slot_2_forces_amaze_inspired",
                    fallbackOccurred = false,
                    fallbackReason = "none"
                )
                BILINEAR -> DemosaicSelection(
                    requestedMode = BILINEAR,
                    resolvedAlgorithm = ResolvedDemosaicAlgorithm.RCD_INSPIRED,
                    resolveReason = "legacy_slot_3_forces_rcd_inspired",
                    fallbackOccurred = false,
                    fallbackReason = "none"
                )
                AUTO -> DemosaicSelection(
                    requestedMode = AUTO,
                    resolvedAlgorithm = ResolvedDemosaicAlgorithm.AUTO_SCENE_ADAPTIVE_NATIVE,
                    resolveReason = "auto_requires_native_scene_analysis",
                    fallbackOccurred = false,
                    fallbackReason = "none"
                )
                NORMAL -> DemosaicSelection(
                    requestedMode = NORMAL,
                    resolvedAlgorithm = ResolvedDemosaicAlgorithm.MALVAR_INSPIRED,
                    resolveReason = "normal_mode_forces_malvar_inspired",
                    fallbackOccurred = false,
                    fallbackReason = "none"
                )
                null -> DemosaicSelection(
                    requestedMode = DEFAULT,
                    resolvedAlgorithm = ResolvedDemosaicAlgorithm.MALVAR_INSPIRED,
                    resolveReason = "default_malvar_inspired_noise_robust",
                    fallbackOccurred = invalidRequested,
                    fallbackReason = if (invalidRequested) "invalid_profile_value_defaulted_malvar_inspired" else "none"
                )
            }
        }
    }
}

enum class ResolvedDemosaicAlgorithm {
    MALVAR_INSPIRED,
    RCD_INSPIRED,
    AMAZE_INSPIRED,
    AUTO_SCENE_ADAPTIVE_NATIVE
}

data class DemosaicSelection(
    val requestedMode: DemosaicMode,
    val resolvedAlgorithm: ResolvedDemosaicAlgorithm,
    val resolveReason: String,
    val fallbackOccurred: Boolean,
    val fallbackReason: String
) {
    private val requestedDebugName: String
        get() = when (requestedMode) {
            DemosaicMode.AUTO -> "AUTO"
            DemosaicMode.BILINEAR -> "RCD_INSPIRED"
            DemosaicMode.NORMAL -> "MALVAR_INSPIRED"
            DemosaicMode.QUALITY -> "AMAZE_INSPIRED"
        }

    val debugPairs: List<Pair<String, String>>
        get() = listOf(
            "requestedDemosaicMode" to requestedDebugName,
            "resolvedDemosaicAlgorithm" to resolvedAlgorithm.name,
            "demosaicResolveReason" to resolveReason,
            "malvarInspiredAvailable" to "true",
            "rcdInspiredAvailable" to "true",
            "amazeInspiredAvailable" to "true",
            "legacyBilinearProductAvailable" to "false",
            "legacyMenonProductAvailable" to "false",
            "fallbackOccurred" to fallbackOccurred.toString(),
            "fallbackReason" to fallbackReason
        )
}
