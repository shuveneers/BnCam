package com.bncam.core.compute

import com.bncam.core.vulkan.VulkanRuntimeOwner
import com.bncam.core.vulkan.VulkanRuntimeState

/**
 * Backend-neutral vocabulary for the persistent compute runtime.
 *
 * This contract does not wrap the disconnected GLES functions. A backend becomes executable only
 * after it owns resources, graph submission, synchronization and typed transfer reporting.
 */
enum class ComputeBackendKind { CPU_NATIVE_OPENCV, VULKAN }

enum class ComputeBackendStatus { ACTIVE, PREPARED, UNAVAILABLE }

enum class ComputeCapability {
    CPU_MAT_INPUT,
    AHARDWAREBUFFER_IMPORT,
    PERSISTENT_PIPELINES,
    PERSISTENT_IMAGE_POOL,
    ASYNC_GRAPH_SUBMISSION,
    EXPLICIT_SYNCHRONIZATION,
    GPU_IMAGE_EXPORT
}

enum class ComputePixelFormat {
    RAW16,
    RGB_FLOAT32,
    RGBA_FLOAT32,
    BGR8,
    RGBA8,
    YUV_420_888
}

data class ComputeImage(
    val identifier: String,
    val width: Int,
    val height: Int,
    val format: ComputePixelFormat,
    val byteCount: Long,
    val externallyOwned: Boolean
)

data class ComputeGraphNode(
    val identifier: String,
    val operation: String,
    val inputIds: List<String>,
    val outputIds: List<String>
)

data class ComputeGraph(
    val identifier: String,
    val nodes: List<ComputeGraphNode>
)

enum class ComputeCrossingDirection { CPU_TO_BACKEND, BACKEND_TO_CPU, EXTERNAL_IMPORT, EXTERNAL_EXPORT }

data class ComputeCrossing(
    val direction: ComputeCrossingDirection,
    val imageId: String,
    val byteCount: Long,
    val reason: String
)

data class ComputeExecutionResult(
    val backendId: String,
    val succeeded: Boolean,
    val outputImages: List<ComputeImage>,
    val crossings: List<ComputeCrossing>,
    val warnings: List<String>,
    val failureReason: String? = null
)

data class ComputeBackendDescriptor(
    val id: String,
    val displayName: String,
    val kind: ComputeBackendKind,
    val status: ComputeBackendStatus,
    /** Target contract, not a claim that every capability is currently enabled. */
    val capabilities: Set<ComputeCapability>,
    val productionCaptureConnected: Boolean,
    val truth: String
)

data class ComputeBackendRuntimeTruth(
    val backendId: String,
    val runtimeState: String,
    val loaderAvailable: Boolean,
    val runtimeInitialized: Boolean,
    val selectedDevice: String?,
    val enabledExtensions: List<String>,
    val enabledFeatures: List<String>,
    val missingRequirements: List<String>,
    val activeProductionStages: List<String>,
    val runtimeIdentity: String,
    val instanceCreationCount: Long,
    val deviceCreationCount: Long,
    val lastFailure: String?
)

interface ComputeBackend {
    val descriptor: ComputeBackendDescriptor

    suspend fun execute(
        graph: ComputeGraph,
        inputImages: List<ComputeImage>
    ): ComputeExecutionResult
}

object ComputeBackendRegistry {
    val CPU_NATIVE_OPENCV = ComputeBackendDescriptor(
        id = "cpu_native_opencv",
        displayName = "Native CPU / OpenCV",
        kind = ComputeBackendKind.CPU_NATIVE_OPENCV,
        status = ComputeBackendStatus.ACTIVE,
        capabilities = setOf(ComputeCapability.CPU_MAT_INPUT),
        productionCaptureConnected = true,
        truth = "Current production alignment, fusion, demosaic and ISP execution."
    )

    val VULKAN = ComputeBackendDescriptor(
        id = "vulkan",
        displayName = "Vulkan",
        kind = ComputeBackendKind.VULKAN,
        status = ComputeBackendStatus.ACTIVE,
        capabilities = setOf(
            ComputeCapability.AHARDWAREBUFFER_IMPORT,
            ComputeCapability.PERSISTENT_PIPELINES,
            ComputeCapability.PERSISTENT_IMAGE_POOL,
            ComputeCapability.ASYNC_GRAPH_SUBMISSION,
            ComputeCapability.EXPLICIT_SYNCHRONIZATION,
            ComputeCapability.GPU_IMAGE_EXPORT
        ),
        productionCaptureConnected = true,
        truth = "Authoritative Vulkan runtime active for production capture processing, demosaic, tone mapping, and ISP compute stages."
    )

    val descriptors = listOf(CPU_NATIVE_OPENCV, VULKAN)

    fun activeCaptureBackend(): ComputeBackendDescriptor {
        val runtime = VulkanRuntimeOwner.snapshot()
        return if (runtime.state == VulkanRuntimeState.READY) {
            VULKAN
        } else {
            CPU_NATIVE_OPENCV
        }
    }

    fun descriptor(id: String): ComputeBackendDescriptor? =
        descriptors.firstOrNull { it.id == id }

    fun vulkanRuntimeTruth(): ComputeBackendRuntimeTruth {
        val snapshot = VulkanRuntimeOwner.snapshot()
        return ComputeBackendRuntimeTruth(
            backendId = VULKAN.id,
            runtimeState = snapshot.state.name,
            loaderAvailable = snapshot.loaderAvailable,
            runtimeInitialized = snapshot.runtimeInitialized,
            selectedDevice = snapshot.selectedDevice,
            enabledExtensions = snapshot.enabledExtensions,
            enabledFeatures = snapshot.enabledFeatures,
            missingRequirements = snapshot.missingRequirements,
            activeProductionStages = snapshot.activeProductionStages,
            runtimeIdentity = snapshot.runtimeIdentity,
            instanceCreationCount = snapshot.instanceCreationCount,
            deviceCreationCount = snapshot.deviceCreationCount,
            lastFailure = snapshot.lastFailure.takeIf { it.isPresent }
                ?.let { "${it.code}:${it.message}" }
        )
    }

    fun isVulkanReadyButNotYetConnected(): Boolean {
        val runtime = VulkanRuntimeOwner.snapshot()
        return runtime.state == VulkanRuntimeState.READY &&
            runtime.activeProductionStages.isEmpty()
    }
}
