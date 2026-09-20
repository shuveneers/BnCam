package com.bncam.core.engine

/**
 * How one semantic stream role chooses its Camera2 output route.
 *
 * Output routing is deliberately independent from operation mode, format, resolution, FPS and
 * request-target membership. A logical camera session may therefore contain outputs routed to the
 * logical device and/or different advertised physical children.
 */
enum class StreamPhysicalRouteMode {
    /** Preserve the currently selected physical-child route when one exists, otherwise logical. */
    INHERIT_ACTIVE_ROUTE,

    /** Keep this output on the opened logical CameraDevice. */
    LOGICAL_CAMERA,

    /** Route this output to one explicitly named advertised physical child. */
    EXPLICIT_PHYSICAL_CHILD
}

data class StreamRolePhysicalRouteRequest(
    val mode: StreamPhysicalRouteMode = StreamPhysicalRouteMode.INHERIT_ACTIVE_ROUTE,
    val explicitPhysicalCameraId: String? = null
) {
    init {
        when (mode) {
            StreamPhysicalRouteMode.EXPLICIT_PHYSICAL_CHILD ->
                require(!explicitPhysicalCameraId.isNullOrBlank()) {
                    "EXPLICIT_PHYSICAL_CHILD requires a non-blank physical camera id"
                }

            StreamPhysicalRouteMode.INHERIT_ACTIVE_ROUTE,
            StreamPhysicalRouteMode.LOGICAL_CAMERA ->
                require(explicitPhysicalCameraId == null) {
                    "$mode must not carry an explicit physical camera id"
                }
        }
    }

    companion object {
        fun inheritActive(): StreamRolePhysicalRouteRequest = StreamRolePhysicalRouteRequest()

        fun logical(): StreamRolePhysicalRouteRequest = StreamRolePhysicalRouteRequest(
            mode = StreamPhysicalRouteMode.LOGICAL_CAMERA
        )

        fun physical(cameraId: String): StreamRolePhysicalRouteRequest = StreamRolePhysicalRouteRequest(
            mode = StreamPhysicalRouteMode.EXPLICIT_PHYSICAL_CHILD,
            explicitPhysicalCameraId = cameraId
        )
    }
}

data class ResolvedStreamRolePhysicalRoute(
    val roleId: String,
    val logicalCameraId: String,
    val requestedMode: StreamPhysicalRouteMode,
    val requestedPhysicalCameraId: String?,
    /** null means the output remains on the logical CameraDevice. */
    val physicalCameraId: String?,
    val reason: String
) {
    init {
        require(roleId.isNotBlank()) { "roleId must not be blank" }
        require(logicalCameraId.isNotBlank()) { "logicalCameraId must not be blank" }
        require(physicalCameraId == null || physicalCameraId != logicalCameraId) {
            "A physical output route must identify a child, not the opened logical camera itself"
        }
    }
}

data class StreamPhysicalRoutingPlan(
    val logicalCameraId: String,
    val advertisedPhysicalCameraIds: Set<String>,
    val routesByRoleId: Map<String, ResolvedStreamRolePhysicalRoute>
) {
    init {
        require(logicalCameraId.isNotBlank()) { "logicalCameraId must not be blank" }
        require(advertisedPhysicalCameraIds.none { it.isBlank() }) {
            "advertised physical camera ids must not be blank"
        }
        require(logicalCameraId !in advertisedPhysicalCameraIds) {
            "opened logical camera id must not be listed as its own physical child"
        }
        routesByRoleId.forEach { (roleId, route) ->
            require(roleId == route.roleId) { "routing map key $roleId does not match route ${route.roleId}" }
            require(route.logicalCameraId == logicalCameraId) {
                "role $roleId belongs to logical ${route.logicalCameraId}, expected $logicalCameraId"
            }
            route.physicalCameraId?.let { physicalId ->
                require(physicalId in advertisedPhysicalCameraIds) {
                    "role $roleId resolved to non-advertised physical child $physicalId"
                }
            }
        }
    }

    fun route(roleId: String): ResolvedStreamRolePhysicalRoute? = routesByRoleId[roleId]

    fun requireRoute(roleId: String): ResolvedStreamRolePhysicalRoute =
        requireNotNull(route(roleId)) { "No physical routing decision exists for stream role $roleId" }

    fun physicalCameraIdFor(roleId: String): String? = requireRoute(roleId).physicalCameraId

    val routedPhysicalCameraIds: Set<String>
        get() = routesByRoleId.values.mapNotNullTo(linkedSetOf()) { it.physicalCameraId }

    fun summary(): String = routesByRoleId.values
        .sortedBy { it.roleId }
        .joinToString(",") { route ->
            "${route.roleId}->${route.physicalCameraId ?: "logical"}[${route.requestedMode}]"
        }
}

