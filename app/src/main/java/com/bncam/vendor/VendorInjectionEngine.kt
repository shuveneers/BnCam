@file:Suppress("UNCHECKED_CAST", "SpellCheckingInspection", "unused")

package com.bncam.vendor

import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.TotalCaptureResult
import android.util.Log
import com.bncam.core.capture.BlackLevelLockController
import com.bncam.core.capture.BlackLevelLockRequestStage
import com.bncam.data.settings.SettingsRepository
import com.bncam.data.settings.VendorTagConfig
import com.bncam.data.settings.VendorTagTarget
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class VendorRequestStage {
    SESSION,
    REPEATING_REQUEST,
    STILL_CAPTURE
}

enum class VendorInjectionStatus {
    APPLIED,
    SKIPPED_DISABLED,
    SKIPPED_STAGE_MISMATCH,
    SKIPPED_NOT_INJECTABLE,
    SKIPPED_RESULT_ONLY,
    SKIPPED_CHARACTERISTIC_ONLY,
    PARSE_FAILED,
    APPLY_FAILED
}

enum class VendorValueType {
    BYTE,
    SHORT,
    INT,
    LONG,
    FLOAT,
    DOUBLE,
    BOOLEAN,
    STRING,
    BYTE_ARRAY,
    SHORT_ARRAY,
    INT_ARRAY,
    LONG_ARRAY,
    FLOAT_ARRAY,
    DOUBLE_ARRAY,
    BOOLEAN_ARRAY,
    UNKNOWN
}

data class VendorInjectionAttempt(
    val lensId: String,
    val keyName: String,
    val requestedValue: String,
    val parsedValuePreview: String,
    val valueType: VendorValueType,
    val target: VendorTagTarget,
    val attempted: Boolean,
    val appliedToBuilder: Boolean,
    val builderStage: VendorRequestStage,
    val parseError: String = "",
    val applyError: String = "",
    val finalStatus: VendorInjectionStatus,
    val source: String = "",
    val notes: String = ""
)

object VendorInjectionEngine {
    private const val TAG = "VendorInjectionEngine"

    private val attemptsByLens = ConcurrentHashMap<String, MutableList<VendorInjectionAttempt>>()

    private val _echoStatuses = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val echoStatuses = _echoStatuses.asStateFlow()

    fun clearEchoStatuses(@Suppress("UNUSED_PARAMETER") lensId: String) {
        _echoStatuses.value = emptyMap()
    }

    fun clearAllEchoStatuses() {
        _echoStatuses.value = emptyMap()
    }

    fun verifyEchoes(lensId: String, result: TotalCaptureResult) {
        // Standard Camera2 truth must be observed even when this lens has zero vendor tags.
        // Keeping this before the legacy early-return prevents BLACK_LEVEL_LOCK telemetry from
        // accidentally depending on vendor-tag configuration.
        BlackLevelLockController.observeResult(lensId, result)

        val activeAttempts = attemptsByLens[lensId] ?: return
        val resultKeys = result.keys.map { it.name }.toSet()
        val updates = activeAttempts.associate { attempt ->
            attempt.keyName to resultKeys.contains(attempt.keyName)
        }
        _echoStatuses.value = _echoStatuses.value + updates
    }

    suspend fun applyToBuilder(
        lensId: String,
        builder: CaptureRequest.Builder,
        stage: VendorRequestStage,
        settingsRepo: SettingsRepository,
        registry: List<DynamicVendorTag> = emptyList()
    ): List<VendorInjectionAttempt> = applyPreparedTags(
        lensId = lensId,
        builder = builder,
        stage = stage,
        activeTags = settingsRepo.getActiveVendorTagsForLens(lensId),
        registry = registry
    )

