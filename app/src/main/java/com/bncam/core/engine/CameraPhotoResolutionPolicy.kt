package com.bncam.core.engine

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Independent Photo-resolution request.
 *
 * GCam evidence recovered three distinct concepts around resolution. This request models only the
 * two stream-size mechanisms that affect PRIMARY_BUFFER candidate selection:
 *
 * 1. a specific RAW-size index from the active RAW format's Camera2 size list; and
 * 2. Resolution Fix, which uses another ImageFormat as a reference size list.
 *
 * The separate needFixResolution()/AeShotParams target-width/height path is intentionally not
 * merged into this model.
 */
data class CameraPhotoResolutionRequest(
    val specificRawSizeIndex: Int? = null,
    val resolutionFixReferenceFormatCode: Int? = null
)

enum class CameraPhotoResolutionSelectionKind {
    NATIVE_AUTO,
    SPECIFIC_RAW_SIZE,
    RESOLUTION_FIX_REMAP,
    UNAVAILABLE
}

data class ResolvedCameraPhotoResolution(
    val effectiveFormatCode: Int,
    val selectionKind: CameraPhotoResolutionSelectionKind,
    val specificRawSizeIndex: Int?,
    val resolutionFixReferenceFormatCode: Int?,
    val resolutionFixApplied: Boolean,
    val nativeCandidates: List<CameraCapabilitySize>,
    val fullFovCandidates: List<CameraCapabilitySize>,
    val policyCandidates: List<CameraCapabilitySize>,
    val selectedSize: CameraCapabilitySize?,
    val conservativeSize: CameraCapabilitySize?,
    val reason: String
) {
    val selectedExtent: CameraCapabilityExtent? get() = selectedSize?.extent

    fun allows(width: Int, height: Int): Boolean =
        policyCandidates.any { it.extent.width == width && it.extent.height == height }
}

/**
 * Resolution authority for the already-resolved PRIMARY_BUFFER format.
 *
 * The output format must be decided before entering this policy. This layer never changes format,
 * FPS, operation mode, physical routing or request targets.
 *
 * The normal Auto path preserves BnCam's existing behavior: prefer a supplied optimized YUV pool
 * only when it contains full-sensor-aspect sizes, otherwise use the standard Camera2 size list and
 * choose the largest full-FOV extent. RAW uses the standard Camera2 list directly.
 *
 * Resolution Fix is deliberately safe: the reference format can influence the candidate geometry,
 * but the resolved output is always one of the target format's own HAL-reported sizes. Exact
 * reference extents are preferred; otherwise the nearest target extent by aspect and area is used.
 * This is a BnCam containment rule around the recovered GCam reference-format mechanism, not a
 * claim that AGC's private getFixResolutionList() uses this exact nearest-size algorithm.
 */
object CameraPhotoResolutionPolicy {
    const val DEFAULT_ASPECT_TOLERANCE = 0.03
    const val RAW_ASPECT_TOLERANCE = 0.025

