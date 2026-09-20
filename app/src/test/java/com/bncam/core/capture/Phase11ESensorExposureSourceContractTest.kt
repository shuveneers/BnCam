package com.bncam.core.capture

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Phase11ESensorExposureSourceContractTest {
    private val appDir: File = sequenceOf(File("."), File("app"))
        .firstOrNull { File(it, "src/main/java/com/bncam/core/engine/BnCameraManager.kt").isFile }
        ?: error("Cannot locate app module from ${File(".").absolutePath}")

    private fun source(relative: String): String = File(appDir, relative).readText()

    @Test
    fun `legacy pseudo ETTR runtime is removed`() {
        val legacy = File(appDir, "src/main/java/com/bncam/core/capture/EttrExposureStrategy.kt")
        assertFalse("Legacy EttrExposureStrategy must not ship in production", legacy.exists())
        val authority = source("src/main/java/com/bncam/core/capture/SensorExposureAuthority.kt")
        assertFalse(authority.contains("maxShiftEv * 0.65f"))
        assertFalse(authority.contains("estimatedShiftEv"))
        assertTrue(authority.contains("object SensorExposureObserver"))
        assertTrue(authority.contains("object SensorExposurePolicy"))
        assertTrue(authority.contains("object SensorExposureAllocator"))
    }

    @Test
    fun `sensor exposure plan is evidence driven and reaches Camera2 sensor keys`() {
        val manager = source("src/main/java/com/bncam/core/engine/BnCameraManager.kt")
        val authority = source("src/main/java/com/bncam/core/capture/SensorExposureAuthority.kt")
        assertTrue(authority.contains("object SensorExposureObserver"))
        assertTrue(manager.contains("SensorExposureObserver.observe("))
        assertTrue(manager.contains("SensorExposurePolicy.resolve("))
        assertTrue(manager.contains("applySensorExposureAuthority(builder, characteristics, explicitManual)"))
        assertTrue(manager.contains("builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, allocation.exposureTimeNs)"))
        assertTrue(manager.contains("builder.set(CaptureRequest.SENSOR_SENSITIVITY, allocation.sensitivityIso)"))
        assertTrue(manager.contains("updateSensorExposureRealizationTruth(request, result, sessionGeneration)"))
        assertTrue(manager.contains("actualAppliedEv"))
        assertTrue(manager.contains("REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR"))
        assertTrue(manager.contains("controlMode=AE_COMPENSATION"))
    }

    @Test
    fun `single and multi share the same profile ETTR setting`() {
        val schema = source("src/main/java/com/bncam/data/settings/CaptureSettingsSchema.kt")
        val exposureLine = schema.lineSequence().first { it.contains("CaptureSettingKeys.EXPOSURE_STRATEGY") }
        assertFalse(exposureLine.contains("modes = multiOnly"))
        assertTrue(exposureLine.contains("ETTR (sensor authority)"))
    }

    @Test
    fun `strict RAW saturation is measured before reconstruction in both kernels`() {
        val full = source("src/main/cpp/vulkan/shaders/raw_preview.comp")
        val fast = source("src/main/cpp/vulkan/shaders/raw_preview_image.comp")
        for (shader in listOf(full, fast)) {
            assertTrue(shader.contains("RAW_TRUE_SATURATION_THRESHOLD = 0.9995"))
            assertTrue(shader.contains("RAW_TRUE_SATURATED_COUNT_INDEX"))
            assertTrue(shader.contains("before highlight reconstruction"))
        }
        val renderer = source("src/main/java/com/bncam/ui/screens/capture/RawPreviewRenderer.kt")
        assertTrue(renderer.contains("SENSOR_EXPOSURE_DIAGNOSTICS_START_INDEX = 638"))
    }

    @Test
    fun `sensor authority consumes only a fresh 11A analysis sidecar`() {
        val renderer = source("src/main/java/com/bncam/ui/screens/capture/RawPreviewRenderer.kt")
        val manager = source("src/main/java/com/bncam/core/engine/BnCameraManager.kt")
        assertTrue(renderer.contains("analysisReadbackUsed"))
        assertTrue(manager.contains("frame.analysisReadbackUsed"))
        assertTrue(manager.contains("Never combine cached 11A sidecar statistics with a newer exposure/ISO request"))
    }

    @Test
    fun `11A asynchronous display path remains non-blocking`() {
        val backend = source("src/main/cpp/vulkan/VulkanRawPreviewBackend.cpp")
        assertTrue(backend.contains("vkGetFenceStatus"))
        val waits = Regex("const VkResult completion = vkWaitForFences\\s*\\(").findAll(backend).count()
        assertTrue("Only bounded shutdown drain may wait; found $waits", waits == 1)
    }
}
