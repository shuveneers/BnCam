package com.bncam.core.engine

/**
 * Source authority for one camera capability inventory.
 *
 * DIRECT_CAMERA and PHYSICAL_CAMERA are sensor-specific CameraCharacteristics sources.
 * LOGICAL_PARENT_FALLBACK is explicitly weaker: it is useful for discovery, but it must never be
 * treated as proof that every parent-advertised stream is available on the requested physical ID.
 */
enum class CameraStreamCatalogSource {
    DIRECT_CAMERA,
    PHYSICAL_CAMERA,
    LOGICAL_PARENT_FALLBACK,
    UNAVAILABLE
}

enum class CameraCapabilityFormatKind {
    RAW10,
    RAW12,
    RAW_SENSOR,
    YUV_420_888,
    OTHER
}

/**
 * Whether BnCam may currently use a discovered format as a primary Photo producer.
 *
 * RAW12 is intentionally DISCOVERY_ONLY in this phase. GCam's resolver evidence includes RAW12 in
 * its ordered RAW fallback chain, but BnCam does not yet have a production RAW12 ingest/develop
 * path. Discovery must not be silently promoted into runtime support.
 */
enum class CameraCapabilityRuntimeAvailability {
    BNCAM_RUNTIME_READY,
    DISCOVERY_ONLY,
    NOT_PRIMARY_CAPTURE
}

data class CameraCapabilityExtent(
    val width: Int,
    val height: Int
) {
    init {
        require(width > 0) { "capability width must be > 0" }
        require(height > 0) { "capability height must be > 0" }
    }

    val area: Long get() = width.toLong() * height.toLong()
}

data class CameraCapabilitySize(
    val extent: CameraCapabilityExtent,
    val minFrameDurationNs: Long?
) {
    init {
        require(minFrameDurationNs == null || minFrameDurationNs > 0L) {
            "minFrameDurationNs must be positive when present"
        }
    }

    val maxFpsFromDuration: Int?
        get() = minFrameDurationNs?.let {
            (1_000_000_000.0 / it.toDouble()).toInt().coerceAtLeast(1)
        }
}

data class CameraCapabilityFpsRange(
    val lower: Int,
    val upper: Int
) {
    init {
        require(lower > 0) { "FPS lower bound must be > 0" }
        require(upper >= lower) { "FPS upper bound must be >= lower bound" }
    }
}

data class CameraCapabilityFormat(
    val formatCode: Int,
    val formatName: String,
    val kind: CameraCapabilityFormatKind,
    val runtimeAvailability: CameraCapabilityRuntimeAvailability,
    val sizes: List<CameraCapabilitySize>
) {
    init {
        require(formatName.isNotBlank()) { "formatName must not be blank" }
        val extents = sizes.map { it.extent }
        require(extents.size == extents.toSet().size) {
            "format $formatName contains duplicate size entries"
        }
    }
}

/**
 * Immutable Camera2/HAL discovery facts for one requested lens route.
 *
 * This is deliberately a capability inventory, not a Stream Configuration. It records what the
 * camera reports. Operation mode, stream roles, per-output routing, request targets, chosen
 * resolution and requested FPS remain separate policy decisions.
 */
data class CameraCapabilityInventory(
    val requestedLensId: String,
    val logicalCameraId: String?,
    val physicalCameraId: String?,
    val source: CameraStreamCatalogSource,
    val hardwareLevel: Int?,
    val requestCapabilities: Set<Int>,
    val aeFpsRanges: List<CameraCapabilityFpsRange>,
    val sensorExtent: CameraCapabilityExtent?,
    val viewfinderExtents: List<CameraCapabilityExtent>,
    val formats: List<CameraCapabilityFormat>,
    val warnings: List<String>
) {
    init {
        require(requestedLensId.isNotBlank()) { "requestedLensId must not be blank" }
        val formatCodes = formats.map { it.formatCode }
        require(formatCodes.size == formatCodes.toSet().size) {
            "camera capability inventory contains duplicate format codes"
        }
        require(viewfinderExtents.size == viewfinderExtents.toSet().size) {
            "camera capability inventory contains duplicate viewfinder extents"
        }
    }

    fun format(formatCode: Int): CameraCapabilityFormat? =
        formats.firstOrNull { it.formatCode == formatCode }

    fun reports(formatCode: Int): Boolean = format(formatCode) != null

    fun photoFormatPlan(): CameraPhotoFormatPlan = CameraPhotoFormatPolicy.resolve(this)
}

