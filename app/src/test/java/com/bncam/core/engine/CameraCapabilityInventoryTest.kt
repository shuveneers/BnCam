package com.bncam.core.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraCapabilityInventoryTest {
    private fun format(
        code: Int,
        kind: CameraCapabilityFormatKind,
        availability: CameraCapabilityRuntimeAvailability =
            CameraPhotoFormatPolicy.defaultRuntimeAvailability(kind)
    ) = CameraCapabilityFormat(
        formatCode = code,
        formatName = kind.name,
        kind = kind,
        runtimeAvailability = availability,
        sizes = listOf(
            CameraCapabilitySize(
                extent = CameraCapabilityExtent(4000, 3000),
                minFrameDurationNs = 33_333_333L
            )
        )
    )

    private fun inventory(formats: List<CameraCapabilityFormat>) = CameraCapabilityInventory(
        requestedLensId = "2",
        logicalCameraId = "0",
        physicalCameraId = "2",
        source = CameraStreamCatalogSource.PHYSICAL_CAMERA,
        hardwareLevel = 1,
        requestCapabilities = setOf(3),
        aeFpsRanges = listOf(CameraCapabilityFpsRange(15, 30), CameraCapabilityFpsRange(30, 60)),
        sensorExtent = CameraCapabilityExtent(4000, 3000),
        viewfinderExtents = listOf(CameraCapabilityExtent(1920, 1080)),
        formats = formats,
        warnings = emptyList()
    )

    @Test
    fun `reported photo formats follow gcam evidence order independent of camera map order`() {
        val plan = inventory(
            listOf(
                format(35, CameraCapabilityFormatKind.YUV_420_888),
                format(32, CameraCapabilityFormatKind.RAW_SENSOR),
                format(38, CameraCapabilityFormatKind.RAW12),
                format(37, CameraCapabilityFormatKind.RAW10)
            )
        ).photoFormatPlan()

        assertEquals(
            listOf(
                CameraCapabilityFormatKind.RAW10,
                CameraCapabilityFormatKind.RAW12,
                CameraCapabilityFormatKind.RAW_SENSOR,
                CameraCapabilityFormatKind.YUV_420_888
            ),
            plan.reportedPriorityOrder.map { it.kind }
        )
    }

    @Test
    fun `raw12 stays discovery only until bncam has a production raw12 path`() {
        val plan = inventory(
            listOf(
                format(37, CameraCapabilityFormatKind.RAW10),
                format(38, CameraCapabilityFormatKind.RAW12),
                format(32, CameraCapabilityFormatKind.RAW_SENSOR),
                format(35, CameraCapabilityFormatKind.YUV_420_888)
            )
        ).photoFormatPlan()

        assertEquals(
            listOf(
                CameraCapabilityFormatKind.RAW10,
                CameraCapabilityFormatKind.RAW_SENSOR,
                CameraCapabilityFormatKind.YUV_420_888
            ),
            plan.runtimeCandidates.map { it.kind }
        )
        assertEquals(
            listOf(CameraCapabilityFormatKind.RAW12),
            plan.discoveryOnlyCandidates.map { it.kind }
        )
        assertFalse(plan.runtimeCandidates.any { it.kind == CameraCapabilityFormatKind.RAW12 })
    }

    @Test
    fun `unreported preferred formats are not invented`() {
        val plan = inventory(
            listOf(
                format(32, CameraCapabilityFormatKind.RAW_SENSOR),
                format(35, CameraCapabilityFormatKind.YUV_420_888)
            )
        ).photoFormatPlan()

        assertEquals(
            listOf(CameraCapabilityFormatKind.RAW_SENSOR, CameraCapabilityFormatKind.YUV_420_888),
            plan.reportedPriorityOrder.map { it.kind }
        )
        assertEquals(listOf(32, 35), plan.runtimeFormatCodes)
    }

    @Test
    fun `non primary formats remain inventory facts but never enter photo preference chain`() {
        val other = format(
            999,
            CameraCapabilityFormatKind.OTHER,
            CameraCapabilityRuntimeAvailability.NOT_PRIMARY_CAPTURE
        )
        val inventory = inventory(
            listOf(
                other,
                format(35, CameraCapabilityFormatKind.YUV_420_888)
            )
        )
        val plan = inventory.photoFormatPlan()

        assertTrue(inventory.reports(999))
        assertEquals(listOf(CameraCapabilityFormatKind.YUV_420_888), plan.reportedPriorityOrder.map { it.kind })
    }

    @Test
    fun `minimum frame duration remains a fact and derives sustainable fps`() {
        val size = CameraCapabilitySize(
            extent = CameraCapabilityExtent(4096, 3072),
            minFrameDurationNs = 50_000_000L
        )
        assertEquals(20, size.maxFpsFromDuration)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `duplicate format codes are rejected`() {
        inventory(
            listOf(
                format(37, CameraCapabilityFormatKind.RAW10),
                format(37, CameraCapabilityFormatKind.RAW_SENSOR)
            )
        )
    }


    @Test
    fun `supported explicit raw request wins over higher global auto priority`() {
        val inventory = inventory(
            listOf(
                format(37, CameraCapabilityFormatKind.RAW10),
                format(32, CameraCapabilityFormatKind.RAW_SENSOR),
                format(35, CameraCapabilityFormatKind.YUV_420_888)
            )
        )

        val resolved = CameraPhotoFormatPolicy.resolveRuntimePrimary(
            inventory = inventory,
            requestedFormatCode = 32,
            requestPolicy = CameraPhotoFormatRequestPolicy.RAW_WITH_ORDERED_FALLBACK
        )

        assertEquals(CameraPhotoFormatSelectionKind.REQUESTED_FORMAT, resolved.selectionKind)
        assertEquals(32, resolved.effectiveFormatCode)
        assertFalse(resolved.fallbackApplied)
    }

    @Test
    fun `missing raw request falls back through executable gcam order`() {
        val inventory = inventory(
            listOf(
                format(37, CameraCapabilityFormatKind.RAW10),
                format(38, CameraCapabilityFormatKind.RAW12),
                format(35, CameraCapabilityFormatKind.YUV_420_888)
            )
        )

        val resolved = CameraPhotoFormatPolicy.resolveRuntimePrimary(
            inventory = inventory,
            requestedFormatCode = 32,
            requestPolicy = CameraPhotoFormatRequestPolicy.RAW_WITH_ORDERED_FALLBACK
        )

        assertEquals(CameraPhotoFormatSelectionKind.ORDERED_FALLBACK, resolved.selectionKind)
        assertEquals(37, resolved.effectiveFormatCode)
        assertTrue(resolved.fallbackApplied)
        assertEquals(listOf(37, 35), resolved.runtimeCandidatesConsidered)
    }

    @Test
    fun `raw12 discovery does not block fallback to raw sensor`() {
        val inventory = inventory(
            listOf(
                format(38, CameraCapabilityFormatKind.RAW12),
                format(32, CameraCapabilityFormatKind.RAW_SENSOR),
                format(35, CameraCapabilityFormatKind.YUV_420_888)
            )
        )

        val resolved = CameraPhotoFormatPolicy.resolveRuntimePrimary(
            inventory = inventory,
            requestedFormatCode = 37,
            requestPolicy = CameraPhotoFormatRequestPolicy.RAW_WITH_ORDERED_FALLBACK
        )

        assertEquals(32, resolved.effectiveFormatCode)
        assertEquals(CameraPhotoFormatSelectionKind.ORDERED_FALLBACK, resolved.selectionKind)
    }

    @Test
    fun `raw fallback may reach yuv when no executable raw producer exists`() {
        val inventory = inventory(
            listOf(
                format(38, CameraCapabilityFormatKind.RAW12),
                format(35, CameraCapabilityFormatKind.YUV_420_888)
            )
        )

        val resolved = CameraPhotoFormatPolicy.resolveRuntimePrimary(
            inventory = inventory,
            requestedFormatCode = 37,
            requestPolicy = CameraPhotoFormatRequestPolicy.RAW_WITH_ORDERED_FALLBACK
        )

        assertEquals(35, resolved.effectiveFormatCode)
        assertEquals(CameraPhotoFormatSelectionKind.ORDERED_FALLBACK, resolved.selectionKind)
    }

    @Test
    fun `explicit yuv exact policy never promotes to raw`() {
        val inventory = inventory(
            listOf(
                format(37, CameraCapabilityFormatKind.RAW10),
                format(32, CameraCapabilityFormatKind.RAW_SENSOR)
            )
        )

        val resolved = CameraPhotoFormatPolicy.resolveRuntimePrimary(
            inventory = inventory,
            requestedFormatCode = 35,
            requestPolicy = CameraPhotoFormatRequestPolicy.EXACT_ONLY
        )

        assertEquals(CameraPhotoFormatSelectionKind.UNAVAILABLE, resolved.selectionKind)
        assertEquals(null, resolved.effectiveFormatCode)
        assertFalse(resolved.fallbackApplied)
    }

    @Test
    fun `reported format without sizes is not executable and cannot win runtime selection`() {
        val emptyRaw10 = format(37, CameraCapabilityFormatKind.RAW10).copy(sizes = emptyList())
        val inventory = inventory(
            listOf(
                emptyRaw10,
                format(32, CameraCapabilityFormatKind.RAW_SENSOR),
                format(35, CameraCapabilityFormatKind.YUV_420_888)
            )
        )

        val resolved = CameraPhotoFormatPolicy.resolveRuntimePrimary(
            inventory = inventory,
            requestedFormatCode = 37,
            requestPolicy = CameraPhotoFormatRequestPolicy.RAW_WITH_ORDERED_FALLBACK
        )

        assertEquals(32, resolved.effectiveFormatCode)
        assertEquals(CameraPhotoFormatSelectionKind.ORDERED_FALLBACK, resolved.selectionKind)
    }
}
