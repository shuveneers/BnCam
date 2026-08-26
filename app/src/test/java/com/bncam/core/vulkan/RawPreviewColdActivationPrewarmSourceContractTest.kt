package com.bncam.core.vulkan

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RawPreviewColdActivationPrewarmSourceContractTest {
    private val appDir: File = sequenceOf(File("."), File("app"))
        .firstOrNull { File(it, "src/main/cpp/CMakeLists.txt").isFile }
        ?: error("Cannot locate app module from ${File(".").absolutePath}")

    private fun source(relative: String): String = File(appDir, relative).readText()

    @Test
    fun `raw profile schedules preview backend preparation before selected buffer`() {
        val manager = source("src/main/java/com/bncam/core/engine/BnCameraManager.kt")
        val renderer = source("src/main/java/com/bncam/ui/screens/capture/RawPreviewRenderer.kt")

        assertTrue(manager.contains("rawPreviewRenderer.prepareBackendAsync("))
        assertTrue(manager.contains("requestedIdentity.bufferFormat == ImageFormat.RAW10"))
        assertTrue(manager.contains("requestedIdentity.bufferFormat == ImageFormat.RAW_SENSOR"))
        assertTrue(renderer.contains("backendWarmupExecutor.execute"))
        assertTrue(renderer.contains("BnCamRawPreviewWarmup"))
        assertTrue(renderer.contains("VulkanRuntimeOwner.prepareRawPreviewBackend()"))
        val prepareBlock = renderer.substringAfter("fun prepareBackendAsync(")
            .substringBefore("fun updateWhiteBalanceGains")
        assertFalse(
            "cold pipeline compilation must not occupy the one-and-only frame drain worker",
            prepareBlock.contains("executor.execute")
        )
        assertTrue(manager.contains("prepareRawPreviewBackendAsync("))
        assertTrue(manager.contains("primeRawViewfinderFromWarmBuffer("))
    }

    @Test
    fun `prewarm uses isolated preview command pool without submission mutex`() {
        val runtime = source("src/main/cpp/vulkan/VulkanRuntime.cpp")
        val prepare = runtime.substringAfter("bool VulkanRuntime::prepareRawPreviewBackend() noexcept {")
            .substringBefore("RawPreviewGpuResult VulkanRuntime::executeRawPreview")

        assertTrue(prepare.contains("handles_.previewCommandPool"))
        assertTrue(prepare.contains("rawPreviewBackend_.prepare(device, commandPool)"))
        assertFalse(prepare.contains("submissionMutex_"))
        assertFalse(prepare.contains("previewSubmissionMutex_"))
    }

    @Test
    fun `prewarm bypasses shared pipeline cache lock and legacy pipeline remains lazy`() {
        val backend = source("src/main/cpp/vulkan/VulkanRawPreviewBackend.cpp")
        val registry = source("src/main/cpp/vulkan/VulkanPipelineCacheRegistry.h")

        assertTrue(backend.contains("initializeLocked(device, commandPool, diagnostics, failureReason, false)"))
        assertTrue(registry.contains("bool usePublishedCache = true"))
        assertTrue(registry.contains("if (!usePublishedCache)"))
        assertTrue(backend.contains("if (!gpuResidentOutput && pipeline_ == VK_NULL_HANDLE)"))
        assertTrue(backend.contains("ensureLegacyPipelineLocked(device, result, failure, true)"))
    }
}
