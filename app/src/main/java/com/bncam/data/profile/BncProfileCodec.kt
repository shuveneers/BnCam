package com.bncam.data.profile

import com.bncam.data.settings.ProfileSettingSpec
import com.bncam.data.settings.ProfileSettingValueType
import com.bncam.data.settings.ProfileSettingsSnapshot
import java.util.UUID

/** Portable, hardware-independent BnCam profile payload stored behind the .bnc extension. */
data class BncProfileDocument(
    val profileUuid: String,
    val profileName: String,
    val captureMode: String,
    val preferredFrameSource: String,
    val settings: ProfileSettingsSnapshot,
    val metadata: BncProfileMetadata
)

data class BncProfileMetadata(
    val bncamVersion: String,
    val exportedAtEpochMs: Long,
    val sourceStableLensKey: String,
    val defaultIspVersion: String,
    val sourceFrameSources: Set<String> = emptySet(),
    val hardwareCalibrationIncluded: Boolean = false
)

data class BncProfileDecodeResult(
    val document: BncProfileDocument,
    val sourceSchemaVersion: Int,
    val migrated: Boolean,
    val ignoredUnknownSettingKeys: Set<String> = emptySet(),
    val fallbackSettingKeys: Set<String> = emptySet(),
    val warnings: List<String> = emptyList()
)

class BncProfileFormatException(message: String) : IllegalArgumentException(message)

/**
 * Versioned .bnc codec.
 *
 * Schema 1 was the initial flat BnCam profile envelope. Schema 2 is the current nested envelope
 * with explicit metadata and hardware-calibration exclusion. Unknown envelope fields are ignored.
 * Unknown setting keys are ignored and reported; known missing settings are filled from their
 * portable defaults so older files remain usable when the portable profile catalog grows.
 */
object BncProfileCodec {
    const val FORMAT_IDENTIFIER = "BNCAM_PROFILE"
    const val CURRENT_SCHEMA_VERSION = 2
    const val SETTINGS_SCHEMA_VERSION = 1
    const val FILE_EXTENSION = ".bnc"
    const val MIME_TYPE = "application/x-bncam-profile"

    private const val LEGACY_SETTINGS_FORMAT = "BNCAM_PROFILE_SETTINGS_CONFIG"

    fun encode(document: BncProfileDocument): String {
        validateIdentity(document.profileUuid, document.profileName)
        require(!document.metadata.hardwareCalibrationIncluded) {
            "BnCam .bnc profiles must not contain physical sensor/hardware calibration."
        }

        val root = linkedMapOf<String, Any?>(
            "format" to FORMAT_IDENTIFIER,
            "schemaVersion" to CURRENT_SCHEMA_VERSION,
            "settingsSchemaVersion" to SETTINGS_SCHEMA_VERSION,
            "profile" to linkedMapOf(
                "uuid" to document.profileUuid,
                "name" to document.profileName,
                "captureMode" to document.captureMode,
                "frameSource" to document.preferredFrameSource,
                "settings" to snapshotToMap(document.settings)
            ),
            "metadata" to linkedMapOf(
                "bncamVersion" to document.metadata.bncamVersion,
                "exportedAtEpochMs" to document.metadata.exportedAtEpochMs,
                "sourceStableLensKey" to document.metadata.sourceStableLensKey,
                "defaultIspVersion" to document.metadata.defaultIspVersion,
                "sourceFrameSources" to document.metadata.sourceFrameSources.sorted(),
                "hardwareCalibrationIncluded" to false
            )
        )
        return PortableJson.stringify(root, pretty = true)
    }

    fun decode(raw: String, specs: List<ProfileSettingSpec>): BncProfileDecodeResult {
        if (raw.isBlank()) throw BncProfileFormatException("Profile file is empty.")
        val root = PortableJson.parseObject(raw)
        val format = root.string("format")
        return when (format) {
            FORMAT_IDENTIFIER -> decodeBnc(root, specs)
            LEGACY_SETTINGS_FORMAT -> decodeLegacySettingsConfig(root, specs)
            else -> throw BncProfileFormatException("Unsupported BnCam profile format '$format'.")
        }
    }

