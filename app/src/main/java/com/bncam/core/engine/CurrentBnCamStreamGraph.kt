package com.bncam.core.engine

private const val CAMERA2_PRIVATE_FORMAT_CODE = 34

/**
 * Stable semantic role IDs used while BnCameraManager is migrated away from ad-hoc Surface lists.
 *
 * These IDs describe meaning, not Camera2 object identity. Concrete Surfaces are bound separately
 * for every session attempt by [BoundStreamConfiguration].
 */
object BnCamStreamRoleIds {
    const val VIEWFINDER = "VIEWFINDER"
    const val PRIMARY_BUFFER = "PRIMARY_BUFFER"
    const val RAW_PREVIEW_SUPPORT = "RAW_PREVIEW_SUPPORT"
}

/**
 * Which already-configured output family is expected to feed the visible viewfinder request path.
 *
 * This is deliberately a request-routing choice, not a CaptureSession-output choice. A VIEWFINDER
 * output may remain configured while RAW is displayed, and can be re-targeted later without
 * rebuilding the session. Likewise the canonical PRIMARY_BUFFER stays warm even while YUV is the
 * visible route because it remains BnCam's capture producer.
 */
enum class CurrentViewfinderRequestRoute {
    CAMERA2_VIEWFINDER,
    BUFFERED_PRIMARY
}

data class CurrentImageReaderRoleInput(
    val formatCode: Int,
    val extent: StreamExtent,
    val maxImages: Int
) {
    init {
        require(maxImages > 0) { "ImageReader maxImages must be > 0" }
    }
}

/**
 * Exact inputs needed to describe the outputs that the current BnCameraManager already owns.
 *
 * This adapts the concrete outputs currently owned by BnCameraManager into the final typed session
 * contract. Capability/format/resolution decisions have already been resolved before this point;
 * request-target membership remains independent from configured session outputs.
 */
data class CurrentBnCamStreamGraphInput(
    val logicalCameraId: String,
    val physicalRouting: StreamPhysicalRoutingPlan,
    val operationMode: ResolvedOperationMode,
    val previewExtent: StreamExtent?,
    val primary: CurrentImageReaderRoleInput?,
    val primaryContract: ResolvedPrimaryStreamContract?,
    val rawPreviewSupport: CurrentImageReaderRoleInput? = null,
    val viewfinderRoute: CurrentViewfinderRequestRoute =
        CurrentViewfinderRequestRoute.CAMERA2_VIEWFINDER,
    val rawPreviewSupportRepeatingEnabled: Boolean = true
) {
    init {
        require(logicalCameraId.isNotBlank()) { "logicalCameraId must not be blank" }
        require(physicalRouting.logicalCameraId == logicalCameraId) {
            "Physical routing belongs to logical ${physicalRouting.logicalCameraId}, expected $logicalCameraId"
        }
        require((primary == null) == (primaryContract == null)) {
            "PRIMARY_BUFFER runtime output and resolved primary contract must either both be present or both be absent"
        }
        if (primary != null && primaryContract != null) {
            require(primaryContract.roleId == BnCamStreamRoleIds.PRIMARY_BUFFER) {
                "Resolved primary contract must target ${BnCamStreamRoleIds.PRIMARY_BUFFER}"
            }
            require(primary.formatCode == primaryContract.effectiveFormatCode) {
                "PRIMARY_BUFFER ImageReader format ${primary.formatCode} does not match resolved contract ${primaryContract.effectiveFormatCode}"
            }
            require(primary.extent == primaryContract.extent) {
                "PRIMARY_BUFFER ImageReader extent ${primary.extent} does not match resolved contract ${primaryContract.extent}"
            }
        }
    }
}

/**
 * Resolves request targets independently from CaptureSession outputs.
 *
 * Invariants:
 * - PRIMARY_BUFFER is always repeating when configured because it is the warm capture producer.
 * - CAMERA2_VIEWFINDER adds the direct display Surface to the repeating request.
 * - BUFFERED_PRIMARY deliberately removes VIEWFINDER from repeating membership; RAW is then
 *   rendered from the buffered producer, avoiding an unnecessary concurrent direct-display target.
 * - RAW_PREVIEW_SUPPORT is repeating only on the buffered RAW viewfinder route. It never becomes a
 *   still-capture target and never replaces PRIMARY_BUFFER as the near-ZSL capture producer.
 */
