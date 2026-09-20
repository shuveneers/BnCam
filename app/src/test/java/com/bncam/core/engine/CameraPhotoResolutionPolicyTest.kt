package com.bncam.core.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraPhotoResolutionPolicyTest {
    private fun size(width: Int, height: Int, durationNs: Long? = null) =
        CameraCapabilitySize(CameraCapabilityExtent(width, height), durationNs)

    private fun format(
        code: Int,
        kind: CameraCapabilityFormatKind,
        vararg sizes: CameraCapabilitySize
    ) = CameraCapabilityFormat(
        formatCode = code,
        formatName = kind.name,
        kind = kind,
        runtimeAvailability = CameraPhotoFormatPolicy.defaultRuntimeAvailability(kind),
        sizes = sizes.toList()
    )

    private fun inventory(
        target: CameraCapabilityFormat,
        vararg others: CameraCapabilityFormat,
        sensor: CameraCapabilityExtent? = CameraCapabilityExtent(4000, 3000)
    ) = CameraCapabilityInventory(
        requestedLensId = "2",
        logicalCameraId = "0",
        physicalCameraId = "2",
        source = CameraStreamCatalogSource.PHYSICAL_CAMERA,
        hardwareLevel = 1,
        requestCapabilities = setOf(3),
        aeFpsRanges = emptyList(),
        sensorExtent = sensor,
        viewfinderExtents = emptyList(),
        formats = listOf(target) + others,
        warnings = emptyList()
    )

    private fun formatResolution(target: CameraCapabilityFormat) = ResolvedCameraPhotoFormat(
        requestedFormatCode = target.formatCode,
        requestPolicy = CameraPhotoFormatRequestPolicy.RAW_WITH_ORDERED_FALLBACK,
        selectionKind = CameraPhotoFormatSelectionKind.REQUESTED_FORMAT,
        effectiveFormat = target,
        runtimeCandidatesConsidered = listOf(target.formatCode),
        reason = "test"
    )

    @Test
    fun `native auto picks largest full fov target size`() {
        val raw10 = format(
            37,
            CameraCapabilityFormatKind.RAW10,
            size(4000, 3000),
            size(1920, 1080),
            size(2000, 1500)
        )
        val resolved = CameraPhotoResolutionPolicy.resolve(
            inventory(raw10),
            formatResolution(raw10)
        )

        assertEquals(CameraPhotoResolutionSelectionKind.NATIVE_AUTO, resolved.selectionKind)
        assertEquals(CameraCapabilityExtent(4000, 3000), resolved.selectedExtent)
        assertEquals(
            listOf(CameraCapabilityExtent(4000, 3000), CameraCapabilityExtent(2000, 1500)),
            resolved.fullFovCandidates.map { it.extent }
        )
    }

    @Test
    fun `specific raw size index uses active format list order and bypasses resolution fix`() {
        val raw10 = format(
            37,
            CameraCapabilityFormatKind.RAW10,
            size(2000, 1500),
            size(4000, 3000),
            size(1000, 750)
        )
        val yuv = format(
            35,
            CameraCapabilityFormatKind.YUV_420_888,
            size(1280, 960)
        )
        val resolved = CameraPhotoResolutionPolicy.resolve(
            inventory(raw10, yuv),
            formatResolution(raw10),
            CameraPhotoResolutionRequest(
                specificRawSizeIndex = 1,
                resolutionFixReferenceFormatCode = 35
            )
        )

        assertEquals(CameraPhotoResolutionSelectionKind.SPECIFIC_RAW_SIZE, resolved.selectionKind)
        assertEquals(CameraCapabilityExtent(4000, 3000), resolved.selectedExtent)
        assertFalse(resolved.resolutionFixApplied)
        assertEquals(listOf(CameraCapabilityExtent(4000, 3000)), resolved.policyCandidates.map { it.extent })
    }

    @Test
    fun `invalid specific raw index does not block later resolution fix`() {
        val raw10 = format(
            37,
            CameraCapabilityFormatKind.RAW10,
            size(4000, 3000),
            size(2000, 1500)
        )
        val rawSensor = format(
            32,
            CameraCapabilityFormatKind.RAW_SENSOR,
            size(2000, 1500)
        )
        val resolved = CameraPhotoResolutionPolicy.resolve(
            inventory(raw10, rawSensor),
            formatResolution(raw10),
            CameraPhotoResolutionRequest(
                specificRawSizeIndex = 99,
                resolutionFixReferenceFormatCode = 32
            )
        )

        assertEquals(CameraPhotoResolutionSelectionKind.RESOLUTION_FIX_REMAP, resolved.selectionKind)
        assertTrue(resolved.resolutionFixApplied)
        assertEquals(CameraCapabilityExtent(2000, 1500), resolved.selectedExtent)
    }

    @Test
    fun `resolution fix exact reference extents never invent unsupported target sizes`() {
        val raw10 = format(
            37,
            CameraCapabilityFormatKind.RAW10,
            size(4000, 3000),
            size(2000, 1500)
        )
        val rawSensor = format(
            32,
            CameraCapabilityFormatKind.RAW_SENSOR,
            size(3000, 2250),
            size(2000, 1500)
        )
        val resolved = CameraPhotoResolutionPolicy.resolve(
            inventory(raw10, rawSensor),
            formatResolution(raw10),
            CameraPhotoResolutionRequest(resolutionFixReferenceFormatCode = 32)
        )

        assertEquals(CameraPhotoResolutionSelectionKind.RESOLUTION_FIX_REMAP, resolved.selectionKind)
        assertTrue(resolved.policyCandidates.all { it in raw10.sizes })
        assertFalse(resolved.policyCandidates.any { it.extent == CameraCapabilityExtent(3000, 2250) })
        assertTrue(resolved.policyCandidates.any { it.extent == CameraCapabilityExtent(2000, 1500) })
    }

    @Test
    fun `resolution fix nearest remap remains target native and follows reference scale`() {
        val raw10 = format(
            37,
            CameraCapabilityFormatKind.RAW10,
            size(4000, 3000),
            size(2400, 1800),
            size(1600, 1200)
        )
        val rawSensor = format(
            32,
            CameraCapabilityFormatKind.RAW_SENSOR,
            size(2100, 1575)
        )
        val resolved = CameraPhotoResolutionPolicy.resolve(
            inventory(raw10, rawSensor),
            formatResolution(raw10),
            CameraPhotoResolutionRequest(resolutionFixReferenceFormatCode = 32)
        )

        assertEquals(CameraCapabilityExtent(2400, 1800), resolved.selectedExtent)
        assertTrue(resolved.selectedSize in raw10.sizes)
    }

    @Test
    fun `missing resolution fix reference falls back to native auto`() {
        val raw10 = format(
            37,
            CameraCapabilityFormatKind.RAW10,
            size(4000, 3000),
            size(2000, 1500)
        )
        val resolved = CameraPhotoResolutionPolicy.resolve(
            inventory(raw10),
            formatResolution(raw10),
            CameraPhotoResolutionRequest(resolutionFixReferenceFormatCode = 999)
        )

        assertEquals(CameraPhotoResolutionSelectionKind.NATIVE_AUTO, resolved.selectionKind)
        assertFalse(resolved.resolutionFixApplied)
        assertEquals(CameraCapabilityExtent(4000, 3000), resolved.selectedExtent)
    }

    @Test
    fun `specific raw size preference is ignored for yuv`() {
        val yuv = format(
            35,
            CameraCapabilityFormatKind.YUV_420_888,
            size(4000, 3000),
            size(2000, 1500)
        )
        val formatResolution = ResolvedCameraPhotoFormat(
            requestedFormatCode = 35,
            requestPolicy = CameraPhotoFormatRequestPolicy.EXACT_ONLY,
            selectionKind = CameraPhotoFormatSelectionKind.REQUESTED_FORMAT,
            effectiveFormat = yuv,
            runtimeCandidatesConsidered = listOf(35),
            reason = "test"
        )
        val resolved = CameraPhotoResolutionPolicy.resolve(
            inventory(yuv),
            formatResolution,
            CameraPhotoResolutionRequest(specificRawSizeIndex = 1)
        )

        assertEquals(CameraPhotoResolutionSelectionKind.NATIVE_AUTO, resolved.selectionKind)
        assertEquals(CameraCapabilityExtent(4000, 3000), resolved.selectedExtent)
    }

    @Test
    fun `preferred yuv pool can win only through target native full fov sizes`() {
        val yuv = format(
            35,
            CameraCapabilityFormatKind.YUV_420_888,
            size(4000, 3000),
            size(2000, 1500),
            size(1920, 1080)
        )
        val formatResolution = ResolvedCameraPhotoFormat(
            requestedFormatCode = 35,
            requestPolicy = CameraPhotoFormatRequestPolicy.EXACT_ONLY,
            selectionKind = CameraPhotoFormatSelectionKind.REQUESTED_FORMAT,
            effectiveFormat = yuv,
            runtimeCandidatesConsidered = listOf(35),
            reason = "test"
        )
        val resolved = CameraPhotoResolutionPolicy.resolve(
            inventory(yuv),
            formatResolution,
            preferredTargetExtents = listOf(
                CameraCapabilityExtent(2000, 1500),
                CameraCapabilityExtent(1920, 1080),
                CameraCapabilityExtent(1234, 925)
            )
        )

        assertEquals(CameraCapabilityExtent(2000, 1500), resolved.selectedExtent)
        assertEquals(
            listOf(CameraCapabilityExtent(4000, 3000), CameraCapabilityExtent(2000, 1500)),
            resolved.policyCandidates.map { it.extent }
        )
    }

    @Test
    fun `conservative recovery remains a separate lower bandwidth full fov decision`() {
        val raw10 = format(
            37,
            CameraCapabilityFormatKind.RAW10,
            size(4000, 3000, 50_000_000L),
            size(3200, 2400, 40_000_000L),
            size(2800, 2100, 33_333_333L),
            size(2000, 1500, 25_000_000L)
        )
        val resolved = CameraPhotoResolutionPolicy.resolve(
            inventory(raw10),
            formatResolution(raw10)
        )

        assertEquals(CameraCapabilityExtent(4000, 3000), resolved.selectedExtent)
        assertEquals(CameraCapabilityExtent(3200, 2400), resolved.conservativeSize?.extent)
    }

    @Test
    fun `resolution policy never changes effective primary format`() {
        val raw10 = format(37, CameraCapabilityFormatKind.RAW10, size(4000, 3000))
        val rawSensor = format(32, CameraCapabilityFormatKind.RAW_SENSOR, size(2000, 1500))
        val resolved = CameraPhotoResolutionPolicy.resolve(
            inventory(raw10, rawSensor),
            formatResolution(raw10),
            CameraPhotoResolutionRequest(resolutionFixReferenceFormatCode = 32)
        )

        assertEquals(37, resolved.effectiveFormatCode)
        assertTrue(resolved.policyCandidates.all { it in raw10.sizes })
    }
}
