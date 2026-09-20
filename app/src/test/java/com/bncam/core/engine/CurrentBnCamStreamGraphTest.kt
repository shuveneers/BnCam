package com.bncam.core.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class CurrentBnCamStreamGraphTest {
    private fun reader(format: Int, width: Int, height: Int, maxImages: Int = 10) =
        CurrentImageReaderRoleInput(
            formatCode = format,
            extent = StreamExtent(width, height),
            maxImages = maxImages
        )

    private fun primaryContract(format: Int, width: Int, height: Int, raw: Boolean) =
        ResolvedPrimaryStreamContract(
            roleId = BnCamStreamRoleIds.PRIMARY_BUFFER,
            kind = if (raw) StreamRoleKind.RAW_PRIMARY else StreamRoleKind.YUV_PRIMARY,
            requestedFormatCode = format,
            effectiveFormatCode = format,
            extent = StreamExtent(width, height),
            formatSelectionKind = CameraPhotoFormatSelectionKind.REQUESTED_FORMAT,
            formatFallbackApplied = false,
            resolutionSelectionKind = CameraPhotoResolutionSelectionKind.NATIVE_AUTO,
            runtimeFallbackTier = StreamRuntimeFallbackTier.NONE,
            evidence = "test"
        )

    private fun routing(
        logicalCameraId: String,
        activePhysicalCameraId: String?,
        advertisedPhysicalCameraIds: Set<String> = activePhysicalCameraId?.let { setOf(it) }.orEmpty(),
        roleRequests: Map<String, StreamRolePhysicalRouteRequest> = mapOf(
            BnCamStreamRoleIds.VIEWFINDER to StreamRolePhysicalRouteRequest.inheritActive(),
            BnCamStreamRoleIds.PRIMARY_BUFFER to StreamRolePhysicalRouteRequest.inheritActive(),
            BnCamStreamRoleIds.RAW_PREVIEW_SUPPORT to StreamRolePhysicalRouteRequest.inheritActive()
        )
    ): StreamPhysicalRoutingPlan = StreamPhysicalRoutingPolicy.resolve(
        logicalCameraId = logicalCameraId,
        advertisedPhysicalCameraIds = advertisedPhysicalCameraIds,
        activePhysicalCameraId = activePhysicalCameraId,
        roleRequests = roleRequests
    )

    @Test
    fun `yuv viewfinder keeps warm primary and direct display as repeating targets`() {
        val configuration = CurrentBnCamStreamGraphFactory.build(
            CurrentBnCamStreamGraphInput(
                logicalCameraId = "0",
                physicalRouting = routing("0", "2"),
                operationMode = ResolvedOperationMode.regular(),
                previewExtent = StreamExtent(1920, 1080),
                primary = reader(format = 35, width = 4096, height = 3072),
                primaryContract = primaryContract(format = 35, width = 4096, height = 3072, raw = false),
                viewfinderRoute = CurrentViewfinderRequestRoute.CAMERA2_VIEWFINDER
            )
        )

        assertEquals(
            setOf(BnCamStreamRoleIds.VIEWFINDER, BnCamStreamRoleIds.PRIMARY_BUFFER),
            configuration.sessionGraph.roleIds
        )
        assertEquals(
            setOf(BnCamStreamRoleIds.PRIMARY_BUFFER, BnCamStreamRoleIds.VIEWFINDER),
            configuration.requestGraph.repeatingRoleIds
        )
        assertEquals(setOf(BnCamStreamRoleIds.PRIMARY_BUFFER), configuration.requestGraph.captureRoleIds)
        assertEquals(
            StreamRoleKind.YUV_PRIMARY,
            configuration.roleGraph.role(BnCamStreamRoleIds.PRIMARY_BUFFER)?.kind
        )
    }

    @Test
    fun `raw selected buffer does not keep direct yuv viewfinder in repeating request`() {
        val configuration = CurrentBnCamStreamGraphFactory.build(
            CurrentBnCamStreamGraphInput(
                logicalCameraId = "0",
                physicalRouting = routing("0", "3"),
                operationMode = ResolvedOperationMode.regular(),
                previewExtent = StreamExtent(1920, 1080),
                primary = reader(format = 37, width = 4080, height = 3072, maxImages = 10),
                primaryContract = primaryContract(format = 37, width = 4080, height = 3072, raw = true),
                rawPreviewSupport = reader(format = 36, width = 2040, height = 1536, maxImages = 4),
                viewfinderRoute = CurrentViewfinderRequestRoute.BUFFERED_PRIMARY
            )
        )

        assertEquals(StreamRoleKind.RAW_PRIMARY, configuration.roleGraph.role(BnCamStreamRoleIds.PRIMARY_BUFFER)?.kind)
        assertEquals(
            StreamRoleKind.RAW_PREVIEW_SUPPORT,
            configuration.roleGraph.role(BnCamStreamRoleIds.RAW_PREVIEW_SUPPORT)?.kind
        )
        assertTrue(BnCamStreamRoleIds.VIEWFINDER in configuration.sessionGraph.roleIds)
        assertFalse(BnCamStreamRoleIds.VIEWFINDER in configuration.requestGraph.repeatingRoleIds)
        assertEquals(
            setOf(BnCamStreamRoleIds.PRIMARY_BUFFER, BnCamStreamRoleIds.RAW_PREVIEW_SUPPORT),
            configuration.requestGraph.repeatingRoleIds
        )
        assertEquals(setOf(BnCamStreamRoleIds.PRIMARY_BUFFER), configuration.requestGraph.captureRoleIds)
    }

    @Test
    fun `raw support can be removed from repeating without removing session output`() {
        val configuration = CurrentBnCamStreamGraphFactory.build(
            CurrentBnCamStreamGraphInput(
                logicalCameraId = "0",
                physicalRouting = routing("0", "3"),
                operationMode = ResolvedOperationMode.regular(),
                previewExtent = StreamExtent(1920, 1080),
                primary = reader(format = 37, width = 4080, height = 3072, maxImages = 10),
                primaryContract = primaryContract(format = 37, width = 4080, height = 3072, raw = true),
                rawPreviewSupport = reader(format = 36, width = 2040, height = 1536, maxImages = 4),
                viewfinderRoute = CurrentViewfinderRequestRoute.BUFFERED_PRIMARY,
                rawPreviewSupportRepeatingEnabled = false
            )
        )

        assertTrue(BnCamStreamRoleIds.RAW_PREVIEW_SUPPORT in configuration.sessionGraph.roleIds)
        assertEquals(
            setOf(BnCamStreamRoleIds.PRIMARY_BUFFER),
            configuration.requestGraph.repeatingRoleIds
        )
    }

    @Test
    fun `raw profile with yuv display keeps direct viewfinder and warm raw primary targeted`() {
        val configuration = CurrentBnCamStreamGraphFactory.build(
            CurrentBnCamStreamGraphInput(
                logicalCameraId = "0",
                physicalRouting = routing("0", "7"),
                operationMode = ResolvedOperationMode.regular(),
                previewExtent = StreamExtent(1440, 1080),
                primary = reader(format = 32, width = 4096, height = 3072),
                primaryContract = primaryContract(format = 32, width = 4096, height = 3072, raw = true),
                viewfinderRoute = CurrentViewfinderRequestRoute.CAMERA2_VIEWFINDER
            )
        )

        assertEquals(
            setOf(BnCamStreamRoleIds.PRIMARY_BUFFER, BnCamStreamRoleIds.VIEWFINDER),
            configuration.requestGraph.repeatingRoleIds
        )
        configuration.sessionRoles().forEach { role ->
            assertEquals("7", role.physicalCameraId)
            assertEquals("0", role.logicalCameraId)
        }
    }

    @Test
    fun `preview only transition remains representable`() {
        val configuration = CurrentBnCamStreamGraphFactory.build(
            CurrentBnCamStreamGraphInput(
                logicalCameraId = "0",
                physicalRouting = routing("0", null),
                operationMode = ResolvedOperationMode.regular(),
                previewExtent = StreamExtent(1280, 720),
                primary = null,
                primaryContract = null,
                viewfinderRoute = CurrentViewfinderRequestRoute.CAMERA2_VIEWFINDER
            )
        )

        assertEquals(setOf(BnCamStreamRoleIds.VIEWFINDER), configuration.sessionGraph.roleIds)
        assertEquals(setOf(BnCamStreamRoleIds.VIEWFINDER), configuration.requestGraph.repeatingRoleIds)
        assertTrue(configuration.requestGraph.captureRoleIds.isEmpty())
    }

    @Test
    fun `buffered viewfinder requires a primary producer`() {
        try {
            CurrentBnCamStreamGraphFactory.build(
                CurrentBnCamStreamGraphInput(
                    logicalCameraId = "0",
                    physicalRouting = routing("0", null),
                    operationMode = ResolvedOperationMode.regular(),
                    previewExtent = StreamExtent(1280, 720),
                    primary = null,
                    primaryContract = null,
                    viewfinderRoute = CurrentViewfinderRequestRoute.BUFFERED_PRIMARY
                )
            )
            fail("Expected BUFFERED_PRIMARY without PRIMARY_BUFFER to be rejected")
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }


    @Test
    fun `stream roles may carry different physical routing decisions in one session`() {
        val routes = routing(
            logicalCameraId = "0",
            activePhysicalCameraId = "3",
            advertisedPhysicalCameraIds = setOf("3", "4"),
            roleRequests = mapOf(
                BnCamStreamRoleIds.VIEWFINDER to StreamRolePhysicalRouteRequest.logical(),
                BnCamStreamRoleIds.PRIMARY_BUFFER to StreamRolePhysicalRouteRequest.inheritActive(),
                BnCamStreamRoleIds.RAW_PREVIEW_SUPPORT to StreamRolePhysicalRouteRequest.physical("4")
            )
        )
        val configuration = CurrentBnCamStreamGraphFactory.build(
            CurrentBnCamStreamGraphInput(
                logicalCameraId = "0",
                physicalRouting = routes,
                operationMode = ResolvedOperationMode.regular(),
                previewExtent = StreamExtent(1920, 1080),
                primary = reader(format = 37, width = 4080, height = 3072),
                primaryContract = primaryContract(format = 37, width = 4080, height = 3072, raw = true),
                rawPreviewSupport = reader(format = 36, width = 2040, height = 1536, maxImages = 4),
                viewfinderRoute = CurrentViewfinderRequestRoute.BUFFERED_PRIMARY
            )
        )

        assertEquals(null, configuration.roleGraph.role(BnCamStreamRoleIds.VIEWFINDER)?.physicalCameraId)
        assertEquals("3", configuration.roleGraph.role(BnCamStreamRoleIds.PRIMARY_BUFFER)?.physicalCameraId)
        assertEquals("4", configuration.roleGraph.role(BnCamStreamRoleIds.RAW_PREVIEW_SUPPORT)?.physicalCameraId)
    }
    @Test
    fun `primary reader must match immutable primary contract`() {
        try {
            CurrentBnCamStreamGraphFactory.build(
                CurrentBnCamStreamGraphInput(
                    logicalCameraId = "0",
                    physicalRouting = routing("0", "3"),
                    operationMode = ResolvedOperationMode.regular(),
                    previewExtent = StreamExtent(1920, 1080),
                    primary = reader(format = 37, width = 4080, height = 3072),
                    primaryContract = primaryContract(format = 37, width = 4096, height = 3072, raw = true),
                    viewfinderRoute = CurrentViewfinderRequestRoute.BUFFERED_PRIMARY
                )
            )
            fail("Expected PRIMARY_BUFFER reader/contract extent mismatch to be rejected")
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }

}
