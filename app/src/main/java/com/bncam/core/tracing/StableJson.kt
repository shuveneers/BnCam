package com.bncam.core.tracing

/** Small deterministic JSON encoder for bounded trace/recipe metadata. */
object StableJson {
    fun encode(value: Any?): String = when (value) {
        null -> "null"
        is String -> quote(value)
        is Number,
        is Boolean -> value.toString()
        is Enum<*> -> quote(value.name)
        is Map<*, *> -> value.entries.joinToString(
            prefix = "{",
            postfix = "}",
            separator = ","
        ) { (key, item) -> "${quote(key.toString())}:${encode(item)}" }
        is Iterable<*> -> value.joinToString(
            prefix = "[",
            postfix = "]",
            separator = ","
        ) { encode(it) }
        is Array<*> -> value.asIterable().joinToString(
            prefix = "[",
            postfix = "]",
            separator = ","
        ) { encode(it) }
        else -> quote(value.toString())
    }

    private fun quote(value: String): String = buildString(value.length + 2) {
        append('"')
        value.forEach { char ->
            when (char) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (char.code < 0x20) {
                    append("\\u")
                    append(char.code.toString(16).padStart(4, '0'))
                } else {
                    append(char)
                }
            }
        }
        append('"')
    }
}
