package com.bncam.core.debug

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.OutputConfiguration
import android.os.Build
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Diagnostic HAL Parity Auditor for telephoto stabilization investigation.
 *
 * Inspects, dumps, and compares Camera2 characteristics, session parameters,
 * request keys, logical results, physical camera results, and vendor tags.
 *
 * All output is routed through [DeviceTelemetryLogger] into `device_telemetry_trace.txt`
 * and logcat. Zero production pipeline state or controls are modified by this auditor.
 */
object HalParityAuditor {
    private const val TAG = "HalParityAudit"

    private val KEYWORDS = listOf(
        "ois", "stabil", "gyro", "motion", "sensor", "physical",
        "master", "multi", "slot", "mode", "preview", "fusion"
    )

    private val resultAuditTriggered = AtomicBoolean(false)
    private var lastResultFrameNumber: Long = -1L

    fun resetAuditState() {
        resultAuditTriggered.set(false)
        lastResultFrameNumber = -1L
    }

    private fun isRelevantKey(name: String): Boolean {
        val lower = name.lowercase()
        return KEYWORDS.any { lower.contains(it) }
    }

    fun formatValue(value: Any?): String {
        if (value == null) return "null"
        return when (value) {
            is ByteArray -> value.joinToString(prefix = "[", postfix = "]")
            is ShortArray -> value.joinToString(prefix = "[", postfix = "]")
            is IntArray -> value.joinToString(prefix = "[", postfix = "]")
            is LongArray -> value.joinToString(prefix = "[", postfix = "]")
            is FloatArray -> value.joinToString(prefix = "[", postfix = "]")
            is DoubleArray -> value.joinToString(prefix = "[", postfix = "]")
            is BooleanArray -> value.joinToString(prefix = "[", postfix = "]")
            is Array<*> -> value.joinToString(prefix = "[", postfix = "]") { formatValue(it) }
            else -> value.toString()
        }
    }

    private fun getRawKeyType(key: Any?): String {
        if (key == null) return "Unknown"
        return runCatching {
            val type = key.javaClass.getMethod("getType").invoke(key) as? Class<*>
            type?.simpleName ?: "Unknown"
        }.getOrDefault("Unknown")
    }

    /**
     * Audit Step 1 — Static Capability & Metadata Audit.
     * Evaluates CameraManager IDs, CameraCharacteristics, session keys, physical request keys.
     */
    fun auditStaticCapabilities(
        cameraManager: CameraManager,
        logicalCameraId: String,
        physicalCameraId: String?
    ) {
        runCatching {
            resetAuditState()
            val allDirectIds = cameraManager.cameraIdList.toList()
            val isPhysicalDirectlyOpenable = if (physicalCameraId != null) {
                allDirectIds.contains(physicalCameraId)
            } else {
                false
            }

            DeviceTelemetryLogger.logEvent(
                "HAL_AUDIT_CAMERA_IDS",
                "logical=$logicalCameraId physical=${physicalCameraId ?: "none"} " +
                    "directIds=$allDirectIds physicalInDirectList=$isPhysicalDirectlyOpenable " +
                    "standaloneOpenSupported=${if (physicalCameraId == null) true else isPhysicalDirectlyOpenable} " +
                    "note=${if (!isPhysicalDirectlyOpenable && physicalCameraId != null) "On Android 10+, physical ID $physicalCameraId is not in cameraIdList so it can ONLY be routed via logical parent $logicalCameraId" else "Direct open possible"}"
            )

            val logicalChars = cameraManager.getCameraCharacteristics(logicalCameraId)
            val physicalChars = if (physicalCameraId != null) {
                try { cameraManager.getCameraCharacteristics(physicalCameraId) } catch (e: Exception) { null }
            } else null

            // 1. Capture Key Sets
            val physicalCameraIds = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                logicalChars.physicalCameraIds.toList()
            } else emptyList()

            val availableReqKeys = logicalChars.availableCaptureRequestKeys?.map { it.name }.orEmpty()
            val availableResKeys = logicalChars.availableCaptureResultKeys?.map { it.name }.orEmpty()
            val availableSessionKeys = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                logicalChars.availableSessionKeys?.map { it.name }.orEmpty()
            } else emptyList()

