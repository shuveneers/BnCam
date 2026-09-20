package com.bncam.core.engine

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OperationModeRuntimeIntegrationSourceContractTest {
    private fun appRoot(): File = sequenceOf(File("."), File("app"))
        .firstOrNull { File(it, "src/main/java/com/bncam/core/engine/BnCameraManager.kt").isFile }
        ?: error("Cannot locate app module from ${File(".").absolutePath}")

    private fun source(path: String): String = File(appRoot(), path).readText()

    @Test
    fun `runtime no longer scans arbitrary vendor operation mode integers`() {
        val manager = source("src/main/java/com/bncam/core/engine/BnCameraManager.kt")

        assertFalse(manager.contains("vendorOperationModeCandidateSessionTypes"))
        assertFalse(manager.contains("(0..50).map { 32768 + it }"))
        assertFalse(manager.contains("VENDOR_OPERATION_MODE_PROBE_NEXT"))
        assertFalse(manager.contains("VENDOR_OPERATION_MODE_PROBE_CONFIG_FAILED"))
        assertFalse(manager.contains("handleVendorOperationModeProbe"))
        assertFalse(manager.contains("activeVendorOperationProbe"))
        assertFalse(manager.contains("getLearnedVendorSessionType"))
        assertFalse(manager.contains("learnedVendorSessionType"))
        assertFalse(manager.contains("activeVendorFeatureSignature"))
    }

    @Test
    fun `runtime has only regular known and manual operation mode authorities`() {
        val manager = source("src/main/java/com/bncam/core/engine/BnCameraManager.kt")
        val policy = source("src/main/java/com/bncam/core/engine/OperationModePolicy.kt")
        val runtimePolicy = source("src/main/java/com/bncam/core/engine/SessionOperationModePolicy.kt")

        assertTrue(policy.contains("REGULAR"))
        assertTrue(policy.contains("KNOWN_DEVICE_MODE"))
        assertTrue(policy.contains("MANUAL_CUSTOM"))
        assertFalse(policy.contains("TRANSITIONAL_EXISTING_RUNTIME"))
        assertTrue(runtimePolicy.contains("explicitOperationModes"))
        assertTrue(runtimePolicy.contains("knownDeviceOperationMode"))
        assertTrue(manager.contains("SESSION_OPERATION_MODE_POLICY"))
        assertTrue(manager.contains("SESSION_OPERATION_MODE_RESOLVED"))
    }

    @Test
    fun `sensor mode request and session operation mode remain separate decisions`() {
        val manager = source("src/main/java/com/bncam/core/engine/BnCameraManager.kt")

        assertTrue(manager.contains("requiredSensorMode"))
        assertTrue(manager.contains("org.codeaurora.qcamera3.sensorMode"))
        assertTrue(manager.contains("SessionOperationModePolicy.resolve("))
        assertTrue(manager.contains("Sensor-mode request injection remains a separate CaptureRequest concern"))
    }

    @Test
    fun `operation mode policy errors are not silently converted to regular session`() {
        val manager = source("src/main/java/com/bncam/core/engine/BnCameraManager.kt")

        assertTrue(manager.contains("catch (e: SessionOperationModePolicyException)"))
        assertTrue(manager.contains("Never hide"))
        assertTrue(manager.contains("silently falling back to SESSION_REGULAR"))
    }
}