    private fun decodeBnc(
        root: Map<String, Any?>,
        specs: List<ProfileSettingSpec>
    ): BncProfileDecodeResult {
        val schema = root.int("schemaVersion")
            ?: throw BncProfileFormatException("Missing .bnc schemaVersion.")
        if (schema !in 1..CURRENT_SCHEMA_VERSION) {
            throw BncProfileFormatException(
                "Unsupported .bnc schema version $schema; supported range is 1..$CURRENT_SCHEMA_VERSION."
            )
        }

        return when (schema) {
            1 -> decodeSchema1(root, specs)
            CURRENT_SCHEMA_VERSION -> decodeSchema2(root, specs)
            else -> throw BncProfileFormatException("No migration path for .bnc schema $schema.")
        }
    }

    private fun decodeSchema1(
        root: Map<String, Any?>,
        specs: List<ProfileSettingSpec>
    ): BncProfileDecodeResult {
        val uuid = root.string("profileUuid")
            ?: throw BncProfileFormatException("Schema 1 profileUuid is missing.")
        val name = root.string("profileName")
            ?: throw BncProfileFormatException("Schema 1 profileName is missing.")
        validateIdentity(uuid, name)

        val settingsObject = root.obj("settings") ?: emptyMap()
        val decoded = snapshotFromMap(settingsObject, specs, fillMissingDefaults = true)
        val metadata = BncProfileMetadata(
            bncamVersion = root.string("bncamVersion") ?: "unknown",
            exportedAtEpochMs = root.long("exportedAtEpochMs") ?: 0L,
            sourceStableLensKey = root.string("sourceStableLensKey") ?: "unknown",
            defaultIspVersion = root.string("defaultIspVersion") ?: "unknown",
            sourceFrameSources = emptySet(),
            hardwareCalibrationIncluded = false
        )
        return BncProfileDecodeResult(
            document = BncProfileDocument(
                profileUuid = uuid,
                profileName = name,
                captureMode = root.string("captureMode") ?: "SINGLE_FRAME_ZSL",
                preferredFrameSource = normalizeFrameSource(root.string("frameSource") ?: "YUV"),
                settings = decoded.snapshot,
                metadata = metadata
            ),
            sourceSchemaVersion = 1,
            migrated = true,
            ignoredUnknownSettingKeys = decoded.unknownKeys,
            fallbackSettingKeys = decoded.fallbackKeys,
            warnings = listOf("Migrated .bnc schema 1 to schema $CURRENT_SCHEMA_VERSION.") + decoded.warnings
        )
    }

    private fun decodeSchema2(
        root: Map<String, Any?>,
        specs: List<ProfileSettingSpec>
    ): BncProfileDecodeResult {
        val settingsSchemaVersion = root.int("settingsSchemaVersion") ?: 1
        if (settingsSchemaVersion != SETTINGS_SCHEMA_VERSION) {
            throw BncProfileFormatException(
                "Unsupported profile settings schema $settingsSchemaVersion; supported version is $SETTINGS_SCHEMA_VERSION."
            )
        }
        val profile = root.obj("profile")
            ?: throw BncProfileFormatException("Profile payload is missing.")
        val metadataMap = root.obj("metadata") ?: emptyMap()
        if (metadataMap.boolean("hardwareCalibrationIncluded") == true) {
            throw BncProfileFormatException("Profile declares physical hardware calibration; import rejected.")
        }

        val uuid = profile.string("uuid")
            ?: throw BncProfileFormatException("Profile UUID is missing.")
        val name = profile.string("name")
            ?: throw BncProfileFormatException("Profile name is missing.")
        validateIdentity(uuid, name)

        val decoded = snapshotFromMap(profile.obj("settings") ?: emptyMap(), specs, fillMissingDefaults = true)
        val sourceFrameSources = metadataMap.array("sourceFrameSources")
            ?.mapNotNull { it as? String }
            ?.map(::normalizeFrameSource)
            ?.toSet()
            .orEmpty()

        return BncProfileDecodeResult(
            document = BncProfileDocument(
                profileUuid = uuid,
                profileName = name,
                captureMode = profile.string("captureMode") ?: "SINGLE_FRAME_ZSL",
                preferredFrameSource = normalizeFrameSource(profile.string("frameSource") ?: "YUV"),
                settings = decoded.snapshot,
                metadata = BncProfileMetadata(
                    bncamVersion = metadataMap.string("bncamVersion") ?: "unknown",
                    exportedAtEpochMs = metadataMap.long("exportedAtEpochMs") ?: 0L,
                    sourceStableLensKey = metadataMap.string("sourceStableLensKey") ?: "unknown",
                    defaultIspVersion = metadataMap.string("defaultIspVersion") ?: "unknown",
                    sourceFrameSources = sourceFrameSources,
                    hardwareCalibrationIncluded = false
                )
            ),
            sourceSchemaVersion = CURRENT_SCHEMA_VERSION,
            migrated = false,
            ignoredUnknownSettingKeys = decoded.unknownKeys,
            fallbackSettingKeys = decoded.fallbackKeys,
            warnings = decoded.warnings
        )
    }

