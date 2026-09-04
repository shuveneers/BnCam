package com.bncam.core.capture

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultRawMeteringReferenceEpochSourceContractTest {
    private val appDir: File = sequenceOf(File("."), File("app"))
        .firstOrNull { File(it, "src/main/cpp/CMakeLists.txt").isFile }
        ?: error("Cannot locate app module from ${File(".").absolutePath}")

    private fun manager(): String =
        File(appDir, "src/main/java/com/bncam/core/engine/BnCameraManager.kt").readText()

    @Test
    fun `metering-domain change restarts default RAW reference from a future repeating epoch`() {
        val source = manager()
        val helper = source.substringAfter("private fun restartDefaultRawAeReferenceForMeteringChange")
            .substringBefore("private fun updateDefaultRawExposureRealizationTruth")

        assertTrue(helper.contains("clearDefaultRawShutterPriorityState(resetMotion = false)"))
        assertTrue(helper.contains("defaultRawShutterAwaitingAeBaseline = true"))
        assertTrue(helper.contains("controlRequestEpochTracker.currentSubmittedEpoch() + 1L"))
    }

    @Test
    fun `metering-style change cannot reuse the previous default RAW brightness reference`() {
        val source = manager()
        val metering = source.substringAfter("fun setMeteringStyle(style: String)")
            .substringBefore("private fun clearTouchAeOverride")

        assertTrue(metering.contains("restartDefaultRawAeReferenceForMeteringChange(\"METERING_STYLE_CHANGED\")"))
        assertTrue(
            metering.indexOf("restartDefaultRawAeReferenceForMeteringChange") <
                metering.indexOf("updatePreviewRepeatingRequest()")
        )
    }

    @Test
    fun `touch AE first reboots Camera2 AE before default RAW shutter-priority resumes`() {
        val source = manager()
        val tap = source.substringAfter("private fun tapToFocusAtOwned(")
            .substringBefore("private fun releaseTouchAeToStandardMeteringForTracking")

        assertTrue(tap.contains("restartDefaultRawAeReferenceForMeteringChange(\"TOUCH_AE_REGION_APPLIED\")"))
        assertTrue(tap.contains("applyExposurePolicy(request)"))
        assertTrue(
            tap.indexOf("TOUCH_AE_REGION_APPLIED") < tap.indexOf("CONTROL_AE_PRECAPTURE_TRIGGER_START")
        )
    }

    @Test
    fun `retiring touch AE reacquires global Auto metering reference`() {
        val source = manager()
        val restore = source.substringAfter("private fun restoreConfiguredAutoFocusOwned")
            .substringBefore("fun triggerContinuousAutoFocus")

        assertTrue(restore.contains("retiringTouchAeOverride"))
        assertTrue(restore.contains("restartDefaultRawAeReferenceForMeteringChange(\"TOUCH_AE_REGION_RETIRED\")"))
        assertTrue(
            restore.indexOf("applyMeteringPolicy(request)") <
                restore.indexOf("TOUCH_AE_REGION_RETIRED")
        )
        assertTrue(
            restore.indexOf("TOUCH_AE_REGION_RETIRED") <
                restore.indexOf("applyExposurePolicy(request)")
        )
    }
}