    /**
     * Applies an immutable settings snapshot without performing DataStore IO. Camera2 session
     * construction uses this path so the camera callback thread never blocks on suspend settings.
     */
    fun applyPreparedTags(
        lensId: String,
        builder: CaptureRequest.Builder,
        stage: VendorRequestStage,
        activeTags: List<VendorTagConfig>,
        registry: List<DynamicVendorTag> = emptyList()
    ): List<VendorInjectionAttempt> {
        val stageTags = activeTags.filter { it.shouldApplyForStage(stage) }
        val registryByName = registry.associateBy { it.name }
        val attempts = stageTags.map { config ->
            applyOne(
                lensId = lensId,
                builder = builder,
                stage = stage,
                config = config,
                descriptor = registryByName[config.keyName]
            )
        }
        if (attempts.isNotEmpty()) {
            attemptsByLens.compute(lensId) { _, current ->
                val list = current ?: mutableListOf()
                list.addAll(attempts)
                list
            }
        }

        // This is only a shared builder hook. BLACK_LEVEL_LOCK is a standard Camera2 control and
        // remains owned by BlackLevelLockController, not by the vendor-tag registry. Apply it last
        // so an arbitrary vendor-tag entry can never silently override the standard authority.
        BlackLevelLockController.applyToBuilder(
            lensId,
            builder,
            BlackLevelLockRequestStage.valueOf(stage.name)
        )
        return attempts
    }

    fun consumeAttempts(lensId: String): List<VendorInjectionAttempt> =
        attemptsByLens.remove(lensId)?.toList().orEmpty()

    fun peekAttempts(lensId: String): List<VendorInjectionAttempt> =
        attemptsByLens[lensId]?.toList().orEmpty()

    fun clearAttempts(lensId: String) {
        attemptsByLens.remove(lensId)
    }

    fun clearAllAttempts() {
        attemptsByLens.clear()
    }

    private fun applyOne(
        lensId: String,
        builder: CaptureRequest.Builder,
        stage: VendorRequestStage,
        config: VendorTagConfig,
        descriptor: DynamicVendorTag?
    ): VendorInjectionAttempt {
        if (!config.enabled) {
            return skipped(
                lensId,
                config,
                stage,
                VendorInjectionStatus.SKIPPED_DISABLED,
                "Vendor tag is disabled."
            )
        }
        if (!config.shouldApplyForStage(stage)) {
            return skipped(
                lensId,
                config,
                stage,
                VendorInjectionStatus.SKIPPED_STAGE_MISMATCH,
                "target=${config.target}, stage=$stage"
            )
        }
        if (config.target == VendorTagTarget.RESULT_ONLY) {
            return skipped(
                lensId,
                config,
                stage,
                VendorInjectionStatus.SKIPPED_RESULT_ONLY,
                "Result-only key cannot be injected into CaptureRequest.Builder."
            )
        }
        if (config.target == VendorTagTarget.CHARACTERISTIC_ONLY) {
            return skipped(
                lensId,
                config,
                stage,
                VendorInjectionStatus.SKIPPED_CHARACTERISTIC_ONLY,
                "Characteristic-only key cannot be injected into CaptureRequest.Builder."
            )
        }
        if (descriptor != null && !descriptor.injectable) {
            return skipped(
                lensId,
                config,
                stage,
                VendorInjectionStatus.SKIPPED_NOT_INJECTABLE,
                descriptor.notes.ifBlank { "Registry marks this key as not injectable." }
            )
        }

        val valueType = normalizeType(
            raw = config.type,
            fallback = descriptor?.typeName,
            rawValue = config.value
        )
        val parsedValue = try {
            parseValue(config.value, valueType)
        } catch (e: Exception) {
            return VendorInjectionAttempt(
                lensId = lensId,
                keyName = config.keyName,
                requestedValue = config.value,
                parsedValuePreview = "",
                valueType = valueType,
                target = config.target,
                attempted = false,
                appliedToBuilder = false,
                builderStage = stage,
                parseError = e.message ?: e.javaClass.simpleName,
                finalStatus = VendorInjectionStatus.PARSE_FAILED,
                source = config.source.name,
                notes = config.notes
            )
        }

        val applyResult = tryApplyToBuilder(
            builder = builder,
            keyName = config.keyName,
            valueType = valueType,
            parsedValue = parsedValue
        )
        return if (applyResult.success) {
            VendorInjectionAttempt(
                lensId = lensId,
                keyName = config.keyName,
                requestedValue = config.value,
                parsedValuePreview = previewValue(parsedValue),
                valueType = valueType,
                target = config.target,
                attempted = true,
                appliedToBuilder = true,
                builderStage = stage,
                finalStatus = VendorInjectionStatus.APPLIED,
                source = config.source.name,
                notes = config.notes
            )
        } else {
            Log.w(TAG, "Apply failed for ${config.keyName}: ${applyResult.error}")
            VendorInjectionAttempt(
                lensId = lensId,
                keyName = config.keyName,
                requestedValue = config.value,
                parsedValuePreview = previewValue(parsedValue),
                valueType = valueType,
                target = config.target,
                attempted = true,
                appliedToBuilder = false,
                builderStage = stage,
                applyError = applyResult.error,
                finalStatus = VendorInjectionStatus.APPLY_FAILED,
                source = config.source.name,
                notes = config.notes
            )
        }
    }

