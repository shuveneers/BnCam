package com.bncam.ui.screens.capture

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PostPhase10ModeStripContractTest {
    private val appDir = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
        .firstOrNull { File(it, "src/main/java/com/bncam/ui/screens/capture/CameraScreen.kt").isFile }
        ?: error("Unable to locate app module")

    private fun read(path: String) = File(appDir, path).readText()

    @Test
    fun `mode strip contains Night Photo Portrait Video in that order`() {
        val camera = read("src/main/java/com/bncam/ui/screens/capture/CameraScreen.kt")
        assertTrue("listOf(ViewfinderMode.NIGHT, ViewfinderMode.PHOTO, ViewfinderMode.PORTRAIT, ViewfinderMode.VIDEO)" in camera)
        assertFalse("PORTRET" in camera)
        assertFalse("\"PRO\"" in camera)
    }

    @Test
    fun `fresh camera host starts Photo without persistent mode storage`() {
        val camera = read("src/main/java/com/bncam/ui/screens/capture/CameraScreen.kt")
        val mode = read("src/main/java/com/bncam/core/capture/ViewfinderMode.kt")
        assertTrue("mutableStateOf(ViewfinderMode.PHOTO)" in camera)
        assertTrue("NIGHT(\"Night\")" in mode)
        assertTrue("PHOTO(\"Photo\")" in mode)
        assertTrue("PORTRAIT(\"Portrait\")" in mode)
        assertTrue("VIDEO(\"Video\")" in mode)
    }

    @Test
    fun `mode strip uses resistant one step snapping`() {
        val camera = read("src/main/java/com/bncam/ui/screens/capture/CameraScreen.kt")
        assertTrue("val commitThresholdPx = with(density) { 58.dp.toPx() }" in camera)
        assertTrue("val dragResistance = 0.42f" in camera)
        assertTrue("val nextIndex = (targetIndex + direction).coerceIn(0, modes.size - 1)" in camera)
        assertTrue("Spring.DampingRatioNoBouncy" in camera)
    }
}
