package com.bncam.core.engine

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraPhotoResolutionPolicySourceContractTest {
    private fun appRoot(): File = sequenceOf(File("."), File("app"))
        .firstOrNull { File(it, "src/main/java/com/bncam/core/engine/BnCameraManager.kt").isFile }
        ?: error("Cannot locate app module from ${File(".").absolutePath}")

    private fun source(path: String): String = File(appRoot(), path).readText()

    @Test
    fun `resolution request keeps specific raw and resolution fix as separate mechanisms`() {
        val policy = source("src/main/java/com/bncam/core/engine/CameraPhotoResolutionPolicy.kt")

        assertTrue(policy.contains("val specificRawSizeIndex: Int?"))
        assertTrue(policy.contains("val resolutionFixReferenceFormatCode: Int?"))
        assertTrue(policy.contains("SPECIFIC_RAW_SIZE"))
        assertTrue(policy.contains("RESOLUTION_FIX_REMAP"))
        assertTrue(policy.contains("bypassed Resolution Fix"))
        assertTrue(policy.contains("needFixResolution()/AeShotParams"))
    }

    @Test
    fun `specific raw size is resolved before resolution fix and indexes active format list`() {
        val policy = source("src/main/java/com/bncam/core/engine/CameraPhotoResolutionPolicy.kt")
        val specific = policy.indexOf("val specificIndex = request.specificRawSizeIndex")
        val indexed = policy.indexOf("val exact = native[specificIndex]", specific)
        val fix = policy.indexOf("val referenceFormatCode = request.resolutionFixReferenceFormatCode", indexed)

        assertTrue(specific >= 0)
        assertTrue(indexed > specific)
        assertTrue(fix > indexed)
    }

    @Test
    fun `resolution fix reference can only map onto target format native sizes`() {
        val policy = source("src/main/java/com/bncam/core/engine/CameraPhotoResolutionPolicy.kt")

        assertTrue(policy.contains("targetCandidates = fullFov"))
        assertTrue(policy.contains("referenceCandidates = referencePool"))
        assertTrue(policy.contains("targetCandidates.firstOrNull { it.extent == reference.extent }"))
        assertTrue(policy.contains("targetCandidates.minWithOrNull("))
        assertFalse(policy.contains("effectiveFormat = reference"))
    }

    @Test
    fun `manager resolution authority sits between format and stream config resolver`() {
        val manager = source("src/main/java/com/bncam/core/engine/BnCameraManager.kt")
        val format = manager.indexOf("CameraPhotoFormatPolicy.resolveRuntimePrimary(")
        val resolution = manager.indexOf("CameraPhotoResolutionPolicy.resolve(", format)
        val resolver = manager.indexOf("PhotoStreamConfigurationResolver.resolvePhoto(", resolution)
        val budget = manager.indexOf("CaptureBufferBudget.resolve(", resolver)

        assertTrue(format >= 0)
        assertTrue(resolution > format)
        assertTrue(resolver > resolution)
        assertTrue(budget > resolver)
        assertTrue(manager.contains("PHOTO_PRIMARY_RESOLUTION_RESOLVED"))
        assertTrue(manager.contains("PIPELINE_PRIMARY_RESOLUTION_UNAVAILABLE"))
    }

    @Test
    fun `conservative runtime recovery consumes resolution policy instead of rediscovering stream sizes`() {
        val resolver = source("src/main/java/com/bncam/core/engine/StreamConfigResolver.kt")

        assertTrue(resolver.contains("resolutionResolution.conservativeSize"))
        assertTrue(resolver.contains("resolutionResolution.conservativeSize"))
        assertTrue(resolver.contains("parsed.captureSize.width != primaryExtent.width"))
        assertTrue(resolver.contains("cannot override resolved PRIMARY_BUFFER extent"))
        assertFalse(resolver.contains("SCALER_STREAM_CONFIGURATION_MAP"))
        assertFalse(resolver.contains("getOutputSizes("))
        assertFalse(resolver.contains("selectConservativeFullFovSize"))
    }

    @Test
    fun `capability inventory preserves active format size order for raw index semantics`() {
        val catalog = source("src/main/java/com/bncam/core/engine/CameraStreamCapabilityCatalog.kt")
        val inventoryFactory = catalog.substring(
            catalog.indexOf("internal object CameraCapabilityInventoryFactory"),
            catalog.indexOf("object CameraStreamCapabilityScanner")
        )

        assertTrue(inventoryFactory.contains("Preserve Camera2's active format-size list order"))
        assertFalse(inventoryFactory.contains(".sortedByDescending { it.width.toLong() * it.height.toLong() }"))
    }
}