    private data class ApplyResult(
        val success: Boolean,
        val error: String = ""
    )

    private fun tryApplyToBuilder(
        builder: CaptureRequest.Builder,
        keyName: String,
        valueType: VendorValueType,
        parsedValue: Any
    ): ApplyResult {
        val errors = mutableListOf<String>()
        val candidateClasses = requestKeyClassesFor(valueType, parsedValue)
        candidateClasses.forEach { typeClass ->
            try {
                val key = createRequestKey(keyName, typeClass) as CaptureRequest.Key<Any>
                builder.set(key, parsedValue)
                return ApplyResult(success = true)
            } catch (e: Exception) {
                errors.add("${typeClass.name}: ${e.message ?: e.javaClass.simpleName}")
            }
        }
        return ApplyResult(
            success = false,
            error = errors.joinToString(" | ").ifBlank { "No key class candidate worked." }
        )
    }

    private fun skipped(
        lensId: String,
        config: VendorTagConfig,
        stage: VendorRequestStage,
        status: VendorInjectionStatus,
        reason: String
    ): VendorInjectionAttempt = VendorInjectionAttempt(
        lensId = lensId,
        keyName = config.keyName,
        requestedValue = config.value,
        parsedValuePreview = "",
        valueType = normalizeType(config.type, null, config.value),
        target = config.target,
        attempted = false,
        appliedToBuilder = false,
        builderStage = stage,
        applyError = reason,
        finalStatus = status,
        source = config.source.name,
        notes = config.notes
    )

    private fun VendorTagConfig.shouldApplyForStage(stage: VendorRequestStage): Boolean =
        when (target) {
            VendorTagTarget.SESSION -> stage == VendorRequestStage.SESSION
            VendorTagTarget.REPEATING_REQUEST -> stage == VendorRequestStage.REPEATING_REQUEST
            VendorTagTarget.STILL_CAPTURE -> stage == VendorRequestStage.STILL_CAPTURE
            VendorTagTarget.REQUEST_BOTH ->
                stage == VendorRequestStage.REPEATING_REQUEST || stage == VendorRequestStage.STILL_CAPTURE
            VendorTagTarget.RESULT_ONLY,
            VendorTagTarget.CHARACTERISTIC_ONLY,
            VendorTagTarget.UNKNOWN -> false
        }

    private fun createRequestKey(name: String, typeClass: Class<*>): CaptureRequest.Key<*> {
        val constructor = runCatching {
            CaptureRequest.Key::class.java.getConstructor(String::class.java, Class::class.java)
        }.getOrElse {
            CaptureRequest.Key::class.java.getDeclaredConstructor(String::class.java, Class::class.java)
        }
        constructor.isAccessible = true
        return constructor.newInstance(name, typeClass) as CaptureRequest.Key<*>
    }

    private fun normalizeType(raw: String, fallback: String?, rawValue: String): VendorValueType {
        val fromRaw = normalizeTypeName(raw)
        if (fromRaw != VendorValueType.UNKNOWN) return fromRaw
        val fromFallback = if (!fallback.isNullOrBlank() && !fallback.equals(raw, ignoreCase = true)) {
            normalizeTypeName(fallback)
        } else {
            VendorValueType.UNKNOWN
        }
        if (fromFallback != VendorValueType.UNKNOWN) return fromFallback
        return inferTypeFromValue(rawValue)
    }

