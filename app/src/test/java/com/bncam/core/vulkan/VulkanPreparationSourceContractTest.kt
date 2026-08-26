package com.bncam.core.vulkan

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VulkanPreparationSourceContractTest {
    private val appDir: File = sequenceOf(File("."), File("app"))
        .firstOrNull { File(it, "src/main/cpp/CMakeLists.txt").isFile }
        ?: error("Cannot locate app module from ${File(".").absolutePath}")

    @Test
    fun applicationOwnsRuntimeAndCaptureObjectsDoNot() {
        val application = File(appDir, "src/main/java/com/bncam/BnCamApplication.kt").readText()
        assertTrue(application.contains("VulkanRuntimeOwner.attach(applicationContext)"))

        listOf(
            "src/main/java/com/bncam/core/engine/BnCameraManager.kt",
            "src/main/java/com/bncam/core/runners/SingleFrameRunner.kt",
            "src/main/java/com/bncam/core/runners/MultiFrameRunner.kt",
            "src/main/java/com/bncam/ui/screens/capture/CameraScreen.kt"
        ).forEach { relative ->
            val source = File(appDir, relative).readText()
            assertFalse("$relative must not initialize Vulkan", source.contains("VulkanRuntimeOwner.initialize"))
            assertFalse("$relative must not own native runtime", source.contains("VulkanRuntime::instance"))
        }
    }

    @Test
    fun temporaryCapabilityAndBenchmarkPathsCannotCreateCompetingInstances() {
        val capability = File(appDir, "src/main/cpp/VulkanCapabilities.cpp").readText()
        val benchmark = File(appDir, "src/main/cpp/VulkanBenchmarkKernels.cpp").readText()
        assertFalse(capability.contains("vkCreateInstance"))
        assertFalse(benchmark.contains("vkCreateInstance"))
        assertTrue(capability.contains("VulkanRuntime::instance"))
        assertTrue(benchmark.contains("RETIRED"))
    }

    @Test
    fun bootstrapImplementsRealVulkanInitializationAndDestruction() {
        val bootstrap = File(appDir, "src/main/cpp/vulkan/VulkanRuntimeBootstrap.cpp").readText()
        val runtime = File(appDir, "src/main/cpp/vulkan/VulkanRuntime.cpp").readText()
        assertTrue(bootstrap.contains("vkCreateInstance"))
        assertTrue(bootstrap.contains("vkCreateDevice"))
        assertTrue(bootstrap.contains("vkCreateCommandPool"))
        assertTrue(bootstrap.contains("vkCreateDescriptorPool"))
        assertTrue(bootstrap.contains("vkCreatePipelineCache"))
        assertTrue(bootstrap.contains("allocator.create"))
        assertTrue(runtime.contains("static VulkanRuntime runtime"))
        assertTrue(runtime.contains("activeProductionStages"))
    }

    @Test
    fun validationCallbackHasNoIoOrJni() {
        val collector = File(appDir, "src/main/cpp/vulkan/VulkanValidationCollector.cpp").readText()
        val adapter = File(appDir, "src/main/cpp/vulkan/VulkanDebugUtilsAdapter.cpp").readText()
        val combined = collector + adapter
        assertFalse(combined.contains("JNIEnv"))
        assertFalse(combined.contains("NewStringUTF"))
        assertFalse(combined.contains("ofstream"))
        assertFalse(combined.contains("fopen("))
        assertTrue(collector.contains("maxUniqueRecords_"))
        assertTrue(collector.contains("droppedMessageCount_"))
    }

    @Test
    fun buildPinsVmaAndKeepsItOptionalUntilFetched() {
        val cmake = File(appDir, "src/main/cpp/CMakeLists.txt").readText()
        val version = File(appDir, "src/main/cpp/third_party/vma-3.3.0/VERSION.txt").readText()
        val fetch = File(appDir, "tools/fetch_vma.ps1").readText()
        assertTrue(cmake.contains("BNCAM_VMA_HEADER_AVAILABLE"))
        assertTrue(cmake.contains("vma-3.3.0"))
        assertTrue(version.contains("3.3.0"))
        assertTrue(fetch.contains("VulkanMemoryAllocator/v3.3.0"))
    }
}
