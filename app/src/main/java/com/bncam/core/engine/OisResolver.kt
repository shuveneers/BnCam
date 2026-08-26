package com.bncam.core.engine

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.os.Build
import android.util.Log

class OisDecision(
    val logicalCameraId: String,
    val physicalCameraId: String?,
    val appliedMethod: OisMethod,
    val appliedValue: Int,
    val applied: Boolean,
    val reason: String,
    val vendorOpticalKeyName: String? = null,
    val requiresRuntimeResultValidation: Boolean = false
) {
    enum class OisMethod {
        LOGICAL_OIS,
        PHYSICAL_OIS,
        VIDEO_STAB,
        PREVIEW_STAB,
        VENDOR,
        FAILED
    }
}

object OisResolver {
    private const val TAG = "OisResolver"

    // The user-facing setting is Optical Stabilization. Never silently substitute EIS/video
    // stabilization: those modes can crop the field of view and change framing.
    fun isApprovedOpticalVendorKey(name: String): Boolean =
        OisVendorKeyPolicy.isOpticalOnlyKey(name)

    fun getCaptureRequestKeyType(key: CaptureRequest.Key<*>): Class<*>? {
        return try {
            key.javaClass.getMethod("getType").invoke(key) as? Class<*>
        } catch (e: Exception) {
            null
        }
    }

