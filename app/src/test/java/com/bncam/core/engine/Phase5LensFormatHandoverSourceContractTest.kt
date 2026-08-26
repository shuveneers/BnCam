package com.bncam.core.engine

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Phase5LensFormatHandoverSourceContractTest {
    private fun appRoot(): File = sequenceOf(File("."), File("app"))
        .firstOrNull { File(it, "src/main/java/com/bncam/core/engine/BnCameraManager.kt").isFile }
        ?: error("Cannot locate app module from ${File(".").absolutePath}")

    private fun source(path: String): String = File(appRoot(), path).readText()

    @Test
    fun `RAW10 low-bit lane order is canonical across Kotlin native and Vulkan`() {
        val reader = source("src/main/java/com/bncam/core/isp/raw/RawSampleReaders.kt")
        val validator = source("src/main/java/com/bncam/core/quality/RawUnpackValidator.kt")
        val merger = source("src/main/cpp/DngMerger.cpp")
        val preview = source("src/main/cpp/RawPreview.cpp")
        val previewShader = source("src/main/cpp/vulkan/shaders/raw_preview.comp")
        val unpackShader = source("src/main/cpp/vulkan/shaders/raw10_unpack.comp")
        val native = source("src/main/cpp/native-lib.cpp")

        assertTrue(reader.contains("(b0 shl 2) or (b4 and 0x03)"))
        assertTrue(reader.contains("((p3 and 0x03) shl 6)"))
        assertTrue(validator.contains("val p0 = ((b0 and 0xFF) shl 2) or ((b4 and 0x03))"))
        assertTrue(merger.contains("(b4 >> (lane * 2u)) & 0x03"))
        assertTrue(preview.contains("const std::uint32_t lowShift = lane * 2u"))
        assertTrue(previewShader.contains("uint lowShift = lane * 2u"))
        assertTrue(unpackShader.contains("packedLow >> (lane * 2u)"))
        assertTrue(native.contains("(lowBits >> shift) & 0x03"))
        assertFalse(reader.contains("out[y * width + x] = (b0 shl 2) or ((b4 shr 6) and 0x03)"))
    }

    @Test
    fun `RAW geometry uses pre-correction active sensor coordinates`() {
        val transform = source("src/main/java/com/bncam/core/runtime/SensorToRawBufferTransform.kt")
        val imageUtils = source("src/main/java/com/bncam/core/engine/ImageUtils.kt")

        assertTrue(transform.contains("SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE"))
        assertTrue(transform.contains("val rawActiveRect = preCorrectionRect"))
        assertTrue(transform.contains("val isAlreadyActive = bufferWidth == rawActiveWidth && bufferHeight == rawActiveHeight"))
        assertTrue(transform.contains("val isFullPixelArray = bufferWidth == pixelArray.width() && bufferHeight == pixelArray.height()"))
        assertFalse(transform.contains("bufferWidth == rawActiveHeight && bufferHeight == rawActiveWidth"))
        assertTrue(imageUtils.contains("spatial.bufferCropLeft"))
        assertTrue(imageUtils.contains("val phaseX = spatial.cfaOffsetX and 1"))
        assertTrue(imageUtils.contains("val phaseY = spatial.cfaOffsetY and 1"))
    }

    @Test
    fun `DNG master dimensions follow dense cropped RAW16 payload`() {
        val single = source("src/main/java/com/bncam/core/runners/SingleFrameRunner.kt")
        val multi = source("src/main/java/com/bncam/core/runners/MultiFrameRunner.kt")
        val nativeBuffer = source("src/main/java/com/bncam/core/isp/raw/NativeRaw16Buffer.kt")

        assertTrue(nativeBuffer.contains("val width: Int"))
        assertTrue(nativeBuffer.contains("val height: Int"))
        assertTrue(single.contains("width = rawInput.width"))
        assertTrue(single.contains("height = rawInput.height"))
        assertTrue(single.contains("width = singleRawFrame?.width ?: frameWidth"))
        assertTrue(multi.contains("width = nativeMaster.width"))
        assertTrue(multi.contains("height = nativeMaster.height"))
    }

    @Test
    fun `legacy RAW10 Vulkan unpack has one writer per output word`() {
        val shader = source("src/main/cpp/vulkan/shaders/raw10_unpack.comp")
        val manager = source("src/main/cpp/vulkan/VulkanComputePipelineManager.cpp")
        val cmake = source("src/main/cpp/CMakeLists.txt")

        assertTrue(shader.contains("uint outWordIdx = gl_GlobalInvocationID.x"))
        assertTrue(shader.contains("outputWords[outWordIdx] = lo | hi"))
        assertFalse(shader.contains("outputWords[outWordIdx] = (outputWords[outWordIdx]"))
        assertTrue(manager.contains("const std::uint64_t outputWords"))
        assertTrue(manager.contains("getRaw10UnpackSpirvGenerated()"))
        assertTrue(cmake.contains("add_dependencies(bncam bncam_raw10_unpack_shader)"))
    }

    @Test
    fun `production RAW viewfinder never activates heavy CPU fallback`() {
        val rawPreview = source("src/main/cpp/RawPreview.cpp")
        val cmake = source("src/main/cpp/CMakeLists.txt")

        assertTrue(cmake.contains("option(BNCAM_ENABLE_RAW_PREVIEW_CPU_REFERENCE"))
        assertTrue(cmake.contains("CPU RAW preview reference path\" OFF"))
        assertTrue(rawPreview.contains("#if !defined(BNCAM_ENABLE_RAW_PREVIEW_CPU_REFERENCE)"))
        assertTrue(rawPreview.contains("GPU_PREVIEW_FAILED_NO_CPU_FALLBACK"))
        assertTrue(rawPreview.contains("return result;\n#else"))
    }

    @Test
    fun `physical producer replacement does not wait for retiring session acknowledgement`() {
        val manager = source("src/main/java/com/bncam/core/engine/BnCameraManager.kt")
        val reset = manager.substringAfter("val retiringImageReader = imageReader")
            .substringBefore("PIPELINE_RESET_END generation=\$resetGeneration configured=true")

        assertTrue(reset.contains("imageReader = ImageReader.newInstance("))
        assertTrue(reset.contains("awaitCaptureSessionConfiguration("))
        assertTrue(reset.contains("oldSessionCloseTicket?.closeBarrier?.isCompleted ?: true"))
        assertTrue(reset.contains("retireReadersWhenSessionCloses("))
        assertTrue(reset.contains("if (!configured)"))
        assertFalse(reset.contains("awaitCaptureSessionClosed("))
        assertFalse(reset.contains("if (!configured || !oldSessionClosed)"))
        assertTrue(manager.contains("onSessionReady?.invoke(retryReady)"))
        assertFalse(manager.contains("onSessionReady?.invoke(retryReady && failedSessionClosed)"))
    }

    @Test
    fun `display handover keeps last known good frame until exact target frame`() {
        val manager = source("src/main/java/com/bncam/core/engine/BnCameraManager.kt")
        val camera = source("src/main/java/com/bncam/ui/screens/capture/CameraScreen.kt")
        val view = source("src/main/java/com/bncam/ui/screens/capture/FocusPeakingView.kt")

        assertTrue(manager.contains("PendingViewfinderDisplayTransition"))
        assertTrue(manager.contains("targetViewfinderGeneration"))
        assertTrue(manager.contains("FIRST_VALID_RAW_VULKAN_FRAME"))
        assertTrue(manager.contains("surfaceTimestampNs < pending.firstProducerTimestampNs"))
        assertTrue(manager.contains("FIRST_VALID_YUV_SURFACE_FRAME"))
        assertTrue(camera.contains("val previewSurfaceOwnerKey = \"persistent_camera_viewfinder\""))
        val transition = camera.substringAfter("// 1B. CAMERA TARGET TRANSITION").substringBefore("// 2. SOFT RESET")
        assertFalse(transition.contains("clearPreviewForCameraTransition()"))
        assertTrue(view.contains("if (source == ViewfinderEffectiveSource.YUV)"))
        assertTrue(view.contains("submitRawPreviewFrame(frame: RawPreviewFrame)"))
        assertTrue(view.contains("onYuvFrameAvailable?.invoke(surfaceTimestampNs)"))
    }
}
