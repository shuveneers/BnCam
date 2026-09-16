package com.bncam.data.settings

import com.bncam.core.quality.NoiseModelCfaPattern
import com.bncam.core.quality.NoiseModelResolver
import com.bncam.core.quality.NoiseModelSource
import com.bncam.core.quality.ResolvedNoiseModel

/** One shutter-time production resolution, including any safe fallback that was required. */
data class PhysicalNoiseModelCaptureResolution(
    val requestedSource: NoiseModelSource,
    val resolved: ResolvedNoiseModel?,
    val requestedPresetId: String? = null,
    val resolvedPresetName: String? = null,
    val fallbackReason: String? = null,
    val settingsReady: Boolean = true
) {
    val effectiveSource: NoiseModelSource? get() = resolved?.source
    val available: Boolean get() = resolved != null
    val usedFallback: Boolean get() = fallbackReason != null
}

/**
 * Pure source-selection policy used by the runtime bridge and JVM tests.
 *
 * Missing/invalid SYSTEM, MANUAL or PRESET data never fabricates coefficients. It falls back to
 * the exact per-frame OEM model when that model is valid. If OEM is unavailable too, physical
 * noise authority is explicitly unavailable for the frame.
 */
object PhysicalNoiseModelCaptureResolver {
    fun resolve(
        lensId: String,
        captureIso: Int,
        cfaPattern: NoiseModelCfaPattern,
        cameraS: DoubleArray,
        cameraO: DoubleArray,
        settings: LensPhysicalNoiseModelSettings,
        userPresets: List<NoiseModelPreset>
    ): PhysicalNoiseModelCaptureResolution {
        val safeSettings = settings.sanitized()
        val requestedSource = safeSettings.source

        fun resolveOem(): ResolvedNoiseModel? = runCatching {
            require(captureIso > 0) { "capture ISO unavailable" }
            require(cameraS.size >= 4 && cameraO.size >= 4) { "OEM S/O requires four channels" }
            val mosaic = canonicalToMosaicSo(cameraS, cameraO, cfaPattern)
            NoiseModelResolver.resolve(
                NoiseModelResolver.Request.Oem(
                    lensId = lensId,
                    captureIso = captureIso,
                    cfaPattern = cfaPattern,
                    sensorNoiseProfileMosaicSo = mosaic,
                    provenance = "Camera2 SENSOR_NOISE_PROFILE"
                )
            )
        }.getOrNull()

        fun resolveParametric(
            source: NoiseModelSource,
            model: PersistedParametricNoiseModel,
            provenance: String
        ): ResolvedNoiseModel? = runCatching {
            require(captureIso > 0) { "capture ISO unavailable" }
            NoiseModelResolver.resolve(
                NoiseModelResolver.Request.Parametric(
                    lensId = lensId,
                    captureIso = captureIso,
                    cfaPattern = cfaPattern,
                    source = source,
                    model = model.toCoreModel(),
                    dynamicIsoEnabled = safeSettings.dynamicIsoEnabled,
                    dynamicIsoCoefficient = safeSettings.dynamicIsoCoefficient,
                    provenance = provenance
                )
            )
        }.getOrNull()

        if (requestedSource == NoiseModelSource.OEM) {
            val oem = resolveOem()
            return PhysicalNoiseModelCaptureResolution(
                requestedSource = requestedSource,
                resolved = oem,
                fallbackReason = if (oem == null) "OEM_SENSOR_NOISE_PROFILE_UNAVAILABLE_OR_INVALID" else null
            )
        }

        val requested: ResolvedNoiseModel? = when (requestedSource) {
            NoiseModelSource.SYSTEM -> safeSettings.systemModel?.let { model ->
                resolveParametric(
                    source = NoiseModelSource.SYSTEM,
                    model = model,
                    provenance = safeSettings.systemModelOrigin
                        ?.takeIf { it.isNotBlank() }
                        ?: "BnCam per-lens System A/B/C/D model"
                )
            }
            NoiseModelSource.MANUAL -> safeSettings.manualModel?.let { model ->
                resolveParametric(
                    source = NoiseModelSource.MANUAL,
                    model = model,
                    provenance = "BnCam Manual A/B/C/D model"
                )
            }
            NoiseModelSource.PRESET -> {
                val preset = safeSettings.selectedPresetId?.let { id ->
                    NoiseModelPresetCatalog.resolve(id, userPresets)
                }
                preset?.let {
                    resolveParametric(
                        source = NoiseModelSource.PRESET,
                        model = it.model,
                        provenance = "${it.origin.name} preset ${it.displayName} [${it.id}]"
                    )
                }
            }
            NoiseModelSource.OEM -> null
        }

        if (requested != null) {
            val presetName = if (requestedSource == NoiseModelSource.PRESET) {
                safeSettings.selectedPresetId?.let { NoiseModelPresetCatalog.resolve(it, userPresets)?.displayName }
            } else null
            return PhysicalNoiseModelCaptureResolution(
                requestedSource = requestedSource,
                resolved = requested,
                requestedPresetId = safeSettings.selectedPresetId,
                resolvedPresetName = presetName
            )
        }

        val oemFallback = resolveOem()
        val reason = when (requestedSource) {
            NoiseModelSource.SYSTEM -> "SYSTEM_MODEL_UNAVAILABLE_OR_INVALID"
            NoiseModelSource.MANUAL -> "MANUAL_MODEL_UNAVAILABLE_OR_INVALID"
            NoiseModelSource.PRESET -> when {
                safeSettings.selectedPresetId.isNullOrBlank() -> "PRESET_NOT_SELECTED"
                NoiseModelPresetCatalog.resolve(safeSettings.selectedPresetId, userPresets) == null -> "SELECTED_PRESET_NOT_FOUND"
                else -> "SELECTED_PRESET_MODEL_INVALID"
            }
            NoiseModelSource.OEM -> "OEM_SENSOR_NOISE_PROFILE_UNAVAILABLE_OR_INVALID"
        }
        return PhysicalNoiseModelCaptureResolution(
            requestedSource = requestedSource,
            resolved = oemFallback,
            requestedPresetId = safeSettings.selectedPresetId,
            resolvedPresetName = safeSettings.selectedPresetId
                ?.let { NoiseModelPresetCatalog.resolve(it, userPresets)?.displayName },
            fallbackReason = if (oemFallback != null) "${reason}_FALLBACK_OEM" else "${reason}_AND_OEM_UNAVAILABLE"
        )
    }

    private fun canonicalToMosaicSo(
        cameraS: DoubleArray,
        cameraO: DoubleArray,
        cfaPattern: NoiseModelCfaPattern
    ): DoubleArray {
        val out = DoubleArray(8)
        cfaPattern.mosaicOrder.forEachIndexed { mosaicIndex, channel ->
            val canonicalIndex = channel.ordinal
            out[mosaicIndex * 2] = cameraS[canonicalIndex]
            out[mosaicIndex * 2 + 1] = cameraO[canonicalIndex]
        }
        return out
    }
}
