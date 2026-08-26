package com.bncam.core.capture

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhotonStandardMeteringSourceContractTest {
    private val appDir: File = sequenceOf(File("."), File("app"))
        .firstOrNull { File(it, "src/main/cpp/CMakeLists.txt").isFile }
        ?: error("Cannot locate app module from ${File(".").absolutePath}")

    private fun source(relative: String): String = File(appDir, relative).readText()

    @Test
    fun `standard metering exposes only auto center average and spot`() {
        val policy = source("src/main/java/com/bncam/core/capture/CameraControlPolicy.kt")
        val quick = source("src/main/java/com/bncam/ui/screens/capture/ViewfinderQuickSettingsOverlay.kt")
        val settings = source("src/main/java/com/bncam/ui/screens/settings/ViewfinderScreen.kt")

        assertTrue(policy.contains("AUTO_DEFAULT_AE(\"Auto\")"))
        assertTrue(policy.contains("CENTER_WEIGHTED(\"Center Weighted\")"))
        assertTrue(policy.contains("FRAME_AVERAGE(\"Frame Average\")"))
        assertTrue(policy.contains("SPOT(\"Spot\")"))
        assertFalse(policy.contains("EVALUATIVE_HIGHLIGHT_PROTECT"))
        assertTrue(quick.contains("MeteringMode.AUTO_DEFAULT_AE"))
        assertTrue(settings.contains("listOf(\"Auto\", \"Center Weighted\", \"Frame Average\", \"Spot\")"))
    }

    @Test
    fun `manager restores template AE for auto and applies metering to still requests`() {
        val manager = source("src/main/java/com/bncam/core/engine/BnCameraManager.kt")

        assertTrue(manager.contains("initialAeMeteringRegions = requestBuilder"))
        assertTrue(manager.contains("builder.set(CaptureRequest.CONTROL_AE_REGIONS, initialRegions)"))
        assertTrue(manager.contains("applyMeteringPolicy(stillBuilder)"))
        assertTrue(manager.contains("applyMeteringPolicy(builder)"))
        assertTrue(manager.contains("activeTapAeRegion"))
        assertTrue(manager.contains("touch_focus_override"))
        assertTrue(manager.contains("physicalAeWritable"))
        assertTrue(manager.contains("domain=physical:\$activePhysicalId"))
        assertTrue(manager.contains("builder.setPhysicalCameraKey("))
        assertTrue(manager.contains("val touchAeRegionApplied = !manualExposureActive && maxAeRegions > 0"))
        assertFalse(manager.contains("recalculateAe"))
    }

    @Test
    fun `legacy hidden auto EV controller is removed from production`() {
        val manager = source("src/main/java/com/bncam/core/engine/BnCameraManager.kt")
        val stats = source("src/main/java/com/bncam/core/capture/ExposureStatistics.kt")

        assertFalse(manager.contains("liveMeteringAutoEv"))
        assertFalse(manager.contains("CameraAutoExposurePolicy"))
        assertFalse(stats.contains("CameraAutoExposurePolicy"))
        assertTrue(manager.contains("hiddenAutoEv=disabled"))
    }
}
