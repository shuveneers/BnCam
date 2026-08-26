package com.bncam.core.engine

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PhysicalCameraCaptureRequestContractTest {
    private val appDir: File = sequenceOf(File("."), File("app"))
        .firstOrNull { File(it, "src/main/java/com/bncam/core/engine/BnCameraManager.kt").isFile }
        ?: error("Cannot locate app module from ${File(".").absolutePath}")

    @Test
    fun hiddenPhysicalPipelineUsesPhysicalAwareCamera2RequestOverload() {
        val manager = source("src/main/java/com/bncam/core/engine/BnCameraManager.kt")
        val helper = manager.substringAfter("private fun createPipelineCaptureRequestBuilder(")
            .substringBefore("private fun resolveWarmRepeatingRequestTemplate(")

        assertTrue(helper.contains("camera.createCaptureRequest(template, setOf(physicalCameraId))"))
        assertTrue(helper.contains("physicalCameraId in advertisedPhysicalIds"))
    }

    @Test
    fun allPipelineRequestBuildersShareThePhysicalAwareContract() {
        val manager = source("src/main/java/com/bncam/core/engine/BnCameraManager.kt")
        assertEquals(
            4,
            Regex("createPipelineCaptureRequestBuilder\\(").findAll(manager).count()
        )
        assertTrue(manager.contains("createPipelineCaptureRequestBuilder(camera, warmTemplate.template)"))
        assertTrue(manager.contains("createPipelineCaptureRequestBuilder(camera, CameraDevice.TEMPLATE_PREVIEW)"))
        assertTrue(manager.contains("createPipelineCaptureRequestBuilder(device, CameraDevice.TEMPLATE_STILL_CAPTURE)"))
    }

    private fun source(relative: String): String = File(appDir, relative).readText()
}
