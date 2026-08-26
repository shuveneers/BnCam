package com.bncam.ui.screens.capture

import android.graphics.Bitmap
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class TemporaryPreviewCaptureTest {

    private val appDir = File(System.getProperty("user.dir") ?: ".")

    @Test
    fun test1_cameraScreenNoLongerContainsCountDownLatch() {
        val cameraScreen = File(appDir, "src/main/java/com/bncam/ui/screens/capture/CameraScreen.kt").readText()
        assertFalse("CameraScreen must not use CountDownLatch", cameraScreen.contains("CountDownLatch"))
        assertFalse("CameraScreen must not use latch.await", cameraScreen.contains("latch.await"))
        assertTrue("CameraScreen must use TemporaryPreviewCapture", cameraScreen.contains("TemporaryPreviewCapture.captureTemporaryPreview"))
    }

    @Test
    fun test2_nullPreviewViewReturnsNullPath() {
        val path = kotlinx.coroutines.runBlocking {
            TemporaryPreviewCapture.awaitTemporaryPreviewBitmap(null)
        }
        assertNull(path)
    }

    @Test
    fun test3_temporaryPreviewCaptureFileStructureAndHelpers() {
        val helper = File(appDir, "src/main/java/com/bncam/ui/screens/capture/TemporaryPreviewCapture.kt").readText()
        assertTrue(helper.contains("suspendCancellableCoroutine"))
        assertTrue(helper.contains("withTimeoutOrNull"))
        assertTrue(helper.contains("File.createTempFile"))
        assertTrue(helper.contains("Bitmap.CompressFormat.JPEG"))
        assertTrue(helper.contains("file.delete()"))
        assertTrue(helper.contains("bitmap.recycle()"))
    }

    @Test
    fun test4_cameraScreenPassesPathToExecuteCaptureAndCleansUpOnException() {
        val cameraScreen = File(appDir, "src/main/java/com/bncam/ui/screens/capture/CameraScreen.kt").readText()
        assertTrue(cameraScreen.contains("temporaryPreviewPath = tempPreviewPath"))
        assertTrue(cameraScreen.contains("tempPreviewPath?.let"))
        assertTrue(cameraScreen.contains("java.io.File(path).takeIf { it.exists() }?.delete()"))
    }
}
