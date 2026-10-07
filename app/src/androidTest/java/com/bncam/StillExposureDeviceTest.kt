package com.bncam

import android.Manifest
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.*
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Range
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CompletableFuture
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/** HAL capability proof, separate from the BnCam flash/ZSL routing smoke tests. */
@RunWith(AndroidJUnit4::class)
class StillExposureDeviceTest {

    @Test fun dedicatedRawStillCanExpose100msAfter30fpsPreview() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.grantRuntimePermission(instrumentation.targetContext.packageName, Manifest.permission.CAMERA)
        val cameraId = requireNotNull(InstrumentationRegistry.getArguments().getString("cameraId")) {
            "Select the verified device camera explicitly with instrumentation argument cameraId"
        }
        val manager = instrumentation.targetContext.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val characteristics = manager.getCameraCharacteristics(cameraId)
        val map = requireNotNull(characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP))
        val rawSize = requireNotNull(map.getOutputSizes(ImageFormat.RAW_SENSOR))
            .filter { it.width.toLong() * it.height <= 13_000_000L }.maxBy { it.width.toLong() * it.height }
        val previewSize = requireNotNull(map.getOutputSizes(ImageFormat.YUV_420_888))
            .filter { it.width <= 1280 && it.height <= 960 }.minBy { abs(it.width * it.height - 640 * 480) }
        val ranges = requireNotNull(characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES))
        val previewFps = ranges.filter { it.upper == 30 }.minBy { it.lower }
        val thread = HandlerThread("BnCamStillExposureProof").apply { start() }
        val handler = Handler(thread.looper)
        val raw = ImageReader.newInstance(rawSize.width, rawSize.height, ImageFormat.RAW_SENSOR, 3)
        val preview = ImageReader.newInstance(previewSize.width, previewSize.height, ImageFormat.YUV_420_888, 3)
        val timestamps = LinkedBlockingQueue<Long>(32)
        raw.setOnImageAvailableListener({ reader -> reader.acquireNextImage()?.use { timestamps.offer(it.timestamp) } }, handler)
        preview.setOnImageAvailableListener({ reader -> reader.acquireLatestImage()?.close() }, handler)
        var device: CameraDevice? = null
        var session: CameraCaptureSession? = null
        try {
            val opened = CompletableFuture<CameraDevice>()
            manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) { opened.complete(camera) }
                override fun onDisconnected(camera: CameraDevice) { camera.close(); opened.completeExceptionally(IllegalStateException("camera disconnected")) }
                override fun onError(camera: CameraDevice, error: Int) { camera.close(); opened.completeExceptionally(IllegalStateException("camera error $error")) }
            }, handler)
            device = opened.get(10, TimeUnit.SECONDS)
            val configured = CompletableFuture<CameraCaptureSession>()
            device.createCaptureSession(listOf(raw.surface, preview.surface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(value: CameraCaptureSession) { configured.complete(value) }
                override fun onConfigureFailed(value: CameraCaptureSession) { configured.completeExceptionally(IllegalStateException("RAW/preview configuration failed")) }
            }, handler)
            session = configured.get(10, TimeUnit.SECONDS)
            val firstPreview = CompletableFuture<TotalCaptureResult>()
            val repeating = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(preview.surface)
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, previewFps)
            }.build()
            session.setRepeatingRequest(repeating, object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(s: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) { firstPreview.complete(result) }
            }, handler)
            val previewResult = firstPreview.get(10, TimeUnit.SECONDS)
            val stillResult = CompletableFuture<TotalCaptureResult>()
            val requestedExposure = 100_000_000L
            val still = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                addTarget(raw.surface)
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, null)
                set(CaptureRequest.SENSOR_SENSITIVITY, 100)
                set(CaptureRequest.SENSOR_EXPOSURE_TIME, requestedExposure)
                set(CaptureRequest.SENSOR_FRAME_DURATION, 110_000_000L)
            }.build()
            assertNull(still.get(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE))
            session.capture(still, object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(s: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) { stillResult.complete(result) }
                override fun onCaptureFailed(s: CameraCaptureSession, request: CaptureRequest, failure: CaptureFailure) { stillResult.completeExceptionally(IllegalStateException("still capture failed ${failure.reason}")) }
            }, handler)
            val result = stillResult.get(10, TimeUnit.SECONDS)
            val exposure = requireNotNull(result.get(CaptureResult.SENSOR_EXPOSURE_TIME))
            val duration = requireNotNull(result.get(CaptureResult.SENSOR_FRAME_DURATION))
            assertTrue("effective exposure=$exposure", abs(exposure - requestedExposure) < 2_000_000L)
            assertTrue("frame duration=$duration exposure=$exposure", duration >= exposure)
            assertEquals(result.get(CaptureResult.SENSOR_TIMESTAMP), timestamps.poll(10, TimeUnit.SECONDS))
            Log.i("BnCamStillExposureProof", "cameraId=$cameraId previewRequestedFps=$previewFps previewEffectiveFrameDurationNs=${previewResult.get(CaptureResult.SENSOR_FRAME_DURATION)} stillRequestedExposureNs=$requestedExposure stillEffectiveExposureNs=$exposure stillEffectiveIso=${result.get(CaptureResult.SENSOR_SENSITIVITY)} stillEffectiveFrameDurationNs=$duration rawTimestampMatched=true")
        } finally {
            session?.close(); device?.close(); raw.close(); preview.close()
            thread.quitSafely(); thread.join(2000)
        }
    }
}
