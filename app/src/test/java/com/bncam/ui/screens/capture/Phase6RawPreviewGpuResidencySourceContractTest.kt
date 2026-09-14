package com.bncam.ui.screens.capture

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Phase6RawPreviewGpuResidencySourceContractTest {
    private val appDir: File = sequenceOf(File("."), File("app"))
        .firstOrNull { File(it, "src/main/cpp/CMakeLists.txt").isFile }
        ?: error("Cannot locate app module from ${File(".").absolutePath}")

    private fun source(relative: String): String = File(appDir, relative).readText()

    @Test
    fun `GPU resident RAW output uses Vulkan storage image and EGLImage without full RGBA readback`() {
        val cmake = source("src/main/cpp/CMakeLists.txt")
        val shader = source("src/main/cpp/vulkan/shaders/raw_preview_image.comp")
        val backend = source("src/main/cpp/vulkan/VulkanRawPreviewBackend.cpp")
        val view = source("src/main/java/com/bncam/ui/screens/capture/FocusPeakingView.kt")

        assertTrue(cmake.contains("raw_preview_image.comp"))
        assertTrue(cmake.contains("bncam_raw_preview_image_shader"))
        assertTrue(shader.contains("rgba8"))
        assertTrue(shader.contains("imageStore"))
        assertTrue(backend.contains("ensureImportedOutputLocked"))
        assertTrue(backend.contains("gpuResidentOutput ? statisticsBytes : rgbaBytes + statisticsBytes"))
        assertTrue(view.contains("bindRawPreviewHardwareBufferToCurrentTexture"))

        val handoff = view.substringAfter("val handoffSucceeded =")
            .substringBefore("val handoffError")
        val gpuBranch = handoff.substringBefore("} else {")
        val cpuVisibleBranch = handoff.substringAfter("} else {")
        assertFalse(gpuBranch.contains("glTexSubImage2D"))
        assertFalse(gpuBranch.contains("glTexImage2D"))
        assertTrue(cpuVisibleBranch.contains("glTexSubImage2D") || cpuVisibleBranch.contains("glTexImage2D"))
    }

    @Test
    fun `cross API output slots are not recycled before GLES completion`() {
        val backend = source("src/main/cpp/vulkan/VulkanRawPreviewBackend.cpp")
        val renderer = source("src/main/java/com/bncam/ui/screens/capture/RawPreviewRenderer.kt")
        val view = source("src/main/java/com/bncam/ui/screens/capture/FocusPeakingView.kt")
        val fenceBackend = source("src/main/java/com/bncam/ui/screens/capture/RawPreviewFenceBackend.kt")

        assertTrue(backend.contains("VK_QUEUE_FAMILY_EXTERNAL"))
        assertTrue(view.contains("closeAfterGlSampled"))
        assertTrue(fenceBackend.contains("ImageUtils.createRawPreviewGlFence"))
        assertTrue(fenceBackend.contains("ImageUtils.pollRawPreviewGlFence"))
        assertTrue(renderer.contains("fenceBackend.pollFence(handle)"))
        assertTrue(renderer.contains("pendingGpuOutputSlots"))
        assertTrue(renderer.contains("slot.pendingGlFenceHandle"))
        assertTrue(renderer.contains("slot.quarantineGpuBuffer()"))
        assertTrue(renderer.contains("RawPreviewGpuInteropStateMachine()"))
        assertFalse(renderer.contains("gpuResidentOutputDisabled"))
        assertTrue(renderer.contains("closeAfterGlInteropFailure"))
    }

    @Test
    fun `GPU resident analysis reads only compact statistics and optional decimated luma`() {
        val backend = source("src/main/cpp/vulkan/VulkanRawPreviewBackend.cpp")
        val shader = source("src/main/cpp/vulkan/shaders/raw_preview_image.comp")
        val manager = source("src/main/java/com/bncam/core/engine/BnCameraManager.kt")
        val renderer = source("src/main/java/com/bncam/ui/screens/capture/RawPreviewRenderer.kt")

        assertTrue(backend.contains("PREVIEW_STATS_BASE_WORDS"))
        assertTrue(backend.contains("request.previewWidth / 4u"))
        assertTrue(backend.contains("request.previewHeight / 4u"))
        assertTrue(shader.contains("analysis"))
        assertTrue(renderer.contains("analysisLuma"))
        assertTrue(manager.contains("frame.displayLumaHistogram16"))
        assertTrue(manager.contains("if (frame.gpuResidentOutputUsed) return null"))
    }

    @Test
    fun `normal RAW input reaches Vulkan as AHardwareBuffer before any CPU map`() {
        val rawPreview = source("src/main/cpp/RawPreview.cpp")
        val backend = source("src/main/cpp/vulkan/VulkanRawPreviewBackend.cpp")

        assertTrue(rawPreview.contains("previewRequest.inputHardwareBuffer = buffer"))
        assertTrue(rawPreview.contains("previewRequest.rawData = nullptr"))
        val gpuRequest = rawPreview.substringAfter("RawPreviewGpuRequest previewRequest")
            .substringBefore("const auto previewGpu = runtime.executeRawPreview(previewRequest)")
        assertFalse(gpuRequest.contains("lockRaw("))

        assertTrue(backend.contains("tryImportInputBufferLocked"))
        assertFalse(backend.contains("request.rawData == nullptr) { return"))
    }

    @Test
    fun `direct RAW buffer import is enabled only for an actually byte addressable AHardwareBuffer`() {
        val backend = source("src/main/cpp/vulkan/VulkanRawPreviewBackend.cpp")
        val bootstrap = source("src/main/cpp/vulkan/VulkanRuntimeBootstrap.cpp")

        assertTrue(backend.contains("desc.format != AHARDWAREBUFFER_FORMAT_BLOB"))
        assertTrue(backend.contains("AHARDWAREBUFFER_USAGE_GPU_DATA_BUFFER"))
        assertTrue(backend.contains("VK_EXTERNAL_MEMORY_FEATURE_IMPORTABLE_BIT"))
        assertTrue(backend.contains("RAW_PREVIEW_INPUT_FOREIGN_QUEUE_FAMILY_UNAVAILABLE"))
        assertTrue(backend.contains("VK_QUEUE_FAMILY_FOREIGN_EXT"))
        assertTrue(bootstrap.contains("VK_EXT_queue_family_foreign"))
        assertTrue(backend.contains("VkImportAndroidHardwareBufferInfoANDROID"))
    }

    @Test
    fun `unsupported direct RAW import has one layout validated byte staging fallback only`() {
        val backend = source("src/main/cpp/vulkan/VulkanRawPreviewBackend.cpp")
        val rawPreview = source("src/main/cpp/RawPreview.cpp")

        assertTrue(backend.contains("AHardwareBuffer_lockPlanes"))
        assertTrue(backend.contains("RAW_PREVIEW_INPUT_PLANE_ROW_STRIDE_MISMATCH"))
        assertTrue(backend.contains("RAW_PREVIEW_INPUT_PLANE_PIXEL_STRIDE_MISMATCH"))
        assertTrue(backend.contains("std::memcpy(inputStaging_.mapped, rawSource"))
        assertTrue(backend.contains("AHardwareBuffer_unlock"))
        assertTrue(backend.contains("toneLutBuffer_"))
        assertTrue(backend.contains("dstBinding = 3u"))
        assertFalse(backend.contains("paddedRawBytes"))

        // Heavy OpenCV/CPU development remains compile-time reference tooling only.
        assertTrue(rawPreview.contains("#if !defined(BNCAM_ENABLE_RAW_PREVIEW_CPU_REFERENCE)"))
        assertTrue(rawPreview.contains("GPU_PREVIEW_FAILED_NO_CPU_FALLBACK"))
    }

    @Test
    fun `authoritative ZSL RAW reader is not mutated to unsupported GPU consumer usage`() {
        val manager = source("src/main/java/com/bncam/core/engine/BnCameraManager.kt")

        assertTrue(manager.contains("acquireNextImage"))
        assertFalse(manager.contains("HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE"))
        assertFalse(manager.contains("HardwareBuffer.USAGE_GPU_DATA_BUFFER"))
        assertFalse(manager.contains("AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE"))
    }

    @Test
    fun `RAW interop telemetry exposes the actual input buffer contract`() {
        val native = source("src/main/cpp/native-lib.cpp")
        val renderer = source("src/main/java/com/bncam/ui/screens/capture/RawPreviewRenderer.kt")
        val header = source("src/main/cpp/vulkan/VulkanRawPreviewBackend.h")

        assertTrue(header.contains("inputAhbFormat"))
        assertTrue(header.contains("inputAhbUsage"))
        assertTrue(header.contains("inputInteropStatus"))
        assertTrue(native.contains("jint expandedValues[EXPANDED_RESULT_SIZE]"))
        assertTrue(native.contains("expandedValues[48]"))
        assertTrue(renderer.contains("result.getOrElse(45)"))
        assertTrue(renderer.contains("result.getOrElse(46)"))
        assertTrue(renderer.contains("result.getOrElse(47)"))
        assertTrue(renderer.contains("result.getOrElse(48)"))
        assertTrue(renderer.contains("directHardwareBufferInputUsed"))
    }
}