data class CameraPhotoFormatPlan(
    /** Reported formats in GCam-evidence priority order, including discovery-only formats. */
    val reportedPriorityOrder: List<CameraCapabilityFormat>,
    /** Formats BnCam may currently execute as the primary Photo producer. */
    val runtimeCandidates: List<CameraCapabilityFormat>,
    /** Reported preferred formats deliberately withheld from runtime selection. */
    val discoveryOnlyCandidates: List<CameraCapabilityFormat>
) {
    val runtimeFormatCodes: List<Int> get() = runtimeCandidates.map { it.formatCode }
}

/**
 * How the profile's requested Photo buffer constrains runtime format selection.
 *
 * EXACT_ONLY is used for explicit YUV routes: selecting YUV must never silently promote the
 * pipeline to RAW. RAW_WITH_ORDERED_FALLBACK keeps a supported explicit RAW request, but when that
 * exact RAW producer is unavailable it may walk the evidence-backed runtime chain.
 */
enum class CameraPhotoFormatRequestPolicy {
    EXACT_ONLY,
    RAW_WITH_ORDERED_FALLBACK
}

enum class CameraPhotoFormatSelectionKind {
    REQUESTED_FORMAT,
    ORDERED_FALLBACK,
    UNAVAILABLE
}

data class ResolvedCameraPhotoFormat(
    val requestedFormatCode: Int,
    val requestPolicy: CameraPhotoFormatRequestPolicy,
    val selectionKind: CameraPhotoFormatSelectionKind,
    val effectiveFormat: CameraCapabilityFormat?,
    val runtimeCandidatesConsidered: List<Int>,
    val reason: String
) {
    val effectiveFormatCode: Int? get() = effectiveFormat?.formatCode
    val fallbackApplied: Boolean
        get() = selectionKind == CameraPhotoFormatSelectionKind.ORDERED_FALLBACK
}

/**
 * Ordered primary Photo format policy.
 *
 * Reverse-engineering evidence from GCam showed AUTO probing the public stream map in the order
 * RAW10 -> RAW12 -> RAW_SENSOR -> YUV. That order is represented here as policy, not as a claim
 * that every BnCam decoder can consume every discovered format.
 */
object CameraPhotoFormatPolicy {
    private val preferenceOrder = listOf(
        CameraCapabilityFormatKind.RAW10,
        CameraCapabilityFormatKind.RAW12,
        CameraCapabilityFormatKind.RAW_SENSOR,
        CameraCapabilityFormatKind.YUV_420_888
    )

    fun defaultRuntimeAvailability(kind: CameraCapabilityFormatKind): CameraCapabilityRuntimeAvailability =
        when (kind) {
            CameraCapabilityFormatKind.RAW10,
            CameraCapabilityFormatKind.RAW_SENSOR,
            CameraCapabilityFormatKind.YUV_420_888 -> CameraCapabilityRuntimeAvailability.BNCAM_RUNTIME_READY

            CameraCapabilityFormatKind.RAW12 -> CameraCapabilityRuntimeAvailability.DISCOVERY_ONLY
            CameraCapabilityFormatKind.OTHER -> CameraCapabilityRuntimeAvailability.NOT_PRIMARY_CAPTURE
        }

    fun preferenceRank(kind: CameraCapabilityFormatKind): Int =
        preferenceOrder.indexOf(kind).takeIf { it >= 0 } ?: Int.MAX_VALUE

    fun resolve(inventory: CameraCapabilityInventory): CameraPhotoFormatPlan {
        val ordered = inventory.formats
            .asSequence()
            .filter { preferenceRank(it.kind) != Int.MAX_VALUE }
            .sortedWith(
                compareBy<CameraCapabilityFormat> { preferenceRank(it.kind) }
                    .thenBy { it.formatCode }
            )
            .toList()
        return CameraPhotoFormatPlan(
            reportedPriorityOrder = ordered,
            runtimeCandidates = ordered.filter {
                it.runtimeAvailability == CameraCapabilityRuntimeAvailability.BNCAM_RUNTIME_READY
            },
            discoveryOnlyCandidates = ordered.filter {
                it.runtimeAvailability == CameraCapabilityRuntimeAvailability.DISCOVERY_ONLY
            }
        )
    }

