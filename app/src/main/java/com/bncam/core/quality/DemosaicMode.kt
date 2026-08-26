package com.bncam.core.quality

import java.util.Locale

enum class DemosaicMode(
    val bridgeValue: Int,
    val displayName: String,
    val available: Boolean
) {
    // Internal enum symbols retain legacy names for persisted-profile/source compatibility.
    // Product bridge identities are 1=Malvar, 2=AMaZE,
    // 3=Neural JDD. Auto remains the transition selector until final Auto Hybrid hysteresis lands.
    AUTO(0, "Auto", true),
    NORMAL(1, "Malvar", true),
    QUALITY(2, "AMaZE", true),
    BILINEAR(3, "Neural JDD", true);

    companion object {
        const val PROFILE_KEY = "demosaic_mode"

        // Product default remains the deterministic Malvar path. AMaZE and Neural JDD are
        // explicit alternatives; Auto stays the fourth UI choice until Auto Hybrid lands.
        val DEFAULT: DemosaicMode = NORMAL
        val USER_ORDER: List<DemosaicMode> = listOf(NORMAL, QUALITY, BILINEAR, AUTO)

        /**
         * Parses both the new product names and all legacy persisted values.
         * Legacy slot semantics intentionally migrate with their bridge value:
         * NORMAL/Malvar -> Malvar, QUALITY/Menon -> AMaZE, BILINEAR/RCD -> Neural JDD.
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

                "BILINEAR", "RCD", "RCD_INSPIRED", "RCD INSPIRED",
                "NEURAL_JDD", "NEURAL JDD", "NEURALJDD" -> BILINEAR
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
                    resolveReason = "legacy_slot_2_forces_amaze",
                    fallbackOccurred = false,
                    fallbackReason = "none"
                )
                BILINEAR -> DemosaicSelection(
                    requestedMode = BILINEAR,
                    resolvedAlgorithm = ResolvedDemosaicAlgorithm.RCD_INSPIRED,
                    resolveReason = "legacy_slot_3_executes_neural_jdd",
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
                    resolveReason = "normal_mode_forces_malvar_2004",
                    fallbackOccurred = false,
                    fallbackReason = "none"
                )
                null -> DemosaicSelection(
                    requestedMode = DEFAULT,
                    resolvedAlgorithm = ResolvedDemosaicAlgorithm.MALVAR_INSPIRED,
                    resolveReason = "default_malvar_2004_noise_robust",
                    fallbackOccurred = invalidRequested,
                    fallbackReason = if (invalidRequested) "invalid_profile_value_defaulted_malvar_2004" else "none"
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
    val resolvedDebugName: String
        get() = when (resolvedAlgorithm) {
            ResolvedDemosaicAlgorithm.MALVAR_INSPIRED -> "MALVAR_2004"
            ResolvedDemosaicAlgorithm.RCD_INSPIRED -> "NEURAL_JDD"
            ResolvedDemosaicAlgorithm.AMAZE_INSPIRED -> "AMAZE"
            ResolvedDemosaicAlgorithm.AUTO_SCENE_ADAPTIVE_NATIVE -> "AUTO"
        }

    private val requestedDebugName: String
        get() = when (requestedMode) {
            DemosaicMode.AUTO -> "AUTO"
            DemosaicMode.BILINEAR -> "NEURAL_JDD"
            DemosaicMode.NORMAL -> "MALVAR"
            DemosaicMode.QUALITY -> "AMAZE"
        }

    val debugPairs: List<Pair<String, String>>
        get() = listOf(
            "requestedDemosaicMode" to requestedDebugName,
            "resolvedDemosaicAlgorithm" to resolvedDebugName,
            "demosaicResolveReason" to resolveReason,
            "malvar2004Available" to "true",
            "neuralJddAvailable" to "true",
            "rcdInspiredAvailable" to "false",
            "amazeAvailable" to "true",
            "amazeInspiredAvailable" to "false",
            "legacyBilinearProductAvailable" to "false",
            "legacyMenonProductAvailable" to "false",
            "fallbackOccurred" to fallbackOccurred.toString(),
            "fallbackReason" to fallbackReason
        )
}
