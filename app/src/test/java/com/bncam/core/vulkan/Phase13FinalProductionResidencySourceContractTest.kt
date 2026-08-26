package com.bncam.core.vulkan

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Phase13FinalProductionResidencySourceContractTest {
    private val appDir: File = sequenceOf(File("."), File("app"))
        .firstOrNull { File(it, "src/main/cpp/CMakeLists.txt").isFile }
        ?: error("Cannot locate app module from ${File(".").absolutePath}")

    private fun source(relative: String): String = File(appDir, relative).readText()

    @Test
    fun rawProductionSuccessPrefersOpaqueResidentGenerationAndCpuNormalizeIsFailureOnly() {
        val native = source("src/main/cpp/native-lib.cpp")
        val render = native.substringAfter(
            "Java_com_bncam_core_engine_ImageUtils_renderJpegFromMasterNative"
        ).substringBefore("#if 0  // Retired giant RAW ISP diagnostic block.")

        val producer = render.indexOf("nativeRaw16ResidentGeneration(nativeRawAddress)")
        val normalize = render.indexOf("executeRawJpegNormalizeFromResidentRaw")
        val residentRender = render.indexOf("ResidentRawRenderInput residentRender")
        val cpuGate = render.indexOf("if (!rawResidentEntryUsed)")
        val cpuNormalize = render.lastIndexOf("normalizeRawForJpeg(")

        assertTrue(producer >= 0)
        assertTrue(normalize > producer)
        assertTrue(residentRender > normalize)
        assertTrue(cpuGate > residentRender)
        assertTrue(cpuNormalize > cpuGate)
        assertTrue(render.contains("NO_RESIDENT_RAW_PRODUCER_GENERATION"))
        assertTrue(render.contains("RAW_JPEG_RESIDENT_NORMALIZE_FAILED"))
    }

    @Test
    fun rawIspSuccessKeepsSpectraAndColourChainResidentUntilPublicationBoundary() {
        val isp = source("src/main/cpp/IspCore.cpp")
        val render = isp.substringAfter("std::vector<uint8_t> IspCore::renderRawBaselineJpeg(")
            .substringBefore("std::vector<uint8_t> IspCore::demosaicAndEncodeJpeg(")

        assertTrue(render.contains("const bool residentEntry"))
        assertTrue(render.contains("tryApplySpectraPass0VulkanResident"))
        assertTrue(render.contains("tryApplySpectraPass1VulkanResident"))
        assertTrue(render.contains("executeSpectraRawFinalizeFromRawNormalize"))
        assertTrue(render.contains("executeSpectraResidentDemosaic"))
        assertTrue(render.contains("executeSpectraResidentAwbCcm"))
        assertTrue(render.contains("executeSpectraResidentTone"))
        assertTrue(render.contains("executeResidentTonePostDemosaic"))
        assertTrue(isp.contains("executeSpectraResidentPostDemosaicFromTone"))
        assertTrue(render.contains("spectraFullFrameCpuReadbacks="))
        assertTrue(render.contains("spectraFullFrameCpuClones="))
        assertTrue(render.contains("cv::imencode(\".jpg\""))

        // All normal heavy CPU recovery helpers are explicitly failure-labelled.
        assertTrue(render.contains("CPU_RAW_FINALIZE_EXPLICIT_FAILSAFE"))
        assertTrue(render.contains("Failure-only CPU materialization"))
        assertTrue(render.contains("applyCpuToneAndVibrance"))
        assertTrue(render.contains("Explicit reference/failsafe when the resident publication chain was unavailable"))
    }

    @Test
    fun legacyCpuRawOverloadsAreReferenceOnlyAndNotUsedByProductionJni() {
        val isp = source("src/main/cpp/IspCore.cpp")
        val native = source("src/main/cpp/native-lib.cpp")
        val overloads = isp.substringAfter("std::vector<uint8_t> IspCore::demosaicAndEncodeJpeg(")

        assertTrue(overloads.contains("PHASE13_REFERENCE_ONLY"))
        assertTrue(overloads.contains("normalizeRawForJpeg("))
        assertTrue(overloads.contains("workingRaw.mosaic.clone()"))
        assertFalse(native.contains("IspCore::demosaicAndEncodeJpeg("))
    }

    @Test
    fun yuvSingleFrameUsesVulkanIspBeforeAnyCpuOpenCvFailsafe() {
        val native = source("src/main/cpp/native-lib.cpp")
        val start = native.indexOf("bool encodeNv21ToJpeg(\n")
        val end = native.indexOf("\n} // namespace", start)
        assertTrue(start >= 0 && end > start)
        val body = native.substring(start, end)

        val gpu = body.indexOf("executeYuvSingleFrameIsp(request)")
        val failureLog = body.indexOf("explicit CPU failsafe")
        val cpu = body.indexOf("return encodeNv21ToJpegCpuFallback(")
        assertTrue(gpu >= 0)
        assertTrue(failureLog > gpu)
        assertTrue(cpu > failureLog)
        assertTrue(body.contains("refusing anchor-only publication"))
    }

    @Test
    fun yuvMultiFrameOpenCvAlignmentIsOnlyInsideExplicitVulkanFailureBranch() {
        val native = source("src/main/cpp/native-lib.cpp")
        val successStart = native.indexOf("if (!yuvAlignmentCpuFallbackUsed && gpuAlignment.success")
        val fallbackStart = native.indexOf("} else {", successStart)
        val encode = native.indexOf("if (!anchorNv21.empty() && !encodeNv21ToJpeg(", fallbackStart)
        assertTrue(successStart >= 0 && fallbackStart > successStart && encode > fallbackStart)

        val success = native.substring(successStart, fallbackStart)
        val fallback = native.substring(fallbackStart, encode)
        assertFalse(success.contains("cv::phaseCorrelate"))
        assertFalse(success.contains("cv::warpAffine"))
        assertTrue(fallback.contains("Explicit bounded Vulkan failure fallback/reference path"))
        assertTrue(fallback.contains("cv::phaseCorrelate"))
        assertTrue(fallback.contains("cv::warpAffine"))
    }

    @Test
    fun rawUnpackAndRawMultiFrameCpuRoutesAreExplicitReferenceFallbacks() {
        val merger = source("src/main/cpp/DngMerger.cpp")
        assertTrue(merger.contains("Explicit bounded fallback only after the authoritative Vulkan route reported failure"))
        assertTrue(merger.contains("CPU_AHB_REFERENCE_FALLBACK"))
        assertTrue(merger.contains("CPU_REFERENCE_FALLBACK"))
        assertTrue(merger.contains("RAW multi-frame Vulkan failure -> explicit CPU reference fallback"))
        assertTrue(merger.contains("residentGenerationForPublication"))
        assertTrue(merger.contains("nativeRaw16ResidentGeneration(void* address)"))
    }

    @Test
    fun spectraOffLegacySharpenIsResidentAndCpuImplementationIsFailsafeOnly() {
        val isp = source("src/main/cpp/IspCore.cpp")
        val backend = source("src/main/cpp/vulkan/VulkanSpectraResidentPostDemosaicBackend.cpp")
        val shader = source("src/main/cpp/vulkan/shaders/spectra_post_demosaic_resident.comp")

        assertTrue(isp.contains("residentLegacySharpenRequested"))
        assertTrue(isp.contains("VULKAN_RESIDENT_LEGACY_EDGE_AWARE_SHARPEN_APPLIED"))
        assertTrue(isp.contains("sharpenBackend="))
        assertTrue(isp.contains("CPU_FAILSAFE"))
        assertTrue(backend.contains("SPECTRA_POST_DEMOSAIC_GPU_RESIDENT_LEGACY_SHARPEN_PUBLICATION_READY"))
        assertTrue(shader.contains("runLegacySrgbSharpen"))
    }

    @Test
    fun criticalNativeBackendSelectionContainsNoDeviceModelHardcodes() {
        val critical = listOf(
            "src/main/cpp/DngMerger.cpp",
            "src/main/cpp/IspCore.cpp",
            "src/main/cpp/native-lib.cpp",
            "src/main/cpp/vulkan/VulkanRuntime.cpp",
            "src/main/cpp/vulkan/VulkanComputePipelineManager.cpp"
        ).joinToString("\n") { source(it) }

        assertFalse(critical.contains("Build.MODEL"))
        assertFalse(critical.contains("BKQ-N49"))
        assertFalse(critical.contains("HONOR"))
        assertFalse(critical.contains("Honor Magic"))
    }
    @Test
    fun rawPreviewSupportedRouteStillAvoidsFullRgbaCpuRoundtrip() {
        val backend = source("src/main/cpp/vulkan/VulkanRawPreviewBackend.cpp")
        val view = source("src/main/java/com/bncam/ui/screens/capture/FocusPeakingView.kt")
        val rawPreview = source("src/main/cpp/RawPreview.cpp")

        assertTrue(backend.contains("ensureImportedOutputLocked"))
        assertTrue(rawPreview.contains("previewRequest.inputHardwareBuffer = buffer"))
        assertTrue(rawPreview.contains("GPU_PREVIEW_FAILED_NO_CPU_FALLBACK"))
        val handoff = view.substringAfter("val handoffSucceeded =")
            .substringBefore("val handoffError")
        val gpuBranch = handoff.substringBefore("} else {")
        assertFalse(gpuBranch.contains("glTexSubImage2D"))
        assertFalse(gpuBranch.contains("glTexImage2D"))
    }

}