    /**
     * Resolves the actual PRIMARY_BUFFER format without coupling format selection to resolution.
     *
     * A supported explicit request wins. Ordered fallback is only used for RAW requests and only
     * across HAL-reported, BnCam-runtime-ready formats with at least one usable output size. This
     * preserves YUV as an exact user constraint while allowing an unavailable RAW request to use
     * the GCam-evidence order RAW10 -> RAW12 -> RAW_SENSOR -> YUV. RAW12 is skipped until its
     * runtime availability becomes BNCAM_RUNTIME_READY.
     */
    fun resolveRuntimePrimary(
        inventory: CameraCapabilityInventory,
        requestedFormatCode: Int,
        requestPolicy: CameraPhotoFormatRequestPolicy
    ): ResolvedCameraPhotoFormat {
        val plan = resolve(inventory)
        val usableRuntimeCandidates = plan.runtimeCandidates.filter { it.sizes.isNotEmpty() }
        val consideredCodes = usableRuntimeCandidates.map { it.formatCode }
        val requested = usableRuntimeCandidates.firstOrNull { it.formatCode == requestedFormatCode }

        if (requested != null) {
            return ResolvedCameraPhotoFormat(
                requestedFormatCode = requestedFormatCode,
                requestPolicy = requestPolicy,
                selectionKind = CameraPhotoFormatSelectionKind.REQUESTED_FORMAT,
                effectiveFormat = requested,
                runtimeCandidatesConsidered = consideredCodes,
                reason = "Requested Photo format ${requested.formatName} is HAL-reported, runtime-ready and has usable output sizes."
            )
        }

        if (requestPolicy == CameraPhotoFormatRequestPolicy.EXACT_ONLY) {
            val reported = inventory.format(requestedFormatCode)
            return ResolvedCameraPhotoFormat(
                requestedFormatCode = requestedFormatCode,
                requestPolicy = requestPolicy,
                selectionKind = CameraPhotoFormatSelectionKind.UNAVAILABLE,
                effectiveFormat = null,
                runtimeCandidatesConsidered = consideredCodes,
                reason = when {
                    reported == null ->
                        "Requested exact Photo format $requestedFormatCode is not reported by this camera route."
                    reported.runtimeAvailability != CameraCapabilityRuntimeAvailability.BNCAM_RUNTIME_READY ->
                        "Requested exact Photo format ${reported.formatName} is reported but is not runtime-ready."
                    reported.sizes.isEmpty() ->
                        "Requested exact Photo format ${reported.formatName} has no usable output sizes."
                    else ->
                        "Requested exact Photo format ${reported.formatName} is unavailable for this runtime route."
                }
            )
        }

        val fallback = usableRuntimeCandidates.firstOrNull()
        if (fallback != null) {
            val requestedReported = inventory.format(requestedFormatCode)
            val requestedReason = when {
                requestedReported == null -> "not reported"
                requestedReported.runtimeAvailability != CameraCapabilityRuntimeAvailability.BNCAM_RUNTIME_READY ->
                    "reported but not runtime-ready"
                requestedReported.sizes.isEmpty() -> "reported without usable output sizes"
                else -> "not executable"
            }
            return ResolvedCameraPhotoFormat(
                requestedFormatCode = requestedFormatCode,
                requestPolicy = requestPolicy,
                selectionKind = CameraPhotoFormatSelectionKind.ORDERED_FALLBACK,
                effectiveFormat = fallback,
                runtimeCandidatesConsidered = consideredCodes,
                reason = "Requested RAW Photo format $requestedFormatCode is $requestedReason; ordered runtime fallback selected ${fallback.formatName}."
            )
        }

        return ResolvedCameraPhotoFormat(
            requestedFormatCode = requestedFormatCode,
            requestPolicy = requestPolicy,
            selectionKind = CameraPhotoFormatSelectionKind.UNAVAILABLE,
            effectiveFormat = null,
            runtimeCandidatesConsidered = consideredCodes,
            reason = "No HAL-reported BnCam-runtime-ready Photo format with usable output sizes is available."
        )
    }
}