    private fun normalizeTypeName(raw: String): VendorValueType {
        val compact = raw.trim().replace("-", "_").replace(" ", "_").lowercase()
        return when (compact) {
            "byte", "byte_(u8)", "u8", "java.lang.byte" -> VendorValueType.BYTE
            "short", "int16", "i16", "java.lang.short" -> VendorValueType.SHORT
            "int", "integer", "int32", "int32_(i32)", "i32", "java.lang.integer" -> VendorValueType.INT
            "long", "int64", "i64", "java.lang.long" -> VendorValueType.LONG
            "float", "float_(f32)", "f32", "java.lang.float" -> VendorValueType.FLOAT
            "double", "float64", "f64", "java.lang.double" -> VendorValueType.DOUBLE
            "boolean", "bool", "java.lang.boolean" -> VendorValueType.BOOLEAN
            "string", "java.lang.string" -> VendorValueType.STRING
            "byte_array", "byte[]", "bytearray", "byte_array_(byte[])" -> VendorValueType.BYTE_ARRAY
            "short_array", "short[]", "shortarray" -> VendorValueType.SHORT_ARRAY
            "int_array", "integer_array", "int[]", "integer[]", "intarray" -> VendorValueType.INT_ARRAY
            "long_array", "long[]", "longarray" -> VendorValueType.LONG_ARRAY
            "float_array", "float[]", "floatarray", "float_array_(float[])" -> VendorValueType.FLOAT_ARRAY
            "double_array", "double[]", "doublearray" -> VendorValueType.DOUBLE_ARRAY
            "boolean_array", "bool_array", "boolean[]", "bool[]", "booleanarray" -> VendorValueType.BOOLEAN_ARRAY
            else -> VendorValueType.UNKNOWN
        }
    }

    private fun inferTypeFromValue(raw: String): VendorValueType {
        val cleaned = raw.trim().removePrefix("[").removeSuffix("]")
        if (cleaned.isBlank()) return VendorValueType.STRING
        val tokens = splitValues(raw)
        if (tokens.size > 1) {
            return when {
                tokens.all { it.isBooleanLiteral() } -> VendorValueType.BOOLEAN_ARRAY
                tokens.all { it.toIntOrNull() != null } -> VendorValueType.INT_ARRAY
                tokens.all { it.toLongOrNull() != null } -> VendorValueType.LONG_ARRAY
                tokens.all { it.toFloatOrNull() != null } -> VendorValueType.FLOAT_ARRAY
                tokens.all { it.toDoubleOrNull() != null } -> VendorValueType.DOUBLE_ARRAY
                else -> VendorValueType.STRING
            }
        }
        val single = tokens.firstOrNull() ?: cleaned
        return when {
            single.isBooleanLiteral() -> VendorValueType.BOOLEAN
            single.toIntOrNull() != null -> VendorValueType.INT
            single.toLongOrNull() != null -> VendorValueType.LONG
            single.toFloatOrNull() != null -> VendorValueType.FLOAT
            single.toDoubleOrNull() != null -> VendorValueType.DOUBLE
            else -> VendorValueType.STRING
        }
    }

    private fun parseValue(raw: String, type: VendorValueType): Any {
        val trimmed = raw.trim()
        return when (type) {
            VendorValueType.BYTE -> trimmed.toByte()
            VendorValueType.SHORT -> trimmed.toShort()
            VendorValueType.INT -> trimmed.toInt()
            VendorValueType.LONG -> trimmed.toLong()
            VendorValueType.FLOAT -> trimmed.toFloat()
            VendorValueType.DOUBLE -> trimmed.toDouble()
            VendorValueType.BOOLEAN -> parseBoolean(trimmed)
            VendorValueType.STRING -> raw
            VendorValueType.BYTE_ARRAY -> splitValues(raw).map { it.toByte() }.toByteArray()
            VendorValueType.SHORT_ARRAY -> splitValues(raw).map { it.toShort() }.toShortArray()
            VendorValueType.INT_ARRAY -> splitValues(raw).map { it.toInt() }.toIntArray()
            VendorValueType.LONG_ARRAY -> splitValues(raw).map { it.toLong() }.toLongArray()
            VendorValueType.FLOAT_ARRAY -> splitValues(raw).map { it.toFloat() }.toFloatArray()
            VendorValueType.DOUBLE_ARRAY -> splitValues(raw).map { it.toDouble() }.toDoubleArray()
            VendorValueType.BOOLEAN_ARRAY -> splitValues(raw).map { parseBoolean(it) }.toBooleanArray()
            VendorValueType.UNKNOWN -> throw IllegalArgumentException("Unsupported or unknown vendor value type.")
        }
    }

