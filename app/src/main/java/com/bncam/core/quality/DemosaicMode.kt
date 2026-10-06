package com.bncam.core.quality

import java.util.Locale

enum class DemosaicMode(
    val bridgeValue: Int,
    val displayName: String,
    val available: Boolean
) {
    // Stable bridge IDs preserve existing profiles; legacy text values are parsed below.
    // BnC Neural remains selectable, with its current native Malvar fallback reported explicitly.
    AUTO_HYBRID(0, "Auto Hybrid", true),
    MALVAR(1, "Malvar", true),
    AMAZE(2, "AMaZE", true),
    BNC_NEURAL(3, "BnC Neural", true);

    companion object {
        const val PROFILE_KEY = "demosaic_mode"

        // Missing/new profiles use automatic routing. AMaZE remains the classical quality baseline.
        val DEFAULT: DemosaicMode = AUTO_HYBRID
        val USER_ORDER: List<DemosaicMode> = listOf(MALVAR, AMAZE, BNC_NEURAL, AUTO_HYBRID)

        /**
         * Parses both the new product names and all legacy persisted values.
         * Legacy slot semantics intentionally migrate with their bridge value:
         * MALVAR/Malvar -> Malvar, AMAZE/Menon -> AMaZE, BNC_NEURAL/RCD -> BnC Neural.
         */
        fun fromPersisted(value: String?): DemosaicMode? {
            val trimmed = value?.trim().orEmpty()
            if (trimmed.isEmpty()) return null
            trimmed.toIntOrNull()?.let { persistedId ->
                return entries.firstOrNull { it.bridgeValue == persistedId }
            }
            return when (trimmed.uppercase(Locale.US)) {
                "AUTO", "AUTO_HYBRID", "AUTO HYBRID", "AUTOHYBRID" -> AUTO_HYBRID
                "NORMAL", "MALVAR", "MALVAR_2004", "MALVAR 2004",
                "MALVAR_INSPIRED", "MALVAR INSPIRED" -> MALVAR

                "QUALITY", "MENON", "MENON_2007", "MENON 2007",
                "MENON_2007_DDFAPD", "AMAZE", "AMAZE_INSPIRED", "AMAZE INSPIRED" -> AMAZE

                "BILINEAR", "RCD", "RCD_INSPIRED", "RCD INSPIRED",
                "BNC_NEURAL", "BNC NEURAL", "BNCNEURAL",
                "NEURAL_JDD", "NEURAL JDD", "NEURALJDD", "NEURAL BN" -> BNC_NEURAL
                else -> null
            }
        }

        fun resolveForPhase4(value: String?): DemosaicSelection {
            val stored = fromPersisted(value)
            val invalidRequested = value != null && value.isNotBlank() && stored == null
            return when (stored) {
                AMAZE -> DemosaicSelection(
                    requestedMode = AMAZE,
                    resolvedAlgorithm = ResolvedDemosaicAlgorithm.AMAZE,
                    resolveReason = "amaze_mode",
                    fallbackOccurred = false,
                    fallbackReason = "none"
                )
                BNC_NEURAL -> DemosaicSelection(
                    requestedMode = BNC_NEURAL,
                    resolvedAlgorithm = ResolvedDemosaicAlgorithm.MALVAR_2004,
                    resolveReason = "bnc_neural_requested_malvar_fallback",
                    fallbackOccurred = true,
                    fallbackReason = "BNC_NEURAL_BACKEND_UNAVAILABLE"
                )
                AUTO_HYBRID -> DemosaicSelection(
                    requestedMode = AUTO_HYBRID,
                    resolvedAlgorithm = ResolvedDemosaicAlgorithm.AUTO_HYBRID,
                    resolveReason = "auto_hybrid_requires_native_scene_analysis",
                    fallbackOccurred = false,
                    fallbackReason = "none"
                )
                MALVAR -> DemosaicSelection(
                    requestedMode = MALVAR,
                    resolvedAlgorithm = ResolvedDemosaicAlgorithm.MALVAR_2004,
                    resolveReason = "normal_mode_forces_malvar_2004",
                    fallbackOccurred = false,
                    fallbackReason = "none"
                )
                null -> DemosaicSelection(
                    requestedMode = DEFAULT,
                    resolvedAlgorithm = ResolvedDemosaicAlgorithm.AUTO_HYBRID,
                    resolveReason = "default_auto_hybrid_requires_native_scene_analysis",
                    fallbackOccurred = invalidRequested,
                    fallbackReason = if (invalidRequested) "invalid_profile_value_defaulted_auto_hybrid" else "none"
                )
            }
        }
    }
}

enum class ResolvedDemosaicAlgorithm {
    MALVAR_2004,
    BNC_NEURAL,
    AMAZE,
    AUTO_HYBRID
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
            ResolvedDemosaicAlgorithm.MALVAR_2004 -> "MALVAR_2004"
            ResolvedDemosaicAlgorithm.BNC_NEURAL -> "BNC_NEURAL"
            ResolvedDemosaicAlgorithm.AMAZE -> "AMAZE"
            ResolvedDemosaicAlgorithm.AUTO_HYBRID -> "AUTO_HYBRID"
        }

    private val requestedDebugName: String
        get() = when (requestedMode) {
            DemosaicMode.AUTO_HYBRID -> "AUTO_HYBRID"
            DemosaicMode.BNC_NEURAL -> "BNC_NEURAL"
            DemosaicMode.MALVAR -> "MALVAR"
            DemosaicMode.AMAZE -> "AMAZE"
        }

    val debugPairs: List<Pair<String, String>>
        get() = listOf(
            "requestedDemosaicMode" to requestedDebugName,
            "resolvedDemosaicAlgorithm" to resolvedDebugName,
            "demosaicResolveReason" to resolveReason,
            "malvar2004Available" to "true",
            "bncNeuralAvailable" to "false",
            "bncNeuralFallbackAlgorithm" to "MALVAR_2004",
            "rcdInspiredAvailable" to "false",
            "amazeAvailable" to "true",
            "amazeInspiredAvailable" to "false",
            "legacyBilinearProductAvailable" to "false",
            "legacyMenonProductAvailable" to "false",
            "fallbackOccurred" to fallbackOccurred.toString(),
            "fallbackReason" to fallbackReason
        )
}