    fun resolve(
        cameraManager: CameraManager,
        activeLensId: String,
        userRequestedOis: Boolean,
        lensRole: String? = null,
        openedCameraId: String? = null,
        verifiedPhysicalCameraId: String? = null,
        directRouteWasRuntimeProbed: Boolean = false
    ): OisDecision {
        var logicalId = activeLensId
        var physicalId: String? = null

        // 1. Prefer the pipeline's already-verified CameraDevice/output route. Re-discovering the
        // logical parent from capability metadata here can disagree with the session that is actually
        // running on OEMs with hidden/under-reported physical cameras.
        val pipelinePhysicalId = verifiedPhysicalCameraId
            ?.takeIf { it.isNotBlank() && it != openedCameraId }
        if (pipelinePhysicalId != null && !openedCameraId.isNullOrBlank()) {
            logicalId = openedCameraId
            physicalId = pipelinePhysicalId
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && openedCameraId != activeLensId) {
            try {
                val preferredOwner = openedCameraId
                val candidateIds = buildList {
                    if (!preferredOwner.isNullOrBlank()) add(preferredOwner)
                    addAll(cameraManager.cameraIdList.filter { it != preferredOwner })
                }
                for (id in candidateIds) {
                    val chars = cameraManager.getCameraCharacteristics(id)
                    val caps = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
                    if (caps?.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA) == true &&
                        chars.physicalCameraIds.contains(activeLensId)
                    ) {
                        logicalId = id
                        physicalId = activeLensId
                        break
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error looking up logical parent for lens $activeLensId", e)
            }
        }
        val directDeviceRoute = physicalId == null && openedCameraId == activeLensId

        val targetId = physicalId ?: logicalId

        // 2. Dump all camera IDs and physical IDs
        try {
            val allIds = cameraManager.cameraIdList.toList()
            val allPhysicalMap = mutableMapOf<String, List<String>>()
            for (id in allIds) {
                val c = cameraManager.getCameraCharacteristics(id)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    allPhysicalMap[id] = c.physicalCameraIds.toList()
                }
            }
            Log.d(TAG, "ALL_CAMERA_IDS ids=$allIds physicalIds=$allPhysicalMap")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to dump all camera IDs", e)
        }

        // 3. Extract characteristics
        val logicalChars = try {
            cameraManager.getCameraCharacteristics(logicalId)
        } catch (e: Exception) {
            null
        }
        val targetChars = try {
            cameraManager.getCameraCharacteristics(targetId)
        } catch (e: Exception) {
            null
        }

        // Available standard OIS modes
        val logicalOis = logicalChars?.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)
        val targetOis = targetChars?.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)

        // Available video stabilization modes
        val logicalVideoStab = logicalChars?.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES)
        val targetVideoStab = targetChars?.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES)

        // Find preview stabilization support
        val targetSupportsPreviewStab = targetVideoStab?.contains(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_PREVIEW_STABILIZATION) == true
        val previewStabStr = if (targetSupportsPreviewStab) "2" else "none"

        // Request keys search
        val requestKeys = targetChars?.availableCaptureRequestKeys ?: emptyList()
        val oisKeys = requestKeys.filter { key ->
            val name = key.name.lowercase()
            name.contains("stabilization") || name.contains("ois") || name.contains("optical") ||
                    name.contains("eis") || name.contains("eisv") || name.contains("anti-shake") ||
                    name.contains("steadiness")
        }

        val standardOisStr = targetOis?.joinToString(",") ?: "null"
        val videoStabStr = targetVideoStab?.joinToString(",") ?: "null"

        val physicalKeysList = oisKeys.filter { it.name.startsWith("android.") }.map {
            val type = getCaptureRequestKeyType(it)?.simpleName ?: "Unknown"
            "${it.name}($type)"
        }
        val physicalKeysStr = if (physicalKeysList.isEmpty()) "none" else physicalKeysList.joinToString(",")

        val vendorStabKeysList = oisKeys.filter { !it.name.startsWith("android.") }.map {
            val type = getCaptureRequestKeyType(it)?.simpleName ?: "Unknown"
            "${it.name}($type)"
        }
        val vendorStabKeysStr = if (vendorStabKeysList.isEmpty()) "none" else vendorStabKeysList.joinToString(",")

        // 4. Log OIS_ROUTE_PROBE
        val role = lensRole ?: "Unknown"
        Log.i(
            TAG,
            "OIS_ROUTE_PROBE lens=$role selected=$activeLensId opened=${openedCameraId ?: "null"} " +
                "logical=$logicalId physical=${physicalId ?: "null"} direct=$directDeviceRoute " +
                "standardOis=$standardOisStr videoStab=$videoStabStr previewStab=$previewStabStr " +
                "physicalKeys=$physicalKeysStr vendorStabKeys=$vendorStabKeysStr"
        )

        // 5. Resolve OIS application plan
        var appliedMethod = OisDecision.OisMethod.FAILED
        var appliedValue = CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF
        var applied = false
        var reason = ""
        var vendorOpticalKeyName: String? = null
        var requiresRuntimeResultValidation = false

        if (!userRequestedOis) {
            appliedMethod = OisDecision.OisMethod.FAILED
            appliedValue = CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF
            applied = false
            reason = "User requested stabilization disabled"
        } else {
            val selectedLensSupportsOis =
                targetOis?.contains(CameraCharacteristics.LENS_OPTICAL_STABILIZATION_MODE_ON) == true
            val logicalSupportsOis =
                logicalOis?.contains(CameraCharacteristics.LENS_OPTICAL_STABILIZATION_MODE_ON) == true
            val logicalStandardOisWritable =
                logicalChars?.availableCaptureRequestKeys
                    ?.contains(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE) == true
            val openedCameraSupportsStandardOis = logicalSupportsOis && logicalStandardOisWritable
            val activeVendorKey = oisKeys.firstOrNull { key ->
                isApprovedOpticalVendorKey(key.name) &&
                    getCaptureRequestKeyType(key).let { type ->
                        type == Byte::class.java || type == Byte::class.javaObjectType ||
                            type == Int::class.java || type == Int::class.javaObjectType ||
                            type == Boolean::class.java || type == Boolean::class.javaObjectType ||
                            type == Long::class.java || type == Long::class.javaObjectType
                    }
            }
            val route = OisRoutePolicy.resolve(
                userRequested = userRequestedOis,
                directDeviceRoute = directDeviceRoute,
                hiddenPhysicalRoute = physicalId != null,
                selectedLensSupportsOis = selectedLensSupportsOis,
                openedCameraSupportsStandardOis = openedCameraSupportsStandardOis,
                probedDirectStandardOisWritable = directRouteWasRuntimeProbed &&
                    directDeviceRoute && logicalStandardOisWritable,
                approvedVendorKeyAvailable = activeVendorKey != null
            )

            // Probing is deliberately optical-only. Preview/video stabilization are EIS-class
            // alternatives and are never valid fallbacks for an OIS setting because they crop FOV.
            when (route) {
                OisRoutePolicy.Route.DIRECT_STANDARD -> {
                    appliedMethod = OisDecision.OisMethod.LOGICAL_OIS
                    appliedValue = CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON
                    applied = true
                    reason = "Selected lens is directly opened and supports standard OIS"
                }
                OisRoutePolicy.Route.PROBED_DIRECT_STANDARD -> {
                    appliedMethod = OisDecision.OisMethod.LOGICAL_OIS
                    appliedValue = CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON
                    applied = true
                    requiresRuntimeResultValidation = true
                    reason = "Runtime-probed direct camera exposes the standard OIS request key; retain OIS only when CaptureResult confirms ON"
                }
                OisRoutePolicy.Route.HIDDEN_STANDARD -> {
                    appliedMethod = OisDecision.OisMethod.LOGICAL_OIS
                    appliedValue = CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON
                    applied = true
                    reason = when {
                        !selectedLensSupportsOis ->
                            "Hidden physical route: request standard OIS despite under-reported physical OIS capability; device GCam proves this HAL can report OIS ON with static has_ois=0"
                        openedCameraSupportsStandardOis ->
                            "Opened logical camera standard OIS controls the selected physical output"
                        else ->
                            "Selected hidden physical lens reports OIS; apply standard OIS on logical request despite under-reported logical OIS capability"
                    }
                }
                OisRoutePolicy.Route.HIDDEN_PHYSICAL_KEY -> {
                    appliedMethod = OisDecision.OisMethod.PHYSICAL_OIS
                    appliedValue = CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON
                    applied = true
                    reason = "Hidden physical lens uses standards-compliant per-physical OIS override"
                }
                OisRoutePolicy.Route.DIRECT_VENDOR_OPTICAL -> {
                    appliedMethod = OisDecision.OisMethod.VENDOR
                    appliedValue = CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON
                    applied = true
                    vendorOpticalKeyName = activeVendorKey?.name
                    reason = "Directly opened selected lens uses optical-only vendor OIS key: ${activeVendorKey?.name}"
                }
                OisRoutePolicy.Route.UNAVAILABLE -> {
                    appliedMethod = OisDecision.OisMethod.FAILED
                    appliedValue = CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF
                    applied = false
                    reason = when {
                        physicalId != null && !selectedLensSupportsOis ->
                            "Selected physical lens does not advertise optical stabilization"
                        directDeviceRoute && selectedLensSupportsOis && !openedCameraSupportsStandardOis ->
                            "Selected direct lens reports OIS but the opened camera does not expose a writable standard OIS route"
                        targetSupportsPreviewStab ||
                            targetVideoStab?.contains(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON) == true ->
                            "No writable optical OIS route; digital preview/video stabilization intentionally not substituted"
                        else -> "No optical OIS support found for this selected lens"
                    }
                }
            }
        }

        // Log OIS_APPLY_PLAN
        val methodStr = appliedMethod.name
        Log.i(
            TAG,
            "OIS_APPLY_PLAN lens=$role logical=$logicalId physical=${physicalId ?: "null"} method=$methodStr applied=$applied reason=$reason"
        )

        return OisDecision(
            logicalCameraId = logicalId,
            physicalCameraId = physicalId,
            appliedMethod = appliedMethod,
            appliedValue = appliedValue,
            applied = applied,
            reason = reason,
            vendorOpticalKeyName = vendorOpticalKeyName,
            requiresRuntimeResultValidation = requiresRuntimeResultValidation
        )
    }

    fun isOisOrStabilizationSupported(cameraManager: CameraManager, lensId: String): Boolean {
        try {
            var logicalId = lensId
            var physicalId: String? = null
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                for (id in cameraManager.cameraIdList) {
                    val chars = cameraManager.getCameraCharacteristics(id)
                    val caps = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
                    if (caps?.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA) == true) {
                        if (chars.physicalCameraIds.contains(lensId)) {
                            logicalId = id
                            physicalId = lensId
                            break
                        }
                    }
                }
            }

            val targetId = physicalId ?: logicalId
            val chars = cameraManager.getCameraCharacteristics(targetId)

            val ois = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)
            val videoStab = chars.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES)
            val requestKeys = chars.availableCaptureRequestKeys ?: emptyList()
            val hasStabKeys = requestKeys.any { key ->
                val name = key.name.lowercase()
                name.contains("stabilization") || name.contains("ois") || name.contains("optical") ||
                        name.contains("eis") || name.contains("eisv") || name.contains("anti-shake") ||
                        name.contains("steadiness")
            }

            val supportsOis = ois != null && ois.isNotEmpty() && !(ois.size == 1 && ois[0] == CameraCharacteristics.LENS_OPTICAL_STABILIZATION_MODE_OFF)
            val supportsVideoStab = videoStab != null && videoStab.isNotEmpty() && !(videoStab.size == 1 && videoStab[0] == CameraCharacteristics.CONTROL_VIDEO_STABILIZATION_MODE_OFF)

            return supportsOis || supportsVideoStab || hasStabKeys || true // Always return true so OIS toggle is not locked globally
        } catch (e: Exception) {
            return true
        }
    }
}
