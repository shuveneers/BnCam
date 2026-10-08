package com.bncam

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.*
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/** Small physical capability probe; no rendered-image quality claims are made by this test. */
@RunWith(AndroidJUnit4::class)
class BnAutoSensorDeviceTest {
    @Test fun realizeLongRawSensorRequestsAndReportOtherLenses() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val thread = HandlerThread("BnAutoPhysicalProbe").apply { start() }
        val handler = Handler(thread.looper)
        val report = StringBuilder()
        var longFrames = 0
        try {
            val physicalIds = manager.cameraIdList.flatMap {
                manager.getCameraCharacteristics(it).physicalCameraIds.toList()
            }
            for (id in (manager.cameraIdList.toList() + physicalIds).distinct()) {
                val chars = manager.getCameraCharacteristics(id)
                val caps = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf()
                val time = chars.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
                val iso = chars.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
                val maxFrame = chars.get(CameraCharacteristics.SENSOR_INFO_MAX_FRAME_DURATION)
                report.appendLine("camera=$id physicalIds=${chars.physicalCameraIds} manual=${CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR in caps} exposure=$time iso=$iso maxFrame=$maxFrame analog=${chars.get(CameraCharacteristics.SENSOR_MAX_ANALOG_SENSITIVITY)}")
                if (CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR !in caps ||
                    time == null || iso == null || maxFrame == null) continue
                // Honor exposes standalone physical routes used by BnCam; exercise those exact
                // sensor modes instead of duplicating the logical rear camera's default sensor.
                if (chars.physicalCameraIds.isNotEmpty()) continue
                val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: continue
                for (format in listOf(ImageFormat.RAW10, ImageFormat.RAW_SENSOR)) {
                    val size = map.getOutputSizes(format)?.minByOrNull { it.width.toLong() * it.height } ?: continue
                    val reader = ImageReader.newInstance(size.width, size.height, format, 2)
                    val imageTimestamp = CompletableFuture<Long>()
                    reader.setOnImageAvailableListener({ r ->
                        r.acquireNextImage()?.let { image ->
                            imageTimestamp.complete(image.timestamp)
                            image.close()
                        }
                    }, handler)
                    var device: CameraDevice? = null
                    var session: CameraCaptureSession? = null
                    try {
                        val opened = CompletableFuture<CameraDevice>()
                        manager.openCamera(id, object : CameraDevice.StateCallback() {
                            override fun onOpened(camera: CameraDevice) { opened.complete(camera) }
                            override fun onDisconnected(camera: CameraDevice) { camera.close(); opened.completeExceptionally(IllegalStateException("disconnected $id")) }
                            override fun onError(camera: CameraDevice, error: Int) { camera.close(); opened.completeExceptionally(IllegalStateException("camera $id error $error")) }
                        }, handler)
                        device = opened.get(5, TimeUnit.SECONDS)
                        val configured = CompletableFuture<CameraCaptureSession>()
                        device.createCaptureSession(listOf(reader.surface), object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(value: CameraCaptureSession) { configured.complete(value) }
                            override fun onConfigureFailed(value: CameraCaptureSession) { configured.completeExceptionally(IllegalStateException("configure failed $id/$format")) }
                        }, handler)
                        session = configured.get(5, TimeUnit.SECONDS)
                        val requestedTime = 125_000_000L.coerceIn(time.lower, minOf(time.upper, maxFrame))
                        val streamMin = map.getOutputMinFrameDuration(format, size)
                        val requestedFrame = maxOf(requestedTime, streamMin).coerceAtMost(maxFrame)
                        val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
                        builder.addTarget(reader.surface)
                        builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                        if (android.os.Build.VERSION.SDK_INT >= 36) builder.set(CaptureRequest.CONTROL_AE_PRIORITY_MODE, CaptureRequest.CONTROL_AE_PRIORITY_MODE_OFF)
                        builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, requestedTime)
                        builder.set(CaptureRequest.SENSOR_SENSITIVITY, iso.lower)
                        builder.set(CaptureRequest.SENSOR_FRAME_DURATION, requestedFrame)
                        val completed = CompletableFuture<TotalCaptureResult>()
                        session.capture(builder.build(), object : CameraCaptureSession.CaptureCallback() {
                            override fun onCaptureCompleted(s: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) { completed.complete(result) }
                            override fun onCaptureFailed(s: CameraCaptureSession, request: CaptureRequest, failure: CaptureFailure) { completed.completeExceptionally(IllegalStateException("capture failed $id/$format ${failure.reason}")) }
                        }, handler)
                        val result = completed.get(8, TimeUnit.SECONDS)
                        val imageTs = imageTimestamp.get(5, TimeUnit.SECONDS)
                        val actualTime = result.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: 0L
                        val actualIso = result.get(CaptureResult.SENSOR_SENSITIVITY) ?: 0
                        val actualFrame = result.get(CaptureResult.SENSOR_FRAME_DURATION) ?: 0L
                        val matches = kotlin.math.abs(actualTime - requestedTime) <= maxOf(100_000L, requestedTime / 50) &&
                            kotlin.math.abs(actualIso - iso.lower) <= maxOf(1, iso.lower / 50) &&
                            actualFrame >= actualTime && actualFrame <= maxFrame &&
                            imageTs == result.get(CaptureResult.SENSOR_TIMESTAMP)
                        report.appendLine("camera=$id format=$format size=$size requestedNs=$requestedTime actualNs=$actualTime requestedIso=${iso.lower} actualIso=$actualIso requestedFrame=$requestedFrame actualFrame=$actualFrame exactPair=${imageTs == result.get(CaptureResult.SENSOR_TIMESTAMP)} matches=$matches")
                        if (matches && actualTime > 66_666_667L) longFrames++
                        assertTrue(report.toString(), matches)
                    } finally {
                        session?.close()
                        device?.close()
                        reader.close()
                    }
                }
            }
            assertTrue("No advertised long RAW exposure realized\n$report", longFrames > 0)
        } finally {
            File(context.filesDir, "bn_auto_sensor_probe.txt").writeText(report.toString())
            thread.quitSafely()
        }
    }
}
