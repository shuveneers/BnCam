package com.bncam.core.debug

/**
 * Compact, read-only shutter-time snapshot spanning the runtime owners that most often explain a
 * camera result without changing any of those owners.
 *
 * The snapshot is deliberately transport-agnostic. [compactText] contains no semicolons or line
 * breaks, so it can be embedded in the existing Camera2 exposure-policy diagnostic record without
 * creating another persistent debug file or another logging subsystem.
 */
data class RuntimeDiagnosticDomain private constructor(
    val status: String,
    val values: Map<String, String>
) {
    companion object {
        fun of(status: String, vararg values: Pair<String, Any?>): RuntimeDiagnosticDomain =
            RuntimeDiagnosticDomain(
                status = status,
                values = values.associate { (key, value) -> key to (value?.toString() ?: "unavailable") }
            )

        fun unavailable(reason: String): RuntimeDiagnosticDomain =
            of("UNAVAILABLE", "reason" to reason)
    }
}

data class UnifiedRuntimeDiagnosticsSnapshot(
    val generation: Int,
    val captureRoute: String,
    val ae: RuntimeDiagnosticDomain,
    val rawPreview: RuntimeDiagnosticDomain,
    val calibration: RuntimeDiagnosticDomain,
    val noise: RuntimeDiagnosticDomain,
    val capability: RuntimeDiagnosticDomain,
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION
) {
    fun compactText(): String = buildString {
        append("schema=").append(schemaVersion)
        append("|generation=").append(generation)
        append("|route=").append(clean(captureRoute))
        appendDomain("AE", ae)
        appendDomain("RAW_PREVIEW", rawPreview)
        appendDomain("CALIBRATION", calibration)
        appendDomain("NOISE", noise)
        appendDomain("CAPABILITY", capability)
    }

    private fun StringBuilder.appendDomain(name: String, domain: RuntimeDiagnosticDomain) {
        append('|').append(name).append('{')
        append("status=").append(clean(domain.status))
        domain.values.toSortedMap().forEach { (key, value) ->
            append(',').append(cleanKey(key)).append('=').append(clean(value))
        }
        append('}')
    }

    companion object {
        const val CURRENT_SCHEMA_VERSION = 1
        private const val MAX_VALUE_CHARS = 384

        internal fun cleanForTest(value: String): String = clean(value)

        private fun cleanKey(value: String): String = clean(value)
            .replace(' ', '_')
            .ifBlank { "unnamed" }

        private fun clean(value: String): String {
            val normalized = buildString(value.length.coerceAtMost(MAX_VALUE_CHARS)) {
                var previousWhitespace = false
                for (character in value) {
                    if (length >= MAX_VALUE_CHARS) break
                    val replacement = when (character) {
                        '\r', '\n', '\t', ';', '|', '{', '}' -> ' '
                        else -> character
                    }
                    if (replacement.isWhitespace()) {
                        if (!previousWhitespace && isNotEmpty()) append(' ')
                        previousWhitespace = true
                    } else {
                        append(replacement)
                        previousWhitespace = false
                    }
                }
            }.trim()
            return normalized.take(MAX_VALUE_CHARS).ifBlank { "unavailable" }
        }
    }
}