    private fun requestKeyClassesFor(type: VendorValueType, parsedValue: Any): List<Class<*>> {
        val classes = mutableListOf<Class<*>>()
        classes.add(parsedValue.javaClass)
        when (type) {
            VendorValueType.BYTE -> {
                classes.add(Byte::class.javaObjectType)
                Byte::class.javaPrimitiveType?.let { classes.add(it) }
            }
            VendorValueType.SHORT -> {
                classes.add(Short::class.javaObjectType)
                Short::class.javaPrimitiveType?.let { classes.add(it) }
            }
            VendorValueType.INT -> {
                classes.add(Int::class.javaObjectType)
                Int::class.javaPrimitiveType?.let { classes.add(it) }
            }
            VendorValueType.LONG -> {
                classes.add(Long::class.javaObjectType)
                Long::class.javaPrimitiveType?.let { classes.add(it) }
            }
            VendorValueType.FLOAT -> {
                classes.add(Float::class.javaObjectType)
                Float::class.javaPrimitiveType?.let { classes.add(it) }
            }
            VendorValueType.DOUBLE -> {
                classes.add(Double::class.javaObjectType)
                Double::class.javaPrimitiveType?.let { classes.add(it) }
            }
            VendorValueType.BOOLEAN -> {
                classes.add(Boolean::class.javaObjectType)
                Boolean::class.javaPrimitiveType?.let { classes.add(it) }
            }
            VendorValueType.STRING -> classes.add(String::class.java)
            VendorValueType.BYTE_ARRAY -> classes.add(ByteArray::class.java)
            VendorValueType.SHORT_ARRAY -> classes.add(ShortArray::class.java)
            VendorValueType.INT_ARRAY -> classes.add(IntArray::class.java)
            VendorValueType.LONG_ARRAY -> classes.add(LongArray::class.java)
            VendorValueType.FLOAT_ARRAY -> classes.add(FloatArray::class.java)
            VendorValueType.DOUBLE_ARRAY -> classes.add(DoubleArray::class.java)
            VendorValueType.BOOLEAN_ARRAY -> classes.add(BooleanArray::class.java)
            VendorValueType.UNKNOWN -> classes.add(parsedValue.javaClass)
        }
        return classes.distinct()
    }

    private fun parseBoolean(value: String): Boolean =
        value.equals("true", ignoreCase = true) ||
            value == "1" ||
            value.equals("yes", ignoreCase = true) ||
            value.equals("on", ignoreCase = true)

    private fun String.isBooleanLiteral(): Boolean =
        equals("true", ignoreCase = true) ||
            equals("false", ignoreCase = true) ||
            this == "1" ||
            this == "0" ||
            equals("yes", ignoreCase = true) ||
            equals("no", ignoreCase = true) ||
            equals("on", ignoreCase = true) ||
            equals("off", ignoreCase = true)

    private fun splitValues(raw: String): List<String> = raw
        .trim()
        .removePrefix("[")
        .removeSuffix("]")
        .split(',', ';', ' ')
        .map { it.trim() }
        .filter { it.isNotEmpty() }

    fun previewValue(value: Any?): String = when (value) {
        null -> "null"
        is ByteArray -> value.take(20).joinToString(
            prefix = "[",
            postfix = if (value.size > 20) ", ...] (${value.size})" else "]"
        )
        is ShortArray -> value.take(20).joinToString(
            prefix = "[",
            postfix = if (value.size > 20) ", ...] (${value.size})" else "]"
        )
        is IntArray -> value.take(20).joinToString(
            prefix = "[",
            postfix = if (value.size > 20) ", ...] (${value.size})" else "]"
        )
        is LongArray -> value.take(20).joinToString(
            prefix = "[",
            postfix = if (value.size > 20) ", ...] (${value.size})" else "]"
        )
        is FloatArray -> value.take(20).joinToString(
            prefix = "[",
            postfix = if (value.size > 20) ", ...] (${value.size})" else "]"
        )
        is DoubleArray -> value.take(20).joinToString(
            prefix = "[",
            postfix = if (value.size > 20) ", ...] (${value.size})" else "]"
        )
        is BooleanArray -> value.take(20).joinToString(
            prefix = "[",
            postfix = if (value.size > 20) ", ...] (${value.size})" else "]"
        )
        else -> value.toString().take(240)
    }
}
