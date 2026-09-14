package com.bncam.ui.screens.capture

import android.content.Context
import android.content.pm.ApplicationInfo
import android.opengl.EGL14
import android.opengl.GLES20
import android.util.Log
import java.util.concurrent.atomic.AtomicInteger
import com.bncam.core.debug.DiagnosticsAggregator
import com.bncam.core.vulkan.VulkanRuntimeOwner

/**
 * Read-only capability probe for the active RAW-preview
 * AHardwareBuffer -> Vulkan -> EGLImage/GLES presentation path. The renderer activates the
 * GPU-resident output route only when every required Vulkan/EGL/GLES capability is present;
 * otherwise it keeps the Vulkan processing pipeline but uses the CPU-visible RGBA presentation
 * path for that session.
 *
 * Must be called from the active GLES thread so GL/EGL extension strings describe the actual
 * viewfinder context rather than an assumed device capability.
 */
data class RawPreviewInteropCapabilitySnapshot(
    val eglGeneration: Int,
    val vulkanAhbExternalMemory: Boolean,
    val vulkanOutputAhbUsage: Long,
    val eglImageBase: Boolean,
    val eglAndroidNativeBuffer: Boolean,
    val eglAndroidGetNativeClientBuffer: Boolean,
    val glOesEglImage: Boolean,
    val glOesEglImageExternal: Boolean,
    val eglFenceSync: Boolean,
    val readyForAhbEglImageInterop: Boolean,
    val blockers: List<String>
) {
    fun toReport(): String = buildString {
        appendLine("RAW_PREVIEW_INTEROP_CAPABILITIES")
        appendLine("eglGeneration=$eglGeneration")
        appendLine("vulkanAhbExternalMemory=$vulkanAhbExternalMemory")
        appendLine("vulkanOutputAhbUsage=0x${vulkanOutputAhbUsage.toString(16)}")
        appendLine("eglImageBase=$eglImageBase")
        appendLine("eglAndroidNativeBuffer=$eglAndroidNativeBuffer")
        appendLine("eglAndroidGetNativeClientBuffer=$eglAndroidGetNativeClientBuffer")
        appendLine("glOesEglImage=$glOesEglImage")
        appendLine("glOesEglImageExternal=$glOesEglImageExternal")
        appendLine("eglFenceSync=$eglFenceSync")
        appendLine("readyForAhbEglImageInterop=$readyForAhbEglImageInterop")
        appendLine("blockers=${if (blockers.isEmpty()) "none" else blockers.joinToString(",")}")
        appendLine("activePath=${RawPreviewInteropCapabilities.activePath}")
    }
}

object RawPreviewInteropCapabilities {
    private val eglGenerationCounter = AtomicInteger(0)
    @Volatile
    var latest: RawPreviewInteropCapabilitySnapshot? = null
        private set

    @Volatile
    var activePath: String = "CPU_VISIBLE_RGBA_TO_GLES"
        private set

    fun recordActivePath(path: String) {
        activePath = path
    }

    fun probeOnGlThread(context: Context): RawPreviewInteropCapabilitySnapshot {
        val eglGeneration = eglGenerationCounter.incrementAndGet()
        fun tokens(value: String?): Set<String> = value.orEmpty()
            .split(' ')
            .asSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .toSet()

        val vulkanExtensions = runCatching { VulkanRuntimeOwner.snapshot().enabledExtensions.toSet() }
            .getOrDefault(emptySet())
        val eglExtensions = runCatching {
            tokens(EGL14.eglQueryString(EGL14.eglGetCurrentDisplay(), EGL14.EGL_EXTENSIONS))
        }.getOrDefault(emptySet())
        val glExtensions = runCatching { tokens(GLES20.glGetString(GLES20.GL_EXTENSIONS)) }
            .getOrDefault(emptySet())

        val vulkanAhb = "VK_ANDROID_external_memory_android_hardware_buffer" in vulkanExtensions
        val vulkanOutputAhbUsage = if (vulkanAhb) {
            VulkanRuntimeOwner.rawPreviewOutputHardwareBufferUsage()
        } else {
            0L
        }
        val eglImageBase = "EGL_KHR_image_base" in eglExtensions || "EGL_KHR_image" in eglExtensions
        val eglNativeBuffer = "EGL_ANDROID_image_native_buffer" in eglExtensions
        val eglGetNativeClientBuffer = "EGL_ANDROID_get_native_client_buffer" in eglExtensions
        val glEglImage = "GL_OES_EGL_image" in glExtensions
        val glEglImageExternal = "GL_OES_EGL_image_external" in glExtensions ||
            "GL_OES_EGL_image_external_essl3" in glExtensions
        val eglFenceSync = "EGL_KHR_fence_sync" in eglExtensions

        val blockers = buildList {
            if (!vulkanAhb) add("VK_ANDROID_external_memory_android_hardware_buffer")
            if (vulkanAhb && vulkanOutputAhbUsage == 0L) add("VULKAN_RGBA8_AHB_STORAGE_SAMPLED_USAGE")
            if (!eglImageBase) add("EGL_KHR_image_base")
            if (!eglNativeBuffer) add("EGL_ANDROID_image_native_buffer")
            if (!eglGetNativeClientBuffer) add("EGL_ANDROID_get_native_client_buffer")
            if (!glEglImage) add("GL_OES_EGL_image")
            if (!eglFenceSync) add("EGL_KHR_fence_sync")
        }
        val snapshot = RawPreviewInteropCapabilitySnapshot(
            eglGeneration = eglGeneration,
            vulkanAhbExternalMemory = vulkanAhb,
            vulkanOutputAhbUsage = vulkanOutputAhbUsage,
            eglImageBase = eglImageBase,
            eglAndroidNativeBuffer = eglNativeBuffer,
            eglAndroidGetNativeClientBuffer = eglGetNativeClientBuffer,
            glOesEglImage = glEglImage,
            glOesEglImageExternal = glEglImageExternal,
            eglFenceSync = eglFenceSync,
            readyForAhbEglImageInterop = blockers.isEmpty(),
            blockers = blockers
        )
        latest = snapshot
        Log.i(TAG, snapshot.toReport().lineSequence().joinToString(" "))

        val app = context.applicationContext
        if ((app.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            DiagnosticsAggregator.initialize(app)
            DiagnosticsAggregator.record(
                stream = DiagnosticsAggregator.Stream.CAMERA,
                scope = "VIEWFINDER",
                section = "RAW PREVIEW INTEROP CAPABILITIES",
                content = snapshot.toReport()
            )
        }
        return snapshot
    }

    private const val TAG = "BnCamRawPreviewInterop"
}
