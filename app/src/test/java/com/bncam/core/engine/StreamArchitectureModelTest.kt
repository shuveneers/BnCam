package com.bncam.core.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamArchitectureModelTest {
    private fun viewfinder() = StreamRoleSpec(
        id = "VIEWFINDER",
        kind = StreamRoleKind.VIEWFINDER,
        logicalCameraId = "0",
        physicalCameraId = "2",
        formatCode = 34,
        extent = StreamExtent(1920, 1080),
        outputKind = StreamOutputKind.DISPLAY_SURFACE
    )

    private fun rawPrimary() = StreamRoleSpec(
        id = "RAW_PRIMARY",
        kind = StreamRoleKind.RAW_PRIMARY,
        logicalCameraId = "0",
        physicalCameraId = "2",
        formatCode = 37,
        extent = StreamExtent(4080, 3072),
        outputKind = StreamOutputKind.IMAGE_READER,
        maxImages = 10
    )


    private fun primaryContract(primary: StreamRoleSpec) = ResolvedPrimaryStreamContract(
        roleId = primary.id,
        kind = primary.kind,
        requestedFormatCode = primary.formatCode,
        effectiveFormatCode = primary.formatCode,
        extent = primary.extent,
        formatSelectionKind = CameraPhotoFormatSelectionKind.REQUESTED_FORMAT,
        formatFallbackApplied = false,
        resolutionSelectionKind = CameraPhotoResolutionSelectionKind.NATIVE_AUTO,
        runtimeFallbackTier = StreamRuntimeFallbackTier.NONE,
        evidence = "unit-test"
    )

    private fun analysis() = StreamRoleSpec(
        id = "ANALYSIS",
        kind = StreamRoleKind.ANALYSIS,
        logicalCameraId = "0",
        physicalCameraId = null,
        formatCode = 35,
        extent = StreamExtent(1280, 720),
        outputKind = StreamOutputKind.IMAGE_READER,
        maxImages = 3
    )

    @Test
    fun `session outputs and repeating targets are independent`() {
        val primary = rawPrimary()
        val roles = StreamRoleGraph(listOf(viewfinder(), primary, analysis()))
        val configuration = ResolvedStreamConfiguration(
            operationMode = ResolvedOperationMode.regular(),
            roleGraph = roles,
            sessionGraph = SessionOutputGraph(setOf("VIEWFINDER", "RAW_PRIMARY", "ANALYSIS")),
            requestGraph = RequestTargetGraph(
                repeatingRoleIds = setOf("VIEWFINDER", "RAW_PRIMARY"),
                captureRoleIds = setOf("RAW_PRIMARY")
            ),
            primaryStream = primaryContract(primary)
        )

        assertEquals(3, configuration.sessionRoles().size)
        assertEquals(2, configuration.repeatingRoles().size)
        assertEquals(listOf("RAW_PRIMARY"), configuration.captureRoles().map { it.id })
        assertFalse("ANALYSIS" in configuration.requestGraph.repeatingRoleIds)
        assertTrue("ANALYSIS" in configuration.sessionGraph.roleIds)
    }

    @Test
    fun `request target cannot reference unconfigured output`() {
        val primary = rawPrimary()
        val roles = StreamRoleGraph(listOf(viewfinder(), primary))
        assertThrows(IllegalArgumentException::class.java) {
            ResolvedStreamConfiguration(
                operationMode = ResolvedOperationMode.regular(),
                roleGraph = roles,
                sessionGraph = SessionOutputGraph(setOf("VIEWFINDER")),
                requestGraph = RequestTargetGraph(
                    repeatingRoleIds = setOf("VIEWFINDER", "RAW_PRIMARY"),
                    captureRoleIds = emptySet()
                ),
                primaryStream = primaryContract(primary)
            )
        }
    }

    @Test
    fun `physical routing is stored per role`() {
        val roles = StreamRoleGraph(listOf(viewfinder(), analysis()))
        assertEquals("2", roles.role("VIEWFINDER")?.physicalCameraId)
        assertEquals(null, roles.role("ANALYSIS")?.physicalCameraId)
    }

    @Test
    fun `duplicate role ids are rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            StreamRoleGraph(listOf(viewfinder(), viewfinder()))
        }
    }
    @Test
    fun `primary contract is part of immutable stream configuration authority`() {
        val primary = rawPrimary()
        val contract = primaryContract(primary)
        val configuration = ResolvedStreamConfiguration(
            operationMode = ResolvedOperationMode.regular(),
            roleGraph = StreamRoleGraph(listOf(primary)),
            sessionGraph = SessionOutputGraph(setOf(primary.id)),
            requestGraph = RequestTargetGraph(
                repeatingRoleIds = setOf(primary.id),
                captureRoleIds = setOf(primary.id)
            ),
            primaryStream = contract
        )

        assertEquals(contract, configuration.primaryStream)
        assertEquals(StreamSessionMode.REGULAR_SESSION, configuration.streamMode)
    }

    @Test
    fun `primary contract cannot disagree with role format or extent`() {
        val primary = rawPrimary()
        val badContract = ResolvedPrimaryStreamContract(
            roleId = primary.id,
            kind = primary.kind,
            requestedFormatCode = primary.formatCode,
            effectiveFormatCode = primary.formatCode + 1,
            extent = primary.extent,
            formatSelectionKind = CameraPhotoFormatSelectionKind.REQUESTED_FORMAT,
            formatFallbackApplied = false,
            resolutionSelectionKind = CameraPhotoResolutionSelectionKind.NATIVE_AUTO,
            runtimeFallbackTier = StreamRuntimeFallbackTier.NONE,
            evidence = "unit-test"
        )
        assertThrows(IllegalArgumentException::class.java) {
            ResolvedStreamConfiguration(
                operationMode = ResolvedOperationMode.regular(),
                roleGraph = StreamRoleGraph(listOf(primary)),
                sessionGraph = SessionOutputGraph(setOf(primary.id)),
                requestGraph = RequestTargetGraph(
                    repeatingRoleIds = setOf(primary.id),
                    captureRoleIds = setOf(primary.id)
                ),
                primaryStream = badContract
            )
        }
    }

}