    fun resolve(
        inventory: CameraCapabilityInventory,
        formatResolution: ResolvedCameraPhotoFormat,
        request: CameraPhotoResolutionRequest = CameraPhotoResolutionRequest(),
        preferredTargetExtents: List<CameraCapabilityExtent> = emptyList()
    ): ResolvedCameraPhotoResolution {
        val target = formatResolution.effectiveFormat
            ?: return ResolvedCameraPhotoResolution(
                effectiveFormatCode = formatResolution.requestedFormatCode,
                selectionKind = CameraPhotoResolutionSelectionKind.UNAVAILABLE,
                specificRawSizeIndex = request.specificRawSizeIndex,
                resolutionFixReferenceFormatCode = request.resolutionFixReferenceFormatCode,
                resolutionFixApplied = false,
                nativeCandidates = emptyList(),
                fullFovCandidates = emptyList(),
                policyCandidates = emptyList(),
                selectedSize = null,
                conservativeSize = null,
                reason = "PRIMARY_BUFFER format is unresolved, so no resolution can be selected."
            )

        val native = target.sizes
            .filter { it.extent.width > 0 && it.extent.height > 0 }
            .distinctBy { it.extent }
        if (native.isEmpty()) {
            return unavailable(
                target = target,
                request = request,
                native = native,
                reason = "${target.formatName} has no usable Camera2 output sizes."
            )
        }

        val tolerance = if (isRaw(target.kind)) RAW_ASPECT_TOLERANCE else DEFAULT_ASPECT_TOLERANCE
        val fullFov = fullFovOrNative(
            candidates = native,
            sensorExtent = inventory.sensorExtent,
            aspectTolerance = tolerance
        )

        // Mechanism A: a valid specific RAW-size selection is terminal and bypasses Resolution Fix.
        val specificIndex = request.specificRawSizeIndex
        if (specificIndex != null && isRaw(target.kind) && specificIndex in native.indices) {
            val exact = native[specificIndex]
            val fullFovKnown = fullFov.any { it.extent == exact.extent }
            if (fullFovKnown) {
                return resolved(
                    target = target,
                    kind = CameraPhotoResolutionSelectionKind.SPECIFIC_RAW_SIZE,
                    request = request,
                    native = native,
                    fullFov = fullFov,
                    policyCandidates = listOf(exact),
                    selected = exact,
                    conservativePool = fullFov,
                    resolutionFixApplied = false,
                    reason = "Specific RAW size index $specificIndex selected ${extentLabel(exact.extent)} from the active ${target.formatName} size list and bypassed Resolution Fix."
                )
            }
        }

        // Mechanism B: Resolution Fix uses another ImageFormat's size list as a geometry reference.
        val referenceFormatCode = request.resolutionFixReferenceFormatCode
        if (referenceFormatCode != null) {
            val reference = inventory.format(referenceFormatCode)
            if (reference != null && reference.sizes.isNotEmpty()) {
                val referencePool = fullFovOrNative(
                    candidates = reference.sizes,
                    sensorExtent = inventory.sensorExtent,
                    aspectTolerance = when {
                        isRaw(reference.kind) -> RAW_ASPECT_TOLERANCE
                        else -> DEFAULT_ASPECT_TOLERANCE
                    }
                )
                val remapped = remapReferenceExtents(
                    targetCandidates = fullFov,
                    referenceCandidates = referencePool
                )
                if (remapped.isNotEmpty()) {
                    val selected = remapped.maxByOrNull { it.extent.area }
                    return resolved(
                        target = target,
                        kind = CameraPhotoResolutionSelectionKind.RESOLUTION_FIX_REMAP,
                        request = request,
                        native = native,
                        fullFov = fullFov,
                        policyCandidates = remapped,
                        selected = selected,
                        conservativePool = remapped,
                        resolutionFixApplied = true,
                        reason = "Resolution Fix used ${reference.formatName} ($referenceFormatCode) as the reference size list and remapped it onto HAL-reported ${target.formatName} sizes."
                    )
                }
            }
        }

        // Native Auto. For YUV an optimized/recommended list is only a preference when the exact
        // target-format sizes also exist in the HAL-reported pool and preserve sensor aspect.
        val preferredKeys = preferredTargetExtents.toHashSet()
        val preferredFullFov = if (preferredKeys.isEmpty()) {
            emptyList()
        } else {
            fullFov.filter { it.extent in preferredKeys }
        }
        val autoPool = preferredFullFov.ifEmpty { fullFov }
        val selected = autoPool.maxByOrNull { it.extent.area }
        val preferenceReason = when {
            preferredFullFov.isNotEmpty() -> "a full-FOV preferred target pool"
            fullFov.size != native.size -> "the standard full-FOV Camera2 pool"
            else -> "the standard Camera2 pool"
        }
        val ignoredSpecific = when {
            specificIndex == null -> ""
            !isRaw(target.kind) -> " Specific RAW size index $specificIndex was ignored because the effective format is not RAW."
            specificIndex !in native.indices -> " Specific RAW size index $specificIndex is outside the active RAW size list and was ignored."
            else -> " Specific RAW size index $specificIndex did not satisfy the full-FOV constraint and was ignored."
        }
        val ignoredFix = when {
            referenceFormatCode == null -> ""
            inventory.format(referenceFormatCode) == null -> " Resolution Fix reference format $referenceFormatCode is not reported and was ignored."
            inventory.format(referenceFormatCode)?.sizes.isNullOrEmpty() -> " Resolution Fix reference format $referenceFormatCode has no usable sizes and was ignored."
            else -> " Resolution Fix could not produce a legal target-format remap and was ignored."
        }
        return resolved(
            target = target,
            kind = CameraPhotoResolutionSelectionKind.NATIVE_AUTO,
            request = request,
            native = native,
            fullFov = fullFov,
            // Native Auto may prefer a recommended subset for its own selection, but the legal
            // candidate set remains the complete target-format full-FOV pool so runtime recovery
            // can select another legal extent without rediscovering Camera2 geometry.
            policyCandidates = fullFov,
            selected = selected,
            conservativePool = fullFov,
            resolutionFixApplied = false,
            reason = "Native Auto selected the largest extent from $preferenceReason.$ignoredSpecific$ignoredFix"
        )
    }

    private fun unavailable(
        target: CameraCapabilityFormat,
        request: CameraPhotoResolutionRequest,
        native: List<CameraCapabilitySize>,
        reason: String
    ) = ResolvedCameraPhotoResolution(
        effectiveFormatCode = target.formatCode,
        selectionKind = CameraPhotoResolutionSelectionKind.UNAVAILABLE,
        specificRawSizeIndex = request.specificRawSizeIndex,
        resolutionFixReferenceFormatCode = request.resolutionFixReferenceFormatCode,
        resolutionFixApplied = false,
        nativeCandidates = native,
        fullFovCandidates = emptyList(),
        policyCandidates = emptyList(),
        selectedSize = null,
        conservativeSize = null,
        reason = reason
    )

