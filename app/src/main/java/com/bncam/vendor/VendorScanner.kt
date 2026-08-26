package com.bncam.vendor

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.util.Log
import com.bncam.data.settings.VendorTagSource
import com.bncam.data.settings.VendorTagTarget
import com.bncam.data.settings.VendorValueOrigin
import java.lang.reflect.Method
import android.os.Build

data class CameraFeature(
    val id: String,
    val displayName: String,
    val description: String,
    val searchPatterns: List<Regex>,
    val targetValue: Any,
    val requiredSensorMode: Int? = null
)

object FeatureRegistry {
    val ALL_FEATURES = listOf(
        CameraFeature(
            id = "isz_active",
            displayName = "In-Sensor Zoom (ISZ)",
            description = "Forceert hardware-matige sensor binning switches voor lossless zoom.",
            searchPatterns = listOf(Regex("(?i).*insensorzoom.*"), Regex("(?i).*isz.*mode.*")),
            targetValue = intArrayOf(1)
        ),
        CameraFeature(
            id = "dcg_active",
            displayName = "Dual Conversion Gain (DCG)",
            description = "Schakelt de sensor naar High-Gain modus voor extreem dynamisch bereik.",
            searchPatterns = listOf(Regex("(?i).*dcg.*mode.*"), Regex("(?i).*dual.*gain.*")),
            targetValue = intArrayOf(1)
        ),
        CameraFeature(
            id = "ideal_raw",
            displayName = "Pure Hardware RAW10",
            description = "Bypass de ISP voor een ongerepte RAW10 datastroom.",
            searchPatterns = listOf(Regex("(?i).*ideal.*raw.*"), Regex("(?i).*raw.*bypass.*")),
            targetValue = byteArrayOf(1)
        ),

        CameraFeature(
            id = "pro_mode_master",
            displayName = "Pro / Vendor Session Mode",
            description = "Forces vendor session operation mode 32772 when the sensor exposes a session operation key. Request-level pro-mode tags are kept on normal pro-mode values.",
            searchPatterns = listOf(
                Regex("(?i).*ReprocessableSessionModeTag.*"),
                Regex("(?i).*session.*operation.*mode.*"),
                Regex("(?i).*operation.*mode.*"),
                Regex("(?i).*professional.*mode.*"),
                Regex("(?i).*pro.*mode.*")
            ),
            targetValue = intArrayOf(0x8004)
        ),
        CameraFeature(
            id = "sensor_direct_master",
            displayName = "Direct Sensor Mode",
            description = "Forceert de HAL om directe sensor-toegang toe te staan.",
            searchPatterns = listOf(Regex("(?i).*sensor.*mode.*"), Regex("(?i).*direct.*sensor.*")),
            targetValue = intArrayOf(1)
        )

    )
    // Deze functie zoekt in de geregistreerde features naar de bijbehorende mode
    fun getRequiredModeForTag(tagName: String): Int? {
        return ALL_FEATURES.firstOrNull { feature ->
            feature.searchPatterns.any { it.containsMatchIn(tagName) }
        }?.requiredSensorMode
    }
}

data class DynamicVendorTag(
    val name: String,
    val typeName: String = "Unknown",
    val isRequestKey: Boolean,
    val isSessionKey: Boolean,
    val isResultKey: Boolean,
    val isCharacteristicKey: Boolean = false,
    val target: VendorTagTarget = VendorTagTarget.UNKNOWN,
    val source: VendorTagSource = VendorTagSource.UNKNOWN,
    val injectable: Boolean = false,
    val valueOrigin: VendorValueOrigin = VendorValueOrigin.EMPTY,
    val defaultValue: String = "",
    val confidence: Int = 0,
    val notes: String = ""
)

