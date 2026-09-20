package com.bncam.core.engine

import android.media.ImageReader
import android.view.Surface

/** Concrete Camera2 output bound to one semantic role for exactly one session attempt. */
data class StreamSurfaceBinding(
    val role: StreamRoleSpec,
    val runtimeName: String,
    val surface: Surface,
    val imageReader: ImageReader? = null
) {
    init {
        require(runtimeName.isNotBlank()) { "runtimeName must not be blank" }
        when (role.outputKind) {
            StreamOutputKind.IMAGE_READER -> {
                val reader = requireNotNull(imageReader) {
                    "ImageReader role ${role.id} requires an ImageReader binding"
                }
                require(reader.imageFormat == role.formatCode) {
                    "ImageReader role ${role.id} format ${reader.imageFormat} does not match resolved role ${role.formatCode}"
                }
                require(reader.width == role.extent.width && reader.height == role.extent.height) {
                    "ImageReader role ${role.id} extent ${reader.width}x${reader.height} does not match resolved role ${role.extent.width}x${role.extent.height}"
                }
                role.maxImages?.let { expectedMaxImages ->
                    require(reader.maxImages == expectedMaxImages) {
                        "ImageReader role ${role.id} maxImages ${reader.maxImages} does not match resolved role $expectedMaxImages"
                    }
                }
            }
            StreamOutputKind.DISPLAY_SURFACE,
            StreamOutputKind.EXTERNAL_SURFACE ->
                require(imageReader == null) { "Non-ImageReader role ${role.id} must not own an ImageReader binding" }
        }
    }
}

/**
 * Binds a resolved semantic graph to the exact immutable Surface snapshot used for Camera2.
 *
 * Session outputs and request targets are queried through different methods on purpose. This keeps
 * the Camera2 runtime from drifting back to the old "every configured output is every request
 * target" assumption when support/analysis roles are introduced.
 */
class BoundStreamConfiguration(
    val configuration: ResolvedStreamConfiguration,
    bindings: List<StreamSurfaceBinding>
) {
    private val bindingsByRoleId: Map<String, StreamSurfaceBinding>

    init {
        val ids = bindings.map { it.role.id }
        require(ids.size == ids.toSet().size) { "stream Surface bindings must have unique role IDs" }

        val configuredIds = configuration.sessionGraph.roleIds
        val boundIds = ids.toSet()
        require(boundIds == configuredIds) {
            "bound Surface roles must exactly match session outputs; missing=${(configuredIds - boundIds).sorted()} " +
                "extra=${(boundIds - configuredIds).sorted()}"
        }

        bindings.forEach { binding ->
            val resolvedRole = configuration.roleGraph.role(binding.role.id)
                ?: error("binding references unknown role ${binding.role.id}")
            require(resolvedRole == binding.role) {
                "binding role ${binding.role.id} does not match resolved role specification"
            }
        }
        bindingsByRoleId = bindings.associateBy { it.role.id }
    }

    fun binding(roleId: String): StreamSurfaceBinding? = bindingsByRoleId[roleId]

    fun sessionBindings(): List<StreamSurfaceBinding> =
        configuration.sessionRoles().map { role -> bindingsByRoleId.getValue(role.id) }

    fun repeatingBindings(): List<StreamSurfaceBinding> =
        configuration.repeatingRoles().map { role -> bindingsByRoleId.getValue(role.id) }

    fun captureBindings(): List<StreamSurfaceBinding> =
        configuration.captureRoles().map { role -> bindingsByRoleId.getValue(role.id) }

    /**
     * Rebinds only request-target policy while preserving the exact configured Surface snapshot.
     *
     * Normal YUV <-> selected-buffer viewfinder switching changes CaptureRequest membership, not
     * CaptureSession output ownership. Reconstructing the bound configuration keeps that boundary
     * explicit and re-validates that every requested target still belongs to this session.
     */
    fun withRequestGraph(requestGraph: RequestTargetGraph): BoundStreamConfiguration =
        BoundStreamConfiguration(
            configuration = configuration.copy(requestGraph = requestGraph),
            bindings = sessionBindings()
        )
}
