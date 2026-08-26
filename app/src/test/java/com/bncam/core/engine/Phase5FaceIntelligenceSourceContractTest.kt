package com.bncam.core.engine

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Phase5FaceIntelligenceSourceContractTest {
    private val appDir: File = sequenceOf(File("."), File("app"))
        .firstOrNull { File(it, "src/main/java/com/bncam/core/engine/BnCameraManager.kt").isFile }
        ?: error("Cannot locate app module")

    @Test
    fun faceFocusPriorityActuallyEnablesCamera2DetectionAndDoesNotDependOnSceneMode() {
        val source = File(appDir, "src/main/java/com/bncam/core/engine/BnCameraManager.kt").readText()
        val apply = source.substringAfter("private fun applyFaceDetectionMode(")
            .substringBefore("// METERING ENGINE")
        assertTrue(apply.contains("requestedForUi || priorityFocus"))
        assertTrue(apply.contains("STATISTICS_FACE_DETECT_MODE_FULL"))
        assertTrue(apply.contains("STATISTICS_FACE_DETECT_MODE_SIMPLE"))
        assertFalse(apply.contains("builder.set(\n                CaptureRequest.CONTROL_SCENE_MODE"))
    }

    @Test
    fun faceFocusTargetTracksDisappearanceInsteadOfLeavingAStaleRegion() {
        val source = File(appDir, "src/main/java/com/bncam/core/engine/BnCameraManager.kt").readText()
        val callback = source.substringAfter("val faces = result.get(CaptureResult.STATISTICS_FACES)")
            .substringBefore("// 3. ZSL METADATA")
        assertTrue(callback.contains("lastFaceMeteringRect = nextFaceRect"))
        assertTrue(callback.contains("builder.set(CaptureRequest.CONTROL_AF_REGIONS, null)"))
        assertTrue(callback.contains("CONTROL_AF_MODE_CONTINUOUS_PICTURE"))
        assertTrue(callback.contains("apparent-proximity proxy"))
    }

    @Test
    fun viewfinderSettingChangesReachTheLiveCameraManager() {
        val screen = File(appDir, "src/main/java/com/bncam/ui/screens/capture/CameraScreen.kt").readText()
        assertTrue(screen.contains("LaunchedEffect(faceDetection, facePriorityFocus)"))
        assertTrue(screen.contains("bnCameraManager.setFaceIntelligence("))
        assertFalse(screen.contains("facePriorityAe"))
    }
}
