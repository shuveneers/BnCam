package com.bncam.core.capture

enum class MethodAvailability {
    IMPLEMENTED,
    EXPERIMENTAL,
    UNSUPPORTED,
    PLANNED
}

data class MultiFrameAlgorithmDescriptor(
    val id: String,
    val displayName: String,
    val description: String,
    val availability: MethodAvailability,
    val exposed: Boolean,
    val applicableSources: Set<FrameOrigin> = FrameOrigin.entries.toSet(),
    val isFusionMethod: Boolean = false,
    val legacyIds: Set<String> = emptySet()
) {
    val isSelectableInPhase5A: Boolean
        get() = exposed &&
            (availability == MethodAvailability.IMPLEMENTED ||
                availability == MethodAvailability.EXPERIMENTAL)
}

data class MethodResolution(
    val requestedId: String,
    val requestedAvailability: MethodAvailability?,
    val supported: Boolean,
    val resolvedId: String,
    val fallback: Boolean,
    val reason: String
)

object FrameSelectionRegistry {
    val LATEST_COMPLETE = MultiFrameAlgorithmDescriptor(
        id = "latest_complete",
        displayName = "Latest Complete",
        description = "Selects the newest complete, generation-valid frame.",
        availability = MethodAvailability.IMPLEMENTED,
        exposed = false
    )
    val SHUTTER_SYNCHRONIZED = MultiFrameAlgorithmDescriptor(
        id = "shutter_synchronized",
        displayName = "Shutter Synchronized",
        description = "Uses shutter-relative freshness and request provenance.",
        availability = MethodAvailability.IMPLEMENTED,
        exposed = false
    )
    val BALANCED = MultiFrameAlgorithmDescriptor(
        id = "balanced",
        displayName = "Balanced",
        description = "Current Single Frame weighted AE/AWB/focus/freshness selection.",
        availability = MethodAvailability.IMPLEMENTED,
        exposed = false
    )
    val SHARPEST = planned("sharpest", "Sharpest")
    val LOWEST_MOTION = planned("lowest_motion", "Lowest Motion")
    val BEST_EXPOSURE = planned("best_exposure", "Best Exposure")
    val MANUAL_POST_CAPTURE = planned("manual_post_capture", "Manual Post-capture")

    val registeredMethods = listOf(
        LATEST_COMPLETE,
        SHUTTER_SYNCHRONIZED,
        BALANCED,
        SHARPEST,
        LOWEST_MOTION,
        BEST_EXPOSURE,
        MANUAL_POST_CAPTURE
    )

    fun resolvedFor(mode: CaptureMode): MultiFrameAlgorithmDescriptor =
        if (mode == CaptureMode.MULTI) LATEST_COMPLETE else BALANCED

    private fun planned(id: String, label: String) = MultiFrameAlgorithmDescriptor(
        id = id,
        displayName = label,
        description = "Registered for future implementation; not executed by the current capture route.",
        availability = MethodAvailability.PLANNED,
        exposed = false
    )
}

object AnchorSelectionRegistry {
    val LATEST_SELECTED = MultiFrameAlgorithmDescriptor(
        id = "latest_selected",
        displayName = "Latest Selected",
        description = "The newest selected frame is the multi-frame anchor.",
        availability = MethodAvailability.IMPLEMENTED,
        exposed = false
    )
    val WEIGHTED_QUALITY = MultiFrameAlgorithmDescriptor(
        id = "weighted_quality",
        displayName = "Weighted Quality",
        description = "Single Frame anchor chosen by the current weighted candidate score.",
        availability = MethodAvailability.IMPLEMENTED,
        exposed = false
    )

    val registeredMethods = listOf(LATEST_SELECTED, WEIGHTED_QUALITY)

    fun resolvedFor(mode: CaptureMode): MultiFrameAlgorithmDescriptor =
        if (mode == CaptureMode.MULTI) LATEST_SELECTED else WEIGHTED_QUALITY
}

