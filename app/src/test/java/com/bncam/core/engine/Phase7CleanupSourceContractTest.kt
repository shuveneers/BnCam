package com.bncam.core.engine

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Phase7CleanupSourceContractTest {
    private val appDir: File by lazy {
        sequenceOf(File("."), File("app"))
            .firstOrNull { File(it, "src/main/java/com/bncam/core/engine/BnCameraManager.kt").isFile }
            ?: error("Cannot locate app module from ${File(".").absolutePath}")
    }

    @Test
    fun settingsNavigationDetachArchitectureIsRemoved() {
        val manager = File(appDir, "src/main/java/com/bncam/core/engine/BnCameraManager.kt").readText()
        val screen = File(appDir, "src/main/java/com/bncam/ui/screens/capture/CameraScreen.kt").readText()

        assertFalse(manager.contains("detachPreviewForInAppNavigation"))
        assertFalse(manager.contains("isWarmPipelineDetachedForInAppNavigation"))
        assertFalse(manager.contains("BUFFER_ONLY"))
        assertFalse(manager.contains("NAVIGATION_OFFSCREEN_RING_CAPACITY"))
        assertFalse(manager.contains("navigationFullRingCapacity"))
        assertFalse(screen.contains("isWarmPipelineDetachedForInAppNavigation"))
        assertTrue(manager.contains("lastPreviewSurface !== previewSurface"))
        assertTrue(screen.contains("bnCameraManager.closeCameraForSurfaceRelease("))
    }

    @Test
    fun disconnectedLegacyGlesComputeEngineIsGone() {
        val cmake = File(appDir, "src/main/cpp/CMakeLists.txt").readText()
        val native = File(appDir, "src/main/cpp/native-lib.cpp").readText()
        val imageUtils = File(appDir, "src/main/java/com/bncam/core/engine/ImageUtils.kt").readText()
        val manager = File(appDir, "src/main/java/com/bncam/core/engine/BnCameraManager.kt").readText()
        val compute = File(appDir, "src/main/java/com/bncam/core/compute/ComputeBackend.kt").readText()

        assertFalse(File(appDir, "src/main/cpp/GpuIsp.cpp").exists())
        assertFalse(File(appDir, "src/main/cpp/GpuIsp.h").exists())
        assertFalse(cmake.contains("GpuIsp.cpp"))
        assertFalse(native.contains("GpuIsp"))
        assertFalse(imageUtils.contains("initializeGpuEngine"))
        assertFalse(imageUtils.contains("releaseGpuEngine"))
        assertFalse(manager.contains("initializeGpuEngine"))
        assertFalse(manager.contains("releaseGpuEngine"))
        assertFalse(compute.contains("GLES31_LEGACY"))
        assertTrue(compute.contains("val descriptors = listOf(CPU_NATIVE_OPENCV, VULKAN)"))
    }

    @Test
    fun rawCpuReferenceIsCompileTimeOnlyAndNotProductionFallback() {
        val rawPreview = File(appDir, "src/main/cpp/RawPreview.cpp").readText()
        val cmake = File(appDir, "src/main/cpp/CMakeLists.txt").readText()

        assertTrue(cmake.contains("option(BNCAM_ENABLE_RAW_PREVIEW_CPU_REFERENCE"))
        assertTrue(cmake.contains("OFF)"))
        assertTrue(rawPreview.contains("#if defined(BNCAM_ENABLE_RAW_PREVIEW_CPU_REFERENCE)\n#include \"Demosaic.h\""))
        assertTrue(rawPreview.contains("#if !defined(BNCAM_ENABLE_RAW_PREVIEW_CPU_REFERENCE)"))
        assertTrue(rawPreview.contains("GPU_PREVIEW_FAILED_NO_CPU_FALLBACK"))
        val productionPrelude = rawPreview.substringBefore("#if defined(BNCAM_ENABLE_RAW_PREVIEW_CPU_REFERENCE)\n#include \"Demosaic.h\"")
        assertFalse(productionPrelude.contains("opencv2/"))
    }

    @Test
    fun previewTelemetryJniUsesActualImageUtilsPackage() {
        val native = File(appDir, "src/main/cpp/native-lib.cpp").readText()
        val imageUtils = File(appDir, "src/main/java/com/bncam/core/engine/ImageUtils.kt").readText()

        assertTrue(imageUtils.contains("external fun getPreviewBufferTelemetryNative()"))
        assertTrue(native.contains("Java_com_bncam_core_engine_ImageUtils_getPreviewBufferTelemetryNative"))
        assertFalse(native.contains("Java_com_bncam_core_utils_ImageUtils_getPreviewBufferTelemetryNative"))
    }
}