    /** Backwards compatibility for the complete SettingsRepository JSON export used before .bnc. */
    private fun decodeLegacySettingsConfig(
        root: Map<String, Any?>,
        specs: List<ProfileSettingSpec>
    ): BncProfileDecodeResult {
        if (root.boolean("hardwareCalibrationIncluded") == true) {
            throw BncProfileFormatException("Legacy profile declares physical hardware calibration; import rejected.")
        }
        val schema = root.int("schemaNumber") ?: root.int("version") ?: 1
        if (schema !in 1..3) {
            throw BncProfileFormatException("Unsupported legacy profile config schema $schema.")
        }
        val first = root.array("profiles")?.firstOrNull() as? Map<*, *>
            ?: throw BncProfileFormatException("Legacy profile file contains no profile.")
        @Suppress("UNCHECKED_CAST")
        val profile = first as Map<String, Any?>
        val settingsObject = profile.obj("settings") ?: profile.obj("activeIspSettings") ?: emptyMap()
        val decoded = snapshotFromMap(
            objectMap = settingsObject,
            specs = specs,
            fillMissingDefaults = schema >= 3
        )
        val generatedUuid = UUID.randomUUID().toString()
        return BncProfileDecodeResult(
            document = BncProfileDocument(
                profileUuid = generatedUuid,
                profileName = profile.string("profileName")?.ifBlank { "Imported Profile" } ?: "Imported Profile",
                captureMode = profile.string("captureMode") ?: "SINGLE_FRAME_ZSL",
                preferredFrameSource = normalizeFrameSource(profile.string("frameSource") ?: "YUV"),
                settings = decoded.snapshot,
                metadata = BncProfileMetadata(
                    bncamVersion = "legacy-json",
                    exportedAtEpochMs = 0L,
                    sourceStableLensKey = "unknown",
                    defaultIspVersion = profile.string("defaultIspVersion") ?: root.string("defaultIspVersion") ?: "unknown",
                    hardwareCalibrationIncluded = false
                )
            ),
            sourceSchemaVersion = schema,
            migrated = true,
            ignoredUnknownSettingKeys = decoded.unknownKeys,
            fallbackSettingKeys = decoded.fallbackKeys,
            warnings = listOf("Imported legacy BnCam JSON profile config and migrated it to .bnc schema $CURRENT_SCHEMA_VERSION.") + decoded.warnings
        )
    }

    private data class SnapshotDecode(
        val snapshot: ProfileSettingsSnapshot,
        val unknownKeys: Set<String>,
        val fallbackKeys: Set<String>,
        val warnings: List<String>
    )

