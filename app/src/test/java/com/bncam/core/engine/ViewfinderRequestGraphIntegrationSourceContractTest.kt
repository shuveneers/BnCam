package com.bncam.core.engine

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ViewfinderRequestGraphIntegrationSourceContractTest {
    private fun appRoot(): File = sequenceOf(File("."), File("app"))
        .firstOrNull { File(it, "src/main/java/com/bncam/core/engine/BnCameraManager.kt").isFile }
        ?: error("Cannot locate app module from ${File(".").absolutePath}")

    private fun source(path: String): String = File(appRoot(), path).readText()

    @Test
    fun `raw selected buffer no longer implies direct yuv display target`() {
        val graph = source("src/main/java/com/bncam/core/engine/CurrentBnCamStreamGraph.kt")

        assertTrue(graph.contains("CurrentViewfinderRequestRoute.BUFFERED_PRIMARY"))
        assertTrue(graph.contains("repeating += BnCamStreamRoleIds.PRIMARY_BUFFER"))
        assertTrue(graph.contains("repeating += BnCamStreamRoleIds.RAW_PREVIEW_SUPPORT"))
        assertFalse(
            graph.contains(
                "repeatingRoleIds = sessionRoleIds"
            )
        )
    }

    @Test
    fun `manager resolves initial repeating graph from visible viewfinder route`() {
        val manager = source("src/main/java/com/bncam/core/engine/BnCameraManager.kt")

        assertTrue(manager.contains("viewfinderRoute = resolveCurrentViewfinderRequestRoute(activeStreamRoute)"))
        assertTrue(manager.contains("rawPreviewSupportRepeatingEnabled ="))
        assertTrue(manager.contains("customRawPreviewDisabledGeneration != sessionGeneration"))
    }

    @Test
    fun `normal viewfinder switch mutates request targets without rebuilding session outputs`() {
        val manager = source("src/main/java/com/bncam/core/engine/BnCameraManager.kt")
        val bindings = source("src/main/java/com/bncam/core/engine/StreamSurfaceBindings.kt")

        assertTrue(manager.contains("reconcileRepeatingTargetGraphForViewfinder("))
        assertTrue(manager.contains("builder.removeTarget(binding.surface)"))
        assertTrue(manager.contains("builder.addTarget(binding.surface)"))
        assertTrue(manager.contains("VIEWFINDER_REQUEST_GRAPH_RECONCILED"))
        assertTrue(manager.contains("scheduleViewfinderRequestTargetReconciliation("))
        assertTrue(bindings.contains("fun withRequestGraph(requestGraph: RequestTargetGraph)"))
    }

    @Test
    fun `disabled custom raw support is removed only from repeating membership`() {
        val manager = source("src/main/java/com/bncam/core/engine/BnCameraManager.kt")
        val graph = source("src/main/java/com/bncam/core/engine/CurrentBnCamStreamGraph.kt")

        assertTrue(manager.contains("reason = \"CUSTOM_RAW_PREVIEW_DISABLED:${'$'}reason\""))
        assertTrue(graph.contains("rawPreviewSupportEnabled"))
        assertTrue(graph.contains("roleGraph.contains(BnCamStreamRoleIds.RAW_PREVIEW_SUPPORT)"))
        assertTrue(graph.contains("sessionGraph = SessionOutputGraph(sessionRoleIds)"))
    }
}
