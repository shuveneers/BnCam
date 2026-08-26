package com.bncam.core.engine

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraSessionTransitionSourceContractTest {
    private fun appRoot(): File = sequenceOf(File("."), File("app"))
        .firstOrNull { File(it, "src/main/java/com/bncam/core/engine/BnCameraManager.kt").isFile }
        ?: error("Cannot locate app module from ${File(".").absolutePath}")

    private fun managerSource(): String =
        File(appRoot(), "src/main/java/com/bncam/core/engine/BnCameraManager.kt").readText()

    private fun cameraScreenSource(): String =
        File(appRoot(), "src/main/java/com/bncam/ui/screens/capture/CameraScreen.kt").readText()

    @Test
    fun `all producer transitions share one serialized owner`() {
        val source = managerSource()

        assertTrue(source.contains("private val pipelineTransitionMutex = Mutex()"))
        assertTrue(source.contains("): Boolean = pipelineTransitionMutex.withLock {\n        startCameraAndZslOwned("))
        assertTrue(source.contains("pipelineTransitionMutex.withLock"))
        assertTrue(source.contains("PipelineTransitionState.RECONFIGURING"))
        assertTrue(source.contains("PipelineTransitionState.LENS_SWITCHING"))
        assertTrue(source.contains("PipelineTransitionState.CLOSING"))
        assertTrue(source.contains("private suspend fun closeCameraOwned("))
        assertTrue(source.contains("private suspend fun performSoftResetPipeline"))
        assertTrue(source.contains("suspend fun handoverToLensWithinOpenLogicalCamera"))
        assertTrue(source.contains("PIPELINE_RESET_COALESCED"))
        assertFalse(source.contains("navigationSessionTransitionMutex"))
    }

    @Test
    fun `session close request and onClosed acknowledgement are separate contracts`() {
        val source = managerSource()

        assertTrue(source.contains("private data class SessionCloseTicket("))
        assertTrue(source.contains("private fun requestCaptureSessionClose("))
        assertTrue(source.contains("private suspend fun awaitCaptureSessionClosed("))
        assertTrue(source.contains("ticket.closeBarrier.await()"))
        assertTrue(source.contains("event=CAPTURE_SESSION_CLOSE_REQUESTED"))
        assertFalse(source.contains("closeCaptureSessionAndAwait("))
        assertFalse(source.contains("Camera2 session close initiated asynchronously"))

        val reset = source.substring(
            source.indexOf("val retiringImageReader = imageReader"),
            source.indexOf("PIPELINE_RESET_END generation=\$resetGeneration configured=true")
        )
        assertTrue(reset.contains("imageReader = ImageReader.newInstance("))
        assertTrue(reset.contains("oldSessionCloseTicket?.closeBarrier?.isCompleted ?: true"))
        assertTrue(reset.contains("retireReadersWhenSessionCloses("))
        assertTrue(reset.contains("if (!configured)"))
        assertFalse(reset.contains("awaitCaptureSessionClosed("))
        assertFalse(reset.contains("if (!configured || !oldSessionClosed)"))
    }

    @Test
    fun `camera device close barrier never fabricates an acknowledgement`() {
        val source = managerSource()

        assertTrue(source.contains("private val cameraDeviceCloseBarriers"))
        assertTrue(source.contains("private fun requestCameraDeviceClose("))
        assertTrue(source.contains("private suspend fun awaitCameraDeviceClosed("))
        assertTrue(source.contains("openingCameraDeviceCount"))
        assertTrue(source.contains("closingCameraDeviceCount"))
        assertTrue(source.contains("return settled"))
        assertFalse(source.contains("closingCameraDeviceCount.set(0)"))
        assertFalse(source.contains("Resetting counter for recovery"))
    }

    @Test
    fun `camera background teardown is non blocking and cannot self join`() {
        val source = managerSource()

        assertTrue(source.contains("threadToStop.quitSafely()"))
        assertTrue(source.contains("blockingJoin=false"))
        assertFalse(source.contains("backgroundThread?.join()"))
        assertFalse(source.contains("threadToStop.join("))
    }

    @Test
    fun `preview Surface is released only after manager retirement contract`() {
        val manager = managerSource()
        val screen = cameraScreenSource()

        assertTrue(manager.contains("fun closeCameraForSurfaceRelease("))
        assertTrue(manager.contains("event=PREVIEW_SURFACE_SAFE_TO_RELEASE"))
        assertTrue(screen.contains("bnCameraManager.closeCameraForSurfaceRelease("))
        assertTrue(screen.contains("camera2Handshake=ON_CLOSED_ACKNOWLEDGED"))
        assertFalse(screen.contains("bnCameraManager.closeCamera()"))
    }

    @Test
    fun `fast reattach requires exact Surface identity`() {
        val source = managerSource()

        assertTrue(source.contains("val exactSurfaceAlreadyAttached = lastPreviewSurface === previewSurface"))
        assertTrue(source.contains("activeSession != null && exactSurfaceAlreadyAttached"))
        assertFalse(source.contains("lastPreviewSurface = previewSurface\n            pipelineTransitionState = PipelineTransitionState.PREVIEW_ATTACHED\n            startWarmBufferWatchdog"))
    }

    @Test
    fun `physical sibling handover retains logical device but remains serialized`() {
        val source = managerSource()

        assertTrue(source.contains("previousIdentity.logicalCameraId == requestedIdentity.logicalCameraId"))
        assertTrue(source.contains("camera.id == requestedIdentity.logicalCameraId"))
        assertTrue(source.contains("PipelineTransitionState.LENS_SWITCHING"))
        assertTrue(source.contains("SAME_LOGICAL_PHYSICAL_LENS_HANDOVER"))
        assertTrue(source.contains("cameraDeviceRetained=true"))
    }
}
