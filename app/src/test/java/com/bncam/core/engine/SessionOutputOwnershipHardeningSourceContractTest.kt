package com.bncam.core.engine

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionOutputOwnershipHardeningSourceContractTest {
    private fun appRoot(): File = sequenceOf(File("."), File("app"))
        .firstOrNull { File(it, "src/main/java/com/bncam/core/engine/BnCameraManager.kt").isFile }
        ?: error("Cannot locate app module from ${File(".").absolutePath}")

    private fun manager(): String =
        File(appRoot(), "src/main/java/com/bncam/core/engine/BnCameraManager.kt").readText()

    @Test
    fun `pending session attempt owns ImageReaders before Camera2 callback`() {
        val source = manager()
        val snapshot = source.indexOf("registerPendingSessionOutputOwnership(")
        val create = source.indexOf("camera.createCaptureSession(sessionConfig)")
        assertTrue(snapshot >= 0)
        assertTrue(create > snapshot)
        assertTrue(source.contains("readerPendingSessionEpochs"))
        assertTrue(source.contains("pendingSessionOutputAttempts"))
        assertTrue(source.contains("pendingEpochs.isNullOrEmpty()"))
    }

    @Test
    fun `request targets and SessionConfiguration outputs share one immutable surface snapshot`() {
        val source = manager()
        val createStart = source.indexOf("private fun createCaptureSession(")
        val createEnd = source.indexOf("private data class SessionCloseTicket", createStart)
        assertTrue(createStart >= 0 && createEnd > createStart)
        val create = source.substring(createStart, createEnd)

        assertTrue(create.contains("sessionNamedSurfaces.forEach { (surface, _) -> requestBuilder.addTarget(surface) }"))
        assertTrue(create.contains("sessionNamedSurfaces.forEach { (surface, name) ->"))
        assertTrue(create.contains("val output = OutputConfiguration(surface)"))
        assertFalse(create.contains("if (imageReader?.surface != null) outputBindings.add"))
        assertFalse(create.contains("imageReader?.surface?.let {\n                    val readerOutput"))
    }

    @Test
    fun `stale same-generation session callbacks cannot mutate the replacement session`() {
        val source = manager()
        val callbackStart = source.indexOf("captureCallback = object : CameraCaptureSession.CaptureCallback()")
        val callbackEnd = source.indexOf("val stateCallback = object : CameraCaptureSession.StateCallback()", callbackStart)
        assertTrue(callbackStart >= 0 && callbackEnd > callbackStart)
        val callback = source.substring(callbackStart, callbackEnd)

        assertTrue(callback.contains("sessionEpoch != sessionConfigurationEpoch"))
        assertTrue(callback.contains("captureSession !== session"))
        assertTrue(callback.contains("STALE_CAPTURE_RESULT_DROPPED"))
        assertTrue(callback.contains("STALE_CAPTURE_BUFFER_LOST_DROPPED"))
        assertTrue(callback.contains("STALE_CAPTURE_FAILURE_DROPPED"))
    }

    @Test
    fun `device close acknowledgement releases ownership even when session onClosed is omitted`() {
        val source = manager()
        assertTrue(source.contains("acknowledgeSessionClosedByDevice("))
        assertTrue(source.contains("CAPTURE_SESSION_CLOSED_BY_DEVICE_ACK"))
        assertTrue(source.contains("releaseAllPendingSessionOutputOwnership(\"CameraDevice.onClosed:\$reason\")"))
        assertTrue(source.contains("Device onClosed is the only universal barrier proving those pending Surfaces"))
    }

    @Test
    fun `session configure timeout retains pending outputs instead of abandoning their Surfaces`() {
        val source = manager()
        assertTrue(source.contains("SESSION_CONFIGURATION_TIMEOUT_PENDING_OUTPUTS_RETAINED"))
        assertTrue(source.contains("pending output ownership is retained until callback or CameraDevice close"))
    }
}