private fun vendorDefaultValueForType(typeName: String): String {
    val normalized = typeName.lowercase()

    return when {
        normalized == "byte" -> "1"
        normalized == "short" -> "1"
        normalized == "integer" || normalized == "int" -> "1"
        normalized == "long" -> "1"
        normalized == "float" -> "1.0"
        normalized == "double" -> "1.0"
        normalized == "boolean" -> "true"
        normalized == "string" -> ""
        normalized.contains("bytearray") || normalized.contains("byte[]") -> "1"
        normalized.contains("shortarray") || normalized.contains("short[]") -> "1"
        normalized.contains("intarray") || normalized.contains("integer[]") || normalized.contains("int[]") -> "1"
        normalized.contains("longarray") || normalized.contains("long[]") -> "1"
        normalized.contains("floatarray") || normalized.contains("float[]") -> "1.0"
        normalized.contains("doublearray") || normalized.contains("double[]") -> "1.0"
        normalized.contains("booleanarray") || normalized.contains("boolean[]") -> "true"
        else -> ""
    }
}

private data class VendorTagAccumulator(
    val name: String,
    var typeName: String = "Unknown",
    var isRequestKey: Boolean = false,
    var isSessionKey: Boolean = false,
    var isResultKey: Boolean = false,
    var isCharacteristicKey: Boolean = false,
    var source: VendorTagSource = VendorTagSource.UNKNOWN,
    var confidence: Int = 0,
    val notes: MutableSet<String> = linkedSetOf()
) {
    fun toDynamicVendorTag(): DynamicVendorTag {
        val target = when {
            isSessionKey -> VendorTagTarget.SESSION
            isRequestKey -> VendorTagTarget.REPEATING_REQUEST
            isResultKey -> VendorTagTarget.RESULT_ONLY
            isCharacteristicKey -> VendorTagTarget.CHARACTERISTIC_ONLY
            else -> VendorTagTarget.UNKNOWN
        }

        val injectable = target == VendorTagTarget.SESSION ||
                target == VendorTagTarget.REPEATING_REQUEST

        return DynamicVendorTag(
            name = name,
            typeName = typeName,
            isRequestKey = isRequestKey,
            isSessionKey = isSessionKey,
            isResultKey = isResultKey,
            isCharacteristicKey = isCharacteristicKey,
            target = target,
            source = source,
            injectable = injectable,
            valueOrigin = if (vendorDefaultValueForType(typeName).isBlank()) {
                VendorValueOrigin.EMPTY
            } else {
                VendorValueOrigin.AUTO
            },
            defaultValue = vendorDefaultValueForType(typeName),
            confidence = confidence,
            notes = notes.joinToString(" | ")
        )
    }
}

object VendorScanner {
    private const val TAG = "VendorScanner"

    private val sensorTagsCache = mutableMapOf<String, List<DynamicVendorTag>>()

    fun clearCache() {
        sensorTagsCache.clear()
    }

    fun scanSensor(
        context: Context,
        cameraManager: CameraManager,
        lensId: String,
        forceRefresh: Boolean = false
    ): List<DynamicVendorTag> {
        if (!forceRefresh) {
            sensorTagsCache[lensId]?.let { return it }
        }

        val registry = linkedMapOf<String, VendorTagAccumulator>()

        try {
            val characteristics = cameraManager.getCameraCharacteristics(lensId)

            collectCharacteristicKeys(registry, characteristics)
            collectRequestKeys(registry, characteristics)
            collectSessionKeys(registry, characteristics)
            collectResultKeys(registry, characteristics)

            // Vóór je oude best-effort reflection pass:
            collectDeepNativeKeys(registry, characteristics)
            collectReflectionKeys(registry, characteristics)

        } catch (e: Exception) {
            Log.e(TAG, "Vendor scan failed for lensId=$lensId", e)
        }

        val sorted = registry.values
            .map { it.toDynamicVendorTag() }
            .filter { it.name.isNotBlank() }
            .filter { !it.name.startsWith("android.") }
            .distinctBy { it.name }
            .sortedWith(
                compareByDescending<DynamicVendorTag> { it.injectable }
                    .thenByDescending { it.confidence }
                    .thenBy { it.name }
            )

        sensorTagsCache[lensId] = sorted

        Log.i(
            TAG,
            "Vendor device scan completed lensId=$lensId total=${sorted.size} " +
                    "request=${sorted.count { it.isRequestKey }} " +
                    "session=${sorted.count { it.isSessionKey }} " +
                    "result=${sorted.count { it.isResultKey }} " +
                    "characteristic=${sorted.count { it.isCharacteristicKey }} " +
                    "sources=${sorted.groupingBy { it.source }.eachCount()}"
        )

        return sorted
    }

