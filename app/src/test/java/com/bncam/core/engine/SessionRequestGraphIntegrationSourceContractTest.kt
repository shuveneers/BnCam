package com.bncam.core.engine

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionRequestGraphIntegrationSourceContractTest {
    private fun appRoot(): File = sequenceOf(File("."), File("app"))
        .firstOrNull { File(it, "src/main/java/com/bncam/core/engine/BnCameraManager.kt").isFile }
        ?: error("Cannot locate app module from ${File(".").absolutePath}")

    private fun source(path: String): String = File(appRoot(), path).readText()

    @Test
    fun `session outputs are no longer copied directly into repeating request`() {
        val manager = source("src/main/java/com/bncam/core/engine/BnCameraManager.kt")

        assertFalse(
            manager.contains(
                "sessionNamedSurfaces.forEach { (surface, _) -> requestBuilder.addTarget(surface) }"
            )
        )
        assertTrue(manager.contains("boundStreamConfiguration.repeatingBindings().forEach"))
        assertTrue(manager.contains("requestBuilder.addTarget(binding.surface)"))
    }

    @Test
    fun `session outputs come from session graph and use per role physical routing`() {
        val manager = source("src/main/java/com/bncam/core/engine/BnCameraManager.kt")

        assertTrue(manager.contains("boundStreamConfiguration.sessionBindings().forEach"))
        assertTrue(manager.contains("val outputPhysicalCameraId = binding.role.physicalCameraId"))
        assertTrue(manager.contains("output.setPhysicalCameraId(outputPhysicalCameraId)"))
        assertTrue(manager.contains("binding.runtimeName to binding.role.physicalCameraId"))
    }

    @Test
    fun `existing lifetime hardening still owns the immutable surface snapshot`() {
        val manager = source("src/main/java/com/bncam/core/engine/BnCameraManager.kt")
        val ownership = manager.indexOf("registerPendingSessionOutputOwnership(")
        val graph = manager.indexOf("val resolvedStreamConfiguration = CurrentBnCamStreamGraphFactory.build(")
        val create = manager.indexOf("camera.createCaptureSession(sessionConfig)")

        assertTrue(ownership >= 0)
        assertTrue(graph > ownership)
        assertTrue(create > graph)
        assertTrue(manager.contains("promotePendingSessionOutputOwnership("))
        assertTrue(manager.contains("releasePendingSessionOutputOwnership("))
        assertTrue(manager.contains("awaitCaptureSessionClosed("))
    }

    @Test
    fun `session graph uses the same resolved operation mode as Camera2 session creation`() {
        val manager = source("src/main/java/com/bncam/core/engine/BnCameraManager.kt")
        val operationPolicy = source("src/main/java/com/bncam/core/engine/OperationModePolicy.kt")

        assertTrue(manager.contains("resolvedOperationMode = resolveSessionOperationMode("))
        assertTrue(manager.contains("sessionOperationMode = resolvedOperationMode.operationMode"))
        assertTrue(manager.contains("val graphOperationMode = resolvedOperationMode"))
        assertFalse(manager.contains("transitionalExistingRuntime"))
        assertFalse(operationPolicy.contains("TRANSITIONAL_EXISTING_RUNTIME"))
    }
}