    private fun snapshotFromMap(
        objectMap: Map<String, Any?>,
        specs: List<ProfileSettingSpec>,
        fillMissingDefaults: Boolean
    ): SnapshotDecode {
        val safeSpecs = specs.distinctBy { "${it.type}:${it.key}" }
        val specByKey = safeSpecs.associateBy { it.key }
        val floats = linkedMapOf<String, Float>()
        val ints = linkedMapOf<String, Int>()
        val booleans = linkedMapOf<String, Boolean>()
        val strings = linkedMapOf<String, String>()
        val unknown = linkedSetOf<String>()
        val fallback = linkedSetOf<String>()
        val warnings = mutableListOf<String>()

        val typeObjects = mapOf(
            ProfileSettingValueType.FLOAT to objectMap.obj("floatValues").orEmpty(),
            ProfileSettingValueType.INT to objectMap.obj("intValues").orEmpty(),
            ProfileSettingValueType.BOOLEAN to objectMap.obj("booleanValues").orEmpty(),
            ProfileSettingValueType.STRING to objectMap.obj("stringValues").orEmpty()
        )

        typeObjects.values.forEach { values ->
            values.keys.filterNot { it in specByKey }.forEach { unknown += it }
        }

        safeSpecs.forEach { spec ->
            val values = typeObjects.getValue(spec.type)
            val value = values[spec.key]
            val accepted = when (spec.type) {
                ProfileSettingValueType.FLOAT -> numberAsDouble(value)
                    ?.takeIf { it.isFinite() }
                    ?.toFloat()
                    ?.takeIf { it.isFinite() }
                    ?.also { floats[spec.key] = it } != null
                ProfileSettingValueType.INT -> numberAsLong(value)?.takeIf { it in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong() }?.toInt()?.also { ints[spec.key] = it } != null
                ProfileSettingValueType.BOOLEAN -> (value as? Boolean)?.also { booleans[spec.key] = it } != null
                ProfileSettingValueType.STRING -> (value as? String)?.also { strings[spec.key] = it } != null
            }
            if (!accepted && value != null) {
                warnings += "Invalid value for '${spec.key}' was ignored."
            }
            if (!accepted && fillMissingDefaults) {
                val default = spec.portableDefault
                    ?: throw BncProfileFormatException("No portable default exists for '${spec.key}'.")
                when (spec.type) {
                    ProfileSettingValueType.FLOAT -> default.toFloatOrNull()?.let { floats[spec.key] = it }
                    ProfileSettingValueType.INT -> default.toIntOrNull()?.let { ints[spec.key] = it }
                    ProfileSettingValueType.BOOLEAN -> default.toBooleanStrictOrNull()?.let { booleans[spec.key] = it }
                    ProfileSettingValueType.STRING -> strings.put(spec.key, default)
                }
                fallback += spec.key
            }
        }

        if (unknown.isNotEmpty()) warnings += "Ignored ${unknown.size} unknown profile setting key(s)."
        if (fallback.isNotEmpty()) warnings += "Applied portable defaults for ${fallback.size} missing/invalid setting(s)."

        return SnapshotDecode(
            snapshot = ProfileSettingsSnapshot(floats, ints, booleans, strings),
            unknownKeys = unknown,
            fallbackKeys = fallback,
            warnings = warnings
        )
    }

    private fun snapshotToMap(snapshot: ProfileSettingsSnapshot): Map<String, Any?> = linkedMapOf(
        "floatValues" to snapshot.floatValues.toSortedMap().mapValues { it.value.toDouble() },
        "intValues" to snapshot.intValues.toSortedMap(),
        "booleanValues" to snapshot.booleanValues.toSortedMap(),
        "stringValues" to snapshot.stringValues.toSortedMap()
    )

    private fun validateIdentity(uuid: String, name: String) {
        try {
            UUID.fromString(uuid)
        } catch (_: Throwable) {
            throw BncProfileFormatException("Profile UUID is invalid.")
        }
        if (name.isBlank()) throw BncProfileFormatException("Profile name is blank.")
        if (name.length > 120) throw BncProfileFormatException("Profile name is too long.")
    }

    fun normalizeFrameSource(value: String): String = when (value.trim().uppercase()) {
        "RAW10" -> "RAW10"
        "RAW_SENSOR" -> "RAW_SENSOR"
        else -> "YUV"
    }

    fun suggestedFileName(profileName: String): String {
        val safe = profileName
            .trim()
            .replace(Regex("[\\/:*?\"<>|\u0000-\u001F]"), "_")
            .trim(' ', '.')
            .take(80)
            .ifBlank { "BnCam_Profile" }
        return safe + FILE_EXTENSION
    }

    private fun numberAsDouble(value: Any?): Double? = when (value) {
        is Double -> value
        is Float -> value.toDouble()
        is Long -> value.toDouble()
        is Int -> value.toDouble()
        else -> null
    }

    private fun numberAsLong(value: Any?): Long? = when (value) {
        is Long -> value
        is Int -> value.toLong()
        is Double -> if (value.isFinite() && value % 1.0 == 0.0) value.toLong() else null
        else -> null
    }