object CurrentBnCamRequestTargetPolicy {
    fun resolve(
        roleGraph: StreamRoleGraph,
        viewfinderRoute: CurrentViewfinderRequestRoute,
        rawPreviewSupportEnabled: Boolean = true
    ): RequestTargetGraph {
        val repeating = linkedSetOf<String>()
        val capture = linkedSetOf<String>()

        if (roleGraph.contains(BnCamStreamRoleIds.PRIMARY_BUFFER)) {
            repeating += BnCamStreamRoleIds.PRIMARY_BUFFER
            capture += BnCamStreamRoleIds.PRIMARY_BUFFER
        }

        when (viewfinderRoute) {
            CurrentViewfinderRequestRoute.CAMERA2_VIEWFINDER -> {
                if (roleGraph.contains(BnCamStreamRoleIds.VIEWFINDER)) {
                    repeating += BnCamStreamRoleIds.VIEWFINDER
                }
            }

            CurrentViewfinderRequestRoute.BUFFERED_PRIMARY -> {
                require(roleGraph.contains(BnCamStreamRoleIds.PRIMARY_BUFFER)) {
                    "BUFFERED_PRIMARY viewfinder route requires PRIMARY_BUFFER"
                }
                if (rawPreviewSupportEnabled &&
                    roleGraph.contains(BnCamStreamRoleIds.RAW_PREVIEW_SUPPORT)
                ) {
                    repeating += BnCamStreamRoleIds.RAW_PREVIEW_SUPPORT
                }
            }
        }

        return RequestTargetGraph(
            repeatingRoleIds = repeating,
            captureRoleIds = capture
        )
    }
}

/**
 * Runtime graph assembler for BnCam's current concrete Camera2 outputs.
 *
 * Session outputs are stable across a normal YUV <-> selected-buffer display switch. Only the
 * request graph changes, which allows the existing CameraCaptureSession and all ownership/lifetime
 * hardening to remain intact while target bandwidth follows the visible producer route.
 */
object CurrentBnCamStreamGraphFactory {
    fun build(input: CurrentBnCamStreamGraphInput): ResolvedStreamConfiguration {
        val roles = buildList {
            input.previewExtent?.let { extent ->
                add(
                    StreamRoleSpec(
                        id = BnCamStreamRoleIds.VIEWFINDER,
                        kind = StreamRoleKind.VIEWFINDER,
                        logicalCameraId = input.logicalCameraId,
                        physicalCameraId = input.physicalRouting.physicalCameraIdFor(
                            BnCamStreamRoleIds.VIEWFINDER
                        ),
                        formatCode = CAMERA2_PRIVATE_FORMAT_CODE, // android.graphics.ImageFormat.PRIVATE
                        extent = extent,
                        outputKind = StreamOutputKind.DISPLAY_SURFACE,
                        lifetime = StreamRoleLifetime.SESSION
                    )
                )
            }

            input.primary?.let { primary ->
                val contract = requireNotNull(input.primaryContract) {
                    "PRIMARY_BUFFER requires a resolved primary stream contract"
                }
                add(
                    StreamRoleSpec(
                        id = BnCamStreamRoleIds.PRIMARY_BUFFER,
                        kind = contract.kind,
                        logicalCameraId = input.logicalCameraId,
                        physicalCameraId = input.physicalRouting.physicalCameraIdFor(
                            BnCamStreamRoleIds.PRIMARY_BUFFER
                        ),
                        formatCode = contract.effectiveFormatCode,
                        extent = contract.extent,
                        outputKind = StreamOutputKind.IMAGE_READER,
                        maxImages = primary.maxImages,
                        lifetime = StreamRoleLifetime.PIPELINE
                    )
                )
            }

            input.rawPreviewSupport?.let { support ->
                add(
                    StreamRoleSpec(
                        id = BnCamStreamRoleIds.RAW_PREVIEW_SUPPORT,
                        kind = StreamRoleKind.RAW_PREVIEW_SUPPORT,
                        logicalCameraId = input.logicalCameraId,
                        physicalCameraId = input.physicalRouting.physicalCameraIdFor(
                            BnCamStreamRoleIds.RAW_PREVIEW_SUPPORT
                        ),
                        formatCode = support.formatCode,
                        extent = support.extent,
                        outputKind = StreamOutputKind.IMAGE_READER,
                        maxImages = support.maxImages,
                        lifetime = StreamRoleLifetime.SESSION
                    )
                )
            }
        }

        val roleGraph = StreamRoleGraph(roles)
        val sessionRoleIds = roleGraph.ids()
        val requestGraph = CurrentBnCamRequestTargetPolicy.resolve(
            roleGraph = roleGraph,
            viewfinderRoute = input.viewfinderRoute,
            rawPreviewSupportEnabled = input.rawPreviewSupportRepeatingEnabled
        )

        return ResolvedStreamConfiguration(
            operationMode = input.operationMode,
            roleGraph = roleGraph,
            sessionGraph = SessionOutputGraph(sessionRoleIds),
            requestGraph = requestGraph,
            primaryStream = input.primaryContract
        )
    }
}