object MultiFrameAlignmentRegistry {
    val AUTO = descriptor(
        id = "auto",
        label = "Auto",
        description = "Resolves to the native phase-correlation translation path.",
        availability = MethodAvailability.IMPLEMENTED,
        exposed = true,
        legacyIds = setOf("Auto")
    )
    val PHASE_CORRELATION = descriptor(
        id = "phase_correlation_fast",
        label = "Phase Correlation Fast",
        description = "Native OpenCV phase-correlation translation used by current multi-frame routes.",
        availability = MethodAvailability.IMPLEMENTED,
        exposed = false,
        legacyIds = setOf("Phase Correlation Pyramid", "Phase Correlation")
    )
    val PYRAMIDAL_TRANSLATION = planned("pyramidal_translation", "Pyramidal Translation")
    val WIENER_PYRAMID = planned("wiener_pyramid", "Wiener Pyramid")
    val TILE_MOTION = planned("tile_motion_alignment", "Tile Motion Alignment", setOf("Tile Pyramid"))
    val OPTICAL_FLOW = planned("optical_flow_quality", "Optical Flow Quality")
    val GYRO_ASSISTED = planned("gyro_assisted_alignment", "Gyro-assisted Alignment")
    val ECC_PYRAMID = planned("ecc_pyramid", "ECC Pyramid", setOf("ECC Pyramid"))

    val registeredMethods = listOf(
        AUTO,
        PHASE_CORRELATION,
        PYRAMIDAL_TRANSLATION,
        WIENER_PYRAMID,
        TILE_MOTION,
        OPTICAL_FLOW,
        GYRO_ASSISTED,
        ECC_PYRAMID
    )
    val selectableMethodsPhase5A = registeredMethods.filter { it.isSelectableInPhase5A }

    fun isValid(id: String): Boolean =
        selectableMethodsPhase5A.any { it.matches(id) }

    fun canonicalSelectableId(id: String): String? =
        selectableMethodsPhase5A.firstOrNull { it.matches(id) }?.id

    fun resolve(requestedId: String, origin: FrameOrigin): MethodResolution =
        resolveRegistered(requestedId, origin, AUTO, PHASE_CORRELATION, registeredMethods)

    private fun descriptor(
        id: String,
        label: String,
        description: String,
        availability: MethodAvailability,
        exposed: Boolean,
        legacyIds: Set<String> = emptySet()
    ) = MultiFrameAlgorithmDescriptor(
        id = id,
        displayName = label,
        description = description,
        availability = availability,
        exposed = exposed,
        legacyIds = legacyIds
    )

    private fun planned(
        id: String,
        label: String,
        legacyIds: Set<String> = emptySet()
    ) = descriptor(
        id = id,
        label = label,
        description = "Registered but not connected to the production capture runner.",
        availability = MethodAvailability.PLANNED,
        exposed = false,
        legacyIds = legacyIds
    )
}

object MultiFrameFusionRegistry {
    val AUTO = descriptor(
        id = "auto",
        label = "Auto",
        description = "Resolves to the source-specific native fusion that is actually executed.",
        availability = MethodAvailability.IMPLEMENTED,
        exposed = true,
        legacyIds = setOf("Auto")
    )
    val ANCHOR_ONLY = descriptor(
        id = "anchor_only",
        label = "Anchor Only",
        description = "Typed fallback/result when no support frame is accepted.",
        availability = MethodAvailability.IMPLEMENTED,
        exposed = false,
        legacyIds = setOf("Reference Dominant")
    )
    val WEIGHTED_AVERAGE = descriptor(
        id = "weighted_average",
        label = "Weighted Average",
        description = "Current aligned YUV luma accumulation with anchor chroma.",
        availability = MethodAvailability.IMPLEMENTED,
        exposed = false,
        sources = setOf(FrameOrigin.YUV),
        legacyIds = setOf("Robust Weighted Average")
    )
    val ROBUST_MEAN = descriptor(
        id = "robust_mean",
        label = "Robust Mean",
        description = "Current RAW native confidence/outlier-weighted accumulation.",
        availability = MethodAvailability.IMPLEMENTED,
        exposed = false,
        sources = setOf(FrameOrigin.RAW10, FrameOrigin.RAW_SENSOR)
    )
    val SIGMA_CLIPPED = planned("sigma_clipped_fusion", "Sigma-clipped Fusion")
    val WIENER_FUSION = planned("wiener_fusion", "Wiener Fusion", setOf("Wiener Pyramid"))
    val BURST_SUPER_RESOLUTION = planned("burst_super_resolution", "Burst Super Resolution")
    val CONSTANT_EXPOSURE_HDR = planned("constant_exposure_hdr", "Constant-exposure HDR")
    val HDR_ENHANCED_TEMPORAL = descriptor(
        id = "hdr_enhanced_temporal_raw",
        label = "HDR Enhanced Temporal RAW",
        description = "Same/similar-exposure deliberate RAW stacking with Vulkan alignment and confidence fusion.",
        availability = MethodAvailability.IMPLEMENTED,
        exposed = false,
        sources = setOf(FrameOrigin.RAW10, FrameOrigin.RAW_SENSOR)
    )
    val BRACKETED_HDR = descriptor(
        id = "bracketed_hdr",
        label = "Bracketed HDR",
        description = "Legacy exposure-bracketed Vulkan confidence fusion.",
        availability = MethodAvailability.IMPLEMENTED,
        exposed = false
    )
    val COMPUTATIONAL_LONG_EXPOSURE = planned(
        "computational_long_exposure",
        "Computational Long Exposure"
    )