    private fun Map<String, Any?>.string(key: String): String? = this[key] as? String
    private fun Map<String, Any?>.boolean(key: String): Boolean? = this[key] as? Boolean
    private fun Map<String, Any?>.int(key: String): Int? = numberAsLong(this[key])?.takeIf { it in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong() }?.toInt()
    private fun Map<String, Any?>.long(key: String): Long? = numberAsLong(this[key])
    @Suppress("UNCHECKED_CAST")
    private fun Map<String, Any?>.obj(key: String): Map<String, Any?>? = this[key] as? Map<String, Any?>
    @Suppress("UNCHECKED_CAST")
    private fun Map<String, Any?>.array(key: String): List<Any?>? = this[key] as? List<Any?>
}

/** Minimal strict JSON reader/writer kept JVM-testable and dependency-free. */
private object PortableJson {
    fun parseObject(raw: String): Map<String, Any?> {
        val reader = Reader(raw)
        val value = reader.readValue()
        reader.skipWhitespace()
        if (!reader.atEnd()) throw BncProfileFormatException("Unexpected trailing data in profile file.")
        @Suppress("UNCHECKED_CAST")
        return value as? Map<String, Any?>
            ?: throw BncProfileFormatException("Profile root must be a JSON object.")
    }

    fun stringify(value: Any?, pretty: Boolean = false): String = buildString {
        writeValue(this, value, if (pretty) 0 else -1)
    }

    private fun writeValue(out: StringBuilder, value: Any?, indent: Int) {
        when (value) {
            null -> out.append("null")
            is String -> writeString(out, value)
            is Boolean -> out.append(value)
            is Number -> {
                val text = value.toString()
                if (text == "NaN" || text == "Infinity" || text == "-Infinity") {
                    throw BncProfileFormatException("Non-finite number cannot be serialized.")
                }
                out.append(text)
            }
            is Map<*, *> -> {
                out.append('{')
                val entries = value.entries.toList()
                entries.forEachIndexed { index, entry ->
                    if (indent >= 0) out.append('\n').append("  ".repeat(indent + 1))
                    writeString(out, entry.key as? String ?: error("JSON object key must be a string"))
                    out.append(if (indent >= 0) ": " else ":")
                    writeValue(out, entry.value, if (indent >= 0) indent + 1 else -1)
                    if (index != entries.lastIndex) out.append(',')
                }
                if (entries.isNotEmpty() && indent >= 0) out.append('\n').append("  ".repeat(indent))
                out.append('}')
            }
            is Iterable<*> -> {
                out.append('[')
                val values = value.toList()
                values.forEachIndexed { index, item ->
                    if (indent >= 0) out.append('\n').append("  ".repeat(indent + 1))
                    writeValue(out, item, if (indent >= 0) indent + 1 else -1)
                    if (index != values.lastIndex) out.append(',')
                }
                if (values.isNotEmpty() && indent >= 0) out.append('\n').append("  ".repeat(indent))
                out.append(']')
            }
            else -> throw BncProfileFormatException("Unsupported JSON value type ${value::class.java.simpleName}.")
        }
    }

    private fun writeString(out: StringBuilder, value: String) {
        out.append('"')
        value.forEach { ch ->
            when (ch) {
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '\b' -> out.append("\\b")
                '\u000C' -> out.append("\\f")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                else -> if (ch.code < 0x20) out.append("\\u%04x".format(ch.code)) else out.append(ch)
            }
        }
        out.append('"')
    }

    private class Reader(private val raw: String) {
        private var index = 0

        fun atEnd(): Boolean = index >= raw.length
        fun skipWhitespace() { while (!atEnd() && raw[index].isWhitespace()) index++ }

        fun readValue(): Any? {
            skipWhitespace()
            if (atEnd()) throw BncProfileFormatException("Unexpected end of profile JSON.")
            return when (raw[index]) {
                '{' -> readObject()
                '[' -> readArray()
                '"' -> readString()
                't' -> readLiteral("true", true)
                'f' -> readLiteral("false", false)
                'n' -> readLiteral("null", null)
                '-', in '0'..'9' -> readNumber()
                else -> throw BncProfileFormatException("Unexpected JSON token at offset $index.")
            }
        }

