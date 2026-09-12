package com.bncam.core.engine

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LensSwitchRecoverySourceContractTest {
    private fun appRoot(): File = sequenceOf(File("."), File("app"))
        .firstOrNull { File(it, "src/main/java/com/bncam/core/engine/BnCameraManager.kt").isFile }
        ?: error("Cannot locate app module from ${File(".").absolutePath}")

    @Test
    fun `public physical ids always retain their direct CameraDevice route`() {
        val manager = File(
            appRoot(),
            "src/main/java/com/bncam/core/engine/BnCameraManager.kt"
        ).readText()
        val route = manager.substringAfter("fun resolveCameraDeviceRoute(cameraId: String)")
            .substringBefore("private fun findLogicalParentCameraId")

        val publicDirectBranch = route.substringAfter(
            "CameraDeviceRoutePolicy.shouldOpenDirectly(cameraId, directIds)"
        ).substringBefore("when (hiddenCameraRouteQualifications")

        assertTrue(publicDirectBranch.contains("logicalCameraId = cameraId"))
        assertTrue(publicDirectBranch.contains("physicalCameraId = null"))
        assertTrue(publicDirectBranch.contains("routeKind = CameraRouteKind.PUBLIC_DIRECT"))
        assertFalse(publicDirectBranch.contains("findLogicalParentCameraId"))
    }

    @Test
    fun `device close acknowledgement releases direct switch without session timeout`() {
        val manager = File(
            appRoot(),
            "src/main/java/com/bncam/core/engine/BnCameraManager.kt"
        ).readText()
        val hardClose = manager.substringAfter("private suspend fun closeCameraOwned(")
            .substringBefore("private fun enqueueCameraClose(")

        assertTrue(hardClose.indexOf("awaitCameraDeviceClosed") < hardClose.indexOf("awaitCaptureSessionClosed"))
        assertTrue(hardClose.contains("deviceTicket != null && hardwareSettled"))
        assertTrue(hardClose.contains("CAPTURE_SESSION_CLOSE_IMPLIED_BY_DEVICE_ACK"))
    }

    @Test
    fun `profile callback cannot label the previous sensor as the target lens`() {
        val manager = File(
            appRoot(),
            "src/main/java/com/bncam/core/engine/BnCameraManager.kt"
        ).readText()
        val configure = manager.substringAfter("fun configureViewfinderStream(")
            .substringBefore("fun setViewfinderStreamDirect")
        val cameraScreen = File(
            appRoot(),
            "src/main/java/com/bncam/ui/screens/capture/CameraScreen.kt"
        ).readText()

        assertTrue(configure.contains("traceIdentity?.selectedLensId == requestedLensId"))
        assertTrue(configure.contains("VIEWFINDER_TARGET_DEFERRED"))
        assertTrue(cameraScreen.contains("requestedLensId = cameraId"))
    }

    @Test
    fun `lens transition ends only on an authoritative target presentation`() {
        val cameraScreen = File(
            appRoot(),
            "src/main/java/com/bncam/ui/screens/capture/CameraScreen.kt"
        ).readText()
        val focusView = File(
            appRoot(),
            "src/main/java/com/bncam/ui/screens/capture/FocusPeakingView.kt"
        ).readText()

        assertTrue(focusView.contains("var onTargetFramePresented:"))
        assertTrue(focusView.contains("if (phase0TargetAuthorityAccepted)"))
        assertTrue(focusView.contains("if (pending.targetAuthorityAccepted)"))
        assertTrue(cameraScreen.contains("if (transitioningToLensId != lensId) return"))
        assertTrue(cameraScreen.contains("lensTransitionProgress.animateTo("))
        assertTrue(cameraScreen.contains("delay(1_800L)"))
        assertTrue(cameraScreen.contains("reason=target_frame_presented"))
        assertTrue(cameraScreen.contains("PathFillType.EvenOdd"))
        assertTrue(cameraScreen.contains("repeat(6) { bladeIndex"))
        assertFalse(cameraScreen.contains("val transitionScale ="))
    }
}
