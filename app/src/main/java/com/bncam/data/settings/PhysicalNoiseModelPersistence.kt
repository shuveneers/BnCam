package com.bncam.data.settings

import com.bncam.core.quality.NoiseModelResolver
import com.bncam.core.quality.NoiseModelSource
import com.bncam.core.quality.ParametricNoiseModel
import kotlin.math.roundToInt

const val PHYSICAL_NOISE_MODEL_SCHEMA_VERSION = 2
private const val PHYSICAL_NOISE_CHANNEL_COUNT = 4

/**
 * Persistable canonical R,Gr,Gb,B A/B/C/D model.
 *
 * Coefficients deliberately remain signed. Only the final resolved physical S/O is clamped
 * by [ParametricNoiseModel], never the persisted model itself.
 */
data class PersistedParametricNoiseModel(
    val a: List<Double>,
    val b: List<Double>,
    val c: List<Double>,
    val d: List<Double>,
    val isoStep: Double
) {
    init {
        validateVector(a, "A")
        validateVector(b, "B")
        validateVector(c, "C")
        validateVector(d, "D")
        require(isoStep.isFinite() && isoStep > 0.0) { "isoStep must be finite and > 0" }
    }

    fun toCoreModel(): ParametricNoiseModel = ParametricNoiseModel(
        a = a.toDoubleArray(),
        b = b.toDoubleArray(),
        c = c.toDoubleArray(),
        d = d.toDoubleArray(),
        isoStep = isoStep
    )

    companion object {
        fun fromCoreModel(model: ParametricNoiseModel): PersistedParametricNoiseModel =
            PersistedParametricNoiseModel(
                a = model.a.toList(),
                b = model.b.toList(),
                c = model.c.toList(),
                d = model.d.toList(),
                isoStep = model.isoStep
            )

        private fun validateVector(values: List<Double>, label: String) {
            require(values.size == PHYSICAL_NOISE_CHANNEL_COUNT) {
                "$label must contain exactly four canonical R,Gr,Gb,B coefficients"
            }
            require(values.all(Double::isFinite)) { "$label coefficients must be finite" }
        }
    }
}

data class PhysicalNoiseModelMigrationMetadata(
    val migratedFromLegacy: Boolean = false,
    val legacyMode: String? = null,
    val legacyManualSoRetained: Boolean = false,
    val legacyParametricFieldsRetained: Boolean = false,
    val legacyDynamicIsoReset: Boolean = false
)

/**
 * Per-lens persisted configuration for the new physical noise-model architecture.
 *
 * SYSTEM and MANUAL keep separate parametric records so switching source never destroys the
 * other source. PRESET stores only an ID here; the shared preset catalog is Phase 3.
 * OEM always resolves the current Camera2 SENSOR_NOISE_PROFILE and therefore needs no model.
 */
data class LensPhysicalNoiseModelSettings(
    val schemaVersion: Int = PHYSICAL_NOISE_MODEL_SCHEMA_VERSION,
    val source: NoiseModelSource = NoiseModelSource.SYSTEM,
    val dynamicIsoEnabled: Boolean = NoiseModelResolver.DEFAULT_DYNAMIC_ISO_ENABLED,
    val dynamicIsoCoefficient: Double = NoiseModelResolver.DEFAULT_DYNAMIC_ISO_COEFFICIENT,
    val systemModel: PersistedParametricNoiseModel? = null,
    val systemModelOrigin: String? = null,
    val manualModel: PersistedParametricNoiseModel? = null,
    val selectedPresetId: String? = null,
    val migration: PhysicalNoiseModelMigrationMetadata = PhysicalNoiseModelMigrationMetadata()
) {
    fun sanitized(): LensPhysicalNoiseModelSettings = copy(
        schemaVersion = PHYSICAL_NOISE_MODEL_SCHEMA_VERSION,
        dynamicIsoCoefficient = sanitizePhysicalDynamicIsoCoefficient(dynamicIsoCoefficient),
        systemModelOrigin = systemModelOrigin.cleanOptionalToken(),
        selectedPresetId = selectedPresetId.cleanOptionalToken()?.takeIf { it.startsWith("bncam:") || it.startsWith("user:") }
    )

    val selectedParametricModel: PersistedParametricNoiseModel?
        get() = when (source) {
            NoiseModelSource.SYSTEM -> systemModel
            NoiseModelSource.MANUAL -> manualModel
            NoiseModelSource.OEM,
            NoiseModelSource.PRESET -> null
        }

    /**
     * A selected SYSTEM/MANUAL model can be incomplete during migration or editing. PRESET is
     * configuration-complete once an ID is selected; Phase 3 still has to resolve that ID in the
     * shared catalog before capture can use it. Capture integration must explicitly fall back to
     * OEM whenever the selected source cannot be resolved.
     */
    val selectedSourceConfigured: Boolean
        get() = when (source) {
            NoiseModelSource.OEM -> true
            NoiseModelSource.SYSTEM -> systemModel != null
            NoiseModelSource.MANUAL -> manualModel != null
            NoiseModelSource.PRESET -> !selectedPresetId.isNullOrBlank()
        }
}

/** Raw legacy state. Values are kept raw because migration must not reinterpret old semantics. */
data class LegacyPhysicalNoiseModelState(
    val mode: String? = null,
    val noiseA: String? = null,
    val noiseB: String? = null,
    val noiseC: String? = null,
    val noiseD: String? = null,
    val isoStep: String? = null,
    val dynamicIsoCoefficient: Double? = null,
    val manualNoiseSo: String? = null
) {
    val hasAnyLegacyValue: Boolean
        get() = listOf(mode, noiseA, noiseB, noiseC, noiseD, isoStep, manualNoiseSo)
            .any { !it.isNullOrBlank() } || dynamicIsoCoefficient != null
}

