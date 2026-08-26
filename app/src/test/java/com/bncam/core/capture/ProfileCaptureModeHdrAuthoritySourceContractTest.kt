package com.bncam.core.capture

import com.bncam.core.engine.CaptureStrategy
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileCaptureModeHdrAuthoritySourceContractTest {
    private val appDir: File = sequenceOf(File("."), File("app"))
        .firstOrNull { File(it, "src/main/java/com/bncam/core/engine/BnCameraManager.kt").isFile }
        ?: error("Cannot locate app module")

    @Test
    fun `legacy profile hdr value cannot request computational hdr`() {
        val resolution = CaptureAuthorityResolver.resolve(
            profileStrategy = CaptureStrategy.HDR_ENHANCED,
            viewfinderMode = ViewfinderMode.PHOTO,
            computationalHdrUserRequested = false,
            ultraHdrRequested = false,
            dedicatedFlashStill = false,
            producesJpeg = true
        )
        assertFalse(resolution.computationalHdrRequested)
        assertEquals(CaptureStrategy.SINGLE_FRAME_ZSL, resolution.profileRequestedStrategy)
        assertEquals(CaptureStrategy.SINGLE_FRAME_ZSL, resolution.effectiveCaptureStrategy)
        assertEquals("SingleFrameRunner", resolution.actualRunner)
    }

    @Test
    fun `app settings hdr toggle still overrides normal profile mode`() {
        val resolution = CaptureAuthorityResolver.resolve(
            profileStrategy = CaptureStrategy.MULTI_FRAME_ZSL,
            viewfinderMode = ViewfinderMode.PHOTO,
            computationalHdrUserRequested = true,
            ultraHdrRequested = false,
            dedicatedFlashStill = false,
            producesJpeg = true
        )
        assertTrue(resolution.computationalHdrRequested)
        assertEquals(CaptureStrategy.HDR_ENHANCED, resolution.effectiveCaptureStrategy)
        assertEquals("HdrEnhancedRunner", resolution.actualRunner)
    }

    @Test
    fun `profile editor does not expose hdr enhanced capture mode`() {
        val editor = File(
            appDir,
            "src/main/java/com/bncam/ui/screens/settings/lens_profiles/ProfileEditScreen.kt"
        ).readText()
        assertTrue(editor.contains(".filter { it != CaptureStrategy.HDR_ENHANCED }"))
    }

    @Test
    fun `manager does not infer hdr from profile capture strategy`() {
        val manager = File(appDir, "src/main/java/com/bncam/core/engine/BnCameraManager.kt").readText()
        assertTrue(manager.contains("val computationalHdrUserRequested = flowValue"))
        assertFalse(manager.contains("flowValue || liveCaptureStrategy == CaptureStrategy.HDR_ENHANCED"))
    }
}
