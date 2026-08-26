package com.bncam.core.capture

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Regression guard for the exact-touch Camera2 autofocus architecture. */
class Phase5VerifiedAfSourceContractTest {
    private val appDir: File = sequenceOf(File("."), File("app"))
        .firstOrNull { File(it, "src/main/java/com/bncam/core/engine/BnCameraManager.kt").isFile }
        ?: error("Cannot locate app module")

    @Test
    fun explicitTapUsesPhysicalGeometryWithoutSemanticRelocation() {
        val manager = File(appDir, "src/main/java/com/bncam/core/engine/BnCameraManager.kt").readText()
        val policy = File(appDir, "src/main/java/com/bncam/core/capture/BnTouchFocusPolicy.kt").readText()
        assertTrue(manager.contains("activePipelineIdentity?.physicalCameraId"))
        assertTrue(manager.contains("BnTouchFocusPolicy.map"))
        assertTrue(policy.contains("SENSOR_INFO_PIXEL_ARRAY_SIZE"))
        assertTrue(policy.contains("REGION_FRACTION = 1f / 8f"))
        assertFalse(manager.contains("TapFocusTargetResolver.resolveTarget"))
        assertFalse(manager.contains("RayFocusEngine.executeRayFocus"))
    }

    @Test
    fun touchTransactionCancelsBeforeApplyingPersistentTouchRegion() {
        val manager = File(appDir, "src/main/java/com/bncam/core/engine/BnCameraManager.kt").readText()
        val tap = manager.substringAfter("private fun tapToFocusAtOwned(")
            .substringBefore("// --- Tracking Variabelen ---")
        val cancel = tap.indexOf("TOUCH_AF_CANCEL")
        val continuous = tap.indexOf("TOUCH_AF_CONTINUOUS_REPEATING")
        val autoStart = tap.indexOf("TOUCH_AF_AUTO_START")
        assertTrue(cancel >= 0)
        assertTrue(continuous > cancel)
        assertTrue(autoStart > cancel)
        assertTrue(tap.contains("CONTROL_AF_MODE_CONTINUOUS_PICTURE"))
        assertTrue(tap.contains("CONTROL_AF_TRIGGER_START"))
        assertTrue(tap.contains("CONTROL_AF_TRIGGER_IDLE"))
    }

    @Test
    fun explicitTouchGuardBlocksBackgroundAfRegionOwnersUntilRestore() {
        val manager = File(appDir, "src/main/java/com/bncam/core/engine/BnCameraManager.kt").readText()
        assertTrue(manager.contains("private var explicitTouchFocusActive: Boolean = false"))
        assertTrue(manager.contains("if (explicitTouchFocusActive) return"))
        assertTrue(manager.contains("!_isAeAfLocked.value && !explicitTouchFocusActive"))
        val restore = manager.substringAfter("private fun restoreConfiguredAutoFocusOwned()")
            .substringBefore("fun triggerContinuousAutoFocus()")
        assertTrue(restore.contains("explicitTouchFocusActive = false"))
    }
}
