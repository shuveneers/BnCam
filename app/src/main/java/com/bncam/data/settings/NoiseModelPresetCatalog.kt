package com.bncam.data.settings

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64

enum class NoiseModelPresetOrigin {
    USER,
    AGC_V12
}

data class NoiseModelPreset(
    val id: String,
    val displayName: String,
    val origin: NoiseModelPresetOrigin,
    val model: PersistedParametricNoiseModel,
    val importedAtEpochMs: Long? = null
) {
    init {
        require(id.isNotBlank()) { "preset id must not be blank" }
        require(displayName.isNotBlank()) { "preset displayName must not be blank" }
        if (origin == NoiseModelPresetOrigin.USER) {
            require(importedAtEpochMs != null && importedAtEpochMs >= 0L) {
                "user preset requires importedAtEpochMs"
            }
        }
    }
}

/** Unified lookup used by UI now and capture integration later. */
object NoiseModelPresetCatalog {
    fun merged(userPresets: List<NoiseModelPreset>): List<NoiseModelPreset> {
        val users = userPresets
            .filter { it.origin == NoiseModelPresetOrigin.USER }
            .distinctBy(NoiseModelPreset::id)
            .sortedWith(
                compareByDescending<NoiseModelPreset> { it.importedAtEpochMs ?: Long.MIN_VALUE }
                    .thenBy { it.displayName.lowercase() }
            )
        return users + AgcV12NoiseModelPresets.all
    }

    fun resolve(id: String?, userPresets: List<NoiseModelPreset>): NoiseModelPreset? {
        if (id.isNullOrBlank()) return null
        return userPresets.firstOrNull { it.id == id } ?: AgcV12NoiseModelPresets.find(id)
    }
}

/** Parser for AGC/CameraITS C-style noise-model text files. */
object NoiseModelPresetImportParser {
    private val arrayRegexes = mapOf(
        "A" to Regex("""(?is)static\s+double\s+noise_model_A\s*\[\s*]\s*=\s*\{(.*?)}\s*;"""),
        "B" to Regex("""(?is)static\s+double\s+noise_model_B\s*\[\s*]\s*=\s*\{(.*?)}\s*;"""),
        "C" to Regex("""(?is)static\s+double\s+noise_model_C\s*\[\s*]\s*=\s*\{(.*?)}\s*;"""),
        "D" to Regex("""(?is)static\s+double\s+noise_model_D\s*\[\s*]\s*=\s*\{(.*?)}\s*;""")
    )
    private val isoStepPatterns = listOf(
        Regex("""(?i)digital_gain\s*=.*?sens\s*/\s*([+\-]?(?:\d+(?:\.\d*)?|\.\d+)(?:[eE][+\-]?\d+)?)"""),
        Regex("""(?i)(?:iso_step|isoStep)\s*[:=]\s*([+\-]?(?:\d+(?:\.\d*)?|\.\d+)(?:[eE][+\-]?\d+)?)""")
    )

    fun parse(
        text: String,
        displayName: String,
        importedAtEpochMs: Long = System.currentTimeMillis()
    ): NoiseModelPreset {
        val cleanName = displayName.trim().ifBlank { "Imported noise model" }
        val a = parseArray(text, "A")
        val b = parseArray(text, "B")
        val c = parseArray(text, "C")
        val d = parseArray(text, "D")
        val isoStep = isoStepPatterns.firstNotNullOfOrNull { pattern ->
            pattern.find(text)?.groupValues?.getOrNull(1)?.toDoubleOrNull()
        }?.takeIf { it.isFinite() && it > 0.0 }
            ?: throw IllegalArgumentException(
                "Missing positive ISO step/digital-gain denominator (for example sens / 800.0)."
            )
        val model = PersistedParametricNoiseModel(a, b, c, d, isoStep)
        return NoiseModelPreset(
            id = userPresetId(cleanName, model),
            displayName = cleanName,
            origin = NoiseModelPresetOrigin.USER,
            model = model,
            importedAtEpochMs = importedAtEpochMs
        )
    }

    private fun parseArray(text: String, label: String): List<Double> {
        val body = arrayRegexes.getValue(label).find(text)?.groupValues?.getOrNull(1)
            ?: throw IllegalArgumentException("Missing noise_model_$label[] array.")
        val values = body
            .split(',', ';', '\n', '\r', '\t', ' ')
            .map(String::trim)
            .filter(String::isNotBlank)
            .map { token ->
                token.toDoubleOrNull()?.takeIf(Double::isFinite)
                    ?: throw IllegalArgumentException("Invalid $label coefficient '$token'.")
            }
        require(values.size == 4) {
            "noise_model_$label[] must contain exactly four R/Gr/Gb/B values; found ${values.size}."
        }
        return values
    }

    private fun userPresetId(name: String, model: PersistedParametricNoiseModel): String {
        val canonical = buildString {
            append(name); append('|')
            append(PhysicalNoiseModelPersistenceCodec.encodeVector(model.a)); append('|')
            append(PhysicalNoiseModelPersistenceCodec.encodeVector(model.b)); append('|')
            append(PhysicalNoiseModelPersistenceCodec.encodeVector(model.c)); append('|')
            append(PhysicalNoiseModelPersistenceCodec.encodeVector(model.d)); append('|')
            append(model.isoStep)
        }
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(StandardCharsets.UTF_8))
            .take(12)
            .joinToString("") { "%02x".format(it) }
        return "user:$digest"
    }
}

internal object NoiseModelUserPresetCodec {
    private const val VERSION = "v1"

    fun encode(preset: NoiseModelPreset): String {
        require(preset.origin == NoiseModelPresetOrigin.USER)
        val e = PhysicalNoiseModelPersistenceCodec.encodeModel(preset.model)
        val encodedName = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(preset.displayName.toByteArray(StandardCharsets.UTF_8))
        return listOf(
            VERSION,
            preset.id,
            encodedName,
            (preset.importedAtEpochMs ?: 0L).toString(),
            e.a,
            e.b,
            e.c,
            e.d,
            e.isoStep
        ).joinToString("|")
    }

    fun decode(raw: String): NoiseModelPreset? = runCatching {
        val p = raw.split('|')
        if (p.size != 9 || p[0] != VERSION || !p[1].startsWith("user:")) return null
        val name = String(Base64.getUrlDecoder().decode(p[2]), StandardCharsets.UTF_8).trim()
        val importedAt = p[3].toLongOrNull()?.takeIf { it >= 0L } ?: return null
        val model = PhysicalNoiseModelPersistenceCodec.decodeModel(
            a = p[4], b = p[5], c = p[6], d = p[7], isoStep = p[8]
        ) ?: return null
        NoiseModelPreset(
            id = p[1],
            displayName = name,
            origin = NoiseModelPresetOrigin.USER,
            model = model,
            importedAtEpochMs = importedAt
        )
    }.getOrNull()
}
