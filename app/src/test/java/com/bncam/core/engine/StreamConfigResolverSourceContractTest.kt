package com.bncam.core.engine

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhotoStreamConfigurationResolverSourceContractTest {
    private fun appRoot(): File = sequenceOf(File("."), File("app"))
        .firstOrNull { File(it, "src/main/java/com/bncam/core/engine/BnCameraManager.kt").isFile }
        ?: error("Cannot locate app module from ${File(".").absolutePath}")

    private fun source(path: String): String = File(appRoot(), path).readText()

    @Test
    fun `runtime resolves capability format then persisted resolution request before final selection`() {
        val manager = source("src/main/java/com/bncam/core/engine/BnCameraManager.kt")
        val inventory = manager.indexOf("CameraCapabilityInventoryFactory.fromCharacteristics(")
        val format = manager.indexOf("CameraPhotoFormatPolicy.resolveRuntimePrimary(", inventory)
        val settings = manager.indexOf("PhotoStreamSettingsStore(context).get(cameraId)", format)
        val resolution = manager.indexOf("CameraPhotoResolutionPolicy.resolve(", settings)
        val resolver = manager.indexOf("PhotoStreamConfigurationResolver.resolvePhoto(", resolution)
        val budget = manager.indexOf("CaptureBufferBudget.resolve(", resolver)

        assertTrue(inventory >= 0)
        assertTrue(format > inventory)
        assertTrue(settings > format)
        assertTrue(resolution > settings)
        assertTrue(resolver > resolution)
        assertTrue(budget > resolver)
        assertTrue(manager.contains("specificRawSizeIndex = photoStreamSettings.specificRawSizeIndex"))
        assertTrue(manager.contains("resolutionFixReferenceFormatCode = photoStreamSettings.resolutionFixReferenceFormatCode"))
    }

    @Test
    fun `legacy candidate and mode compatibility authority are removed`() {
        val resolver = source("src/main/java/com/bncam/core/engine/StreamConfigResolver.kt")
        val manager = source("src/main/java/com/bncam/core/engine/BnCameraManager.kt")

        assertFalse(resolver.contains("legacyValidatedCandidateId"))
        assertFalse(resolver.contains("StreamCandidateIdCodec"))
        assertFalse(resolver.contains("StreamConfigurationMode"))
        assertFalse(resolver.contains("CameraSessionPreflight"))
        assertFalse(manager.contains("StreamConfigurationMode"))
        assertFalse(manager.contains("validatedCandidateId"))
    }

    @Test
    fun `explicit resolution recovery uses native auto then conservative geometry`() {
        val resolver = source("src/main/java/com/bncam/core/engine/StreamConfigResolver.kt")
        val manager = source("src/main/java/com/bncam/core/engine/BnCameraManager.kt")

        assertTrue(manager.contains("val autoResolution = if (photoStreamSettings.hasExplicitResolutionOverride)"))
        assertTrue(resolver.contains("StreamRuntimeFallbackTier.AUTO_GEOMETRY,\n            StreamRuntimeFallbackTier.CONSERVATIVE_FULL_FOV -> autoResolution"))
        assertTrue(resolver.contains("autoResolution.conservativeSize?.extent"))
        assertTrue(resolver.contains("without rewriting saved settings"))
    }

    @Test
    fun `final session contract carries the same primary authority into the role graph`() {
        val manager = source("src/main/java/com/bncam/core/engine/BnCameraManager.kt")
        val graph = source("src/main/java/com/bncam/core/engine/CurrentBnCamStreamGraph.kt")
        val model = source("src/main/java/com/bncam/core/engine/StreamArchitectureModel.kt")

        assertTrue(manager.contains("primaryStreamContract = photoStreamSelection.toPrimaryStreamContract("))
        assertTrue(manager.contains("primaryContract = activeStreamRoute?.primaryStreamContract"))
        assertTrue(graph.contains("val primaryContract: ResolvedPrimaryStreamContract?"))
        assertTrue(graph.contains("formatCode = contract.effectiveFormatCode"))
        assertTrue(graph.contains("extent = contract.extent"))
        assertTrue(graph.contains("primaryStream = input.primaryContract"))
        assertTrue(model.contains("val primaryStream: ResolvedPrimaryStreamContract? = null"))
    }

    @Test
    fun `concrete ImageReader bindings are checked against resolved role format and extent`() {
        val bindings = source("src/main/java/com/bncam/core/engine/StreamSurfaceBindings.kt")

        assertTrue(bindings.contains("reader.imageFormat == role.formatCode"))
        assertTrue(bindings.contains("reader.width == role.extent.width && reader.height == role.extent.height"))
        assertTrue(bindings.contains("reader.maxImages == expectedMaxImages"))
    }

    @Test
    fun `resolver remains outside lifecycle operation mode and fps ownership`() {
        val resolver = source("src/main/java/com/bncam/core/engine/StreamConfigResolver.kt")
        val manager = source("src/main/java/com/bncam/core/engine/BnCameraManager.kt")

        assertFalse(resolver.contains("createCaptureSession("))
        assertFalse(resolver.contains("pipelineGeneration"))
        assertFalse(resolver.contains("sessionConfigurationEpoch"))
        assertFalse(resolver.contains("vendorSessionType"))
        assertFalse(resolver.contains("CONTROL_AE_TARGET_FPS_RANGE"))
        assertTrue(manager.contains("private var pipelineGeneration"))
        assertTrue(manager.contains("private var sessionConfigurationEpoch"))
    }

    @Test
    fun `custom raw preview support is controlled directly by binding plus recovery`() {
        val manager = source("src/main/java/com/bncam/core/engine/BnCameraManager.kt")

        assertTrue(manager.contains("raw10Binding = raw10BindingSetting"))
        assertTrue(manager.contains("rawSensorBinding = rawSensorBindingSetting"))
        assertTrue(manager.contains("customBindingAllowed = photoStreamSelection.runtimeFallbackTier == StreamRuntimeFallbackTier.NONE"))
        assertFalse(manager.contains("manualStreamConfigurationActive"))
    }
}