/**
 * Resolves physical-camera routing per output role.
 *
 * The policy never invents a physical-camera fallback. A requested child must be present in the
 * dynamically discovered `CameraCharacteristics.getPhysicalCameraIds()` set for the opened logical
 * camera or resolution fails before Camera2 session creation.
 */
object StreamPhysicalRoutingPolicy {
    fun resolve(
        logicalCameraId: String,
        advertisedPhysicalCameraIds: Set<String>,
        activePhysicalCameraId: String?,
        roleRequests: Map<String, StreamRolePhysicalRouteRequest>
    ): StreamPhysicalRoutingPlan {
        require(logicalCameraId.isNotBlank()) { "logicalCameraId must not be blank" }
        require(roleRequests.keys.none { it.isBlank() }) { "stream role ids must not be blank" }

        val advertised = advertisedPhysicalCameraIds
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toCollection(linkedSetOf())
        require(logicalCameraId !in advertised) {
            "logical camera $logicalCameraId cannot advertise itself as a physical child"
        }

        val activePhysical = activePhysicalCameraId?.trim()?.takeIf { it.isNotEmpty() }
        if (activePhysical != null) {
            require(activePhysical != logicalCameraId) {
                "active physical camera must be a child of logical $logicalCameraId, not the logical id itself"
            }
            require(activePhysical in advertised) {
                "Active physical camera $activePhysical is not advertised by logical camera $logicalCameraId"
            }
        }

        val routes = linkedMapOf<String, ResolvedStreamRolePhysicalRoute>()
        roleRequests.forEach { (roleId, request) ->
            val resolvedPhysicalId = when (request.mode) {
                StreamPhysicalRouteMode.INHERIT_ACTIVE_ROUTE -> activePhysical
                StreamPhysicalRouteMode.LOGICAL_CAMERA -> null
                StreamPhysicalRouteMode.EXPLICIT_PHYSICAL_CHILD -> {
                    val requested = requireNotNull(request.explicitPhysicalCameraId)
                    require(requested != logicalCameraId) {
                        "Role $roleId requested logical camera $logicalCameraId as a physical child"
                    }
                    require(requested in advertised) {
                        "Role $roleId requested physical child $requested, but logical camera " +
                            "$logicalCameraId advertises ${advertised.sorted()}"
                    }
                    requested
                }
            }

            val reason = when (request.mode) {
                StreamPhysicalRouteMode.INHERIT_ACTIVE_ROUTE -> if (resolvedPhysicalId == null) {
                    "No selected physical child; output remains on the opened logical CameraDevice."
                } else {
                    "Inherited the selected physical-child route $resolvedPhysicalId."
                }

                StreamPhysicalRouteMode.LOGICAL_CAMERA ->
                    "Role explicitly remains on the opened logical CameraDevice."

                StreamPhysicalRouteMode.EXPLICIT_PHYSICAL_CHILD ->
                    "Role explicitly targets advertised physical child $resolvedPhysicalId."
            }

            routes[roleId] = ResolvedStreamRolePhysicalRoute(
                roleId = roleId,
                logicalCameraId = logicalCameraId,
                requestedMode = request.mode,
                requestedPhysicalCameraId = request.explicitPhysicalCameraId,
                physicalCameraId = resolvedPhysicalId,
                reason = reason
            )
        }

        return StreamPhysicalRoutingPlan(
            logicalCameraId = logicalCameraId,
            advertisedPhysicalCameraIds = advertised,
            routesByRoleId = routes
        )
    }
}
