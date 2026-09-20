package com.bncam.core.engine

/** Selects the Camera2 session creation mechanism, not the stream format or FPS. */
enum class StreamSessionMode {
    REGULAR_SESSION,
    CUSTOM_OPERATION_MODE_SESSION
}

/** First-class semantic roles inside one camera session. */
enum class StreamRoleKind {
    VIEWFINDER,
    YUV_PRIMARY,
    SUPPORT_LARGE,
    ANALYSIS,
    RAW_PRIMARY,
    RAW_PREVIEW_SUPPORT,
    AUXILIARY
}

/** The owner that supplies a Surface for this role. */
enum class StreamOutputKind {
    DISPLAY_SURFACE,
    IMAGE_READER,
    EXTERNAL_SURFACE
}

/** Lifetime contract for the concrete runtime output that will later bind to this role. */
enum class StreamRoleLifetime {
    SESSION,
    PIPELINE,
    ONE_SHOT
}

/**
 * Camera2-independent stream extent so the topology model is unit-testable without Android stubs.
 */
data class StreamExtent(
    val width: Int,
    val height: Int
) {
    init {
        require(width > 0) { "stream width must be > 0" }
        require(height > 0) { "stream height must be > 0" }
    }

    val area: Long get() = width.toLong() * height.toLong()
}

/**
 * Immutable specification for one semantic stream role.
 *
 * logicalCameraId identifies the opened CameraDevice. physicalCameraId is deliberately per-output:
 * it may differ between roles in the same logical session and is later applied through
 * OutputConfiguration.setPhysicalCameraId where supported.
 */
data class StreamRoleSpec(
    val id: String,
    val kind: StreamRoleKind,
    val logicalCameraId: String,
    val physicalCameraId: String? = null,
    val formatCode: Int,
    val extent: StreamExtent,
    val outputKind: StreamOutputKind,
    val maxImages: Int? = null,
    val usage: Long? = null,
    val lifetime: StreamRoleLifetime = StreamRoleLifetime.SESSION
) {
    init {
        require(id.isNotBlank()) { "stream role id must not be blank" }
        require(logicalCameraId.isNotBlank()) { "logicalCameraId must not be blank" }
        require(maxImages == null || maxImages > 0) { "maxImages must be > 0 when present" }
        require(outputKind == StreamOutputKind.IMAGE_READER || maxImages == null) {
            "maxImages is only valid for IMAGE_READER roles"
        }
    }
}

/**
 * Immutable authority for BnCam's canonical Photo producer inside one resolved session contract.
 *
 * This records the already-resolved format/extent decision so the later role graph cannot silently
 * reselect geometry from a legacy candidate or infer RAW/YUV semantics from a concrete ImageReader.
 * FPS is intentionally absent: target cadence belongs to CaptureRequest policy, not Stream Config.
 */
data class ResolvedPrimaryStreamContract(
    val roleId: String,
    val kind: StreamRoleKind,
    val requestedFormatCode: Int,
    val effectiveFormatCode: Int,
    val extent: StreamExtent,
    val formatSelectionKind: CameraPhotoFormatSelectionKind,
    val formatFallbackApplied: Boolean,
    val resolutionSelectionKind: CameraPhotoResolutionSelectionKind,
    val specificRawSizeIndex: Int? = null,
    val resolutionFixReferenceFormatCode: Int? = null,
    val resolutionFixApplied: Boolean = false,
    val runtimeFallbackTier: StreamRuntimeFallbackTier = StreamRuntimeFallbackTier.NONE,
    val evidence: String
) {
    init {
        require(roleId.isNotBlank()) { "primary stream roleId must not be blank" }
        require(kind == StreamRoleKind.YUV_PRIMARY || kind == StreamRoleKind.RAW_PRIMARY) {
            "primary stream contract kind must be YUV_PRIMARY or RAW_PRIMARY"
        }
        require(effectiveFormatCode >= 0) { "effectiveFormatCode must be non-negative" }
        require(evidence.isNotBlank()) { "primary stream evidence must not be blank" }
    }
}

/** All semantic roles selected for one resolved configuration. */
data class StreamRoleGraph(
    val roles: List<StreamRoleSpec>
) {
    init {
        val ids = roles.map { it.id }
        require(ids.size == ids.toSet().size) { "stream role ids must be unique" }
    }

    private val byId: Map<String, StreamRoleSpec> = roles.associateBy { it.id }

    fun role(id: String): StreamRoleSpec? = byId[id]
    fun contains(id: String): Boolean = byId.containsKey(id)
    fun ids(): Set<String> = byId.keys
}

/**
 * Roles whose concrete Surfaces are configured as CaptureSession outputs.
 *
 * This graph is intentionally independent from CaptureRequest targets.
 */
