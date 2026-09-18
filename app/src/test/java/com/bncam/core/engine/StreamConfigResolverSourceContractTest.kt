package com.bncam.core.engine

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamConfigResolverSourceContractTest {
    private fun appRoot(): File = sequenceOf(File("."), File("app"))
        .firstOrNull { File(it, "src/main/java/com/bncam/core/engine/BnCameraManager.kt").isFile }
        ?: error("Cannot locate app module from ${File(".").absolutePath}")

    private fun source(path: String): String = File(appRoot(), path).readText()

    @Test
    fun `runtime resolves stream plan after existing full fov auto geometry and before buffer budget`() {
        val manager = source("src/main/java/com/bncam/core/engine/BnCameraManager.kt")
        val geometry = manager.indexOf("val autoBestSize = availableSizes.maxByOrNull")
        val resolver = manager.indexOf("StreamConfigResolver.resolvePhoto(", geometry)
        val budget = manager.indexOf("CaptureBufferBudget.resolve(", resolver)

        assertTrue(geometry >= 0)
        assertTrue(resolver > geometry)
        assertTrue(budget > resolver)
        assertTrue(manager.contains("event = \"STREAM_CONFIGURATION_RESOLVED\""))
    }

    @Test
    fun `auto remains the pre stream configuration geometry`() {
        val resolver = source("src/main/java/com/bncam/core/engine/StreamConfigResolver.kt")

        assertTrue(resolver.contains("StreamConfigurationMode.AUTO -> autoPlan("))
        assertTrue(resolver.contains("captureSize = autoCaptureSize"))
        assertTrue(resolver.contains("Auto retained the existing BnCam full-FOV stream geometry."))
    }

    @Test
    fun `validated mode cannot silently change the profile buffer format`() {
        val resolver = source("src/main/java/com/bncam/core/engine/StreamConfigResolver.kt")

        assertTrue(resolver.contains("if (parsed.formatCode != requestedFormatCode)"))
        assertTrue(resolver.contains("profile buffer ownership is preserved"))
        assertTrue(resolver.contains("effectiveFormatCode = requestedFormatCode"))
    }

    @Test
    fun `validated photo runtime requires exact active preview hal preflight`() {
        val resolver = source("src/main/java/com/bncam/core/engine/StreamConfigResolver.kt")
        val catalog = source("src/main/java/com/bncam/core/engine/CameraStreamCapabilityCatalog.kt")

        assertTrue(resolver.contains("configuredPreviewSize == null"))
        assertTrue(catalog.contains("manager.isCameraDeviceSetupSupported(cameraId)"))
        assertTrue(catalog.contains("setup.isSessionConfigurationSupported(config)"))
        assertTrue(catalog.contains("OutputConfiguration(previewSize, SurfaceTexture::class.java)"))
        assertTrue(catalog.contains("OutputConfiguration(captureFormat, captureSize)"))
        assertTrue(resolver.contains("fallbackToAuto = true"))
    }

    @Test
    fun `phase two does not create a second session lifecycle or vendor operation mode authority`() {
        val resolver = source("src/main/java/com/bncam/core/engine/StreamConfigResolver.kt")
        val manager = source("src/main/java/com/bncam/core/engine/BnCameraManager.kt")

        assertFalse(resolver.contains("createCaptureSession("))
        assertFalse(resolver.contains("pipelineGeneration"))
        assertFalse(resolver.contains("sessionConfigurationEpoch"))
        assertFalse(resolver.contains("vendorSessionType"))
        assertTrue(manager.contains("private var pipelineGeneration"))
        assertTrue(manager.contains("private var sessionConfigurationEpoch"))
    }

    @Test
    fun `validated catalog exposes only full fov photo candidates and ui only enables hal validated choices`() {
        val catalog = source("src/main/java/com/bncam/core/engine/CameraStreamCapabilityCatalog.kt")
        val screen = source("src/main/java/com/bncam/ui/screens/settings/lens_profiles/RawStreamBindingSettingsScreen.kt")

        assertTrue(catalog.contains("CameraStreamGeometryPolicy.fullFovCandidates("))
        assertTrue(catalog.contains("val photoSizes = allSizes.filter"))
        assertTrue(screen.contains("enabled = candidate.validationStatus == StreamCandidateValidationStatus.SESSION_VALIDATED"))
        assertTrue(screen.contains(".clickable(enabled = enabled"))
    }
    @Test
    fun `validated mode refuses mismatched vendor session contracts and auto cannot inherit manual raw binding`() {
        val resolver = source("src/main/java/com/bncam/core/engine/StreamConfigResolver.kt")
        val manager = source("src/main/java/com/bncam/core/engine/BnCameraManager.kt")

        assertTrue(resolver.contains("if (sessionHasVendorOverrides)"))
        assertTrue(resolver.contains("vendor/session override is active"))
        assertTrue(manager.contains("manualStreamConfigurationActive: Boolean"))
        assertTrue(manager.contains("if (!manualStreamConfigurationActive || viewfinderStreamSetting != ViewfinderStream.SELECTED_BUFFER)"))
        assertTrue(manager.contains("resolvedStreamPlan.configuredMode == StreamConfigurationMode.MANUAL"))
    }

}
