package com.bncam.core.engine

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamPhysicalRoutingSourceContractTest {
    private fun appRoot(): File = sequenceOf(File("."), File("app"))
        .firstOrNull { File(it, "src/main/java/com/bncam/core/engine/BnCameraManager.kt").isFile }
        ?: error("Cannot locate app module from ${File(".").absolutePath}")

    private fun source(path: String): String = File(appRoot(), path).readText()

    @Test
    fun `manager discovers physical children from opened logical camera before graph resolution`() {
        val manager = source("src/main/java/com/bncam/core/engine/BnCameraManager.kt")
        val discover = manager.indexOf("getCachedCameraCharacteristics(activeLogicalId).physicalCameraIds.toSet()")
        val route = manager.indexOf("StreamPhysicalRoutingPolicy.resolve(", discover)
        val graph = manager.indexOf("CurrentBnCamStreamGraphFactory.build(", route)

        assertTrue(discover >= 0)
        assertTrue(route > discover)
        assertTrue(graph > route)
        assertTrue(manager.contains("STREAM_PHYSICAL_ROUTING_RESOLVED"))
    }

    @Test
    fun `graph input no longer carries one session wide physical camera id`() {
        val graph = source("src/main/java/com/bncam/core/engine/CurrentBnCamStreamGraph.kt")

        assertTrue(graph.contains("val physicalRouting: StreamPhysicalRoutingPlan"))
        assertFalse(graph.contains("val physicalCameraId: String?,\n    val operationMode"))
        assertTrue(graph.contains("physicalCameraIdFor(\n                            BnCamStreamRoleIds.VIEWFINDER"))
        assertTrue(graph.contains("physicalCameraIdFor(\n                            BnCamStreamRoleIds.PRIMARY_BUFFER"))
        assertTrue(graph.contains("physicalCameraIdFor(\n                            BnCamStreamRoleIds.RAW_PREVIEW_SUPPORT"))
    }

    @Test
    fun `output configuration consumes role route rather than active pipeline physical id`() {
        val manager = source("src/main/java/com/bncam/core/engine/BnCameraManager.kt")
        val outputBlockStart = manager.indexOf("boundStreamConfiguration.sessionBindings().forEach")
        val outputBlockEnd = manager.indexOf("val sessionConfig = SessionConfiguration(", outputBlockStart)
        val outputBlock = manager.substring(outputBlockStart, outputBlockEnd)

        assertTrue(outputBlock.contains("val outputPhysicalCameraId = binding.role.physicalCameraId"))
        assertTrue(outputBlock.contains("output.setPhysicalCameraId(outputPhysicalCameraId)"))
        assertFalse(outputBlock.contains("setPhysicalCameraId(activePhysicalId)"))
    }

    @Test
    fun `routing policy rejects undiscovered physical ids and keeps route mode explicit`() {
        val policy = source("src/main/java/com/bncam/core/engine/StreamPhysicalRoutingPolicy.kt")

        assertTrue(policy.contains("INHERIT_ACTIVE_ROUTE"))
        assertTrue(policy.contains("LOGICAL_CAMERA"))
        assertTrue(policy.contains("EXPLICIT_PHYSICAL_CHILD"))
        assertTrue(policy.contains("Active physical camera \$activePhysical is not advertised"))
        assertTrue(policy.contains("requested physical child \$requested"))
        assertFalse(policy.contains("firstOrNull()"))
    }
}