    private fun resolved(
        target: CameraCapabilityFormat,
        kind: CameraPhotoResolutionSelectionKind,
        request: CameraPhotoResolutionRequest,
        native: List<CameraCapabilitySize>,
        fullFov: List<CameraCapabilitySize>,
        policyCandidates: List<CameraCapabilitySize>,
        selected: CameraCapabilitySize?,
        conservativePool: List<CameraCapabilitySize>,
        resolutionFixApplied: Boolean,
        reason: String
    ): ResolvedCameraPhotoResolution {
        val conservative = selected?.let { chooseConservative(conservativePool, it) }
        return ResolvedCameraPhotoResolution(
            effectiveFormatCode = target.formatCode,
            selectionKind = kind,
            specificRawSizeIndex = request.specificRawSizeIndex,
            resolutionFixReferenceFormatCode = request.resolutionFixReferenceFormatCode,
            resolutionFixApplied = resolutionFixApplied,
            nativeCandidates = native,
            fullFovCandidates = fullFov,
            policyCandidates = policyCandidates.distinctBy { it.extent },
            selectedSize = selected,
            conservativeSize = conservative,
            reason = reason
        )
    }

    private fun fullFovOrNative(
        candidates: List<CameraCapabilitySize>,
        sensorExtent: CameraCapabilityExtent?,
        aspectTolerance: Double
    ): List<CameraCapabilitySize> {
        val sensorAspect = sensorExtent?.let(::normalizedAspect) ?: return candidates
        val full = candidates.filter { size ->
            val candidateAspect = normalizedAspect(size.extent)
            abs(candidateAspect - sensorAspect) / sensorAspect <= aspectTolerance.coerceAtLeast(0.0)
        }
        return full.ifEmpty { candidates }
    }

    private fun remapReferenceExtents(
        targetCandidates: List<CameraCapabilitySize>,
        referenceCandidates: List<CameraCapabilitySize>
    ): List<CameraCapabilitySize> {
        if (targetCandidates.isEmpty() || referenceCandidates.isEmpty()) return emptyList()
        val mapped = mutableListOf<CameraCapabilitySize>()

        referenceCandidates.forEach { reference ->
            val exact = targetCandidates.firstOrNull { it.extent == reference.extent }
            val chosen = exact ?: targetCandidates.minWithOrNull(
                compareBy<CameraCapabilitySize> {
                    relativeAspectDistance(it.extent, reference.extent)
                }.thenBy {
                    relativeAreaDistance(it.extent, reference.extent)
                }.thenByDescending {
                    it.extent.area
                }
            )
            if (chosen != null) mapped += chosen
        }
        return mapped.distinctBy { it.extent }
    }

    private fun chooseConservative(
        candidates: List<CameraCapabilitySize>,
        selected: CameraCapabilitySize
    ): CameraCapabilitySize {
        val selectedArea = selected.extent.area
        if (selectedArea <= 0L) return selected
        val minimumUsefulArea = (selectedArea * 0.40).toLong()
        val smaller = candidates
            .asSequence()
            .filter { it.extent.area in minimumUsefulArea until selectedArea }
            .distinctBy { it.extent }
            .toList()
        if (smaller.isEmpty()) return selected

        val materiallySmaller = smaller.filter { candidate ->
            candidate.extent.area * 100L <= selectedArea * 85L
        }.ifEmpty { smaller }

        val selectedDuration = selected.minFrameDurationNs
        val fasterOrEqual = materiallySmaller.filter { candidate ->
            val duration = candidate.minFrameDurationNs
            duration == null || selectedDuration == null || duration <= selectedDuration
        }
        val pool = fasterOrEqual.ifEmpty { materiallySmaller }
        return pool.maxByOrNull { it.extent.area } ?: selected
    }

    private fun isRaw(kind: CameraCapabilityFormatKind): Boolean = when (kind) {
        CameraCapabilityFormatKind.RAW10,
        CameraCapabilityFormatKind.RAW12,
        CameraCapabilityFormatKind.RAW_SENSOR -> true
        CameraCapabilityFormatKind.YUV_420_888,
        CameraCapabilityFormatKind.OTHER -> false
    }

    private fun normalizedAspect(extent: CameraCapabilityExtent): Double {
        val longSide = max(extent.width, extent.height).toDouble()
        val shortSide = min(extent.width, extent.height).coerceAtLeast(1).toDouble()
        return longSide / shortSide
    }

    private fun relativeAspectDistance(a: CameraCapabilityExtent, b: CameraCapabilityExtent): Double {
        val target = normalizedAspect(b)
        if (target <= 0.0) return Double.MAX_VALUE
        return abs(normalizedAspect(a) - target) / target
    }

    private fun relativeAreaDistance(a: CameraCapabilityExtent, b: CameraCapabilityExtent): Double {
        val target = b.area.coerceAtLeast(1L).toDouble()
        return abs(a.area.toDouble() - target) / target
    }

    private fun extentLabel(extent: CameraCapabilityExtent): String =
        "${extent.width}x${extent.height}"
}
