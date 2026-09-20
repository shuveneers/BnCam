package com.bncam.core.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamRuntimeTelemetryTest {
    private fun configuration(): ResolvedStreamConfiguration {
        val primary = ResolvedPrimaryStreamContract(
            roleId = BnCamStreamRoleIds.PRIMARY_BUFFER,
            kind = StreamRoleKind.YUV_PRIMARY,
            requestedFormatCode = 35,
            effectiveFormatCode = 35,
            extent = StreamExtent(4000, 3000),
            formatSelectionKind = CameraPhotoFormatSelectionKind.REQUESTED_FORMAT,
            formatFallbackApplied = false,
            resolutionSelectionKind = CameraPhotoResolutionSelectionKind.NATIVE_AUTO,
            evidence = "test primary"
        )
        val roles = StreamRoleGraph(
            listOf(
                StreamRoleSpec(
                    id = BnCamStreamRoleIds.VIEWFINDER,
                    kind = StreamRoleKind.VIEWFINDER,
                    logicalCameraId = "0",
                    physicalCameraId = null,
                    formatCode = 34,
                    extent = StreamExtent(1920, 1080),
                    outputKind = StreamOutputKind.DISPLAY_SURFACE
                ),
                StreamRoleSpec(
                    id = BnCamStreamRoleIds.PRIMARY_BUFFER,
                    kind = StreamRoleKind.YUV_PRIMARY,
                    logicalCameraId = "0",
                    physicalCameraId = "3",
                    formatCode = 35,
                    extent = StreamExtent(4000, 3000),
                    outputKind = StreamOutputKind.IMAGE_READER,
                    maxImages = 8,
                    lifetime = StreamRoleLifetime.PIPELINE
                ),
                StreamRoleSpec(
                    id = BnCamStreamRoleIds.RAW_PREVIEW_SUPPORT,
                    kind = StreamRoleKind.RAW_PREVIEW_SUPPORT,
                    logicalCameraId = "0",
                    physicalCameraId = "3",
                    formatCode = 37,
                    extent = StreamExtent(4000, 3000),
                    outputKind = StreamOutputKind.IMAGE_READER,
                    maxImages = 3
                )
            )
        )
        return ResolvedStreamConfiguration(
            operationMode = ResolvedOperationMode.regular("test regular"),
            roleGraph = roles,
            sessionGraph = SessionOutputGraph(roles.ids()),
            requestGraph = RequestTargetGraph(
                repeatingRoleIds = setOf(BnCamStreamRoleIds.PRIMARY_BUFFER, BnCamStreamRoleIds.VIEWFINDER),
                captureRoleIds = setOf(BnCamStreamRoleIds.PRIMARY_BUFFER)
            ),
            primaryStream = primary
        )
    }

    @Test
    fun `resolved telemetry keeps session graph request graph and physical routing distinct`() {
        val tracker = StreamRuntimeTelemetryTracker(cadenceWindowSize = 8)
        tracker.resolvedConfiguration(
            generation = 7,
            epoch = 12L,
            logicalCameraId = "0",
            advertisedPhysicalCameraIds = setOf("2", "3"),
            configuration = configuration(),
            runtimeNamesByRoleId = mapOf(
                BnCamStreamRoleIds.VIEWFINDER to "YUV_VIEWFINDER",
                BnCamStreamRoleIds.PRIMARY_BUFFER to "YUV_WARM_RING",
                BnCamStreamRoleIds.RAW_PREVIEW_SUPPORT to "CUSTOM_RAW_PREVIEW"
            ),
            nowElapsedNs = 100L
        )

        val snapshot = tracker.snapshot()
        assertEquals(StreamTelemetrySessionState.RESOLVED, snapshot.sessionState)
        assertEquals(OperationModePolicyKind.REGULAR, snapshot.operationModePolicy)
        assertEquals(StreamSessionMode.REGULAR_SESSION, snapshot.streamMode)
        assertEquals(
            setOf(BnCamStreamRoleIds.VIEWFINDER, BnCamStreamRoleIds.PRIMARY_BUFFER, BnCamStreamRoleIds.RAW_PREVIEW_SUPPORT),
            snapshot.sessionRoleIds
        )
        assertEquals(
            setOf(BnCamStreamRoleIds.VIEWFINDER, BnCamStreamRoleIds.PRIMARY_BUFFER),
            snapshot.repeatingRoleIds
        )
        val primary = snapshot.roles.single { it.roleId == BnCamStreamRoleIds.PRIMARY_BUFFER }
        assertEquals("3", primary.physicalCameraId)
        assertTrue(primary.captureTarget)
        assertEquals("YUV_WARM_RING", primary.runtimeName)
    }

    @Test
    fun `request graph updates do not rewrite configured session outputs`() {
        val tracker = StreamRuntimeTelemetryTracker()
        tracker.resolvedConfiguration(7, 12L, "0", setOf("3"), configuration(), emptyMap(), 100L)
        val originalSessionOutputs = tracker.snapshot().sessionRoleIds

        tracker.requestGraphUpdated(
            generation = 7,
            epoch = 12L,
            requestGraph = RequestTargetGraph(
                repeatingRoleIds = setOf(BnCamStreamRoleIds.PRIMARY_BUFFER, BnCamStreamRoleIds.RAW_PREVIEW_SUPPORT),
                captureRoleIds = setOf(BnCamStreamRoleIds.PRIMARY_BUFFER)
            ),
            reason = "RAW_SELECTED",
            nowElapsedNs = 200L
        )

        val snapshot = tracker.snapshot()
        assertEquals(originalSessionOutputs, snapshot.sessionRoleIds)
        assertFalse(BnCamStreamRoleIds.VIEWFINDER in snapshot.repeatingRoleIds)
        assertTrue(BnCamStreamRoleIds.RAW_PREVIEW_SUPPORT in snapshot.repeatingRoleIds)
        assertEquals("RAW_SELECTED", snapshot.requestGraphReason)
    }

    @Test
    fun `requested fps and measured cadence remain separate telemetry`() {
        val tracker = StreamRuntimeTelemetryTracker(cadenceWindowSize = 8)
        tracker.resolvedConfiguration(7, 12L, "0", setOf("3"), configuration(), emptyMap(), 100L)
        tracker.repeatingRequestSubmitted(7, 7, 30, "SESSION_INITIAL_REPEATING", 200L)
        tracker.captureResult(7, 1_000_000_000L, 7, 30, 210L)
        tracker.captureResult(7, 1_033_333_333L, 7, 30, 220L)
        tracker.captureResult(7, 1_066_666_666L, 7, 30, 230L)

        val snapshot = tracker.snapshot()
        assertEquals(7, snapshot.requestedRepeatingFpsLower)
        assertEquals(30, snapshot.requestedRepeatingFpsUpper)
        assertEquals(7, snapshot.captureResultReportedFpsLower)
        assertEquals(30, snapshot.captureResultReportedFpsUpper)
        assertTrue((snapshot.captureResultSensorFps ?: 0.0) in 29.9..30.1)
    }

    @Test
    fun `producer cadence is independent for primary and raw support even at overlapping timestamps`() {
        val tracker = StreamRuntimeTelemetryTracker(cadenceWindowSize = 8)
        tracker.resolvedConfiguration(7, 12L, "0", setOf("3"), configuration(), emptyMap(), 100L)

        tracker.producerFrameArrived(7, BnCamStreamRoleIds.PRIMARY_BUFFER, 1_000_000_000L, 2_000_000_000L)
        tracker.producerFrameArrived(7, BnCamStreamRoleIds.RAW_PREVIEW_SUPPORT, 1_000_000_000L, 2_005_000_000L)
        tracker.producerFrameArrived(7, BnCamStreamRoleIds.PRIMARY_BUFFER, 1_033_333_333L, 2_033_333_333L)
        tracker.producerFrameArrived(7, BnCamStreamRoleIds.RAW_PREVIEW_SUPPORT, 1_066_666_666L, 2_071_666_666L)

        val cadence = tracker.snapshot().producerCadence.associateBy { it.roleId }
        assertTrue((cadence.getValue(BnCamStreamRoleIds.PRIMARY_BUFFER).sensorFps ?: 0.0) in 29.9..30.1)
        assertTrue((cadence.getValue(BnCamStreamRoleIds.RAW_PREVIEW_SUPPORT).sensorFps ?: 0.0) in 14.9..15.1)
        assertEquals(2, cadence.getValue(BnCamStreamRoleIds.PRIMARY_BUFFER).sampleCount)
        assertEquals(2, cadence.getValue(BnCamStreamRoleIds.RAW_PREVIEW_SUPPORT).sampleCount)
    }

    @Test
    fun `stale session lifecycle callbacks cannot overwrite current session telemetry`() {
        val tracker = StreamRuntimeTelemetryTracker()
        tracker.resolvedConfiguration(7, 12L, "0", setOf("3"), configuration(), emptyMap(), 100L)
        tracker.sessionConfigured(7, 12L, 111, 200L)
        tracker.resolvedConfiguration(7, 13L, "0", setOf("3"), configuration(), emptyMap(), 300L)
        tracker.sessionConfigured(7, 13L, 222, 400L)
        tracker.sessionClosed(7, 12L, 111, 500L)

        val snapshot = tracker.snapshot()
        assertEquals(13L, snapshot.sessionEpoch)
        assertEquals(StreamTelemetrySessionState.CONFIGURED, snapshot.sessionState)
        assertEquals(222, snapshot.sessionIdentityHash)
    }
    @Test
    fun `synchronous session creation failure is represented without inventing session identity`() {
        val tracker = StreamRuntimeTelemetryTracker()
        tracker.resolvedConfiguration(7, 12L, "0", setOf("3"), configuration(), emptyMap(), 100L)
        tracker.sessionCreationFailed(7, 12L, 200L)

        val snapshot = tracker.snapshot()
        assertEquals(StreamTelemetrySessionState.CONFIGURE_FAILED, snapshot.sessionState)
        assertEquals(null, snapshot.sessionIdentityHash)
    }

}
