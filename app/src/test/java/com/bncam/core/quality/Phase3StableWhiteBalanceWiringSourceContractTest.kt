package com.bncam.core.quality

import java.io.File
import org.junit.Test
import org.junit.Assert.assertTrue

class Phase3StableWhiteBalanceWiringSourceContractTest {
    @Test
    fun `stable auto wb is observed continuously and snapshotted for raw capture`() {
        val manager = File("src/main/java/com/bncam/core/engine/BnCameraManager.kt").readText()
        val sensor = File("src/main/java/com/bncam/core/quality/SensorCalibration.kt").readText()
        val single = File("src/main/java/com/bncam/core/runners/SingleFrameRunner.kt").readText()
        val multi = File("src/main/java/com/bncam/core/runners/MultiFrameRunner.kt").readText()

        assertTrue(manager.contains("private val whiteBalanceStateEngine = WhiteBalanceStateEngine()"))
        assertTrue(manager.contains("updateAutoWhiteBalanceState(result, sessionGeneration)"))
        assertTrue(manager.contains("stableAutoWhiteBalanceSnapshotForActiveCamera()"))
        assertTrue(manager.contains("CaptureRequest.CONTROL_AWB_LOCK, false"))
        assertTrue(manager.contains("stableAutoWhiteBalance = stableAutoWhiteBalanceAtShutter"))
        assertTrue(sensor.contains("BnCam stable CaptureResult AWB"))
        assertTrue(sensor.contains("stableAutoAllowed"))
        assertTrue(single.contains("stableAutoWhiteBalance = stableAutoWhiteBalance"))
        assertTrue(multi.contains("stableAutoWhiteBalance = stableAutoWhiteBalance"))
    }
}
