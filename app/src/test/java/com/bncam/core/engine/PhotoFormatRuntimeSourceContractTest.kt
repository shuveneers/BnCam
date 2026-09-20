package com.bncam.core.engine

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhotoFormatRuntimeSourceContractTest {
    private fun appRoot(): File = sequenceOf(File("."), File("app"))
        .firstOrNull { File(it, "src/main/java/com/bncam/core/engine/BnCameraManager.kt").isFile }
        ?: error("Cannot locate app module from ${File(".").absolutePath}")

    private fun source(path: String): String = File(appRoot(), path).readText()

    @Test
    fun `pipeline resolves format from capability inventory before independent resolution policy`() {
        val manager = source("src/main/java/com/bncam/core/engine/BnCameraManager.kt")
        val inventory = manager.indexOf("CameraCapabilityInventoryFactory.fromCharacteristics(")
        val format = manager.indexOf("CameraPhotoFormatPolicy.resolveRuntimePrimary(", inventory)
        val resolution = manager.indexOf("CameraPhotoResolutionPolicy.resolve(", format)

        assertTrue(inventory >= 0)
        assertTrue(format > inventory)
        assertTrue(resolution > format)
        assertTrue(manager.contains("resolutionResolution.effectiveFormatCode == effectiveFormat"))
        assertFalse(manager.contains("val supportsYuv ="))
        assertFalse(manager.contains("val supportsRaw10 ="))
        assertFalse(manager.contains("val supportsRawSensor ="))
    }

    @Test
    fun `yuv stays exact while raw requests use ordered fallback policy`() {
        val manager = source("src/main/java/com/bncam/core/engine/BnCameraManager.kt")
        val inventory = source("src/main/java/com/bncam/core/engine/CameraCapabilityInventory.kt")

        assertTrue(manager.contains("requestedFormatCode == ImageFormat.YUV_420_888"))
        assertTrue(manager.contains("CameraPhotoFormatRequestPolicy.EXACT_ONLY"))
        assertTrue(manager.contains("CameraPhotoFormatRequestPolicy.RAW_WITH_ORDERED_FALLBACK"))
        assertTrue(inventory.contains("val fallback = usableRuntimeCandidates.firstOrNull()"))
        assertTrue(inventory.contains("RAW10 -> RAW12 -> RAW_SENSOR -> YUV"))
    }

    @Test
    fun `runtime inventory gates raw execution on raw request capability`() {
        val catalog = source("src/main/java/com/bncam/core/engine/CameraStreamCapabilityCatalog.kt")

        assertTrue(catalog.contains("REQUEST_AVAILABLE_CAPABILITIES_RAW in requestCapabilities"))
        assertTrue(catalog.contains("!rawCapabilityAdvertised"))
        assertTrue(catalog.contains("CameraCapabilityRuntimeAvailability.DISCOVERY_ONLY"))
    }

    @Test
    fun `legacy stream settings cannot restore profile owned format authority`() {
        val manager = source("src/main/java/com/bncam/core/engine/BnCameraManager.kt")
        val resolver = source("src/main/java/com/bncam/core/engine/StreamConfigResolver.kt")

        assertTrue(manager.contains("formatResolution = formatResolution"))
        assertTrue(manager.contains("photoStreamSelection.effectiveFormatCode == effectiveFormat"))
        assertTrue(resolver.contains("if (parsed.formatCode != effectiveFormatCode)"))
        assertTrue(resolver.contains("does not match the resolved PRIMARY_BUFFER format"))
        assertFalse(resolver.contains("profile buffer ownership is preserved"))
        assertFalse(resolver.contains("captureSize = parsed.captureSize"))
    }

    @Test
    fun `custom raw preview follows effective primary format after fallback`() {
        val manager = source("src/main/java/com/bncam/core/engine/BnCameraManager.kt")

        assertTrue(manager.contains("requestedSource = cameraFormatName(effectiveFormat)"))
        assertTrue(manager.contains("ViewfinderEffectiveSource.fromImageFormat(identity.bufferFormat)"))
        assertFalse(manager.contains("ViewfinderEffectiveSource.fromFrameSource(identity.requestedFrameSource)"))
    }
}
