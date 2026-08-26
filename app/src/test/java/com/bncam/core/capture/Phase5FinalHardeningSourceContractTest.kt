package com.bncam.core.capture

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Phase5FinalHardeningSourceContractTest {
    private val appDir: File = sequenceOf(File("."), File("app"))
        .firstOrNull { File(it, "src/main/java/com/bncam/core/capture/PredictiveAfTracker.kt").isFile }
        ?: error("Cannot locate app module")

    @Test
    fun predictiveHistoryUsesExplicitMonotonicTimestampGuard() {
        val source = File(
            appDir,
            "src/main/java/com/bncam/core/capture/PredictiveAfTracker.kt"
        ).readText()
        assertTrue(source.contains("val lastTimestampNs = history.lastOrNull()?.timestampNs"))
        assertTrue(source.contains("lastTimestampNs != null && frame.timestamp <= lastTimestampNs"))
        assertFalse(source.contains("timestampNs?.let { frame.timestamp <= it }"))
    }

    @Test
    fun focusAndFaceLabelsDescribeTheActualRuntimePolicy() {
        val source = File(
            appDir,
            "src/main/java/com/bncam/ui/screens/settings/ViewfinderScreen.kt"
        ).readText()
        assertTrue(source.contains("likely in-focus detail using focus confidence"))
        assertTrue(source.contains("AF target when available"))
        assertTrue(source.contains("during Frame Average metering"))
        assertTrue(source.contains("Spot, Center and Highlight metering remain authoritative"))
        assertTrue(source.contains("largest face; no depth claim"))
    }
    @Test
    fun focusDataPresentationIsCompactDistanceOnly() {
        val source = File(
            appDir,
            "src/main/java/com/bncam/ui/screens/capture/CameraScreen.kt"
        ).readText()
        val overlay = source.substringAfter("fun FocusOverlay(")
            .substringBefore("// ==========================================")
        assertTrue(overlay.contains("liveFocusDiopters: Float"))
        assertTrue(overlay.contains("text = \"DIST \$distanceText\""))
        assertTrue(overlay.contains("String.format(Locale.US, \"%.2fm\", 1f / liveFocusDiopters)"))
        assertFalse(overlay.contains("focusGuidance: FocusPeakingGuidance"))
        assertFalse(overlay.contains("CONTROL_AF_STATE_"))
        assertFalse(overlay.contains("CONF "))
        assertFalse(overlay.contains("LENS MOVING"))
        assertFalse(overlay.contains("AE/AF LOCK"))
    }

    @Test
    fun exactTouchTapFocusDoesNotRunManualLensSearchAtShutter() {
        val manager = File(
            appDir,
            "src/main/java/com/bncam/core/engine/BnCameraManager.kt"
        ).readText()
        assertFalse(manager.contains("RayFocusEngine.executeRayFocus"))
        assertFalse(manager.contains("PredictiveFocusSolver.executePredictiveFocus"))
        assertTrue(manager.contains("TOUCH_AF_CONTINUOUS_REPEATING"))
        assertTrue(manager.contains("TOUCH_AF_AUTO_START"))
    }

}
