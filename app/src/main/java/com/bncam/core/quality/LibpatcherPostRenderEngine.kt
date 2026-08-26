package com.bncam.core.quality

import java.util.Locale

/** Runtime bridge retained for reporting that legacy common-render libpatcher controls are removed. */
data class CommonPostRenderResult(
    val post: PostProcessingQualityConfig,
    val applied: Boolean,
    val activeControlCount: Int,
    val neutralControlCount: Int,
    val mappedControlCount: Int,
    val neutralSkippedCount: Int,
    val activeControlLines: List<String>,
    val clampEvents: List<String>,
    val aggregateValues: Map<String, Float>
) {
    fun debugPairs(maxLines: Int = 80): List<Pair<String, String>> {
        val pairs = mutableListOf<Pair<String, String>>()
        pairs += "Common Post-Render Applied" to applied.toString()
        pairs += "Common Post-Render Scope" to "ISP Controls have been removed. No common render settings are applied."
        return pairs
    }
}

object LibpatcherPostRenderEngine {
    fun applyCommonPostRender(
        base: PostProcessingQualityConfig,
        resolved: ResolvedIspSettings
    ): CommonPostRenderResult {
        return CommonPostRenderResult(
            post = base,
            applied = false,
            activeControlCount = 0,
            neutralControlCount = 0,
            mappedControlCount = 0,
            neutralSkippedCount = 0,
            activeControlLines = emptyList(),
            clampEvents = emptyList(),
            aggregateValues = emptyMap()
        )
    }
}