/**
 * One-way semantic migration from the legacy Off/Auto/Manual system.
 *
 * Important: old Manual S/O and old Dynamic ISO cannot be converted into the new model without
 * inventing physics. Existing legacy DataStore keys are therefore retained untouched by the
 * storage layer; migration only selects a safe new authority and records provenance.
 */
object PhysicalNoiseModelMigration {
    fun migrate(legacy: LegacyPhysicalNoiseModelState): LensPhysicalNoiseModelSettings {
        val normalizedMode = legacy.mode?.trim()?.lowercase().orEmpty()

        // After OEM was split out as its own source, legacy Auto is an exact semantic match for
        // OEM because it consumed Camera2 SENSOR_NOISE_PROFILE directly. Off/Manual have no exact
        // parametric equivalent and intentionally land on SYSTEM without fabricating a model.
        val source = when (normalizedMode) {
            "auto", "on", "oem" -> NoiseModelSource.OEM
            "preset" -> NoiseModelSource.PRESET
            "manual", "off", "system", "" -> NoiseModelSource.SYSTEM
            else -> NoiseModelSource.SYSTEM
        }

        return LensPhysicalNoiseModelSettings(
            source = source,
            dynamicIsoEnabled = false,
            dynamicIsoCoefficient = NoiseModelResolver.DEFAULT_DYNAMIC_ISO_COEFFICIENT,
            systemModel = null,
            systemModelOrigin = null,
            manualModel = null,
            selectedPresetId = null,
            migration = PhysicalNoiseModelMigrationMetadata(
                migratedFromLegacy = legacy.hasAnyLegacyValue,
                legacyMode = legacy.mode?.trim()?.takeIf(String::isNotEmpty),
                legacyManualSoRetained = !legacy.manualNoiseSo.isNullOrBlank(),
                legacyParametricFieldsRetained = listOf(
                    legacy.noiseA,
                    legacy.noiseB,
                    legacy.noiseC,
                    legacy.noiseD,
                    legacy.isoStep
                ).any { !it.isNullOrBlank() },
                legacyDynamicIsoReset = legacy.dynamicIsoCoefficient != null
            )
        ).sanitized()
    }
}

object PhysicalNoiseModelPersistenceCodec {
    fun encodeVector(values: List<Double>): String {
        require(values.size == PHYSICAL_NOISE_CHANNEL_COUNT)
        require(values.all(Double::isFinite))
        return values.joinToString(",") { it.toString() }
    }

    fun decodeVector(raw: String?): List<Double>? {
        if (raw.isNullOrBlank()) return null
        val values = raw
            .trim()
            .split(Regex("[,;|\\s]+"))
            .filter(String::isNotBlank)
            .map { it.toDoubleOrNull() ?: return null }
        if (values.size != PHYSICAL_NOISE_CHANNEL_COUNT || values.any { !it.isFinite() }) return null
        return values
    }

    fun encodeModel(model: PersistedParametricNoiseModel): EncodedParametricNoiseModel =
        EncodedParametricNoiseModel(
            a = encodeVector(model.a),
            b = encodeVector(model.b),
            c = encodeVector(model.c),
            d = encodeVector(model.d),
            isoStep = model.isoStep.toString()
        )

    fun decodeModel(
        a: String?,
        b: String?,
        c: String?,
        d: String?,
        isoStep: String?
    ): PersistedParametricNoiseModel? {
        val decodedA = decodeVector(a) ?: return null
        val decodedB = decodeVector(b) ?: return null
        val decodedC = decodeVector(c) ?: return null
        val decodedD = decodeVector(d) ?: return null
        val decodedIsoStep = isoStep?.trim()?.toDoubleOrNull()
            ?.takeIf { it.isFinite() && it > 0.0 }
            ?: return null
        return PersistedParametricNoiseModel(
            a = decodedA,
            b = decodedB,
            c = decodedC,
            d = decodedD,
            isoStep = decodedIsoStep
        )
    }
}

data class EncodedParametricNoiseModel(
    val a: String,
    val b: String,
    val c: String,
    val d: String,
    val isoStep: String
)

fun sanitizePhysicalDynamicIsoCoefficient(value: Double): Double {
    if (!value.isFinite()) return NoiseModelResolver.DEFAULT_DYNAMIC_ISO_COEFFICIENT
    val clamped = value.coerceIn(
        NoiseModelResolver.MIN_DYNAMIC_ISO_COEFFICIENT,
        NoiseModelResolver.MAX_DYNAMIC_ISO_COEFFICIENT
    )
    val steps = ((clamped - NoiseModelResolver.MIN_DYNAMIC_ISO_COEFFICIENT) /
        NoiseModelResolver.DYNAMIC_ISO_COEFFICIENT_STEP).roundToInt()
    return NoiseModelResolver.MIN_DYNAMIC_ISO_COEFFICIENT +
        steps * NoiseModelResolver.DYNAMIC_ISO_COEFFICIENT_STEP
}

internal fun parsePersistedNoiseModelSource(value: String?): NoiseModelSource = when (
    value?.trim()?.lowercase()
) {
    "oem" -> NoiseModelSource.OEM
    "manual" -> NoiseModelSource.MANUAL
    "preset" -> NoiseModelSource.PRESET
    "system" -> NoiseModelSource.SYSTEM
    else -> NoiseModelSource.SYSTEM
}

private fun String?.cleanOptionalToken(): String? = this?.trim()?.takeIf(String::isNotEmpty)