    // Dit systeem probeert elke modus (0-50) om te zien welke tags hij ontgrendelt
    fun probeAvailableModes(context: Context, cameraManager: CameraManager, lensId: String): Map<String, Int> {
        val modeMap = mutableMapOf<String, Int>()
        val testModes = 0..50

        testModes.forEach { mode ->
            try {
                // In een echte implementatie stuur je hier de 'org.codeaurora.qcamera3.sensorMode' tag
                // En kijk je of de resultaten (de echo's) veranderen.
                // We slaan de resultaten per mode op.
                Log.d(TAG, "Probing Sensor Mode: $mode")
                // ... probeer sessie opbouw ...
            } catch (e: Exception) {
                Log.w(TAG, "Mode $mode not supported.")
            }
        }
        return modeMap
    }

    fun groupTagsAsRecipes(tags: List<DynamicVendorTag>): Map<String, List<DynamicVendorTag>> {
        return tags.groupBy { tag ->
            val parts = tag.name.split(".")
            if (parts.size > 2) parts.dropLast(1).joinToString(".") else "Misc"
        }.toSortedMap()
    }

    fun inferValueType(raw: Any?): String {
        return when (raw) {
            null -> "Unknown"
            is Byte -> "Byte"
            is Short -> "Short"
            is Int -> "Int"
            is Long -> "Long"
            is Float -> "Float"
            is Double -> "Double"
            is Boolean -> "Boolean"
            is String -> "String"
            is ByteArray -> "ByteArray"
            is ShortArray -> "ShortArray"
            is IntArray -> "IntArray"
            is LongArray -> "LongArray"
            is FloatArray -> "FloatArray"
            is DoubleArray -> "DoubleArray"
            is BooleanArray -> "BooleanArray"
            else -> raw.javaClass.simpleName ?: "Unknown"
        }
    }

    private fun cameraKeyTypeName(key: Any?): String {
        if (key == null) return "Unknown"

        return runCatching {
            val type = key.javaClass.getMethod("getType").invoke(key) as? Class<*>
            type?.simpleName ?: "Unknown"
        }.getOrDefault("Unknown")
    }

    private fun collectCharacteristicKeys(
        registry: MutableMap<String, VendorTagAccumulator>,
        characteristics: CameraCharacteristics
    ) {
        runCatching {
            characteristics.keys.forEach { key ->
                mergeKey(
                    registry = registry,
                    name = key.name,
                    typeName = cameraKeyTypeName(key),
                    source = VendorTagSource.DEVICE_SCAN,
                    confidence = 100,
                    note = "CameraCharacteristics.keys",
                    isCharacteristic = true
                )
            }
        }.onFailure {
            Log.w(TAG, "Could not read CameraCharacteristics.keys", it)
        }
    }

    private fun collectRequestKeys(
        registry: MutableMap<String, VendorTagAccumulator>,
        characteristics: CameraCharacteristics
    ) {
        runCatching {
            characteristics.availableCaptureRequestKeys.forEach { key: CaptureRequest.Key<*> ->
                mergeKey(
                    registry = registry,
                    name = key.name,
                    typeName = cameraKeyTypeName(key),
                    source = VendorTagSource.DEVICE_SCAN,
                    confidence = 100,
                    note = "availableCaptureRequestKeys",
                    isRequest = true
                )
            }
        }.onFailure {
            Log.w(TAG, "Could not read availableCaptureRequestKeys", it)
        }
    }

    private fun collectSessionKeys(
        registry: MutableMap<String, VendorTagAccumulator>,
        characteristics: CameraCharacteristics
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            Log.i(TAG, "availableSessionKeys skipped: requires API 28, current API=${Build.VERSION.SDK_INT}")
            return
        }