        private fun readObject(): Map<String, Any?> {
            expect('{')
            skipWhitespace()
            val result = linkedMapOf<String, Any?>()
            if (consume('}')) return result
            while (true) {
                skipWhitespace()
                if (atEnd() || raw[index] != '"') throw BncProfileFormatException("Expected object key at offset $index.")
                val key = readString()
                skipWhitespace()
                expect(':')
                if (key in result) throw BncProfileFormatException("Duplicate JSON key '$key'.")
                result[key] = readValue()
                skipWhitespace()
                if (consume('}')) return result
                expect(',')
            }
        }

        private fun readArray(): List<Any?> {
            expect('[')
            skipWhitespace()
            val result = mutableListOf<Any?>()
            if (consume(']')) return result
            while (true) {
                result += readValue()
                skipWhitespace()
                if (consume(']')) return result
                expect(',')
            }
        }

        private fun readString(): String {
            expect('"')
            val out = StringBuilder()
            while (!atEnd()) {
                val ch = raw[index++]
                when (ch) {
                    '"' -> return out.toString()
                    '\\' -> {
                        if (atEnd()) throw BncProfileFormatException("Unterminated JSON escape.")
                        when (val escaped = raw[index++]) {
                            '"' -> out.append('"')
                            '\\' -> out.append('\\')
                            '/' -> out.append('/')
                            'b' -> out.append('\b')
                            'f' -> out.append('\u000C')
                            'n' -> out.append('\n')
                            'r' -> out.append('\r')
                            't' -> out.append('\t')
                            'u' -> {
                                if (index + 4 > raw.length) throw BncProfileFormatException("Invalid unicode escape.")
                                val hex = raw.substring(index, index + 4)
                                val code = hex.toIntOrNull(16) ?: throw BncProfileFormatException("Invalid unicode escape.")
                                out.append(code.toChar())
                                index += 4
                            }
                            else -> throw BncProfileFormatException("Invalid JSON escape '\\$escaped'.")
                        }
                    }
                    else -> {
                        if (ch.code < 0x20) throw BncProfileFormatException("Control character in JSON string.")
                        out.append(ch)
                    }
                }
            }
            throw BncProfileFormatException("Unterminated JSON string.")
        }

        private fun readNumber(): Any {
            val start = index
            if (raw[index] == '-') index++
            if (atEnd()) throw BncProfileFormatException("Invalid JSON number.")
            if (raw[index] == '0') {
                index++
            } else {
                if (raw[index] !in '1'..'9') throw BncProfileFormatException("Invalid JSON number.")
                while (!atEnd() && raw[index].isDigit()) index++
            }
            var floating = false
            if (!atEnd() && raw[index] == '.') {
                floating = true
                index++
                val fractionStart = index
                while (!atEnd() && raw[index].isDigit()) index++
                if (fractionStart == index) throw BncProfileFormatException("Invalid JSON fraction.")
            }
            if (!atEnd() && (raw[index] == 'e' || raw[index] == 'E')) {
                floating = true
                index++
                if (!atEnd() && (raw[index] == '+' || raw[index] == '-')) index++
                val exponentStart = index
                while (!atEnd() && raw[index].isDigit()) index++
                if (exponentStart == index) throw BncProfileFormatException("Invalid JSON exponent.")
            }
            val token = raw.substring(start, index)
            return if (floating) {
                token.toDoubleOrNull()?.takeIf { it.isFinite() }
                    ?: throw BncProfileFormatException("Invalid or non-finite JSON number.")
            } else {
                token.toLongOrNull() ?: throw BncProfileFormatException("JSON integer is out of range.")
            }
        }

        private fun <T> readLiteral(token: String, value: T): T {
            if (!raw.regionMatches(index, token, 0, token.length)) {
                throw BncProfileFormatException("Invalid JSON literal at offset $index.")
            }
            index += token.length
            return value
        }

        private fun consume(ch: Char): Boolean {
            skipWhitespace()
            if (!atEnd() && raw[index] == ch) {
                index++
                return true
            }
            return false
        }

        private fun expect(ch: Char) {
            skipWhitespace()
            if (atEnd() || raw[index] != ch) {
                throw BncProfileFormatException("Expected '$ch' at offset $index.")
            }
            index++
        }
    }
}