            val availablePhysicalReqKeys = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                logicalChars.availablePhysicalCameraRequestKeys?.map { it.name }.orEmpty()
            } else emptyList()

            DeviceTelemetryLogger.logEvent(
                "HAL_AUDIT_KEY_SETS",
                "logical=$logicalCameraId physical=${physicalCameraId ?: "none"} " +
                    "physicalCameraIds=$physicalCameraIds " +
                    "totalReqKeys=${availableReqKeys.size} totalResKeys=${availableResKeys.size} " +
                    "totalSessionKeys=${availableSessionKeys.size} totalPhysicalReqKeys=${availablePhysicalReqKeys.size}"
            )

            // 2. Dump matching CameraCharacteristics keys for Logical Camera
            val matchingLogicalCharKeys = logicalChars.keys.filter { isRelevantKey(it.name) }
            val logicalCharDump = matchingLogicalCharKeys.joinToString("\n  ") { key ->
                val valStr = formatValue(runCatching { logicalChars.get(key) }.getOrNull())
                val isSession = availableSessionKeys.contains(key.name)
                val isPhysicalReq = availablePhysicalReqKeys.contains(key.name)
                "${key.name} (${getRawKeyType(key)}): charVal=$valStr sessionCapable=$isSession physicalReqCapable=$isPhysicalReq"
            }
            DeviceTelemetryLogger.logEvent(
                "HAL_AUDIT_LOGICAL_CHARACTERISTICS",
                "logical=$logicalCameraId count=${matchingLogicalCharKeys.size}\n  $logicalCharDump"
            )

            // 3. Dump matching CameraCharacteristics keys for Physical Camera (if available)
            if (physicalChars != null && physicalCameraId != null) {
                val matchingPhysicalCharKeys = physicalChars.keys.filter { isRelevantKey(it.name) }
                val physicalCharDump = matchingPhysicalCharKeys.joinToString("\n  ") { key ->
                    val valStr = formatValue(runCatching { physicalChars.get(key) }.getOrNull())
                    "${key.name} (${getRawKeyType(key)}): charVal=$valStr"
                }
                DeviceTelemetryLogger.logEvent(
                    "HAL_AUDIT_PHYSICAL_CHARACTERISTICS",
                    "physical=$physicalCameraId count=${matchingPhysicalCharKeys.size}\n  $physicalCharDump"
                )
            }
        }.onFailure { t ->
            Log.w(TAG, "Static capability audit failed", t)
        }
    }

    /**
     * Audit Step 2 — Session Configuration, Output Bindings, and Session Parameters.
     */
    fun auditSessionCreation(
        logicalCameraId: String,
        physicalCameraId: String?,
        vendorSessionType: Int,
        outputBindings: List<Pair<String, String?>>,
        sessionParameters: CaptureRequest?
    ) {
        runCatching {
            val bindingsStr = outputBindings.joinToString(", ") { (surfaceLabel, physId) ->
                "$surfaceLabel -> physicalId=${physId ?: "none (logical default)"}"
            }

            DeviceTelemetryLogger.logEvent(
                "HAL_AUDIT_SESSION_CONFIG",
                "logical=$logicalCameraId physical=${physicalCameraId ?: "none"} " +
                    "vendorSessionType=$vendorSessionType (0x${vendorSessionType.toString(16).uppercase()}) " +
                    "hasSessionParameters=${sessionParameters != null} outputs=[$bindingsStr]"
            )

            if (sessionParameters != null) {
                val paramKeys = sessionParameters.keys.filter { isRelevantKey(it.name) }
                val paramDump = paramKeys.joinToString("\n  ") { key ->
                    val valStr = formatValue(runCatching { sessionParameters.get(key as CaptureRequest.Key<Any>) }.getOrNull())
                    "${key.name} (${getRawKeyType(key)}): sessionParamVal=$valStr"
                }
                DeviceTelemetryLogger.logEvent(
                    "HAL_AUDIT_SESSION_PARAMETERS",
                    "logical=$logicalCameraId count=${paramKeys.size}\n  $paramDump"
                )
            } else {
                DeviceTelemetryLogger.logEvent(
                    "HAL_AUDIT_SESSION_PARAMETERS",
                    "logical=$logicalCameraId sessionParameters=NONE"
                )
            }
        }.onFailure { t ->
            Log.w(TAG, "Session configuration audit failed", t)
        }
    }

    /**
     * Audit Step 3 — Submitted Repeating Request Parameters.
     */
    fun auditRepeatingRequest(
        logicalCameraId: String,
        physicalCameraId: String?,
        request: CaptureRequest
    ) {
        runCatching {
            val reqKeys = request.keys.filter { isRelevantKey(it.name) }
            val reqDump = reqKeys.joinToString("\n  ") { key ->
                val valStr = formatValue(runCatching { request.get(key as CaptureRequest.Key<Any>) }.getOrNull())
                "${key.name} (${getRawKeyType(key)}): repeatingReqVal=$valStr"
            }
            DeviceTelemetryLogger.logEvent(
                "HAL_AUDIT_REPEATING_REQUEST",
                "logical=$logicalCameraId physical=${physicalCameraId ?: "none"} count=${reqKeys.size}\n  $reqDump"
            )
        }.onFailure { t ->
            Log.w(TAG, "Repeating request audit failed", t)
        }
    }

    /**
     * Audit Step 4 — CaptureResult and Physical Camera Results.
     */
    fun auditCaptureResult(
        logicalCameraId: String,
        physicalCameraId: String?,
        result: TotalCaptureResult
    ) {
        runCatching {
            val frameNo = result.frameNumber
            // Audit on first stable frame, then every 120 frames
            if (!resultAuditTriggered.compareAndSet(false, true) && (frameNo - lastResultFrameNumber) < 120) {
                return
            }
            lastResultFrameNumber = frameNo

            // Logical Result Keys
            val resKeys = result.keys.filter { isRelevantKey(it.name) }
            val logicalResDump = resKeys.joinToString("\n  ") { key ->
                val valStr = formatValue(runCatching { result.get(key as CaptureResult.Key<Any>) }.getOrNull())
                "${key.name} (${getRawKeyType(key)}): logicalResVal=$valStr"
            }

            DeviceTelemetryLogger.logEvent(
                "HAL_AUDIT_LOGICAL_RESULT",
                "frame=$frameNo logical=$logicalCameraId count=${resKeys.size}\n  $logicalResDump"
            )

            // Physical Result Keys
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && physicalCameraId != null) {
                val physicalResultsMap = result.physicalCameraResults
                val physicalResult = physicalResultsMap[physicalCameraId]

                if (physicalResult != null) {
                    val physKeys = physicalResult.keys.filter { isRelevantKey(it.name) }
                    val physResDump = physKeys.joinToString("\n  ") { key ->
                        val valStr = formatValue(runCatching { physicalResult.get(key as CaptureResult.Key<Any>) }.getOrNull())
                        "${key.name} (${getRawKeyType(key)}): physicalResVal=$valStr"
                    }
                    DeviceTelemetryLogger.logEvent(
                        "HAL_AUDIT_PHYSICAL_RESULT",
                        "frame=$frameNo physical=$physicalCameraId count=${physKeys.size}\n  $physResDump"
                    )
                } else {
                    DeviceTelemetryLogger.logEvent(
                        "HAL_AUDIT_PHYSICAL_RESULT",
                        "frame=$frameNo physical=$physicalCameraId result=NULL physicalMapKeys=${physicalResultsMap.keys}"
                    )
                }
            }
        }.onFailure { t ->
            Log.w(TAG, "CaptureResult audit failed", t)
        }
    }
}