    val registeredMethods = listOf(
        AUTO,
        ANCHOR_ONLY,
        WEIGHTED_AVERAGE,
        ROBUST_MEAN,
        SIGMA_CLIPPED,
        WIENER_FUSION,
        BURST_SUPER_RESOLUTION,
        CONSTANT_EXPOSURE_HDR,
        HDR_ENHANCED_TEMPORAL,
        BRACKETED_HDR,
        COMPUTATIONAL_LONG_EXPOSURE
    )
    val selectableMethodsPhase5A = registeredMethods.filter { it.isSelectableInPhase5A }

    fun isValid(id: String): Boolean =
        selectableMethodsPhase5A.any { it.matches(id) }

    fun canonicalSelectableId(id: String): String? =
        selectableMethodsPhase5A.firstOrNull { it.matches(id) }?.id

    fun resolve(requestedId: String, origin: FrameOrigin): MethodResolution {
        val executed = when (origin) {
            FrameOrigin.YUV -> WEIGHTED_AVERAGE
            FrameOrigin.RAW10,
            FrameOrigin.RAW_SENSOR -> ROBUST_MEAN
        }
        return resolveRegistered(requestedId, origin, AUTO, executed, registeredMethods)
    }

    private fun descriptor(
        id: String,
        label: String,
        description: String,
        availability: MethodAvailability,
        exposed: Boolean,
        sources: Set<FrameOrigin> = FrameOrigin.entries.toSet(),
        legacyIds: Set<String> = emptySet()
    ) = MultiFrameAlgorithmDescriptor(
        id = id,
        displayName = label,
        description = description,
        availability = availability,
        exposed = exposed,
        applicableSources = sources,
        isFusionMethod = true,
        legacyIds = legacyIds
    )

    private fun planned(
        id: String,
        label: String,
        legacyIds: Set<String> = emptySet()
    ) = descriptor(
        id = id,
        label = label,
        description = "Registered for a later processing phase; not executed by production capture.",
        availability = MethodAvailability.PLANNED,
        exposed = false,
        legacyIds = legacyIds
    )
}

private fun MultiFrameAlgorithmDescriptor.matches(value: String): Boolean =
    id.equals(value.trim(), ignoreCase = true) ||
        displayName.equals(value.trim(), ignoreCase = true) ||
        legacyIds.any { it.equals(value.trim(), ignoreCase = true) }

private fun resolveRegistered(
    requestedId: String,
    origin: FrameOrigin,
    auto: MultiFrameAlgorithmDescriptor,
    autoExecuted: MultiFrameAlgorithmDescriptor,
    registered: List<MultiFrameAlgorithmDescriptor>
): MethodResolution {
    val requested = registered.firstOrNull { it.matches(requestedId) }
    return when {
        requested == auto -> MethodResolution(
            requestedId = requestedId,
            requestedAvailability = auto.availability,
            supported = true,
            resolvedId = autoExecuted.id,
            fallback = false,
            reason = "auto_resolved_to_current_native_${autoExecuted.id}"
        )
        requested != null &&
            requested.isSelectableInPhase5A &&
            origin in requested.applicableSources -> MethodResolution(
            requestedId = requestedId,
            requestedAvailability = requested.availability,
            supported = true,
            resolvedId = requested.id,
            fallback = false,
            reason = "implemented_method_selected"
        )
        requested != null -> MethodResolution(
            requestedId = requestedId,
            requestedAvailability = requested.availability,
            supported = false,
            resolvedId = autoExecuted.id,
            fallback = true,
            reason = "registered_method_not_connected_to_production_runner"
        )
        else -> MethodResolution(
            requestedId = requestedId,
            requestedAvailability = null,
            supported = false,
            resolvedId = autoExecuted.id,
            fallback = true,
            reason = "unknown_legacy_method_resolved_explicitly"
        )
    }
}
