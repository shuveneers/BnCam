package com.bncam.core.engine

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraCapabilityInventorySourceContractTest {
    private fun appRoot(): File = sequenceOf(File("."), File("app"))
        .firstOrNull { File(it, "src/main/java/com/bncam/core/engine/BnCameraManager.kt").isFile }
        ?: error("Cannot locate app module from ${File(".").absolutePath}")

    private fun source(path: String): String = File(appRoot(), path).readText()

    @Test
    fun `camera map is converted once into first class capability inventory`() {
        val catalog = source("src/main/java/com/bncam/core/engine/CameraStreamCapabilityCatalog.kt")

        assertTrue(catalog.contains("internal object CameraCapabilityInventoryFactory"))
        assertTrue(catalog.contains("val inventoryFormats = map.outputFormats"))
        assertTrue(catalog.contains("CameraCapabilityInventoryFactory.fromCharacteristics("))
        assertTrue(catalog.contains("CameraCapabilityInventory("))
        assertTrue(catalog.contains("CameraPhotoFormatPolicy.defaultRuntimeAvailability(kind)"))
        assertTrue(catalog.contains("val formats: List<CameraStreamFormatCapability> = inventory.formats"))
    }

    @Test
    fun `photo candidate generation consumes ordered inventory policy rather than hardcoded list`() {
        val catalog = source("src/main/java/com/bncam/core/engine/CameraStreamCapabilityCatalog.kt")

        assertTrue(catalog.contains("val formatPlan = inventory.photoFormatPlan()"))
        assertTrue(catalog.contains("formatPlan.runtimeCandidates.forEach"))
        assertFalse(catalog.contains("val preferredFormats = listOf"))
    }

    @Test
    fun `raw12 is discovered but not falsely advertised as executable`() {
        val inventory = source("src/main/java/com/bncam/core/engine/CameraCapabilityInventory.kt")
        val catalog = source("src/main/java/com/bncam/core/engine/CameraStreamCapabilityCatalog.kt")

        assertTrue(inventory.contains("CameraCapabilityFormatKind.RAW10"))
        assertTrue(inventory.contains("CameraCapabilityFormatKind.RAW12"))
        assertTrue(inventory.contains("CameraCapabilityFormatKind.RAW_SENSOR"))
        assertTrue(inventory.contains("CameraCapabilityFormatKind.YUV_420_888"))
        assertTrue(inventory.contains("CameraCapabilityFormatKind.RAW12 -> CameraCapabilityRuntimeAvailability.DISCOVERY_ONLY"))
        assertTrue(catalog.contains("ImageFormat.RAW12 -> CameraCapabilityFormatKind.RAW12"))
    }

    @Test
    fun `format size fps and physical route remain separate inventory facts`() {
        val inventory = source("src/main/java/com/bncam/core/engine/CameraCapabilityInventory.kt")

        assertTrue(inventory.contains("val physicalCameraId: String?"))
        assertTrue(inventory.contains("val aeFpsRanges: List<CameraCapabilityFpsRange>"))
        assertTrue(inventory.contains("val sensorExtent: CameraCapabilityExtent?"))
        assertTrue(inventory.contains("val viewfinderExtents: List<CameraCapabilityExtent>"))
        assertTrue(inventory.contains("val formats: List<CameraCapabilityFormat>"))
        assertFalse(inventory.contains("operationMode"))
        assertFalse(inventory.contains("RequestTargetGraph"))
    }
}