        runCatching {
            characteristics.availableSessionKeys.forEach { key: CaptureRequest.Key<*> ->
                mergeKey(
                    registry = registry,
                    name = key.name,
                    typeName = cameraKeyTypeName(key),
                    source = VendorTagSource.DEVICE_SCAN,
                    confidence = 100,
                    note = "availableSessionKeys",
                    isSession = true,
                    isRequest = true
                )
            }
        }.onFailure {
            Log.w(TAG, "Could not read availableSessionKeys", it)
        }
    }

    private fun collectResultKeys(
        registry: MutableMap<String, VendorTagAccumulator>,
        characteristics: CameraCharacteristics
    ) {
        runCatching {
            characteristics.availableCaptureResultKeys.forEach { key: CaptureResult.Key<*> ->
                mergeKey(
                    registry = registry,
                    name = key.name,
                    typeName = cameraKeyTypeName(key),
                    source = VendorTagSource.DEVICE_SCAN,
                    confidence = 100,
                    note = "availableCaptureResultKeys",
                    isResult = true
                )
            }
        }.onFailure {
            Log.w(TAG, "Could not read availableCaptureResultKeys", it)
        }
    }

    private fun collectReflectionKeys(
        registry: MutableMap<String, VendorTagAccumulator>,
        characteristics: CameraCharacteristics
    ) {
        val methods = characteristics.javaClass.methods.toList() +
                characteristics.javaClass.declaredMethods.toList()

        methods
            .distinctBy { it.name + it.parameterTypes.joinToString(",") }
            .filter { method ->
                method.parameterTypes.isEmpty() &&
                        method.name.contains("key", ignoreCase = true)
            }
            .forEach { method ->
                tryInvokeKeyMethod(registry, characteristics, method)
            }
    }

    private fun tryInvokeKeyMethod(
        registry: MutableMap<String, VendorTagAccumulator>,
        characteristics: CameraCharacteristics,
        method: Method
    ) {
        runCatching {
            method.isAccessible = true
            val result = method.invoke(characteristics)

            when (result) {
                is List<*> -> {
                    result.forEach { item ->
                        mergeReflectedKey(registry, item, method.name)
                    }
                }

                is Array<*> -> {
                    result.forEach { item ->
                        mergeReflectedKey(registry, item, method.name)
                    }
                }

                else -> Unit
            }
        }.onFailure {
            // Niet hard falen. Hidden/blocked methods mogen de scanner niet breken.
            Log.d(TAG, "Reflection key method skipped: ${method.name}")
        }
    }

    private fun mergeReflectedKey(
        registry: MutableMap<String, VendorTagAccumulator>,
        item: Any?,
        methodName: String
    ) {
        if (item == null) return

        val name = reflectedKeyName(item) ?: return
        if (!looksLikeVendorKey(name)) return

        val typeName = reflectedKeyTypeName(item)

        val className = item.javaClass.name.lowercase()
        val method = methodName.lowercase()

        val isRequest =
            className.contains("capturerequest") ||
                    method.contains("request")

        val isResult =
            className.contains("captureresult") ||
                    method.contains("result")

        val isSession =
            method.contains("session")

        val isCharacteristic =
            className.contains("cameracharacteristics") ||
                    method.contains("characteristic")

        mergeKey(
            registry = registry,
            name = name,
            typeName = typeName,
            source = VendorTagSource.REFLECTION_SCAN,
            confidence = 60,
            note = "reflection:$methodName",
            isRequest = isRequest,
            isSession = isSession,
            isResult = isResult,
            isCharacteristic = isCharacteristic
        )
    }

    private fun reflectedKeyName(item: Any): String? {
        return runCatching {
            item.javaClass.getMethod("getName").invoke(item) as? String
        }.getOrNull()
    }

    private fun reflectedKeyTypeName(item: Any): String {
        return runCatching {
            val type = item.javaClass.getMethod("getType").invoke(item) as? Class<*>
            type?.simpleName ?: "Unknown"
        }.getOrDefault("Unknown")
    }

    private fun mergeKey(
        registry: MutableMap<String, VendorTagAccumulator>,
        name: String,
        typeName: String,
        source: VendorTagSource,
        confidence: Int,
        note: String,
        isRequest: Boolean = false,
        isSession: Boolean = false,
        isResult: Boolean = false,
        isCharacteristic: Boolean = false
    ) {
        if (!looksLikeVendorKey(name)) return

        val existing = registry.getOrPut(name) {
            VendorTagAccumulator(name = name)
        }

        if (typeName.isNotBlank() && typeName != "Unknown") {
            existing.typeName = typeName
        }

        existing.isRequestKey = existing.isRequestKey || isRequest
        existing.isSessionKey = existing.isSessionKey || isSession
        existing.isResultKey = existing.isResultKey || isResult
        existing.isCharacteristicKey = existing.isCharacteristicKey || isCharacteristic

        if (confidence >= existing.confidence) {
            existing.confidence = confidence
            existing.source = source
        }

        existing.notes.add(note)
    }

    private fun looksLikeVendorKey(name: String): Boolean {
        if (name.isBlank()) return false
        if (!name.contains(".")) return false
        if (name.startsWith("android.")) return false
        return true
    }

    @android.annotation.SuppressLint("SoonBlockedPrivateApi", "DiscouragedPrivateApi")
    private fun collectDeepNativeKeys(
        registry: MutableMap<String, VendorTagAccumulator>,
        characteristics: CameraCharacteristics
    ) {
        try {
            // Voorkom een harde waarschuwing op Android 16+ (API 36).
            // We vertrouwen hier op je HiddenApiBypass implementatie.
            if (android.os.Build.VERSION.SDK_INT >= 36) {
                Log.w(TAG, "API 36+ gedetecteerd. Waarschuwing genegeerd omdat HiddenApiBypass dit afhandelt.")
            }

            // 1. Pak het onderliggende verborgen native object (CameraMetadataNative)
            val propertiesField = CameraCharacteristics::class.java.getDeclaredField("mProperties")
            propertiesField.isAccessible = true
            val nativeMetadata = propertiesField.get(characteristics) ?: return
            val nativeClass = nativeMetadata.javaClass

            // 2. Zoek de verborgen getAllVendorKeys() methode
            // Signature: public <TKey> ArrayList<TKey> getAllVendorKeys(Class<TKey> keyClass)
            val getVendorKeysMethod = try {
                nativeClass.getDeclaredMethod("getAllVendorKeys", Class::class.java).apply {
                    isAccessible = true
                }
            } catch (e: NoSuchMethodException) {
                Log.w(TAG, "getAllVendorKeys methode niet gevonden in native metadata.")
                return
            }

            // 3. Haal CaptureRequest vendor keys op (Voor je REPEATING_REQUEST / SESSION injecties)
            val requestKeys = getVendorKeysMethod.invoke(nativeMetadata, android.hardware.camera2.CaptureRequest.Key::class.java) as? Iterable<*>
            requestKeys?.forEach { key -> mergeReflectedKey(registry, key, "deepScan:Request") }

            // 4. Haal CaptureResult vendor keys op
            val resultKeys = getVendorKeysMethod.invoke(nativeMetadata, android.hardware.camera2.CaptureResult.Key::class.java) as? Iterable<*>
            resultKeys?.forEach { key -> mergeReflectedKey(registry, key, "deepScan:Result") }

            // 5. Haal CameraCharacteristics vendor keys op (Sensor info)
            val charKeys = getVendorKeysMethod.invoke(nativeMetadata, CameraCharacteristics.Key::class.java) as? Iterable<*>
            charKeys?.forEach { key -> mergeReflectedKey(registry, key, "deepScan:Characteristic") }

            Log.i(TAG, "Diepe native scan succesvol uitgevoerd!")

        } catch (e: Exception) {
            Log.w(TAG, "Diepe native vendor scan mislukt. Mogelijk door Android Hidden API blokkade.", e)
        }
    }
}