data class SessionOutputGraph(
    val roleIds: Set<String>
) {
    init {
        require(roleIds.none { it.isBlank() }) { "session output role ids must not be blank" }
    }
}

/**
 * Per-request target topology. A configured session output does not automatically become a target
 * of either the repeating request or a still/one-shot request.
 */
data class RequestTargetGraph(
    val repeatingRoleIds: Set<String>,
    val captureRoleIds: Set<String>
) {
    init {
        require(repeatingRoleIds.none { it.isBlank() }) { "repeating target role ids must not be blank" }
        require(captureRoleIds.none { it.isBlank() }) { "capture target role ids must not be blank" }
    }
}

/**
 * Top-level resolved Stream Configuration contract.
 *
 * This replaces the old conceptual "one candidate = format + size + preview" model. It contains
 * operation-mode policy, session implementation, semantic stream roles, configured outputs and
 * independent request target sets.
 */
data class ResolvedStreamConfiguration(
    val operationMode: ResolvedOperationMode,
    val roleGraph: StreamRoleGraph,
    val sessionGraph: SessionOutputGraph,
    val requestGraph: RequestTargetGraph,
    val primaryStream: ResolvedPrimaryStreamContract? = null
) {
    init {
        require(operationMode.streamMode == streamModeFor(operationMode.operationMode)) {
            "stream mode must follow the resolved operation mode"
        }

        val knownRoles = roleGraph.ids()
        val unknownSessionRoles = sessionGraph.roleIds - knownRoles
        require(unknownSessionRoles.isEmpty()) {
            "session graph references unknown roles: ${unknownSessionRoles.sorted()}"
        }

        val unknownRepeatingRoles = requestGraph.repeatingRoleIds - sessionGraph.roleIds
        require(unknownRepeatingRoles.isEmpty()) {
            "repeating request targets must be configured session outputs: ${unknownRepeatingRoles.sorted()}"
        }

        val unknownCaptureRoles = requestGraph.captureRoleIds - sessionGraph.roleIds
        require(unknownCaptureRoles.isEmpty()) {
            "capture request targets must be configured session outputs: ${unknownCaptureRoles.sorted()}"
        }

        val primaryRoles = roleGraph.roles.filter {
            it.kind == StreamRoleKind.YUV_PRIMARY || it.kind == StreamRoleKind.RAW_PRIMARY
        }
        require(primaryRoles.size <= 1) {
            "resolved stream configuration may contain at most one canonical primary role"
        }
        require(primaryRoles.isEmpty() || primaryStream != null) {
            "canonical primary role requires a ResolvedPrimaryStreamContract"
        }
        require(primaryStream == null || primaryRoles.size == 1) {
            "ResolvedPrimaryStreamContract requires exactly one canonical primary role"
        }

        primaryStream?.let { primary ->
            val role = requireNotNull(roleGraph.role(primary.roleId)) {
                "primary stream contract references unknown role ${primary.roleId}"
            }
            require(primary.roleId in sessionGraph.roleIds) {
                "primary stream role ${primary.roleId} must be a configured session output"
            }
            require(primary.roleId in requestGraph.repeatingRoleIds) {
                "primary stream role ${primary.roleId} must remain warm in the repeating request"
            }
            require(primary.roleId in requestGraph.captureRoleIds) {
                "primary stream role ${primary.roleId} must be a capture target"
            }
            require(role.kind == primary.kind) {
                "primary stream role kind ${role.kind} does not match resolved contract ${primary.kind}"
            }
            require(role.formatCode == primary.effectiveFormatCode) {
                "primary stream role format ${role.formatCode} does not match resolved contract ${primary.effectiveFormatCode}"
            }
            require(role.extent == primary.extent) {
                "primary stream role extent ${role.extent} does not match resolved contract ${primary.extent}"
            }
        }
    }

    val streamMode: StreamSessionMode get() = operationMode.streamMode

    fun sessionRoles(): List<StreamRoleSpec> =
        sessionGraph.roleIds.mapNotNull(roleGraph::role)

    fun repeatingRoles(): List<StreamRoleSpec> =
        requestGraph.repeatingRoleIds.mapNotNull(roleGraph::role)

    fun captureRoles(): List<StreamRoleSpec> =
        requestGraph.captureRoleIds.mapNotNull(roleGraph::role)

    private companion object {
        fun streamModeFor(operationMode: Int): StreamSessionMode =
            if (operationMode == 0) {
                StreamSessionMode.REGULAR_SESSION
            } else {
                StreamSessionMode.CUSTOM_OPERATION_MODE_SESSION
            }
    }
}
