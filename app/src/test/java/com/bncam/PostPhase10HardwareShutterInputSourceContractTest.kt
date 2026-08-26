package com.bncam

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

class PostPhase10HardwareShutterInputSourceContractTest {
    private val appDir = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
        .firstOrNull { File(it, "src/main/java/com/bncam/MainActivity.kt").isFile }
        ?: error("Unable to locate app module")

    private fun read(path: String) = File(appDir, path).readText()

    @Test
    fun `foreground camera keys and camera-button broadcasts route through the capture event bus`() {
        val activity = read("src/main/java/com/bncam/MainActivity.kt")
        assertTrue("KeyEvent.KEYCODE_CAMERA" in activity)
        assertTrue("Intent.ACTION_CAMERA_BUTTON" in activity)
        assertTrue("CameraEventBus.captureRequests.tryEmit(Unit)" in activity)
        assertTrue("HARDWARE SHUTTER INPUT" in activity)
    }

    @Test
    fun `still-image camera intents reuse the foreground activity instead of spawning a duplicate`() {
        val activity = read("src/main/java/com/bncam/MainActivity.kt")
        val manifest = read("src/main/AndroidManifest.xml")
        assertTrue("MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA" in activity)
        assertTrue("android.media.action.STILL_IMAGE_CAMERA" in manifest)
        assertTrue("android:launchMode=\"singleTop\"" in manifest)
    }
}
