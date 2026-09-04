package com.bncam.core.engine

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Regression contracts for the source-cadence recovery path. */
class CameraCadenceGeometrySourceContractTest {
    private fun appRoot(): File = sequenceOf(File("."), File("app"))
        .firstOrNull { File(it, "src/main/java/com/bncam/core/engine/BnCameraManager.kt").isFile }
        ?: error("Cannot locate app module from ${File(".").absolutePath}")

    private fun managerSource(): String =
        File(appRoot(), "src/main/java/com/bncam/core/engine/BnCameraManager.kt").readText()

    private fun geometrySource(): String =
        File(appRoot(), "src/main/java/com/bncam/core/engine/CameraStreamGeometryPolicy.kt").readText()

    private fun settingsSource(): String =
        File(appRoot(), "src/main/java/com/bncam/data/settings/SettingsRepository.kt").readText()

    @Test
    fun `fps request policy may inspect active geometry but may never mutate it`() {
        val source = managerSource()
        val start = source.indexOf("private fun applyOptimalAeTargetFpsRange(")
        val end = source.indexOf("Last-write stabilization contract", start)
        assertTrue(start >= 0)
        assertTrue(end > start)
        val fpsBlock = source.substring(start, end)

        assertTrue(fpsBlock.contains("CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES"))
        assertTrue(fpsBlock.contains("getOutputMinFrameDuration"))

        assertFalse(fpsBlock.contains("ImageReader.newInstance("))
        assertFalse(fpsBlock.contains("activePipelineIdentity ="))
        assertFalse(fpsBlock.contains("SCALER_CROP_REGION"))
        assertFalse(fpsBlock.contains("CONTROL_ZOOM_RATIO"))
        assertFalse(fpsBlock.contains("createCaptureSession("))
        assertFalse(fpsBlock.contains("recommendedSizes"))
        assertFalse(fpsBlock.contains("standardSizes"))
    }

    @Test
    fun `geometry policy contains no cadence or fps ranking input`() {
        val source = geometrySource().lowercase()
        assertFalse(source.contains("fps"))
        assertFalse(source.contains("cadence"))
        assertFalse(source.contains("frameduration"))
        assertFalse(source.contains("minframeduration"))
    }

    @Test
    fun `default raw preview format does not add the optional vendor preview output`() {
        val settings = settingsSource()
        val manager = managerSource()

        assertTrue(settings.contains("preferences[key] ?: \"AUTO\""))

        val start = manager.indexOf("private suspend fun resolveCustomRawPreviewBinding(")
        val end = manager.indexOf("Resolves one user-selectable camera id", start)
        assertTrue(start >= 0)
        assertTrue(end > start)
        val binding = manager.substring(start, end)

        assertTrue(binding.contains("getOrDefault(\"AUTO\")"))
        assertTrue(binding.contains("parseCameraFormatCode(encoded)"))
        assertTrue(binding.contains("?: return RawPreviewBindingResolution(\"none\", null)"))
    }

    @Test
    fun `warm raw producer keeps preview and image reader as existing targets`() {
        val source = managerSource()
        assertTrue(source.contains("previewSurface?.let { requestBuilder.addTarget(it) }"))
        assertTrue(source.contains("imageReader?.surface?.let { requestBuilder.addTarget(it) }"))
    }
}
